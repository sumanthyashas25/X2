package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.PrimaryTab
import com.example.SecondaryDestination
import com.example.data.UserRoleType
import com.example.domain.CatalogueSeedData
import com.example.model.AccessLevel
import com.example.model.AppPermission
import com.example.ui.AgencyUiState
import com.example.ui.theme.XMaxTheme
import com.example.ui.theme.XMaxType

enum class WorkspaceRoleTier(val label: String, val roleKey: String) {
    SUPER_ADMIN("Super Admin", "super_admin"),
    ADMIN("Admin / Manager", "admin"),
    STANDARD_MEMBER("Team Member", "standard_member")
}

data class WorkspaceUiPolicy(
    val roleTier: WorkspaceRoleTier,
    val isSuperAdmin: Boolean,
    val isAdminOrAbove: Boolean,
    val isStandardMember: Boolean,
    val canAccessAdminDashboard: Boolean,
    val canManageProjectsLifecycle: Boolean,
    val canManageTeamAndGovernance: Boolean,
    val canManageCommercialQuotes: Boolean,
    val canSeeCommercialModule: Boolean,
    val canSeeProjectsBoard: Boolean,
    val canAcceptOrDeclineRequests: Boolean,
    val canViewAnalyticsAndFinance: Boolean,
    val showAdminOverviewBanner: Boolean,
    val showAdminDrawerCounters: Boolean,
    val showStandardMemberFocusedHome: Boolean,
    val visiblePrimaryTabs: List<PrimaryTab>,
    val permittedSecondaryDestinations: List<SecondaryDestination>
) {
    companion object {
        fun fromState(state: AgencyUiState): WorkspaceUiPolicy {
            val verifiedEmail = state.verifiedEmail.trim()
            val hasSession = state.firebaseUser != null || state.verifiedUserRole.isAuthenticated
            val isSuper = if (hasSession) {
                CatalogueSeedData.isSuperAdminEmail(verifiedEmail) ||
                    state.verifiedUserRole.role == UserRoleType.SUPER_ADMIN
            } else {
                state.isSuperAdmin
            }

            val access = state.myMember?.accessLevelEnum() ?: AccessLevel.STAFF
            val isAdminMember = !isSuper && access == AccessLevel.ADMIN && state.isAdmin

            val tier = when {
                isSuper -> WorkspaceRoleTier.SUPER_ADMIN
                isAdminMember -> WorkspaceRoleTier.ADMIN
                else -> WorkspaceRoleTier.STANDARD_MEMBER
            }

            val isAdminOrAbove = tier == WorkspaceRoleTier.SUPER_ADMIN || tier == WorkspaceRoleTier.ADMIN
            val isStandardMember = tier == WorkspaceRoleTier.STANDARD_MEMBER

            val canManageCommercialQuotes = isSuper ||
                state.hasPerm(AppPermission.CREATE_QUOTATIONS) ||
                state.hasPerm(AppPermission.ISSUE_QUOTATIONS)

            val canSeeCommercialModule = isAdminOrAbove ||
                listOf(
                    AppPermission.MANAGE_CLIENTS,
                    AppPermission.CREATE_REQUESTS,
                    AppPermission.CREATE_QUOTATIONS,
                    AppPermission.ISSUE_QUOTATIONS,
                    AppPermission.VIEW_ALL_CLIENTS_AND_QUOTES
                ).any { state.hasPerm(it) }

            val canSeeProjectsBoard = isAdminOrAbove ||
                state.hasPerm(AppPermission.ASSIGN_WORK) ||
                state.hasPerm(AppPermission.APPROVE_DELIVERABLES)

            val canViewAnalyticsAndFinance = isAdminOrAbove ||
                state.hasPerm(AppPermission.VIEW_PAYMENTS) ||
                state.hasPerm(AppPermission.RECORD_PAYMENTS)

            val canManageProjectsLifecycle = isSuper

            val canAccessAdminDashboard = isSuper || isAdminMember

            val visibleTabs = buildList {
                if (isStandardMember) {
                    add(PrimaryTab.MY_WORK)
                    if (canSeeCommercialModule) add(PrimaryTab.REQUESTS)
                    add(PrimaryTab.CHAT)
                } else {
                    add(PrimaryTab.HOME)
                    add(PrimaryTab.REQUESTS)
                    add(PrimaryTab.CHAT)
                    add(PrimaryTab.MY_WORK)
                }
            }

            val permittedSecondary = buildList {
                if (canSeeProjectsBoard) add(SecondaryDestination.PROJECTS)
                add(SecondaryDestination.NOTIFICATIONS)
                if (canSeeCommercialModule) {
                    add(SecondaryDestination.CLIENTS)
                    add(SecondaryDestination.QUOTATIONS)
                }
                if (canSeeCommercialModule || state.hasPerm(AppPermission.EDIT_CATALOGUE_PRICES)) {
                    add(SecondaryDestination.CATALOGUE)
                }
                if (state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) {
                    add(SecondaryDestination.PAYMENTS)
                }
                if (canAccessAdminDashboard) {
                    add(SecondaryDestination.ADMIN_DASHBOARD)
                }
                if (isAdminOrAbove) {
                    add(SecondaryDestination.TEAM)
                    add(SecondaryDestination.ACTIVITY)
                }
                if (isSuper) {
                    add(SecondaryDestination.ORG_ROUTING)
                    add(SecondaryDestination.COMPANY_SETTINGS)
                }
            }

            return WorkspaceUiPolicy(
                roleTier = tier,
                isSuperAdmin = isSuper,
                isAdminOrAbove = isAdminOrAbove,
                isStandardMember = isStandardMember,
                canAccessAdminDashboard = canAccessAdminDashboard,
                canManageProjectsLifecycle = canManageProjectsLifecycle,
                canManageTeamAndGovernance = isAdminOrAbove,
                canManageCommercialQuotes = canManageCommercialQuotes,
                canSeeCommercialModule = canSeeCommercialModule,
                canSeeProjectsBoard = canSeeProjectsBoard,
                canAcceptOrDeclineRequests = state.hasPerm(AppPermission.ACCEPT_PROJECT) || state.hasPerm(AppPermission.DECLINE_PROJECT),
                canViewAnalyticsAndFinance = canViewAnalyticsAndFinance,
                showAdminOverviewBanner = canAccessAdminDashboard,
                showAdminDrawerCounters = isAdminOrAbove,
                showStandardMemberFocusedHome = isStandardMember,
                visiblePrimaryTabs = visibleTabs,
                permittedSecondaryDestinations = permittedSecondary
            )
        }
    }
}

