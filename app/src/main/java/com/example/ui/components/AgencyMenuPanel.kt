package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.PrimaryTab
import com.example.SecondaryDestination
import com.example.data.CloudStorageSummary
import com.example.data.StorageLimitTier
import com.example.data.formatStorageBytes
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun AgencyMenuDrawerContent(
    state: AgencyUiState,
    visibleTabs: List<PrimaryTab>,
    activeTab: PrimaryTab,
    secondaryDest: SecondaryDestination?,
    permittedSecondary: List<SecondaryDestination>,
    canCreateQuote: Boolean,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    cloudStorageSummary: CloudStorageSummary = CloudStorageSummary(),
    isScanningStorage: Boolean = false,
    onChangeThemeMode: (AppThemeMode) -> Unit = {},
    onOpenStorageManager: () -> Unit = {},
    onRefreshStorage: () -> Unit = {},
    onSelectTab: (PrimaryTab) -> Unit,
    onSelectSecondary: (SecondaryDestination) -> Unit,
    onOpenNewQuote: () -> Unit,
    onOpenProfilePhoto: () -> Unit,
    onSignOut: () -> Unit,
    onCloseDrawer: () -> Unit,
    onOpenRequirementsQuote: (() -> Unit)? = null
) {
    val member = state.myMember ?: return
    val palette = XMaxTheme.colors
    val access = member.accessLevelEnum()
    val uiPolicy = remember(state) { WorkspaceUiPolicy.fromState(state) }
    val pendingMembersCount = state.members.count { it.statusEnum() == MembershipStatus.PENDING }
    val activeProjectsCount = state.projects.count { !it.deleted }
    val pendingRequestsCount = state.requests.count { it.decisionStatusEnum() == DecisionStatus.PENDING }
    val myPendingCount = state.deliverables.count {
        it.assignedToUid == member.uid && it.statusEnum() != DeliverableStatus.APPROVED
    }

    val adminSecondary = permittedSecondary.filter {
        it in setOf(
            SecondaryDestination.ADMIN_DASHBOARD,
            SecondaryDestination.TEAM,
            SecondaryDestination.ORG_ROUTING,
            SecondaryDestination.COMPANY_SETTINGS,
            SecondaryDestination.ACTIVITY
        )
    }
    val commercialSecondary = permittedSecondary.filter {
        it in setOf(
            SecondaryDestination.CLIENTS,
            SecondaryDestination.QUOTATIONS,
            SecondaryDestination.CATALOGUE,
            SecondaryDestination.PAYMENTS
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = palette.surface,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier
            .widthIn(min = 292.dp, max = 338.dp)
            .testTag("agency_menu_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                XMaxLogoImage(tint = palette.textPrimary, height = 16.dp)
                Surface(
                    onClick = onCloseDrawer,
                    shape = CircleShape,
                    color = palette.surfaceMuted,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("close_menu_drawer_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close Menu",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Surface(
                onClick = onOpenProfilePhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_profile_card"),
                shape = RoundedCornerShape(20.dp),
                color = palette.surfaceSoft,
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StudioAvatar(
                            name = member.fullName,
                            size = 46.dp,
                            borderColor = palette.surface,
                            photoBase64 = member.photoBase64,
                            showEditBadge = true,
                            onClick = onOpenProfilePhoto,
                            modifier = Modifier.testTag("drawer_profile_avatar")
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatTitleCase(member.fullName.ifBlank { "Member" }),
                                style = XMaxType.headline,
                                color = palette.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.firebaseUser?.email ?: state.firebaseUser?.phoneNumber ?: member.jobRoleName,
                                style = XMaxType.footnote,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Update Profile Photo",
                            tint = palette.accentViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            label = formatTitleCase(access.label),
                            containerColor = palette.buttonPrimaryBg,
                            contentColor = palette.buttonPrimaryFg
                        )
                        if (member.jobRoleName.isNotBlank()) {
                            StatusPill(
                                label = formatTitleCase(member.jobRoleName),
                                containerColor = palette.surfaceElevated,
                                contentColor = palette.textPrimary
                            )
                        }
                    }

                    AdminOnlyContent(policy = uiPolicy) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("drawer_admin_counters"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DrawerMiniCounter(
                                label = "Projects",
                                count = "$activeProjectsCount",
                                containerColor = palette.kpiSkyBg,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectSecondary(SecondaryDestination.PROJECTS) }
                            )
                            DrawerMiniCounter(
                                label = "Requests",
                                count = "$pendingRequestsCount",
                                containerColor = palette.kpiLavenderBg,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectTab(PrimaryTab.REQUESTS) }
                            )
                            DrawerMiniCounter(
                                label = "Approvals",
                                count = "$pendingMembersCount",
                                containerColor = if (pendingMembersCount > 0) palette.amberBg else palette.kpiMintBg,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectSecondary(SecondaryDestination.TEAM) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MenuSectionLabel("Workspace")
                val workspaceTabs = visibleTabs.filter { it != PrimaryTab.CHAT }
                workspaceTabs.forEach { tab ->
                    val badge = when (tab) {
                        PrimaryTab.CHAT -> null
                        PrimaryTab.REQUESTS -> pendingRequestsCount.takeIf { it > 0 }?.toString()
                        PrimaryTab.MY_WORK -> myPendingCount.takeIf { it > 0 }?.toString()
                        PrimaryTab.HOME -> null
                    }
                    MinimalSidebarRow(
                        title = formatTitleCase(tab.label),
                        icon = tab.inactiveIcon,
                        activeIcon = tab.icon,
                        selected = secondaryDest == null && activeTab == tab,
                        badgeText = badge,
                        modifier = Modifier.testTag("menu_tab_${tab.name.lowercase()}"),
                        onClick = { onSelectTab(tab) }
                    )
                }
                if (permittedSecondary.contains(SecondaryDestination.PROJECTS)) {
                    MinimalSidebarRow(
                        title = formatTitleCase(SecondaryDestination.PROJECTS.label),
                        icon = SecondaryDestination.PROJECTS.icon,
                        activeIcon = SecondaryDestination.PROJECTS.icon,
                        selected = secondaryDest == SecondaryDestination.PROJECTS,
                        badgeText = activeProjectsCount.takeIf { it > 0 }?.toString(),
                        modifier = Modifier.testTag("menu_dest_projects"),
                        onClick = { onSelectSecondary(SecondaryDestination.PROJECTS) }
                    )
                }

                if (permittedSecondary.contains(SecondaryDestination.NOTIFICATIONS)) {
                    MinimalSidebarRow(
                        title = formatTitleCase(SecondaryDestination.NOTIFICATIONS.label),
                        icon = SecondaryDestination.NOTIFICATIONS.icon,
                        activeIcon = SecondaryDestination.NOTIFICATIONS.icon,
                        selected = secondaryDest == SecondaryDestination.NOTIFICATIONS,
                        badgeText = state.unreadNotificationCount.takeIf { it > 0 }?.toString(),
                        badgeAlert = state.unreadNotificationCount > 0,
                        modifier = Modifier.testTag("menu_dest_notifications"),
                        onClick = { onSelectSecondary(SecondaryDestination.NOTIFICATIONS) }
                    )
                }

                AdminOnlyContent(policy = uiPolicy) {
                    if (adminSecondary.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        MenuSectionLabel(
                            if (uiPolicy.isSuperAdmin) "Super Admin Controls" else "Admin Controls"
                        )
                        adminSecondary.forEach { dest ->
                            val badge = if (dest == SecondaryDestination.TEAM && pendingMembersCount > 0) {
                                "$pendingMembersCount"
                            } else null
                            MinimalSidebarRow(
                                title = formatTitleCase(dest.label),
                                icon = dest.icon,
                                activeIcon = dest.icon,
                                selected = secondaryDest == dest,
                                badgeText = badge,
                                badgeAlert = dest == SecondaryDestination.TEAM && pendingMembersCount > 0,
                                modifier = Modifier.testTag("menu_dest_${dest.name.lowercase()}"),
                                onClick = { onSelectSecondary(dest) }
                            )
                        }
                    }
                }

                RoleGate(visible = canCreateQuote || commercialSecondary.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MenuSectionLabel("Commercial & Finance")
                    if (canCreateQuote) {
                        if (onOpenRequirementsQuote != null) {
                            MinimalSidebarRow(
                                title = "Quote From Brief",
                                icon = Icons.Outlined.AutoAwesome,
                                activeIcon = Icons.Filled.AutoAwesome,
                                selected = false,
                                modifier = Modifier.testTag("menu_requirements_quote_button"),
                                onClick = onOpenRequirementsQuote
                            )
                        }
                        MinimalSidebarRow(
                            title = "New Quote",
                            icon = Icons.Filled.AddCircleOutline,
                            activeIcon = Icons.Filled.AddCircle,
                            selected = false,
                            modifier = Modifier.testTag("menu_new_quote_button"),
                            onClick = onOpenNewQuote
                        )
                    }
                    commercialSecondary.forEach { dest ->
                        val badge = when (dest) {
                            SecondaryDestination.CLIENTS -> state.clients.size.takeIf { it > 0 }?.toString()
                            SecondaryDestination.QUOTATIONS -> state.quotations.size.takeIf { it > 0 }?.toString()
                            else -> null
                        }
                        MinimalSidebarRow(
                            title = dest.label,
                            icon = dest.icon,
                            activeIcon = dest.icon,
                            selected = secondaryDest == dest,
                            badgeText = badge,
                            modifier = Modifier.testTag("menu_dest_${dest.name.lowercase()}"),
                            onClick = { onSelectSecondary(dest) }
                        )
                    }
                }

                // Appearance / Dark Mode Selector
                Spacer(Modifier.height(10.dp))
                MenuSectionLabel("Appearance")
                DrawerAppearanceCard(
                    themeMode = themeMode,
                    onChangeThemeMode = onChangeThemeMode
                )

                AdminOnlyContent(policy = uiPolicy, requireSuperAdmin = true) {
                    // Cloud Storage Tracker & Cleanup Card (Super Admin only)
                    Spacer(Modifier.height(10.dp))
                    MenuSectionLabel("Cloud Storage & Free Limits")
                    DrawerCloudStorageCard(
                        summary = cloudStorageSummary,
                        isScanning = isScanningStorage,
                        onOpenManager = onOpenStorageManager,
                        onRefresh = onRefreshStorage
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = palette.outline)
            Spacer(Modifier.height(8.dp))

            // Clean Log Out row at bottom
            Surface(
                onClick = onSignOut,
                shape = RoundedCornerShape(16.dp),
                color = palette.canvas,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("menu_sign_out_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(palette.blushBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Log out",
                            tint = palette.blushInk,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Text(
                        text = "Log Out (${member.accessLevelEnum().label})",
                        style = XMaxType.buttonCompact,
                        color = palette.blushInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerAppearanceCard(
    themeMode: AppThemeMode,
    onChangeThemeMode: (AppThemeMode) -> Unit
) {
    val context = LocalContext.current
    val palette = XMaxTheme.colors
    var showCustomizer by remember { mutableStateOf(false) }

    val accent by AppAppearanceStore.accentColorFlow.collectAsState()
    val buttonTheme by AppAppearanceStore.buttonThemeFlow.collectAsState()
    val navHighlight by AppAppearanceStore.navHighlightFlow.collectAsState()
    val dashboardTint by AppAppearanceStore.dashboardTintFlow.collectAsState()
    val appIconTheme by AppAppearanceStore.appIconThemeFlow.collectAsState()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.surfaceSoft,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("menu_appearance_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (XMaxTheme.isDark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                        contentDescription = null,
                        tint = palette.accentViolet,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Appearance & Theme",
                        style = XMaxType.subheadlineSemibold,
                        color = palette.textPrimary
                    )
                }

                IconButton(
                    onClick = { showCustomizer = !showCustomizer },
                    modifier = Modifier.size(32.dp).testTag("toggle_theme_customizer_button")
                ) {
                    Icon(
                        imageVector = if (showCustomizer) Icons.Default.ExpandLess else Icons.Default.Tune,
                        contentDescription = "Customize Theme",
                        tint = palette.accentViolet,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Mode Toggle (Auto / Light / Dark)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val selected = themeMode == mode
                    val icon = when (mode) {
                        AppThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                        AppThemeMode.LIGHT -> Icons.Outlined.LightMode
                        AppThemeMode.DARK -> Icons.Outlined.DarkMode
                    }
                    Surface(
                        onClick = { onChangeThemeMode(mode) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) palette.buttonPrimaryBg else palette.surfaceElevated,
                        border = if (selected) null else BorderStroke(1.dp, palette.outline),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("menu_theme_${mode.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = mode.label,
                                tint = if (selected) palette.buttonPrimaryFg else palette.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = mode.label,
                                style = XMaxType.caption1Semibold,
                                color = if (selected) palette.buttonPrimaryFg else palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Expanded Theme Customizer Studio
            if (showCustomizer) {
                HorizontalDivider(color = palette.outline.copy(alpha = 0.6f))

                // 1. Accent & Brand Color
                Text("Brand Accent", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppAccentColor.entries.forEach { acc ->
                        val isSel = accent == acc
                        val swatchColor = if (XMaxTheme.isDark) acc.darkPrimary else acc.lightPrimary
                        Surface(
                            onClick = { AppAppearanceStore.setAccentColor(context, acc) },
                            shape = CircleShape,
                            color = swatchColor,
                            border = if (isSel) BorderStroke(2.dp, palette.textPrimary) else null,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("theme_accent_${acc.name.lowercase()}")
                        ) {
                            if (isSel) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = acc.label,
                                        tint = PureWhite,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Navigation Highlight Color
                Text("Navigation Highlight", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppNavHighlight.entries.forEach { nh ->
                        val isSel = navHighlight == nh
                        FilterChip(
                            selected = isSel,
                            onClick = { AppAppearanceStore.setNavHighlight(context, nh) },
                            label = { Text(nh.label, style = XMaxType.caption2Medium) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("theme_nav_${nh.name.lowercase()}")
                        )
                    }
                }

                // 3. Button Color & Style
                Text("Buttons Color & Style", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppButtonTheme.entries.forEach { bt ->
                        val isSel = buttonTheme == bt
                        FilterChip(
                            selected = isSel,
                            onClick = { AppAppearanceStore.setButtonTheme(context, bt) },
                            label = { Text(bt.label, style = XMaxType.caption2Medium) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("theme_btn_${bt.name.lowercase()}")
                        )
                    }
                }

                // 4. Dashboard Accent Tint
                Text("Dashboard Cards Style", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppDashboardTint.entries.forEach { dt ->
                        val isSel = dashboardTint == dt
                        FilterChip(
                            selected = isSel,
                            onClick = { AppAppearanceStore.setDashboardTint(context, dt) },
                            label = { Text(dt.label, style = XMaxType.caption2Medium) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("theme_dash_${dt.name.lowercase()}")
                        )
                    }
                }

                // 5. App Icon Theme
                Text("App Icon Monogram", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppIconTheme.entries.forEach { ai ->
                        val isSel = appIconTheme == ai
                        Surface(
                            onClick = { AppAppearanceStore.setAppIconTheme(context, ai) },
                            shape = RoundedCornerShape(12.dp),
                            color = palette.surfaceElevated,
                            border = BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) palette.accentViolet else palette.outline),
                            modifier = Modifier.testTag("theme_icon_${ai.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ai.badgeColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("X", style = XMaxType.caption2Bold, color = PureWhite)
                                }
                                Text(ai.label, style = XMaxType.caption2Medium, color = palette.textPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerCloudStorageCard(
    summary: CloudStorageSummary,
    isScanning: Boolean,
    onOpenManager: () -> Unit,
    onRefresh: () -> Unit
) {
    val palette = XMaxTheme.colors
    val health = summary.overallHealth
    val (barColor, badgeBg, badgeFg, badgeLabel) = when (health) {
        StorageLimitTier.CRITICAL -> DrawerStorageTone(CrimsonBlocked, palette.blushBg, palette.blushInk, "Near Limit!")
        StorageLimitTier.WARNING -> DrawerStorageTone(WarmAlarmAmber, palette.amberBg, palette.amberInk, "Moderate")
        StorageLimitTier.SAFE -> DrawerStorageTone(EmeraldApproved, palette.mintBg, palette.mintInk, "Safe")
    }
    val pctText = String.format(Locale.US, "%.0f%%", summary.primaryFraction * 100f)

    Surface(
        onClick = onOpenManager,
        shape = RoundedCornerShape(18.dp),
        color = palette.surfaceSoft,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("menu_cloud_storage_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudQueue,
                        contentDescription = null,
                        tint = badgeFg,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = summary.primaryLabel,
                        style = XMaxType.caption1Semibold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
                StatusPill(label = badgeLabel, containerColor = badgeBg, contentColor = badgeFg)
            }

            LinearProgressIndicator(
                progress = { summary.primaryFraction.coerceAtLeast(0.02f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = barColor,
                trackColor = palette.surfaceMuted
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatStorageBytes(summary.primaryUsedBytes)} / ${formatStorageBytes(summary.primaryQuotaBytes)}",
                    style = XMaxType.caption1Medium.tabular(),
                    color = palette.textSecondary
                )
                Text(
                    text = if (isScanning) "Scanning…" else "$pctText used",
                    style = XMaxType.caption1Semibold.tabular(),
                    color = badgeFg
                )
            }

            if (summary.expiredStatusCount > 0) {
                Text(
                    text = "${summary.expiredStatusCount} expired status${if (summary.expiredStatusCount == 1) "" else "es"} ready to clean",
                    style = XMaxType.caption1,
                    color = palette.amberInk
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onOpenManager,
                    shape = RoundedCornerShape(12.dp),
                    color = palette.buttonPrimaryBg,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("menu_manage_storage_button")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            tint = palette.buttonPrimaryFg,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Manage & Delete",
                            style = XMaxType.caption1Semibold,
                            color = palette.buttonPrimaryFg
                        )
                    }
                }
                Surface(
                    onClick = onRefresh,
                    enabled = !isScanning,
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("menu_refresh_storage_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = palette.accentViolet
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh Storage",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class DrawerStorageTone(
    val bar: Color,
    val bg: Color,
    val fg: Color,
    val label: String
)

@Composable
private fun DrawerMiniCounter(
    label: String,
    count: String,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count,
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

@Composable
private fun MenuSectionLabel(title: String) {
    val palette = XMaxTheme.colors
    Text(
        text = formatTitleCase(title),
        style = XMaxType.caption1Medium,
        color = palette.textSecondary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun MinimalSidebarRow(
    title: String,
    icon: ImageVector,
    activeIcon: ImageVector,
    selected: Boolean,
    badgeText: String? = null,
    badgeAlert: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) palette.surfaceSoft else Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (selected) palette.buttonPrimaryBg else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) activeIcon else icon,
                    contentDescription = null,
                    tint = if (selected) palette.buttonPrimaryFg else palette.textSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Text(
                text = title,
                style = if (selected) XMaxType.subheadlineSemibold else XMaxType.subheadlineMedium,
                color = if (selected) palette.textPrimary else palette.iconInactive,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!badgeText.isNullOrBlank()) {
                StatusPill(
                    label = badgeText,
                    containerColor = if (badgeAlert) palette.blushBg else palette.surfaceMuted,
                    contentColor = if (badgeAlert) palette.blushInk else palette.textPrimary
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.buttonPrimaryBg)
                )
            }
        }
    }
}
