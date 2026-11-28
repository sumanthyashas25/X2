package com.example.model

import android.graphics.Bitmap

enum class CallConnectionHealth(
    val label: String,
    val isWarning: Boolean
) {
    CONNECTING("Connecting…", false),
    WAITING_FOR_PEER("Waiting for other party...", true),
    CONNECTED("Live • Encrypted", false),
    RECONNECTING("Reconnecting...", true),
    CONNECTION_LOST("Connection Lost", true),
    DISCONNECTED("Call Ended", false)
}

data class CallToastNotification(
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val isAlert: Boolean = false
)

sealed class CallState {
    object Idle : CallState()

    data class Incoming(
        val callId: String,
        val channelId: String,
        val callerUid: String,
        val callerName: String,
        val callType: CallType
    ) : CallState()

    data class Connecting(
        val callId: String,
        val channelId: String,
        val peerUid: String,
        val peerName: String,
        val callType: CallType
    ) : CallState()

    data class Active(
        val callId: String,
        val channelId: String,
        val peerUid: String,
        val peerName: String,
        val callType: CallType,
        val isVideo: Boolean,
        val isMuted: Boolean,
        val isCameraOff: Boolean,
        val remoteBitmap: Bitmap? = null
    ) : CallState()

    data class Disconnected(
        val callId: String,
        val reason: String
    ) : CallState()
}
