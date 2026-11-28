package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AuthenticationRepository
import com.example.data.BackendDiagnosticReport
import com.example.data.ServiceDiagnosticStatus
import com.example.data.ServiceHealthState
import com.example.data.UserRoleType
import com.example.domain.CatalogueSeedData
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.AnalyticsVisualizer
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdminAccessCheckResult(
    val isAuthorized: Boolean,
    val currentEmail: String,
    val resolvedRole: UserRoleType,
    val reason: String
)

object AdminDashboardAccessEvaluator {
    const val AUTHORIZED_SUPER_ADMIN_EMAIL = CatalogueSeedData.DEFAULT_SUPER_ADMIN_EMAIL

    fun evaluate(state: AgencyUiState): AdminAccessCheckResult {
        val currentEmail = state.verifiedEmail.trim()
        val verifiedRole = if (state.verifiedUserRole.isAuthenticated) {
            state.verifiedUserRole
        } else {
            AuthenticationRepository.verifyUserRole(
                email = currentEmail,
                uid = state.firebaseUser?.uid ?: state.myMember?.uid.orEmpty(),
                isAuthenticated = currentEmail.isNotBlank() || state.firebaseUser != null
            )
        }
        val emailMatches = currentEmail.equals(AUTHORIZED_SUPER_ADMIN_EMAIL, ignoreCase = true)
        val isAuthorized = emailMatches && verifiedRole.role == UserRoleType.SUPER_ADMIN
        val reason = when {
            isAuthorized -> "Verified Super Admin ($AUTHORIZED_SUPER_ADMIN_EMAIL)."
            currentEmail.isBlank() -> "No signed-in email verified. Access is restricted strictly to $AUTHORIZED_SUPER_ADMIN_EMAIL."
            else -> "Signed in as $currentEmail (${verifiedRole.role.roleKey}). Restricted features require $AUTHORIZED_SUPER_ADMIN_EMAIL."
        }
        return AdminAccessCheckResult(
            isAuthorized = isAuthorized,
            currentEmail = currentEmail,
            resolvedRole = verifiedRole.role,
            reason = reason
        )
    }
}

