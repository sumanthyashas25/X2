package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.chat.realtime.LiveCallEngine
import com.example.chat.realtime.RtdbCallSignalingManager
import com.example.model.CallConnectionHealth
import com.example.model.CallState
import com.example.model.CallType
import com.example.model.ChatCallSession
import com.example.ui.CallViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSystemAndWorkflowTest {

    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `evaluateConnectionHealth reports accurate states for idle, network drop, waiting, live, and peer stall`() {
        assertEquals(
            CallConnectionHealth.DISCONNECTED,
            CallViewModel.evaluateConnectionHealth(
                state = CallState.Idle,
                deviceOnline = true,
                rtdbConnected = true,
                nowMillis = 10_000L,
                activeStartedAtMs = 0L,
                lastPeerMediaAtMs = 0L
            )
        )

        assertEquals(
            CallConnectionHealth.DISCONNECTED,
            CallViewModel.evaluateConnectionHealth(
                state = CallState.Disconnected(callId = "c1", reason = "Ended"),
                deviceOnline = true,
                rtdbConnected = true,
                nowMillis = 10_000L,
                activeStartedAtMs = 0L,
                lastPeerMediaAtMs = 0L
            )
        )

        val connecting = CallState.Connecting(
            callId = "call_1",
            channelId = "ch_dm_1",
            peerUid = "u2",
            peerName = "Riya",
            callType = CallType.VIDEO
        )
        assertEquals(
            CallConnectionHealth.CONNECTION_LOST,
            CallViewModel.evaluateConnectionHealth(
                state = connecting,
                deviceOnline = false,
                rtdbConnected = true,
                nowMillis = 10_000L,
                activeStartedAtMs = 0L,
                lastPeerMediaAtMs = 0L
            )
        )
        assertEquals(
            CallConnectionHealth.CONNECTION_LOST,
            CallViewModel.evaluateConnectionHealth(
                state = connecting,
                deviceOnline = true,
                rtdbConnected = false,
                nowMillis = 10_000L,
                activeStartedAtMs = 0L,
                lastPeerMediaAtMs = 0L
            )
        )
        assertEquals(
            CallConnectionHealth.WAITING_FOR_PEER,
            CallViewModel.evaluateConnectionHealth(
                state = connecting,
                deviceOnline = true,
                rtdbConnected = true,
                nowMillis = 10_000L,
                activeStartedAtMs = 0L,
                lastPeerMediaAtMs = 0L
            )
        )
        assertEquals("Waiting for other party...", CallConnectionHealth.WAITING_FOR_PEER.label)
        assertEquals("Connection Lost", CallConnectionHealth.CONNECTION_LOST.label)

        val active = CallState.Active(
            callId = "call_1",
            channelId = "ch_dm_1",
            peerUid = "u2",
            peerName = "Riya",
            callType = CallType.VIDEO,
            isVideo = true,
            isMuted = false,
            isCameraOff = false
        )
        assertEquals(
            CallConnectionHealth.CONNECTED,
            CallViewModel.evaluateConnectionHealth(
                state = active,
                deviceOnline = true,
                rtdbConnected = true,
                nowMillis = 10_000L,
                activeStartedAtMs = 7_000L,
                lastPeerMediaAtMs = 9_000L
            )
        )
        assertEquals(
            CallConnectionHealth.WAITING_FOR_PEER,
            CallViewModel.evaluateConnectionHealth(
                state = active,
                deviceOnline = true,
                rtdbConnected = true,
                nowMillis = 20_000L,
                activeStartedAtMs = 10_000L,
                lastPeerMediaAtMs = 11_000L
            )
        )
    }

    @Test
    fun `CallViewModel tracks outgoing, connected, mute camera toggles, and peer hangup cleanly`() {
        val vm = CallViewModel(application)
        assertTrue(vm.callState.value is CallState.Idle)

        vm.startOutgoingCall(
            callId = "call_99",
            channelId = "ch_dm_u1_u2",
            peerUid = "u2",
            peerName = "Sumanth Yashas",
            callType = CallType.VIDEO,
            companyId = "xmax"
        )

        val connecting = vm.callState.value as? CallState.Connecting
        assertNotNull(connecting)
        assertEquals("call_99", connecting?.callId)
        assertEquals(CallConnectionHealth.WAITING_FOR_PEER, vm.connectionHealth.value)
        assertEquals("Waiting for other party...", vm.toastNotification.value?.message)
        assertFalse(vm.toastNotification.value?.isAlert ?: true)

        val connectedSession = ChatCallSession(
            id = "call_99",
            channelId = "ch_dm_u1_u2",
            callType = "VIDEO",
            callerUid = "u1",
            callerName = "Aarav",
            calleeUids = listOf("u2"),
            status = "CONNECTED",
            videoEnabled = true
        )
        vm.syncSessionUpdate(
            session = connectedSession,
            myUid = "u1",
            peerUid = "u2",
            peerName = "Sumanth Yashas",
            fallbackChannelId = "ch_dm_u1_u2",
            fallbackCallType = CallType.VIDEO,
            companyId = "xmax"
        )

        val active = vm.callState.value as? CallState.Active
        assertNotNull(active)
        assertEquals(CallConnectionHealth.CONNECTED, vm.connectionHealth.value)
        assertEquals("Call connected", vm.toastNotification.value?.message)
        assertFalse(active!!.isMuted)
        assertFalse(active.isCameraOff)

        vm.updateMuteState(true)
        vm.updateCameraOffState(true)
        val toggled = vm.callState.value as CallState.Active
        assertTrue(toggled.isMuted)
        assertTrue(toggled.isCameraOff)

        vm.syncSessionUpdate(
            session = connectedSession.copy(status = "ENDED"),
            myUid = "u1",
            peerUid = "u2",
            peerName = "Sumanth Yashas",
            fallbackChannelId = "ch_dm_u1_u2",
            fallbackCallType = CallType.VIDEO,
            companyId = "xmax"
        )

        val disconnected = vm.callState.value as? CallState.Disconnected
        assertNotNull(disconnected)
        assertEquals("Other party ended the call", disconnected?.reason)
        assertEquals(CallConnectionHealth.DISCONNECTED, vm.connectionHealth.value)

        vm.resetToIdle()
        assertTrue(vm.callState.value is CallState.Idle)
    }

    @Test
    fun `RtdbCallSignalingManager scopes signaling paths under company calls node`() {
        assertEquals(
            "companies/xmax/calls/call_123/signaling",
            RtdbCallSignalingManager.buildSignalingPath("xmax", "call_123")
        )
        assertEquals(
            "companies/xmax/calls/unknown/signaling",
            RtdbCallSignalingManager.buildSignalingPath("   ", " ")
        )
    }

    @Test
    fun `LiveCallEngine mu-law PCM codec preserves signal shape and length cleanly`() {
        val samples = ShortArray(160) { i ->
            when {
                i % 4 == 0 -> 0
                i % 4 == 1 -> 4000
                i % 4 == 2 -> (-4000).toShort()
                else -> 12000
            }
        }
        val encoded = LiveCallEngine.encodeMuLawBase64(samples)
        assertTrue(encoded.isNotBlank())

        val decoded = LiveCallEngine.decodeMuLawBase64(encoded)
        assertEquals(samples.size, decoded.size)
        for (i in samples.indices) {
            val diff = abs(samples[i].toInt() - decoded[i].toInt())
            assertTrue("Sample $i quantization error too large: $diff", diff < 700)
        }
    }
}
