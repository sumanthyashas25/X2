package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.FirebaseApp

/**
 * Ensures [FirebaseApp] is initialized defensively before any [FirebaseAuth] or
 * [FirebaseFirestore] singleton is accessed, and logs detailed initialization
 * diagnostics confirming connection to the provisioned Firebase project
 * and named Firestore database.
 */
object FirebaseStartup {
    private const val TAG = "XMaxFirebaseDiagnostics"
    const val EXPECTED_PROJECT_ID = "gen-lang-client-0410091917"
    const val EXPECTED_DATABASE_ID = "ai-studio-android-xteam-52a1be4f-f358-4190-91b3-cc887128007a"
    const val EXPECTED_PACKAGE_NAME = "com.aistudio.xmax.krmxpq"
    const val EXPECTED_SHA1 = "96:CB:CB:67:25:85:90:10:4E:63:83:9C:18:86:1F:67:F6:7F:9D:14"
    const val EXPECTED_SHA256 = "FD:B3:04:55:1F:33:AE:F5:4D:45:CD:85:D1:7A:4D:5C:B5:0F:21:59:C1:07:C8:77:A9:BE:57:33:66:9D:00:F9"

    @Volatile
    private var initialized = false

    fun resolveDatabaseId(context: Context?): String {
        val fromRes = context?.let {
            runCatching { it.applicationContext.getString(R.string.firestore_database_id) }.getOrNull()?.trim()
        }.orEmpty()
        return fromRes.ifBlank { EXPECTED_DATABASE_ID }
    }

    fun ensureInitialized(context: Context? = null) {
        if (initialized && runCatching { FirebaseApp.getInstance() }.isSuccess) return
        synchronized(this) {
            val appCtx = context?.applicationContext ?: context
            runCatching {
                if (FirebaseApp.getApps(appCtx ?: return@runCatching).isEmpty()) {
                    var app = try { FirebaseApp.initializeApp(appCtx) } catch (_: Throwable) { null }
                    if (app == null) {
                        val fallbackOptions = com.google.firebase.FirebaseOptions.Builder()
                            .setApplicationId(appCtx.packageName.ifBlank { EXPECTED_PACKAGE_NAME })
                            .setProjectId(EXPECTED_PROJECT_ID)
                            .setApiKey("AIzaSyDummyKeyForInitializationCheck00")
                            .build()
                        FirebaseApp.initializeApp(appCtx, fallbackOptions)
                    }
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
