package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.MoneyFormatter
import com.example.domain.PermissionEngine
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.components.XMaxWordmarkBadge
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
private fun monochromeChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = XMaxTheme.colors.buttonPrimaryBg,
    selectedLabelColor = XMaxTheme.colors.buttonPrimaryFg,
    containerColor = XMaxTheme.colors.surfaceMuted,
    labelColor = XMaxTheme.colors.textPrimary
)

@Composable
fun TeamGovernanceScreen(
    state: AgencyUiState,
    onInviteMember: (String, AccessLevel, Department, JobRole, ScopeType, List<String>, Map<String, PermissionGrant>) -> Unit,
    onConfigureMember: (Member, MembershipStatus, AccessLevel, Department, JobRole, ScopeType, List<String>, Map<String, PermissionGrant>) -> Unit,
    onUpdateStaffDelegation: (Member, Map<String, DelegatedGrant>) -> Unit
) {
    val palette = XMaxTheme.colors
    var editingTarget by remember { mutableStateOf<Pair<Member, MembershipStatus?>?>(null) }
    var delegatingStaff by remember { mutableStateOf<Member?>(null) }
    var showInviteDialog by remember { mutableStateOf(false) }

    val actor = state.myMember ?: return
    val isSuper = state.isSuperAdmin
    val privateByUid = remember(state.allPrivateInfo) { state.allPrivateInfo.associateBy { it.uid } }
    val sortedMembers = remember(state.members) {
        state.members.sortedWith(
            compareBy<Member>(
                {
                    when (it.statusEnum()) {
                        MembershipStatus.PENDING -> 0
                        MembershipStatus.APPROVED -> 1
                        MembershipStatus.SUSPENDED -> 2
                        MembershipStatus.DECLINED -> 3
                    }
                },
                {
                    when (it.accessLevelEnum()) {
                        AccessLevel.SUPER_ADMIN -> 0
                        AccessLevel.ADMIN -> 1
                        AccessLevel.STAFF -> 2
                    }
                },
                { it.fullName.lowercase() }
            )
        )
    }
    val pendingMembers = remember(sortedMembers) {
        sortedMembers.filter { it.statusEnum() == MembershipStatus.PENDING }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.canvas),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Team & Permissions (${state.members.size})",
                    style = XMaxType.sectionHeading.tabular(),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Button(
                        onClick = {
                            runCatching {
                                val file = com.example.pdf.AppUserGuidePdfGenerator.buildGuidePdfFile(context)
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Open User Guide PDF"))
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.surfaceMuted, contentColor = palette.textPrimary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("download_user_guide_pdf")
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("GUIDE", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                    }

                    if (isSuper) {
                        Button(
                            onClick = { showInviteDialog = true },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("invite_member_button")
                        ) {
                            Icon(Icons.Filled.MailOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("INVITE", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        if (isSuper && pendingMembers.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.butterBg,
                    border = BorderStroke(1.dp, palette.butterInk.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("team_pending_approvals_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PendingActions,
                            contentDescription = "Pending Approvals",
                            tint = palette.butterInk,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${pendingMembers.size} Membership Request${if (pendingMembers.size == 1) "" else "s"} Waiting",
                                style = XMaxType.subheadlineSemibold,
                                color = palette.butterInk
                            )
                            Text(
                                text = "Tap Approve & Configure below to assign department, job role, and permissions.",
                                style = XMaxType.caption1,
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            }
        }

        items(sortedMembers, key = { it.uid }) { m ->
            val pInfo = privateByUid[m.uid]
            val isPending = m.statusEnum() == MembershipStatus.PENDING
            val activeCount = state.deliverables.count { it.assignedToUid == m.uid && it.statusEnum() != DeliverableStatus.APPROVED }
            val blockedCount = state.deliverables.count { it.assignedToUid == m.uid && it.statusEnum() == DeliverableStatus.BLOCKED }
            val (stBg, stFg) = when (m.statusEnum()) {
                MembershipStatus.APPROVED -> palette.mintBg to palette.mintInk
                MembershipStatus.PENDING -> palette.butterBg to palette.butterInk
                MembershipStatus.DECLINED, MembershipStatus.SUSPENDED -> palette.blushBg to palette.blushInk
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(
                    width = if (isPending) 1.5.dp else 1.dp,
                    color = if (isPending) palette.butterInk.copy(alpha = 0.45f) else palette.outline
                ),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().testTag("team_member_card_${m.uid}")
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StudioAvatar(
                            name = m.fullName.ifBlank { m.requestedJobRole },
                            photoBase64 = m.photoBase64,
                            size = 46.dp,
                            borderColor = palette.lavenderBg
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                m.fullName.ifBlank { m.requestedJobRole.ifBlank { "New Applicant" } }.uppercase(),
                                style = XMaxType.headline,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val deptLabel = m.departmentName.ifBlank { m.requestedDepartment.ifBlank { "Unassigned Dept" } }
                            val roleLabel = m.jobRoleName.ifBlank { m.requestedJobRole.ifBlank { "Unassigned Role" } }
                            Text(
                                "${m.accessLevelEnum().label} • $roleLabel ($deptLabel)",
                                style = XMaxType.footnote,
                                color = palette.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isPending && m.companyNameInput.isNotBlank()) {
                                Text(
                                    "Submitted company: ${m.companyNameInput}",
                                    style = XMaxType.caption1,
                                    color = palette.textSecondary
                                )
                            }
                        }
                        StatusPill(m.statusEnum().label, stBg, stFg)
                    }
                    if (isSuper && pInfo != null) {
                        val phonePart = if (pInfo.phone.isNotBlank()) " • ${pInfo.phone}" else ""
                        Text("Private PII: Age ${pInfo.age} • ${pInfo.email}$phonePart", style = XMaxType.caption1.tabular(), color = palette.textSecondary)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPending) "Requested access — awaiting review" else "Workload: $activeCount active • $blockedCount blocked",
                            style = XMaxType.footnoteMedium.tabular(),
                            color = palette.textSecondary
                        )
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isSuper) {
                            if (isPending) {
                                Button(
                                    onClick = { editingTarget = m to MembershipStatus.APPROVED },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.buttonPrimaryBg,
                                        contentColor = palette.buttonPrimaryFg
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("configure_member_${m.uid}")
                                ) {
                                    Icon(Icons.Filled.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Approve & Configure", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val fallbackDept = state.departments.firstOrNull { it.active }
                                            ?: Department("dept_ops", "Operations")
                                        val fallbackRole = state.jobRoles.firstOrNull { it.active && it.departmentId == fallbackDept.id }
                                            ?: state.jobRoles.firstOrNull { it.active }
                                            ?: JobRole("role_pm", "Project Manager", fallbackDept.id)
                                        onConfigureMember(
                                            m,
                                            MembershipStatus.DECLINED,
                                            AccessLevel.STAFF,
                                            fallbackDept,
                                            fallbackRole,
                                            ScopeType.TEAM,
                                            emptyList(),
                                            emptyMap()
                                        )
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("decline_member_${m.uid}")
                                ) {
                                    Text("Decline", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { editingTarget = m to null },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, palette.outline),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = palette.lavenderBg, contentColor = palette.lavenderInk),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("configure_member_${m.uid}")
                                ) {
                                    Text("Configure & Permissions", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                                }
                            }
                        } else if (actor.accessLevelEnum() == AccessLevel.ADMIN && m.accessLevelEnum() == AccessLevel.STAFF && PermissionEngine.canAccessDepartment(actor, m.departmentId)) {
                            OutlinedButton(
                                onClick = { delegatingStaff = m },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, palette.outline),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = palette.lavenderBg, contentColor = palette.lavenderInk),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Delegate Staff Capabilities", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }

    editingTarget?.let { (target, preferredStatus) ->
        MemberPermissionEditorDialog(
            state = state,
            target = target,
            initialStatus = preferredStatus,
            onSave = { st, lvl, dept, role, scopeType, scopeIds, perms ->
                onConfigureMember(target, st, lvl, dept, role, scopeType, scopeIds, perms)
                editingTarget = null
            },
            onDismiss = { editingTarget = null }
        )
    }

    delegatingStaff?.let { staff ->
        StaffDelegationDialog(
            actor = actor,
            staff = staff,
            onSave = { grants ->
                onUpdateStaffDelegation(staff, grants)
                delegatingStaff = null
            },
            onDismiss = { delegatingStaff = null }
        )
    }

    if (showInviteDialog) {
        InviteMemberDialog(
            departments = state.departments.filter { it.active }.ifEmpty { com.example.domain.CatalogueSeedData.defaultDepartments() },
            jobRoles = state.jobRoles.filter { it.active }.ifEmpty { com.example.domain.CatalogueSeedData.defaultJobRoles() },
            onInvite = { email, lvl, dept, role ->
                onInviteMember(email, lvl, dept, role, ScopeType.TEAM, listOf(dept.id), emptyMap())
                showInviteDialog = false
            },
            onDismiss = { showInviteDialog = false }
        )
    }
}

@Composable
internal fun MemberPermissionEditorDialog(
    state: AgencyUiState,
    target: Member,
    initialStatus: MembershipStatus? = null,
    onSave: (MembershipStatus, AccessLevel, Department, JobRole, ScopeType, List<String>, Map<String, PermissionGrant>) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    val activeDepts = remember(state.departments) {
        state.departments.filter { it.active }.ifEmpty { com.example.domain.CatalogueSeedData.defaultDepartments() }
    }
    val activeRoles = remember(state.jobRoles) {
        state.jobRoles.filter { it.active }.ifEmpty { com.example.domain.CatalogueSeedData.defaultJobRoles() }
    }
    val pInfo = remember(state.allPrivateInfo, target.uid) {
        state.allPrivateInfo.firstOrNull { it.uid == target.uid }
    }
    val isEditingSelfSuperAdmin = target.uid == state.myMember?.uid && state.isSuperAdmin
    val selectableLevels = remember(isEditingSelfSuperAdmin) {
        if (isEditingSelfSuperAdmin) listOf(AccessLevel.SUPER_ADMIN)
        else listOf(AccessLevel.STAFF, AccessLevel.ADMIN)
    }
    val defaultAdminManagerPerms = remember {
        mapOf(
            AppPermission.MANAGE_CLIENTS.name to PermissionGrant(allowed = true, canDelegate = false),
            AppPermission.CREATE_REQUESTS.name to PermissionGrant(allowed = true, canDelegate = false),
            AppPermission.CREATE_QUOTATIONS.name to PermissionGrant(allowed = true, canDelegate = false),
            AppPermission.ISSUE_QUOTATIONS.name to PermissionGrant(allowed = true, canDelegate = false),
            AppPermission.VIEW_ALL_CLIENTS_AND_QUOTES.name to PermissionGrant(allowed = true, canDelegate = false),
            AppPermission.VIEW_PAYMENTS.name to PermissionGrant(allowed = true, canDelegate = false),
            AppPermission.ASSIGN_WORK.name to PermissionGrant(allowed = true, canDelegate = false),
            AppPermission.APPROVE_DELIVERABLES.name to PermissionGrant(allowed = true, canDelegate = false)
        )
    }

    var status by remember(target.uid, initialStatus) {
        mutableStateOf(
            when {
                isEditingSelfSuperAdmin -> MembershipStatus.APPROVED
                initialStatus != null -> initialStatus
                else -> target.statusEnum()
            }
        )
    }
    var accessLevel by remember(target.uid) {
        mutableStateOf(
            if (isEditingSelfSuperAdmin) AccessLevel.SUPER_ADMIN
            else if (target.accessLevelEnum() == AccessLevel.SUPER_ADMIN) AccessLevel.ADMIN
            else target.accessLevelEnum()
        )
    }
    var selectedDept by remember(target.uid, activeDepts) {
        val reqDept = target.requestedDepartment.trim()
        val matched = activeDepts.firstOrNull { it.id == target.departmentId }
            ?: activeDepts.firstOrNull { reqDept.isNotBlank() && it.name.equals(reqDept, ignoreCase = true) }
            ?: activeDepts.firstOrNull { reqDept.isNotBlank() && it.name.contains(reqDept, ignoreCase = true) }
            ?: activeDepts.firstOrNull()
            ?: Department("dept_ops", "Operations")
        mutableStateOf(matched)
    }
    val deptFilteredRoles = remember(activeRoles, selectedDept.id) {
        activeRoles.filter { it.departmentId == selectedDept.id }.ifEmpty { activeRoles }
    }
    var selectedRole by remember(target.uid, deptFilteredRoles) {
        val reqRole = target.requestedJobRole.trim()
        val matched = deptFilteredRoles.firstOrNull { it.id == target.jobRoleId }
            ?: deptFilteredRoles.firstOrNull { reqRole.isNotBlank() && it.name.equals(reqRole, ignoreCase = true) }
            ?: deptFilteredRoles.firstOrNull { reqRole.isNotBlank() && it.name.contains(reqRole, ignoreCase = true) }
            ?: deptFilteredRoles.firstOrNull()
            ?: activeRoles.firstOrNull()
            ?: JobRole("role_pm", "Project Manager", selectedDept.id)
        mutableStateOf(matched)
    }
    var scopeType by remember(target.uid) { mutableStateOf(target.scopeTypeEnum()) }
    var scopeDeptIds by remember(target.uid, selectedDept.id) {
        mutableStateOf(target.scopeDepartmentIds.toSet().ifEmpty { setOf(selectedDept.id) })
    }
    var perms by remember(target.uid) { mutableStateOf(target.permissions) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = palette.canvas
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = palette.textPrimary)
                    }
                    Text(
                        "CONFIGURE ${target.fullName.ifBlank { "MEMBER" }.uppercase()}",
                        style = XMaxType.headline,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Spacer(Modifier.size(48.dp))
                }
                HorizontalDivider(color = palette.outline)

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (target.requestedDepartment.isNotBlank() || target.requestedJobRole.isNotBlank() || pInfo != null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = palette.surfaceMuted,
                                border = BorderStroke(1.dp, palette.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Applicant Submission Details", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                                    if (target.requestedDepartment.isNotBlank() || target.requestedJobRole.isNotBlank()) {
                                        Text(
                                            "Requested: ${target.requestedDepartment.ifBlank { "-" }} • ${target.requestedJobRole.ifBlank { "-" }}",
                                            style = XMaxType.footnote,
                                            color = palette.textSecondary
                                        )
                                    }
                                    if (target.companyNameInput.isNotBlank()) {
                                        Text(
                                            "Company: ${target.companyNameInput}",
                                            style = XMaxType.footnote,
                                            color = palette.textSecondary
                                        )
                                    }
                                    if (pInfo != null) {
                                        val phoneSuffix = if (pInfo.phone.isNotBlank()) " • ${pInfo.phone}" else ""
                                        Text(
                                            "Contact: ${pInfo.email}$phoneSuffix • Age ${pInfo.age}",
                                            style = XMaxType.footnote,
                                            color = palette.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text("Membership Status", style = XMaxType.headline, color = palette.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        if (isEditingSelfSuperAdmin) {
                            StatusPill("Approved (Super Admin Locked)", palette.mintBg, palette.mintInk)
                        } else {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MembershipStatus.entries.forEach { st ->
                                    FilterChip(
                                        selected = status == st,
                                        onClick = { status = st },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = monochromeChipColors(),
                                        border = null,
                                        label = { Text(st.label, style = XMaxType.footnoteMedium) }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text("Access Level (Separate from Job Role)", style = XMaxType.headline, color = palette.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            selectableLevels.forEach { lvl ->
                                FilterChip(
                                    selected = accessLevel == lvl,
                                    onClick = {
                                        accessLevel = lvl
                                        if (lvl == AccessLevel.ADMIN && perms.values.none { it.allowed }) {
                                            perms = defaultAdminManagerPerms
                                        }
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(lvl.label, style = XMaxType.footnoteMedium) }
                                )
                            }
                        }
                        if (!isEditingSelfSuperAdmin) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Super Admin access is reserved exclusively for xdotmax@gmail.com.",
                                style = XMaxType.caption1,
                                color = palette.textSecondary
                            )
                        }
                    }
                    item {
                        Text("Department & Job Role", style = XMaxType.headline, color = palette.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeDepts.forEach { d ->
                                FilterChip(
                                    selected = selectedDept.id == d.id,
                                    onClick = {
                                        selectedDept = d
                                        val rolesForDept = activeRoles.filter { it.departmentId == d.id }
                                        if (rolesForDept.isNotEmpty() && selectedRole.departmentId != d.id) {
                                            selectedRole = rolesForDept.first()
                                        }
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(d.name, style = XMaxType.footnoteMedium) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            deptFilteredRoles.forEach { r ->
                                FilterChip(
                                    selected = selectedRole.id == r.id,
                                    onClick = { selectedRole = r },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(r.name, style = XMaxType.footnoteMedium) }
                                )
                            }
                        }
                    }

                    if (accessLevel == AccessLevel.ADMIN) {
                        item {
                            Text("Manager Scope", style = XMaxType.headline, color = palette.textPrimary)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScopeType.entries.forEach { sc ->
                                    FilterChip(
                                        selected = scopeType == sc,
                                        onClick = { scopeType = sc },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = monochromeChipColors(),
                                        border = null,
                                        label = { Text(sc.label, style = XMaxType.footnoteMedium) }
                                    )
                                }
                            }
                            if (scopeType == ScopeType.TEAM) {
                                Spacer(Modifier.height(6.dp))
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    activeDepts.forEach { d ->
                                        val checked = d.id in scopeDeptIds
                                        FilterChip(
                                            selected = checked,
                                            onClick = { scopeDeptIds = if (checked) scopeDeptIds - d.id else scopeDeptIds + d.id },
                                            shape = RoundedCornerShape(999.dp),
                                            colors = monochromeChipColors(),
                                            border = null,
                                            label = { Text(d.name, style = XMaxType.footnoteMedium) }
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Admin Permission Matrix (Allowed / May Delegate to Staff)", style = XMaxType.headline, color = palette.textPrimary)
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AssistChip(
                                        onClick = { perms = defaultAdminManagerPerms },
                                        label = { Text("Manager Preset", style = XMaxType.caption1Semibold) }
                                    )
                                    AssistChip(
                                        onClick = { perms = emptyMap() },
                                        label = { Text("Clear All", style = XMaxType.caption1Semibold) }
                                    )
                                }
                            }
                        }
                        items(AppPermission.entries, key = { it.name }) { p ->
                            val current = perms[p.name] ?: PermissionGrant()
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = palette.surfaceMuted,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = palette.surface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(p.label, style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                                                    Text(p.description, style = XMaxType.footnote, color = palette.textSecondary)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("Allowed", style = XMaxType.caption1, color = palette.textSecondary)
                                                    Switch(
                                                        checked = current.allowed,
                                                        onCheckedChange = { allow ->
                                                            perms = perms + (p.name to current.copy(allowed = allow, canDelegate = if (allow) current.canDelegate else false))
                                                        }
                                                    )
                                                }
                                            }
                                            if (p.staffEligible) {
                                                HorizontalDivider(color = palette.outline)
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text("May delegate to staff", style = XMaxType.footnoteMedium, color = palette.textPrimary)
                                                    Switch(
                                                        checked = current.canDelegate,
                                                        enabled = current.allowed,
                                                        onCheckedChange = { del ->
                                                            perms = perms + (p.name to current.copy(canDelegate = del))
                                                        }
                                                    )
                                                }
                                            } else {
                                                Text("Reserved action — never delegable to Staff", style = XMaxType.caption1, color = CrimsonBlocked)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { onSave(status, accessLevel, selectedDept, selectedRole, scopeType, scopeDeptIds.toList(), perms) },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_member_permissions_button")
                ) { Text("Save Member & Permissions", style = XMaxType.button, maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
private fun StaffDelegationDialog(
    actor: Member,
    staff: Member,
    onSave: (Map<String, DelegatedGrant>) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    var grants by remember { mutableStateOf(staff.delegatedGrants) }
    val delegablePerms = AppPermission.entries.filter { PermissionEngine.canAdminDelegateToStaff(actor, staff, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("DELEGATE CAPABILITIES — ${staff.fullName.uppercase()}", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (delegablePerms.isEmpty()) {
                    Text("You do not hold any delegable permissions within this team member's scope.", style = XMaxType.subheadline, color = palette.textSecondary)
                } else {
                    delegablePerms.forEach { p ->
                        val active = grants[p.name]?.allowed == true
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label, style = XMaxType.subheadlineMedium, modifier = Modifier.weight(1f), color = palette.textPrimary)
                            Switch(
                                checked = active,
                                onCheckedChange = { checked ->
                                    grants = if (checked) {
                                        grants + (p.name to DelegatedGrant(true, actor.uid, System.currentTimeMillis()))
                                    } else {
                                        grants - p.name
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(grants) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
            ) { Text("Save", style = XMaxType.buttonCompact) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
    )
}

@Composable
private fun InviteMemberDialog(
    departments: List<Department>,
    jobRoles: List<JobRole>,
    onInvite: (String, AccessLevel, Department, JobRole) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    var email by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(AccessLevel.STAFF) }
    var dept by remember { mutableStateOf(departments.firstOrNull() ?: Department("dept_creative", "Creative")) }
    val deptRoles = remember(jobRoles, dept.id) {
        jobRoles.filter { it.departmentId == dept.id }.ifEmpty { jobRoles }
    }
    var role by remember {
        mutableStateOf(
            deptRoles.firstOrNull()
                ?: jobRoles.firstOrNull()
                ?: JobRole("role_graphic_designer", "Graphic Designer", dept.id)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Invite Team Member", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = email, onValueChange = { email = it }, textStyle = XMaxType.body, label = { Text("Google Email *", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AccessLevel.STAFF, AccessLevel.ADMIN).forEach { l ->
                        FilterChip(selected = level == l, onClick = { level = l }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(l.label, style = XMaxType.footnoteMedium) })
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    departments.forEach { d ->
                        FilterChip(
                            selected = dept.id == d.id,
                            onClick = {
                                dept = d
                                val rolesForDept = jobRoles.filter { it.departmentId == d.id }
                                if (rolesForDept.isNotEmpty() && role.departmentId != d.id) {
                                    role = rolesForDept.first()
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = monochromeChipColors(),
                            border = null,
                            label = { Text(d.name, style = XMaxType.footnoteMedium) }
                        )
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    deptRoles.forEach { r ->
                        FilterChip(selected = role.id == r.id, onClick = { role = r }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(r.name, style = XMaxType.footnoteMedium) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInvite(email, level, dept, role) },
                enabled = email.contains("@"),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
            ) { Text("Send Invite", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
    )
}

@Composable
fun OrgAndRoutingScreen(
    state: AgencyUiState,
    onSaveDepartment: (String?, String, String) -> Unit,
    onSaveJobRole: (String?, String, String) -> Unit,
    onRetireDepartment: (Department, Department, JobRole) -> Unit,
    onRetireJobRole: (JobRole, Department, JobRole) -> Unit,
    onSaveRoutingRule: (RoutingRule) -> Unit
) {
    val palette = XMaxTheme.colors
    var retireDeptTarget by remember { mutableStateOf<Department?>(null) }
    var retireRoleTarget by remember { mutableStateOf<JobRole?>(null) }
    var showAddDept by remember { mutableStateOf(false) }
    var showAddRole by remember { mutableStateOf(false) }

    val activeDepts = state.departments.filter { it.active }
    val activeRoles = state.jobRoles.filter { it.active }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Departments (${activeDepts.size})",
                    style = XMaxType.sectionHeading.tabular(),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Button(
                    onClick = { showAddDept = true },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) { Text("+ DEPT", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false) }
            }
        }
        items(activeDepts, key = { it.id }) { d ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name.uppercase(), style = XMaxType.headline, color = palette.textPrimary)
                        if (d.description.isNotBlank()) Text(d.description, style = XMaxType.footnote, color = palette.textSecondary)
                    }
                    if (activeDepts.size > 1) {
                        TextButton(onClick = { retireDeptTarget = d }) { Text("Retire", style = XMaxType.footnoteSemibold, color = CrimsonBlocked) }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Job Roles (${activeRoles.size})",
                    style = XMaxType.sectionHeading.tabular(),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Button(
                    onClick = { showAddRole = true },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) { Text("+ ROLE", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false) }
            }
        }
        items(activeRoles, key = { it.id }) { r ->
            val dName = activeDepts.firstOrNull { it.id == r.departmentId }?.name ?: r.departmentId
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name.uppercase(), style = XMaxType.headline, color = palette.textPrimary)
                        Text("Department: ${dName.uppercase()}", style = XMaxType.footnote, color = palette.textSecondary)
                    }
                    if (activeRoles.size > 1) {
                        TextButton(onClick = { retireRoleTarget = r }) { Text("Retire", style = XMaxType.footnoteSemibold, color = CrimsonBlocked) }
                    }
                }
            }
        }

        item { Text("Service-to-Role Routing Rules (${state.routingRules.size})", style = XMaxType.sectionHeading.tabular(), color = palette.textPrimary) }
        items(state.routingRules, key = { it.id }) { rule ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(formatTitleCase(rule.name), style = XMaxType.headline, color = palette.textPrimary)
                        Text("Priority ${rule.priority} • Target: ${formatTitleCase(rule.departmentName)} / ${formatTitleCase(rule.jobRoleName)}", style = XMaxType.footnote.tabular(), color = palette.textSecondary)
                    }
                    Switch(checked = rule.active, onCheckedChange = { onSaveRoutingRule(rule.copy(active = it)) })
                }
            }
        }
    }

    retireDeptTarget?.let { dept ->
        val activeDeliverablesCount = state.deliverables.count { it.departmentId == dept.id && it.statusEnum() != DeliverableStatus.APPROVED }
        val impact = PermissionEngine.analyzeDepartmentRetirement(dept.id, state.members, state.jobRoles, state.routingRules, activeDeliverablesCount)
        val candidates = PermissionEngine.validReplacementDepartments(state.departments, dept.id)
        var repDept by remember(dept.id) { mutableStateOf(candidates.firstOrNull()) }
        var repRole by remember(dept.id, repDept?.id) {
            mutableStateOf(
                activeRoles.firstOrNull { it.departmentId == repDept?.id } ?: activeRoles.firstOrNull()
            )
        }

        AlertDialog(
            onDismissRequest = { retireDeptTarget = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Retire Department: ${dept.name}", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Impact Preview:\n• ${impact.affectedEmployees.size} employees\n• ${impact.affectedJobRoles.size} job roles\n• ${impact.affectedRoutingRules.size} routing rules\n• ${impact.activeDeliverableCount} active deliverables", style = XMaxType.subheadline.tabular(), color = palette.textSecondary)
                    if (candidates.isEmpty() || repRole == null) {
                        Text(
                            "Create another active department and job role before retiring this department.",
                            color = CrimsonBlocked,
                            style = XMaxType.footnote
                        )
                    } else {
                        Text("Reassign all to:", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.forEach { c ->
                                FilterChip(
                                    selected = repDept?.id == c.id,
                                    onClick = {
                                        repDept = c
                                        repRole = activeRoles.firstOrNull { it.departmentId == c.id } ?: activeRoles.firstOrNull()
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(c.name, style = XMaxType.footnoteMedium) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val safeDept = repDept
                val safeRole = repRole
                Button(
                    onClick = {
                        if (safeDept != null && safeRole != null) {
                            onRetireDepartment(dept, safeDept, safeRole)
                            retireDeptTarget = null
                        }
                    },
                    enabled = safeDept != null && safeRole != null,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Reassign & Retire", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { retireDeptTarget = null }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }

    retireRoleTarget?.let { role ->
        val activeCount = state.deliverables.count { it.jobRoleId == role.id && it.statusEnum() != DeliverableStatus.APPROVED }
        val impact = PermissionEngine.analyzeRoleRetirement(role.id, state.members, state.routingRules, activeCount)
        val candidates = activeRoles.filter { it.id != role.id }
        var repRole by remember(role.id) { mutableStateOf(candidates.firstOrNull()) }
        val repDept = repRole?.let { rr -> activeDepts.firstOrNull { it.id == rr.departmentId } } ?: activeDepts.firstOrNull()

        AlertDialog(
            onDismissRequest = { retireRoleTarget = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Retire Job Role: ${role.name}", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Impact Preview:\n• ${impact.affectedEmployees.size} employees\n• ${impact.affectedRoutingRules.size} routing rules\n• ${impact.activeDeliverableCount} active deliverables", style = XMaxType.subheadline.tabular(), color = palette.textSecondary)
                    if (candidates.isEmpty() || repDept == null) {
                        Text(
                            "Create another active job role before retiring this role.",
                            color = CrimsonBlocked,
                            style = XMaxType.footnote
                        )
                    } else {
                        Text("Reassign to replacement role:", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.forEach { c ->
                                FilterChip(selected = repRole?.id == c.id, onClick = { repRole = c }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(c.name, style = XMaxType.footnoteMedium) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val safeRole = repRole
                val safeDept = repDept
                Button(
                    onClick = {
                        if (safeDept != null && safeRole != null) {
                            onRetireJobRole(role, safeDept, safeRole)
                            retireRoleTarget = null
                        }
                    },
                    enabled = safeDept != null && safeRole != null,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Reassign & Retire", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { retireRoleTarget = null }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }

    if (showAddDept) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDept = false },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Add Department", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, textStyle = XMaxType.body, label = { Text("Department Name *", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, textStyle = XMaxType.body, label = { Text("Description", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSaveDepartment(null, name, desc); showAddDept = false },
                    enabled = name.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Save", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { showAddDept = false }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }

    if (showAddRole && activeDepts.isNotEmpty()) {
        var name by remember { mutableStateOf("") }
        var dept by remember { mutableStateOf(activeDepts.first()) }
        AlertDialog(
            onDismissRequest = { showAddRole = false },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Add Job Role", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, textStyle = XMaxType.body, label = { Text("Role Name *", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeDepts.forEach { d -> FilterChip(selected = dept.id == d.id, onClick = { dept = d }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(d.name, style = XMaxType.footnoteMedium) }) }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSaveJobRole(null, name, dept.id); showAddRole = false },
                    enabled = name.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Save", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { showAddRole = false }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }
}

@Composable
fun CompanySettingsScreen(
    state: AgencyUiState,
    onSaveCompany: (CompanyProfile) -> Unit
) {
    val palette = XMaxTheme.colors
    val company = state.company ?: return
    var name by remember(company) { mutableStateOf(company.name) }
    var tagline by remember(company) { mutableStateOf(company.tagline) }
    var address by remember(company) { mutableStateOf(company.address) }
    var phone by remember(company) { mutableStateOf(company.phone) }
    var email by remember(company) { mutableStateOf(company.email) }
    var gstin by remember(company) { mutableStateOf(company.gstin) }
    var gstEnabled by remember(company) { mutableStateOf(company.gstEnabled) }
    var gstRate by remember(company) { mutableStateOf(company.gstRatePercent.toString()) }
    var advPct by remember(company) { mutableStateOf(company.defaultAdvancePercent.toString()) }
    var valDays by remember(company) { mutableStateOf(company.defaultValidityDays.toString()) }
    var revRounds by remember(company) { mutableStateOf(company.defaultRevisionRounds.toString()) }
    var payTerms by remember(company) { mutableStateOf(company.defaultPaymentTerms) }
    var defaultWallpaperId by remember(company) { mutableStateOf(company.defaultChatWallpaperId.ifBlank { "studio_white" }) }
    var defaultWallpaperUrl by remember(company) { mutableStateOf(company.defaultChatWallpaperUrl) }

    val presetWallpapers = listOf(
        "studio_white" to "Studio Crisp",
        "dark_doodle" to "Dark Doodle",
        "midnight_slate" to "Obsidian Slate",
        "warm_gradient" to "Sunset Amber",
        "sky_breeze" to "Sky Breeze",
        "lavender_cloud" to "Pastel Violet"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { XMaxWordmarkBadge("X.MAX COMPANY & QUOTE DEFAULTS") }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tfColors = OutlinedTextFieldDefaults.colors(focusedContainerColor = palette.surface, unfocusedContainerColor = palette.surface)
                    OutlinedTextField(value = name, onValueChange = { name = it }, textStyle = XMaxType.body, label = { Text("Company Name", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = tagline, onValueChange = { tagline = it }, textStyle = XMaxType.body, label = { Text("Tagline", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = address, onValueChange = { address = it }, textStyle = XMaxType.body, label = { Text("Business Address", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, textStyle = XMaxType.body.tabular(), label = { Text("Phone", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = email, onValueChange = { email = it }, textStyle = XMaxType.body, label = { Text("Email", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = gstin, onValueChange = { gstin = it }, textStyle = XMaxType.body.tabular(), label = { Text("GSTIN (Optional)", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    Surface(shape = RoundedCornerShape(14.dp), color = palette.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable GST on new quotes", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                            Switch(checked = gstEnabled, onCheckedChange = { gstEnabled = it })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = gstRate, onValueChange = { gstRate = it.filter(Char::isDigit) }, textStyle = XMaxType.body.tabular(), label = { Text("GST %", style = XMaxType.inputLabel) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = advPct, onValueChange = { advPct = it.filter(Char::isDigit) }, textStyle = XMaxType.body.tabular(), label = { Text("Advance %", style = XMaxType.inputLabel) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = valDays, onValueChange = { valDays = it.filter(Char::isDigit) }, textStyle = XMaxType.body.tabular(), label = { Text("Valid Days", style = XMaxType.inputLabel) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = revRounds, onValueChange = { revRounds = it.filter(Char::isDigit) }, textStyle = XMaxType.body.tabular(), label = { Text("Revisions", style = XMaxType.inputLabel) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = payTerms, onValueChange = { payTerms = it }, textStyle = XMaxType.body, label = { Text("Default Payment Terms", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())

                    HorizontalDivider(color = palette.outline, modifier = Modifier.padding(vertical = 4.dp))

                    // Super Admin Workspace Default Chat Wallpaper
                    Text(
                        text = "Workspace Default Chat Wallpaper",
                        style = XMaxType.subheadlineSemibold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Set a baseline background for all team chats. Team members can still override with their personal favorite.",
                        style = XMaxType.caption1,
                        color = palette.textSecondary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetWallpapers.forEach { (pid, plabel) ->
                            val isSel = defaultWallpaperId == pid && defaultWallpaperUrl.isBlank()
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    defaultWallpaperId = pid
                                    defaultWallpaperUrl = ""
                                },
                                label = { Text(plabel, style = XMaxType.caption1Medium) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = defaultWallpaperUrl,
                        onValueChange = { defaultWallpaperUrl = it },
                        textStyle = XMaxType.body,
                        label = { Text("Custom Wallpaper Image URL (Optional)", style = XMaxType.inputLabel) },
                        placeholder = { Text("https://example.com/company-wallpaper.jpg", style = XMaxType.caption1) },
                        shape = RoundedCornerShape(14.dp),
                        colors = tfColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            onSaveCompany(
                                company.copy(
                                    name = name,
                                    tagline = tagline,
                                    address = address,
                                    phone = phone,
                                    email = email,
                                    gstin = gstin,
                                    gstEnabled = gstEnabled,
                                    gstRatePercent = gstRate.toIntOrNull()?.coerceIn(0, 28) ?: 18,
                                    defaultAdvancePercent = advPct.toIntOrNull()?.coerceIn(0, 100) ?: 50,
                                    defaultValidityDays = valDays.toIntOrNull()?.coerceIn(1, 365) ?: 15,
                                    defaultRevisionRounds = revRounds.toIntOrNull()?.coerceIn(0, 20) ?: 2,
                                    defaultPaymentTerms = payTerms,
                                    defaultChatWallpaperId = defaultWallpaperId,
                                    defaultChatWallpaperUrl = defaultWallpaperUrl.trim()
                                )
                            )
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("SAVE COMPANY SETTINGS", style = XMaxType.button) }
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(
    state: AgencyUiState,
    onMarkRead: (WorkspaceNotification) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
    onAutoMarkAllRead: () -> Unit = onMarkAllRead,
    onToggleSound: (Boolean) -> Unit = {},
    onTestSound: () -> Unit = {},
    onDeleteNotification: (String) -> Unit = {},
    onOpenChatChannel: (String) -> Unit = {},
    onOpenDeliverableById: (String) -> Unit = {},
    onOpenPayments: () -> Unit = {}
) {
    val palette = XMaxTheme.colors
    val myUid = state.myMember?.uid.orEmpty()
    val relevant = state.myNotifications
    val unreadCount = relevant.count { it.isUnreadFor(myUid) }
    LaunchedEffect(myUid, unreadCount) {
        if (myUid.isNotBlank() && unreadCount > 0) {
            delay(350L)
            onAutoMarkAllRead()
        }
    }
    var unreadOnly by remember { mutableStateOf(false) }
    val visible = remember(relevant, unreadOnly, myUid) {
        if (unreadOnly) relevant.filter { it.isUnreadFor(myUid) } else relevant
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .testTag("notifications_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notifications (${relevant.size})",
                    style = XMaxType.sectionHeading.tabular(),
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (unreadCount > 0) {
                    TextButton(
                        onClick = onMarkAllRead,
                        modifier = Modifier.testTag("mark_all_notifications_read_button")
                    ) {
                        Text("MARK ALL READ", style = XMaxType.footnoteSemibold, color = palette.lavenderInk)
                    }
                }
            }
        }

        // Sound Alert Settings Card
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(palette.lavenderBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.soundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = "Notification sound",
                                    tint = palette.lavenderInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text("NOTIFICATION SOUND", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                                Text("Play alert chime on new tasks, approvals & messages", style = XMaxType.footnote, color = palette.textSecondary)
                            }
                        }
                        Switch(
                            checked = state.soundEnabled,
                            onCheckedChange = onToggleSound,
                            modifier = Modifier.testTag("notification_sound_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !unreadOnly,
                            onClick = { unreadOnly = false },
                            shape = RoundedCornerShape(999.dp),
                            colors = monochromeChipColors(),
                            border = null,
                            modifier = Modifier.testTag("notif_filter_all"),
                            label = { Text("ALL (${relevant.size})", style = XMaxType.footnoteMedium.tabular()) }
                        )
                        FilterChip(
                            selected = unreadOnly,
                            onClick = { unreadOnly = true },
                            shape = RoundedCornerShape(999.dp),
                            colors = monochromeChipColors(),
                            border = null,
                            modifier = Modifier.testTag("notif_filter_unread"),
                            label = { Text("UNREAD ($unreadCount)", style = XMaxType.footnoteMedium.tabular()) }
                        )
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Filled.NotificationsNone,
                    if (unreadOnly) "NO UNREAD NOTIFICATIONS" else "ALL CAUGHT UP",
                    "New tasks, approvals, projects & chat alerts will chime and appear here."
                )
            }
        } else {
            items(visible, key = { it.id }) { notif ->
                val isUnread = notif.isUnreadFor(myUid)
                val icon = when (notif.categoryEnum()) {
                    NotificationCategory.CHAT -> Icons.Filled.ChatBubbleOutline
                    NotificationCategory.DELIVERABLE -> Icons.Filled.TaskAlt
                    NotificationCategory.PROJECT -> Icons.Filled.WorkOutline
                    NotificationCategory.PAYMENT -> Icons.Filled.Payments
                    NotificationCategory.MEMBERSHIP -> Icons.Filled.Group
                    NotificationCategory.SYSTEM -> Icons.Filled.Notifications
                }
                val timeLabel = notif.createdAt?.toDate()?.time?.let { MoneyFormatter.formatDateTime(it) } ?: "JUST NOW"
                Surface(
                    onClick = {
                        onMarkRead(notif)
                        if (notif.channelId.isNotBlank()) {
                            onOpenChatChannel(notif.channelId)
                        } else if (notif.categoryEnum() == NotificationCategory.DELIVERABLE && notif.entityId.isNotBlank()) {
                            onOpenDeliverableById(notif.entityId)
                        } else if (notif.categoryEnum() == NotificationCategory.PAYMENT) {
                            onOpenPayments()
                        }
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = if (isUnread) palette.lavenderBg.copy(alpha = 0.72f) else palette.surface,
                    border = BorderStroke(1.dp, if (isUnread) palette.accentViolet.copy(alpha = 0.45f) else palette.outline),
                    shadowElevation = if (isUnread) 3.dp else 1.dp,
                    modifier = Modifier.fillMaxWidth().testTag("notification_card_${notif.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isUnread) palette.accentViolet else palette.surfaceMuted),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isUnread) PureWhite else palette.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isUnread) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(palette.accentViolet)
                                    )
                                }
                                Text(
                                    formatTitleCase(notif.title),
                                    style = XMaxType.headline,
                                    color = palette.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (notif.body.isNotBlank()) {
                                Text(
                                    notif.body,
                                    style = XMaxType.footnote,
                                    color = palette.textPrimary
                                )
                            }
                            Text(
                                "${formatTitleCase(notif.actorName.ifBlank { "Workspace" })} • $timeLabel",
                                style = XMaxType.caption1.tabular(),
                                color = palette.textSecondary
                            )
                        }
                        if (state.isSuperAdmin) {
                            IconButton(
                                onClick = { onDeleteNotification(notif.id) },
                                modifier = Modifier.size(48.dp).testTag("delete_notif_${notif.id}")
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete notification", tint = CrimsonBlocked)
                            }
                        }
                    }
                }
            }
        }
    }
}