@Composable
fun AdminDashboardScreen(
    state: AgencyUiState,
    onRunBackendDiagnostics: () -> Unit,
    onToggleDeleteProject: (Project, Boolean) -> Unit,
    onPermanentlyDeleteProject: (Project) -> Unit,
    onCleanupOldDoneProjects: () -> Unit,
    onOpenTeamGovernance: () -> Unit,
    onOpenCompanySettings: () -> Unit,
    onBack: () -> Unit,
    onConfigureMember: (Member, MembershipStatus, AccessLevel, Department, JobRole, ScopeType, List<String>, Map<String, PermissionGrant>) -> Unit = { _, _, _, _, _, _, _, _ -> }
) {
    val palette = XMaxTheme.colors
    val accessCheck = remember(state.firebaseUser?.email, state.verifiedUserRole, state.myMember?.uid) {
        AdminDashboardAccessEvaluator.evaluate(state)
    }

    if (!accessCheck.isAuthorized) {
        RestrictedAdminGateView(
            accessCheck = accessCheck,
            onBack = onBack
        )
        return
    }

    LaunchedEffect(accessCheck.isAuthorized) {
        if (accessCheck.isAuthorized &&
            state.backendDiagnostics.lastCheckedMillis == 0L &&
            !state.backendDiagnostics.isRunning
        ) {
            onRunBackendDiagnostics()
        }
    }

    val activeProjects = remember(state.projects) { state.projects.filter { !it.deleted } }
    val archivedProjects = remember(state.projects) { state.projects.filter { it.deleted } }
    val completedProjects = remember(activeProjects, state.deliverables) {
        activeProjects.filter { p ->
            val pDelivs = state.deliverables.filter { it.projectId == p.id }
            val allDone = pDelivs.isNotEmpty() && pDelivs.all { it.statusEnum() == DeliverableStatus.APPROVED }
            allDone || p.statusEnum() == ProjectStatus.COMPLETED
        }
    }
    val pendingRequests = remember(state.requests) {
        state.requests.filter { it.decisionStatusEnum() == DecisionStatus.PENDING }
    }
    val blockedDeliverables = remember(state.deliverables) {
        state.deliverables.filter { it.statusEnum() == DeliverableStatus.BLOCKED }
    }
    val pendingMembers = remember(state.members) {
        state.members.filter { it.statusEnum() == MembershipStatus.PENDING }
    }
    val sortedAuditMembers = remember(state.members) {
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
                { it.fullName.lowercase() }
            )
        )
    }
    val privateByUid = remember(state.allPrivateInfo) {
        state.allPrivateInfo.associateBy { it.uid }
    }
    val analyticsSeries = remember(state.projects, state.deliverables, state.payments, state.timeEntries) {
        WorkDistributionEngine.computeAgencyAnalyticsSeries(
            projects = state.projects,
            deliverables = state.deliverables,
            payments = state.payments,
            timeEntries = state.timeEntries
        )
    }

    var projectPendingPermanentDelete by remember { mutableStateOf<Project?>(null) }
    var editingMemberTarget by remember { mutableStateOf<Pair<Member, MembershipStatus?>?>(null) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var showArchivedOnly by remember { mutableStateOf(false) }

    val displayedProjects = if (showArchivedOnly) archivedProjects else activeProjects

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("admin_dashboard_unlocked"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Super Admin Identity Verification Banner
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.accentViolet.copy(alpha = 0.35f)),
                shadowElevation = if (XMaxTheme.isDark) 0.dp else 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_identity_verified_card")
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(palette.kpiLavenderBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = "Super Admin Verified",
                                    tint = palette.accentViolet,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Super Admin Control Center",
                                    style = XMaxType.headline,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = accessCheck.currentEmail,
                                    style = XMaxType.footnoteMedium,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        StatusPill(
                            label = accessCheck.resolvedRole.roleKey,
                            containerColor = palette.mintBg,
                            contentColor = palette.mintInk
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdminStatTile(
                            label = "Active Projects",
                            value = "${activeProjects.size}",
                            bg = palette.kpiSkyBg,
                            modifier = Modifier.weight(1f)
                        )
                        AdminStatTile(
                            label = "Pending Req",
                            value = "${pendingRequests.size}",
                            bg = palette.kpiLavenderBg,
                            modifier = Modifier.weight(1f)
                        )
                        AdminStatTile(
                            label = "Blocked",
                            value = "${blockedDeliverables.size}",
                            bg = if (blockedDeliverables.isNotEmpty()) palette.blushBg else palette.kpiMintBg,
                            modifier = Modifier.weight(1f)
                        )
                        AdminStatTile(
                            label = "Approvals",
                            value = "${pendingMembers.size}",
                            bg = if (pendingMembers.isNotEmpty()) palette.amberBg else palette.kpiPeachBg,
                            modifier = Modifier.weight(1f),
                            onClick = onOpenTeamGovernance
                        )
                    }
                }
            }
        }

        // 1a. Pending Membership Approvals & Permission Configuration (Super Admin Action Center)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(
                    width = if (pendingMembers.isNotEmpty()) 1.5.dp else 1.dp,
                    color = if (pendingMembers.isNotEmpty()) palette.butterInk.copy(alpha = 0.45f) else palette.outline
                ),
                shadowElevation = if (XMaxTheme.isDark) 0.dp else 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_pending_approvals_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Membership Approvals (${pendingMembers.size})",
                                style = XMaxType.headline,
                                color = palette.textPrimary
                            )
                            Text(
                                text = if (pendingMembers.isNotEmpty()) {
                                    "Review submitted requests and assign department, job role & permissions."
                                } else {
                                    "No pending requests right now. Open Team & Permissions to edit any member."
                                },
                                style = XMaxType.footnote,
                                color = palette.textSecondary
                            )
                        }
                        StatusPill(
                            label = if (pendingMembers.isNotEmpty()) "${pendingMembers.size} Waiting" else "Up to date",
                            containerColor = if (pendingMembers.isNotEmpty()) palette.butterBg else palette.mintBg,
                            contentColor = if (pendingMembers.isNotEmpty()) palette.butterInk else palette.mintInk
                        )
                    }

                    if (pendingMembers.isEmpty()) {
                        OutlinedButton(
                            onClick = onOpenTeamGovernance,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("admin_manage_team_permissions_button")
                        ) {
                            Icon(Icons.Outlined.Group, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Manage Team & Permissions", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                        }
                    } else {
                        pendingMembers.forEach { m ->
                            val pInfo = privateByUid[m.uid]
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = palette.surfaceSoft,
                                border = BorderStroke(1.dp, palette.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("admin_pending_member_row_${m.uid}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StudioAvatar(
                                            name = m.fullName.ifBlank { m.requestedJobRole },
                                            photoBase64 = m.photoBase64,
                                            size = 42.dp,
                                            borderColor = palette.butterBg
                                        )
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = formatTitleCase(m.fullName.ifBlank { "New Applicant" }),
                                                style = XMaxType.subheadlineSemibold,
                                                color = palette.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val reqDept = m.requestedDepartment.ifBlank { "Department not specified" }
                                            val reqRole = m.requestedJobRole.ifBlank { "Role not specified" }
                                            Text(
                                                text = "Requested: $reqDept • $reqRole",
                                                style = XMaxType.footnote,
                                                color = palette.textSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val contactText = listOfNotNull(
                                                pInfo?.email?.takeIf { it.isNotBlank() },
                                                pInfo?.phone?.takeIf { it.isNotBlank() },
                                                m.companyNameInput.takeIf { it.isNotBlank() }?.let { "Org: $it" }
                                            ).joinToString(" • ")
                                            if (contactText.isNotBlank()) {
                                                Text(
                                                    text = contactText,
                                                    style = XMaxType.caption1,
                                                    color = palette.textSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        StatusPill(
                                            label = "Pending",
                                            containerColor = palette.butterBg,
                                            contentColor = palette.butterInk
                                        )
                                    }

                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { editingMemberTarget = m to MembershipStatus.APPROVED },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = palette.buttonPrimaryBg,
                                                contentColor = palette.buttonPrimaryFg
                                            ),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                            modifier = Modifier
                                                .defaultMinSize(minHeight = 48.dp)
                                                .testTag("admin_approve_member_${m.uid}")
                                        ) {
                                            Icon(Icons.Filled.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Approve & Set Permissions", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
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
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.45f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                            modifier = Modifier
                                                .defaultMinSize(minHeight = 48.dp)
                                                .testTag("admin_decline_member_${m.uid}")
                                        ) {
                                            Text("Decline", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1b. Super Admin Progress & Revenue Forecast
        item {
            AnalyticsVisualizer(
                series = analyticsSeries,
                title = "Progress & Revenue Forecast",
                modifier = Modifier.testTag("admin_analytics_visualizer")
            )
        }

        // 2. Cloud Infrastructure Health (Firebase / Cloudflare / Supabase)
        item {
            BackendHealthDiagnosticsCard(
                report = state.backendDiagnostics,
                onRunCheck = onRunBackendDiagnostics
            )
        }

        // 3. Restricted Agency Project Management Controls
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Restricted Project Governance",
                            style = XMaxType.headline,
                            color = palette.textPrimary
                        )
                        StatusPill(
                            label = "${archivedProjects.size} archived",
                            containerColor = palette.surfaceMuted,
                            contentColor = palette.textSecondary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showArchivedOnly,
                            onClick = { showArchivedOnly = false },
                            label = { Text("Active (${activeProjects.size})", style = XMaxType.caption1Semibold) },
                            modifier = Modifier.testTag("admin_filter_active_projects")
                        )
                        FilterChip(
                            selected = showArchivedOnly,
                            onClick = { showArchivedOnly = true },
                            label = { Text("Archived (${archivedProjects.size})", style = XMaxType.caption1Semibold) },
                            modifier = Modifier.testTag("admin_filter_archived_projects")
                        )
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenTeamGovernance,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("admin_open_team_button")
                        ) {
                            Icon(Icons.Outlined.Group, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Team Roles", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onOpenCompanySettings,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("admin_open_settings_button")
                        ) {
                            Icon(Icons.Outlined.Business, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Agency Setup", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    if (completedProjects.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showCleanupConfirm = true },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.45f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("admin_cleanup_done_button")
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Cleanup ${completedProjects.size} Completed Project(s)",
                                style = XMaxType.buttonCompact
                            )
                        }
                    }
                }
            }
        }

        // 4. Project Lifecycle List
        if (displayedProjects.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.WorkOutline,
                    title = if (showArchivedOnly) "No archived projects" else "No active projects",
                    subtitle = if (showArchivedOnly) {
                        "Soft-deleted agency projects will appear here for restore or permanent removal."
                    } else {
                        "Accepted client quotes will create managed agency projects here."
                    }
                )
            }
        } else {
            items(displayedProjects, key = { "admin_proj_${it.id}" }) { proj ->
                val pDelivs = state.deliverables.filter { it.projectId == proj.id }
                val done = pDelivs.count { it.statusEnum() == DeliverableStatus.APPROVED }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_project_row_${proj.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = formatTitleCase(proj.title),
                                    style = XMaxType.headline,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatTitleCase(proj.clientCompanyName)} • $done/${pDelivs.size} deliverables • Due ${MoneyFormatter.formatInr(proj.outstandingPaise)}",
                                    style = XMaxType.footnote,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusPill(
                                label = if (proj.deleted) "Archived" else proj.statusEnum().label,
                                containerColor = if (proj.deleted) palette.amberBg else palette.skyBg,
                                contentColor = if (proj.deleted) palette.amberInk else palette.skyInk
                            )
                        }

                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onToggleDeleteProject(proj, !proj.deleted) },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("admin_toggle_archive_${proj.id}")
                            ) {
                                Icon(
                                    imageVector = if (proj.deleted) Icons.Outlined.RestoreFromTrash else Icons.Outlined.Archive,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (proj.deleted) "Restore" else "Archive",
                                    style = XMaxType.buttonCompact,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { projectPendingPermanentDelete = proj },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.blushBg,
                                    contentColor = CrimsonBlocked
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("admin_perm_delete_${proj.id}")
                            ) {
                                Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Delete Forever", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // 5. Team Role Audit Card
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
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
                        Text(
                            text = "Workspace Members & Role Audit (${sortedAuditMembers.size})",
                            style = XMaxType.headline,
                            color = palette.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenTeamGovernance) {
                            Text("View All", style = XMaxType.buttonCompact, color = palette.accentViolet)
                        }
                    }
                    Text(
                        text = "Only xdotmax@gmail.com holds auth role super_admin. All other workspace members resolve to standard_member.",
                        style = XMaxType.footnote,
                        color = palette.textSecondary
                    )
                    sortedAuditMembers.take(25).forEach { m ->
                        val privateEmail = privateByUid[m.uid]?.email
                            ?: if (m.uid == state.firebaseUser?.uid) state.verifiedEmail else ""
                        val authRole = AuthenticationRepository.verifyUserRole(
                            email = privateEmail,
                            uid = m.uid,
                            isAuthenticated = true
                        )
                        val memberStatus = m.statusEnum()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = formatTitleCase(m.fullName.ifBlank { m.requestedJobRole.ifBlank { "Member" } }),
                                    style = XMaxType.subheadlineSemibold,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = privateEmail.ifBlank {
                                    m.jobRoleName.ifBlank { m.requestedJobRole.ifBlank { m.uid } }
                                }
                                Text(
                                    text = "$subtitle • ${memberStatus.label}",
                                    style = XMaxType.caption1,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(
                                    label = if (memberStatus == MembershipStatus.PENDING) "Pending" else authRole.role.roleKey,
                                    containerColor = when {
                                        memberStatus == MembershipStatus.PENDING -> palette.butterBg
                                        authRole.isSuperAdmin -> palette.mintBg
                                        else -> palette.surfaceMuted
                                    },
                                    contentColor = when {
                                        memberStatus == MembershipStatus.PENDING -> palette.butterInk
                                        authRole.isSuperAdmin -> palette.mintInk
                                        else -> palette.textSecondary
                                    }
                                )
                                OutlinedButton(
                                    onClick = {
                                        editingMemberTarget = m to (if (memberStatus == MembershipStatus.PENDING) MembershipStatus.APPROVED else null)
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 40.dp)
                                        .testTag("admin_audit_configure_${m.uid}")
                                ) {
                                    Text(
                                        text = if (memberStatus == MembershipStatus.PENDING) "Approve" else "Edit",
                                        style = XMaxType.caption1Semibold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingMemberTarget?.let { (target, preferredStatus) ->
        MemberPermissionEditorDialog(
            state = state,
            target = target,
            initialStatus = preferredStatus,
            onSave = { st, lvl, dept, role, scopeType, scopeIds, perms ->
                onConfigureMember(target, st, lvl, dept, role, scopeType, scopeIds, perms)
                editingMemberTarget = null
            },
            onDismiss = { editingMemberTarget = null }
        )
    }

    projectPendingPermanentDelete?.let { proj ->
        AlertDialog(
            onDismissRequest = { projectPendingPermanentDelete = null },
            containerColor = palette.surfaceElevated,
            title = { Text("Permanently Delete Project?", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Text(
                    "This permanently erases '${proj.title}' and all its deliverables from Firestore. This action cannot be undone.",
                    style = XMaxType.subheadline,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        projectPendingPermanentDelete = null
                        onPermanentlyDeleteProject(proj)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Delete Forever", style = XMaxType.buttonCompact) }
            },
            dismissButton = {
                TextButton(onClick = { projectPendingPermanentDelete = null }) {
                    Text("Cancel", style = XMaxType.buttonCompact, color = palette.textPrimary)
                }
            }
        )
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            containerColor = palette.surfaceElevated,
            title = { Text("Cleanup Completed Projects?", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Text(
                    "Permanently delete ${completedProjects.size} completed or archived project(s) and their deliverables?",
                    style = XMaxType.subheadline,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        onCleanupOldDoneProjects()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Clean All", style = XMaxType.buttonCompact) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) {
                    Text("Cancel", style = XMaxType.buttonCompact, color = palette.textPrimary)
                }
            }
        )
    }
}

@Composable
private fun RestrictedAdminGateView(
    accessCheck: AdminAccessCheckResult,
    onBack: () -> Unit
) {
    val palette = XMaxTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.35f)),
            shadowElevation = if (XMaxTheme.isDark) 0.dp else 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .testTag("admin_dashboard_restricted_card")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(palette.blushBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Access Restricted",
                        tint = CrimsonBlocked,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "Super Admin Access Required",
                    style = XMaxType.secondaryTitle,
                    color = palette.textPrimary
                )
                Text(
                    text = accessCheck.reason,
                    style = XMaxType.subheadline,
                    color = palette.textSecondary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        label = accessCheck.currentEmail.ifBlank { "No Email" },
                        containerColor = palette.surfaceMuted,
                        contentColor = palette.textPrimary
                    )
                    StatusPill(
                        label = accessCheck.resolvedRole.roleKey,
                        containerColor = palette.blushBg,
                        contentColor = palette.blushInk
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.buttonPrimaryBg,
                        contentColor = palette.buttonPrimaryFg
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("admin_dashboard_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Return to Workspace", style = XMaxType.button)
                }
            }
        }
    }
}

@Composable
private fun BackendHealthDiagnosticsCard(
    report: BackendDiagnosticReport,
    onRunCheck: () -> Unit
) {
    val palette = XMaxTheme.colors
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backend_health_diagnostics_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.widthIn(min = 160.dp)) {
                    Text(
                        text = "Cloud & Backend Health",
                        style = XMaxType.headline,
                        color = palette.textPrimary
                    )
                    val checkedLabel = if (report.lastCheckedMillis > 0L) {
                        "Last check ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(report.lastCheckedMillis))}"
                    } else {
                        "Firebase • Cloudflare • Supabase"
                    }
                    Text(
                        text = checkedLabel,
                        style = XMaxType.caption1,
                        color = palette.textSecondary
                    )
                }
                Button(
                    onClick = onRunCheck,
                    enabled = !report.isRunning,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.buttonPrimaryBg,
                        contentColor = palette.buttonPrimaryFg
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("run_backend_diagnostics_button")
                ) {
                    if (report.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = palette.buttonPrimaryFg
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (report.isRunning) "Checking…" else "Run Live Check", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                }
            }

            listOf(report.firebase, report.cloudflare, report.supabase).forEach { svc ->
                DiagnosticServiceRow(status = svc)
            }
        }
    }
}

