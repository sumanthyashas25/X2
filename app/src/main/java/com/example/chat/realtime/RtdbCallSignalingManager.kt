package com.example.chat.realtime

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.FirebaseStartup
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * Free-tier Firebase Realtime Database WebRTC Signaling Manager.
 * Scopes all call signaling strictly under `companies/{companyId}/calls/{callId}/signaling`
 * matching `database.rules.json` and resolving the configured RTDB URL safely.
 */
object RtdbCallSignalingManager {

    private const val TAG = "RtdbCallSignaling"

    fun buildSignalingPath(companyId: String, callId: String): String {
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        val safeCall = callId.trim().ifBlank { "unknown" }
        return "companies/$safeCompany/calls/$safeCall/signaling"
    }

    private fun resolveDb(context: Context? = null): FirebaseDatabase? {
        FirebaseStartup.ensureInitialized(context)
        return runCatching {
            val options = FirebaseApp.getInstance().options
            val url = RtdbPresenceManager.resolveRtdbUrl(
                configuredUrl = BuildConfig.FIREBASE_RTDB_URL,
                activeProjectId = options.projectId,
                optionsDatabaseUrl = options.databaseUrl
            )
            FirebaseDatabase.getInstance(url)
        }.onFailure { err ->
            Log.d(TAG, "RTDB signaling unavailable: ${err.message}")
        }.getOrNull()
    }

    private fun signalingRef(companyId: String, callId: String, context: Context? = null): DatabaseReference? {
        if (callId.isBlank()) return null
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        return resolveDb(context)?.reference
            ?.child("companies")
            ?.child(safeCompany)
            ?.child("calls")
            ?.child(callId.trim())
            ?.child("signaling")
    }

    suspend fun sendOffer(
        callId: String,
        sdp: String,
        sdpType: String,
        companyId: String = "xmax",
        callerUid: String = "",
        context: Context? = null
    ) {
        val ref = signalingRef(companyId, callId, context) ?: return
        runCatching {
            ref.onDisconnect().removeValue()
            ref.child("offer").setValue(
                mapOf(
                    "sdp" to sdp,
                    "type" to sdpType,
                    "callerUid" to callerUid,
                    "timestamp" to ServerValue.TIMESTAMP
                )
            ).await()
        }.onFailure { Log.e(TAG, "Failed to send SDP offer", it) }
    }

    suspend fun sendAnswer(
        callId: String,
        sdp: String,
        sdpType: String,
        companyId: String = "xmax",
        calleeUid: String = "",
        context: Context? = null
    ) {
        val ref = signalingRef(companyId, callId, context) ?: return
        runCatching {
            ref.child("answer").setValue(
                mapOf(
                    "sdp" to sdp,
                    "type" to sdpType,
                    "calleeUid" to calleeUid,
                    "timestamp" to ServerValue.TIMESTAMP
                )
            ).await()
        }.onFailure { Log.e(TAG, "Failed to send SDP answer", it) }
    }

    suspend fun addIceCandidate(
        callId: String,
        isCaller: Boolean,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        companyId: String = "xmax",
        context: Context? = null
    ) {
        val ref = signalingRef(companyId, callId, context) ?: return
        runCatching {
            val roleKey = if (isCaller) "caller" else "callee"
            ref.child("ice_candidates").child(roleKey).push().setValue(
                mapOf(
                    "candidate" to candidate,
                    "sdpMid" to sdpMid,
                    "sdpMLineIndex" to sdpMLineIndex,
                    "timestamp" to ServerValue.TIMESTAMP
                )
            ).await()
        }.onFailure { Log.e(TAG, "Failed to add ICE candidate", it) }
    }

    fun observeSignaling(
        callId: String,
        companyId: String = "xmax",
        context: Context? = null
    ): Flow<Map<String, Any>> {
        val ref = signalingRef(companyId, callId, context) ?: return flowOf(emptyMap())
        return callbackFlow {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<String, Any>()
                    snapshot.children.forEach { child ->
                        child.value?.let { map[child.key.orEmpty()] = it }
                    }
                    trySend(map)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Signaling error: ${error.message}")
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    /**
     * Observes RTDB `.info/connected` socket connectivity so UI immediately knows
     * when signaling / media transport drops ("Connection Lost").
     */
    fun observeConnectionState(context: Context? = null): Flow<Boolean> {
        val db = resolveDb(context) ?: return flowOf(false)
        val connectedRef = db.getReference(".info/connected")
        return callbackFlow {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    trySend(connected)
                }

                override fun onCancelled(error: DatabaseError) {
                    trySend(false)
                }
            }
            connectedRef.addValueEventListener(listener)
            awaitClose { connectedRef.removeEventListener(listener) }
        }
    }

    suspend fun cleanupSignaling(
        callId: String,
        companyId: String = "xmax",
        context: Context? = null
    ) {
        val ref = signalingRef(companyId, callId, context) ?: return
        runCatching {
            ref.removeValue().await()
        }
    }
}
