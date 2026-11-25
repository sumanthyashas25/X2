package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Represents the lifecycle state of Cloud Firestore initialization.
 * Firestore stays uninitialized until a user has completed authentication.
 */
sealed interface FirestoreInitState {
    data object AwaitingAuthentication : FirestoreInitState

    data class Initializing(
        val uid: String,
        val databaseId: String
    ) : FirestoreInitState

    data class Ready(
        val firestore: FirebaseFirestore,
        val databaseId: String,
        val uid: String,
        val initializedAtMillis: Long,
        val freeTierCacheBytes: Long
    ) : FirestoreInitState

    data class Failed(
        val message: String,
        val uid: String? = null,
        val retryable: Boolean = true
    ) : FirestoreInitState
}

/**
 * Manages post-authentication initialization of the named Cloud Firestore database
 * (`R.string.firestore_database_id`) using exclusively Spark free-tier capabilities:
 * - No eager Firestore reads or writes before authentication succeeds.
 * - Bounded local persistent cache ([SPARK_FREE_CACHE_BYTES]) to minimize billable network reads.
 * - Idempotent, mutex-guarded initialization per authenticated user UID.
 * - Clean session reset on sign-out.
 */
class FirestoreInitializationService internal constructor(
    context: Context,
    private val authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    private val firestoreFactory: (String) -> FirebaseFirestore = { dbId -> FirebaseFirestore.getInstance(dbId) }
) {
    private val appContext: Context = context.applicationContext
    private val initMutex = Mutex()

    private val _initState = MutableStateFlow<FirestoreInitState>(FirestoreInitState.AwaitingAuthentication)
    val initState: StateFlow<FirestoreInitState> = _initState.asStateFlow()

    @Volatile
    private var settingsAppliedDatabaseId: String? = null

    val isReady: Boolean
        get() = _initState.value is FirestoreInitState.Ready

    fun resolveDatabaseId(): String {
        val fromRes = runCatching { appContext.getString(R.string.firestore_database_id).trim() }
            .getOrNull()
            .orEmpty()
        return fromRes.ifBlank { FirebaseStartup.EXPECTED_DATABASE_ID }
    }

    fun currentReadyFirestore(): FirebaseFirestore? =
        (_initState.value as? FirestoreInitState.Ready)?.firestore

    fun isReadyForUser(uid: String?): Boolean {
        if (uid.isNullOrBlank()) return false
        val ready = _initState.value as? FirestoreInitState.Ready ?: return false
        return ready.uid == uid
    }

    /**
     * Initializes and configures the named Firestore instance strictly after [user]
     * is authenticated. Safe to call repeatedly for the same user (idempotent).
     */
    suspend fun initializeAfterAuth(user: FirebaseUser): Result<FirebaseFirestore> {
        val uid = user.uid.trim()
        if (uid.isEmpty()) {
            val err = IllegalStateException("Cannot initialize Firestore without an authenticated user UID.")
            _initState.value = FirestoreInitState.Failed(
                message = err.message ?: "Authentication required.",
                uid = null,
                retryable = false
            )
            return Result.failure(err)
        }

        val currentReady = _initState.value as? FirestoreInitState.Ready
        if (currentReady != null && currentReady.uid == uid) {
            return Result.success(currentReady.firestore)
        }

        return initMutex.withLock {
            val lockedReady = _initState.value as? FirestoreInitState.Ready
            if (lockedReady != null && lockedReady.uid == uid) {
                return@withLock Result.success(lockedReady.firestore)
            }

            val databaseId = resolveDatabaseId()
            _initState.value = FirestoreInitState.Initializing(uid = uid, databaseId = databaseId)

            try {
                FirebaseStartup.ensureInitialized(appContext)
                val firestore = firestoreFactory(databaseId)
                configureFreeTierSettings(firestore, databaseId)

                // Verify the authenticated ID token without forcing an unnecessary network call
                // when cached credentials are valid. If offline, allow local persistent cache access.
                runCatching { user.getIdToken(false).await() }
                    .onFailure { tokenErr ->
                        if (tokenErr is CancellationException) throw tokenErr
                        Log.w(TAG, "Cached token check deferred (offline or transient): ${tokenErr.message}")
                    }

                runCatching { firestore.enableNetwork().await() }
                    .onFailure { netErr ->
                        if (netErr is CancellationException) throw netErr
                        Log.d(TAG, "Firestore network enable deferred (continuing with offline cache): ${netErr.message}")
                    }

                NetworkConnectivityMonitor.init(appContext)

                val ready = FirestoreInitState.Ready(
                    firestore = firestore,
                    databaseId = databaseId,
                    uid = uid,
                    initializedAtMillis = System.currentTimeMillis(),
                    freeTierCacheBytes = SPARK_FREE_CACHE_BYTES
                )
                _initState.value = ready
                Log.i(TAG, "Firestore initialized after auth for uid=$uid on database=$databaseId (Spark free-tier cache=${SPARK_FREE_CACHE_BYTES / (1024 * 1024)}MB)")
                Result.success(firestore)
            } catch (ce: CancellationException) {
                if (_initState.value is FirestoreInitState.Initializing) {
                    _initState.value = FirestoreInitState.AwaitingAuthentication
                }
                throw ce
            } catch (e: FirebaseFirestoreException) {
                handleFirestoreError(e, OperationType.GET, "init/$databaseId")
                val msg = e.localizedMessage ?: "Failed to initialize Firestore database."
                _initState.value = FirestoreInitState.Failed(message = msg, uid = uid, retryable = true)
                Result.failure(e)
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, "init/$databaseId")
                val msg = e.localizedMessage ?: "Failed to initialize Firestore database."
                _initState.value = FirestoreInitState.Failed(message = msg, uid = uid, retryable = true)
                Result.failure(e)
            }
        }
    }

    suspend fun retryInitialization(user: FirebaseUser?): Result<FirebaseFirestore> {
        val activeUser = user ?: runCatching { authProvider().currentUser }.getOrNull()
        if (activeUser == null) {
            onSignedOut()
            return Result.failure(IllegalStateException("Sign in before initializing Firestore."))
        }
        if (_initState.value is FirestoreInitState.Failed) {
            _initState.value = FirestoreInitState.AwaitingAuthentication
        }
        return initializeAfterAuth(activeUser)
    }

    /**
     * Returns the initialized [FirebaseFirestore] for an active authenticated session.
     * Throws [IllegalStateException] if called while signed out.
     */
    fun requireFirestore(): FirebaseFirestore {
        val ready = _initState.value as? FirestoreInitState.Ready
        if (ready != null) return ready.firestore

        val activeUser = runCatching { authProvider().currentUser }.getOrNull()
        if (activeUser == null || activeUser.uid.isBlank()) {
            throw IllegalStateException("Authenticated user required before accessing Firestore.")
        }

        val databaseId = resolveDatabaseId()
        FirebaseStartup.ensureInitialized(appContext)
        val firestore = firestoreFactory(databaseId)
        configureFreeTierSettings(firestore, databaseId)
        return firestore
    }

    fun onSignedOut() {
        _initState.value = FirestoreInitState.AwaitingAuthentication
        Log.d(TAG, "Firestore session state reset after sign-out.")
    }

    private fun configureFreeTierSettings(firestore: FirebaseFirestore, databaseId: String) {
        if (settingsAppliedDatabaseId == databaseId) return
        synchronized(this) {
            if (settingsAppliedDatabaseId == databaseId) return
            try {
                val cacheSettings = PersistentCacheSettings.newBuilder()
                    .setSizeBytes(SPARK_FREE_CACHE_BYTES)
                    .build()
                firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(cacheSettings)
                    .build()
            } catch (_: IllegalStateException) {
                // Settings are immutable once Firestore has executed its first operation.
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to apply custom Firestore settings: ${t.message}")
            }
            settingsAppliedDatabaseId = databaseId
        }
    }

    companion object {
        private const val TAG = "XMaxFirestoreInit"

        // 50 MB local offline cache reduces repeat Firestore reads to keep usage within the Spark free tier.
        const val SPARK_FREE_CACHE_BYTES: Long = 50L * 1024L * 1024L

        @Volatile
        private var instance: FirestoreInitializationService? = null

        fun getInstance(context: Context): FirestoreInitializationService {
            return instance ?: synchronized(this) {
                instance ?: FirestoreInitializationService(context).also { instance = it }
            }
        }
    }
}