@Composable
private fun DiagnosticServiceRow(status: ServiceDiagnosticStatus) {
    val palette = XMaxTheme.colors
    val (pillBg, pillFg) = when (status.state) {
        ServiceHealthState.HEALTHY -> palette.mintBg to palette.mintInk
        ServiceHealthState.CHECKING -> palette.skyBg to palette.skyInk
        ServiceHealthState.NOT_CONFIGURED, ServiceHealthState.DEGRADED -> palette.amberBg to palette.amberInk
        ServiceHealthState.ERROR -> palette.blushBg to palette.blushInk
        ServiceHealthState.UNKNOWN -> palette.surfaceMuted to palette.textSecondary
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceSoft,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = status.serviceName,
                    style = XMaxType.subheadlineSemibold,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(
                    label = status.state.label,
                    containerColor = pillBg,
                    contentColor = pillFg
                )
            }
            Text(
                text = status.summary,
                style = XMaxType.footnote,
                color = palette.textPrimary
            )
            if (status.detail.isNotBlank()) {
                Text(
                    text = status.detail,
                    style = XMaxType.caption1,
                    color = palette.textSecondary
                )
            }
        }
    }
}

@Composable
private fun AdminStatTile(
    label: String,
    value: String,
    bg: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val palette = XMaxTheme.colors
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = bg,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = value,
                    style = XMaxType.numericHeadline,
                    color = palette.textPrimary
                )
                Text(
                    text = label,
                    style = XMaxType.caption2Medium,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = bg,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = value,
                    style = XMaxType.numericHeadline,
                    color = palette.textPrimary
                )
                Text(
                    text = label,
                    style = XMaxType.caption2Medium,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
