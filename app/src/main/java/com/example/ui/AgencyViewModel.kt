package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat.ChatE2eeCrypto
import com.example.chat.ChatFileManager
import com.example.chat.ChatSettingsStore
import com.example.chat.StagedChatAttachment
import com.example.chat.realtime.RtdbPresenceManager
import com.example.ui.theme.AppAppearanceStore
import com.example.data.AgencyRepository
import com.example.data.AuthSessionStatus
import com.example.data.AuthenticationRepository
import com.example.data.BackendDiagnosticReport
import com.example.data.BackendHealthDiagnostics
import com.example.data.ChatRepository
import com.example.data.ClientRepository
import com.example.data.CloudStorageSummary
import com.example.data.CloudStorageTracker
import com.example.data.CommercialWorkflowRepository
import com.example.data.DeletableCloudItem
import com.example.data.formatStorageBytes
import com.example.data.FirestoreInitState
import com.example.data.GoogleSignInResult
import com.example.data.OperationType
import com.example.data.PhoneVerificationSession
import com.example.data.ProjectRepository
import com.example.data.QuotationRepository
import com.example.data.SyncedData
import com.example.data.VerifiedUserRole
import com.example.data.findActivity
import com.example.data.handleFirestoreError
import com.example.domain.CatalogueSeedData
import com.example.domain.DeliverableDraft
import com.example.domain.PermissionEngine
import com.example.model.*
import com.example.pdf.QuotationPdfGenerator
import com.example.system.AppHapticsAndNotifier
import com.example.system.PaymentReminderAutomator
import com.example.system.PaymentReminderWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class AgencyUiState(
    val authSessionStatus: AuthSessionStatus = AuthSessionStatus.Initializing,
    val authInitialized: Boolean = false,
    val firebaseUser: FirebaseUser? = null,
    val company: CompanyProfile? = null,
    val companyLoaded: Boolean = false,
    val myMember: Member? = null,
    val memberLoaded: Boolean = false,
    val myPrivateInfo: MemberPrivateInfo? = null,
    val members: List<Member> = emptyList(),
    val allPrivateInfo: List<MemberPrivateInfo> = emptyList(),
    val invitations: List<Invitation> = emptyList(),
    val departments: List<Department> = emptyList(),
    val jobRoles: List<JobRole> = emptyList(),
    val catalogue: List<CatalogueItem> = emptyList(),
    val routingRules: List<RoutingRule> = emptyList(),
    val clients: List<Client> = emptyList(),
    val requests: List<ProjectRequest> = emptyList(),
    val quotations: List<Quotation> = emptyList(),
    val projects: List<Project> = emptyList(),
    val deliverables: List<Deliverable> = emptyList(),
    val deliverablesPendingSync: Boolean = false,
    val payments: List<PaymentRecord> = emptyList(),
    val activityLog: List<ActivityLog> = emptyList(),
    val chatChannels: List<ChatChannel> = emptyList(),
    val chatStatuses: List<ChatStatus> = emptyList(),
    val notifications: List<WorkspaceNotification> = emptyList(),
    val staffPlanItems: List<StaffPlanItem> = emptyList(),
    val timeEntries: List<TimeEntry> = emptyList(),
    val portalLinks: List<ClientPortalLink> = emptyList(),
    val nudgeLogs: List<PaymentNudgeLog> = emptyList(),
    val activeCallSessions: List<ChatCallSession> = emptyList(),
    val soundEnabled: Boolean = true,
    val activeTimerDeliverableId: String? = null,
    val timerSecondsByDeliverable: Map<String, Long> = emptyMap(),
    val focusPresetMinutes: Int = 25,
    val focusSecondsRemaining: Long = 25 * 60L,
    val isFocusTimerRunning: Boolean = false,
    val syncFeedback: SyncFeedback = SyncFeedback.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val authInProgress: Boolean = false,
    val autoBootstrapInProgress: Boolean = false,
    val verifiedUserRole: VerifiedUserRole = VerifiedUserRole(),
    val backendDiagnostics: BackendDiagnosticReport = BackendDiagnosticReport()
) {
    val myNotifications: List<WorkspaceNotification>
        get() = notifications.filter { it.appliesTo(myMember) }

    val unreadNotificationCount: Int
        get() {
            val uid = myMember?.uid.orEmpty()
            return if (uid.isBlank()) 0 else myNotifications.count { it.isUnreadFor(uid) }
        }

    val membersByUid: Map<String, Member> get() = members.associateBy { it.uid }
    val effectivePermissions: Set<AppPermission>
        get() = PermissionEngine.effectivePermissions(myMember, membersByUid)

    fun hasPerm(perm: AppPermission): Boolean =
        PermissionEngine.hasPermission(myMember, perm, membersByUid)

    val verifiedEmail: String
        get() = firebaseUser?.email?.trim().orEmpty()
            .ifBlank { (authSessionStatus as? AuthSessionStatus.SignedIn)?.user?.email?.trim().orEmpty() }
            .ifBlank { verifiedUserRole.email.trim() }
            .ifBlank { runCatching { FirebaseAuth.getInstance().currentUser?.email }.getOrNull().orEmpty().trim() }

    val isSuperAdmin: Boolean
        get() {
            val hasActiveUser = firebaseUser != null || authSessionStatus is AuthSessionStatus.SignedIn || verifiedUserRole.isAuthenticated
            return if (hasActiveUser) {
                CatalogueSeedData.isSuperAdminEmail(verifiedEmail)
            } else {
                // Fixture fallback only when no Firebase session is attached (e.g., Compose UI tests)
                PermissionEngine.isActiveApproved(myMember) && myMember?.accessLevelEnum() == AccessLevel.SUPER_ADMIN
            }
        }

    val isCurrentUserSuperAdminEmail: Boolean
        get() = CatalogueSeedData.isSuperAdminEmail(verifiedEmail)

    val isAdmin: Boolean
        get() = PermissionEngine.isActiveApproved(myMember) && myMember?.accessLevelEnum() == AccessLevel.ADMIN

    val isApprovedMember: Boolean
        get() {
            if (CatalogueSeedData.isBootstrapEmail(verifiedEmail)) return true
            return PermissionEngine.isActiveApproved(myMember)
        }

    val isBootstrappedEmail: Boolean
        get() = CatalogueSeedData.isBootstrapEmail(verifiedEmail)
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgencyViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = AuthenticationRepository(application)
    private val coreRepo = AgencyRepository(application)
    private val firestoreInitService = coreRepo.firestoreInitService
    private val clientRepo: ClientRepository = ClientRepository(coreRepo)
    private val projectRepo: ProjectRepository = ProjectRepository(coreRepo)
    private val quotationRepo: QuotationRepository = QuotationRepository(coreRepo)
    private val workRepo = CommercialWorkflowRepository(coreRepo)
    private val chatRepo = ChatRepository(coreRepo)
    private val storageTracker = CloudStorageTracker(coreRepo)

    val firestoreInitState: StateFlow<FirestoreInitState> = firestoreInitService.initState

    private val _cloudStorageSummary = MutableStateFlow(CloudStorageSummary())
    val cloudStorageSummary: StateFlow<CloudStorageSummary> = _cloudStorageSummary.asStateFlow()

    private val _isScanningCloudStorage = MutableStateFlow(false)
    val isScanningCloudStorage: StateFlow<Boolean> = _isScanningCloudStorage.asStateFlow()

    private val _isDeletingCloudStorage = MutableStateFlow(false)
    val isDeletingCloudStorage: StateFlow<Boolean> = _isDeletingCloudStorage.asStateFlow()

    private val _backendDiagnostics = MutableStateFlow(BackendDiagnosticReport())
    val backendDiagnostics: StateFlow<BackendDiagnosticReport> = _backendDiagnostics.asStateFlow()

    private val _authInProgress = MutableStateFlow(false)
    private val _phoneSession = MutableStateFlow(PhoneVerificationSession())
    val phoneSession: StateFlow<PhoneVerificationSession> = _phoneSession.asStateFlow()
    private val _autoBootstrapInProgress = MutableStateFlow(false)
    private val _profileRetryTrigger = MutableStateFlow(0)
    private var _lastAutoBootstrapUid: String? = null
    private val _syncFeedback = MutableStateFlow(SyncFeedback.IDLE)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _soundEnabled = MutableStateFlow(AppHapticsAndNotifier.isSoundEnabled(application))
    private val _activeTimerDeliverableId = MutableStateFlow<String?>(null)
    private val _timerSeconds = MutableStateFlow(AppHapticsAndNotifier.loadSavedTimers(application))
    private var timerJob: Job? = null
    private val _focusPresetMinutes = MutableStateFlow(25)
    private val _focusSecondsRemaining = MutableStateFlow(25 * 60L)
    private val _isFocusTimerRunning = MutableStateFlow(false)
    private var focusTimerJob: Job? = null
    private var notificationsHydratedForUid: String? = null
    private val seenNotificationIds = mutableSetOf<String>()
    private var syncedCatalogueOnceForUid: String? = null
    private var paymentWorkerScheduledForUid: String? = null

    val sessionStatus: StateFlow<AuthSessionStatus> = authRepository.sessionStatusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthSessionStatus.Initializing)

    private val _userFlow: StateFlow<FirebaseUser?> = sessionStatus
        .map { (it as? AuthSessionStatus.SignedIn)?.user }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authRepository.currentUser)

    private val _verifiedRoleFlow: StateFlow<VerifiedUserRole> = authRepository.verifiedUserRoleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authRepository.currentVerifiedUserRole())

    /**
     * Emits the signed-in [FirebaseUser] ONLY after [FirestoreInitializationService.initializeAfterAuth]
     * succeeds for that user, preventing any premature Firestore listener or read before auth.
     */
    private val _authenticatedReadyUser: StateFlow<FirebaseUser?> = combine(
        sessionStatus,
        _profileRetryTrigger
    ) { status, _ -> status }
        .flatMapLatest { status ->
            when (status) {
                AuthSessionStatus.Initializing -> flowOf<FirebaseUser?>(null)
                AuthSessionStatus.SignedOut -> {
                    firestoreInitService.onSignedOut()
                    flowOf<FirebaseUser?>(null)
                }
                is AuthSessionStatus.SignedIn -> flow<FirebaseUser?> {
                    val res = firestoreInitService.initializeAfterAuth(status.user)
                    res.onSuccess {
                        emit(status.user)
                    }.onFailure { err ->
                        if (err is CancellationException) throw err
                        _errorMessage.value = err.localizedMessage
                            ?: "Unable to initialize Firestore for your account. Retry or check connectivity."
                        emit(null)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _myMemberFlow = _authenticatedReadyUser
        .flatMapLatest { user ->
            if (user == null) {
                val signedInNow = sessionStatus.value is AuthSessionStatus.SignedIn
                flowOf(null to !signedInNow)
            } else {
                val isBootstrap = CatalogueSeedData.isBootstrapEmail(user.email)
                val defaultSuperAdmin = if (isBootstrap) {
                    Member(
                        id = user.uid,
                        uid = user.uid,
                        fullName = user.displayName?.takeIf { it.isNotBlank() } ?: "Super Admin",
                        accessLevel = AccessLevel.SUPER_ADMIN.name,
                        status = MembershipStatus.APPROVED.name,
                        active = true,
                        departmentId = "dept_ops",
                        departmentName = "Operations",
                        jobRoleId = "role_director",
                        jobRoleName = "Super Admin",
                        scopeType = ScopeType.COMPANY.name
                    )
                } else null

                coreRepo.observeMyMember(user.uid)
                    .map { fetched ->
                        (fetched ?: defaultSuperAdmin) to true
                    }
                    .catch { err ->
                        if (err is CancellationException) throw err
                        android.util.Log.w("AgencyViewModel", "observeMyMember error: ${err.message}")
                        if (defaultSuperAdmin != null) {
                            emit(defaultSuperAdmin to true)
                        } else {
                            if (_errorMessage.value.isNullOrBlank()) {
                                _errorMessage.value = err.localizedMessage
                                    ?: "Unable to sync your workspace profile. Check connection and retry."
                            }
                            emit(null to false)
                        }
                    }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null to false)

    private val _companyFlow = combine(_authenticatedReadyUser, _myMemberFlow) { user, (member, memberLoaded) ->
        if (user == null || !memberLoaded) {
            Triple(false, user, false)
        } else {
            val isBootstrap = CatalogueSeedData.isBootstrapEmail(user.email)
            val canReadCompany = isBootstrap || PermissionEngine.isActiveApproved(member)
            Triple(true, user, canReadCompany)
        }
    }.distinctUntilChanged().flatMapLatest { (ready, _, canReadCompany) ->
        when {
            !ready -> flowOf(null to false)
            !canReadCompany -> flowOf(null to true)
            else -> coreRepo.observeCompany().map { it to true }.catch { emit(null to true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null to false)

    private val _myPrivateFlow = _authenticatedReadyUser.flatMapLatest { user ->
        if (user == null) flowOf(null)
        else coreRepo.observeMyPrivateInfo(user.uid).catch { emit(null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _approvedGate = combine(_authenticatedReadyUser, _myMemberFlow) { user, (member, _) ->
        if (user != null && PermissionEngine.isActiveApproved(member)) member else null
    }.distinctUntilChanged()

    private val _membersFlow = _approvedGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else coreRepo.observeMembers().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _departmentsFlow = _approvedGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else coreRepo.observeDepartments().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _jobRolesFlow = _approvedGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else coreRepo.observeJobRoles().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _catalogueFlow = _approvedGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else coreRepo.observeCatalogue().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _routingRulesFlow = _approvedGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else coreRepo.observeRoutingRules().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _allPrivateFlow = _approvedGate.flatMapLatest { m ->
        if (m?.accessLevelEnum() == AccessLevel.SUPER_ADMIN) {
            coreRepo.observeAllPrivateInfo().catch { emit(emptyList()) }
        } else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _invitationsFlow = _approvedGate.flatMapLatest { m ->
        if (m?.accessLevelEnum() == AccessLevel.SUPER_ADMIN) {
            coreRepo.observeInvitations().catch { emit(emptyList()) }
        } else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activityFlow = _approvedGate.flatMapLatest { m ->
        if (m?.accessLevelEnum() == AccessLevel.SUPER_ADMIN || m?.accessLevelEnum() == AccessLevel.ADMIN) {
            coreRepo.observeActivityLog().catch { emit(emptyList()) }
        } else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _commercialGate = combine(_approvedGate, _membersFlow) { m, all ->
        if (m == null) null
        else {
            val map = all.associateBy { it.uid }
            val ok = m.accessLevelEnum() == AccessLevel.SUPER_ADMIN ||
                m.accessLevelEnum() == AccessLevel.ADMIN ||
                listOf(
                    AppPermission.MANAGE_CLIENTS,
                    AppPermission.CREATE_REQUESTS,
                    AppPermission.CREATE_QUOTATIONS,
                    AppPermission.ISSUE_QUOTATIONS,
                    AppPermission.VIEW_ALL_CLIENTS_AND_QUOTES,
                    AppPermission.VIEW_PAYMENTS,
                    AppPermission.RECORD_PAYMENTS
                ).any { PermissionEngine.hasPermission(m, it, map) }
            if (ok) m else null
        }
    }.distinctUntilChanged()

    private val _clientsFlow = _commercialGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else clientRepo.observeClients().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _requestsFlow = _commercialGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else projectRepo.observeRequests().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quotationsFlow = _commercialGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else quotationRepo.observeQuotations().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _projectGate = combine(_approvedGate, _membersFlow) { m, all ->
        if (m == null) null
        else {
            val map = all.associateBy { it.uid }
            val ok = m.accessLevelEnum() == AccessLevel.SUPER_ADMIN ||
                m.accessLevelEnum() == AccessLevel.ADMIN ||
                listOf(
                    AppPermission.ASSIGN_WORK,
                    AppPermission.APPROVE_DELIVERABLES,
                    AppPermission.VIEW_PAYMENTS,
                    AppPermission.RECORD_PAYMENTS
                ).any { PermissionEngine.hasPermission(m, it, map) }
            if (ok) m else null
        }
    }.distinctUntilChanged()

    private val _projectsFlow = _projectGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else projectRepo.observeProjects().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _paymentGate = combine(_approvedGate, _membersFlow) { m, all ->
        if (m == null) null
        else {
            val map = all.associateBy { it.uid }
            if (PermissionEngine.hasPermission(m, AppPermission.VIEW_PAYMENTS, map) ||
                PermissionEngine.hasPermission(m, AppPermission.RECORD_PAYMENTS, map)
            ) m else null
        }
    }.distinctUntilChanged()

    private val _paymentsFlow = _paymentGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else workRepo.observePayments().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _deliverablesFlow = combine(_approvedGate, _projectGate) { approved, manager ->
        approved to (manager != null)
    }.distinctUntilChanged().flatMapLatest { (approved, isManager) ->
        when {
            approved == null -> flowOf(SyncedData(emptyList(), false))
            isManager -> workRepo.observeManagerDeliverables().catch { emit(SyncedData(emptyList(), false)) }
            else -> combine(
                workRepo.observeStaffDeliverables(approved.uid).catch { emit(SyncedData(emptyList(), false)) },
                workRepo.observeDeptQueueDeliverables(approved.departmentId).catch { emit(emptyList()) }
            ) { assigned, queue ->
                SyncedData((assigned.data + queue).distinctBy { it.id }, assigned.hasPendingWrites)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncedData(emptyList(), false))

    private val _chatChannelsFlow = _approvedGate.flatMapLatest { approved ->
        if (approved == null) flowOf(emptyList())
        else chatRepo.observeChannels(
            myUid = approved.uid,
            isSuperAdmin = approved.accessLevelEnum() == AccessLevel.SUPER_ADMIN
        ).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _chatStatusesFlow = _approvedGate.flatMapLatest { approved ->
        if (approved == null) flowOf(emptyList())
        else chatRepo.observeStatuses().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _notificationsFlow = _approvedGate.flatMapLatest { approved ->
        if (approved == null) flowOf(emptyList())
        else coreRepo.observeNotifications().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _staffPlanItemsFlow = _approvedGate.flatMapLatest { approved ->
        if (approved == null) flowOf(emptyList())
        else coreRepo.observeMyPlanItems(approved.uid).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _timeEntriesFlow = combine(_approvedGate, _projectGate) { approved, manager ->
        approved to (manager != null)
    }.distinctUntilChanged().flatMapLatest { (approved, isManager) ->
        if (approved == null) flowOf(emptyList())
        else workRepo.observeTimeEntries(approved.uid, isManager).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _portalLinksFlow = _commercialGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else workRepo.observePortalLinks().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _nudgeLogsFlow = _commercialGate.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else workRepo.observeNudgeLogs().catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeCallSessionsFlow = _approvedGate.flatMapLatest { approved ->
        if (approved == null) flowOf(emptyList())
        else chatRepo.observeActiveCallSessions(approved.uid).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<AgencyUiState> = combine(
        sessionStatus,
        _authInProgress,
        _userFlow,
        _companyFlow,
        _myMemberFlow,
        _myPrivateFlow,
        _membersFlow,
        _allPrivateFlow,
        _invitationsFlow,
        _departmentsFlow,
        _jobRolesFlow,
        _catalogueFlow,
        _routingRulesFlow,
        _clientsFlow,
        _requestsFlow,
        _quotationsFlow,
        _projectsFlow,
        _deliverablesFlow,
        _paymentsFlow,
        _activityFlow,
        _chatChannelsFlow,
        _notificationsFlow,
        _staffPlanItemsFlow,
        _soundEnabled,
        _activeTimerDeliverableId,
        _timerSeconds,
        _focusPresetMinutes,
        _focusSecondsRemaining,
        _isFocusTimerRunning,
        _syncFeedback,
        _statusMessage,
        _errorMessage,
        _autoBootstrapInProgress,
        _chatStatusesFlow,
        _timeEntriesFlow,
        _portalLinksFlow,
        _nudgeLogsFlow,
        _activeCallSessionsFlow,
        _verifiedRoleFlow,
        _backendDiagnostics
    ) { values ->
        val authStatus = values[0] as AuthSessionStatus
        val delivSynced = values[17] as SyncedData<List<Deliverable>>
        val compPair = values[3] as Pair<CompanyProfile?, Boolean>
        val memPair = values[4] as Pair<Member?, Boolean>
        AgencyUiState(
            authSessionStatus = authStatus,
            authInitialized = authStatus !is AuthSessionStatus.Initializing,
            authInProgress = values[1] as Boolean,
            firebaseUser = values[2] as FirebaseUser?,
            company = compPair.first,
            companyLoaded = compPair.second,
            myMember = memPair.first,
            memberLoaded = memPair.second,
            myPrivateInfo = values[5] as MemberPrivateInfo?,
            members = values[6] as List<Member>,
            allPrivateInfo = values[7] as List<MemberPrivateInfo>,
            invitations = values[8] as List<Invitation>,
            departments = values[9] as List<Department>,
            jobRoles = values[10] as List<JobRole>,
            catalogue = (values[11] as List<CatalogueItem>).sortedBy { it.sortOrder },
            routingRules = (values[12] as List<RoutingRule>).sortedByDescending { it.priority },
            clients = (values[13] as List<Client>).sortedBy { it.companyName },
            requests = (values[14] as List<ProjectRequest>).sortedByDescending { it.createdAt?.seconds ?: 0L },
            quotations = (values[15] as List<Quotation>).sortedByDescending { it.issuedDateMillis },
            projects = (values[16] as List<Project>).sortedByDescending { it.createdAt?.seconds ?: 0L },
            deliverables = delivSynced.data,
            deliverablesPendingSync = delivSynced.hasPendingWrites,
            payments = (values[18] as List<PaymentRecord>).sortedByDescending { it.paymentDateMillis },
            activityLog = values[19] as List<ActivityLog>,
            chatChannels = values[20] as List<ChatChannel>,
            chatStatuses = values[33] as List<ChatStatus>,
            notifications = values[21] as List<WorkspaceNotification>,
            staffPlanItems = values[22] as List<StaffPlanItem>,
            timeEntries = values[34] as List<TimeEntry>,
            portalLinks = values[35] as List<ClientPortalLink>,
            nudgeLogs = values[36] as List<PaymentNudgeLog>,
            activeCallSessions = values[37] as List<ChatCallSession>,
            soundEnabled = values[23] as Boolean,
            activeTimerDeliverableId = values[24] as String?,
            timerSecondsByDeliverable = values[25] as Map<String, Long>,
            focusPresetMinutes = values[26] as Int,
            focusSecondsRemaining = values[27] as Long,
            isFocusTimerRunning = values[28] as Boolean,
            syncFeedback = values[29] as SyncFeedback,
            statusMessage = values[30] as String?,
            errorMessage = values[31] as String?,
            autoBootstrapInProgress = values[32] as Boolean,
            verifiedUserRole = values[38] as VerifiedUserRole,
            backendDiagnostics = values[39] as BackendDiagnosticReport
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgencyUiState())

    init {
        AppHapticsAndNotifier.ensureNotificationChannel(application)
        viewModelScope.launch {
            _approvedGate.collectLatest { me ->
                if (me != null) {
                    RtdbPresenceManager.bindUserSession(getApplication(), coreRepo.companyId, me.uid)
                    val pubKey = ChatE2eeCrypto.getOrCreatePublicKeyBase64(getApplication(), me.uid)
                    if (pubKey.isNotBlank() && pubKey != me.e2eePublicKey) {
                        runCatching { coreRepo.updateMyE2eePublicKey(me.uid, pubKey) }
                    }
                    while (true) {
                        val avail = if (_isFocusTimerRunning.value) "BUSY" else "ACTIVE"
                        runCatching { coreRepo.updateMyPresenceHeartbeat(me.uid, avail, online = true) }
                        delay(45_000L)
                    }
                } else {
                    RtdbPresenceManager.unbindUserSession(getApplication(), coreRepo.companyId)
                }
            }
        }
        viewModelScope.launch {
            combine(_approvedGate, _notificationsFlow, _soundEnabled) { me, list, sound ->
                Triple(me, list, sound)
            }.collect { (me, list, sound) ->
                if (me == null) {
                    notificationsHydratedForUid = null
                    seenNotificationIds.clear()
                    return@collect
                }
                val relevant = list.filter { it.appliesTo(me) }
                if (notificationsHydratedForUid != me.uid) {
                    notificationsHydratedForUid = me.uid
                    seenNotificationIds.clear()
                    seenNotificationIds.addAll(relevant.map { it.id })
                    return@collect
                }
                val newOnes = relevant.filter { it.id !in seenNotificationIds }
                seenNotificationIds.addAll(newOnes.map { it.id })
                val incomingAlert = newOnes.firstOrNull {
                    it.actorUid != me.uid && it.isUnreadFor(me.uid)
                }
                if (incomingAlert != null) {
                    AppHapticsAndNotifier.postSystemNotification(
                        context = getApplication(),
                        title = incomingAlert.title,
                        body = incomingAlert.body,
                        notifId = incomingAlert.id.hashCode(),
                        soundEnabled = sound
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(_authenticatedReadyUser, _myMemberFlow) { user, (member, memberLoaded) ->
                Triple(user, member, memberLoaded)
            }.collect { (user, member, memberLoaded) ->
                if (user == null) {
                    _lastAutoBootstrapUid = null
                    _autoBootstrapInProgress.value = false
                    return@collect
                }
                if (_lastAutoBootstrapUid != user.uid) {
                    _errorMessage.value = null
                }
                val isBootstrap = CatalogueSeedData.isBootstrapEmail(user.email)
                val needsSuperAdmin = isBootstrap &&
                    memberLoaded &&
                    (member == null || !PermissionEngine.isActiveApproved(member) || member.accessLevelEnum() != AccessLevel.SUPER_ADMIN)

                if (needsSuperAdmin && _lastAutoBootstrapUid != user.uid && !_autoBootstrapInProgress.value) {
                    _lastAutoBootstrapUid = user.uid
                    _autoBootstrapInProgress.value = true
                    _errorMessage.value = null
                    try {
                        val resolvedName = member?.fullName?.takeIf { it.isNotBlank() }
                            ?: user.displayName
                            ?: "X.MAX Director"
                        withTimeout(15_000L) {
                            coreRepo.bootstrapXMaxWorkspace(user, resolvedName, age = null)
                        }
                        _statusMessage.value = "Signed in as X.MAX Super Admin."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: FirebaseFirestoreException) {
                        handleFirestoreError(e, OperationType.WRITE, "bootstrap")
                        _errorMessage.value = e.localizedMessage ?: "Failed to activate Super Admin access."
                    } catch (e: Exception) {
                        handleFirestoreError(e, OperationType.WRITE, "bootstrap")
                        _errorMessage.value = e.localizedMessage ?: "Failed to activate Super Admin access."
                    } finally {
                        _autoBootstrapInProgress.value = false
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(_approvedGate, _membersFlow, _catalogueFlow, _routingRulesFlow) { me, members, catList, ruleList ->
                listOf(me, members, catList, ruleList)
            }.collect { tuple ->
                @Suppress("UNCHECKED_CAST")
                val me = tuple[0] as? Member
                @Suppress("UNCHECKED_CAST")
                val members = tuple[1] as List<Member>
                @Suppress("UNCHECKED_CAST")
                val catList = tuple[2] as List<CatalogueItem>
                @Suppress("UNCHECKED_CAST")
                val ruleList = tuple[3] as List<RoutingRule>
                if (me == null) {
                    syncedCatalogueOnceForUid = null
                    return@collect
                }
                if (syncedCatalogueOnceForUid == me.uid) return@collect
                val membersMap = members.associateBy { it.uid }
                val canEditCat = PermissionEngine.hasPermission(me, AppPermission.EDIT_CATALOGUE_PRICES, membersMap)
                if (!canEditCat) {
                    syncedCatalogueOnceForUid = me.uid
                    return@collect
                }
                if (catList.isEmpty()) return@collect
                val catOutdated = !catList.any { it.id == "srv_01" && it.name == "Social media post" && it.pricePaise == 50000L } ||
                    !catList.any { it.id == "pkg_04" && it.name == "BUSINESS LAUNCH" && it.pricePaise == 5500000L } ||
                    !catList.any { it.id == "add_08" && it.name == "Additional website page" && it.pricePaise == 200000L } ||
                    catList.size < 56
                val isSuper = me.accessLevelEnum() == AccessLevel.SUPER_ADMIN
                val rulesOutdated = isSuper && (
                    !ruleList.any { it.serviceCategories.contains("Video Production & Editing") } ||
                        !ruleList.any { it.serviceCategories.contains("Website Development") }
                    )
                if (catOutdated || rulesOutdated) {
                    syncedCatalogueOnceForUid = me.uid
                    runCatching {
                        coreRepo.syncOfficialCatalogueAndRouting(
                            syncRouting = isSuper && rulesOutdated,
                            syncCompanyTerms = isSuper,
                            actor = me
                        )
                    }.onSuccess { count ->
                        _statusMessage.value = "Updated $count services & packages to 2026 PDF rates."
                    }
                } else {
                    syncedCatalogueOnceForUid = me.uid
                }
            }
        }
        viewModelScope.launch {
            combine(
                _approvedGate,
                _quotationsFlow,
                _projectsFlow,
                _deliverablesFlow,
                _soundEnabled
            ) { me, quotes, projects, delivSynced, sound ->
                listOf(me, quotes, projects, delivSynced.data, sound)
            }.collect { tuple ->
                @Suppress("UNCHECKED_CAST")
                val me = tuple[0] as? Member ?: run {
                    paymentWorkerScheduledForUid = null
                    return@collect
                }
                if (!PaymentReminderAutomator.canReceivePaymentReminders(me)) return@collect
                if (paymentWorkerScheduledForUid != me.uid) {
                    paymentWorkerScheduledForUid = me.uid
                    PaymentReminderWorker.schedulePeriodic(getApplication())
                }
                @Suppress("UNCHECKED_CAST")
                val quotes = tuple[1] as List<Quotation>
                @Suppress("UNCHECKED_CAST")
                val projects = tuple[2] as List<Project>
                @Suppress("UNCHECKED_CAST")
                val deliverables = tuple[3] as List<Deliverable>
                val sound = tuple[4] as Boolean
                if (quotes.isEmpty() && projects.isEmpty()) return@collect
                runCatching {
                    PaymentReminderAutomator.evaluateAndDispatch(
                        context = getApplication(),
                        coreRepo = coreRepo,
                        actor = me,
                        quotations = quotes,
                        projects = projects,
                        deliverables = deliverables,
                        soundEnabled = sound
                    )
                }
            }
        }
    }

    fun retryProfileSync() {
        _errorMessage.value = null
        _lastAutoBootstrapUid = null
        _autoBootstrapInProgress.value = false
        viewModelScope.launch {
            firestoreInitService.retryInitialization(_userFlow.value)
        }
        _profileRetryTrigger.value += 1
    }

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
        if (_syncFeedback.value == SyncFeedback.ERROR || _syncFeedback.value == SyncFeedback.SAVED) {
            _syncFeedback.value = SyncFeedback.IDLE
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            _authInProgress.value = true
            _errorMessage.value = null
            try {
                when (val res = authRepository.signInWithGoogle(activityContext)) {
                    is GoogleSignInResult.Success -> {
                        _errorMessage.value = null
                        _statusMessage.value = "Signed in as ${res.user.displayName ?: res.user.email ?: "member"}"
                    }
                    GoogleSignInResult.Cancelled -> Unit
                    is GoogleSignInResult.Failure -> _errorMessage.value = res.message
                }
            } finally {
                _authInProgress.value = false
            }
        }
    }

    fun startPhoneVerification(context: Context, rawPhone: String) {
        val cleaned = rawPhone.trim().replace(" ", "").replace("-", "")
        if (!cleaned.startsWith("+") || cleaned.length < 8) {
            _phoneSession.value = _phoneSession.value.copy(
                errorMessage = "Include country code in E.164 format (e.g. +919876543210)."
            )
            return
        }
        val activity = context.findActivity()
        if (activity == null) {
            _phoneSession.value = _phoneSession.value.copy(
                errorMessage = "Unable to launch verification from this context."
            )
            return
        }
        _phoneSession.value = _phoneSession.value.copy(
            phoneNumber = cleaned,
            inProgress = true,
            errorMessage = null
        )
        authRepository.startPhoneVerification(
            activity = activity,
            phoneNumber = cleaned,
            resendToken = _phoneSession.value.resendToken,
            onCodeSent = { verificationId, token ->
                _phoneSession.value = PhoneVerificationSession(
                    phoneNumber = cleaned,
                    verificationId = verificationId,
                    resendToken = token,
                    inProgress = false,
                    errorMessage = null
                )
            },
            onAutoVerified = { user ->
                _phoneSession.value = PhoneVerificationSession()
                _statusMessage.value = "Signed in as ${user.phoneNumber ?: "phone user"}"
            },
            onFailure = { message ->
                _phoneSession.value = _phoneSession.value.copy(
                    inProgress = false,
                    errorMessage = message
                )
            }
        )
    }

    fun verifyPhoneOtp(code: String) {
        val verificationId = _phoneSession.value.verificationId ?: return
        if (code.trim().length < 4) {
            _phoneSession.value = _phoneSession.value.copy(errorMessage = "Enter the SMS verification code.")
            return
        }
        viewModelScope.launch {
            _phoneSession.value = _phoneSession.value.copy(inProgress = true, errorMessage = null)
            when (val res = authRepository.signInWithPhoneOtp(verificationId, code)) {
                is GoogleSignInResult.Success -> {
                    _phoneSession.value = PhoneVerificationSession()
                    _statusMessage.value = "Signed in as ${res.user.phoneNumber ?: res.user.email ?: "member"}"
                }
                GoogleSignInResult.Cancelled -> {
                    _phoneSession.value = _phoneSession.value.copy(inProgress = false)
                }
                is GoogleSignInResult.Failure -> {
                    _phoneSession.value = _phoneSession.value.copy(inProgress = false, errorMessage = res.message)
                }
            }
        }
    }

    fun resetPhoneVerification() {
        _phoneSession.value = PhoneVerificationSession()
    }

    fun signOut(context: Context) {
        viewModelScope.launch {
            val curUid = uiState.value.myMember?.uid ?: _userFlow.value?.uid
            if (!curUid.isNullOrBlank()) {
                runCatching {
                    withTimeout(2_000L) {
                        coreRepo.updateMyPresenceHeartbeat(curUid, "AWAY", online = false)
                    }
                }
            }
            _phoneSession.value = PhoneVerificationSession()
            _errorMessage.value = null
            _statusMessage.value = null
            AppAppearanceStore.setActiveProfile(context, null)
            ChatSettingsStore.setActiveProfile(context, null)
            RtdbPresenceManager.unbindUserSession(getApplication(), coreRepo.companyId)
            firestoreInitService.onSignedOut()
            authRepository.signOut(context)
        }
    }

    fun bootstrapXMax(fullName: String, age: Int?) = runWrite("bootstrap") {
        val user = uiState.value.firebaseUser ?: error("Not signed in")
        coreRepo.bootstrapXMaxWorkspace(user, fullName, age)
        _statusMessage.value = "X.MAX workspace provisioned with 40 services, 5 packages & 11 add-ons."
    }

    fun submitOnboarding(
        companyNameInput: String,
        fullName: String,
        age: Int,
        requestedDepartment: String,
        requestedJobRole: String,
        phone: String
    ) = runWrite("onboarding") {
        val user = uiState.value.firebaseUser ?: error("Not signed in")
        coreRepo.submitOnboardingOrAcceptInvite(user, companyNameInput, fullName, age, requestedDepartment, requestedJobRole, phone)
        _statusMessage.value = "Profile submitted. Awaiting approval or invitation check."
    }

    fun updateCompanyProfile(profile: CompanyProfile) = runWrite("company") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.updateCompanyProfile(profile, actor)
        _statusMessage.value = "Company settings updated."
    }

    fun inviteMember(
        email: String,
        accessLevel: AccessLevel,
        department: Department,
        jobRole: JobRole,
        scopeType: ScopeType,
        scopeDeptIds: List<String>,
        permissions: Map<String, PermissionGrant>
    ) = runWrite("invitations") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.createInvitation(email, accessLevel, department, jobRole, scopeType, scopeDeptIds, permissions, actor)
        _statusMessage.value = "Invitation created for $email"
    }

    fun configureMember(
        target: Member,
        status: MembershipStatus,
        accessLevel: AccessLevel,
        dept: Department,
        role: JobRole,
        scopeType: ScopeType,
        scopeDeptIds: List<String>,
        permissions: Map<String, PermissionGrant>
    ) = runWrite("members/${target.uid}") {
        val actor = uiState.value.myMember ?: error("No active member")
        val staff = uiState.value.members.filter { it.accessLevelEnum() == AccessLevel.STAFF }
        coreRepo.approveAndConfigureMember(
            targetMember = target,
            status = status,
            accessLevel = accessLevel,
            departmentId = dept.id,
            departmentName = dept.name,
            jobRoleId = role.id,
            jobRoleName = role.name,
            scopeType = scopeType,
            scopeDepartmentIds = scopeDeptIds,
            permissions = permissions,
            allStaff = staff,
            actor = actor
        )
        _statusMessage.value = "Saved access & permissions for ${target.fullName}"
    }

    fun updateStaffDelegation(staff: Member, grants: Map<String, DelegatedGrant>) = runWrite("members/${staff.uid}") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.updateStaffDelegations(staff, grants, actor)
        _statusMessage.value = "Updated staff capabilities for ${staff.fullName}"
    }

    fun saveDepartment(id: String?, name: String, description: String) = runWrite("departments") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.saveDepartment(id, name, description, actor)
        _statusMessage.value = "Department saved."
    }

    fun saveJobRole(id: String?, name: String, deptId: String) = runWrite("job_roles") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.saveJobRole(id, name, deptId, actor)
        _statusMessage.value = "Job role saved."
    }

    fun retireDepartment(retiring: Department, replacementDept: Department, replacementRole: JobRole) = runWrite("departments/${retiring.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        val state = uiState.value
        coreRepo.retireDepartmentAndReassign(retiring, replacementDept, replacementRole, state.members, state.jobRoles, state.routingRules, state.deliverables, actor)
        _statusMessage.value = "${retiring.name} retired and reassigned to ${replacementDept.name}."
    }

    fun retireJobRole(retiring: JobRole, replacementDept: Department, replacementRole: JobRole) = runWrite("job_roles/${retiring.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        val state = uiState.value
        coreRepo.retireJobRoleAndReassign(retiring, replacementDept, replacementRole, state.members, state.routingRules, state.deliverables, actor)
        _statusMessage.value = "${retiring.name} retired and reassigned to ${replacementRole.name}."
    }

    fun saveCatalogueItem(item: CatalogueItem) = runWrite("catalogue") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.saveCatalogueItem(item, actor)
        _statusMessage.value = "Saved ${item.name}"
    }

    fun setCatalogueArchived(item: CatalogueItem, archived: Boolean) = runWrite("catalogue/${item.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.setCatalogueArchived(item, archived, actor)
        _statusMessage.value = if (archived) "${item.name} archived." else "${item.name} restored."
    }

    fun syncOfficialCatalogueNow() = runWrite("catalogue/sync") {
        val actor = uiState.value.myMember ?: error("No active member")
        if (!uiState.value.hasPerm(AppPermission.EDIT_CATALOGUE_PRICES)) {
            error("Only managers with Catalogue Edit access can sync pricing.")
        }
        val isSuper = uiState.value.isSuperAdmin
        val updatedCount = coreRepo.syncOfficialCatalogueAndRouting(
            syncRouting = isSuper,
            syncCompanyTerms = isSuper,
            actor = actor
        )
        _statusMessage.value = "Synced $updatedCount official X.MAX 2026 PDF items."
    }

    fun saveRoutingRule(rule: RoutingRule) = runWrite("routing_rules") {
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.saveRoutingRule(rule, actor)
        _statusMessage.value = "Routing rule saved."
    }

    fun saveClient(client: Client, onSaved: (Client) -> Unit = {}) = runWrite("clients") {
        val actor = uiState.value.myMember ?: error("No active member")
        val saved = clientRepo.saveClient(client, actor).getOrThrow()
        _statusMessage.value = "Client ${saved.companyName} saved."
        onSaved(saved)
    }

    fun createRequest(
        client: Client,
        title: String,
        brief: String,
        services: List<String>,
        targetDateMillis: Long,
        manager: Member,
        onCreated: (ProjectRequest) -> Unit = {}
    ) = runWrite("requests") {
        val actor = uiState.value.myMember ?: error("No active member")
        val req = projectRepo.createRequest(client, title, brief, services, targetDateMillis, manager, actor).getOrThrow()
        _statusMessage.value = "Request \"${req.projectTitle}\" captured."
        onCreated(req)
    }

    fun saveOrIssueQuote(
        draft: Quotation,
        issueNow: Boolean,
        onResult: (Quotation) -> Unit = {}
    ) = runWrite("quotations") {
        val state = uiState.value
        val actor = state.myMember ?: error("No active member")
        val company = state.company ?: CatalogueSeedData.defaultCompanyProfile(actor.uid)
        val saved = quotationRepo.saveOrIssueQuotation(draft, company, issueNow, actor).getOrThrow()
        _statusMessage.value = if (issueNow) "Issued ${saved.quoteNumber}" else "Saved quotation draft"
        onResult(saved)
    }

    fun createQuoteRevision(previous: Quotation, onResult: (Quotation) -> Unit = {}) = runWrite("quotations") {
        val actor = uiState.value.myMember ?: error("No active member")
        val rev = quotationRepo.createQuotationRevision(previous, actor).getOrThrow()
        _statusMessage.value = "Created revision v${rev.revision}"
        onResult(rev)
    }

    fun generateAndSavePdf(
        context: Context,
        quote: Quotation,
        shareAfterSave: Boolean = false,
        onReady: (File) -> Unit = {}
    ) = runWrite("quotations/${quote.id}") {
        val gen = withContext(Dispatchers.IO) {
            QuotationPdfGenerator.generateAndSave(context, quote)
        }
        quotationRepo.savePdfMetadata(quote.id, gen.file.absolutePath, gen.sha256).getOrThrow()
        _statusMessage.value = "PDF saved: ${gen.file.name}"
        onReady(gen.file)
        if (shareAfterSave) {
            QuotationPdfGenerator.launchShareSheet(context, gen.file, quote)
        }
    }

    fun markQuoteSent(quote: Quotation, onDone: () -> Unit = {}) = runWrite("quotations/${quote.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        quotationRepo.markQuotationSent(quote, actor).getOrThrow()
        _statusMessage.value = "${quote.quoteNumber} marked Sent to Client."
        onDone()
    }

    fun markQuoteClientApproved(quote: Quotation, note: String, onDone: () -> Unit = {}) = runWrite("quotations/${quote.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        quotationRepo.markQuotationClientApproved(quote, note, actor).getOrThrow()
        _statusMessage.value = "${quote.quoteNumber} marked Client Approved."
        onDone()
    }

    fun acceptRequest(request: ProjectRequest, quote: Quotation, drafts: List<DeliverableDraft>, onAccepted: (String) -> Unit = {}) = runWrite("projects") {
        val state = uiState.value
        val actor = state.myMember ?: error("No active member")
        val company = state.company ?: CatalogueSeedData.defaultCompanyProfile(actor.uid)
        val projectId = projectRepo.acceptRequestAndCreateProject(request, quote, drafts, company, actor).getOrThrow()
        _statusMessage.value = "Project accepted & ${drafts.size} deliverables distributed."
        onAccepted(projectId)
    }

    fun declineRequest(request: ProjectRequest, note: String) = runWrite("requests/${request.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        projectRepo.declineRequest(request, note, actor).getOrThrow()
        _statusMessage.value = "Request declined."
    }

    fun toggleDeleteProject(project: Project, deleted: Boolean) = runWrite("projects/${project.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        projectRepo.setProjectDeleted(project, deleted, actor).getOrThrow()
        _statusMessage.value = if (deleted) "Project soft-deleted." else "Project restored."
    }

    fun assignDeliverable(deliverable: Deliverable, assignee: Member, dueDateMillis: Long) = runWrite("deliverables/${deliverable.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        workRepo.assignDeliverable(deliverable, assignee, dueDateMillis, actor)
        _statusMessage.value = "Assigned to ${assignee.fullName}"
    }

    fun submitStaffProgress(
        deliverable: Deliverable,
        status: DeliverableStatus,
        checklist: List<ChecklistItem>,
        note: String,
        previewUrl: String,
        blockedReason: String,
        helpNeeded: String
    ) = runWrite("deliverables/${deliverable.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        workRepo.submitStaffProgress(deliverable, status, checklist, note, previewUrl, blockedReason, helpNeeded, actor)
        _statusMessage.value = "Progress saved (${status.label})"
    }

    fun reviewDeliverable(deliverable: Deliverable, action: UpdateActionType, feedback: String) = runWrite("deliverables/${deliverable.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        val projDeliverables = uiState.value.deliverables.filter { it.projectId == deliverable.projectId }
        workRepo.reviewDeliverable(deliverable, action, feedback, projDeliverables, actor)
        _statusMessage.value = "${action.label} recorded."
    }

    fun recordPayment(project: Project, amountPaise: Long, dateMillis: Long, method: PaymentMethod, ref: String, note: String) = runWrite("payments") {
        val actor = uiState.value.myMember ?: error("No active member")
        workRepo.recordPayment(project, amountPaise, dateMillis, method, ref, note, actor)
        _statusMessage.value = "Payment recorded."
    }

    fun observeDeliverableUpdates(deliverableId: String): Flow<SyncedData<List<DeliverableUpdate>>> =
        workRepo.observeDeliverableUpdates(deliverableId).catch { emit(SyncedData(emptyList(), false)) }

    fun observeChatMessages(channelId: String): Flow<SyncedData<List<ChatMessage>>> =
        chatRepo.observeMessages(channelId).catch { emit(SyncedData(emptyList(), false)) }

    fun ensureDefaultGeneralChannel() {
        val actor = uiState.value.myMember ?: return
        if (uiState.value.chatChannels.any { it.id == ChatRepository.GENERAL_CHANNEL_ID }) return
        runWrite("chat_channels/${ChatRepository.GENERAL_CHANNEL_ID}") {
            chatRepo.ensureDefaultGeneralChannel(actor)
        }
    }

    fun createOrOpenChatChannel(
        title: String,
        topic: String,
        channelType: ChatChannelType,
        project: Project? = null,
        onReady: (ChatChannel) -> Unit = {},
        onError: (String) -> Unit = {}
    ) = runWrite("chat_channels", onError = onError) {
        val actor = uiState.value.myMember ?: error("No active member")
        val ch = chatRepo.createOrGetChannel(title, topic, channelType, project, actor)
        _statusMessage.value = "Opened #${ch.title}"
        onReady(ch)
    }

    fun sendChatMessage(
        channelId: String,
        body: String,
        parentMessage: ChatMessage?,
        attachment: StagedChatAttachment?,
        onSent: () -> Unit = {}
    ) = runWrite("chat_channels/$channelId/messages") {
        val actor = uiState.value.myMember ?: error("No active member")
        chatRepo.sendMessage(channelId, body, parentMessage, attachment, actor)
        if (attachment != null) {
            _statusMessage.value = "Shared ${attachment.fileName}"
        }
        onSent()
    }

    fun updateMyProfilePhoto(context: Context, uri: Uri, onDone: () -> Unit = {}) = runWrite("members/photo") {
        val uid = uiState.value.firebaseUser?.uid ?: uiState.value.myMember?.uid ?: error("Not signed in")
        val encoded = ChatFileManager.prepareProfilePhotoBase64(context, uri).getOrThrow()
        withTimeout(12_000L) {
            coreRepo.updateMyProfilePhoto(uid, encoded)
        }
        _statusMessage.value = "Profile photo updated."
        onDone()
    }

    fun updateMyCroppedProfilePhoto(
        bitmap: Bitmap,
        zoom: Float,
        offsetXFrac: Float,
        offsetYFrac: Float,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) = runWrite("members/photo") {
        val uid = uiState.value.firebaseUser?.uid ?: uiState.value.myMember?.uid ?: error("Not signed in")
        try {
            val encoded = ChatFileManager.compressCroppedProfilePhotoBase64(bitmap, zoom, offsetXFrac, offsetYFrac).getOrThrow()
            withTimeout(12_000L) {
                coreRepo.updateMyProfilePhoto(uid, encoded)
            }
            _statusMessage.value = "Profile photo updated."
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Photo upload timed out or failed. Please try again.")
            throw e
        }
    }

    fun removeMyProfilePhoto(
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) = runWrite("members/photo") {
        val uid = uiState.value.firebaseUser?.uid ?: uiState.value.myMember?.uid ?: error("Not signed in")
        try {
            withTimeout(12_000L) {
                coreRepo.updateMyProfilePhoto(uid, "")
            }
            _statusMessage.value = "Profile photo removed."
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Failed to remove profile photo.")
            throw e
        }
    }

    fun startDeliverableWork(deliverable: Deliverable) = runWrite("deliverables/${deliverable.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        workRepo.startDeliverableWork(deliverable, actor)
        _statusMessage.value = "Started ${deliverable.title}"
    }

    fun claimDepartmentDeliverable(deliverable: Deliverable) = runWrite("deliverables/${deliverable.id}") {
        val actor = uiState.value.myMember ?: error("No active member")
        workRepo.claimDepartmentDeliverable(deliverable, actor)
        _statusMessage.value = "Claimed ${deliverable.title}"
    }

    fun toggleDeliverableTimer(deliverableId: String) {
        val currentlyRunning = _activeTimerDeliverableId.value
        if (currentlyRunning == deliverableId) {
            timerJob?.cancel()
            timerJob = null
            _activeTimerDeliverableId.value = null
            return
        }
        timerJob?.cancel()
        _activeTimerDeliverableId.value = deliverableId
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val updated = _timerSeconds.value.toMutableMap()
                val next = (updated[deliverableId] ?: 0L) + 1L
                updated[deliverableId] = next
                _timerSeconds.value = updated
                AppHapticsAndNotifier.saveTimerSeconds(getApplication(), deliverableId, next)
            }
        }
    }

    fun resetDeliverableTimer(deliverableId: String) {
        if (_activeTimerDeliverableId.value == deliverableId) {
            timerJob?.cancel()
            timerJob = null
            _activeTimerDeliverableId.value = null
        }
        val updated = _timerSeconds.value.toMutableMap().apply { remove(deliverableId) }
        _timerSeconds.value = updated
        AppHapticsAndNotifier.saveTimerSeconds(getApplication(), deliverableId, 0L)
    }

    fun openDirectChat(
        otherMember: Member,
        onReady: (ChatChannel) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val actor = uiState.value.myMember ?: run {
            val err = "No active member"
            _errorMessage.value = err
            onError(err)
            return
        }
        val existing = uiState.value.chatChannels.firstOrNull { ch ->
            ch.channelTypeEnum() == ChatChannelType.DIRECT &&
                (ch.id == ChatRepository.directChannelId(actor.uid, otherMember.uid) ||
                 (ch.participantUids.contains(otherMember.uid) && ch.participantUids.contains(actor.uid)))
        }
        if (existing != null) {
            _statusMessage.value = "Direct chat: ${otherMember.fullName}"
            onReady(existing)
            return
        }
        runWrite("chat_channels/dm", onError = onError) {
            val ch = chatRepo.createOrGetDirectChannel(otherMember, actor)
            _statusMessage.value = "Direct chat: ${otherMember.fullName}"
            onReady(ch)
        }
    }

    fun openDepartmentChat(
        dept: Department,
        onReady: (ChatChannel) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val actor = uiState.value.myMember ?: run {
            val err = "No active member"
            _errorMessage.value = err
            onError(err)
            return
        }
        val existing = uiState.value.chatChannels.firstOrNull { ch ->
            (ch.channelTypeEnum() == ChatChannelType.GROUP || ch.channelTypeEnum() == ChatChannelType.DEPARTMENT) &&
                (ch.departmentId == dept.id || ch.id == "ch_grp_dept_${dept.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}")
        }
        if (existing != null) {
            _statusMessage.value = "Opened ${dept.name} group"
            onReady(existing)
            return
        }
        runWrite("chat_channels/dept", onError = onError) {
            val ch = chatRepo.createOrGetDepartmentChannel(dept, actor)
            _statusMessage.value = "Opened ${dept.name} chat"
            onReady(ch)
        }
    }

    fun markNotificationRead(notification: WorkspaceNotification) {
        val uid = uiState.value.myMember?.uid ?: return
        if (!notification.isUnreadFor(uid)) return
        viewModelScope.launch {
            runCatching { coreRepo.markNotificationRead(notification, uid) }
        }
    }

    fun markAllNotificationsRead() {
        val state = uiState.value
        val uid = state.myMember?.uid ?: return
        val unread = state.myNotifications.filter { it.isUnreadFor(uid) }
        if (unread.isEmpty()) return
        runWrite("notifications/readAll") {
            coreRepo.markAllNotificationsRead(unread, uid)
            _statusMessage.value = "All notifications marked read."
        }
    }

    fun toggleNotificationSound(enabled: Boolean) {
        _soundEnabled.value = enabled
        AppHapticsAndNotifier.setSoundEnabled(getApplication(), enabled)
        if (enabled) {
            AppHapticsAndNotifier.playNotificationSound(getApplication())
        }
    }

    fun testNotificationSound() {
        AppHapticsAndNotifier.postSystemNotification(
            context = getApplication(),
            title = "X.MAX Alert Sound Active",
            body = "Notifications and sound chimes are enabled on this device.",
            notifId = 9001,
            soundEnabled = true
        )
        _statusMessage.value = "Test notification and sound triggered."
    }

    // --- Super Admin-Only Deletions ---

    fun deleteActivityLog(activityId: String) = runWrite("activity/$activityId") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can delete logs.")
        coreRepo.deleteActivityLog(activityId)
        _statusMessage.value = "Log entry deleted."
    }

    fun clearAllActivityLogs() = runWrite("activity/clear") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can clear logs.")
        val ids = uiState.value.activityLog.map { it.id }
        coreRepo.clearAllActivityLogs(ids)
        _statusMessage.value = "All activity logs cleared."
    }

    fun deleteClient(client: Client) = runWrite("clients/${client.id}") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can delete clients.")
        val actor = uiState.value.myMember ?: error("No active member")
        clientRepo.deleteClient(client.id, client.companyName, actor).getOrThrow()
        _statusMessage.value = "Client ${client.companyName} deleted."
    }

    fun permanentlyDeleteProject(project: Project) = runWrite("projects/${project.id}/delete") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can delete projects.")
        val actor = uiState.value.myMember ?: error("No active member")
        projectRepo.deleteProjectCascade(project, actor).getOrThrow()
        _statusMessage.value = "Project deleted permanently."
    }

    fun cleanupOldDoneProjects() = runWrite("projects/cleanupDone") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can clean up completed projects.")
        val actor = uiState.value.myMember ?: error("No active member")
        val doneProjects = uiState.value.projects.filter {
            it.statusEnum() == ProjectStatus.COMPLETED || it.deleted
        }
        if (doneProjects.isEmpty()) {
            _statusMessage.value = "No completed or archived projects to delete."
            return@runWrite
        }
        val count = coreRepo.deleteOldCompletedProjects(doneProjects, actor)
        _statusMessage.value = "Deleted $count completed or archived project(s)."
    }

    fun deleteChatChannel(channelId: String, onDeleted: () -> Unit = {}) = runWrite("chat_channels/$channelId") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can delete chats.")
        chatRepo.deleteChannelCascade(channelId)
        _statusMessage.value = "Chat channel deleted."
        onDeleted()
    }

    fun deleteChatMessage(channelId: String, messageId: String) = runWrite("chat_channels/$channelId/messages/$messageId") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can delete messages.")
        chatRepo.deleteMessage(channelId, messageId)
        _statusMessage.value = "Message deleted."
    }

    fun deleteNotification(notificationId: String) = runWrite("notifications/$notificationId") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can delete notifications.")
        coreRepo.deleteNotification(notificationId)
    }

    fun deleteQuotation(quotation: Quotation) = runWrite("quotations/${quotation.id}") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can delete quotations.")
        val actor = uiState.value.myMember ?: error("No active member")
        quotationRepo.deleteQuotation(quotation.id, quotation.quoteNumber, actor).getOrThrow()
        _statusMessage.value = "Quotation ${quotation.quoteNumber} deleted."
    }

    fun reportChatMessage(message: String, isError: Boolean = true) {
        if (isError) _errorMessage.value = message else _statusMessage.value = message
    }

    // --- Staff Monthly Plan & Day-to-Day Helpers ---

    fun savePlanItem(item: StaffPlanItem, onDone: () -> Unit = {}) = runWrite("staff_plans") {
        val uid = uiState.value.myMember?.uid ?: error("No active member")
        coreRepo.savePlanItem(item, uid).getOrThrow()
        _statusMessage.value = if (item.id.isBlank()) "Added \"${item.title.trim()}\" to plan." else "Plan item updated."
        onDone()
    }

    fun togglePlanItemCompleted(item: StaffPlanItem) = runWrite("staff_plans/${item.id}") {
        coreRepo.togglePlanItemCompleted(item).getOrThrow()
        _statusMessage.value = if (!item.completed) "Completed: ${item.title}" else "Reopened: ${item.title}"
    }

    fun reschedulePlanItemTomorrow(item: StaffPlanItem) = runWrite("staff_plans/${item.id}") {
        val nextDayMillis = item.dateMillis + 86_400_000L
        coreRepo.reschedulePlanItemDate(item, nextDayMillis).getOrThrow()
        _statusMessage.value = "Rescheduled \"${item.title}\" to next day."
    }

    fun carryOverIncompletePlans(items: List<StaffPlanItem>, targetDateMillis: Long) = runWrite("staff_plans/carryOver") {
        val count = coreRepo.carryOverIncompletePlanItems(items, targetDateMillis).getOrThrow()
        _statusMessage.value = if (count > 0) "Moved $count pending task(s) to today." else "No overdue pending tasks to carry over."
    }

    fun deletePlanItem(planId: String) = runWrite("staff_plans/$planId") {
        coreRepo.deletePlanItem(planId).getOrThrow()
        _statusMessage.value = "Plan item removed."
    }

    fun setFocusPresetMinutes(minutes: Int) {
        focusTimerJob?.cancel()
        focusTimerJob = null
        _isFocusTimerRunning.value = false
        val safeMinutes = minutes.coerceIn(5, 90)
        _focusPresetMinutes.value = safeMinutes
        _focusSecondsRemaining.value = safeMinutes * 60L
    }

    fun resetFocusTimer() {
        focusTimerJob?.cancel()
        focusTimerJob = null
        _isFocusTimerRunning.value = false
        _focusSecondsRemaining.value = _focusPresetMinutes.value * 60L
        RtdbPresenceManager.setAvailability(availability = com.example.chat.realtime.MemberAvailability.ACTIVE)
    }

    fun toggleFocusTimer() {
        if (_isFocusTimerRunning.value) {
            focusTimerJob?.cancel()
            focusTimerJob = null
            _isFocusTimerRunning.value = false
            RtdbPresenceManager.setAvailability(availability = com.example.chat.realtime.MemberAvailability.ACTIVE)
            return
        }
        if (_focusSecondsRemaining.value <= 0L) {
            _focusSecondsRemaining.value = _focusPresetMinutes.value * 60L
        }
        _isFocusTimerRunning.value = true
        RtdbPresenceManager.setAvailability(availability = com.example.chat.realtime.MemberAvailability.BUSY)
        focusTimerJob?.cancel()
        focusTimerJob = viewModelScope.launch {
            while (_focusSecondsRemaining.value > 0L) {
                delay(1000L)
                val next = (_focusSecondsRemaining.value - 1L).coerceAtLeast(0L)
                _focusSecondsRemaining.value = next
                if (next == 0L) {
                    _isFocusTimerRunning.value = false
                    RtdbPresenceManager.setAvailability(availability = com.example.chat.realtime.MemberAvailability.ACTIVE)
                    AppHapticsAndNotifier.triggerCompletionVibration(getApplication())
                    if (_soundEnabled.value) {
                        AppHapticsAndNotifier.playNotificationSound(getApplication())
                    }
                    _statusMessage.value = "${_focusPresetMinutes.value}m Focus sprint complete!"
                    break
                }
            }
        }
    }

    fun refreshCloudStorageSummary(context: Context = getApplication()) {
        if (!uiState.value.isSuperAdmin) return
        if (_isScanningCloudStorage.value) return
        viewModelScope.launch {
            _isScanningCloudStorage.value = true
            try {
                _cloudStorageSummary.value = storageTracker.scanCloudStorage(context, uiState.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Unable to scan cloud storage."
            } finally {
                _isScanningCloudStorage.value = false
            }
        }
    }

    fun deleteCloudStorageItems(
        context: Context = getApplication(),
        items: List<DeletableCloudItem>,
        onComplete: () -> Unit = {}
    ) {
        if (!uiState.value.isSuperAdmin) {
            _errorMessage.value = "Only Super Admin can manage cloud storage."
            return
        }
        if (items.isEmpty() || _isDeletingCloudStorage.value) return
        viewModelScope.launch {
            _isDeletingCloudStorage.value = true
            try {
                storageTracker.deleteCloudItems(items)
                    .onSuccess { (count, freed) ->
                        _statusMessage.value = if (count > 0) {
                            "Deleted $count cloud item${if (count == 1) "" else "s"} (${formatStorageBytes(freed)} freed)"
                        } else {
                            "No eligible cloud items deleted."
                        }
                        _cloudStorageSummary.value = storageTracker.scanCloudStorage(context, uiState.value)
                        onComplete()
                    }
                    .onFailure { err ->
                        _errorMessage.value = err.localizedMessage ?: "Failed to delete cloud storage items."
                    }
            } finally {
                _isDeletingCloudStorage.value = false
            }
        }
    }

    fun deleteExpiredCloudStatuses(context: Context = getApplication()) {
        if (!uiState.value.isSuperAdmin) {
            _errorMessage.value = "Only Super Admin can clean up expired cloud statuses."
            return
        }
        if (_isDeletingCloudStorage.value) return
        viewModelScope.launch {
            _isDeletingCloudStorage.value = true
            try {
                val latest = storageTracker.scanCloudStorage(context, uiState.value)
                storageTracker.deleteExpiredStatuses(latest)
                    .onSuccess { (count, freed) ->
                        _statusMessage.value = if (count > 0) {
                            "Cleared $count expired status${if (count == 1) "" else "es"} (${formatStorageBytes(freed)} freed)"
                        } else {
                            "No expired statuses found in cloud storage."
                        }
                        _cloudStorageSummary.value = storageTracker.scanCloudStorage(context, uiState.value)
                    }
                    .onFailure { err ->
                        _errorMessage.value = err.localizedMessage ?: "Unable to delete expired statuses."
                    }
            } finally {
                _isDeletingCloudStorage.value = false
            }
        }
    }

    fun clearLocalMediaCache(context: Context = getApplication()) {
        viewModelScope.launch {
            ChatFileManager.clearChatMediaCache(context)
            _statusMessage.value = "On-device media cache cleared."
            if (uiState.value.isSuperAdmin) {
                _cloudStorageSummary.value = storageTracker.scanCloudStorage(context, uiState.value)
            }
        }
    }

    // --- Time Tracking, Client Portal Links, Nudge Bot, GST Tax Invoice, Wallpaper & Video Calls ---

    fun logTimeEntry(
        project: Project,
        deliverable: Deliverable?,
        durationMinutes: Int,
        billable: Boolean,
        hourlyRatePaise: Long,
        notes: String,
        workDateMillis: Long = System.currentTimeMillis(),
        onDone: () -> Unit = {}
    ) = runWrite("time_entries") {
        val actor = uiState.value.myMember ?: error("No active member")
        workRepo.logTimeEntry(
            project = project,
            deliverable = deliverable,
            durationMinutes = durationMinutes,
            billable = billable,
            hourlyRatePaise = hourlyRatePaise,
            notes = notes,
            workDateMillis = workDateMillis,
            actor = actor
        )
        _statusMessage.value = "Logged ${durationMinutes}m on ${project.title}."
        onDone()
    }

    fun commitTimerToTimeEntry(
        deliverable: Deliverable,
        billable: Boolean = true,
        hourlyRatePaise: Long = 150000L,
        notes: String = "",
        onDone: () -> Unit = {}
    ) {
        val secs = _timerSeconds.value[deliverable.id] ?: 0L
        val mins = ((secs + 59L) / 60L).toInt().coerceAtLeast(1)
        val project = uiState.value.projects.firstOrNull { it.id == deliverable.projectId }
            ?: Project(
                id = deliverable.projectId,
                title = deliverable.projectTitle,
                clientCompanyName = deliverable.clientCompanyName
            )
        logTimeEntry(
            project = project,
            deliverable = deliverable,
            durationMinutes = mins,
            billable = billable,
            hourlyRatePaise = hourlyRatePaise,
            notes = notes.ifBlank { "Deliverable focus timer (${secs}s)" },
            workDateMillis = System.currentTimeMillis(),
            onDone = {
                resetDeliverableTimer(deliverable.id)
                onDone()
            }
        )
    }

    fun deleteTimeEntry(entryId: String) = runWrite("time_entries/$entryId") {
        workRepo.deleteTimeEntry(entryId)
        _statusMessage.value = "Time entry deleted."
    }

    fun createPortalLink(
        project: Project,
        quotationId: String = "",
        validDays: Int = 30,
        onCreated: (ClientPortalLink) -> Unit = {}
    ) = runWrite("client_portal_links") {
        val actor = uiState.value.myMember ?: error("No active member")
        val link = workRepo.createClientPortalLink(project, quotationId, validDays, actor)
        _statusMessage.value = "Client portal link created."
        onCreated(link)
    }

    fun revokePortalLink(token: String) = runWrite("client_portal_links/$token") {
        val actor = uiState.value.myMember ?: error("No active member")
        workRepo.revokeClientPortalLink(token, actor)
        _statusMessage.value = "Client portal link revoked."
    }

    fun dispatchClientNudge(
        context: Context,
        project: Project,
        client: Client?,
        channel: String,
        portalUrl: String = "",
        onOutcome: (com.example.system.NudgeDispatchOutcome) -> Unit = {}
    ) = runWrite("nudge_logs") {
        val actor = uiState.value.myMember ?: error("No active member")
        val outcome = withContext(Dispatchers.IO) {
            PaymentReminderAutomator.dispatchClientNudge(
                context = context,
                coreRepo = coreRepo,
                commercialRepo = workRepo,
                project = project,
                client = client,
                channel = channel,
                actor = actor,
                portalUrl = portalUrl
            )
        }
        _statusMessage.value = when (outcome.statusLabel) {
            "SENT" -> "Payment nudge dispatched via ${channel.lowercase()}."
            "RATE_LIMITED" -> "Daily rate limit hit — retry shortly."
            else -> "Reminder logged — opening ${if (channel == "EMAIL") "Email" else "WhatsApp"}."
        }
        onOutcome(outcome)
    }

    fun generateAndShareTaxInvoicePdf(
        context: Context,
        project: Project,
        quotation: Quotation?,
        onReady: (File) -> Unit = {}
    ) = runWrite("projects/${project.id}/invoice") {
        val state = uiState.value
        val result = withContext(Dispatchers.IO) {
            QuotationPdfGenerator.generateTaxInvoicePdf(
                context = context,
                project = project,
                quotation = quotation,
                payments = state.payments,
                company = state.company,
                timeEntries = state.timeEntries
            )
        }
        _statusMessage.value = "GST Tax Invoice ready: ${result.file.name}"
        onReady(result.file)
        QuotationPdfGenerator.launchInvoiceShareSheet(context, result.file, project)
    }

    fun setCompanyDefaultWallpaper(presetId: String, customUrl: String = "") = runWrite("company/wallpaper") {
        if (!uiState.value.isSuperAdmin) error("Only Super Admin can set the workspace default wallpaper.")
        val actor = uiState.value.myMember ?: error("No active member")
        coreRepo.setCompanyDefaultWallpaper(presetId, customUrl, actor)
        _statusMessage.value = "Workspace default chat wallpaper updated for everyone."
    }

    fun startVideoCall(
        channel: ChatChannel,
        calleeUids: List<String>,
        onStarted: (ChatCallSession) -> Unit = {}
    ) = runWrite("call_sessions") {
        val actor = uiState.value.myMember ?: error("No active member")
        val session = chatRepo.startCallSession(channel, actor, calleeUids, videoEnabled = true)
        onStarted(session)
    }

    fun acceptVideoCall(callId: String) = runWrite("call_sessions/$callId/accept") {
        val uid = uiState.value.myMember?.uid ?: error("No active member")
        chatRepo.acceptCallSession(callId, uid)
    }

    fun endOrDeclineVideoCall(callId: String, status: String) = runWrite("call_sessions/$callId/end") {
        chatRepo.endOrDeclineCallSession(callId, status)
    }

    fun runBackendDiagnostics() {
        viewModelScope.launch {
            _backendDiagnostics.value = _backendDiagnostics.value.copy(isRunning = true)
            val report = BackendHealthDiagnostics.runFullDiagnostics(getApplication(), coreRepo.companyId)
            _backendDiagnostics.value = report
        }
    }

    private fun runWrite(path: String, onError: (String) -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            _syncFeedback.value = SyncFeedback.SAVING
            _errorMessage.value = null
            try {
                block()
                _syncFeedback.value = if (uiState.value.deliverablesPendingSync) SyncFeedback.PENDING_SYNC else SyncFeedback.SAVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseFirestoreException) {
                handleFirestoreError(e, OperationType.WRITE, path)
                _syncFeedback.value = SyncFeedback.ERROR
                val msg = e.localizedMessage ?: "Permission or sync error while saving."
                _errorMessage.value = msg
                onError(msg)
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                _syncFeedback.value = SyncFeedback.ERROR
                val msg = e.localizedMessage ?: "Operation failed."
                _errorMessage.value = msg
                onError(msg)
            }
        }
    }
}
