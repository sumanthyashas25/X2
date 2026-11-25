package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FirebaseStartup
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthConfigVerificationTest {

    @Test
    fun `google services generated web client id and firebase options are real and match provisioned project`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FirebaseStartup.ensureInitialized(context)

        val webClientId = context.getString(R.string.default_web_client_id)
        assertFalse("Web client ID must not be empty", webClientId.isBlank())
        assertFalse("Web client ID must not use dummy client ID", webClientId.contains("dummyclientid", ignoreCase = true))
        assertEquals("1078871317445-8hpi7fn9sr0ge2f1sg7jcgjp8lrocdq0.apps.googleusercontent.com", webClientId)

        val databaseId = context.getString(R.string.firestore_database_id)
        assertEquals("ai-studio-android-xteam-52a1be4f-f358-4190-91b3-cc887128007a", databaseId)

        val options = FirebaseApp.getInstance().options
        assertEquals("gen-lang-client-0410091917", options.projectId)
        assertNotEquals("AIzaSyDummyKeyForInitializationCheck00", options.apiKey)
        assertTrue(options.applicationId.isNotBlank())

        assertEquals(
            "https://gen-lang-client-0410091917-default-rtdb.firebaseio.com",
            BuildConfig.FIREBASE_RTDB_URL.trim()
        )
        assertEquals(
            "https://gen-lang-client-0410091917-default-rtdb.firebaseio.com",
            com.example.chat.realtime.RtdbPresenceManager.resolveRtdbUrl(
                configuredUrl = "https://gen-lang-client-0461366135-default-rtdb.firebaseio.com",
                activeProjectId = options.projectId,
                optionsDatabaseUrl = null
            )
        )
    }
}
