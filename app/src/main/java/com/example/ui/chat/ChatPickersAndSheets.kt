package com.example.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.chat.ChatSettingsStore
import com.example.chat.ChatUserPreferences
import com.example.model.ChatChannel
import com.example.model.ChatChannelType
import com.example.model.ChatMessage
import com.example.model.Member
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiStickerGifSheet(
    onPickEmoji: (String) -> Unit,
    onPickSticker: (ChatStickerItem) -> Unit,
    onPickGif: (ChatGifItem) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0=Emoji, 1=Stickers, 2=GIFs

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MutedSurface,
                contentColor = ObsidianCharcoal,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Emoji", style = XMaxType.footnoteSemibold) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Stickers", style = XMaxType.footnoteSemibold) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("GIFs", style = XMaxType.footnoteSemibold) })
            }

            when (selectedTab) {
                0 -> {
                    val rows = ChatVisualCatalog.quickEmojis.chunked(6)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                row.forEach { emoji ->
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .clickable { onPickEmoji(emoji) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(emoji, fontSize = 26.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val rows = ChatVisualCatalog.stickers.chunked(2)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { stk ->
                                    Surface(
                                        onClick = { onPickSticker(stk) },
                                        shape = RoundedCornerShape(18.dp),
                                        color = stk.bgColor,
                                        border = BorderStroke(1.dp, stk.accentColor.copy(alpha = 0.25f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(stk.emoji, fontSize = 28.sp)
                                            Text(
                                                text = stk.title,
                                                style = XMaxType.headline,
                                                color = stk.accentColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    val pulseTransition = rememberInfiniteTransition(label = "gif_picker_pulse")
                    val scale by pulseTransition.animateFloat(
                        initialValue = 0.96f,
                        targetValue = 1.04f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "gif_picker_scale"
                    )
                    val rows = ChatVisualCatalog.gifs.chunked(2)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { gif ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(82.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Brush.linearGradient(listOf(gif.gradientStart, gif.gradientEnd)))
                                            .clickable { onPickGif(gif) }
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = gif.emoji,
                                                fontSize = 28.sp,
                                                modifier = Modifier.scale(scale)
                                            )
                                            Column {
                                                Text(
                                                    text = "GIF",
                                                    style = XMaxType.caption2Medium,
                                                    color = PureWhite.copy(alpha = 0.85f)
                                                )
                                                Text(
                                                    text = gif.badgeText,
                                                    style = XMaxType.subheadlineSemibold,
                                                    color = PureWhite
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentMenuBottomSheet(
    hdMedia: Boolean,
    viewOnce: Boolean,
    onToggleHd: (Boolean) -> Unit,
    onToggleViewOnce: (Boolean) -> Unit,
    onCamera: () -> Unit,
    onGalleryPhoto: () -> Unit,
    onMultiPhoto: () -> Unit,
    onVideo: () -> Unit,
    onDocument: () -> Unit,
    onAudioFile: () -> Unit,
    onContact: () -> Unit,
    onCurrentLocation: () -> Unit,
    onLiveLocation: (minutes: Int) -> Unit,
    onCreatePoll: () -> Unit,
    onCreateEvent: () -> Unit,
    onBusinessRecord: () -> Unit,
    onDismiss: () -> Unit
) {
    var showLiveChoices by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Share in Chat",
                    style = XMaxType.secondaryTitle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = hdMedia,
                        onClick = { onToggleHd(!hdMedia) },
                        label = { Text("HD", style = XMaxType.caption1Semibold) },
                        leadingIcon = { Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = viewOnce,
                        onClick = { onToggleViewOnce(!viewOnce) },
                        label = { Text("1x View", style = XMaxType.caption1Semibold.tabular()) },
                        leadingIcon = { Icon(Icons.Default.LooksOne, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            if (showLiveChoices) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF0FDF4),
                    border = BorderStroke(1.dp, EmeraldApproved.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Live Location Duration", style = XMaxType.subheadlineSemibold, color = ObsidianCharcoal)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 60).forEach { mins ->
                                Button(
                                    onClick = { onLiveLocation(mins) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (mins == 15) "15 Minutes" else "1 Hour", style = XMaxType.buttonCompact.tabular())
                                }
                            }
                        }
                    }
                }
            }

            val items = listOf(
                Triple("Camera", Icons.Default.CameraAlt, Color(0xFFEC4899)) to onCamera,
                Triple("Photo", Icons.Default.Image, Color(0xFF8B5CF6)) to onGalleryPhoto,
                Triple("Multi-Photo", Icons.Default.Collections, Color(0xFF6366F1)) to onMultiPhoto,
                Triple("Video", Icons.Default.Videocam, Color(0xFFEF4444)) to onVideo,
                Triple("Docs / ZIP", Icons.Default.Description, Color(0xFF0284C7)) to onDocument,
                Triple("Audio File", Icons.Default.AudioFile, Color(0xFFF97316)) to onAudioFile,
                Triple("Contact", Icons.Default.ContactPhone, Color(0xFF0EA5E9)) to onContact,
                Triple("Location", Icons.Default.LocationOn, Color(0xFF10B981)) to onCurrentLocation,
                Triple("Live Location", Icons.Default.MyLocation, Color(0xFF059669)) to { showLiveChoices = !showLiveChoices },
                Triple("Poll", Icons.Default.Poll, Color(0xFF7C3AED)) to onCreatePoll,
                Triple("Event", Icons.Default.Event, Color(0xFFD97706)) to onCreateEvent,
                Triple("Agency Ref", Icons.Default.Work, Color(0xFF1E293B)) to onBusinessRecord
            )

            items.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (meta, action) ->
                        val (label, icon, tint) = meta
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { action() }
                                .padding(vertical = 8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = tint.copy(alpha = 0.14f),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
                                }
                            }
                            Text(
                                text = label,
                                style = XMaxType.caption1Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun CreateEventDialog(
    onCreate: (title: String, location: String, timeMillis: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var presetIndex by remember { mutableStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val now = remember { System.currentTimeMillis() }
    val presets = remember(now) {
        listOf(
            "In 1 hour" to (now + 3_600_000L),
            "Tomorrow 10 AM" to (now + 86_400_000L),
            "In 3 days" to (now + 259_200_000L),
            "Next Week" to (now + 604_800_000L)
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = PureWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Schedule Group Event", style = XMaxType.secondaryTitle)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; errorMsg = null },
                    textStyle = XMaxType.body,
                    label = { Text("Event Title", style = XMaxType.inputLabel) },
                    placeholder = { Text("Creative review call / Shoot day", style = XMaxType.subheadline) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    textStyle = XMaxType.body,
                    label = { Text("Location / Link (optional)", style = XMaxType.inputLabel) },
                    placeholder = { Text("Studio B / Meet link", style = XMaxType.subheadline) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("When", style = XMaxType.caption1Semibold, color = StudioTextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEachIndexed { idx, (label, _) ->
                        FilterChip(
                            selected = presetIndex == idx,
                            onClick = { presetIndex = idx },
                            label = { Text(label, style = XMaxType.caption1Medium.tabular()) }
                        )
                    }
                }
                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg.orEmpty(), color = CrimsonBlocked, style = XMaxType.caption1)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.trim().isBlank()) {
                                errorMsg = "Enter an event title."
                            } else {
                                onCreate(title.trim(), location.trim(), presets[presetIndex].second)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                    ) {
                        Text("Create Event", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
    }
}

@Composable
fun ForwardMessageDialog(
    channels: List<ChatChannel>,
    myUid: String,
    onSelectChannel: (ChatChannel) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(channels, query) {
        val q = query.trim().lowercase()
        channels.filter { ch ->
            q.isBlank() || ch.title.lowercase().contains(q) || ch.topic.lowercase().contains(q)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = PureWhite,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Forward To…", style = XMaxType.secondaryTitle)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = XMaxType.body,
                    placeholder = { Text("Search chats…", style = XMaxType.subheadline) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { ch ->
                        Surface(
                            onClick = { onSelectChannel(ch) },
                            shape = RoundedCornerShape(14.dp),
                            color = MutedSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                StudioAvatar(
                                    name = if (ch.isSelfNotes(myUid)) "Saved Notes" else ch.title,
                                    size = 38.dp,
                                    borderColor = PureWhite,
                                    photoBase64 = ch.photoBase64
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (ch.isSelfNotes(myUid)) "Message Yourself (Saved)" else formatTitleCase(ch.title),
                                        style = XMaxType.headline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ch.channelTypeEnum().label,
                                        style = XMaxType.caption1,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoBottomSheet(
    message: ChatMessage,
    allMembers: List<Member>,
    onDismiss: () -> Unit
) {
    val sentTime = remember(message.createdAt) {
        val ms = message.createdAt?.toDate()?.time ?: System.currentTimeMillis()
        SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ms))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Message Information", style = XMaxType.secondaryTitle)

            Surface(shape = RoundedCornerShape(14.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Type: ${message.kindEnum().label}", style = XMaxType.subheadlineSemibold)
                    Text("Sent: $sentTime", style = XMaxType.numericFootnote, color = StudioTextSecondary)
                    if (message.storageProvider.isNotBlank()) {
                        Text(
                            text = "Storage: ${if (message.isCloudflareStored) "Cloudflare R2 Free Tier" else "Firestore Inline"}",
                            style = XMaxType.footnoteSemibold,
                            color = VioletPrimary
                        )
                    }
                    if (message.viewOnce) {
                        Text("View-Once enabled (${message.viewedByUids.size} viewed)", style = XMaxType.numericFootnote)
                    }
                    if (message.expiresAtMillis > 0L) {
                        val exp = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(message.expiresAtMillis))
                        Text("Disappears at: $exp", style = XMaxType.numericFootnote, color = Color(0xFFD97706))
                    }
                }
            }

            Text(
                text = "Read By (${message.readByUids.size})",
                style = XMaxType.numericHeadline,
                color = StudioBlueAccent
            )
            if (message.readByUids.isEmpty()) {
                Text("No read receipts yet.", style = XMaxType.footnote, color = StudioTextSecondary)
            } else {
                message.readByUids.forEach { uid ->
                    val name = allMembers.firstOrNull { it.uid == uid }?.fullName ?: uid
                    Text("• ${formatTitleCase(name)}", style = XMaxType.footnote)
                }
            }

            Text(
                text = "Delivered To (${message.deliveredToUids.size})",
                style = XMaxType.numericHeadline,
                color = StudioTextSecondary
            )
            if (message.deliveredToUids.isEmpty()) {
                Text("Pending recipient delivery receipt.", style = XMaxType.footnote, color = StudioTextSecondary)
            } else {
                message.deliveredToUids.forEach { uid ->
                    val name = allMembers.firstOrNull { it.uid == uid }?.fullName ?: uid
                    Text("• ${formatTitleCase(name)}", style = XMaxType.footnote)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun ReportContentDialog(
    title: String,
    onSubmit: (reason: String) -> Unit,
    onDismiss: () -> Unit
) {
    val reasons = listOf("Spam or unwanted", "Harassment or abuse", "Misleading / confidential leak", "Other")
    var selectedReason by remember { mutableStateOf(reasons.first()) }
    var extraNote by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = XMaxType.secondaryTitle, color = CrimsonBlocked)
                Text(
                    "Recent context is sent securely to workspace admins for review.",
                    style = XMaxType.footnote,
                    color = StudioTextSecondary
                )
                reasons.forEach { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReason = r }
                    ) {
                        RadioButton(selected = selectedReason == r, onClick = { selectedReason = r })
                        Text(r, style = XMaxType.subheadline)
                    }
                }
                OutlinedTextField(
                    value = extraNote,
                    onValueChange = { extraNote = it },
                    textStyle = XMaxType.body,
                    label = { Text("Details (optional)", style = XMaxType.inputLabel) },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val combined = if (extraNote.isBlank()) selectedReason else "$selectedReason: ${extraNote.trim()}"
                            onSubmit(combined)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                    ) {
                        Text("Submit Report", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
    }
}

@Composable
fun PinUnlockDialog(
    title: String = "Unlock Chat",
    subtitle: String = "Enter your secret chat lock code.",
    confirmSetupMode: Boolean = false,
    onVerified: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = VioletPrimary)
                    Text(title, style = XMaxType.secondaryTitle)
                }
                Text(subtitle, style = XMaxType.footnote, color = StudioTextSecondary)
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.take(12)
                        errorMsg = null
                    },
                    textStyle = XMaxType.body,
                    label = { Text(if (confirmSetupMode) "New Secret Code (min 4 chars)" else "Secret Code", style = XMaxType.inputLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_pin_input")
                )
                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg.orEmpty(), color = CrimsonBlocked, style = XMaxType.caption1)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val clean = pin.trim()
                            if (confirmSetupMode) {
                                if (clean.length < 4) {
                                    errorMsg = "Secret code must be at least 4 characters."
                                } else {
                                    ChatSettingsStore.setSecretCode(context, clean)
                                    onVerified(clean)
                                }
                            } else {
                                if (ChatSettingsStore.verifySecretCode(context, clean)) {
                                    onVerified(clean)
                                } else {
                                    errorMsg = "Incorrect secret code."
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                    ) {
                        Text(if (confirmSetupMode) "Save Code" else "Unlock", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsAndSecuritySheet(
    prefs: ChatUserPreferences,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showPinSetup by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Chat Settings & Privacy", style = XMaxType.secondaryTitle)

            // Chat Lock Secret Code
            Surface(shape = RoundedCornerShape(16.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Secret Code for Locked Chats", style = XMaxType.headline)
                            Text(
                                text = if (prefs.hasSecretCode) "Secret PIN active (${prefs.lockedChannelIds.size} locked)" else "Not configured",
                                style = XMaxType.numericCaption1,
                                color = StudioTextSecondary
                            )
                        }
                        TextButton(onClick = { showPinSetup = true }) {
                            Text(if (prefs.hasSecretCode) "Change" else "Set Code", style = XMaxType.buttonCompact)
                        }
                    }
                    if (prefs.hasSecretCode) {
                        TextButton(onClick = { ChatSettingsStore.clearSecretCode(context) }) {
                            Text("Remove Secret Code & Unlock All", style = XMaxType.buttonCompact, color = CrimsonBlocked)
                        }
                    }
                }
            }

            // Global Wallpaper
            Text("Canvas Tint & Wallpaper", style = XMaxType.headline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatVisualCatalog.wallpapers.forEach { wp ->
                    FilterChip(
                        selected = prefs.globalWallpaperId == wp.id,
                        onClick = { ChatSettingsStore.setWallpaper(context, wp.id) },
                        label = { Text(wp.label, style = XMaxType.caption1Medium) }
                    )
                }
            }

            // Bubble Theme
            Text("Chat Bubble Theme", style = XMaxType.headline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatVisualCatalog.bubbleThemes.forEach { bt ->
                    FilterChip(
                        selected = prefs.bubbleThemeId == bt.id,
                        onClick = { ChatSettingsStore.setBubbleTheme(context, bt.id) },
                        label = { Text(bt.label, style = XMaxType.caption1Medium) }
                    )
                }
            }

            // Media & Auto-Download Settings
            Text("Media & Storage", style = XMaxType.headline)
            Surface(shape = RoundedCornerShape(16.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-download photos", style = XMaxType.subheadline)
                        Switch(
                            checked = prefs.autoDownloadPhotos,
                            onCheckedChange = {
                                ChatSettingsStore.updateMediaSettings(context, it, prefs.autoDownloadAudio, prefs.autoDownloadDocs, prefs.mediaVisibleInGallery)
                            }
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-download voice & audio", style = XMaxType.subheadline)
                        Switch(
                            checked = prefs.autoDownloadAudio,
                            onCheckedChange = {
                                ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, it, prefs.autoDownloadDocs, prefs.mediaVisibleInGallery)
                            }
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-download documents", style = XMaxType.subheadline)
                        Switch(
                            checked = prefs.autoDownloadDocs,
                            onCheckedChange = {
                                ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, prefs.autoDownloadAudio, it, prefs.mediaVisibleInGallery)
                            }
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show saved media in device Gallery", style = XMaxType.subheadline)
                            Text("When off, downloads save privately inside app storage", style = XMaxType.caption1, color = StudioTextSecondary)
                        }
                        Switch(
                            checked = prefs.mediaVisibleInGallery,
                            onCheckedChange = {
                                ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, prefs.autoDownloadAudio, prefs.autoDownloadDocs, it)
                            }
                        )
                    }
                }
            }

            // Custom Chat Lists
            Text("Custom Chat Lists", style = XMaxType.headline)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    textStyle = XMaxType.body,
                    placeholder = { Text("New list name…", style = XMaxType.subheadline) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        ChatSettingsStore.addCustomList(context, newListName)
                        newListName = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) {
                    Text("Add", style = XMaxType.buttonCompact)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                prefs.customLists.forEach { list ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(list, style = XMaxType.caption1Medium) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete list",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { ChatSettingsStore.deleteCustomList(context, list) }
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showPinSetup) {
        PinUnlockDialog(
            title = "Set Secret Code",
            subtitle = "Use this PIN to hide & unlock protected conversations.",
            confirmSetupMode = true,
            onVerified = { showPinSetup = false },
            onDismiss = { showPinSetup = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredMessagesSheet(
    messages: List<ChatMessage>,
    myUid: String,
    onUnstar: (ChatMessage) -> Unit,
    onDismiss: () -> Unit
) {
    val starred = remember(messages, myUid) { messages.filter { it.isStarredBy(myUid) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Starred Messages (${starred.size})", style = XMaxType.secondaryTitle.tabular())
            if (starred.isEmpty()) {
                Text(
                    "Tap any message bubble in this conversation and choose Star to save important messages here.",
                    style = XMaxType.footnote,
                    color = StudioTextSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(starred, key = { it.id }) { msg ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatTitleCase(msg.senderName), style = XMaxType.caption1Semibold, color = VioletPrimary)
                                    Text(msg.body.ifBlank { msg.attachmentName.ifBlank { msg.kindEnum().label } }, style = XMaxType.subheadline, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onUnstar(msg) }) {
                                    Icon(Icons.Default.Star, contentDescription = "Unstar", tint = Color(0xFFF59E0B))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
