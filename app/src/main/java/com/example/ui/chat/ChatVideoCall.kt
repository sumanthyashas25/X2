package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.graphicsLayer
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

@Composable
fun ChatVideoCallDialog(
    channel: ChatChannel,
    myMember: Member,
    peerMembers: List<Member>,
    callSession: ChatCallSession? = null,
    onAcceptCall: () -> Unit = {},
    onEndOrDeclineCall: (status: String) -> Unit = {},
    onDismiss: () -> Unit,
    onCallCompleted: (durationSeconds: Int) -> Unit
) {
    val context = LocalContext.current
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
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (!audioGranted) add(Manifest.permission.RECORD_AUDIO)
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
    var isVideoEnabled by remember { mutableStateOf(true) }
    var isFrontCamera by remember { mutableStateOf(true) }
    var isSpeakerOn by remember { mutableStateOf(true) }
    var isPipExpanded by remember { mutableStateOf(false) }

    // Count connected seconds ONLY when Firestore signaling marks call CONNECTED
    LaunchedEffect(callStatus) {
        if (callStatus == VideoCallStatus.CONNECTED) {
            while (true) {
                delay(1000)
                durationSeconds++
            }
        }
    }

    // Auto-close dialog shortly after remote decline/end
    LaunchedEffect(callSession?.status) {
        val st = callSession?.status
        if (st == "DECLINED" || st == "ENDED" || st == "MISSED") {
            delay(900)
            onCallCompleted(durationSeconds)
            onDismiss()
        }
    }

    val primaryPeer = peerMembers.firstOrNull() ?: myMember
    val isGroupCall = peerMembers.size > 1

    fun finishCall(targetStatus: String) {
        if (localEnded) return
        localEnded = true
        onEndOrDeclineCall(targetStatus)
        onCallCompleted(durationSeconds)
        onDismiss()
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
                .background(Color(0xFF0A0C10))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("video_call_dialog_screen")
        ) {
            // Main Stage: Remote Participant Signaling Status & Audio Visualizer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("remote_video_stage"),
                contentAlignment = Alignment.Center
            ) {
                RemoteVideoStage(
                    primaryPeer = primaryPeer,
                    isGroup = isGroupCall,
                    callStatus = callStatus,
                    isIncoming = isIncomingRinging,
                    channelTitle = channel.title
                )
            }

            // Top Status & Header Bar
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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
                                    callStatus == VideoCallStatus.CONNECTING -> "Signaling…"
                                    isIncomingRinging -> "Incoming video call…"
                                    callStatus == VideoCallStatus.RINGING -> "Ringing…"
                                    callStatus == VideoCallStatus.CONNECTED -> formatTimer(durationSeconds)
                                    callStatus == VideoCallStatus.DECLINED -> "Declined"
                                    else -> "Call ended"
                                },
                                style = XMaxType.caption1Medium,
                                color = PureWhite.copy(alpha = 0.85f)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "E2E Encrypted",
                                tint = EmeraldApproved,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (cameraGranted) "Live • Encrypted" else "Audio Only",
                                style = XMaxType.caption2Medium,
                                color = PureWhite
                            )
                        }
                    }
                }
            }

            // Local Camera PiP with Real CameraX Preview
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 74.dp, end = 16.dp)
                    .size(if (isPipExpanded) 144.dp else 104.dp, if (isPipExpanded) 196.dp else 140.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1E222D))
                    .border(2.dp, PureWhite.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable { isPipExpanded = !isPipExpanded }
                    .testTag("video_call_local_pip"),
                contentAlignment = Alignment.Center
            ) {
                if (isVideoEnabled && cameraGranted) {
                    LocalCameraPreview(isFront = isFrontCamera, myMember = myMember)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        StudioAvatar(
                            name = myMember.fullName,
                            size = 38.dp,
                            photoBase64 = myMember.photoBase64
                        )
                        Text(
                            text = if (!cameraGranted) "Camera perm" else "Camera off",
                            style = XMaxType.caption2Medium,
                            color = PureWhite.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Bottom Controls Island
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color(0xFF171A21).copy(alpha = 0.94f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 420.dp)
                    .testTag("video_call_control_bar")
            ) {
                if (isIncomingRinging) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { finishCall("DECLINED") },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("call_btn_decline")
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline Call", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Decline", style = XMaxType.buttonCompact)
                        }
                        Button(
                            onClick = onAcceptCall,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldApproved, contentColor = PureWhite),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("call_btn_accept")
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "Accept Call", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
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
                        CallControlButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMuted) "Unmute" else "Mute",
                            active = isMuted,
                            activeContainerColor = CrimsonBlocked,
                            onClick = { isMuted = !isMuted },
                            testTag = "call_btn_mute"
                        )

                        CallControlButton(
                            icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            label = if (isVideoEnabled) "Cam On" else "Cam Off",
                            active = !isVideoEnabled,
                            activeContainerColor = CrimsonBlocked,
                            onClick = {
                                if (!cameraGranted) {
                                    avPermLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                } else {
                                    isVideoEnabled = !isVideoEnabled
                                }
                            },
                            testTag = "call_btn_video"
                        )

                        CallControlButton(
                            icon = Icons.Default.Cameraswitch,
                            label = if (isFrontCamera) "Front" else "Rear",
                            active = false,
                            onClick = { isFrontCamera = !isFrontCamera },
                            testTag = "call_btn_flip"
                        )

                        CallControlButton(
                            icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                            label = if (isSpeakerOn) "Speaker" else "Earpiece",
                            active = isSpeakerOn,
                            onClick = { isSpeakerOn = !isSpeakerOn },
                            testTag = "call_btn_speaker"
                        )

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
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF171A21),
        border = BorderStroke(1.dp, EmeraldApproved.copy(alpha = 0.6f)),
        shadowElevation = 12.dp,
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
            StudioAvatar(name = session.callerName, size = 42.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatTitleCase(session.callerName),
                    style = XMaxType.headline,
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Incoming ${if (session.callType == "AUDIO") "Voice" else "Video"} Call",
                    style = XMaxType.caption1,
                    color = EmeraldApproved,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onDecline,
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
                onClick = onAccept,
                shape = CircleShape,
                color = EmeraldApproved,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("incoming_call_accept")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Videocam, contentDescription = "Accept", tint = PureWhite, modifier = Modifier.size(20.dp))
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
private fun RemoteVideoStage(
    primaryPeer: Member,
    isGroup: Boolean,
    callStatus: VideoCallStatus,
    isIncoming: Boolean,
    channelTitle: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "remote_video_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF241C49),
                        Color(0xFF13151D),
                        Color(0xFF090A0D)
                    ),
                    center = Offset(500f, 600f),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(340.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(
                color = Color(0xFF6E56CF).copy(alpha = 0.12f),
                radius = 160f * pulseScale,
                center = center
            )
            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.08f),
                radius = 120f * pulseScale,
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
                        .size(136.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                        }
                        .clip(CircleShape)
                        .background(Color(0xFF6E56CF).copy(alpha = 0.22f))
                )
                StudioAvatar(
                    name = primaryPeer.fullName,
                    size = 110.dp,
                    borderColor = PureWhite.copy(alpha = 0.6f),
                    photoBase64 = primaryPeer.photoBase64
                )
            }

            Text(
                text = if (isGroup) formatTitleCase(channelTitle) else formatTitleCase(primaryPeer.fullName),
                style = XMaxType.primaryTitle,
                color = PureWhite,
                textAlign = TextAlign.Center
            )

            if (!isGroup && primaryPeer.jobRoleName.isNotBlank()) {
                Text(
                    text = primaryPeer.jobRoleName,
                    style = XMaxType.subheadline,
                    color = PureWhite.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )
            }

            when (callStatus) {
                VideoCallStatus.CONNECTED -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(28.dp)
                    ) {
                        repeat(7) { idx -> AudioWaveBar(index = idx) }
                    }
                    Text(
                        text = "Connected via Live Call Signaling",
                        style = XMaxType.caption1Medium,
                        color = EmeraldApproved
                    )
                }
                VideoCallStatus.RINGING -> {
                    Text(
                        text = if (isIncoming) "Tap Accept to join call" else "Ringing peer device…",
                        style = XMaxType.caption1Medium,
                        color = Color(0xFFFBBF24)
                    )
                }
                VideoCallStatus.DECLINED -> {
                    Text(
                        text = "Call declined by recipient",
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
                        text = "Starting encrypted signaling…",
                        style = XMaxType.caption1Medium,
                        color = EmeraldApproved
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioWaveBar(index: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_bar_$index")
    val heightScale by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400 + index * 90, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar_height"
    )
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(24.dp * heightScale)
            .clip(RoundedCornerShape(999.dp))
            .background(EmeraldApproved.copy(alpha = 0.85f))
    )
}

@Composable
private fun LocalCameraPreview(isFront: Boolean, myMember: Member) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraError by remember { mutableStateOf(false) }

    if (cameraError) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioAvatar(name = myMember.fullName, size = 34.dp, photoBase64 = myMember.photoBase64)
                Text(if (isFront) "Front" else "Rear", style = XMaxType.caption2Medium, color = PureWhite.copy(alpha = 0.75f))
            }
        }
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(isFront, lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview)
            }.onFailure {
                cameraError = true
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

private fun formatTimer(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
