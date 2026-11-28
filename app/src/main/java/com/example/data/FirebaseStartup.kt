package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.FirebaseApp

data class FirebaseConfigStatus(
    val appInitialized: Boolean = false,
    val projectId: String = "",
    val databaseId: String = "",
    val applicationId: String = "",
    val hasValidApiKey: Boolean = false,
    val hasValidWebClientId: Boolean = false,
    val issueMessage: String? = null
) {
    val isHealthy: Boolean
        get() = appInitialized &&
            projectId == FirebaseStartup.EXPECTED_PROJECT_ID &&
            databaseId == FirebaseStartup.EXPECTED_DATABASE_ID &&
            hasValidApiKey &&
            hasValidWebClientId
}

/**
 * Ensures [FirebaseApp] is initialized defensively before any [FirebaseAuth] or
 * [FirebaseFirestore] singleton is accessed, and logs detailed initialization
 * diagnostics confirming connection to the provisioned Firebase project
 * and named Firestore database.
 */
object FirebaseStartup {
    private const val TAG = "XMaxFirebaseDiagnostics"
    const val EXPECTED_PROJECT_ID = "gen-lang-client-0137668580"
    const val EXPECTED_DATABASE_ID = "ai-studio-android-x2-2e94d6cb-2e92-47e1-acbe-98bc9d3528ed"
    const val EXPECTED_PACKAGE_NAME = "com.aistudio.xmax.krmxpq"
    const val EXPECTED_SHA1 = "3F:DE:14:FD:31:D8:5B:B2:4D:1C:CB:B9:85:7F:2E:34:CB:40:8C:73"
    const val EXPECTED_SHA256 = "4D:C6:95:6D:AD:8E:C1:F6:85:74:20:20:B1:D9:A8:8F:D3:6F:0D:27:56:F3:6D:20:7D:A0:87:90:74:A6:59:B8"

    @Volatile
    private var initialized = false

    fun resolveDatabaseId(context: Context?): String {
        val fromRes = context?.let {
            runCatching { it.applicationContext.getString(R.string.firestore_database_id) }.getOrNull()?.trim()
        }.orEmpty()
        return fromRes.ifBlank { EXPECTED_DATABASE_ID }
    }

    fun inspectConfig(context: Context?): FirebaseConfigStatus {
        val appCtx = context?.applicationContext ?: context
        if (appCtx != null) {
            ensureInitialized(appCtx)
        }
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull()
        val options = app?.options
        val projectId = options?.projectId.orEmpty()
        val apiKey = options?.apiKey.orEmpty()
        val appId = options?.applicationId.orEmpty()
        val resolvedDbId = resolveDatabaseId(appCtx)
        val webClientId = appCtx?.let {
            runCatching { it.getString(R.string.default_web_client_id).trim() }.getOrNull()
        }.orEmpty()
        val hasValidApiKey = apiKey.isNotBlank() && !apiKey.contains("Dummy", ignoreCase = true)
        val hasValidWebClientId = webClientId.isNotBlank() && !webClientId.startsWith("100000000000-dummyclientid")

        val issue = when {
            app == null -> "FirebaseApp failed to initialize from google-services.json."
            projectId != EXPECTED_PROJECT_ID -> "Firebase project mismatch: $projectId (expected $EXPECTED_PROJECT_ID)."
            !hasValidApiKey -> "Firebase API key missing or placeholder in google-services.json."
            !hasValidWebClientId -> "Google Sign-In default_web_client_id missing or placeholder."
            resolvedDbId != EXPECTED_DATABASE_ID -> "Firestore database ID mismatch: $resolvedDbId."
            else -> null
        }

        return FirebaseConfigStatus(
            appInitialized = app != null,
            projectId = projectId,
            databaseId = resolvedDbId,
            applicationId = appId,
            hasValidApiKey = hasValidApiKey,
            hasValidWebClientId = hasValidWebClientId,
            issueMessage = issue
        )
    }

    fun ensureInitialized(context: Context? = null) {
        if (initialized && runCatching { FirebaseApp.getInstance() }.isSuccess) return
        synchronized(this) {
            val appCtx = context?.applicationContext ?: context ?: return
            runCatching {
                if (FirebaseApp.getApps(appCtx).isEmpty()) {
                    FirebaseApp.initializeApp(appCtx)
                }
                val app = FirebaseApp.getInstance()
                val options = app.options
                val resolvedDbId = resolveDatabaseId(appCtx)
                initialized = true

                Log.d(TAG, "==================================================")
                Log.d(TAG, "FIREBASE INITIALIZATION & DIAGNOSTICS REPORT")
                Log.d(TAG, "==================================================")
                Log.d(TAG, "App Package Name: ${appCtx.packageName} (Expected: $EXPECTED_PACKAGE_NAME)")
                Log.d(TAG, "Firebase Project ID: ${options.projectId} (Expected: $EXPECTED_PROJECT_ID)")
                Log.d(TAG, "Firebase App ID: ${options.applicationId}")
                Log.d(TAG, "Firestore Target Database ID: $resolvedDbId")
                Log.d(TAG, "Certificate SHA-1: $EXPECTED_SHA1")
                Log.d(TAG, "Certificate SHA-256: $EXPECTED_SHA256")
                Log.d(TAG, "==================================================")

                if (options.projectId != EXPECTED_PROJECT_ID) {
                    Log.w(TAG, "WARNING: Firebase Project ID [${options.projectId}] does not match expected [$EXPECTED_PROJECT_ID]!")
                }
            }.onFailure { err ->
                Log.e(TAG, "CRITICAL: FirebaseApp initialization failed: ${err.message}", err)
            }
        }
    }
}
