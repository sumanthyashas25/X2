package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatFileManager
import com.example.chat.ChatQrGenerator
import com.example.chat.ChatSettingsStore
import com.example.chat.ChatUserPreferences
import com.example.chat.realtime.MemberAvailability
import com.example.chat.realtime.RtdbPresenceManager
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.chat.StatusPrivacyEngine
import com.example.ui.components.AvatarWithAvailabilityBadge
import com.example.ui.components.SegmentedStatusAvatar
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun CreateGroupSheet(
    colleagues: List<Member>,
    statuses: List<ChatStatus> = emptyList(),
    myMember: Member? = null,
    onOpenStatuses: (List<ChatStatus>) -> Unit = {},
    onCreateGroup: (
        title: String,
        description: String,
        memberUids: List<String>,
        photoBase64: String,
        announcementOnly: Boolean,
        requireApproval: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = XMaxTheme.colors
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var photoBase64 by remember { mutableStateOf("") }
    var announcementOnly by remember { mutableStateOf(false) }
    var requireApproval by remember { mutableStateOf(false) }
    val selectedUids = remember { mutableStateListOf<String>() }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val companyPresence by remember {
        RtdbPresenceManager.observeCompanyPresence(CatalogueSeedData.DEFAULT_COMPANY_ID)
    }.collectAsStateWithLifecycle(initialValue = emptyMap())

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                ChatFileManager.prepareProfilePhotoBase64(context, uri).onSuccess { photoBase64 = it }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.outline),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("New Group / Community", style = XMaxType.secondaryTitle, color = palette.textPrimary)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(palette.surfaceMuted)
                            .clickable {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photoBase64.isNotBlank()) {
                            StudioAvatar(name = title.ifBlank { "G" }, size = 56.dp, borderColor = palette.surface, photoBase64 = photoBase64)
                        } else {
                            Icon(Icons.Default.AddAPhoto, contentDescription = "Group icon", tint = VioletPrimary)
                        }
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; errorMsg = null },
                        textStyle = XMaxType.body,
                        label = { Text("Group Name", style = XMaxType.inputLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    textStyle = XMaxType.body,
                    label = { Text("Group Description", style = XMaxType.inputLabel) },
                    placeholder = { Text("Purpose, guidelines, or campaign brief", style = XMaxType.subheadline) },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(shape = RoundedCornerShape(14.dp), color = palette.surfaceMuted, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Announcements Only (Admin post)", style = XMaxType.footnoteMedium)
                            Switch(checked = announcementOnly, onCheckedChange = { announcementOnly = it })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Approve New Members", style = XMaxType.footnoteMedium)
                            Switch(checked = requireApproval, onCheckedChange = { requireApproval = it })
                        }
                    }
                }

                Text("Add Members (${selectedUids.size} selected)", style = XMaxType.caption1Semibold.tabular(), color = palette.textSecondary)
                colleagues.forEach { person ->
                    val checked = person.uid in selectedUids
                    val availability = RtdbPresenceManager.resolveAvailability(person, companyPresence[person.uid])
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (checked) selectedUids.remove(person.uid) else selectedUids.add(person.uid)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            if (checked) selectedUids.remove(person.uid) else selectedUids.add(person.uid)
                        })
                        val prefsSnap = ChatSettingsStore.ensureLoaded(context)
                        val memberStatuses = remember(statuses, person.uid, myMember?.uid, prefsSnap.blockedUserUids) {
                            if (myMember == null) emptyList()
                            else StatusPrivacyEngine.visibleActiveStatusesForOwner(
                                statuses = statuses,
                                ownerUid = person.uid,
                                viewerUid = myMember.uid,
                                viewerMember = myMember,
                                blockedByViewerUids = prefsSnap.blockedUserUids
                            )
                        }
                        SegmentedStatusAvatar(
                            name = person.fullName,
                            availability = availability,
                            showAvailabilityBadge = true,
                            size = 34.dp,
                            borderColor = palette.surface,
                            photoBase64 = person.photoBase64,
                            activeStatuses = memberStatuses,
                            viewerUid = myMember?.uid.orEmpty(),
                            isOwnAvatar = false,
                            badgeTestTag = "group_create_status_${person.uid}",
                            onAvatarClick = if (memberStatuses.isNotEmpty()) {
                                { onOpenStatuses(memberStatuses) }
                            } else null
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(formatTitleCase(person.fullName), style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                            val roleLabel = person.jobRoleName.ifBlank { person.departmentName }
                            Text(
                                text = if (roleLabel.isNotBlank()) "${availability.label} • $roleLabel" else availability.label,
                                style = XMaxType.caption1,
                                color = when (availability) {
                                    MemberAvailability.ACTIVE -> EmeraldApproved
                                    MemberAvailability.BUSY -> CrimsonBlocked
                                    MemberAvailability.AWAY -> palette.textSecondary
                                }
                            )
                        }
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
                                errorMsg = "Group name is required."
                            } else {
                                onCreateGroup(title.trim(), description.trim(), selectedUids.toList(), photoBase64, announcementOnly, requireApproval)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                    ) {
                        Text("Create Group", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
    }
}

