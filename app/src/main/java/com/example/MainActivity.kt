package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.FirebaseStartup
import com.example.data.FirestoreConnectionBanner
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.AgencyViewModel
import com.example.ui.UserProfileState
import com.example.ui.UserViewModel
import com.example.ui.components.*
import com.example.ui.navigation.XMaxNavGraph
import com.example.ui.screens.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class PrimaryTab(val label: String, val icon: ImageVector, val inactiveIcon: ImageVector) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    REQUESTS("Requests", Icons.Filled.Inbox, Icons.Outlined.Inbox),
    CHAT("Chats", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    MY_WORK("My Work", Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt)
}

enum class SecondaryDestination(val label: String, val icon: ImageVector) {
    PROJECTS("Projects", Icons.Filled.Work),
    NOTIFICATIONS("Notifications", Icons.Filled.Notifications),
    CLIENTS("Clients", Icons.Filled.Contacts),
    QUOTATIONS("Quotations", Icons.AutoMirrored.Filled.ReceiptLong),
    CATALOGUE("Catalogue", Icons.Filled.Sell),
    PAYMENTS("Payments", Icons.Filled.Payments),
    ADMIN_DASHBOARD("Admin Dashboard", Icons.Filled.AdminPanelSettings),
    TEAM("Team & Permissions", Icons.Filled.Group),
    ORG_ROUTING("Departments, Roles & Routing", Icons.Filled.AccountTree),
    COMPANY_SETTINGS("Company Settings", Icons.Filled.Business),
    ACTIVITY("Activity Log", Icons.Filled.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseStartup.ensureInitialized(applicationContext)
        AppAppearanceStore.ensureLoaded(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        setContent {
            val vm: AgencyViewModel = viewModel()
            val userVm: UserViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()
            val userProfileState by userVm.userProfileState.collectAsStateWithLifecycle()
            val activeUid = state.myMember?.uid ?: state.firebaseUser?.uid
            LaunchedEffect(activeUid) {
                com.example.ui.theme.AppAppearanceStore.setActiveProfile(applicationContext, activeUid)
                com.example.chat.ChatSettingsStore.setActiveProfile(applicationContext, activeUid)
            }
            val themeMode by AppAppearanceStore.themeModeFlow.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            DisposableEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            XMaxTheme(darkTheme = darkTheme) {
                XMaxAppRoot(
                    state = state,
                    userProfileState = userProfileState,
                    viewModel = vm,
                    onRetryUserProfile = { userVm.retryProfileSync() }
                )
            }
        }
    }
}

@Composable
private fun XMaxAppRoot(
    state: AgencyUiState,
    userProfileState: UserProfileState,
    viewModel: AgencyViewModel,
    onRetryUserProfile: () -> Unit
) {
    XMaxNavGraph(
        state = state,
        viewModel = viewModel,
        userProfileState = userProfileState,
        onRetryUserProfile = onRetryUserProfile,
        dashboardShell = {
            ApprovedWorkspace(state = state, viewModel = viewModel)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovedWorkspace(state: AgencyUiState, viewModel: AgencyViewModel) {
    val context = LocalContext.current
    val member = state.myMember ?: run {
        com.example.ui.navigation.LoadingDestinationScreen(
            label = "Syncing workspace session…",
            userName = state.firebaseUser?.displayName ?: "Alex",
            canSignOut = true,
            onSignOut = { viewModel.signOut(context) }
        )
        return
    }
    val chatViewModel: com.example.ui.ChatViewModel = viewModel()
    val uiPolicy = remember(state.verifiedEmail, state.verifiedUserRole, state.myMember, state.members) {
        WorkspaceUiPolicy.fromState(state)
    }
    val isStaffOnly = uiPolicy.isStandardMember
    val canSeeCommercial = uiPolicy.canSeeCommercialModule
    val canSeeProjects = uiPolicy.canSeeProjectsBoard
    val canCreateQuote = uiPolicy.canManageCommercialQuotes
    val visibleTabs = uiPolicy.visiblePrimaryTabs

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(member.uid) {
        com.example.chat.ChatSettingsStore.ensureLoaded(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var activeTab by remember(member.uid) {
        mutableStateOf(if (isStaffOnly) PrimaryTab.MY_WORK else PrimaryTab.HOME)
    }
    var secondaryDest by remember { mutableStateOf<SecondaryDestination?>(null) }
    var chatInitialChannelId by remember { mutableStateOf<String?>(null) }
    var quoteBuilderMode by remember { mutableStateOf(false) }
    var requirementsQuoteMode by remember { mutableStateOf(false) }
    var editingQuote by remember { mutableStateOf<Quotation?>(null) }
    var quoteRequest by remember { mutableStateOf<ProjectRequest?>(null) }
    var quoteLinkedProject by remember { mutableStateOf<Project?>(null) }
    var activeDeliverable by remember { mutableStateOf<Deliverable?>(null) }
    var showQuickCreateSheet by remember { mutableStateOf(false) }
    var showProfilePhotoDialog by remember { mutableStateOf(false) }
    var showCloudStorageSheet by remember { mutableStateOf(false) }
    var planModeRequestKey by remember { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val palette = XMaxTheme.colors

    val themeMode by AppAppearanceStore.themeModeFlow.collectAsStateWithLifecycle()
    val cloudStorageSummary by viewModel.cloudStorageSummary.collectAsStateWithLifecycle()
    val isScanningCloudStorage by viewModel.isScanningCloudStorage.collectAsStateWithLifecycle()
    val isDeletingCloudStorage by viewModel.isDeletingCloudStorage.collectAsStateWithLifecycle()

    LaunchedEffect(drawerState.isOpen, state.isSuperAdmin) {
        if (drawerState.isOpen && state.isSuperAdmin) {
            viewModel.refreshCloudStorageSummary(context, force = false)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var activeToastTone by remember { mutableStateOf(ToastTone.INFO) }
    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val err = state.errorMessage
        val ok = state.statusMessage
        when {
            !err.isNullOrBlank() -> {
                activeToastTone = ToastTone.ERROR
                viewModel.clearMessages()
                snackbarHostState.showSnackbar(message = err, duration = SnackbarDuration.Short)
            }
            !ok.isNullOrBlank() -> {
                activeToastTone = ToastTone.SUCCESS
                viewModel.clearMessages()
                snackbarHostState.showSnackbar(message = ok, duration = SnackbarDuration.Short)
            }
        }
    }

    if (quoteBuilderMode) {
        BackHandler { quoteBuilderMode = false }
        QuoteBuilderScreen(
            state = state,
            initialQuote = editingQuote,
            initialRequest = quoteRequest,
            linkedProject = quoteLinkedProject,
            onBack = { quoteBuilderMode = false },
            onQuickSaveClient = { cli, cb -> viewModel.saveClient(cli, cb) },
            onCreateRequest = { cli, title, brief, services, target, mgr, cb ->
                viewModel.createRequest(cli, title, brief, services, target, mgr, cb)
            },
            onSaveOrIssueQuote = { draft, issue, cb -> viewModel.saveOrIssueQuote(draft, issue, cb) },
            onCreateRevision = { prev, cb -> viewModel.createQuoteRevision(prev, cb) },
            onGeneratePdf = { q, share, cb -> viewModel.generateAndSavePdf(context, q, share, cb) },
            onMarkSent = { q, onDone -> viewModel.markQuoteSent(q, onDone) },
            onMarkClientApproved = { q, note, onDone -> viewModel.markQuoteClientApproved(q, note, onDone) }
        )
        return
    }

    if (drawerState.isOpen) {
        BackHandler { scope.launch { drawerState.close() } }
    } else if (secondaryDest != null) {
        BackHandler { secondaryDest = null }
    } else if (activeTab == PrimaryTab.CHAT) {
        BackHandler {
            chatInitialChannelId = null
            activeTab = visibleTabs.firstOrNull { it != PrimaryTab.CHAT } ?: PrimaryTab.HOME
        }
    }

    fun openQuote(q: Quotation? = null, req: ProjectRequest? = null, proj: Project? = null) {
        editingQuote = q
        quoteRequest = req
        quoteLinkedProject = proj
        quoteBuilderMode = true
    }

    val permittedSecondary = uiPolicy.permittedSecondaryDestinations

    val pendingBadgeCount = state.members.count { it.statusEnum() == MembershipStatus.PENDING } +
        state.deliverables.count { it.statusEnum() == DeliverableStatus.BLOCKED }
    val notifBadgeCount = state.unreadNotificationCount +
        (if (uiPolicy.isAdminOrAbove) pendingBadgeCount else 0)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AgencyMenuDrawerContent(
                state = state,
                visibleTabs = visibleTabs,
                activeTab = activeTab,
                secondaryDest = secondaryDest,
                permittedSecondary = permittedSecondary,
                canCreateQuote = canCreateQuote,
                themeMode = themeMode,
                cloudStorageSummary = cloudStorageSummary,
                isScanningStorage = isScanningCloudStorage,
                onChangeThemeMode = { mode ->
                    AppAppearanceStore.setThemeMode(context, mode)
                },
                onOpenStorageManager = {
                    scope.launch { drawerState.close() }
                    viewModel.refreshCloudStorageSummary(context, force = true)
                    showCloudStorageSheet = true
                },
                onRefreshStorage = {
                    viewModel.refreshCloudStorageSummary(context, force = true)
                },
                onSelectTab = { tab ->
                    secondaryDest = null
                    activeTab = tab
                    scope.launch { drawerState.close() }
                },
                onSelectSecondary = { dest ->
                    chatInitialChannelId = null
                    secondaryDest = dest
                    scope.launch { drawerState.close() }
                },
                onOpenNewQuote = {
                    scope.launch { drawerState.close() }
                    openQuote()
                },
                onOpenProfilePhoto = {
                    scope.launch { drawerState.close() }
                    showProfilePhotoDialog = true
                },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    viewModel.signOut(context)
                },
                onCloseDrawer = { scope.launch { drawerState.close() } },
                onOpenRequirementsQuote = {
                    scope.launch { drawerState.close() }
                    requirementsQuoteMode = true
                }
            )
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().pastelAmbientBackdrop()) {
            val isWide = maxWidth >= 600.dp
            val isChatFullScreen = secondaryDest == null && activeTab == PrimaryTab.CHAT

            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = if (isChatFullScreen) {
                        WindowInsets(0, 0, 0, 0)
                    } else {
                        ScaffoldDefaults.contentWindowInsets
                    },
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = if (!isWide && secondaryDest == null && !isChatFullScreen) 96.dp else 16.dp
                            )
                        ) { data ->
                            val toastTitle = when (activeToastTone) {
                                ToastTone.ERROR -> "Action Needed"
                                ToastTone.WARNING -> "Heads Up"
                                ToastTone.INFO -> "Workspace Update"
                                ToastTone.SUCCESS -> "Completed"
                            }
                            com.example.ui.components.XMaxToastBanner(
                                title = toastTitle,
                                message = data.visuals.message,
                                tone = activeToastTone,
                                onDismiss = { data.dismiss() }
                            )
                        }
                    },
                    topBar = {
                        val activeSecondary = secondaryDest
                        if (isChatFullScreen) {
                            // TeamChatScreen renders its own full-screen header and edge-to-edge backdrop
                        } else if (activeSecondary != null) {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                                title = {
                                    Text(
                                        formatTitleCase(activeSecondary.label),
                                        style = XMaxType.primaryTitle,
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { secondaryDest = null }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                                    }
                                },
                                actions = {
                                    Spacer(Modifier.width(8.dp))
                                }
                            )
                        } else {
                            // Top Welcome Header with uncompressed ExtraBold Title Case name and notifications button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { scope.launch { drawerState.open() } }
                                        .padding(vertical = 2.dp, horizontal = 2.dp)
                                ) {
                                    StudioAvatar(
                                        name = member.fullName,
                                        photoBase64 = member.photoBase64,
                                        size = 44.dp,
                                        borderColor = palette.surface,
                                        showEditBadge = false,
                                        modifier = Modifier.testTag("header_profile_avatar"),
                                        onClick = { scope.launch { drawerState.open() } }
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Welcome",
                                            style = XMaxType.footnote,
                                            color = palette.textSecondary,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = formatTitleCase(member.fullName.ifBlank { "Member" }),
                                            style = XMaxType.userNameDashboard,
                                            color = palette.textPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box {
                                        CircleHeaderIconButton(
                                            icon = Icons.Outlined.Notifications,
                                            contentDescription = "Notifications & Sound",
                                            modifier = Modifier.testTag("notifications_button"),
                                            onClick = { secondaryDest = SecondaryDestination.NOTIFICATIONS }
                                        )
                                        if (notifBadgeCount > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(top = 3.dp, end = 3.dp)
                                                    .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                                                    .clip(CircleShape)
                                                    .background(WarmAlarmAmber)
                                                    .padding(horizontal = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (notifBadgeCount > 9) "9+" else "$notifBadgeCount",
                                                    style = XMaxType.caption2Medium.tabular(),
                                                    color = PureWhite
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = if (isChatFullScreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxSize().padding(innerPadding)
                        }
                    ) {
                        if (isWide && secondaryDest == null && !isChatFullScreen) {
                            NavigationRail(containerColor = palette.surface) {
                                visibleTabs.forEach { tab ->
                                    NavigationRailItem(
                                        selected = activeTab == tab,
                                        onClick = { activeTab = tab },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label, style = XMaxType.navLabel) }
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                FirestoreConnectionBanner()
                                AnimatedContent(
                                    targetState = Pair(secondaryDest, activeTab),
                                    transitionSpec = {
                                        (fadeIn(tween(210)) + slideInVertically(tween(220)) { it / 32 })
                                            .togetherWith(fadeOut(tween(140)))
                                    },
                                    label = "workspace_content_transition",
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                ) { (dest, tab) ->
                                if (dest != null) {
                                    when (dest) {
                                        SecondaryDestination.PROJECTS -> ProjectsScreen(
                                            state = state,
                                            onOpenDeliverable = { activeDeliverable = it },
                                            onAssignDeliverable = { d, m, due -> viewModel.assignDeliverable(d, m, due) },
                                            onOpenAddOnQuote = { proj -> openQuote(proj = proj) },
                                            onToggleDeleteProject = { proj, del -> viewModel.toggleDeleteProject(proj, del) },
                                            onPermanentlyDeleteProject = { proj -> viewModel.permanentlyDeleteProject(proj) },
                                            onCleanupOldDoneProjects = { viewModel.cleanupOldDoneProjects() },
                                            onGenerateTaxInvoice = { proj, q -> viewModel.generateAndShareTaxInvoicePdf(context, proj, q) },
                                            onCreatePortalLink = { proj, q, onDone -> viewModel.createPortalLink(proj, q?.id ?: proj.approvedQuoteId, 30, onDone) },
                                            onRevokePortalLink = { token -> viewModel.revokePortalLink(token) },
                                            onUpdateProjectStatus = { proj, st -> viewModel.updateProjectStatus(proj, st) }
                                        )
                                        SecondaryDestination.NOTIFICATIONS -> NotificationsScreen(
                                            state = state,
                                            onMarkRead = { viewModel.markNotificationRead(it) },
                                            onMarkAllRead = { viewModel.markAllNotificationsRead() },
                                            onAutoMarkAllRead = { viewModel.markAllNotificationsRead(silent = true) },
                                            onToggleSound = { viewModel.toggleNotificationSound(it) },
                                            onTestSound = { viewModel.testNotificationSound() },
                                            onDeleteNotification = { viewModel.deleteNotification(it) },
                                            onOpenChatChannel = { chId ->
                                                chatInitialChannelId = chId
                                                secondaryDest = null
                                                activeTab = PrimaryTab.CHAT
                                            },
                                            onOpenDeliverableById = { delivId ->
                                                val target = state.deliverables.firstOrNull { it.id == delivId }
                                                if (target != null) {
                                                    secondaryDest = null
                                                    activeTab = PrimaryTab.MY_WORK
                                                    activeDeliverable = target
                                                }
                                            }
                                        )
                                        SecondaryDestination.CLIENTS -> ClientsScreen(
                                            state = state,
                                            onSaveClient = { viewModel.saveClient(it) },
                                            onDeleteClient = { viewModel.deleteClient(it) }
                                        )
                                        SecondaryDestination.QUOTATIONS -> QuotationsScreen(
                                            state = state,
                                            onOpenQuote = { openQuote(q = it) },
                                            onDeleteQuotation = { viewModel.deleteQuotation(it) },
                                            onOpenPayments = { secondaryDest = SecondaryDestination.PAYMENTS },
                                            onShareQuotePdf = { q -> viewModel.generateAndSavePdf(context, q, shareAfterSave = true) },
                                            onOpenRequirementsQuote = { requirementsQuoteMode = true }
                                        )
                                        SecondaryDestination.CATALOGUE -> CatalogueScreen(
                                            state = state,
                                            onSaveItem = { viewModel.saveCatalogueItem(it) },
                                            onToggleArchive = { item, arch -> viewModel.setCatalogueArchived(item, arch) },
                                            onSyncOfficialCatalogue = { viewModel.syncOfficialCatalogueNow() }
                                        )
                                        SecondaryDestination.PAYMENTS -> PaymentsScreen(
                                            state = state,
                                            onRecordPayment = { proj, amt, date, method, ref, note ->
                                                viewModel.recordPayment(proj, amt, date, method, ref, note)
                                            },
                                            onGenerateTaxInvoice = { proj, q -> viewModel.generateAndShareTaxInvoicePdf(context, proj, q) },
                                            onDispatchNudge = { proj, cli, ch, url ->
                                                viewModel.dispatchClientNudge(context, proj, cli, ch, url) { outcome ->
                                                    if (outcome.statusLabel != "SENT" && outcome.launchIntent != null) {
                                                        runCatching { context.startActivity(outcome.launchIntent) }
                                                    }
                                                }
                                            },
                                            onCreatePortalLink = { proj, q, onDone -> viewModel.createPortalLink(proj, q?.id ?: proj.approvedQuoteId, 30, onDone) },
                                            onCreateInvoice = { proj, amt, paid, due, notes ->
                                                viewModel.createInvoice(proj, amt, paid, due, notes)
                                            },
                                            onToggleInvoicePaid = { inv, paid -> viewModel.setInvoicePaidStatus(inv, paid) },
                                            onDeleteInvoice = { inv -> viewModel.deleteInvoice(inv) }
                                        )
                                        SecondaryDestination.ADMIN_DASHBOARD -> AdminDashboardScreen(
                                            state = state,
                                            onRunBackendDiagnostics = { viewModel.runBackendDiagnostics() },
                                            onToggleDeleteProject = { proj, del -> viewModel.toggleDeleteProject(proj, del) },
                                            onPermanentlyDeleteProject = { proj -> viewModel.permanentlyDeleteProject(proj) },
                                            onCleanupOldDoneProjects = { viewModel.cleanupOldDoneProjects() },
                                            onOpenTeamGovernance = { secondaryDest = SecondaryDestination.TEAM },
                                            onOpenCompanySettings = { secondaryDest = SecondaryDestination.COMPANY_SETTINGS },
                                            onBack = { secondaryDest = null },
                                            onConfigureMember = { m, st, lvl, dept, role, sc, scIds, perms ->
                                                viewModel.configureMember(m, st, lvl, dept, role, sc, scIds, perms)
                                            }
                                        )
                                        SecondaryDestination.TEAM -> TeamGovernanceScreen(
                                            state = state,
                                            onInviteMember = { email, lvl, dept, role, sc, scIds, perms ->
                                                viewModel.inviteMember(email, lvl, dept, role, sc, scIds, perms)
                                            },
                                            onConfigureMember = { m, st, lvl, dept, role, sc, scIds, perms ->
                                                viewModel.configureMember(m, st, lvl, dept, role, sc, scIds, perms)
                                            },
                                            onUpdateStaffDelegation = { staff, grants -> viewModel.updateStaffDelegation(staff, grants) }
                                        )
                                        SecondaryDestination.ORG_ROUTING -> OrgAndRoutingScreen(
                                            state = state,
                                            onSaveDepartment = { id, name, d -> viewModel.saveDepartment(id, name, d) },
                                            onSaveJobRole = { id, name, dId -> viewModel.saveJobRole(id, name, dId) },
                                            onRetireDepartment = { ret, repD, repR -> viewModel.retireDepartment(ret, repD, repR) },
                                            onRetireJobRole = { ret, repD, repR -> viewModel.retireJobRole(ret, repD, repR) },
                                            onSaveRoutingRule = { rule -> viewModel.saveRoutingRule(rule) }
                                        )
                                        SecondaryDestination.COMPANY_SETTINGS -> CompanySettingsScreen(
                                            state = state,
                                            onSaveCompany = { viewModel.updateCompanyProfile(it) }
                                        )
                                        SecondaryDestination.ACTIVITY -> ActivityLogScreen(
                                            state = state,
                                            onDeleteLog = { viewModel.deleteActivityLog(it) },
                                            onClearAllLogs = { viewModel.clearAllActivityLogs() }
                                        )
                                    }
                                } else {
                                    when (tab) {
                                        PrimaryTab.HOME -> HomeScreen(
                                            state = state,
                                            onOpenNewQuote = { openQuote() },
                                            onNavigateRequests = { activeTab = PrimaryTab.REQUESTS },
                                            onNavigateProjects = { secondaryDest = SecondaryDestination.PROJECTS },
                                            onNavigateMyWork = { activeTab = PrimaryTab.MY_WORK },
                                            onNavigateTeam = { secondaryDest = SecondaryDestination.TEAM },
                                            onNavigatePayments = { secondaryDest = SecondaryDestination.PAYMENTS },
                                            onNavigateQuotations = { secondaryDest = SecondaryDestination.QUOTATIONS },
                                            onNavigateAdminDashboard = { secondaryDest = SecondaryDestination.ADMIN_DASHBOARD },
                                            onNavigateCompanySettings = { secondaryDest = SecondaryDestination.COMPANY_SETTINGS },
                                            onNavigateOrgRouting = { secondaryDest = SecondaryDestination.ORG_ROUTING },
                                            onOpenMenuPanel = { scope.launch { drawerState.open() } },
                                            onOpenDeliverable = { activeDeliverable = it },
                                            onOpenProjectQuote = { proj -> openQuote(proj = proj) },
                                            onToggleDeleteProject = { proj, del -> viewModel.toggleDeleteProject(proj, del) },
                                            onCleanupOldDoneProjects = { viewModel.cleanupOldDoneProjects() }
                                        )
                                        PrimaryTab.REQUESTS -> RequestsScreen(
                                            state = state,
                                            onOpenQuoteBuilder = { req, q -> openQuote(q = q, req = req) },
                                            onCreateRequest = { cli, t, b, s, d, mgr -> viewModel.createRequest(cli, t, b, s, d, mgr) },
                                            onAcceptRequest = { req, q, drafts -> viewModel.acceptRequest(req, q, drafts) },
                                            onDeclineRequest = { req, note -> viewModel.declineRequest(req, note) },
                                            onOpenRequirementsQuote = { requirementsQuoteMode = true }
                                        )
                                        PrimaryTab.CHAT -> TeamChatScreen(
                                            state = state,
                                            initialChannelId = chatInitialChannelId,
                                            observeMessages = { channelId -> viewModel.observeChatMessages(channelId) },
                                            onEnsureGeneralChannel = { viewModel.ensureDefaultGeneralChannel() },
                                            onCreateChannel = { title, topic, type, proj, onReady, onError ->
                                                viewModel.createOrOpenChatChannel(title, topic, type, proj, onReady, onError)
                                            },
                                            onSendMessage = { chId, body, parent, attachment, cb ->
                                                viewModel.sendChatMessage(chId, body, parent, attachment, cb)
                                            },
                                            onReportMessage = { msg, err -> viewModel.reportChatMessage(msg, err) },
                                            onOpenDirectChat = { targetMember, onReady, onError ->
                                                viewModel.openDirectChat(targetMember, onReady, onError)
                                            },
                                            onOpenDepartmentChat = { dept, onReady, onError ->
                                                viewModel.openDepartmentChat(dept, onReady, onError)
                                            },
                                            onDeleteChannel = { channelId, onDone ->
                                                viewModel.deleteChatChannel(channelId, onDone)
                                            },
                                            onDeleteMessage = { channelId, messageId ->
                                                viewModel.deleteChatMessage(channelId, messageId)
                                            },
                                            onSetCompanyDefaultWallpaper = { presetId, customUrl ->
                                                viewModel.setCompanyDefaultWallpaper(presetId, customUrl)
                                            },
                                            onStartVideoCall = { ch, callees, onStarted ->
                                                viewModel.startVideoCall(ch, callees, onStarted)
                                            },
                                            onStartVoiceCall = { ch, callees, onStarted ->
                                                viewModel.startVoiceCall(ch, callees, onStarted)
                                            },
                                            onAcceptVideoCall = { callId ->
                                                viewModel.acceptVideoCall(callId)
                                            },
                                            onEndVideoCall = { callId, st ->
                                                viewModel.endOrDeclineVideoCall(callId, st)
                                            },
                                            onToggleCallMute = { callId, isMuted ->
                                                viewModel.toggleCallMute(callId, isMuted)
                                            },
                                            onToggleCallCamera = { callId, isOff ->
                                                viewModel.toggleCallCamera(callId, isOff)
                                            },
                                            onCloseChat = {
                                                chatInitialChannelId = null
                                                activeTab = visibleTabs.firstOrNull { it != PrimaryTab.CHAT } ?: PrimaryTab.HOME
                                            },
                                            chatViewModel = chatViewModel
                                        )
                                        PrimaryTab.MY_WORK -> MyWorkScreen(
                                            state = state,
                                            deliverableTimerStateFlow = viewModel.deliverableTimerState,
                                            focusTimerStateFlow = viewModel.focusTimerState,
                                            onOpenDeliverable = { activeDeliverable = it },
                                            onStartDeliverable = { viewModel.startDeliverableWork(it) },
                                            onClaimDepartmentTask = { viewModel.claimDepartmentDeliverable(it) },
                                            onToggleTimer = { viewModel.toggleDeliverableTimer(it) },
                                            onQuickSubmitReview = { deliv ->
                                                val completedChecklist = deliv.checklist.map { it.copy(done = true) }
                                                viewModel.submitStaffProgress(
                                                    deliverable = deliv,
                                                    status = DeliverableStatus.READY_FOR_REVIEW,
                                                    checklist = completedChecklist,
                                                    note = "Submitted directly for review",
                                                    previewUrl = deliv.previewUrl,
                                                    blockedReason = "",
                                                    helpNeeded = ""
                                                )
                                            },
                                            planModeRequestKey = planModeRequestKey,
                                            onSavePlanItem = { item, cb -> viewModel.savePlanItem(item, cb) },
                                            onTogglePlanCompleted = { viewModel.togglePlanItemCompleted(it) },
                                            onReschedulePlanTomorrow = { viewModel.reschedulePlanItemTomorrow(it) },
                                            onCarryOverIncompletePlans = { items, day -> viewModel.carryOverIncompletePlans(items, day) },
                                            onDeletePlanItem = { viewModel.deletePlanItem(it) },
                                            onToggleFocusTimer = { viewModel.toggleFocusTimer() },
                                            onResetFocusTimer = { viewModel.resetFocusTimer() },
                                            onSetFocusPreset = { viewModel.setFocusPresetMinutes(it) },
                                            onLogTimeEntry = { proj, deliv, mins, bill, rate, notes ->
                                                viewModel.logTimeEntry(proj, deliv, mins, bill, rate, notes)
                                            },
                                            onCommitTimerToTimeEntry = { d -> viewModel.commitTimerToTimeEntry(d) },
                                            onDeleteTimeEntry = { id -> viewModel.deleteTimeEntry(id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

                // 5-Slot Floating White Pill Bottom Dock with Centered Jet-Black + FAB
                AnimatedVisibility(
                    visible = !isWide && secondaryDest == null && !isChatFullScreen,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 2 },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    FloatingStudioDock(
                        visibleTabs = visibleTabs,
                        activeTab = activeTab,
                        onSelectTab = { activeTab = it },
                        onQuickCreate = { showQuickCreateSheet = true }
                    )
                }
            }
        }
    }

    if (showQuickCreateSheet) {
        QuickWorkspaceSheet(
            member = member,
            canCreateQuote = canCreateQuote,
            canSeeCommercial = canSeeCommercial,
            canSeeProjects = canSeeProjects,
            isAdmin = uiPolicy.isAdminOrAbove,
            canSeePayments = state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS),
            onDismiss = { showQuickCreateSheet = false },
            onNewQuote = {
                showQuickCreateSheet = false
                openQuote()
            },
            onOpenRequirementsQuote = {
                showQuickCreateSheet = false
                requirementsQuoteMode = true
            },
            onOpenRequests = {
                showQuickCreateSheet = false
                secondaryDest = null
                activeTab = PrimaryTab.REQUESTS
            },
            onOpenProjects = {
                showQuickCreateSheet = false
                secondaryDest = SecondaryDestination.PROJECTS
            },
            onOpenMyWork = {
                showQuickCreateSheet = false
                secondaryDest = null
                activeTab = PrimaryTab.MY_WORK
            },
            onOpenDailyPlan = {
                showQuickCreateSheet = false
                secondaryDest = null
                activeTab = PrimaryTab.MY_WORK
                planModeRequestKey += 1
            },
            onOpenSecondary = { dest ->
                showQuickCreateSheet = false
                chatInitialChannelId = null
                secondaryDest = dest
            }
        )
    }

    if (requirementsQuoteMode && canCreateQuote) {
        ProjectRequirementsQuoteSheet(
            state = state,
            onDismiss = { requirementsQuoteMode = false },
            onSaveClient = { cli, cb -> viewModel.saveClient(cli, cb) },
            onCreateRequest = { cli, title, brief, services, target, mgr, cb ->
                viewModel.createRequest(cli, title, brief, services, target, mgr, cb)
            },
            onSaveQuoteDraft = { draft, issue, cb ->
                viewModel.saveOrIssueQuote(draft, issue, cb)
            },
            onOpenInFullQuoteBuilder = { req, quote ->
                requirementsQuoteMode = false
                openQuote(q = quote, req = req)
            }
        )
    }

    if (showProfilePhotoDialog) {
        ProfileOverviewDialog(
            member = member,
            emailOrPhone = state.firebaseUser?.email ?: state.firebaseUser?.phoneNumber ?: "",
            onPickPhoto = { uri ->
                viewModel.updateMyProfilePhoto(context, uri) { showProfilePhotoDialog = false }
            },
            onSaveCroppedPhoto = { bitmap, zoom, offX, offY, onDone, onError ->
                viewModel.updateMyCroppedProfilePhoto(
                    bitmap = bitmap,
                    zoom = zoom,
                    offsetXFrac = offX,
                    offsetYFrac = offY,
                    onDone = onDone,
                    onError = onError
                )
            },
            onRemovePhoto = {
                viewModel.removeMyProfilePhoto(onDone = { showProfilePhotoDialog = false })
            },
            onDismiss = { showProfilePhotoDialog = false }
        )
    }

    var activeCallOverlayId by remember { mutableStateOf<String?>(null) }
    var dismissedIncomingCallIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val ringingIncomingCall = remember(state.activeCallSessions, member.uid, activeCallOverlayId, dismissedIncomingCallIds) {
        state.activeCallSessions.firstOrNull { s ->
            s.id != activeCallOverlayId &&
                s.id !in dismissedIncomingCallIds &&
                s.status == "RINGING" &&
                s.callerUid != member.uid &&
                member.uid in s.calleeUids
        }
    }

    if (ringingIncomingCall != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            com.example.ui.chat.IncomingCallBanner(
                session = ringingIncomingCall,
                onAccept = {
                    activeCallOverlayId = ringingIncomingCall.id
                    viewModel.acceptVideoCall(ringingIncomingCall.id)
                },
                onDecline = {
                    dismissedIncomingCallIds = dismissedIncomingCallIds + ringingIncomingCall.id
                    viewModel.endOrDeclineVideoCall(ringingIncomingCall.id, "DECLINED")
                },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    activeCallOverlayId?.let { overlayId ->
        val callSession = state.activeCallSessions.firstOrNull { it.id == overlayId }
        val callChannel = state.chatChannels.firstOrNull { it.id == (callSession?.channelId ?: "") }
            ?: ChatChannel(
                id = callSession?.channelId ?: "",
                title = callSession?.channelTitle ?: "Video Call",
                participantUids = listOfNotNull(callSession?.callerUid) + (callSession?.calleeUids ?: emptyList())
            )
        val peers = state.members.filter { it.uid != member.uid && it.uid in callChannel.participantUids }
        val companyId = state.company?.id?.ifBlank { CatalogueSeedData.DEFAULT_COMPANY_ID } ?: CatalogueSeedData.DEFAULT_COMPANY_ID
        val initialType = if (callSession?.callType == "AUDIO" || callSession?.videoEnabled == false) CallType.AUDIO else CallType.VIDEO
        com.example.ui.chat.ChatVideoCallDialog(
            channel = callChannel,
            myMember = member,
            peerMembers = peers,
            callSession = callSession,
            companyId = companyId,
            initialCallType = initialType,
            onAcceptCall = { callSession?.let { viewModel.acceptVideoCall(it.id) } },
            onEndOrDeclineCall = { st -> viewModel.endOrDeclineVideoCall(overlayId, st) },
            onToggleMute = { isMuted -> callSession?.let { viewModel.toggleCallMute(it.id, isMuted) } },
            onToggleCamera = { isOff -> callSession?.let { viewModel.toggleCallCamera(it.id, isOff) } },
            onDismiss = { activeCallOverlayId = null },
            onCallCompleted = { _ -> }
        )
    }

    if (showCloudStorageSheet && state.isSuperAdmin) {
        CloudStorageManagerSheet(
            summary = cloudStorageSummary,
            isScanning = isScanningCloudStorage,
            isDeleting = isDeletingCloudStorage,
            onRefresh = { viewModel.refreshCloudStorageSummary(context, force = true) },
            onDeleteItems = { items, onDone ->
                viewModel.deleteCloudStorageItems(context, items, onDone)
            },
            onDeleteExpiredStatuses = { viewModel.deleteExpiredCloudStatuses(context) },
            onClearLocalCache = { viewModel.clearLocalMediaCache(context) },
            onDismiss = { showCloudStorageSheet = false }
        )
    }

    activeDeliverable?.let { d ->
        val fresh = state.deliverables.firstOrNull { it.id == d.id } ?: d
        DeliverableDetailDialog(
            state = state,
            deliverable = fresh,
            updatesFlow = viewModel.observeDeliverableUpdates(fresh.id),
            deliverableTimerStateFlow = viewModel.deliverableTimerState,
            onSubmitProgress = { deliv, status, checklist, note, url, reason, help ->
                viewModel.submitStaffProgress(deliv, status, checklist, note, url, reason, help)
            },
            onReviewDeliverable = { deliv, action, fb ->
                viewModel.reviewDeliverable(deliv, action, fb)
            },
            onStartWork = { viewModel.startDeliverableWork(it) },
            onToggleTimer = { viewModel.toggleDeliverableTimer(it) },
            onResetTimer = { viewModel.resetDeliverableTimer(it) },
            onCommitTimerToTimeEntry = { deliv -> viewModel.commitTimerToTimeEntry(deliv) },
            onDismiss = { activeDeliverable = null }
        )
    }
}

@Composable
private fun CircleHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = palette.surface,
            border = BorderStroke(1.dp, palette.outline),
            shadowElevation = 1.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingStudioDock(
    visibleTabs: List<PrimaryTab>,
    activeTab: PrimaryTab,
    onSelectTab: (PrimaryTab) -> Unit,
    onQuickCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = XMaxTheme.colors
    val splitIndex = (visibleTabs.size + 1) / 2
    val leftTabs = visibleTabs.take(splitIndex)
    val rightTabs = visibleTabs.drop(splitIndex)

    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(999.dp),
        color = palette.surfaceElevated,
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
                DockTabIconButton(
                    tab = tab,
                    selected = activeTab == tab,
                    onClick = { onSelectTab(tab) }
                )
            }

            // Centered Quick Workspace FAB
            Surface(
                onClick = onQuickCreate,
                shape = CircleShape,
                color = palette.buttonPrimaryBg,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("dock_quick_create_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Quick Actions",
                        tint = palette.buttonPrimaryFg,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            rightTabs.forEach { tab ->
                DockTabIconButton(
                    tab = tab,
                    selected = activeTab == tab,
                    onClick = { onSelectTab(tab) }
                )
            }
        }
    }
}

@Composable
private fun DockTabIconButton(
    tab: PrimaryTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    val bgColor by animateColorAsState(
        targetValue = if (selected) palette.kpiLavenderBg else Color.Transparent,
        animationSpec = tween(200),
        label = "dock_bg_${tab.name}"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) palette.navHighlightColor else palette.iconInactive,
        animationSpec = tween(200),
        label = "dock_tint_${tab.name}"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "dock_scale_${tab.name}"
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier
            .size(48.dp)
            .testTag("nav_tab_${tab.name.lowercase()}")
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
        }
    }
}

private data class QuickActionTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val bg: Color,
    val tint: Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickWorkspaceSheet(
    member: Member,
    canCreateQuote: Boolean,
    canSeeCommercial: Boolean,
    canSeeProjects: Boolean,
    isAdmin: Boolean,
    canSeePayments: Boolean,
    onDismiss: () -> Unit,
    onNewQuote: () -> Unit,
    onOpenRequirementsQuote: () -> Unit = {},
    onOpenRequests: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenMyWork: () -> Unit,
    onOpenDailyPlan: () -> Unit,
    onOpenSecondary: (SecondaryDestination) -> Unit
) {
    val palette = XMaxTheme.colors
    val firstName = member.fullName.trim().substringBefore(" ").ifBlank { "Member" }
    val tiles = buildList {
        if (canCreateQuote) {
            add(
                QuickActionTile(
                    title = "Quote Summary",
                    subtitle = "Brief -> client quote",
                    icon = Icons.Outlined.AutoAwesome,
                    bg = palette.kpiLavenderBg,
                    tint = palette.kpiLavenderStroke,
                    onClick = onOpenRequirementsQuote
                )
            )
            add(
                QuickActionTile(
                    title = "New Quote",
                    subtitle = "56-service PDF",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    bg = palette.kpiPeachBg,
                    tint = palette.kpiPeachStroke,
                    onClick = onNewQuote
                )
            )
        }
        if (canSeeCommercial) {
            add(
                QuickActionTile(
                    title = "Requests",
                    subtitle = "Client pipeline",
                    icon = Icons.Filled.Inbox,
                    bg = palette.kpiLavenderBg,
                    tint = palette.kpiLavenderStroke,
                    onClick = onOpenRequests
                )
            )
        }
        if (canSeeProjects) {
            add(
                QuickActionTile(
                    title = "Projects",
                    subtitle = "Live board",
                    icon = Icons.Filled.Work,
                    bg = palette.kpiSkyBg,
                    tint = palette.kpiSkyStroke,
                    onClick = onOpenProjects
                )
            )
        }
        add(
            QuickActionTile(
                title = "My Tasks",
                subtitle = "Schedule & checklists",
                icon = Icons.Filled.TaskAlt,
                bg = palette.kpiMintBg,
                tint = palette.kpiMintStroke,
                onClick = onOpenMyWork
            )
        )
        add(
            QuickActionTile(
                title = "Daily Plan",
                subtitle = "Calendar & focus",
                icon = Icons.Filled.CalendarMonth,
                bg = palette.kpiLavenderBg,
                tint = palette.kpiLavenderStroke,
                onClick = onOpenDailyPlan
            )
        )
        if (canSeeCommercial) {
            add(
                QuickActionTile(
                    title = "Clients",
                    subtitle = "Accounts",
                    icon = Icons.Filled.Contacts,
                    bg = palette.kpiSkyBg,
                    tint = palette.kpiSkyStroke,
                    onClick = { onOpenSecondary(SecondaryDestination.CLIENTS) }
                )
            )
        }
        if (canSeePayments) {
            add(
                QuickActionTile(
                    title = "Payments",
                    subtitle = "Invoices & dues",
                    icon = Icons.Filled.Payments,
                    bg = palette.kpiMintBg,
                    tint = palette.kpiMintStroke,
                    onClick = { onOpenSecondary(SecondaryDestination.PAYMENTS) }
                )
            )
        }
        if (isAdmin) {
            add(
                QuickActionTile(
                    title = "Team & Access",
                    subtitle = "Approvals & roles",
                    icon = Icons.Filled.Group,
                    bg = palette.kpiLavenderBg,
                    tint = palette.kpiLavenderStroke,
                    onClick = { onOpenSecondary(SecondaryDestination.TEAM) }
                )
            )
        }
    }.take(4)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudioAvatar(
                    name = member.fullName,
                    photoBase64 = member.photoBase64,
                    size = 48.dp,
                    borderColor = palette.outline,
                    showEditBadge = false
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hi, ${formatTitleCase(firstName)}!",
                        style = XMaxType.subheadline,
                        color = palette.textSecondary
                    )
                    Text(
                        text = "Quick Workspace Actions",
                        style = XMaxType.secondaryTitle,
                        color = palette.textPrimary
                    )
                }
            }

            tiles.chunked(2).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowTiles.forEach { tile ->
                        Surface(
                            onClick = tile.onClick,
                            shape = RoundedCornerShape(22.dp),
                            color = tile.bg,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = palette.surfaceElevated,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = tile.icon,
                                            contentDescription = tile.title,
                                            tint = tile.tint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = tile.title,
                                        style = XMaxType.headline,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = tile.subtitle,
                                        style = XMaxType.footnote,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (rowTiles.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

