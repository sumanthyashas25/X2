package com.example.ui.status

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatFileManager
import com.example.chat.ChatSettingsStore
import com.example.chat.StatusOwnerSummary
import com.example.chat.StatusPrivacyEngine
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.ChatViewModel
import com.example.ui.StatusUploadJob
import com.example.ui.StatusUploadJobStatus
import com.example.ui.components.SegmentedStatusAvatar
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun StatusTabScreen(
    state: AgencyUiState,
    chatViewModel: ChatViewModel,
    onOpenDirectChannel: (ChatChannel) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = XMaxTheme.colors
    val member = state.myMember ?: return
    val companyId = state.company?.id?.ifBlank { CatalogueSeedData.DEFAULT_COMPANY_ID }
        ?: CatalogueSeedData.DEFAULT_COMPANY_ID

    LaunchedEffect(Unit) {
        ChatSettingsStore.ensureLoaded(context)
    }

    LaunchedEffect(
        state.company?.statusCircleColorId,
        state.company?.statusCircleUnviewedHex,
        state.company?.statusCircleAccentHex,
        state.company?.statusCircleOwnHex,
        state.company?.statusCircleGradientEnabled
    ) {
        val comp = state.company ?: return@LaunchedEffect
        ChatSettingsStore.syncCompanyStatusCircleTheme(
            context = context,
            colorId = comp.statusCircleColorId,
            unviewedHex = comp.statusCircleUnviewedHex,
            accentHex = comp.statusCircleAccentHex,
            ownHex = comp.statusCircleOwnHex,
            gradientEnabled = comp.statusCircleGradientEnabled
        )
    }

    val prefs by ChatSettingsStore.prefsFlow.collectAsStateWithLifecycle()
    val uploadJobs by chatViewModel.statusUploadJobs.collectAsStateWithLifecycle()

    // Live ticker so expired statuses disappear automatically
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val categorized = remember(
        state.chatStatuses,
        member,
        state.members,
        prefs.mutedStatusOwnerUids,
        prefs.blockedUserUids,
        nowMillis
    ) {
        StatusPrivacyEngine.categorizeStatuses(
            statuses = state.chatStatuses,
            myMember = member,
            allMembers = state.members,
            mutedOwnerUids = prefs.mutedStatusOwnerUids,
            blockedByViewerUids = prefs.blockedUserUids,
            nowMillis = nowMillis
        )
    }

    var creatorInitialMode by remember { mutableStateOf<StatusKind?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showCircleColorDialog by remember { mutableStateOf(false) }
    var showManageMySheet by remember { mutableStateOf(false) }
    var showMutedSection by remember { mutableStateOf(false) }

    // Viewer state: list of (Member, List<ChatStatus>) + starting ownerUid
    var viewerGroups by remember { mutableStateOf<List<Pair<Member, List<ChatStatus>>>?>(null) }
    var viewerStartOwnerUid by remember { mutableStateOf("") }

    val approvedPeers = remember(state.members, member.uid) {
        state.members.filter {
            it.statusEnum() == MembershipStatus.APPROVED && it.active && it.uid != member.uid
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("status_tab_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Updates Header + Privacy, Blocked & Add Controls (matching WhatsApp Updates structure)
        item(key = "status_header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (onBack != null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("status_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = palette.textPrimary
                                )
                            }
                        }
                        Text(
                            text = "Updates",
                            style = XMaxType.primaryTitle,
                            color = palette.textPrimary
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { creatorInitialMode = StatusKind.TEXT },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = palette.buttonPrimaryBg,
                                contentColor = palette.buttonPrimaryFg
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("status_tab_add_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", style = XMaxType.buttonCompact)
                        }
                        Surface(
                            onClick = { showSettingsDialog = true },
                            shape = CircleShape,
                            color = palette.surface,
                            border = BorderStroke(1.dp, palette.outline),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("status_settings_btn")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Status Settings",
                                    tint = palette.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status",
                        style = XMaxType.sectionHeading,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "24h team updates • ${categorized.totalUnviewedContacts} unread",
                        style = XMaxType.numericFootnote,
                        color = palette.textSecondary
                    )
                }
            }
        }

        // Active Upload Queue (Progress, Cancel, Retry)
        if (uploadJobs.isNotEmpty()) {
            items(uploadJobs, key = { it.id }) { job ->
                StatusUploadJobCard(
                    job = job,
                    onCancel = { chatViewModel.cancelStatusUpload(job.id) },
                    onRetry = { chatViewModel.retryStatusUpload(job.id, member) },
                    onDismiss = { chatViewModel.dismissStatusUploadJob(job.id) }
                )
            }
        }

        // Horizontal Status Card Strip ("Add status" + Team Update Cards) matching WhatsApp Updates screenshot
        item(key = "status_cards_strip") {
            val myActive = categorized.myStatuses
            val latestMy = myActive.maxByOrNull { it.createdAtMillis }
            val allVisibleUpdates = categorized.recentUpdates + categorized.viewedUpdates
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Card 1: Add status / My Status card
                val myLatestBmp = remember(latestMy?.id, latestMy?.imageDataBase64) {
                    latestMy?.imageDataBase64?.takeIf { it.isNotBlank() }?.let {
                        ChatFileManager.decodeBase64Bitmap(it, 360)
                    }
                }
                Surface(
                    onClick = {
                        if (myActive.isNotEmpty()) {
                            viewerGroups = listOf(member to myActive)
                            viewerStartOwnerUid = member.uid
                        } else {
                            creatorInitialMode = StatusKind.TEXT
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier
                        .width(114.dp)
                        .height(172.dp)
                        .testTag("my_status_card")
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (myLatestBmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = myLatestBmp.asImageBitmap(),
                                contentDescription = "My latest status",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.65f))
                                        )
                                    )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(bottom = 14.dp),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            SegmentedStatusAvatar(
                                name = member.fullName,
                                size = 54.dp,
                                borderColor = palette.surface,
                                photoBase64 = member.photoBase64,
                                activeStatuses = myActive,
                                viewerUid = member.uid,
                                isOwnAvatar = true,
                                onAvatarClick = if (myActive.isNotEmpty()) {
                                    {
                                        viewerGroups = listOf(member to myActive)
                                        viewerStartOwnerUid = member.uid
                                    }
                                } else null
                            )
                            Surface(
                                onClick = { creatorInitialMode = StatusKind.IMAGE },
                                shape = CircleShape,
                                color = EmeraldApproved,
                                border = BorderStroke(2.dp, palette.surface),
                                modifier = Modifier.size(22.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, contentDescription = "Quick add status", tint = PureWhite, modifier = Modifier.size(13.dp))
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (myActive.isEmpty()) "Add status" else "My Status (${myActive.size})",
                                style = XMaxType.footnoteSemibold,
                                color = if (myLatestBmp != null) PureWhite else palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (myActive.isNotEmpty()) {
                                Text(
                                    text = "Tap to view",
                                    style = XMaxType.caption2Medium,
                                    color = if (myLatestBmp != null) PureWhite.copy(alpha = 0.85f) else palette.textSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Subsequent Cards: Team Status Cards with Segmented Rings
                allVisibleUpdates.forEach { summary ->
                    val latest = summary.statuses.maxByOrNull { it.createdAtMillis }
                    val thumbBmp = remember(latest?.id, latest?.imageDataBase64) {
                        latest?.imageDataBase64?.takeIf { it.isNotBlank() }?.let {
                            ChatFileManager.decodeBase64Bitmap(it, 360)
                        }
                    }
                    val cardColors = remember(summary.owner.uid) {
                        val idx = (summary.owner.uid.hashCode() and 0x7fffffff) % 4
                        when (idx) {
                            0 -> listOf(Color(0xFF4F46E5), Color(0xFF7C3AED))
                            1 -> listOf(Color(0xFF0F766E), Color(0xFF0284C7))
                            2 -> listOf(Color(0xFFBE185D), Color(0xFF7C3AED))
                            else -> listOf(Color(0xFF1E293B), Color(0xFF334155))
                        }
                    }
                    Surface(
                        onClick = {
                            viewerGroups = allVisibleUpdates.map { it.owner to it.statuses }
                            viewerStartOwnerUid = summary.owner.uid
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = cardColors.first(),
                        border = BorderStroke(1.dp, palette.outline),
                        modifier = Modifier
                            .width(114.dp)
                            .height(172.dp)
                            .testTag("status_card_${summary.owner.uid}")
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(cardColors))
                        ) {
                            if (thumbBmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = thumbBmp.asImageBitmap(),
                                    contentDescription = "${summary.owner.fullName} status preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (!latest?.effectiveDisplayText().isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = latest?.effectiveDisplayText().orEmpty(),
                                        style = XMaxType.caption1Medium,
                                        color = PureWhite,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.72f))
                                        )
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                SegmentedStatusAvatar(
                                    name = summary.owner.fullName,
                                    size = 38.dp,
                                    borderColor = PureWhite,
                                    photoBase64 = summary.owner.photoBase64,
                                    activeStatuses = summary.statuses,
                                    viewerUid = member.uid
                                )
                            }
                            Text(
                                text = formatTitleCase(summary.owner.fullName),
                                style = XMaxType.footnoteSemibold,
                                color = PureWhite,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        // My Status Summary / Manage Bar (when user has active updates)
        if (categorized.myStatuses.isNotEmpty()) {
            item(key = "my_status_manage_bar") {
                val myActive = categorized.myStatuses
                val totalViews = myActive.sumOf { it.viewerUids.size }
                val totalReactions = myActive.sumOf { it.reactions.size }
                val latest = myActive.maxByOrNull { it.createdAtMillis }
                Surface(
                    onClick = { showManageMySheet = true },
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("My Active Statuses (${myActive.size})", style = XMaxType.headline.tabular(), color = palette.textPrimary)
                            Text(
                                text = "$totalViews views • $totalReactions reactions • ${formatRelativeStatusTime(latest?.createdAtMillis ?: 0L, nowMillis)}",
                                style = XMaxType.numericFootnote,
                                color = palette.textSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = { showManageMySheet = true },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("my_status_manage_btn")
                        ) {
                            Text("Manage", style = XMaxType.buttonCompact)
                        }
                    }
                }
            }
        }

        // Quick Creation Format Row
        item(key = "quick_create_strip") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusKind.entries.forEach { kind ->
                    val icon = when (kind) {
                        StatusKind.TEXT -> Icons.Default.Title
                        StatusKind.IMAGE -> Icons.Default.PhotoCamera
                        StatusKind.VIDEO -> Icons.Default.Videocam
                        StatusKind.VOICE -> Icons.Default.Mic
                    }
                    Surface(
                        onClick = { creatorInitialMode = kind },
                        shape = RoundedCornerShape(14.dp),
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.outline),
                        modifier = Modifier.testTag("status_quick_${kind.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(icon, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(18.dp))
                            Text(kind.label, style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                        }
                    }
                }
            }
        }

        // Recent Status Updates (Unviewed)
        item(key = "recent_header") {
            Text(
                text = "Recent Updates (${categorized.recentUpdates.size})",
                style = XMaxType.sectionHeading.tabular(),
                color = palette.textPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (categorized.recentUpdates.isEmpty()) {
            item(key = "recent_empty") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "All caught up! New team updates will appear here.",
                        style = XMaxType.subheadline,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(categorized.recentUpdates, key = { "recent_${it.owner.uid}" }) { summary ->
                StatusOwnerRowCard(
                    summary = summary,
                    viewerUid = member.uid,
                    nowMillis = nowMillis,
                    onClick = {
                        viewerGroups = categorized.recentUpdates.map { it.owner to it.statuses }
                        viewerStartOwnerUid = summary.owner.uid
                    }
                )
            }
        }

        // Viewed Status Updates
        if (categorized.viewedUpdates.isNotEmpty()) {
            item(key = "viewed_header") {
                Text(
                    text = "Viewed Updates (${categorized.viewedUpdates.size})",
                    style = XMaxType.sectionHeading.tabular(),
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(categorized.viewedUpdates, key = { "viewed_${it.owner.uid}" }) { summary ->
                StatusOwnerRowCard(
                    summary = summary,
                    viewerUid = member.uid,
                    nowMillis = nowMillis,
                    onClick = {
                        viewerGroups = categorized.viewedUpdates.map { it.owner to it.statuses }
                        viewerStartOwnerUid = summary.owner.uid
                    }
                )
            }
        }

        // Muted Status Updates (Collapsible)
        if (categorized.mutedUpdates.isNotEmpty()) {
            item(key = "muted_header") {
                Surface(
                    onClick = { showMutedSection = !showMutedSection },
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surfaceMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("muted_updates_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Muted Updates (${categorized.mutedUpdates.size})",
                            style = XMaxType.headline.tabular(),
                            color = palette.textSecondary
                        )
                        Icon(
                            imageVector = if (showMutedSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle muted updates",
                            tint = palette.textSecondary
                        )
                    }
                }
            }
            if (showMutedSection) {
                items(categorized.mutedUpdates, key = { "muted_${it.owner.uid}" }) { summary ->
                    StatusOwnerRowCard(
                        summary = summary,
                        viewerUid = member.uid,
                        nowMillis = nowMillis,
                        trailingActionLabel = "Unmute",
                        onTrailingAction = {
                            ChatSettingsStore.setMutedStatusOwner(context, summary.owner.uid, false)
                        },
                        onClick = {
                            viewerGroups = listOf(summary.owner to summary.statuses)
                            viewerStartOwnerUid = summary.owner.uid
                        }
                    )
                }
            }
        }
    }

    // Create Status Studio Dialog
    creatorInitialMode?.let { mode ->
        CreateStatusStudioDialog(
            initialMode = mode,
            myMember = member,
            allMembers = state.members,
            onPublishQueue = { submissions ->
                chatViewModel.enqueueStatusUploads(submissions, member)
            },
            onDismiss = { creatorInitialMode = null }
        )
    }

    // Full-Screen Status Viewer
    viewerGroups?.let { groups ->
        EnhancedFullScreenStatusViewer(
            statusGroups = groups,
            initialOwnerUid = viewerStartOwnerUid,
            myMember = member,
            allMembers = state.members,
            companyId = companyId,
            mutedOwnerUids = prefs.mutedStatusOwnerUids,
            blockedUserUids = prefs.blockedUserUids,
            onMarkViewed = { sId -> chatViewModel.markStatusViewed(sId, member.uid) },
            onReact = { st, owner, emoji ->
                chatViewModel.reactToStatusAndSendDm(st, owner, member, emoji)
            },
            onReplyDm = { st, owner, reply ->
                chatViewModel.replyToStatusInDm(st, owner, member, reply)
            },
            onToggleMute = { ownerUid ->
                ChatSettingsStore.toggleMuteStatusOwner(context, ownerUid)
            },
            onToggleBlock = { ownerUid ->
                ChatSettingsStore.toggleBlockUser(context, ownerUid)
                val nextBlocked = ChatSettingsStore.ensureLoaded(context).blockedUserUids.toList()
                val pMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
                    .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
                chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                    activeMyStatuses = categorized.myStatuses,
                    privacyMode = pMode,
                    allowedUids = prefs.statusAllowedUids.toList(),
                    excludedUids = prefs.statusExcludedUids.toList(),
                    blockedUids = nextBlocked,
                    privacyLabel = if (pMode == StatusPrivacyMode.SELECTED_ONLY) prefs.statusAudienceLabel else "",
                    privacyEmoji = if (pMode == StatusPrivacyMode.SELECTED_ONLY) prefs.statusAudienceEmoji else ""
                )
            },
            onReportStatus = { st, reason ->
                chatViewModel.reportStatus(st, reason, member)
            },
            onDeleteStatus = { st ->
                chatViewModel.deleteStatus(st.id, st.effectiveStoragePath())
            },
            onDeleteAllMyStatuses = { list ->
                chatViewModel.deleteAllMyStatuses(list)
            },
            onOpenResponderChat = { target ->
                chatViewModel.openOrStartDm(target, member) { ch ->
                    onOpenDirectChannel(ch)
                }
            },
            onDismiss = { viewerGroups = null }
        )
    }

    // My Status Management Sheet
    if (showManageMySheet) {
        MyStatusManagementSheet(
            myStatuses = categorized.myStatuses,
            allMembers = state.members,
            companyId = companyId,
            onDeleteStatus = { st -> chatViewModel.deleteStatus(st.id, st.effectiveStoragePath()) },
            onDeleteAll = {
                chatViewModel.deleteAllMyStatuses(categorized.myStatuses)
                showManageMySheet = false
            },
            onOpenResponderChat = { target ->
                showManageMySheet = false
                chatViewModel.openOrStartDm(target, member) { ch ->
                    onOpenDirectChannel(ch)
                }
            },
            onDismiss = { showManageMySheet = false }
        )
    }

    // Unified Status Settings Sheet (Privacy, Blocked Contacts, and Super Admin Future Circle Colors)
    if (showSettingsDialog) {
        StatusSettingsDialog(
            member = member,
            prefs = prefs,
            onOpenPrivacy = { showPrivacyDialog = true },
            onOpenBlockedUsers = { showBlockedDialog = true },
            onOpenStatusCircleColors = { showCircleColorDialog = true },
            onDismiss = { showSettingsDialog = false }
        )
    }

    // Redesigned Status Privacy Panel (syncs live to active statuses + saved default)
    if (showPrivacyDialog) {
        val currentMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
            .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
        StatusPrivacyPanelDialog(
            myMember = member,
            allMembers = approvedPeers,
            initialMode = currentMode,
            initialAllowedUids = prefs.statusAllowedUids,
            initialExcludedUids = prefs.statusExcludedUids,
            initialAudienceLabel = prefs.statusAudienceLabel,
            initialAudienceEmoji = prefs.statusAudienceEmoji,
            prefs = prefs,
            onSave = { mode, allow, excl, label, emoji ->
                ChatSettingsStore.updateStatusPrivacy(context, mode.name, allow, excl, label, emoji)
                chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                    activeMyStatuses = categorized.myStatuses,
                    privacyMode = mode,
                    allowedUids = allow.toList(),
                    excludedUids = excl.toList(),
                    blockedUids = prefs.blockedUserUids.toList(),
                    privacyLabel = if (mode == StatusPrivacyMode.SELECTED_ONLY) label else "",
                    privacyEmoji = if (mode == StatusPrivacyMode.SELECTED_ONLY) emoji else ""
                )
                showPrivacyDialog = false
            },
            onDismiss = { showPrivacyDialog = false }
        )
    }

    // Super Admin Exclusive Future Status Circle Colors Dialog
    if (showCircleColorDialog && member.accessLevelEnum() == AccessLevel.SUPER_ADMIN) {
        SuperAdminStatusCircleColorDialog(
            member = member,
            activeColorId = prefs.workspaceStatusCircleColorId,
            onSelectPreset = { preset ->
                ChatSettingsStore.syncCompanyStatusCircleTheme(
                    context = context,
                    colorId = preset.id,
                    unviewedHex = preset.unviewedHex,
                    accentHex = preset.accentHex,
                    ownHex = preset.ownHex,
                    gradientEnabled = preset.gradientEnabled
                )
                chatViewModel.updateCompanyStatusCircleTheme(preset, member)
                showCircleColorDialog = false
            },
            onDismiss = { showCircleColorDialog = false }
        )
    }

    // Blocked Users Dialog (enforces mutual invisibility for statuses & rings)
    if (showBlockedDialog) {
        Dialog(onDismissRequest = { showBlockedDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Blocked Users (${prefs.blockedUserUids.size})", style = XMaxType.secondaryTitle.tabular(), color = palette.textPrimary)
                    Text(
                        text = "Blocked users cannot view your statuses or status indicators, and their updates remain hidden from you.",
                        style = XMaxType.footnote,
                        color = palette.textSecondary
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(approvedPeers, key = { it.uid }) { peer ->
                            val isBlocked = peer.uid in prefs.blockedUserUids
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        ChatSettingsStore.setBlockedUser(context, peer.uid, !isBlocked)
                                        val nextBlocked = ChatSettingsStore.ensureLoaded(context).blockedUserUids.toList()
                                        val pMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
                                            .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
                                        chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                                            activeMyStatuses = categorized.myStatuses,
                                            privacyMode = pMode,
                                            allowedUids = prefs.statusAllowedUids.toList(),
                                            excludedUids = prefs.statusExcludedUids.toList(),
                                            blockedUids = nextBlocked,
                                            privacyLabel = if (pMode == StatusPrivacyMode.SELECTED_ONLY) prefs.statusAudienceLabel else "",
                                            privacyEmoji = if (pMode == StatusPrivacyMode.SELECTED_ONLY) prefs.statusAudienceEmoji else ""
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Checkbox(
                                    checked = isBlocked,
                                    onCheckedChange = { checked ->
                                        ChatSettingsStore.setBlockedUser(context, peer.uid, checked)
                                        val nextBlocked = ChatSettingsStore.ensureLoaded(context).blockedUserUids.toList()
                                        val pMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
                                            .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
                                        chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                                            activeMyStatuses = categorized.myStatuses,
                                            privacyMode = pMode,
                                            allowedUids = prefs.statusAllowedUids.toList(),
                                            excludedUids = prefs.statusExcludedUids.toList(),
                                            blockedUids = nextBlocked,
                                            privacyLabel = if (pMode == StatusPrivacyMode.SELECTED_ONLY) prefs.statusAudienceLabel else "",
                                            privacyEmoji = if (pMode == StatusPrivacyMode.SELECTED_ONLY) prefs.statusAudienceEmoji else ""
                                        )
                                    }
                                )
                                StudioAvatar(name = peer.fullName, size = 34.dp, photoBase64 = peer.photoBase64)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatTitleCase(peer.fullName), style = XMaxType.headline, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(peer.jobRoleName.ifBlank { peer.departmentName }, style = XMaxType.caption1, color = palette.textSecondary)
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { showBlockedDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                        ) {
                            Text("Done", style = XMaxType.buttonCompact)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusOwnerRowCard(
    summary: StatusOwnerSummary,
    viewerUid: String,
    nowMillis: Long,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    val latest = summary.statuses.maxByOrNull { it.createdAtMillis }
    val previewLabel = latest?.let { st ->
        val timeText = formatRelativeStatusTime(st.createdAtMillis, nowMillis)
        val kindTag = st.kindEnum().label
        if (summary.statuses.size > 1) "${summary.statuses.size} updates ($kindTag) • $timeText"
        else "$kindTag • $timeText"
    } ?: "Active update"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_owner_row_${summary.owner.uid}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SegmentedStatusAvatar(
                name = summary.owner.fullName,
                size = 48.dp,
                borderColor = palette.surface,
                photoBase64 = summary.owner.photoBase64,
                activeStatuses = summary.statuses,
                viewerUid = viewerUid,
                onAvatarClick = onClick
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatTitleCase(summary.owner.fullName),
                    style = XMaxType.headline,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = previewLabel,
                    style = XMaxType.numericFootnote,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailingActionLabel != null && onTrailingAction != null) {
                TextButton(onClick = onTrailingAction) {
                    Text(trailingActionLabel, style = XMaxType.buttonCompact)
                }
            } else if (summary.unviewedCount > 0) {
                StatusPill(
                    label = "${summary.unviewedCount} new",
                    containerColor = palette.mintBg,
                    contentColor = palette.mintInk
                )
            }
        }
    }
}

@Composable
private fun StatusUploadJobCard(
    job: StatusUploadJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (job.status == StatusUploadJobStatus.FAILED) palette.blushBg else palette.softVioletBg,
        border = BorderStroke(
            1.dp,
            if (job.status == StatusUploadJobStatus.FAILED) CrimsonBlocked.copy(alpha = 0.4f) else VioletPrimary.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_upload_job_${job.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when (job.status) {
                        StatusUploadJobStatus.FAILED -> Icons.Default.ErrorOutline
                        StatusUploadJobStatus.DONE -> Icons.Default.CheckCircle
                        else -> Icons.Default.CloudUpload
                    },
                    contentDescription = null,
                    tint = if (job.status == StatusUploadJobStatus.FAILED) CrimsonBlocked else VioletPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${job.kind.label}: ${job.label}",
                        style = XMaxType.headline,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when (job.status) {
                        StatusUploadJobStatus.QUEUED -> "Queued for upload…"
                        StatusUploadJobStatus.UPLOADING -> "Uploading ${(job.progress * 100).toInt()}%…"
                        StatusUploadJobStatus.DONE -> "Published!"
                        StatusUploadJobStatus.FAILED -> job.errorMessage.ifBlank { "Upload failed" }
                        StatusUploadJobStatus.CANCELLED -> "Cancelled"
                    }
                    Text(subtitle, style = XMaxType.numericCaption1, color = palette.textSecondary)
                }

                when (job.status) {
                    StatusUploadJobStatus.QUEUED, StatusUploadJobStatus.UPLOADING -> {
                        TextButton(onClick = onCancel) {
                            Text("Cancel", style = XMaxType.buttonCompact, color = CrimsonBlocked)
                        }
                    }
                    StatusUploadJobStatus.FAILED -> {
                        TextButton(onClick = onRetry) {
                            Text("Retry", style = XMaxType.buttonCompact)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss failed upload", modifier = Modifier.size(16.dp))
                        }
                    }
                    else -> Unit
                }
            }
            if (job.status == StatusUploadJobStatus.QUEUED || job.status == StatusUploadJobStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0.05f, 1f) },
                    color = VioletPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                )
            }
        }
    }
}