@Composable
fun JoinInviteLinkDialog(
    onJoin: (code: String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = palette.surface, border = BorderStroke(1.dp, palette.outline), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Join Group by Invite", style = XMaxType.secondaryTitle, color = palette.textPrimary)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    textStyle = XMaxType.body,
                    label = { Text("Invite Link or Code", style = XMaxType.inputLabel) },
                    placeholder = { Text("https://xmax.app/join/AB12CD34 or AB12CD34", style = XMaxType.subheadline) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { if (code.trim().isNotBlank()) onJoin(code.trim()) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                    ) {
                        Text("Join", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedConversationInfoSheet(
    channel: ChatChannel,
    messages: List<ChatMessage>,
    allMembers: List<Member>,
    myUid: String,
    isSuperAdmin: Boolean,
    prefs: ChatUserPreferences,
    onUpdateInfo: (title: String, desc: String, photoBase64: String?) -> Unit,
    onUpdatePermissions: (onlyAdminsMsg: Boolean, onlyAdminsEdit: Boolean, requireApproval: Boolean) -> Unit,
    onUpdateDisappearing: (Long) -> Unit,
    onMuteToggle: () -> Unit,
    onPinToggle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onFavouriteToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onResetInviteLink: () -> Unit,
    onApprovePending: (String) -> Unit,
    onDeclinePending: (String) -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onToggleAdmin: (String) -> Unit,
    onClearChat: () -> Unit,
    onExitGroup: () -> Unit,
    onReportGroup: () -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    onDismiss: () -> Unit,
    statuses: List<ChatStatus> = emptyList(),
    onOpenMemberStatuses: (List<ChatStatus>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = XMaxTheme.colors
    val isDark = XMaxTheme.isDark
    val isDirect = channel.channelTypeEnum() == ChatChannelType.DIRECT || channel.isSelfNotes(myUid)
    val isMuted = channel.isMutedFor(myUid)
    val isPinned = channel.isPinnedFor(myUid)
    val isArchived = channel.isArchivedFor(myUid)
    val isFav = channel.isFavouriteFor(myUid)
    val isLocked = channel.id in prefs.lockedChannelIds
    val isChannelAdmin = channel.isChannelAdmin(myUid, isSuperAdmin)
    val canEditInfo = isChannelAdmin || !channel.onlyAdminsCanEditInfo

    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showAddMemberPicker by remember { mutableStateOf(false) }
    var sharedTab by remember { mutableStateOf(0) } // 0=Media, 1=Docs, 2=Links
    val companyPresence by remember {
        RtdbPresenceManager.observeCompanyPresence(CatalogueSeedData.DEFAULT_COMPANY_ID)
    }.collectAsStateWithLifecycle(initialValue = emptyMap())

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && canEditInfo) {
            scope.launch {
                ChatFileManager.prepareProfilePhotoBase64(context, uri).onSuccess { b64 ->
                    onUpdateInfo(channel.title, channel.description.ifBlank { channel.topic }, b64)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.clickable(enabled = !isDirect && canEditInfo) {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            ) {
                StudioAvatar(
                    name = channel.title,
                    size = 76.dp,
                    borderColor = palette.surface,
                    photoBase64 = channel.photoBase64
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = formatTitleCase(channel.title),
                    style = XMaxType.secondaryTitle
                )
                if (!isDirect && canEditInfo) {
                    IconButton(onClick = { showEditInfoDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit group info", modifier = Modifier.size(16.dp))
                    }
                }
            }

            Text(
                text = channel.description.ifBlank { channel.topic }.ifBlank { "Workspace conversation" },
                style = XMaxType.footnote,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )

            // Quick toggles row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = isMuted,
                    onClick = onMuteToggle,
                    label = { Text(if (isMuted) "Muted" else "Mute", style = XMaxType.footnoteMedium) },
                    leadingIcon = { Icon(if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isPinned,
                    onClick = onPinToggle,
                    label = { Text(if (isPinned) "Pinned" else "Pin", style = XMaxType.footnoteMedium) },
                    leadingIcon = { Icon(Icons.Default.PushPin, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isFav,
                    onClick = onFavouriteToggle,
                    label = { Text(if (isFav) "Favourite" else "Fav", style = XMaxType.footnoteMedium) },
                    leadingIcon = { Icon(Icons.Default.Favorite, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isArchived,
                    onClick = onArchiveToggle,
                    label = { Text(if (isArchived) "Archived" else "Archive", style = XMaxType.footnoteMedium) },
                    leadingIcon = { Icon(Icons.Default.Archive, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isLocked,
                    onClick = onLockToggle,
                    label = { Text(if (isLocked) "Locked" else "Lock", style = XMaxType.footnoteMedium) },
                    leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) }
                )
            }

            // Custom Lists tagging
            if (prefs.customLists.isNotEmpty()) {
                val activeLists = prefs.channelListsMap[channel.id].orEmpty()
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Custom Lists", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        prefs.customLists.forEach { listName ->
                            FilterChip(
                                selected = listName in activeLists,
                                onClick = { ChatSettingsStore.toggleChannelInCustomList(context, channel.id, listName) },
                                label = { Text(listName, style = XMaxType.footnoteMedium) }
                            )
                        }
                    }
                }
            }

            // Disappearing Messages Timer
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Disappearing Messages", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0L to "Off", 86_400L to "24 Hours", 604_800L to "7 Days", 7_776_000L to "90 Days").forEach { (sec, label) ->
                        FilterChip(
                            selected = channel.disappearingSeconds == sec,
                            onClick = { onUpdateDisappearing(sec) },
                            label = { Text(label, style = XMaxType.footnoteMedium.tabular()) }
                        )
                    }
                }
            }

            // Thread Wallpaper override (Custom Gallery Image + Presets)
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val resolvedWp = ChatSettingsStore.resolveThreadWallpaper(prefs, channel.id)
                val perChatCustomPath = prefs.channelCustomWallpaperPaths[channel.id].orEmpty()
                val currentPresetWp = prefs.channelWallpapers[channel.id] ?: "default"
                val hasCustomOverride = perChatCustomPath.isNotBlank() || currentPresetWp != "default"
                val perChatPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            ChatFileManager.saveCustomChatWallpaper(context, uri, channel.id)
                                .onSuccess { savedPath ->
                                    ChatSettingsStore.setCustomWallpaperPath(context, savedPath, channel.id)
                                }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chat Wallpaper", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                    if (hasCustomOverride) {
                        TextButton(
                            onClick = {
                                if (perChatCustomPath.isNotBlank()) {
                                    ChatFileManager.deleteWallpaperFile(perChatCustomPath)
                                }
                                ChatSettingsStore.clearCustomWallpaper(context, channel.id)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Reset to Global", style = XMaxType.caption1Semibold, color = CrimsonBlocked)
                        }
                    }
                }

                // Active Wallpaper Preview + Pick Custom Image CTA
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surfaceMuted,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val previewBmp = remember(resolvedWp.customImagePath) {
                            ChatFileManager.loadWallpaperBitmap(resolvedWp.customImagePath)
                        }
                        val presetObj = ChatVisualCatalog.wallpaper(resolvedWp.presetId, !isDark)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        presetObj.baseColors
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (previewBmp != null) {
                                Image(
                                    bitmap = previewBmp.asImageBitmap(),
                                    contentDescription = "Active chat wallpaper preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.Wallpaper, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = when {
                                    perChatCustomPath.isNotBlank() -> "Custom Photo (This Chat)"
                                    currentPresetWp != "default" -> "${presetObj.label} (This Chat)"
                                    !resolvedWp.customImagePath.isNullOrBlank() -> "Using Global Custom Photo"
                                    else -> "Global: ${presetObj.label}"
                                },
                                style = XMaxType.subheadlineSemibold,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Applies only to this conversation",
                                style = XMaxType.caption1,
                                color = palette.textSecondary
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                perChatPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = palette.buttonPrimaryBg,
                                contentColor = palette.buttonPrimaryFg
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Gallery", style = XMaxType.caption1Semibold)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !hasCustomOverride,
                        onClick = {
                            if (perChatCustomPath.isNotBlank()) {
                                ChatFileManager.deleteWallpaperFile(perChatCustomPath)
                            }
                            ChatSettingsStore.clearCustomWallpaper(context, channel.id)
                        },
                        label = { Text("Global Default", style = XMaxType.footnoteMedium) }
                    )
                    ChatVisualCatalog.wallpapers.forEach { wp ->
                        FilterChip(
                            selected = perChatCustomPath.isBlank() && currentPresetWp == wp.id,
                            onClick = { ChatSettingsStore.setWallpaper(context, wp.id, channel.id) },
                            label = { Text(wp.label, style = XMaxType.footnoteMedium) }
                        )
                    }
                }
            }

            // Group Controls: Permissions, Invite Link + QR, Pending Approvals, Members
            if (!isDirect) {
                if (isChannelAdmin) {
                    Surface(shape = RoundedCornerShape(16.dp), color = palette.surfaceMuted, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Group Permissions", style = XMaxType.subheadlineSemibold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Only admins can send messages", style = XMaxType.footnote)
                                Switch(
                                    checked = channel.onlyAdminsCanMessage,
                                    onCheckedChange = { onUpdatePermissions(it, channel.onlyAdminsCanEditInfo, channel.requireAdminApproval) }
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Only admins edit group info", style = XMaxType.footnote)
                                Switch(
                                    checked = channel.onlyAdminsCanEditInfo,
                                    onCheckedChange = { onUpdatePermissions(channel.onlyAdminsCanMessage, it, channel.requireAdminApproval) }
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Admin approval for new members", style = XMaxType.footnote)
                                Switch(
                                    checked = channel.requireAdminApproval,
                                    onCheckedChange = { onUpdatePermissions(channel.onlyAdminsCanMessage, channel.onlyAdminsCanEditInfo, it) }
                                )
                            }
                        }
                    }

                    // Invite link & QR code
                    val inviteCode = channel.inviteCode.ifBlank { channel.id.take(8).uppercase() }
                    val inviteUrl = "https://xmax.app/join/$inviteCode"
                    Surface(shape = RoundedCornerShape(16.dp), color = palette.softVioletBg, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Group Invite Link", style = XMaxType.subheadlineSemibold, color = VioletPrimary)
                            Text(inviteUrl, style = XMaxType.monoTimer, color = palette.textPrimary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Group Invite Link", inviteUrl))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Copy", style = XMaxType.caption1Semibold)
                                }
                                OutlinedButton(
                                    onClick = { showQrDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("QR Code", style = XMaxType.caption1Semibold)
                                }
                                OutlinedButton(
                                    onClick = onResetInviteLink,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset", style = XMaxType.caption1Semibold)
                                }
                            }
                        }
                    }

                    // Pending Member Approvals
                    if (channel.pendingMemberUids.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Pending Join Requests (${channel.pendingMemberUids.size})", style = XMaxType.subheadlineSemibold.tabular(), color = CrimsonBlocked)
                            channel.pendingMemberUids.forEach { pUid ->
                                val pm = allMembers.firstOrNull { it.uid == pUid }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(formatTitleCase(pm?.fullName ?: pUid), style = XMaxType.subheadlineSemibold)
                                    Row {
                                        TextButton(onClick = { onApprovePending(pUid) }) { Text("Approve", style = XMaxType.footnoteSemibold, color = EmeraldApproved) }
                                        TextButton(onClick = { onDeclinePending(pUid) }) { Text("Decline", style = XMaxType.footnoteSemibold, color = CrimsonBlocked) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Members List + Add Member
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Members (${channel.participantUids.size.coerceAtLeast(1)})", style = XMaxType.headline.tabular())
                        if (isChannelAdmin) {
                            TextButton(onClick = { showAddMemberPicker = !showAddMemberPicker }) {
                                Text(if (showAddMemberPicker) "Done" else "+ Add Member", style = XMaxType.footnoteSemibold)
                            }
                        }
                    }
                    if (showAddMemberPicker && isChannelAdmin) {
                        val available = allMembers.filter { it.uid !in channel.participantUids && it.active }
                        available.forEach { cand ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddMember(cand.uid) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatTitleCase(cand.fullName), style = XMaxType.subheadlineMedium)
                                Text("+ Add", style = XMaxType.footnoteSemibold, color = VioletPrimary)
                            }
                        }
                    }

                    channel.participantUids.forEach { uid ->
                        val mem = allMembers.firstOrNull { it.uid == uid }
                        val isAdmin = uid in channel.adminUids || uid == channel.createdByUid
                        val availability = RtdbPresenceManager.resolveAvailability(mem, companyPresence[uid])
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = palette.surfaceElevated,
                            border = BorderStroke(1.dp, palette.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val meMember = remember(allMembers, myUid) { allMembers.firstOrNull { it.uid == myUid } }
                                val memberStatuses = remember(statuses, uid, myUid, prefs.blockedUserUids) {
                                    StatusPrivacyEngine.visibleActiveStatusesForOwner(
                                        statuses = statuses,
                                        ownerUid = uid,
                                        viewerUid = myUid,
                                        viewerMember = meMember,
                                        blockedByViewerUids = prefs.blockedUserUids
                                    )
                                }
                                SegmentedStatusAvatar(
                                    name = mem?.fullName ?: "Member",
                                    availability = availability,
                                    showAvailabilityBadge = true,
                                    size = 36.dp,
                                    borderColor = palette.surface,
                                    photoBase64 = mem?.photoBase64.orEmpty(),
                                    activeStatuses = memberStatuses,
                                    viewerUid = myUid,
                                    isOwnAvatar = uid == myUid,
                                    badgeTestTag = "group_member_status_$uid",
                                    onAvatarClick = if (memberStatuses.isNotEmpty()) {
                                        { onOpenMemberStatuses(memberStatuses) }
                                    } else null
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatTitleCase(mem?.fullName ?: uid) + if (uid == myUid) " (You)" else "", style = XMaxType.headline, color = palette.textPrimary)
                                    val rolePart = if (isAdmin) "Admin" else "Member"
                                    Text(
                                        text = "${availability.label} • $rolePart",
                                        style = XMaxType.caption1,
                                        color = when (availability) {
                                            MemberAvailability.ACTIVE -> EmeraldApproved
                                            MemberAvailability.BUSY -> CrimsonBlocked
                                            MemberAvailability.AWAY -> palette.textSecondary
                                        }
                                    )
                                }
                                if (isChannelAdmin && uid != myUid) {
                                    TextButton(onClick = { onToggleAdmin(uid) }) {
                                        Text(if (isAdmin) "Dismiss" else "Admin", style = XMaxType.caption1Semibold)
                                    }
                                    IconButton(onClick = { onRemoveMember(uid) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove", tint = CrimsonBlocked, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Shared Media / Documents / Links Browser
            val sharedMedia = remember(messages) {
                messages.filter { !it.isDeletedForEveryone && (it.attachmentTypeEnum() == ChatAttachmentType.IMAGE || it.attachmentTypeEnum() == ChatAttachmentType.VIDEO) }
            }
            val sharedDocs = remember(messages) {
                messages.filter { !it.isDeletedForEveryone && (it.attachmentTypeEnum() == ChatAttachmentType.FILE || it.attachmentTypeEnum() == ChatAttachmentType.AUDIO) }
            }
            val sharedLinks = remember(messages) {
                messages.filter { !it.isDeletedForEveryone && (it.linkUrl.isNotBlank() || ChatFileManager.extractHttpsUrls(it.body).isNotEmpty()) }
            }

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shared Media, Docs & Links", style = XMaxType.headline, color = palette.textPrimary)
                TabRow(selectedTabIndex = sharedTab, containerColor = palette.surfaceMuted, contentColor = palette.textPrimary) {
                    Tab(selected = sharedTab == 0, onClick = { sharedTab = 0 }, text = { Text("Media (${sharedMedia.size})", style = XMaxType.footnoteSemibold.tabular()) })
                    Tab(selected = sharedTab == 1, onClick = { sharedTab = 1 }, text = { Text("Docs (${sharedDocs.size})", style = XMaxType.footnoteSemibold.tabular()) })
                    Tab(selected = sharedTab == 2, onClick = { sharedTab = 2 }, text = { Text("Links (${sharedLinks.size})", style = XMaxType.footnoteSemibold.tabular()) })
                }
                when (sharedTab) {
                    0 -> if (sharedMedia.isEmpty()) Text("No photos or videos shared yet.", style = XMaxType.footnote, color = palette.textSecondary)
                    else sharedMedia.take(10).forEach { m ->
                        Surface(onClick = { onOpenAttachment(m) }, shape = RoundedCornerShape(10.dp), color = palette.surfaceMuted, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(m.attachmentName.ifBlank { m.kindEnum().label }, style = XMaxType.subheadlineMedium, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(ChatFileManager.formatFileSize(m.attachmentSizeBytes), style = XMaxType.caption1.tabular(), color = palette.textSecondary)
                            }
                        }
                    }
                    1 -> if (sharedDocs.isEmpty()) Text("No documents shared yet.", style = XMaxType.footnote, color = palette.textSecondary)
                    else sharedDocs.take(10).forEach { m ->
                        Surface(onClick = { onOpenAttachment(m) }, shape = RoundedCornerShape(10.dp), color = palette.surfaceMuted, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(m.attachmentName.ifBlank { "Document" }, style = XMaxType.subheadlineMedium, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(ChatFileManager.formatFileSize(m.attachmentSizeBytes), style = XMaxType.caption1.tabular(), color = palette.textSecondary)
                            }
                        }
                    }
                    else -> if (sharedLinks.isEmpty()) Text("No shared links yet.", style = XMaxType.footnote, color = palette.textSecondary)
                    else sharedLinks.take(10).forEach { m ->
                        val url = m.linkUrl.ifBlank { ChatFileManager.extractHttpsUrls(m.body).firstOrNull().orEmpty() }
                        Surface(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = palette.surfaceMuted,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(url, style = XMaxType.footnote, color = palette.skyInk, modifier = Modifier.padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Clear, Report, Exit Actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onClearChat,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.blushBg, contentColor = CrimsonBlocked),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Chat", style = XMaxType.caption1Semibold)
                }
                Button(
                    onClick = onReportGroup,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.butterBg, contentColor = palette.butterInk),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Report", style = XMaxType.caption1Semibold)
                }
                if (!isDirect && channel.id != "ch_general") {
                    Button(
                        onClick = onExitGroup,
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Exit Group", style = XMaxType.caption1Semibold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showEditInfoDialog) {
        var editTitle by remember { mutableStateOf(channel.title) }
        var editDesc by remember { mutableStateOf(channel.description.ifBlank { channel.topic }) }
        Dialog(onDismissRequest = { showEditInfoDialog = false }) {
            Surface(shape = RoundedCornerShape(22.dp), color = palette.surface, border = BorderStroke(1.dp, palette.outline), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Edit Group Info", style = XMaxType.secondaryTitle, color = palette.textPrimary)
                    OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, textStyle = XMaxType.body, label = { Text("Name", style = XMaxType.inputLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editDesc, onValueChange = { editDesc = it }, textStyle = XMaxType.body, label = { Text("Description", style = XMaxType.inputLabel) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showEditInfoDialog = false }) { Text("Cancel", style = XMaxType.buttonCompact) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onUpdateInfo(editTitle.trim(), editDesc.trim(), null)
                                showEditInfoDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                        ) { Text("Save", style = XMaxType.buttonCompact) }
                    }
                }
            }
        }
    }

    if (showQrDialog) {
        val inviteCode = channel.inviteCode.ifBlank { channel.id.take(8).uppercase() }
        val inviteUrl = "https://xmax.app/join/$inviteCode"
        val qrBmp = remember(inviteUrl) { ChatQrGenerator.generateQrBitmap(inviteUrl, 520) }
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = palette.surface, border = BorderStroke(1.dp, palette.outline), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Scan to Join Group", style = XMaxType.secondaryTitle, color = palette.textPrimary)
                    Surface(shape = RoundedCornerShape(16.dp), color = PureWhite) {
                        Image(bitmap = qrBmp.asImageBitmap(), contentDescription = "Group QR Code", modifier = Modifier.padding(8.dp).size(204.dp))
                    }
                    Text(inviteUrl, style = XMaxType.monoTimer, color = palette.textSecondary)
                    Button(
                        onClick = { showQrDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                    ) { Text("Done", style = XMaxType.buttonCompact) }
                }
            }
        }
    }
}
