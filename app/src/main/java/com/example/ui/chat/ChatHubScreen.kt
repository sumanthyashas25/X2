package com.example.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatFileManager
import com.example.chat.ChatSettingsStore
import com.example.chat.StagedChatAttachment
import com.example.chat.StatusPrivacyEngine
import com.example.chat.realtime.MemberAvailability
import com.example.chat.realtime.RtdbPresenceManager
import com.example.chat.remote.WorkerBridgeClient
import com.example.chat.remote.WorkerResult
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.ChatFilterTab
import com.example.ui.ChatViewModel
import com.example.ui.components.AvatarWithAvailabilityBadge
import com.example.ui.components.SegmentedStatusAvatar
import com.example.ui.components.StudioAvatar
import com.example.ui.components.availabilityDotColor
import com.example.ui.components.formatTitleCase
import com.example.ui.components.pastelAmbientBackdrop
import com.example.ui.status.StatusTabScreen
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChatHubBottomTab(
    val label: String,
    val icon: ImageVector,
    val inactiveIcon: ImageVector
) {
    CHATS("Chats", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    STATUS("Updates", Icons.Filled.Adjust, Icons.Outlined.Adjust),
    DIRECTORY("People", Icons.Filled.People, Icons.Outlined.People),
    SETTINGS("You", Icons.Filled.Person, Icons.Outlined.Person)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatHubScreen(
    member: Member,
    isSuperAdmin: Boolean,
    channels: List<ChatChannel>,
    statuses: List<ChatStatus>,
    colleagues: List<Member>,
    departments: List<Department>,
    activeChannel: ChatChannel,
    searchQuery: String,
    filterTab: ChatFilterTab,
    selectedCustomList: String?,
    composerText: String,
    stagedAttachment: StagedChatAttachment?,
    isProcessingFile: Boolean,
    openingThreadLabel: String?,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilterTab: (ChatFilterTab) -> Unit,
    onSelectCustomList: (String?) -> Unit,
    onComposerTextChange: (String) -> Unit,
    onClearAttachment: () -> Unit,
    onOpenChannelsSheet: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFiles: () -> Unit,
    onPickImages: () -> Unit,
    onSelectChannel: (ChatChannel) -> Unit,
    onOpenDirectChat: (Member) -> Unit,
    onOpenDepartmentChat: (Department) -> Unit,
    onOpenSavedNotes: () -> Unit,
    onOpenCreateGroup: () -> Unit,
    onOpenJoinByInvite: () -> Unit,
    onOpenCreateStatus: () -> Unit,
    onOpenStatusViewer: (ownerUid: String, list: List<ChatStatus>) -> Unit,
    onTogglePinChannel: (ChatChannel) -> Unit,
    onToggleMuteChannel: (ChatChannel) -> Unit,
    onToggleArchiveChannel: (ChatChannel) -> Unit,
    onToggleFavouriteChannel: (ChatChannel) -> Unit,
    onMarkReadUnread: (ChatChannel, Boolean) -> Unit,
    onDeleteChannel: (String) -> Unit,
    onCreateNewChannelClick: () -> Unit,
    onSendQuickCompose: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit = onBack,
    onSetCompanyDefaultWallpaper: (presetId: String, customUrl: String) -> Unit = { _, _ -> },
    state: AgencyUiState? = null,
    chatViewModel: ChatViewModel? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = XMaxTheme.colors
    LaunchedEffect(Unit) {
        ChatSettingsStore.ensureLoaded(context)
    }
    LaunchedEffect(
        state?.company?.statusCircleColorId,
        state?.company?.statusCircleUnviewedHex,
        state?.company?.statusCircleAccentHex,
        state?.company?.statusCircleOwnHex,
        state?.company?.statusCircleGradientEnabled
    ) {
        val comp = state?.company ?: return@LaunchedEffect
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
    val companyId = state?.company?.id?.ifBlank { CatalogueSeedData.DEFAULT_COMPANY_ID }
        ?: CatalogueSeedData.DEFAULT_COMPANY_ID
    val companyPresence by RtdbPresenceManager.observeCompanyPresence(companyId)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    var liveNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000L)
            liveNowMillis = System.currentTimeMillis()
        }
    }

    val categorizedStatuses = remember(statuses, member, colleagues, prefs.mutedStatusOwnerUids, prefs.blockedUserUids, liveNowMillis) {
        StatusPrivacyEngine.categorizeStatuses(
            statuses = statuses,
            myMember = member,
            allMembers = colleagues + member,
            mutedOwnerUids = prefs.mutedStatusOwnerUids,
            blockedByViewerUids = prefs.blockedUserUids,
            nowMillis = liveNowMillis
        )
    }
    val visibleStatusesByOwner = categorizedStatuses.visibleByOwner
    val unviewedStatusContacts = categorizedStatuses.totalUnviewedContacts

    var activeBottomTab by remember { mutableStateOf(ChatHubBottomTab.CHATS) }
    var showExtraFilterChips by remember { mutableStateOf(false) }
    var showHubOverflowMenu by remember { mutableStateOf(false) }
    var showQuickChatActionsSheet by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var pendingPinSetupAfterLockTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var pendingPinSetupSwitchLockedTab by remember { mutableStateOf(false) }
    var lockedTabUnlocked by remember { mutableStateOf(false) }
    var pinUnlockTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var pinUnlockCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var channelActionTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var directorySearchQuery by remember { mutableStateOf("") }
    var directoryFilterOnlineOnly by remember { mutableStateOf(false) }
    var newCustomListInput by remember { mutableStateOf("") }

    // Custom wallpaper target inside You / Settings tab (null = Global, non-null = per-chat)
    var wallpaperScopeChannelId by remember { mutableStateOf<String?>(null) }
    var wallpaperFeedback by remember { mutableStateOf<String?>(null) }
    var showEditNoteDialog by remember { mutableStateOf(false) }
    var statusNoteDraft by remember(prefs.statusNote) { mutableStateOf(prefs.statusNote) }
    var cacheStats by remember { mutableStateOf(ChatFileManager.ChatCacheStats(0, 0L)) }
    var healthCheckMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingHealth by remember { mutableStateOf(false) }

    LaunchedEffect(activeBottomTab) {
        if (activeBottomTab == ChatHubBottomTab.SETTINGS) {
            cacheStats = ChatFileManager.getChatMediaCacheStats(context)
        }
    }

    val wallpaperGalleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val targetId = wallpaperScopeChannelId
            scope.launch {
                ChatFileManager.saveCustomChatWallpaper(context, uri, targetId)
                    .onSuccess { savedPath ->
                        ChatSettingsStore.setCustomWallpaperPath(context, savedPath, targetId)
                        wallpaperFeedback = if (targetId == null) {
                            "Global custom wallpaper saved"
                        } else {
                            "Custom wallpaper updated for this chat"
                        }
                    }
                    .onFailure { err ->
                        wallpaperFeedback = err.message ?: "Unable to apply wallpaper image"
                    }
            }
        }
    }

    fun handleProtectedOpen(channel: ChatChannel, onOpen: () -> Unit) {
        val isLocked = channel.id in prefs.lockedChannelIds && channel.id !in prefs.unlockedThisSessionIds
        if (isLocked && prefs.hasSecretCode) {
            pinUnlockTarget = channel
            pinUnlockCallback = onOpen
        } else {
            onOpen()
        }
    }

    val directChannelsByPeer = remember(channels, member.uid) {
        channels.filter { it.channelTypeEnum() == ChatChannelType.DIRECT && !it.isSelfNotes(member.uid) }
            .mapNotNull { ch ->
                val other = ch.participantUids.firstOrNull { it != member.uid }
                if (other != null) other to ch else null
            }.toMap()
    }

    val nonDirectChannels = remember(channels, member.uid) {
        channels.filter { it.channelTypeEnum() != ChatChannelType.DIRECT || it.isSelfNotes(member.uid) }
    }

    val filteredNonDirectChannels = remember(nonDirectChannels, searchQuery, filterTab, selectedCustomList, prefs, member.uid) {
        val q = searchQuery.trim().lowercase()
        nonDirectChannels.filter { ch ->
            val matchesQuery = q.isBlank() ||
                ch.title.lowercase().contains(q) ||
                ch.lastMessagePreview.lowercase().contains(q) ||
                ch.topic.lowercase().contains(q)
            val isLocked = ch.id in prefs.lockedChannelIds
            val isArchived = ch.isArchivedFor(member.uid)
            val customSet = prefs.channelListsMap[ch.id].orEmpty()
            val matchesTab = when {
                selectedCustomList != null -> selectedCustomList in customSet
                filterTab == ChatFilterTab.DIRECT -> false
                filterTab == ChatFilterTab.UNREAD -> ch.unreadCountFor(member.uid) > 0 && !isArchived && !isLocked
                filterTab == ChatFilterTab.GROUPS -> !isArchived && !isLocked
                filterTab == ChatFilterTab.FAVOURITES -> ch.isFavouriteFor(member.uid) && !isArchived
                filterTab == ChatFilterTab.ARCHIVED -> isArchived
                filterTab == ChatFilterTab.LOCKED -> isLocked
                else -> !isArchived && !isLocked
            }
            matchesQuery && matchesTab
        }.sortedWith(
            compareByDescending<ChatChannel> { it.isPinnedFor(member.uid) }
                .thenByDescending { it.lastMessageAtMillis }
        )
    }

    val filteredColleagues = remember(colleagues, directChannelsByPeer, searchQuery, filterTab, selectedCustomList, prefs, member.uid) {
        val q = searchQuery.trim().lowercase()
        colleagues.filter { person ->
            val ch = directChannelsByPeer[person.uid]
            val matchesQuery = q.isBlank() ||
                person.fullName.lowercase().contains(q) ||
                (ch?.lastMessagePreview?.lowercase()?.contains(q) == true)
            val isLocked = ch != null && ch.id in prefs.lockedChannelIds
            val isArchived = ch?.isArchivedFor(member.uid) == true
            val customSet = ch?.let { prefs.channelListsMap[it.id] }.orEmpty()
            val matchesTab = when {
                selectedCustomList != null -> selectedCustomList in customSet
                filterTab == ChatFilterTab.GROUPS -> false
                filterTab == ChatFilterTab.DIRECT -> !isArchived && !isLocked
                filterTab == ChatFilterTab.UNREAD -> (ch?.unreadCountFor(member.uid) ?: 0) > 0 && !isArchived && !isLocked
                filterTab == ChatFilterTab.FAVOURITES -> ch?.isFavouriteFor(member.uid) == true && !isArchived
                filterTab == ChatFilterTab.ARCHIVED -> isArchived
                filterTab == ChatFilterTab.LOCKED -> isLocked
                else -> !isArchived && !isLocked
            }
            matchesQuery && matchesTab
        }.sortedByDescending { directChannelsByPeer[it.uid]?.lastMessageAtMillis ?: 0L }
    }

    val colleaguesByUid = remember(colleagues, member) {
        (colleagues + member).associateBy { it.uid }
    }

    val totalUnreadCount = remember(channels, member.uid) {
        channels.sumOf { it.unreadCountFor(member.uid) }
    }

    @Composable
    fun HubOverflowDropdown() {
        DropdownMenu(
            expanded = showHubOverflowMenu,
            onDismissRequest = { showHubOverflowMenu = false },
            containerColor = palette.surface
        ) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = palette.textPrimary) },
                text = { Text("New Chat", style = XMaxType.subheadlineMedium, color = palette.textPrimary) },
                onClick = { showHubOverflowMenu = false; onOpenChannelsSheet() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null, tint = palette.textPrimary) },
                text = { Text("Message Yourself", style = XMaxType.subheadlineMedium, color = palette.textPrimary) },
                onClick = { showHubOverflowMenu = false; onOpenSavedNotes() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.GroupAdd, null, tint = palette.textPrimary) },
                text = { Text("New Group", style = XMaxType.subheadlineMedium, color = palette.textPrimary) },
                onClick = { showHubOverflowMenu = false; onOpenCreateGroup() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.Adjust, null, tint = palette.textPrimary) },
                text = { Text("Add Status Update", style = XMaxType.subheadlineMedium, color = palette.textPrimary) },
                onClick = { showHubOverflowMenu = false; onOpenCreateStatus() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.QrCode, null, tint = palette.textPrimary) },
                text = { Text("Join Group by Link", style = XMaxType.subheadlineMedium, color = palette.textPrimary) },
                onClick = { showHubOverflowMenu = false; onOpenJoinByInvite() }
            )
            if (activeBottomTab != ChatHubBottomTab.SETTINGS) {
                HorizontalDivider(color = palette.divider)
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, tint = palette.textPrimary) },
                    text = { Text("Chat Settings & Wallpapers", style = XMaxType.subheadlineMedium, color = palette.textPrimary) },
                    onClick = {
                        showHubOverflowMenu = false
                        activeBottomTab = ChatHubBottomTab.SETTINGS
                    }
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pastelAmbientBackdrop()
            .testTag("chat_hub_screen")
    ) {
        val isWide = maxWidth >= 600.dp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            if (isWide) {
                NavigationRail(
                    containerColor = palette.surface,
                    header = {
                        FloatingActionButton(
                            onClick = { showQuickChatActionsSheet = true },
                            containerColor = palette.buttonPrimaryBg,
                            contentColor = palette.buttonPrimaryFg,
                            shape = CircleShape,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .size(48.dp)
                                .testTag("chat_rail_quick_create_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Quick Chat Actions")
                        }
                    }
                ) {
                    ChatHubBottomTab.entries.forEach { tab ->
                        val selected = activeBottomTab == tab
                        val badgeCount = when (tab) {
                            ChatHubBottomTab.CHATS -> totalUnreadCount
                            ChatHubBottomTab.STATUS -> unviewedStatusContacts
                            else -> 0
                        }
                        NavigationRailItem(
                            selected = selected,
                            onClick = { activeBottomTab = tab },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (badgeCount > 0) {
                                            Badge(containerColor = VioletPrimary) {
                                                Text(
                                                    if (badgeCount > 9) "9+" else "$badgeCount",
                                                    style = XMaxType.caption2Medium.tabular(),
                                                    color = PureWhite
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (selected) tab.icon else tab.inactiveIcon,
                                        contentDescription = tab.label
                                    )
                                }
                            },
                            label = { Text(tab.label, style = XMaxType.navLabel) }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.ime)
                ) {
                    when (activeBottomTab) {
                        ChatHubBottomTab.CHATS -> {
                            // Top Bar: Back + Title + Overflow
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = onBack,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("chat_hub_back_button")
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = palette.textPrimary
                                        )
                                    }
                                    Text(
                                        text = "Chats",
                                        style = XMaxType.primaryTitle,
                                        color = palette.textPrimary
                                    )
                                }

                                Box {
                                    Surface(
                                        onClick = { showHubOverflowMenu = true },
                                        shape = CircleShape,
                                        color = palette.surface,
                                        border = BorderStroke(1.dp, palette.outline),
                                        shadowElevation = 1.dp,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("chat_hub_overflow_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "Chat Options",
                                                tint = palette.textPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    HubOverflowDropdown()
                                }
                            }

                            // Rounded Search Pill matching screenshot structure
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = palette.surface,
                                border = BorderStroke(1.dp, palette.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = onSearchQueryChange,
                                        singleLine = true,
                                        textStyle = XMaxType.body.copy(color = palette.textPrimary),
                                        cursorBrush = SolidColor(palette.textPrimary),
                                        decorationBox = { inner ->
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    "Search chats, people, or groups",
                                                    style = XMaxType.subheadline,
                                                    color = palette.textSecondary
                                                )
                                            }
                                            inner()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("chat_search_button")
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }

                            // Pill Filter Row: All, Unread, Favourites, Groups, + (extra/custom lists)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 18.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val primaryTabs = listOf(
                                    ChatFilterTab.ALL,
                                    ChatFilterTab.UNREAD,
                                    ChatFilterTab.FAVOURITES,
                                    ChatFilterTab.GROUPS
                                )
                                primaryTabs.forEach { tab ->
                                    val selected = selectedCustomList == null && filterTab == tab
                                    ChatPillFilterChip(
                                        label = tab.label,
                                        selected = selected,
                                        onClick = {
                                            lockedTabUnlocked = false
                                            onSelectCustomList(null)
                                            onSelectFilterTab(tab)
                                        }
                                    )
                                }
                                ChatPillFilterChip(
                                    label = if (showExtraFilterChips) "Less" else "+",
                                    selected = showExtraFilterChips || selectedCustomList != null || filterTab in listOf(
                                        ChatFilterTab.DIRECT,
                                        ChatFilterTab.ARCHIVED,
                                        ChatFilterTab.LOCKED
                                    ),
                                    onClick = { showExtraFilterChips = !showExtraFilterChips }
                                )
                            }

                            AnimatedVisibility(
                                visible = showExtraFilterChips || selectedCustomList != null || filterTab in listOf(
                                    ChatFilterTab.DIRECT,
                                    ChatFilterTab.ARCHIVED,
                                    ChatFilterTab.LOCKED
                                ),
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(ChatFilterTab.DIRECT, ChatFilterTab.ARCHIVED, ChatFilterTab.LOCKED).forEach { tab ->
                                        val selected = selectedCustomList == null && filterTab == tab
                                        ChatPillFilterChip(
                                            label = tab.label,
                                            selected = selected,
                                            onClick = {
                                                if (tab == ChatFilterTab.LOCKED) {
                                                    if (!prefs.hasSecretCode) {
                                                        pendingPinSetupSwitchLockedTab = true
                                                        showPinSetupDialog = true
                                                    } else if (!lockedTabUnlocked) {
                                                        pinUnlockTarget = null
                                                        pinUnlockCallback = {
                                                            lockedTabUnlocked = true
                                                            onSelectFilterTab(tab)
                                                        }
                                                    } else {
                                                        onSelectFilterTab(tab)
                                                    }
                                                } else {
                                                    lockedTabUnlocked = false
                                                    onSelectCustomList(null)
                                                    onSelectFilterTab(tab)
                                                }
                                            }
                                        )
                                    }
                                    prefs.customLists.forEach { customList ->
                                        ChatPillFilterChip(
                                            label = customList,
                                            selected = selectedCustomList == customList,
                                            onClick = {
                                                lockedTabUnlocked = false
                                                onSelectCustomList(if (selectedCustomList == customList) null else customList)
                                            }
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(visible = !openingThreadLabel.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(shape = RoundedCornerShape(999.dp), color = palette.buttonPrimaryBg, modifier = Modifier.testTag("chat_opening_indicator")) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.buttonPrimaryFg)
                                            Text(openingThreadLabel.orEmpty(), style = XMaxType.caption1Medium, color = palette.buttonPrimaryFg)
                                        }
                                    }
                                }
                            }

                            // Main Scrollable Conversations List (No Status Strip inside Chats — Status lives in Updates tab!)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = if (isWide) 24.dp else 116.dp)
                            ) {
                                // Direct Messages (1:1 Person-to-person chats)
                                if (filteredColleagues.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("chat_dm_section")
                                    ) {
                                        filteredColleagues.forEach { person ->
                                            val dmChannel = directChannelsByPeer[person.uid]
                                            val presence = companyPresence[person.uid]
                                            val isOnlineNow = RtdbPresenceManager.isMemberOnlineNow(person, presence)
                                            val lastSeenMs = RtdbPresenceManager.effectiveLastSeenMillis(person, presence)
                                            val availability = RtdbPresenceManager.resolveAvailability(person, presence)
                                            val unread = dmChannel?.unreadCountFor(member.uid) ?: 0
                                            val isMuted = dmChannel?.isMutedFor(member.uid) == true
                                            val isPinned = dmChannel?.isPinnedFor(member.uid) == true
                                            val isLocked = dmChannel != null && dmChannel.id in prefs.lockedChannelIds
                                            val timeStr = remember(dmChannel?.lastMessageAtMillis) {
                                                val ms = dmChannel?.lastMessageAtMillis ?: 0L
                                                if (ms > 0L) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) else ""
                                            }
                                            val presenceLabel = remember(isOnlineNow, availability, lastSeenMs) {
                                                when {
                                                    isOnlineNow && availability == MemberAvailability.BUSY -> "Busy"
                                                    isOnlineNow -> "Online"
                                                    lastSeenMs > 0L -> "Last seen ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeenMs))}"
                                                    else -> "Offline"
                                                }
                                            }

                                            Surface(
                                                color = Color.Transparent,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .combinedClickable(
                                                        onClick = {
                                                            if (dmChannel != null) {
                                                                handleProtectedOpen(dmChannel) { onOpenDirectChat(person) }
                                                            } else {
                                                                onOpenDirectChat(person)
                                                            }
                                                        },
                                                        onLongClick = {
                                                            if (dmChannel != null) {
                                                                channelActionTarget = dmChannel
                                                            }
                                                        }
                                                    )
                                                    .testTag("dm_person_${person.uid}")
                                            ) {
                                                Column {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                    ) {
                                                        val peerStatuses = visibleStatusesByOwner[person.uid].orEmpty()
                                                        SegmentedStatusAvatar(
                                                            name = person.fullName,
                                                            availability = availability,
                                                            showAvailabilityBadge = true,
                                                            size = 48.dp,
                                                            borderColor = palette.surface,
                                                            photoBase64 = person.photoBase64,
                                                            dotSize = 13.dp,
                                                            activeStatuses = peerStatuses,
                                                            viewerUid = member.uid,
                                                            isOwnAvatar = false,
                                                            onAvatarClick = if (peerStatuses.isNotEmpty()) {
                                                                { onOpenStatusViewer(person.uid, peerStatuses) }
                                                            } else null,
                                                            modifier = Modifier.testTag("dm_avatar_${person.uid}")
                                                        )

                                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                Text(
                                                                    text = formatTitleCase(person.fullName.ifBlank { "Colleague" }),
                                                                    style = XMaxType.headline,
                                                                    color = palette.textPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                Text(
                                                                    text = "• $presenceLabel",
                                                                    style = XMaxType.caption1Semibold,
                                                                    color = if (isOnlineNow) {
                                                                        if (availability == MemberAvailability.BUSY) CrimsonBlocked else EmeraldApproved
                                                                    } else palette.textSecondary,
                                                                    maxLines = 1
                                                                )
                                                                if (isPinned) Icon(Icons.Default.PushPin, null, tint = VioletPrimary, modifier = Modifier.size(13.dp))
                                                                if (isMuted) Icon(Icons.Default.VolumeOff, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                                                                if (isLocked) Icon(Icons.Default.Lock, null, tint = palette.textPrimary, modifier = Modifier.size(13.dp))
                                                            }
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                if (dmChannel != null && dmChannel.lastSenderUid == member.uid) {
                                                                    DeliveryStatusIcon(status = dmChannel.lastMessageStatus, modifier = Modifier.size(14.dp))
                                                                }
                                                                Text(
                                                                    text = dmChannel?.lastMessagePreview?.takeIf { it.isNotBlank() }
                                                                        ?: presenceLabel,
                                                                    style = XMaxType.subheadline,
                                                                    color = palette.textSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }

                                                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            if (timeStr.isNotBlank()) {
                                                                Text(timeStr, style = XMaxType.caption1.tabular(), color = if (unread > 0) VioletPrimary else palette.textSecondary)
                                                            }
                                                            if (unread > 0) {
                                                                Surface(shape = CircleShape, color = VioletPrimary) {
                                                                    Text(
                                                                        text = unread.toString(),
                                                                        style = XMaxType.caption1Semibold.tabular(),
                                                                        color = PureWhite,
                                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    HorizontalDivider(color = palette.outline.copy(alpha = 0.7f), modifier = Modifier.padding(start = 80.dp, end = 18.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                // Groups, General & Saved Messages
                                if (filteredNonDirectChannels.isNotEmpty()) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        filteredNonDirectChannels.forEach { ch ->
                                            val unread = ch.unreadCountFor(member.uid)
                                            val isMuted = ch.isMutedFor(member.uid)
                                            val isPinned = ch.isPinnedFor(member.uid)
                                            val isLocked = ch.id in prefs.lockedChannelIds
                                            val isSaved = ch.isSelfNotes(member.uid)
                                            val timeStr = remember(ch.lastMessageAtMillis) {
                                                if (ch.lastMessageAtMillis > 0L) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ch.lastMessageAtMillis)) else ""
                                            }
                                            val groupMemberAvails = remember(ch.participantUids, companyPresence, colleaguesByUid, member.uid) {
                                                val otherUids = ch.participantUids.filter { it != member.uid }.ifEmpty {
                                                    colleagues.map { it.uid }
                                                }
                                                otherUids.map { uid ->
                                                    RtdbPresenceManager.resolveAvailability(colleaguesByUid[uid], companyPresence[uid])
                                                }
                                            }
                                            val groupBadgeAvail = remember(groupMemberAvails) {
                                                when {
                                                    MemberAvailability.ACTIVE in groupMemberAvails -> MemberAvailability.ACTIVE
                                                    MemberAvailability.BUSY in groupMemberAvails -> MemberAvailability.BUSY
                                                    else -> MemberAvailability.AWAY
                                                }
                                            }
                                            val activeMembersCount = remember(groupMemberAvails) {
                                                groupMemberAvails.count { it == MemberAvailability.ACTIVE }
                                            }

                                            Surface(
                                                color = Color.Transparent,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .combinedClickable(
                                                        onClick = { handleProtectedOpen(ch) { onSelectChannel(ch) } },
                                                        onLongClick = { channelActionTarget = ch }
                                                    )
                                                    .testTag("channel_chip_${ch.id}")
                                            ) {
                                                Column {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                    ) {
                                                        if (isSaved) {
                                                            Surface(shape = CircleShape, color = palette.softVioletBg, modifier = Modifier.size(48.dp)) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Icon(Icons.Default.Bookmark, contentDescription = "Saved Notes", tint = VioletPrimary)
                                                                }
                                                            }
                                                        } else {
                                                            AvatarWithAvailabilityBadge(
                                                                name = ch.title,
                                                                availability = groupBadgeAvail,
                                                                size = 48.dp,
                                                                borderColor = palette.surface,
                                                                photoBase64 = ch.photoBase64,
                                                                dotSize = 13.dp
                                                            )
                                                        }

                                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                Text(
                                                                    text = if (isSaved) "Message Yourself" else formatTitleCase(ch.title),
                                                                    style = XMaxType.headline,
                                                                    color = palette.textPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                if (isPinned) Icon(Icons.Default.PushPin, null, tint = VioletPrimary, modifier = Modifier.size(13.dp))
                                                                if (isMuted) Icon(Icons.Default.VolumeOff, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                                                                if (isLocked) Icon(Icons.Default.Lock, null, tint = palette.textPrimary, modifier = Modifier.size(13.dp))
                                                            }
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                Text(
                                                                    text = ch.lastMessagePreview.ifBlank { ch.topic.ifBlank { "Group conversation" } },
                                                                    style = XMaxType.subheadline,
                                                                    color = palette.textSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                if (!isSaved && activeMembersCount > 0) {
                                                                    Text(
                                                                        text = "• $activeMembersCount active",
                                                                        style = XMaxType.caption1Medium.tabular(),
                                                                        color = EmeraldApproved,
                                                                        maxLines = 1
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            if (timeStr.isNotBlank()) {
                                                                Text(timeStr, style = XMaxType.caption1.tabular(), color = if (unread > 0) VioletPrimary else palette.textSecondary)
                                                            }
                                                            if (unread > 0) {
                                                                Surface(shape = CircleShape, color = VioletPrimary) {
                                                                    Text(
                                                                        text = unread.toString(),
                                                                        style = XMaxType.caption1Semibold.tabular(),
                                                                        color = PureWhite,
                                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    HorizontalDivider(color = palette.outline.copy(alpha = 0.7f), modifier = Modifier.padding(start = 80.dp, end = 18.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                if (filteredColleagues.isEmpty() && filteredNonDirectChannels.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = palette.surface,
                                        border = BorderStroke(1.dp, palette.outline),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp, vertical = 16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(30.dp))
                                            Text("No matching conversations", style = XMaxType.headline, color = palette.textPrimary)
                                            Text(
                                                "Clear filters or tap + below to start a new chat or group.",
                                                style = XMaxType.footnote,
                                                color = palette.textSecondary
                                            )
                                        }
                                    }
                                }

                                // Quick compose bar if draft/attachment was staged
                                if (composerText.isNotEmpty() || stagedAttachment != null) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = palette.surface,
                                        border = BorderStroke(1.dp, palette.outline),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp, vertical = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            BasicTextField(
                                                value = composerText,
                                                onValueChange = onComposerTextChange,
                                                cursorBrush = SolidColor(palette.textPrimary),
                                                textStyle = XMaxType.body.copy(color = palette.textPrimary),
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = onSendQuickCompose,
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .testTag("chat_hub_send_button")
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = VioletPrimary)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ChatHubBottomTab.STATUS -> {
                            if (state != null && chatViewModel != null) {
                                StatusTabScreen(
                                    state = state,
                                    chatViewModel = chatViewModel,
                                    onOpenDirectChannel = onSelectChannel,
                                    onBack = { activeBottomTab = ChatHubBottomTab.CHATS },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(18.dp)
                                ) {
                                    StatusAvatarBar(
                                        myMember = member,
                                        statuses = statuses,
                                        colleagues = colleagues,
                                        onAddStatus = onOpenCreateStatus,
                                        onOpenStatusesForOwner = onOpenStatusViewer
                                    )
                                }
                            }
                        }

                        ChatHubBottomTab.DIRECTORY -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { activeBottomTab = ChatHubBottomTab.CHATS },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("chat_directory_back_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                                    }
                                    Text(
                                        text = "People",
                                        style = XMaxType.primaryTitle,
                                        color = palette.textPrimary
                                    )
                                }
                                Box {
                                    Surface(
                                        onClick = { showHubOverflowMenu = true },
                                        shape = CircleShape,
                                        color = palette.surface,
                                        border = BorderStroke(1.dp, palette.outline),
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Chat Options", tint = palette.textPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    HubOverflowDropdown()
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = palette.surface,
                                border = BorderStroke(1.dp, palette.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(19.dp))
                                    BasicTextField(
                                        value = directorySearchQuery,
                                        onValueChange = { directorySearchQuery = it },
                                        singleLine = true,
                                        textStyle = XMaxType.body.copy(color = palette.textPrimary),
                                        cursorBrush = SolidColor(palette.textPrimary),
                                        decorationBox = { inner ->
                                            if (directorySearchQuery.isEmpty()) {
                                                Text("Search colleagues or roles…", style = XMaxType.subheadline, color = palette.textSecondary)
                                            }
                                            inner()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (directorySearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { directorySearchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 18.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ChatPillFilterChip(
                                    label = "All Colleagues",
                                    selected = !directoryFilterOnlineOnly,
                                    onClick = { directoryFilterOnlineOnly = false }
                                )
                                ChatPillFilterChip(
                                    label = "Active & Busy",
                                    selected = directoryFilterOnlineOnly,
                                    onClick = { directoryFilterOnlineOnly = true }
                                )
                                ChatPillFilterChip(
                                    label = "+ New Group",
                                    selected = false,
                                    onClick = { onOpenCreateGroup() }
                                )
                            }

                            val dirFiltered = remember(colleagues, directorySearchQuery) {
                                val q = directorySearchQuery.trim().lowercase()
                                colleagues.filter { c ->
                                    q.isBlank() ||
                                        c.fullName.lowercase().contains(q) ||
                                        c.jobRoleName.lowercase().contains(q) ||
                                        c.departmentName.lowercase().contains(q)
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(bottom = if (isWide) 24.dp else 116.dp)
                            ) {
                                items(dirFiltered, key = { it.uid }) { person ->
                                    val presence = companyPresence[person.uid]
                                    val isOnlineNow = RtdbPresenceManager.isMemberOnlineNow(person, presence)
                                    val lastSeenMs = RtdbPresenceManager.effectiveLastSeenMillis(person, presence)
                                    val availability = RtdbPresenceManager.resolveAvailability(person, presence)
                                    val presenceText = when {
                                        isOnlineNow && availability == MemberAvailability.BUSY -> "Busy"
                                        isOnlineNow -> "Online"
                                        lastSeenMs > 0L -> "Last seen ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeenMs))}"
                                        else -> "Offline"
                                    }
                                    if (!directoryFilterOnlineOnly || isOnlineNow) {
                                        Surface(
                                            onClick = { onOpenDirectChat(person) },
                                            color = Color.Transparent,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                ) {
                                                    val peerStatuses = visibleStatusesByOwner[person.uid].orEmpty()
                                                    SegmentedStatusAvatar(
                                                        name = person.fullName,
                                                        availability = availability,
                                                        showAvailabilityBadge = true,
                                                        size = 48.dp,
                                                        borderColor = palette.surface,
                                                        photoBase64 = person.photoBase64,
                                                        dotSize = 13.dp,
                                                        activeStatuses = peerStatuses,
                                                        viewerUid = member.uid,
                                                        isOwnAvatar = false,
                                                        onAvatarClick = if (peerStatuses.isNotEmpty()) {
                                                            { onOpenStatusViewer(person.uid, peerStatuses) }
                                                        } else null,
                                                        modifier = Modifier.testTag("dir_avatar_${person.uid}")
                                                    )
                                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Text(formatTitleCase(person.fullName), style = XMaxType.headline, color = palette.textPrimary)
                                                            Text(
                                                                text = "• $presenceText",
                                                                style = XMaxType.caption1Semibold,
                                                                color = if (isOnlineNow) {
                                                                    if (availability == MemberAvailability.BUSY) CrimsonBlocked else EmeraldApproved
                                                                } else palette.textSecondary
                                                            )
                                                        }
                                                        Text(formatTitleCase(person.jobRoleName.ifBlank { "Team Member" }), style = XMaxType.footnote, color = palette.textSecondary)
                                                        Text(formatTitleCase(person.departmentName.ifBlank { "Workspace" }), style = XMaxType.caption1, color = palette.textSecondary)
                                                    }
                                                    IconButton(
                                                        onClick = { onOpenDirectChat(person) },
                                                        modifier = Modifier.size(48.dp)
                                                    ) {
                                                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Chat", tint = VioletPrimary)
                                                    }
                                                }
                                                HorizontalDivider(color = palette.outline.copy(alpha = 0.7f), modifier = Modifier.padding(start = 80.dp, end = 18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ChatHubBottomTab.SETTINGS -> {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(
                                        start = 18.dp,
                                        end = 18.dp,
                                        top = 6.dp,
                                        bottom = if (isWide) 28.dp else 116.dp
                                    ),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Top Back & Title Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IconButton(
                                            onClick = { activeBottomTab = ChatHubBottomTab.CHATS },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("chat_settings_back_button")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Chats", tint = palette.textPrimary)
                                        }
                                        Text(
                                            text = "You & Chat Settings",
                                            style = XMaxType.sectionHeading,
                                            color = palette.textPrimary
                                        )
                                    }
                                }

                                // "You" Profile Card
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.outline),
                                    shadowElevation = 2.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        palette.softVioletBg.copy(alpha = if (XMaxTheme.isDark) 0.4f else 0.7f),
                                                        palette.surface
                                                    )
                                                )
                                            )
                                            .padding(horizontal = 20.dp, vertical = 18.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            onClick = { showEditNoteDialog = true },
                                            shape = RoundedCornerShape(999.dp),
                                            color = palette.surface,
                                            border = BorderStroke(1.dp, palette.outline)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(prefs.statusNote, style = XMaxType.caption1Medium, color = palette.textSecondary)
                                                Icon(Icons.Default.Edit, contentDescription = "Edit status note", tint = VioletPrimary, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                        Box(contentAlignment = Alignment.BottomEnd) {
                                            StudioAvatar(
                                                name = member.fullName,
                                                photoBase64 = member.photoBase64,
                                                size = 76.dp,
                                                borderColor = palette.surface,
                                                showEditBadge = false
                                            )
                                            Surface(
                                                onClick = onOpenCreateStatus,
                                                shape = CircleShape,
                                                color = EmeraldApproved,
                                                border = BorderStroke(2.dp, palette.surface),
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Add, contentDescription = "Post status", tint = PureWhite, modifier = Modifier.size(15.dp))
                                                }
                                            }
                                        }
                                        Text(
                                            text = formatTitleCase(member.fullName.ifBlank { "Member" }),
                                            style = XMaxType.secondaryTitle,
                                            color = palette.textPrimary
                                        )
                                        Text(
                                            text = "@${member.fullName.trim().lowercase().replace(Regex("\\s+"), "_")} • ${formatTitleCase(member.jobRoleName.ifBlank { member.accessLevelEnum().label })}",
                                            style = XMaxType.footnote,
                                            color = palette.textSecondary
                                        )
                                    }
                                }

                                // Custom Image Wallpaper (Global + Per-Chat)
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        val selectedChannelObj = channels.firstOrNull { it.id == wallpaperScopeChannelId }
                                        val activeTargetCustomPath = if (wallpaperScopeChannelId == null) {
                                            prefs.globalCustomWallpaperPath
                                        } else {
                                            prefs.channelCustomWallpaperPaths[wallpaperScopeChannelId].orEmpty()
                                        }
                                        val activeTargetPresetId = if (wallpaperScopeChannelId == null) {
                                            prefs.globalWallpaperId
                                        } else {
                                            prefs.channelWallpapers[wallpaperScopeChannelId] ?: "default"
                                        }
                                        val resolvedPreview = if (wallpaperScopeChannelId == null) {
                                            com.example.chat.ResolvedThreadWallpaper(
                                                presetId = prefs.globalWallpaperId,
                                                customImagePath = prefs.globalCustomWallpaperPath.takeIf { it.isNotBlank() }
                                            )
                                        } else {
                                            ChatSettingsStore.resolveThreadWallpaper(prefs, wallpaperScopeChannelId!!)
                                        }
                                        val previewBitmap = remember(resolvedPreview.customImagePath) {
                                            ChatFileManager.loadWallpaperBitmap(resolvedPreview.customImagePath)
                                        }
                                        val previewPreset = ChatVisualCatalog.wallpaper(resolvedPreview.presetId, true)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                Text("Chat Wallpaper", style = XMaxType.headline, color = palette.textPrimary)
                                                Text(
                                                    "Set a custom gallery photo globally or per conversation",
                                                    style = XMaxType.footnote,
                                                    color = palette.textSecondary
                                                )
                                            }
                                            if (activeTargetCustomPath.isNotBlank() || (wallpaperScopeChannelId != null && activeTargetPresetId != "default")) {
                                                TextButton(
                                                    onClick = {
                                                        if (activeTargetCustomPath.isNotBlank()) {
                                                            ChatFileManager.deleteWallpaperFile(activeTargetCustomPath)
                                                        }
                                                        ChatSettingsStore.clearCustomWallpaper(context, wallpaperScopeChannelId)
                                                        wallpaperFeedback = if (wallpaperScopeChannelId == null) {
                                                            "Reset global wallpaper"
                                                        } else {
                                                            "Reset chat to global wallpaper"
                                                        }
                                                    }
                                                ) {
                                                    Text("Reset", style = XMaxType.buttonCompact, color = CrimsonBlocked)
                                                }
                                            }
                                        }

                                        // Target scope selector: All Chats (Global) or any channel
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ChatPillFilterChip(
                                                label = "All Chats (Global)",
                                                selected = wallpaperScopeChannelId == null,
                                                onClick = { wallpaperScopeChannelId = null }
                                            )
                                            channels.take(12).forEach { ch ->
                                                val displayTitle = if (ch.channelTypeEnum() == ChatChannelType.DIRECT && !ch.isSelfNotes(member.uid)) {
                                                    val peerUid = ch.participantUids.firstOrNull { it != member.uid }
                                                    colleaguesByUid[peerUid]?.fullName?.let { formatTitleCase(it) } ?: formatTitleCase(ch.title)
                                                } else if (ch.isSelfNotes(member.uid)) {
                                                    "Message Yourself"
                                                } else {
                                                    formatTitleCase(ch.title)
                                                }
                                                ChatPillFilterChip(
                                                    label = displayTitle,
                                                    selected = wallpaperScopeChannelId == ch.id,
                                                    onClick = { wallpaperScopeChannelId = ch.id }
                                                )
                                            }
                                        }

                                        // Wallpaper Preview + Gallery Picker CTA
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = palette.surfaceMuted,
                                            border = BorderStroke(1.dp, palette.outline),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(Brush.verticalGradient(previewPreset.baseColors)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (previewBitmap != null) {
                                                        Image(
                                                            bitmap = previewBitmap.asImageBitmap(),
                                                            contentDescription = "Wallpaper preview",
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Icon(Icons.Default.Wallpaper, contentDescription = null, tint = palette.textSecondary)
                                                    }
                                                }
                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    val scopeName = selectedChannelObj?.let { ch ->
                                                        if (ch.isSelfNotes(member.uid)) "Message Yourself" else formatTitleCase(ch.title)
                                                    } ?: "All Chats (Global)"
                                                    Text(scopeName, style = XMaxType.subheadlineSemibold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        text = when {
                                                            activeTargetCustomPath.isNotBlank() -> "Custom Photo Active"
                                                            wallpaperScopeChannelId != null && activeTargetPresetId == "default" ->
                                                                if (!resolvedPreview.customImagePath.isNullOrBlank()) "Inherits Global Custom Photo"
                                                                else "Inherits Global (${previewPreset.label})"
                                                            else -> "Preset: ${previewPreset.label}"
                                                        },
                                                        style = XMaxType.caption1,
                                                        color = palette.textSecondary
                                                    )
                                                }
                                                FilledTonalButton(
                                                    onClick = {
                                                        wallpaperGalleryPicker.launch(
                                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                        )
                                                    },
                                                    shape = RoundedCornerShape(999.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = palette.buttonPrimaryBg,
                                                        contentColor = palette.buttonPrimaryFg
                                                    ),
                                                    modifier = Modifier
                                                        .height(42.dp)
                                                        .testTag("settings_pick_wallpaper_button")
                                                ) {
                                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Pick Image", style = XMaxType.buttonCompact)
                                                }
                                            }
                                        }

                                        wallpaperFeedback?.let { msg ->
                                            Text(msg, style = XMaxType.caption1Medium, color = VioletPrimary)
                                        }

                                        // Preset Color Swatches
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (wallpaperScopeChannelId != null) {
                                                FilterChip(
                                                    selected = activeTargetCustomPath.isBlank() && activeTargetPresetId == "default",
                                                    onClick = {
                                                        if (activeTargetCustomPath.isNotBlank()) {
                                                            ChatFileManager.deleteWallpaperFile(activeTargetCustomPath)
                                                        }
                                                        ChatSettingsStore.clearCustomWallpaper(context, wallpaperScopeChannelId)
                                                    },
                                                    label = { Text("Global Default", style = XMaxType.caption1Medium) }
                                                )
                                            }
                                            ChatVisualCatalog.wallpapers.forEach { wp ->
                                                val isSelected = activeTargetCustomPath.isBlank() &&
                                                    (if (wallpaperScopeChannelId == null) prefs.globalWallpaperId == wp.id else activeTargetPresetId == wp.id)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        ChatSettingsStore.setWallpaper(context, wp.id, wallpaperScopeChannelId)
                                                        wallpaperFeedback = null
                                                    },
                                                    leadingIcon = {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(14.dp)
                                                                .clip(CircleShape)
                                                                .background(wp.baseColors.lastOrNull() ?: PureWhite)
                                                                .border(1.dp, Color(0xFF94A3B8), CircleShape)
                                                        )
                                                    },
                                                    label = { Text(wp.label, style = XMaxType.caption1Medium) }
                                                )
                                            }
                                        }
                                        if (isSuperAdmin && wallpaperScopeChannelId == null) {
                                            OutlinedButton(
                                                onClick = {
                                                    onSetCompanyDefaultWallpaper(prefs.globalWallpaperId, "")
                                                    wallpaperFeedback = "Applied \"${previewPreset.label}\" as workspace default for everyone"
                                                },
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .defaultMinSize(minHeight = 44.dp)
                                                    .testTag("settings_apply_workspace_wallpaper")
                                            ) {
                                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Apply as Workspace Default for Everyone", style = XMaxType.buttonCompact)
                                            }
                                        }
                                    }
                                }

                                // Chat Bubble Theme
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Chat Bubble Appearance", style = XMaxType.headline, color = palette.textPrimary)
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
                                                    leadingIcon = {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(14.dp)
                                                                .clip(CircleShape)
                                                                .background(bt.outgoingBg)
                                                                .border(1.dp, palette.outline, CircleShape)
                                                        )
                                                    },
                                                    label = { Text(bt.label, style = XMaxType.caption1Medium) }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Secret Code & Locked Chats
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Secret Code & Locked Chats", style = XMaxType.headline, color = palette.textPrimary)
                                        Text(
                                            text = if (prefs.hasSecretCode) "Secret PIN configured (${prefs.lockedChannelIds.size} locked)" else "Set a 4+ digit PIN to hide and lock sensitive conversations",
                                            style = XMaxType.footnote.tabular(),
                                            color = palette.textSecondary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { showPinSetupDialog = true },
                                                modifier = Modifier.testTag("settings_set_pin_button")
                                            ) {
                                                Text(if (prefs.hasSecretCode) "Change PIN" else "Set Secret PIN", style = XMaxType.buttonCompact)
                                            }
                                            if (prefs.hasSecretCode) {
                                                TextButton(onClick = { ChatSettingsStore.clearSecretCode(context) }) {
                                                    Text("Remove PIN", style = XMaxType.buttonCompact, color = CrimsonBlocked)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Media & Auto-Download
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Media & Auto-Download", style = XMaxType.headline, color = palette.textPrimary)
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("Auto-download photos", style = XMaxType.subheadline, color = palette.textPrimary)
                                            Switch(checked = prefs.autoDownloadPhotos, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, it, prefs.autoDownloadAudio, prefs.autoDownloadDocs, prefs.mediaVisibleInGallery) })
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("Auto-download audio & voice", style = XMaxType.subheadline, color = palette.textPrimary)
                                            Switch(checked = prefs.autoDownloadAudio, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, it, prefs.autoDownloadDocs, prefs.mediaVisibleInGallery) })
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("Auto-download documents", style = XMaxType.subheadline, color = palette.textPrimary)
                                            Switch(checked = prefs.autoDownloadDocs, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, prefs.autoDownloadAudio, it, prefs.mediaVisibleInGallery) })
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("Show media in device gallery", style = XMaxType.subheadline, color = palette.textPrimary)
                                            Switch(checked = prefs.mediaVisibleInGallery, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, prefs.autoDownloadAudio, prefs.autoDownloadDocs, it) })
                                        }
                                    }
                                }

                                // Custom Chat Lists
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Custom Chat Lists", style = XMaxType.headline, color = palette.textPrimary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = newCustomListInput,
                                                onValueChange = { newCustomListInput = it },
                                                textStyle = XMaxType.body,
                                                placeholder = { Text("New list name…", style = XMaxType.subheadline) },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = {
                                                    ChatSettingsStore.addCustomList(context, newCustomListInput)
                                                    newCustomListInput = ""
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
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
                                            prefs.customLists.forEach { listName ->
                                                InputChip(
                                                    selected = false,
                                                    onClick = {},
                                                    label = { Text(listName, style = XMaxType.caption1Medium) },
                                                    trailingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = "Delete list",
                                                            modifier = Modifier
                                                                .size(16.dp)
                                                                .clickable { ChatSettingsStore.deleteCustomList(context, listName) }
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isSuperAdmin) {
                                    // Free-Tier Hybrid Infrastructure (Super Admin only)
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = palette.surface,
                                        border = BorderStroke(1.dp, palette.outline),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("super_admin_cloud_performance_card")
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Cloud & Storage Performance", style = XMaxType.headline, color = palette.textPrimary)
                                                Surface(shape = RoundedCornerShape(999.dp), color = palette.mintBg) {
                                                    Text(
                                                        "Free Tier",
                                                        style = XMaxType.caption2Medium,
                                                        color = palette.mintInk,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "Firebase (Auth & Messages) • Cloudflare Worker (Signed Edge Auth) • Supabase Storage (${WorkerBridgeClient.activeStorageProviderLabel()})",
                                                style = XMaxType.footnote,
                                                color = palette.textSecondary
                                            )
                                            Text(
                                                text = "Local media cache: ${cacheStats.fileCount} files (${ChatFileManager.formatFileSize(cacheStats.totalBytes)})",
                                                style = XMaxType.numericFootnote,
                                                color = palette.textPrimary
                                            )
                                            healthCheckMessage?.let { msg ->
                                                Text(msg, style = XMaxType.caption1Medium, color = VioletPrimary)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    enabled = !isCheckingHealth,
                                                    onClick = {
                                                        scope.launch {
                                                            isCheckingHealth = true
                                                            healthCheckMessage = when (val res = WorkerBridgeClient.checkHealth()) {
                                                                is WorkerResult.Success -> "Connected: ${res.data.storageProvider} (${res.data.plan} plan)"
                                                                is WorkerResult.NotConfigured -> "Worker URL not configured yet in Secrets; using Firestore inline fallback."
                                                                is WorkerResult.RateLimited -> "Rate limited; retry in ${res.retryAfterSeconds}s."
                                                                is WorkerResult.Error -> res.message
                                                            }
                                                            isCheckingHealth = false
                                                        }
                                                    }
                                                ) {
                                                    Text(if (isCheckingHealth) "Checking…" else "Check Backend", style = XMaxType.buttonCompact)
                                                }
                                                TextButton(
                                                    onClick = {
                                                        scope.launch {
                                                            cacheStats = ChatFileManager.clearChatMediaCache(context)
                                                        }
                                                    }
                                                ) {
                                                    Text("Clear Media Cache", style = XMaxType.buttonCompact, color = CrimsonBlocked)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Unified Floating White Pill Bottom Dock (matches FloatingStudioDock in MainActivity)
                if (!isWide) {
                    FloatingChatDock(
                        activeTab = activeBottomTab,
                        unreadChatsCount = totalUnreadCount,
                        unviewedStatusesCount = unviewedStatusContacts,
                        onSelectTab = { activeBottomTab = it },
                        onQuickCreate = { showQuickChatActionsSheet = true },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    if (showQuickChatActionsSheet) {
        QuickChatActionsSheet(
            onNewChat = {
                showQuickChatActionsSheet = false
                onOpenChannelsSheet()
            },
            onNewGroup = {
                showQuickChatActionsSheet = false
                onOpenCreateGroup()
            },
            onAddStatus = {
                showQuickChatActionsSheet = false
                onOpenCreateStatus()
            },
            onMessageYourself = {
                showQuickChatActionsSheet = false
                onOpenSavedNotes()
            },
            onJoinByLink = {
                showQuickChatActionsSheet = false
                onOpenJoinByInvite()
            },
            onDismiss = { showQuickChatActionsSheet = false }
        )
    }

    if (showEditNoteDialog) {
        AlertDialog(
            onDismissRequest = { showEditNoteDialog = false },
            containerColor = palette.surface,
            title = { Text("Update Your Note", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = statusNoteDraft,
                    onValueChange = { statusNoteDraft = it.take(60) },
                    label = { Text("Current Focus / Status Note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ChatSettingsStore.setStatusNote(context, statusNoteDraft)
                        showEditNoteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) {
                    Text("Save", style = XMaxType.buttonCompact)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNoteDialog = false }) {
                    Text("Cancel", style = XMaxType.buttonCompact)
                }
            }
        )
    }

    // Long-press quick conversation actions (Lock/Unlock, Pin, Mute, Archive, Favourite, Read/Unread)
    channelActionTarget?.let { target ->
        val isLocked = target.id in prefs.lockedChannelIds
        val isPinned = target.isPinnedFor(member.uid)
        val isMuted = target.isMutedFor(member.uid)
        val isArchived = target.isArchivedFor(member.uid)
        val isFav = target.isFavouriteFor(member.uid)
        val hasUnread = target.unreadCountFor(member.uid) > 0

        Dialog(onDismissRequest = { channelActionTarget = null }) {
            Surface(shape = RoundedCornerShape(22.dp), color = palette.surface, border = BorderStroke(1.dp, palette.outline), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formatTitleCase(target.title),
                        style = XMaxType.secondaryTitle,
                        color = palette.textPrimary
                    )
                    HorizontalDivider(color = palette.outline)
                    ActionDialogRow(
                        icon = if (isLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                        label = if (isLocked) "Unlock Chat" else "Lock Chat"
                    ) {
                        channelActionTarget = null
                        if (!prefs.hasSecretCode) {
                            pendingPinSetupAfterLockTarget = target
                            showPinSetupDialog = true
                        } else {
                            ChatSettingsStore.toggleChannelLocked(context, target.id)
                        }
                    }
                    ActionDialogRow(
                        icon = Icons.Outlined.PushPin,
                        label = if (isPinned) "Unpin Chat" else "Pin Chat"
                    ) {
                        channelActionTarget = null
                        onTogglePinChannel(target)
                    }
                    ActionDialogRow(
                        icon = if (isMuted) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        label = if (isMuted) "Unmute Notifications" else "Mute Notifications"
                    ) {
                        channelActionTarget = null
                        onToggleMuteChannel(target)
                    }
                    ActionDialogRow(
                        icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (isFav) "Remove from Favourites" else "Add to Favourites"
                    ) {
                        channelActionTarget = null
                        onToggleFavouriteChannel(target)
                    }
                    ActionDialogRow(
                        icon = Icons.Outlined.Archive,
                        label = if (isArchived) "Unarchive Chat" else "Archive Chat"
                    ) {
                        channelActionTarget = null
                        onToggleArchiveChannel(target)
                    }
                    ActionDialogRow(
                        icon = if (hasUnread) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread,
                        label = if (hasUnread) "Mark as Read" else "Mark as Unread"
                    ) {
                        channelActionTarget = null
                        onMarkReadUnread(target, !hasUnread)
                    }
                }
            }
        }
    }

    if (showPinSetupDialog) {
        PinUnlockDialog(
            title = if (prefs.hasSecretCode) "Change Secret PIN" else "Set Secret Code",
            subtitle = "Use this PIN to hide and unlock protected conversations.",
            confirmSetupMode = true,
            onVerified = {
                pendingPinSetupAfterLockTarget?.let { ch ->
                    ChatSettingsStore.toggleChannelLocked(context, ch.id)
                }
                pendingPinSetupAfterLockTarget = null
                if (pendingPinSetupSwitchLockedTab) {
                    lockedTabUnlocked = true
                    onSelectFilterTab(ChatFilterTab.LOCKED)
                    pendingPinSetupSwitchLockedTab = false
                }
                showPinSetupDialog = false
            },
            onDismiss = {
                pendingPinSetupAfterLockTarget = null
                pendingPinSetupSwitchLockedTab = false
                showPinSetupDialog = false
            }
        )
    }

    if (pinUnlockCallback != null) {
        PinUnlockDialog(
            onVerified = {
                pinUnlockTarget?.let { ChatSettingsStore.markUnlockedForSession(it.id) }
                val cb = pinUnlockCallback
                pinUnlockTarget = null
                pinUnlockCallback = null
                cb?.invoke()
            },
            onDismiss = {
                pinUnlockTarget = null
                pinUnlockCallback = null
            }
        )
    }
}

@Composable
private fun ChatPillFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    val bgColor by animateColorAsState(
        targetValue = if (selected) palette.buttonPrimaryBg else palette.surface,
        animationSpec = tween(160),
        label = "chat_chip_bg_$label"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) palette.buttonPrimaryFg else palette.textPrimary,
        animationSpec = tween(160),
        label = "chat_chip_fg_$label"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        border = if (selected) null else BorderStroke(1.dp, palette.outline),
        modifier = Modifier.defaultMinSize(minHeight = 36.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = label,
                style = XMaxType.footnoteMedium,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FloatingChatDock(
    activeTab: ChatHubBottomTab,
    unreadChatsCount: Int,
    unviewedStatusesCount: Int,
    onSelectTab: (ChatHubBottomTab) -> Unit,
    onQuickCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = XMaxTheme.colors
    val leftTabs = listOf(ChatHubBottomTab.CHATS, ChatHubBottomTab.STATUS)
    val rightTabs = listOf(ChatHubBottomTab.DIRECTORY, ChatHubBottomTab.SETTINGS)

    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(999.dp),
        color = palette.dockBg,
        border = BorderStroke(1.dp, palette.outline),
        shadowElevation = 14.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leftTabs.forEach { tab ->
                val badge = when (tab) {
                    ChatHubBottomTab.CHATS -> unreadChatsCount
                    ChatHubBottomTab.STATUS -> unviewedStatusesCount
                    else -> 0
                }
                ChatDockTabIconButton(
                    tab = tab,
                    selected = activeTab == tab,
                    badgeCount = badge,
                    onClick = { onSelectTab(tab) }
                )
            }

            // Centered '+' Quick Chat Actions FAB (matches MainActivity)
            Surface(
                onClick = onQuickCreate,
                shape = CircleShape,
                color = palette.buttonPrimaryBg,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("chat_dock_quick_create_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Quick Chat Actions",
                        tint = palette.buttonPrimaryFg,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            rightTabs.forEach { tab ->
                ChatDockTabIconButton(
                    tab = tab,
                    selected = activeTab == tab,
                    badgeCount = 0,
                    onClick = { onSelectTab(tab) }
                )
            }
        }
    }
}

@Composable
private fun ChatDockTabIconButton(
    tab: ChatHubBottomTab,
    selected: Boolean,
    badgeCount: Int,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    val bgColor by animateColorAsState(
        targetValue = if (selected) palette.softVioletBg else Color.Transparent,
        animationSpec = tween(200),
        label = "chat_dock_bg_${tab.name}"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) palette.navHighlightColor else palette.iconInactive,
        animationSpec = tween(200),
        label = "chat_dock_tint_${tab.name}"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "chat_dock_scale_${tab.name}"
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier
            .size(48.dp)
            .testTag("chat_nav_tab_${tab.name.lowercase()}")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) tab.icon else tab.inactiveIcon,
                contentDescription = tab.label,
                tint = iconTint,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .clip(CircleShape)
                        .background(VioletPrimary)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badgeCount > 9) "9+" else "$badgeCount",
                        style = XMaxType.caption2Medium.tabular(),
                        color = PureWhite
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickChatActionsSheet(
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onAddStatus: () -> Unit,
    onMessageYourself: () -> Unit,
    onJoinByLink: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Quick Chat Actions", style = XMaxType.secondaryTitle, color = palette.textPrimary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickChatTile("New Chat", "Direct or channel", Icons.Filled.ChatBubble, palette.lavenderBg, VioletPrimary, onNewChat, Modifier.weight(1f))
                QuickChatTile("New Group", "Create team room", Icons.Filled.GroupAdd, palette.skyBg, palette.skyStroke, onNewGroup, Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickChatTile("Add Status", "24h team update", Icons.Filled.Adjust, palette.mintBg, EmeraldApproved, onAddStatus, Modifier.weight(1f))
                QuickChatTile("Saved Notes", "Message yourself", Icons.Filled.Bookmark, palette.peachBg, palette.peachStroke, onMessageYourself, Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = onJoinByLink,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Join Group by Invite Link", style = XMaxType.buttonCompact)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun QuickChatTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    bg: Color,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = XMaxTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(shape = CircleShape, color = palette.surface, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(19.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = XMaxType.headline, color = palette.textPrimary)
                Text(subtitle, style = XMaxType.footnote, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ActionDialogRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
        Text(label, style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
    }
}
