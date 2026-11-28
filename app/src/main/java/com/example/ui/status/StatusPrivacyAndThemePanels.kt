package com.example.ui.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chat.ChatUserPreferences
import com.example.chat.StatusCircleColorCatalog
import com.example.chat.StatusCircleColorPreset
import com.example.model.AccessLevel
import com.example.model.Member
import com.example.model.StatusPrivacyMode
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*

private data class AudienceBadgePreset(
    val emoji: String,
    val label: String
)

private val AUDIENCE_BADGE_PRESETS = listOf(
    AudienceBadgePreset("💜", "Close friends"),
    AudienceBadgePreset("⭐", "VIP Clients"),
    AudienceBadgePreset("🎨", "Creative Leads"),
    AudienceBadgePreset("🚀", "Core Team")
)

/**
 * Redesigned Status Privacy changing panel inspired by modern audience-preview sheets:
 * audience radio cards at top + elevated preview card with ringed avatar, pill badge,
 * 3 icon-highlighted privacy rules, inline member checklist, and primary emerald CTA.
 */
@Composable
fun StatusPrivacyPanelDialog(
    myMember: Member?,
    allMembers: List<Member>,
    initialMode: StatusPrivacyMode,
    initialAllowedUids: Set<String>,
    initialExcludedUids: Set<String>,
    initialAudienceLabel: String = "Close friends",
    initialAudienceEmoji: String = "💜",
    prefs: ChatUserPreferences = ChatUserPreferences(),
    onSave: (
        mode: StatusPrivacyMode,
        allowedUids: Set<String>,
        excludedUids: Set<String>,
        audienceLabel: String,
        audienceEmoji: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var allowed by remember(initialAllowedUids) { mutableStateOf(initialAllowedUids) }
    var excluded by remember(initialExcludedUids) { mutableStateOf(initialExcludedUids) }
    var audienceLabel by remember(initialAudienceLabel) { mutableStateOf(initialAudienceLabel.ifBlank { "Close friends" }) }
    var audienceEmoji by remember(initialAudienceEmoji) { mutableStateOf(initialAudienceEmoji.ifBlank { "💜" }) }
    var memberQuery by remember { mutableStateOf("") }

    val filteredPeers = remember(allMembers, memberQuery) {
        val q = memberQuery.trim().lowercase()
        if (q.isBlank()) allMembers
        else allMembers.filter {
            it.fullName.lowercase().contains(q) ||
                it.jobRoleName.lowercase().contains(q) ||
                it.departmentName.lowercase().contains(q)
        }
    }

    val ringPrimaryColor = when (mode) {
        StatusPrivacyMode.SELECTED_ONLY -> Color(0xFFA855F7)
        StatusPrivacyMode.ALL_EXCEPT -> Color(0xFFF59E0B)
        StatusPrivacyMode.ALL_APPROVED -> Color(prefs.workspaceStatusUnviewedHex)
    }
    val ringAccentColor = when (mode) {
        StatusPrivacyMode.SELECTED_ONLY -> Color(0xFFEC4899)
        StatusPrivacyMode.ALL_EXCEPT -> Color(0xFFF97316)
        StatusPrivacyMode.ALL_APPROVED -> Color(prefs.workspaceStatusAccentHex)
    }
    val ringUsesGradient = mode == StatusPrivacyMode.ALL_APPROVED && prefs.workspaceStatusGradientEnabled

    val badgeEmoji = when (mode) {
        StatusPrivacyMode.ALL_APPROVED -> "🌐"
        StatusPrivacyMode.SELECTED_ONLY -> audienceEmoji.ifBlank { "💜" }
        StatusPrivacyMode.ALL_EXCEPT -> "🙈"
    }
    val badgeTitle = when (mode) {
        StatusPrivacyMode.ALL_APPROVED -> "All approved team"
        StatusPrivacyMode.SELECTED_ONLY -> audienceLabel.ifBlank { "Close friends" }
        StatusPrivacyMode.ALL_EXCEPT -> "Except ${excluded.size} hidden"
    }

    val sheetContainerColor = Color(0xFF15191C)
    val sheetSurfaceMuted = Color(0xFF1F252A)
    val sheetTextPrimary = Color(0xFFF8FAFC)
    val sheetTextSecondary = Color(0xFF94A3B8)
    val vibrantGreen = Color(0xFF22C55E)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0C1013),
            contentColor = sheetTextPrimary,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("status_privacy_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("status_privacy_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = sheetTextPrimary
                        )
                    }
                    Text(
                        text = "Status privacy",
                        style = XMaxType.secondaryTitle,
                        color = sheetTextPrimary
                    )
                }

                // Top Audience Options Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Audience who can see my status",
                        style = XMaxType.footnoteSemibold,
                        color = sheetTextSecondary
                    )

                    StatusPrivacyMode.entries.forEach { m ->
                        val selected = mode == m
                        val optionIcon = when (m) {
                            StatusPrivacyMode.ALL_APPROVED -> Icons.Outlined.PersonOutline
                            StatusPrivacyMode.SELECTED_ONLY -> Icons.Default.Favorite
                            StatusPrivacyMode.ALL_EXCEPT -> Icons.Outlined.VisibilityOff
                        }
                        val optionLabel = when (m) {
                            StatusPrivacyMode.ALL_APPROVED -> "My team contacts"
                            StatusPrivacyMode.SELECTED_ONLY -> "$audienceLabel (${allowed.size})"
                            StatusPrivacyMode.ALL_EXCEPT -> "My contacts except… (${excluded.size})"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) Color(0xFF1B2329) else Color.Transparent
                                )
                                .clickable { mode = m }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .testTag("status_privacy_mode_${m.name.lowercase()}")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (selected) ringPrimaryColor.copy(alpha = 0.2f) else sheetSurfaceMuted,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = optionIcon,
                                        contentDescription = null,
                                        tint = if (selected) ringPrimaryColor else sheetTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = optionLabel,
                                    style = XMaxType.headline,
                                    color = sheetTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = m.subtitle,
                                    style = XMaxType.caption1,
                                    color = sheetTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            RadioButton(
                                selected = selected,
                                onClick = { mode = m },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = vibrantGreen,
                                    unselectedColor = sheetTextSecondary
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Signature Elevated Preview + Customization Sheet Panel
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = sheetContainerColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top Drag Handle Pill
                        Box(
                            modifier = Modifier
                                .width(38.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.25f))
                        )

                        Spacer(Modifier.height(14.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Centered Ringed Avatar + Overlapping Badge Pill
                            Box(
                                contentAlignment = Alignment.TopCenter,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(90.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val strokePx = 3.5.dp.toPx()
                                        val inset = strokePx / 2f
                                        val arcSize = Size(size.width - strokePx, size.height - strokePx)
                                        if (ringUsesGradient) {
                                            drawArc(
                                                brush = Brush.sweepGradient(
                                                    listOf(ringPrimaryColor, ringAccentColor, ringPrimaryColor)
                                                ),
                                                startAngle = 0f,
                                                sweepAngle = 360f,
                                                useCenter = false,
                                                topLeft = Offset(inset, inset),
                                                size = arcSize,
                                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                            )
                                        } else {
                                            drawArc(
                                                color = ringPrimaryColor,
                                                startAngle = 0f,
                                                sweepAngle = 360f,
                                                useCenter = false,
                                                topLeft = Offset(inset, inset),
                                                size = arcSize,
                                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                            )
                                        }
                                    }
                                    StudioAvatar(
                                        name = myMember?.fullName ?: "You",
                                        size = 78.dp,
                                        borderColor = sheetContainerColor,
                                        photoBase64 = myMember?.photoBase64.orEmpty()
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = sheetSurfaceMuted,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .padding(top = 74.dp)
                                        .testTag("status_privacy_preview_badge")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(badgeEmoji, style = XMaxType.footnote)
                                        Text(
                                            text = badgeTitle,
                                            style = XMaxType.footnoteSemibold,
                                            color = sheetTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Headline
                            Text(
                                text = when (mode) {
                                    StatusPrivacyMode.SELECTED_ONLY ->
                                        "You can now share your status with ${audienceLabel.lowercase()}"
                                    StatusPrivacyMode.ALL_APPROVED ->
                                        "Share your status with all approved team members"
                                    StatusPrivacyMode.ALL_EXCEPT ->
                                        "Share updates while hiding from selected contacts"
                                },
                                style = XMaxType.secondaryTitle,
                                color = sheetTextPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )

                            // 3 Clean Icon Highlight Rows (matching screenshot)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                PrivacyHighlightRow(
                                    icon = Icons.Outlined.LocalOffer,
                                    text = "A status audience with a custom name and emoji",
                                    tint = sheetTextSecondary,
                                    textColor = sheetTextPrimary
                                )
                                PrivacyHighlightRow(
                                    icon = Icons.Outlined.Visibility,
                                    text = "Your audience will see the name and emoji when they view your status",
                                    tint = sheetTextSecondary,
                                    textColor = sheetTextPrimary
                                )
                                PrivacyHighlightRow(
                                    icon = Icons.Outlined.VisibilityOff,
                                    text = "They won't see who else is in your audience",
                                    tint = sheetTextSecondary,
                                    textColor = sheetTextPrimary
                                )
                            }

                            // Custom Audience Badge Presets when SELECTED_ONLY is active
                            AnimatedVisibility(visible = mode == StatusPrivacyMode.SELECTED_ONLY) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Audience badge preset",
                                        style = XMaxType.caption1Semibold,
                                        color = sheetTextSecondary
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AUDIENCE_BADGE_PRESETS.forEach { preset ->
                                            val active = audienceLabel == preset.label && audienceEmoji == preset.emoji
                                            Surface(
                                                onClick = {
                                                    audienceLabel = preset.label
                                                    audienceEmoji = preset.emoji
                                                },
                                                shape = RoundedCornerShape(999.dp),
                                                color = if (active) ringPrimaryColor.copy(alpha = 0.25f) else sheetSurfaceMuted,
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (active) ringPrimaryColor else Color.White.copy(alpha = 0.1f)
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                ) {
                                                    Text(preset.emoji, style = XMaxType.footnote)
                                                    Text(preset.label, style = XMaxType.caption1Semibold, color = sheetTextPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Member Picker Checklist for SELECTED_ONLY / ALL_EXCEPT
                            AnimatedVisibility(visible = mode != StatusPrivacyMode.ALL_APPROVED) {
                                val activeSet = if (mode == StatusPrivacyMode.SELECTED_ONLY) allowed else excluded
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(sheetSurfaceMuted)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (mode == StatusPrivacyMode.SELECTED_ONLY) {
                                                "Select viewers (${activeSet.size})"
                                            } else {
                                                "Hide status from (${activeSet.size})"
                                            },
                                            style = XMaxType.caption1Semibold.tabular(),
                                            color = sheetTextPrimary
                                        )
                                        TextButton(
                                            onClick = {
                                                val allUids = allMembers.map { it.uid }.toSet()
                                                if (mode == StatusPrivacyMode.SELECTED_ONLY) {
                                                    allowed = if (allowed.size == allUids.size) emptySet() else allUids
                                                } else {
                                                    excluded = if (excluded.isNotEmpty()) emptySet() else allUids
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                text = if (activeSet.isEmpty()) "Select all" else "Clear",
                                                style = XMaxType.caption1Semibold,
                                                color = vibrantGreen
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = memberQuery,
                                        onValueChange = { memberQuery = it },
                                        singleLine = true,
                                        placeholder = {
                                            Text("Search team members…", style = XMaxType.caption1, color = sheetTextSecondary)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Search, contentDescription = null, tint = sheetTextSecondary, modifier = Modifier.size(18.dp))
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = sheetTextPrimary,
                                            unfocusedTextColor = sheetTextPrimary,
                                            focusedBorderColor = vibrantGreen,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.14f)
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 190.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(filteredPeers, key = { it.uid }) { mem ->
                                            val isChecked = mem.uid in activeSet
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        if (mode == StatusPrivacyMode.SELECTED_ONLY) {
                                                            allowed = if (isChecked) allowed - mem.uid else allowed + mem.uid
                                                        } else {
                                                            excluded = if (isChecked) excluded - mem.uid else excluded + mem.uid
                                                        }
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                            ) {
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = {
                                                        if (mode == StatusPrivacyMode.SELECTED_ONLY) {
                                                            allowed = if (isChecked) allowed - mem.uid else allowed + mem.uid
                                                        } else {
                                                            excluded = if (isChecked) excluded - mem.uid else excluded + mem.uid
                                                        }
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = vibrantGreen,
                                                        uncheckedColor = sheetTextSecondary
                                                    )
                                                )
                                                StudioAvatar(
                                                    name = mem.fullName,
                                                    size = 32.dp,
                                                    borderColor = sheetSurfaceMuted,
                                                    photoBase64 = mem.photoBase64
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = formatTitleCase(mem.fullName),
                                                        style = XMaxType.footnoteSemibold,
                                                        color = sheetTextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = mem.jobRoleName.ifBlank { mem.departmentName },
                                                        style = XMaxType.caption2Medium,
                                                        color = sheetTextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Vibrant Green Bottom CTA Button (matching screenshot)
                        Button(
                            onClick = {
                                onSave(
                                    mode,
                                    allowed,
                                    excluded,
                                    audienceLabel.ifBlank { "Close friends" },
                                    audienceEmoji.ifBlank { "💜" }
                                )
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = vibrantGreen,
                                contentColor = Color(0xFF052E16)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("status_privacy_save_btn")
                        ) {
                            Text(
                                text = "Continue",
                                style = XMaxType.button.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyHighlightRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
    textColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = text,
            style = XMaxType.subheadline,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Unified Status Settings panel opened from the single Settings icon in the Status header.
 * Houses Status Privacy, Blocked Contacts, and (exclusively for Super Admin) Future Status Circle Colors.
 */
@Composable
fun StatusSettingsDialog(
    member: Member,
    prefs: ChatUserPreferences,
    onOpenPrivacy: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenStatusCircleColors: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    val isSuperAdmin = member.accessLevelEnum() == AccessLevel.SUPER_ADMIN
    val currentMode = remember(prefs.statusPrivacyMode) {
        runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
            .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
    }
    val privacySummary = when (currentMode) {
        StatusPrivacyMode.ALL_APPROVED -> "All approved team members"
        StatusPrivacyMode.SELECTED_ONLY -> "${prefs.statusAudienceEmoji} ${prefs.statusAudienceLabel} (${prefs.statusAllowedUids.size})"
        StatusPrivacyMode.ALL_EXCEPT -> "Hidden from ${prefs.statusExcludedUids.size} contacts"
    }
    val activePreset = remember(prefs.workspaceStatusCircleColorId) {
        StatusCircleColorCatalog.findById(prefs.workspaceStatusCircleColorId)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.outline),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("status_settings_sheet")
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Status Settings",
                            style = XMaxType.secondaryTitle,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Privacy, safety & display controls",
                            style = XMaxType.footnote,
                            color = palette.textSecondary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Settings", tint = palette.textSecondary)
                    }
                }

                // Option 1: Status Privacy
                StatusSettingsOptionRow(
                    icon = Icons.Outlined.Lock,
                    title = "Status Privacy",
                    subtitle = privacySummary,
                    accentColor = VioletPrimary,
                    testTag = "status_privacy_btn",
                    onClick = {
                        onDismiss()
                        onOpenPrivacy()
                    }
                )

                // Option 2: Blocked Contacts
                StatusSettingsOptionRow(
                    icon = Icons.Outlined.Block,
                    title = "Blocked Contacts",
                    subtitle = if (prefs.blockedUserUids.isEmpty()) "No blocked contacts" else "${prefs.blockedUserUids.size} blocked from your statuses",
                    accentColor = CrimsonBlocked,
                    testTag = "status_blocked_users_btn",
                    onClick = {
                        onDismiss()
                        onOpenBlockedUsers()
                    }
                )

                // Option 3: Future Status Circle Colors — STRICTLY SUPER ADMIN ONLY
                if (isSuperAdmin) {
                    StatusSettingsOptionRow(
                        icon = Icons.Outlined.Palette,
                        title = "Future Status Circle Colors",
                        subtitle = "${activePreset.name} • Workspace-wide ring theme",
                        accentColor = Color(activePreset.unviewedHex),
                        badgeText = "Super Admin",
                        testTag = "status_circle_color_btn",
                        trailingPreview = {
                            StatusRingPreviewSwatch(
                                unviewedColor = Color(prefs.workspaceStatusUnviewedHex),
                                accentColor = Color(prefs.workspaceStatusAccentHex),
                                gradientEnabled = prefs.workspaceStatusGradientEnabled,
                                sizeDp = 24
                            )
                        },
                        onClick = {
                            onDismiss()
                            onOpenStatusCircleColors()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusSettingsOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    badgeText: String? = null,
    testTag: String,
    trailingPreview: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.14f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = XMaxType.headline,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (badgeText != null) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = palette.softVioletBg
                        ) {
                            Text(
                                text = badgeText,
                                style = XMaxType.caption2Medium,
                                color = VioletPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = XMaxType.caption1,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailingPreview != null) {
                trailingPreview()
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = palette.textSecondary
            )
        }
    }
}

/**
 * Super Admin exclusive dialog to customize workspace-wide Status Circle ring colors & future gradient styles.
 */
@Composable
fun SuperAdminStatusCircleColorDialog(
    member: Member,
    activeColorId: String,
    onSelectPreset: (StatusCircleColorPreset) -> Unit,
    onDismiss: () -> Unit
) {
    if (member.accessLevelEnum() != AccessLevel.SUPER_ADMIN) return

    val palette = XMaxTheme.colors
    var selectedPreset by remember(activeColorId) {
        mutableStateOf(StatusCircleColorCatalog.findById(activeColorId))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.outline),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .testTag("status_circle_color_dialog")
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Status Circle Colors",
                            style = XMaxType.secondaryTitle,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Super Admin control for all team status rings",
                            style = XMaxType.footnote,
                            color = palette.textSecondary
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.softVioletBg
                    ) {
                        Text(
                            text = "Super Admin",
                            style = XMaxType.caption2Medium,
                            color = VioletPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Live Preview Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokePx = 3.2.dp.toPx()
                                val inset = strokePx / 2f
                                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                                val c1 = Color(selectedPreset.unviewedHex)
                                val c2 = Color(selectedPreset.accentHex)
                                val gap = 14f
                                val sweep = (360f - gap * 3f) / 3f
                                repeat(3) { idx ->
                                    val start = -90f + idx * (sweep + gap)
                                    if (selectedPreset.gradientEnabled) {
                                        drawArc(
                                            brush = Brush.sweepGradient(listOf(c1, c2, c1)),
                                            startAngle = start,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            topLeft = Offset(inset, inset),
                                            size = arcSize,
                                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                        )
                                    } else {
                                        drawArc(
                                            color = c1,
                                            startAngle = start,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            topLeft = Offset(inset, inset),
                                            size = arcSize,
                                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                        )
                                    }
                                }
                            }
                            StudioAvatar(
                                name = member.fullName,
                                size = 50.dp,
                                borderColor = palette.surface,
                                photoBase64 = member.photoBase64
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedPreset.name,
                                style = XMaxType.headline,
                                color = palette.textPrimary
                            )
                            Text(
                                text = selectedPreset.subtitle,
                                style = XMaxType.caption1,
                                color = palette.textSecondary
                            )
                        }
                    }
                }

                // Scrollable Preset List (Classic + Future Gradient Rings)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(StatusCircleColorCatalog.presets, key = { it.id }) { preset ->
                        val isSelected = selectedPreset.id == preset.id
                        Surface(
                            onClick = { selectedPreset = preset },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) palette.softVioletBg else palette.surfaceMuted,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) VioletPrimary else palette.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("circle_color_preset_${preset.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StatusRingPreviewSwatch(
                                    unviewedColor = Color(preset.unviewedHex),
                                    accentColor = Color(preset.accentHex),
                                    gradientEnabled = preset.gradientEnabled,
                                    sizeDp = 30
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = preset.name,
                                            style = XMaxType.subheadlineSemibold,
                                            color = palette.textPrimary
                                        )
                                        if (preset.isFutureStyle) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = Color(preset.unviewedHex).copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = "FUTURE",
                                                    style = XMaxType.caption2Medium,
                                                    color = Color(preset.unviewedHex),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = preset.subtitle,
                                        style = XMaxType.caption1,
                                        color = palette.textSecondary
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = VioletPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", style = XMaxType.buttonCompact)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSelectPreset(selectedPreset) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.buttonPrimaryBg,
                            contentColor = palette.buttonPrimaryFg
                        ),
                        modifier = Modifier.testTag("apply_status_circle_color_btn")
                    ) {
                        Text("Apply to Workspace", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRingPreviewSwatch(
    unviewedColor: Color,
    accentColor: Color,
    gradientEnabled: Boolean,
    sizeDp: Int
) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val strokePx = 3.dp.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        if (gradientEnabled) {
            drawArc(
                brush = Brush.sweepGradient(listOf(unviewedColor, accentColor, unviewedColor)),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        } else {
            drawArc(
                color = unviewedColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}
