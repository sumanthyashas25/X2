package com.example.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.ChatChannel
import com.example.model.Member
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.EmeraldApproved
import com.example.ui.theme.PureWhite
import com.example.ui.theme.XMaxTheme
import com.example.ui.theme.XMaxType
import kotlinx.coroutines.delay

enum class VideoCallStatus {
    CONNECTING,
    RINGING,
    CONNECTED,
    ENDED
}

@Composable
fun ChatVideoCallDialog(
    channel: ChatChannel,
    myMember: Member,
    peerMembers: List<Member>,
    onDismiss: () -> Unit,
    onCallCompleted: (durationSeconds: Int) -> Unit
) {
    var callStatus by remember { mutableStateOf(VideoCallStatus.CONNECTING) }
    var durationSeconds by remember { mutableIntStateOf(0) }
    var isMuted by remember { mutableStateOf(false) }
    var isVideoEnabled by remember { mutableStateOf(true) }
    var isFrontCamera by remember { mutableStateOf(true) }
    var isSpeakerOn by remember { mutableStateOf(true) }
    var isPipExpanded by remember { mutableStateOf(false) }

    // Simulated connection progression: Connecting -> Ringing (1.2s) -> Connected (2.4s)
    LaunchedEffect(Unit) {
        delay(1200)
        if (callStatus == VideoCallStatus.CONNECTING) {
            callStatus = VideoCallStatus.RINGING
        }
        delay(1400)
        if (callStatus == VideoCallStatus.RINGING) {
            callStatus = VideoCallStatus.CONNECTED
        }
    }

    // Call duration timer
    LaunchedEffect(callStatus) {
        if (callStatus == VideoCallStatus.CONNECTED) {
            while (true) {
                delay(1000)
                durationSeconds++
            }
        }
    }

    val primaryPeer = peerMembers.firstOrNull() ?: myMember
    val isGroupCall = peerMembers.size > 1

    Dialog(
        onDismissRequest = {
            onCallCompleted(durationSeconds)
            onDismiss()
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
            // Main Stage: Remote Participant Video Feed (or animated simulated feed)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("remote_video_stage"),
                contentAlignment = Alignment.Center
            ) {
                RemoteVideoStage(
                    primaryPeer = primaryPeer,
                    allPeers = peerMembers,
                    isGroup = isGroupCall,
                    callStatus = callStatus,
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
                            maxLines = 1
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
                                            else -> Color(0xFF94A3B8)
                                        }
                                    )
                            )
                            Text(
                                text = when (callStatus) {
                                    VideoCallStatus.CONNECTING -> "Connecting…"
                                    VideoCallStatus.RINGING -> "Ringing…"
                                    VideoCallStatus.CONNECTED -> formatTimer(durationSeconds)
                                    VideoCallStatus.ENDED -> "Call ended"
                                },
                                style = XMaxType.caption1Medium,
                                color = PureWhite.copy(alpha = 0.85f)
                            )
                        }
                    }

                    // Network Quality Badge
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
                                text = "HD • Encrypted",
                                style = XMaxType.caption2Medium,
                                color = PureWhite
                            )
                        }
                    }
                }
            }

            // Local Camera PiP (Picture-in-Picture)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 70.dp, end = 16.dp)
                    .size(if (isPipExpanded) 140.dp else 100.dp, if (isPipExpanded) 190.dp else 135.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1E222D))
                    .border(2.dp, PureWhite.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable { isPipExpanded = !isPipExpanded }
                    .testTag("video_call_local_pip"),
                contentAlignment = Alignment.Center
            ) {
                if (isVideoEnabled) {
                    // Simulated Front/Rear Camera stream with subtle ambient shimmer
                    LocalCameraStream(
                        isFront = isFrontCamera,
                        userName = myMember.fullName
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StudioAvatar(
                            name = myMember.fullName,
                            size = 36.dp,
                            photoBase64 = myMember.photoBase64
                        )
                        Text(
                            text = "Camera off",
                            style = XMaxType.caption2Medium,
                            color = PureWhite.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Bottom Controls Island
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color(0xFF171A21).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 420.dp)
                    .testTag("video_call_control_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute Audio Toggle
                    CallControlButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) "Unmute" else "Mute",
                        active = isMuted,
                        activeContainerColor = Color(0xFFEF4444),
                        onClick = { isMuted = !isMuted },
                        testTag = "call_btn_mute"
                    )

                    // Video Camera Toggle
                    CallControlButton(
                        icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = if (isVideoEnabled) "Cam On" else "Cam Off",
                        active = !isVideoEnabled,
                        activeContainerColor = Color(0xFFEF4444),
                        onClick = { isVideoEnabled = !isVideoEnabled },
                        testTag = "call_btn_video"
                    )

                    // Camera Flip
                    CallControlButton(
                        icon = Icons.Default.Cameraswitch,
                        label = if (isFrontCamera) "Front" else "Rear",
                        active = false,
                        onClick = { isFrontCamera = !isFrontCamera },
                        testTag = "call_btn_flip"
                    )

                    // Speakerphone Toggle
                    CallControlButton(
                        icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        label = if (isSpeakerOn) "Speaker" else "Earpiece",
                        active = isSpeakerOn,
                        onClick = { isSpeakerOn = !isSpeakerOn },
                        testTag = "call_btn_speaker"
                    )

                    // End Call
                    Surface(
                        onClick = {
                            callStatus = VideoCallStatus.ENDED
                            onCallCompleted(durationSeconds)
                            onDismiss()
                        },
                        shape = CircleShape,
                        color = Color(0xFFEF4444),
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
            .size(46.dp)
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
    allPeers: List<Member>,
    isGroup: Boolean,
    callStatus: VideoCallStatus,
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
        // Decorative background audio waves / subtle radar rings
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
                // Pulsing ring
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

            // Audio wave activity animation during active call
            if (callStatus == VideoCallStatus.CONNECTED) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(28.dp)
                ) {
                    repeat(7) { idx ->
                        AudioWaveBar(index = idx)
                    }
                }
            } else {
                Text(
                    text = if (callStatus == VideoCallStatus.RINGING) "Calling…" else "Establishing peer connection…",
                    style = XMaxType.caption1Medium,
                    color = EmeraldApproved
                )
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
private fun LocalCameraStream(isFront: Boolean, userName: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1E293B),
                        Color(0xFF0F172A)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                if (isFront) Icons.Default.Face else Icons.Default.CameraRear,
                contentDescription = null,
                tint = PureWhite.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = if (isFront) "Front" else "Rear",
                style = XMaxType.caption2Medium,
                color = PureWhite.copy(alpha = 0.75f)
            )
        }
    }
}

private fun formatTimer(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
