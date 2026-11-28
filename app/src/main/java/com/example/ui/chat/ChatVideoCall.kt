package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.realtime.LiveCallEngine
import com.example.model.ChatCallSession
import com.example.model.ChatChannel
import com.example.model.Member
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.CrimsonBlocked
import com.example.ui.theme.EmeraldApproved
import com.example.ui.theme.PureWhite
import com.example.ui.theme.XMaxType
import kotlinx.coroutines.delay

enum class VideoCallStatus {
    CONNECTING,
    RINGING,
    CONNECTED,
    DECLINED,
    ENDED
}

/**
 * Full-screen real-time calling interface supporting both Voice Calls (audio-only)
 * and Video Calls (dual camera with real-time peer frame streaming & PiP swap).
 */
@Composable
fun ChatVideoCallDialog(
    channel: ChatChannel,
    myMember: Member,
    peerMembers: List<Member>,
    callSession: ChatCallSession? = null,
    onAcceptCall: () -> Unit = {},
    onEndOrDeclineCall: (status: String) -> Unit = {},
    onToggleMute: (Boolean) -> Unit = {},
    onToggleCamera: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    onCallCompleted: (durationSeconds: Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isVoiceCall = (callSession?.callType == "AUDIO") || (callSession?.videoEnabled == false)
    val callId = callSession?.id.orEmpty()
    val companyId = channel.id.split("_").firstOrNull()?.ifBlank { "xmax" } ?: "xmax"

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val avPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        cameraGranted = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(isVoiceCall) {
        val needed = buildList {
            if (!audioGranted) add(Manifest.permission.RECORD_AUDIO)
            if (!isVoiceCall && !cameraGranted) add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            avPermLauncher.launch(needed.toTypedArray())
        }
    }

    var localEnded by remember { mutableStateOf(false) }
    val isIncomingRinging = callSession != null &&
        callSession.callerUid != myMember.uid &&
        callSession.status == "RINGING" &&
        !localEnded

    val callStatus = when {
        localEnded -> VideoCallStatus.ENDED
        callSession == null -> VideoCallStatus.CONNECTING
        callSession.status == "CONNECTED" -> VideoCallStatus.CONNECTED
        callSession.status == "RINGING" -> VideoCallStatus.RINGING
        callSession.status == "DECLINED" -> VideoCallStatus.DECLINED
        callSession.status == "ENDED" || callSession.status == "MISSED" -> VideoCallStatus.ENDED
        else -> VideoCallStatus.CONNECTING
    }

    var durationSeconds by remember(callSession?.id) { mutableIntStateOf(0) }
    var isMuted by remember { mutableStateOf(false) }
    var isVideoEnabled by remember(isVoiceCall) { mutableStateOf(!isVoiceCall) }
    var isFrontCamera by remember { mutableStateOf(true) }
    var isSpeakerOn by remember(isVoiceCall) { mutableStateOf(!isVoiceCall) }
    var isSwappedViews by remember { mutableStateOf(false) }

    val primaryPeer = peerMembers.firstOrNull() ?: myMember
    val isGroupCall = peerMembers.size > 1

    // Peer remote states from signaling
    val isPeerMuted = callSession?.mutedUids?.contains(primaryPeer.uid) == true
    val isPeerCameraOff = callSession?.cameraOffUids?.contains(primaryPeer.uid) == true

    // Real-time voice amplitudes from live audio engine
    val localAmp by LiveCallEngine.localVoiceAmplitude.collectAsStateWithLifecycle()
    val remoteAmp by LiveCallEngine.remoteVoiceAmplitude.collectAsStateWithLifecycle()

    // Real-time remote video stream from live video engine
    var remoteVideoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var remoteCameraOffByRtdb by remember { mutableStateOf(false) }

    LaunchedEffect(callId, primaryPeer.uid, isVoiceCall, callStatus) {
        if (!isVoiceCall && callId.isNotBlank() && callStatus == VideoCallStatus.CONNECTED) {
            LiveCallEngine.observeRemoteVideo(companyId, callId, primaryPeer.uid, context).collect { (bmp, camOff) ->
                remoteVideoBitmap = bmp
                remoteCameraOffByRtdb = camOff
            }
        }
    }

    // Audio lifecycle: Ringback tone when caller is RINGING, in-call audio when CONNECTED
    LaunchedEffect(callStatus, isIncomingRinging) {
        when {
            callStatus == VideoCallStatus.RINGING && !isIncomingRinging -> {
                LiveCallEngine.startOutgoingRingback()
            }
            callStatus == VideoCallStatus.CONNECTED -> {
                LiveCallEngine.stopRinging()
                LiveCallEngine.startInCallAudio(
                    context = context,
                    companyId = companyId,
                    callId = callId,
                    myUid = myMember.uid,
                    peerUid = primaryPeer.uid,
                    isVideoCall = !isVoiceCall
                )
            }
            callStatus == VideoCallStatus.DECLINED || callStatus == VideoCallStatus.ENDED -> {
                LiveCallEngine.stopRinging()
                LiveCallEngine.endCallAudio(context)
            }
        }
    }

    // Timer increment ONLY while CONNECTED
    LaunchedEffect(callStatus) {
        if (callStatus == VideoCallStatus.CONNECTED) {
            while (true) {
                delay(1000)
                durationSeconds++
            }
        }
    }

    // Auto-dismiss shortly after remote decline or end
    LaunchedEffect(callSession?.status) {
        val st = callSession?.status
        if (st == "DECLINED" || st == "ENDED" || st == "MISSED") {
            delay(900)
            LiveCallEngine.endCallAudio(context)
            onCallCompleted(durationSeconds)
            onDismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            LiveCallEngine.endCallAudio(context)
            LiveCallEngine.unbindLocalCamera()
        }
    }

    fun finishCall(targetStatus: String) {
        if (localEnded) return
        localEnded = true
        LiveCallEngine.endCallAudio(context)
        LiveCallEngine.unbindLocalCamera()
        if (callId.isNotBlank()) {
            LiveCallEngine.cleanupCallSession(companyId, callId, context)
        }
        onEndOrDeclineCall(targetStatus)
        onCallCompleted(durationSeconds)
        onDismiss()
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Local camera binding and frame broadcasting
    LaunchedEffect(isVoiceCall, isVideoEnabled, isFrontCamera, cameraGranted, callStatus) {
        if (!isVoiceCall && isVideoEnabled && cameraGranted && callStatus != VideoCallStatus.ENDED) {
            LiveCallEngine.bindLocalCamera(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                isFrontCamera = isFrontCamera,
                isVideoMuted = !isVideoEnabled,
                onFrameCaptured = { base64 ->
                    if (callId.isNotBlank()) {
                        LiveCallEngine.publishLocalVideoFrame(
                            companyId = companyId,
                            callId = callId,
                            myUid = myMember.uid,
                            frameBase64 = base64,
                            isCameraOff = !isVideoEnabled,
                            context = context
                        )
                    }
                }
            )
        } else {
            LiveCallEngine.unbindLocalCamera()
            if (callId.isNotBlank()) {
                LiveCallEngine.publishLocalVideoFrame(
                    companyId = companyId,
                    callId = callId,
                    myUid = myMember.uid,
                    frameBase64 = "",
                    isCameraOff = true,
                    context = context
                )
            }
        }
    }

    Dialog(
        onDismissRequest = {
            finishCall(if (isIncomingRinging) "DECLINED" else "ENDED")
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090B10))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("video_call_dialog_screen")
        ) {
            val remoteEffectiveCameraOff = isVoiceCall || isPeerCameraOff || remoteCameraOffByRtdb || remoteVideoBitmap == null

            // ==========================================
            // MAIN STAGE (Remote feed or full-screen local if swapped)
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("remote_video_stage"),
                contentAlignment = Alignment.Center
            ) {
                if (isSwappedViews && !isVoiceCall && isVideoEnabled && cameraGranted) {
                    // Swapped: Fullscreen Local Camera
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (!remoteEffectiveCameraOff && remoteVideoBitmap != null) {
                    // Normal Video Call: Fullscreen Remote Camera Stream
                    Image(
                        bitmap = remoteVideoBitmap!!.asImageBitmap(),
                        contentDescription = "Remote participant video",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Voice Call or Remote Camera Off: Immersive Audio Stage
                    CallAudioStage(
                        peer = primaryPeer,
                        isGroup = isGroupCall,
                        callType = if (isVoiceCall) "Voice Call" else "Video Call",
                        callStatus = callStatus,
                        isIncoming = isIncomingRinging,
                        channelTitle = channel.title,
                        voiceAmplitude = if (callStatus == VideoCallStatus.CONNECTED) remoteAmp.coerceAtLeast(localAmp * 0.5f) else 0f,
                        isPeerMuted = isPeerMuted,
                        isPeerCameraOff = !isVoiceCall && (isPeerCameraOff || remoteCameraOffByRtdb)
                    )
                }
            }

            // ==========================================
            // TOP STATUS & PARTICIPANT HEADER
            // ==========================================
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = if (isGroupCall) formatTitleCase(channel.title) else formatTitleCase(primaryPeer.fullName),
                            style = XMaxType.headline,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (callStatus) {
                                            VideoCallStatus.CONNECTED -> EmeraldApproved
                                            VideoCallStatus.RINGING -> Color(0xFFFBBF24)
                                            VideoCallStatus.DECLINED -> CrimsonBlocked
                                            else -> Color(0xFF94A3B8)
                                        }
                                    )
                            )
                            Text(
                                text = when {
                                    callStatus == VideoCallStatus.CONNECTING -> "Connecting…"
                                    isIncomingRinging -> "Incoming ${if (isVoiceCall) "Voice" else "Video"} Call…"
                                    callStatus == VideoCallStatus.RINGING -> "Ringing…"
                                    callStatus == VideoCallStatus.CONNECTED -> formatTimer(durationSeconds)
                                    callStatus == VideoCallStatus.DECLINED -> "Call Declined"
                                    else -> "Call Ended"
                                },
                                style = XMaxType.caption1Medium,
                                color = PureWhite.copy(alpha = 0.88f)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "E2E Encrypted",
                                tint = EmeraldApproved,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (isVoiceCall) "Voice Call • Encrypted" else "Video Call • Encrypted",
                                style = XMaxType.caption2Medium,
                                color = PureWhite
                            )
                        }
                    }
                }
            }

            // ==========================================
            // VIDEO PiP WINDOW (Only for Video Calls)
            // ==========================================
            if (!isVoiceCall) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 74.dp, end = 16.dp)
                        .size(112.dp, 154.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF1E222D))
                        .border(2.dp, PureWhite.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                        .clickable { isSwappedViews = !isSwappedViews }
                        .testTag("video_call_local_pip"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSwappedViews) {
                        // In PiP: Remote feed thumbnail
                        if (remoteVideoBitmap != null && !remoteEffectiveCameraOff) {
                            Image(
                                bitmap = remoteVideoBitmap!!.asImageBitmap(),
                                contentDescription = "Remote video PiP",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            StudioAvatar(name = primaryPeer.fullName, size = 44.dp, photoBase64 = primaryPeer.photoBase64)
                        }
                    } else {
                        // In PiP: Local Camera View
                        if (isVideoEnabled && cameraGranted) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(8.dp)
                            ) {
                                StudioAvatar(name = myMember.fullName, size = 36.dp, photoBase64 = myMember.photoBase64)
                                Text(
                                    text = if (!cameraGranted) "Perm needed" else "Camera Off",
                                    style = XMaxType.caption2Medium,
                                    color = PureWhite.copy(alpha = 0.75f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Small indicator badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isSwappedViews) primaryPeer.fullName.take(6) else "You",
                            style = XMaxType.caption2,
                            color = PureWhite
                        )
                    }
                }
            }

            // ==========================================
            // BOTTOM CONTROLS ISLAND
            // ==========================================
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color(0xFF151820).copy(alpha = 0.95f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 440.dp)
                    .testTag("video_call_control_bar")
            ) {
                if (isIncomingRinging) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { finishCall("DECLINED") },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                            modifier = Modifier
                                .height(50.dp)
                                .weight(1f)
                                .testTag("call_btn_decline")
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline Call", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Decline", style = XMaxType.buttonCompact)
                        }
                        Spacer(Modifier.width(16.dp))
                        Button(
                            onClick = onAcceptCall,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldApproved, contentColor = PureWhite),
                            modifier = Modifier
                                .height(50.dp)
                                .weight(1f)
                                .testTag("call_btn_accept")
                        ) {
                            Icon(
                                if (isVoiceCall) Icons.Default.Call else Icons.Default.Videocam,
                                contentDescription = "Accept Call",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Accept", style = XMaxType.buttonCompact)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Mic Mute Button
                        CallControlButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMuted) "Unmute" else "Mute",
                            active = isMuted,
                            activeContainerColor = CrimsonBlocked,
                            onClick = {
                                isMuted = !isMuted
                                LiveCallEngine.setMicrophoneMute(context, isMuted)
                                onToggleMute(isMuted)
                            },
                            testTag = "call_btn_mute"
                        )

                        // 2. Video Toggle Button (Only in Video Call)
                        if (!isVoiceCall) {
                            CallControlButton(
                                icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                label = if (isVideoEnabled) "Cam On" else "Cam Off",
                                active = !isVideoEnabled,
                                activeContainerColor = CrimsonBlocked,
                                onClick = {
                                    if (!cameraGranted) {
                                        avPermLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                    } else {
                                        isVideoEnabled = !isVideoEnabled
                                        onToggleCamera(!isVideoEnabled)
                                    }
                                },
                                testTag = "call_btn_video"
                            )

                            // 3. Flip Camera (Front / Rear)
                            CallControlButton(
                                icon = Icons.Default.Cameraswitch,
                                label = if (isFrontCamera) "Front" else "Rear",
                                active = false,
                                onClick = { isFrontCamera = !isFrontCamera },
                                testTag = "call_btn_flip"
                            )
                        }

                        // 4. Speakerphone Toggle
                        CallControlButton(
                            icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                            label = if (isSpeakerOn) "Speaker" else "Earpiece",
                            active = isSpeakerOn,
                            onClick = {
                                isSpeakerOn = !isSpeakerOn
                                LiveCallEngine.setSpeakerphone(context, isSpeakerOn)
                            },
                            testTag = "call_btn_speaker"
                        )

                        // 5. End Call Button
                        Surface(
                            onClick = { finishCall("ENDED") },
                            shape = CircleShape,
                            color = CrimsonBlocked,
                            modifier = Modifier
                                .size(52.dp)
                                .testTag("call_btn_end")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.CallEnd,
                                    contentDescription = "End Call",
                                    tint = PureWhite,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingCallBanner(
    session: ChatCallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isVoice = session.callType == "AUDIO"

    LaunchedEffect(session.id) {
        LiveCallEngine.startIncomingRingtone(context)
    }

    DisposableEffect(session.id) {
        onDispose {
            LiveCallEngine.stopRinging()
        }
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF151820),
        border = BorderStroke(1.dp, EmeraldApproved.copy(alpha = 0.65f)),
        shadowElevation = 14.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("incoming_call_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StudioAvatar(name = session.callerName, size = 44.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatTitleCase(session.callerName),
                    style = XMaxType.headline,
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Incoming ${if (isVoice) "Voice" else "Video"} Call…",
                    style = XMaxType.caption1Medium,
                    color = EmeraldApproved,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = {
                    LiveCallEngine.stopRinging()
                    onDecline()
                },
                shape = CircleShape,
                color = CrimsonBlocked,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("incoming_call_decline")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = PureWhite, modifier = Modifier.size(20.dp))
                }
            }
            Surface(
                onClick = {
                    LiveCallEngine.stopRinging()
                    onAccept()
                },
                shape = CircleShape,
                color = EmeraldApproved,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("incoming_call_accept")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isVoice) Icons.Default.Call else Icons.Default.Videocam,
                        contentDescription = "Accept",
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    activeContainerColor: Color = Color.White.copy(alpha = 0.25f),
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) activeContainerColor else Color.White.copy(alpha = 0.12f),
        modifier = Modifier
            .size(48.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = PureWhite,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CallAudioStage(
    peer: Member,
    isGroup: Boolean,
    callType: String,
    callStatus: VideoCallStatus,
    isIncoming: Boolean,
    channelTitle: String,
    voiceAmplitude: Float,
    isPeerMuted: Boolean,
    isPeerCameraOff: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_stage_pulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_pulse"
    )

    // Dynamic wave scale reflecting real voice activity from microphone
    val activeScale = (1f + (voiceAmplitude * 0.45f)).coerceAtLeast(idlePulse)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF201646),
                        Color(0xFF10121A),
                        Color(0xFF090A0D)
                    ),
                    center = Offset(500f, 600f),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(360.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(
                color = Color(0xFF6E56CF).copy(alpha = 0.14f * activeScale),
                radius = 165f * activeScale,
                center = center
            )
            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.10f * activeScale),
                radius = 125f * activeScale,
                center = center
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .graphicsLayer {
                            scaleX = activeScale
                            scaleY = activeScale
                        }
                        .clip(CircleShape)
                        .background(Color(0xFF6E56CF).copy(alpha = 0.25f))
                )
                StudioAvatar(
                    name = peer.fullName,
                    size = 112.dp,
                    borderColor = PureWhite.copy(alpha = 0.7f),
                    photoBase64 = peer.photoBase64
                )
            }

            Text(
                text = if (isGroup) formatTitleCase(channelTitle) else formatTitleCase(peer.fullName),
                style = XMaxType.primaryTitle,
                color = PureWhite,
                textAlign = TextAlign.Center
            )

            if (!isGroup && peer.jobRoleName.isNotBlank()) {
                Text(
                    text = peer.jobRoleName,
                    style = XMaxType.subheadline,
                    color = PureWhite.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center
                )
            }

            // Status & Voice Equalizer
            when (callStatus) {
                VideoCallStatus.CONNECTED -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(28.dp)
                    ) {
                        repeat(7) { idx ->
                            RealtimeVoiceBar(index = idx, baseAmplitude = voiceAmplitude)
                        }
                    }

                    if (isPeerMuted) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = CrimsonBlocked.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.MicOff, contentDescription = null, tint = CrimsonBlocked, modifier = Modifier.size(14.dp))
                                Text("Participant Muted", style = XMaxType.caption2Medium, color = CrimsonBlocked)
                            }
                        }
                    } else if (isPeerCameraOff) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "Camera Off",
                                style = XMaxType.caption2Medium,
                                color = PureWhite.copy(alpha = 0.8f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "Connected • Live HD Audio",
                            style = XMaxType.caption1Medium,
                            color = EmeraldApproved
                        )
                    }
                }
                VideoCallStatus.RINGING -> {
                    Text(
                        text = if (isIncoming) "Tap Accept to join call" else "Ringing recipient…",
                        style = XMaxType.caption1Medium,
                        color = Color(0xFFFBBF24)
                    )
                }
                VideoCallStatus.DECLINED -> {
                    Text(
                        text = "Call declined",
                        style = XMaxType.caption1Medium,
                        color = CrimsonBlocked
                    )
                }
                VideoCallStatus.ENDED -> {
                    Text(
                        text = "Call ended",
                        style = XMaxType.caption1Medium,
                        color = PureWhite.copy(alpha = 0.7f)
                    )
                }
                VideoCallStatus.CONNECTING -> {
                    Text(
                        text = "Connecting secure signaling…",
                        style = XMaxType.caption1Medium,
                        color = EmeraldApproved
                    )
                }
            }
        }
    }
}

@Composable
private fun RealtimeVoiceBar(index: Int, baseAmplitude: Float) {
    val clamped = baseAmplitude.coerceIn(0.12f, 1f)
    val barHeight = (8.dp + (24.dp * clamped * (1f - (index % 3) * 0.2f))).coerceIn(6.dp, 30.dp)
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(barHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(EmeraldApproved.copy(alpha = 0.88f))
    )
}

private fun formatTimer(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
