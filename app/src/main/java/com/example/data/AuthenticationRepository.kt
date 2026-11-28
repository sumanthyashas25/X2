package com.example.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.domain.CatalogueSeedData
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

sealed interface AuthSessionStatus {
    data object Initializing : AuthSessionStatus
    data object SignedOut : AuthSessionStatus
    data class SignedIn(val user: FirebaseUser) : AuthSessionStatus
}

sealed interface GoogleSignInResult {
    data class Success(val user: FirebaseUser) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Failure(val message: String) : GoogleSignInResult
}

data class PhoneVerificationSession(
    val phoneNumber: String = "",
    val verificationId: String? = null,
    val resendToken: PhoneAuthProvider.ForceResendingToken? = null,
    val inProgress: Boolean = false,
    val errorMessage: String? = null
)

enum class UserRoleType(val roleKey: String, val displayLabel: String) {
    SUPER_ADMIN("super_admin", "Super Admin"),
    STANDARD_MEMBER("standard_member", "Agency Team Member"),
    UNAUTHENTICATED("unauthenticated", "Signed Out")
}

data class VerifiedUserRole(
    val role: UserRoleType = UserRoleType.UNAUTHENTICATED,
    val email: String = "",
    val uid: String = "",
    val isSuperAdmin: Boolean = false,
    val isAuthenticated: Boolean = false
)

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Manages Firebase Authentication state, Jetpack Credential Manager Google Sign-In credentials,
 * Firebase Phone Auth verification, and streams real-time session status updates.
 */