/**
 * Conditional UI gate that renders [content] only when [visible] is true,
 * keeping restricted administrative components completely absent from standard members' view trees.
 */
@Composable
fun RoleGate(
    visible: Boolean,
    fallback: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (visible) {
        content()
    } else {
        fallback?.invoke()
    }
}

/**
 * Animated conditional wrapper for expandable admin/commercial sub-sections.
 */
@Composable
fun AnimatedRoleGate(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        content()
    }
}

/**
 * Gates administrative UI behind [WorkspaceUiPolicy], distinguishing Super Admin (`xdotmax@gmail.com`)
 * from standard agency team members.
 */
@Composable
fun AdminOnlyContent(
    policy: WorkspaceUiPolicy,
    requireSuperAdmin: Boolean = false,
    fallback: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val allowed = if (requireSuperAdmin) policy.isSuperAdmin else policy.isAdminOrAbove
    RoleGate(visible = allowed, fallback = fallback, content = content)
}

/**
 * Gates commercial quotation/client builder controls behind verified commercial permissions.
 */
@Composable
fun CommercialFeatureGate(
    policy: WorkspaceUiPolicy,
    fallback: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    RoleGate(visible = policy.canManageCommercialQuotes, fallback = fallback, content = content)
}

/**
 * Clean, distraction-free studio focus header shown to standard team members
 * in place of administrative dashboards and financial governance widgets.
 */
@Composable
fun StandardMemberCleanFocusCard(
    pendingTaskCount: Int,
    departmentName: String,
    modifier: Modifier = Modifier
) {
    val palette = XMaxTheme.colors
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
        modifier = modifier
            .fillMaxWidth()
            .testTag("standard_member_clean_focus_card")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.TaskAlt,
                contentDescription = "Team Workspace",
                tint = palette.mintInk,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Focused Workspace",
                    style = XMaxType.subheadlineSemibold,
                    color = palette.textPrimary
                )
                Text(
                    text = "${departmentName.ifBlank { "Studio" }} • $pendingTaskCount active task${if (pendingTaskCount == 1) "" else "s"}",
                    style = XMaxType.caption1,
                    color = palette.textSecondary
                )
            }
            StatusPill(
                label = "Team Member",
                containerColor = palette.mintBg,
                contentColor = palette.mintInk
            )
        }
    }
}
