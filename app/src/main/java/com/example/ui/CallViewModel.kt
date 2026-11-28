package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat.realtime.LiveCallEngine
import com.example.chat.realtime.RtdbCallSignalingManager
import com.example.data.NetworkConnectivityMonitor
import com.example.model.CallConnectionHealth
import com.example.model.CallState
import com.example.model.CallToastNotification
import com.example.model.CallType
import com.example.model.ChatCallSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel managing real-time call lifecycle via [CallState], RTDB SDP/ICE signaling,
 * connection health monitoring ("Waiting for other party...", "Connection Lost"),
 * and visual in-call toast notifications.
 */
class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _connectionHealth = MutableStateFlow(CallConnectionHealth.CONNECTING)
    val connectionHealth: StateFlow<CallConnectionHealth> = _connectionHealth.asStateFlow()

    private val _toastNotification = MutableStateFlow<CallToastNotification?>(null)
    val toastNotification: StateFlow<CallToastNotification?> = _toastNotification.asStateFlow()

    private val _rtdbConnected = MutableStateFlow(true)
    val rtdbConnected: StateFlow<Boolean> = _rtdbConnected.asStateFlow()

    private var healthMonitorJob: Job? = null
    private var signalingObserverJob: Job? = null
    private var toastDismissJob: Job? = null
    private var rtdbStatusJob: Job? = null

    private var activeCallStartedAtMs: Long = 0L
    private var sentOfferForCallId: String = ""
    private var sentAnswerForCallId: String = ""
    private var currentCompanyId: String = "xmax"
    private var currentCallId: String = ""
    private var currentIsMuted: Boolean = false
    private var currentIsCameraOff: Boolean = false

    init {
        rtdbStatusJob = viewModelScope.launch {
            RtdbCallSignalingManager.observeConnectionState(application)
                .catch { emit(false) }
                .collectLatest { connected ->
                    _rtdbConnected.value = connected
                }
        }
    }

    fun startOutgoingCall(
        callId: String,
        channelId: String,
        peerUid: String,
        peerName: String,
        callType: CallType,
        companyId: String = "xmax"
    ) {
        currentCompanyId = companyId.trim().ifBlank { "xmax" }
        currentCallId = callId
        currentIsMuted = false
        currentIsCameraOff = callType == CallType.AUDIO
        activeCallStartedAtMs = 0L

        _callState.value = CallState.Connecting(
            callId = callId,
            channelId = channelId,
            peerUid = peerUid,
            peerName = peerName,
            callType = callType
        )
        setHealth(CallConnectionHealth.WAITING_FOR_PEER)
        showToast("Waiting for other party...", isAlert = false)

        if (callId.isNotBlank()) {
            publishCallerOfferIfNeeded(callId, peerUid, callType, currentCompanyId)
            startSignalingAndHealthMonitors(currentCompanyId, callId, isCaller = true)
        }
    }

    fun bindIncomingCall(
        session: ChatCallSession,
        companyId: String = "xmax"
    ) {
        currentCompanyId = companyId.trim().ifBlank { "xmax" }
        currentCallId = session.id
        val callType = if (session.callType == "AUDIO" || !session.videoEnabled) CallType.AUDIO else CallType.VIDEO
        _callState.value = CallState.Incoming(
            callId = session.id,
            channelId = session.channelId,
            callerUid = session.callerUid,
            callerName = session.callerName,
            callType = callType
        )
        setHealth(CallConnectionHealth.CONNECTING)
        startSignalingAndHealthMonitors(currentCompanyId, session.id, isCaller = false)
    }

    fun syncSessionUpdate(
        session: ChatCallSession?,
        myUid: String,
        peerUid: String,
        peerName: String,
        fallbackChannelId: String,
        fallbackCallType: CallType,
        companyId: String = "xmax"
    ) {
        currentCompanyId = companyId.trim().ifBlank { "xmax" }
        if (session == null) return
        currentCallId = session.id
        val callType = if (session.callType == "AUDIO" || !session.videoEnabled) CallType.AUDIO else CallType.VIDEO
        val isCaller = session.callerUid == myUid

        when (session.status.uppercase()) {
            "RINGING" -> {
                if (isCaller) {
                    if (_callState.value !is CallState.Connecting) {
                        _callState.value = CallState.Connecting(
                            callId = session.id,
                            channelId = session.channelId.ifBlank { fallbackChannelId },
                            peerUid = peerUid,
                            peerName = peerName,
                            callType = callType
                        )
                    }
                    publishCallerOfferIfNeeded(session.id, myUid, callType, currentCompanyId)
                    setHealth(CallConnectionHealth.WAITING_FOR_PEER)
                    startSignalingAndHealthMonitors(currentCompanyId, session.id, isCaller = true)
                } else {
                    if (_callState.value !is CallState.Incoming) {
                        bindIncomingCall(session, currentCompanyId)
                    }
                }
            }
            "CONNECTED" -> {
                val previous = _callState.value
                if (previous !is CallState.Active || previous.callId != session.id) {
                    activeCallStartedAtMs = System.currentTimeMillis()
                    currentIsCameraOff = callType == CallType.AUDIO
                    _callState.value = CallState.Active(
                        callId = session.id,
                        channelId = session.channelId.ifBlank { fallbackChannelId },
                        peerUid = peerUid,
                        peerName = peerName,
                        callType = callType,
                        isVideo = callType == CallType.VIDEO,
                        isMuted = currentIsMuted,
                        isCameraOff = currentIsCameraOff,
                        remoteBitmap = null
                    )
                    if (!isCaller) {
                        publishCalleeAnswerIfNeeded(session.id, myUid, callType, currentCompanyId)
                    }
                    setHealth(CallConnectionHealth.CONNECTED)
                    showToast("Call connected", isAlert = false)
                    startSignalingAndHealthMonitors(currentCompanyId, session.id, isCaller = isCaller)
                }
            }
            "DECLINED", "ENDED", "MISSED" -> {
                val reason = when (session.status.uppercase()) {
                    "DECLINED" -> "Call declined"
                    "MISSED" -> "Call missed"
                    else -> "Other party ended the call"
                }
                transitionToDisconnected(session.id, reason)
            }
        }
    }

    fun updateMuteState(muted: Boolean) {
        currentIsMuted = muted
        val cur = _callState.value
        if (cur is CallState.Active) {
            _callState.value = cur.copy(isMuted = muted)
        }
    }

    fun updateCameraOffState(cameraOff: Boolean) {
        currentIsCameraOff = cameraOff
        val cur = _callState.value
        if (cur is CallState.Active) {
            _callState.value = cur.copy(isCameraOff = cameraOff)
        }
    }

    fun updateRemoteBitmap(bitmap: Bitmap?) {
        val cur = _callState.value
        if (cur is CallState.Active) {
            _callState.value = cur.copy(remoteBitmap = bitmap)
        }
    }

    fun onLocalHangup(callId: String, reason: String = "Call ended") {
        transitionToDisconnected(callId, reason)
    }

    fun onCreationTimedOut() {
        val curId = currentCallId
        setHealth(CallConnectionHealth.CONNECTION_LOST)
        showToast("Connection Lost — unable to reach signaling server", isAlert = true)
        transitionToDisconnected(curId, "Connection Lost")
    }

    fun resetToIdle() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
        signalingObserverJob?.cancel()
        signalingObserverJob = null
        sentOfferForCallId = ""
        sentAnswerForCallId = ""
        activeCallStartedAtMs = 0L
        currentCallId = ""
        _connectionHealth.value = CallConnectionHealth.CONNECTING
        _toastNotification.value = null
        _callState.value = CallState.Idle
    }

    fun dismissToast() {
        toastDismissJob?.cancel()
        _toastNotification.value = null
    }

    private fun transitionToDisconnected(callId: String, reason: String) {
        val cur = _callState.value
        if (cur is CallState.Disconnected && cur.callId == callId) return
        healthMonitorJob?.cancel()
        healthMonitorJob = null
        signalingObserverJob?.cancel()
        signalingObserverJob = null
        _connectionHealth.value = CallConnectionHealth.DISCONNECTED
        _callState.value = CallState.Disconnected(callId = callId, reason = reason)
        showToast(reason, isAlert = reason.contains("Lost", ignoreCase = true) || reason.contains("declined", ignoreCase = true))

        if (callId.isNotBlank()) {
            viewModelScope.launch {
                RtdbCallSignalingManager.cleanupSignaling(callId, currentCompanyId, getApplication())
            }
        }
    }

    private fun publishCallerOfferIfNeeded(callId: String, uid: String, callType: CallType, companyId: String) {
        if (callId.isBlank() || sentOfferForCallId == callId) return
        sentOfferForCallId = callId
        viewModelScope.launch {
            val sdpPayload = "v=0\no=xmax $callId 1 IN IP4 127.0.0.1\ns=${callType.name}\na=rtdb-duplex:v2"
            RtdbCallSignalingManager.sendOffer(
                callId = callId,
                sdp = sdpPayload,
                sdpType = "offer",
                companyId = companyId,
                callerUid = uid,
                context = getApplication()
            )
            RtdbCallSignalingManager.addIceCandidate(
                callId = callId,
                isCaller = true,
                candidate = "candidate:1 1 udp 2122260223 rtdb-relay 3478 typ host",
                sdpMid = "0",
                sdpMLineIndex = 0,
                companyId = companyId,
                context = getApplication()
            )
        }
    }

    private fun publishCalleeAnswerIfNeeded(callId: String, uid: String, callType: CallType, companyId: String) {
        if (callId.isBlank() || sentAnswerForCallId == callId) return
        sentAnswerForCallId = callId
        viewModelScope.launch {
            val sdpPayload = "v=0\no=xmax-peer $callId 1 IN IP4 127.0.0.1\ns=${callType.name}\na=rtdb-duplex:v2"
            RtdbCallSignalingManager.sendAnswer(
                callId = callId,
                sdp = sdpPayload,
                sdpType = "answer",
                companyId = companyId,
                calleeUid = uid,
                context = getApplication()
            )
            RtdbCallSignalingManager.addIceCandidate(
                callId = callId,
                isCaller = false,
                candidate = "candidate:2 1 udp 2122260223 rtdb-relay 3478 typ host",
                sdpMid = "0",
                sdpMLineIndex = 0,
                companyId = companyId,
                context = getApplication()
            )
        }
    }

    private fun startSignalingAndHealthMonitors(companyId: String, callId: String, isCaller: Boolean) {
        if (callId.isBlank()) return
        if (signalingObserverJob == null || currentCallId != callId) {
            signalingObserverJob?.cancel()
            signalingObserverJob = viewModelScope.launch {
                RtdbCallSignalingManager.observeSignaling(callId, companyId, getApplication())
                    .catch { emit(emptyMap()) }
                    .collectLatest { sig ->
                        if (isCaller && sig.containsKey("answer") && _connectionHealth.value == CallConnectionHealth.WAITING_FOR_PEER) {
                            setHealth(CallConnectionHealth.CONNECTED)
                        }
                    }
            }
        }

        if (healthMonitorJob?.isActive != true) {
            healthMonitorJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000L)
                    val curState = _callState.value
                    if (curState is CallState.Idle || curState is CallState.Disconnected) break

                    val nextHealth = evaluateConnectionHealth(
                        state = curState,
                        deviceOnline = NetworkConnectivityMonitor.isOnline.value,
                        rtdbConnected = _rtdbConnected.value,
                        nowMillis = System.currentTimeMillis(),
                        activeStartedAtMs = activeCallStartedAtMs,
                        lastPeerMediaAtMs = LiveCallEngine.peerLastMediaAtMs.value
                    )
                    val prevHealth = _connectionHealth.value
                    if (nextHealth != prevHealth) {
                        setHealth(nextHealth)
                        when (nextHealth) {
                            CallConnectionHealth.CONNECTION_LOST ->
                                showToast("Connection Lost — check network", isAlert = true)
                            CallConnectionHealth.WAITING_FOR_PEER ->
                                showToast("Waiting for other party...", isAlert = false)
                            CallConnectionHealth.CONNECTED ->
                                if (prevHealth == CallConnectionHealth.CONNECTION_LOST || prevHealth == CallConnectionHealth.RECONNECTING) {
                                    showToast("Connection restored", isAlert = false)
                                }
                            CallConnectionHealth.RECONNECTING ->
                                showToast("Reconnecting...", isAlert = true)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun setHealth(health: CallConnectionHealth) {
        _connectionHealth.value = health
    }

    private fun showToast(message: String, isAlert: Boolean) {
        val notification = CallToastNotification(
            id = System.currentTimeMillis(),
            message = message,
            isAlert = isAlert
        )
        _toastNotification.value = notification
        toastDismissJob?.cancel()
        toastDismissJob = viewModelScope.launch {
            delay(3200L)
            if (_toastNotification.value?.id == notification.id) {
                _toastNotification.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        healthMonitorJob?.cancel()
        signalingObserverJob?.cancel()
        rtdbStatusJob?.cancel()
        toastDismissJob?.cancel()
    }

    companion object {
        const val PEER_MEDIA_STALL_MS = 6500L

        /**
         * Pure evaluation function for unit/Robolectric tests and live call monitoring.
         */
        fun evaluateConnectionHealth(
            state: CallState,
            deviceOnline: Boolean,
            rtdbConnected: Boolean,
            nowMillis: Long,
            activeStartedAtMs: Long,
            lastPeerMediaAtMs: Long
        ): CallConnectionHealth {
            if (state is CallState.Disconnected || state is CallState.Idle) {
                return CallConnectionHealth.DISCONNECTED
            }
            if (!deviceOnline) {
                return CallConnectionHealth.CONNECTION_LOST
            }
            if (!rtdbConnected) {
                return CallConnectionHealth.CONNECTION_LOST
            }
            return when (state) {
                is CallState.Connecting -> CallConnectionHealth.WAITING_FOR_PEER
                is CallState.Incoming -> CallConnectionHealth.CONNECTING
                is CallState.Active -> {
                    val elapsedSinceActive = if (activeStartedAtMs > 0L) nowMillis - activeStartedAtMs else 0L
                    val lastSeen = maxOf(lastPeerMediaAtMs, activeStartedAtMs)
                    val silenceDuration = if (lastSeen > 0L) nowMillis - lastSeen else 0L
                    if (elapsedSinceActive > PEER_MEDIA_STALL_MS && silenceDuration > PEER_MEDIA_STALL_MS) {
                        CallConnectionHealth.WAITING_FOR_PEER
                    } else {
                        CallConnectionHealth.CONNECTED
                    }
                }
                CallState.Idle, is CallState.Disconnected -> CallConnectionHealth.DISCONNECTED
            }
        }
    }
}