class AuthenticationRepository(
    context: Context? = null,
    providedAuth: FirebaseAuth? = null
) {
    private val appContext: Context? = context?.applicationContext

    private val firebaseAuth: FirebaseAuth by lazy {
        providedAuth ?: run {
            FirebaseStartup.ensureInitialized(appContext)
            FirebaseAuth.getInstance()
        }
    }

    init {
        if (appContext != null) {
            FirebaseStartup.ensureInitialized(appContext)
        }
    }

    companion object {
        private const val TAG = "XMaxAuthRepository"
        const val SUPER_ADMIN_EMAIL = CatalogueSeedData.DEFAULT_SUPER_ADMIN_EMAIL

        fun verifyUserRole(
            email: String?,
            uid: String = "",
            isAuthenticated: Boolean = true
        ): VerifiedUserRole {
            if (!isAuthenticated) {
                return VerifiedUserRole(
                    role = UserRoleType.UNAUTHENTICATED,
                    email = "",
                    uid = "",
                    isSuperAdmin = false,
                    isAuthenticated = false
                )
            }
            val normalizedEmail = email.orEmpty().trim().lowercase()
            val superAdmin = CatalogueSeedData.isSuperAdminEmail(normalizedEmail)
            return VerifiedUserRole(
                role = if (superAdmin) UserRoleType.SUPER_ADMIN else UserRoleType.STANDARD_MEMBER,
                email = normalizedEmail,
                uid = uid,
                isSuperAdmin = superAdmin,
                isAuthenticated = true
            )
        }

        fun verifyUserRole(user: FirebaseUser?): VerifiedUserRole {
            return if (user == null) {
                verifyUserRole(email = null, uid = "", isAuthenticated = false)
            } else {
                verifyUserRole(email = user.email, uid = user.uid, isAuthenticated = true)
            }
        }
    }

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    /**
     * Real-time stream of the current FirebaseUser (null when signed out).
     */
    val authStateFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser)
        }
        firebaseAuth.addAuthStateListener(listener)
        trySend(firebaseAuth.currentUser)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    /**
     * Real-time verified user role stream distinguishing `super_admin` (xdotmax@gmail.com)
     * from `standard_member` agency team members.
     */
    val verifiedUserRoleFlow: Flow<VerifiedUserRole> = authStateFlow
        .map { user -> verifyUserRole(user) }
        .distinctUntilChanged()

    fun currentVerifiedUserRole(): VerifiedUserRole = verifyUserRole(currentUser)

    /**
     * Stream of [AuthSessionStatus] allowing navigation & UI layers to react to login/logout events.
     */
    val sessionStatusFlow: Flow<AuthSessionStatus> = authStateFlow
        .map { user ->
            if (user == null) AuthSessionStatus.SignedOut else AuthSessionStatus.SignedIn(user)
        }
        .distinctUntilChanged { old, new ->
            when {
                old is AuthSessionStatus.SignedIn && new is AuthSessionStatus.SignedIn ->
                    old.user.uid == new.user.uid && old.user.email == new.user.email
                else -> old == new
            }
        }

    fun requireUserId(): String =
        firebaseAuth.currentUser?.uid ?: throw IllegalStateException("Authenticated user required.")

    private fun resolveWebClientId(context: Context): String? =
        runCatching { context.getString(R.string.default_web_client_id).trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith("100000000000-dummyclientid") }

    suspend fun attemptAutoSignIn(context: Context): FirebaseUser? {
        val existing = firebaseAuth.currentUser
        if (existing != null) return existing
        val activity = context.findActivity() ?: return null
        val clientId = resolveWebClientId(activity) ?: return null
        return try {
            val credentialManager = CredentialManager.create(activity)
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(activity, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                firebaseAuth.signInWithCredential(firebaseCred).await().user
            } else {
                null
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.d(TAG, "Auto sign-in skipped or unavailable: ${t.message}")
            null
        }
    }

    suspend fun signInWithGoogle(activityContext: Context): GoogleSignInResult {
        return try {
            val activity = activityContext.findActivity()
                ?: return GoogleSignInResult.Failure("Unable to open Google account picker from this context. Reopen the app and try again.")
            val clientId = resolveWebClientId(activity)
                ?: return GoogleSignInResult.Failure("Google Sign-In configuration missing: default_web_client_id not found.")
            val credentialManager = CredentialManager.create(activity)
            val signInOption = GetSignInWithGoogleOption.Builder(
                serverClientId = clientId
            ).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()
            val result = credentialManager.getCredential(activity, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                val user = firebaseAuth.signInWithCredential(firebaseCred).await().user
                if (user != null) {
                    GoogleSignInResult.Success(user)
                } else {
                    GoogleSignInResult.Failure("Google sign-in completed but Firebase user was null.")
                }
            } else {
                GoogleSignInResult.Failure("Unexpected credential type returned by Credential Manager.")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google sign-in cancelled by user: ${e.message}", e)
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credentials available: ${e.message}", e)
            GoogleSignInResult.Failure("No Google account found on this device. Add a Google account in Android Settings and try again.")
        } catch (e: GetCredentialException) {
            Log.e(TAG, "CredentialManager error (${e.type})", e)
            GoogleSignInResult.Failure(
                e.errorMessage?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Google sign-in failed (${e.type}). Verify Google Play Services and network connection."
            )
        } catch (e: FirebaseNetworkException) {
            Log.e(TAG, "Firebase network error during sign-in", e)
            GoogleSignInResult.Failure("Network error while contacting Firebase Authentication. Check your internet connection.")
        } catch (e: FirebaseAuthException) {
            Log.e(TAG, "FirebaseAuth error (${e.errorCode})", e)
            val friendly = when (e.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" -> "Google Sign-In provider is disabled in Firebase Authentication."
                "ERROR_USER_DISABLED" -> "This account has been disabled by an administrator."
                "ERROR_INVALID_CREDENTIAL" -> "Google sign-in credential was rejected by Firebase. Verify app SHA-1 and Web Client ID."
                else -> e.localizedMessage ?: "Firebase authentication failed (${e.errorCode})."
            }
            GoogleSignInResult.Failure(friendly)
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in unexpected error", e)
            GoogleSignInResult.Failure(e.message ?: "Sign-in failed.")
        }
    }

    fun startPhoneVerification(
        activity: Activity,
        phoneNumber: String,
        resendToken: PhoneAuthProvider.ForceResendingToken?,
        onCodeSent: (verificationId: String, token: PhoneAuthProvider.ForceResendingToken?) -> Unit,
        onAutoVerified: (FirebaseUser) -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    firebaseAuth.signInWithCredential(credential)
                        .addOnSuccessListener { res ->
                            val user = res.user
                            if (user != null) onAutoVerified(user)
                            else onFailure("Phone verification completed without a user session.")
                        }
                        .addOnFailureListener { err ->
                            onFailure(err.localizedMessage ?: "Phone sign-in failed.")
                        }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    Log.w(TAG, "Phone verification failed", e)
                    onFailure(e.localizedMessage ?: "Phone verification failed. Check E.164 format (+countrycode) and Firebase Phone Auth setup.")
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    onCodeSent(verificationId, token)
                }
            }

            val builder = PhoneAuthOptions.newBuilder(firebaseAuth)
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
            if (resendToken != null) {
                builder.setForceResendingToken(resendToken)
            }
            PhoneAuthProvider.verifyPhoneNumber(builder.build())
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to initiate phone verification", t)
            onFailure(t.localizedMessage ?: "Unable to start phone verification.")
        }
    }

    suspend fun signInWithPhoneOtp(verificationId: String, smsCode: String): GoogleSignInResult {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode.trim())
            val user = firebaseAuth.signInWithCredential(credential).await().user
            if (user != null) {
                GoogleSignInResult.Success(user)
            } else {
                GoogleSignInResult.Failure("Phone verification succeeded but Firebase user was null.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTP verification failed", e)
            GoogleSignInResult.Failure(e.localizedMessage ?: "Invalid or expired verification code.")
        }
    }

    suspend fun signOut(context: Context) {
        withContext(NonCancellable) {
            try {
                val resolvedContext = context.findActivity() ?: context
                CredentialManager.create(resolvedContext).clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "ClearCredentialState error: ${e.message}")
            }
            firebaseAuth.signOut()
        }
    }
}
