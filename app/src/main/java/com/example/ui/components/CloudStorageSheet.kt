package com.example.ui.components

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class StorageFilterMode(val label: String) {
    ALL("All"),
    EXPIRED_STATUSES("Expired Statuses"),
    CHAT_MEDIA("Chat Media"),
    LARGE_FILES("Large >500 KB")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStorageManagerSheet(
    summary: CloudStorageSummary,
    isScanning: Boolean,
    isDeleting: Boolean,
    onRefresh: () -> Unit,
    onDeleteItems: (List<DeletableCloudItem>, onDone: () -> Unit) -> Unit,
    onDeleteExpiredStatuses: () -> Unit,
    onClearLocalCache: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = XMaxTheme.colors
    var activeFilter by remember { mutableStateOf(StorageFilterMode.ALL) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var pendingConfirmItems by remember { mutableStateOf<List<DeletableCloudItem>?>(null) }

    val filteredItems = remember(summary.deletableItems, activeFilter) {
        when (activeFilter) {
            StorageFilterMode.ALL -> summary.deletableItems
            StorageFilterMode.EXPIRED_STATUSES -> summary.deletableItems.filter { it.kind == CloudItemKind.EXPIRED_STATUS }
            StorageFilterMode.CHAT_MEDIA -> summary.deletableItems.filter {
                it.kind == CloudItemKind.CHAT_MEDIA || it.kind == CloudItemKind.CLOUD_BUCKET_OBJECT
            }
            StorageFilterMode.LARGE_FILES -> summary.deletableItems.filter { it.byteSize >= 512L * 1024L }
        }
    }

    val selectedItems = remember(filteredItems, selectedIds) {
        filteredItems.filter { it.id in selectedIds && it.canDelete }
    }
    val selectedBytes = remember(selectedItems) {
        selectedItems.sumOf { it.byteSize }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = palette.outline) },
        modifier = Modifier.testTag("cloud_storage_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cloud Storage & Limits",
                        style = XMaxType.secondaryTitle,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Track free-tier limits & remove unwanted cloud files",
                        style = XMaxType.footnote,
                        color = palette.textSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        onClick = onRefresh,
                        enabled = !isScanning && !isDeleting,
                        shape = CircleShape,
                        color = palette.surfaceMuted,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("storage_refresh_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.accentViolet
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Refresh Storage Scan",
                                    tint = palette.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = onDismiss,
                        shape = CircleShape,
                        color = palette.surfaceMuted,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close Storage Manager",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                // 1. External Cloud Bucket Quota Card (Supabase 1 GB / Cloudflare R2 10 GB)
                item {
                    val hasExternalData = summary.externalConfigured && summary.externalError == null && summary.externalProvider != "UNCONFIGURED"
                    val extTitle = when (summary.externalProvider) {
                        "CLOUDFLARE_R2" -> "Cloudflare R2 Bucket"
                        "SUPABASE_STORAGE" -> "Supabase Cloud Storage"
                        else -> "External Cloud Bucket"
                    }
                    val extTier = if (hasExternalData) {
                        computeStorageLimitTier(summary.externalUsedBytes, summary.externalQuotaBytes)
                    } else StorageLimitTier.SAFE
                    StorageQuotaMeterCard(
                        title = extTitle,
                        subtitle = when {
                            hasExternalData -> "${summary.externalObjectCount} cloud object${if (summary.externalObjectCount == 1) "" else "s"}" +
                                if (summary.externalTruncated) " (sample capped)" else ""
                            !summary.externalConfigured -> "Worker URL not configured yet in Secrets"
                            summary.externalError != null -> summary.externalError
                            else -> "Free-tier media storage"
                        },
                        usedBytes = summary.externalUsedBytes,
                        quotaBytes = summary.externalQuotaBytes,
                        tier = extTier,
                        icon = Icons.Filled.CloudQueue,
                        isAvailable = hasExternalData
                    )
                }

                // 2. Firestore Spark 1 GiB Database Meter
                item {
                    val fsTier = computeStorageLimitTier(summary.firestoreUsedBytes, summary.firestoreQuotaBytes)
                    StorageQuotaMeterCard(
                        title = "Firestore Spark Database",
                        subtitle = "Media payloads ${formatStorageBytes(summary.firestoreInlineMediaBytes)} • Records ${formatStorageBytes(summary.firestoreRecordsBytes)}",
                        usedBytes = summary.firestoreUsedBytes,
                        quotaBytes = summary.firestoreQuotaBytes,
                        tier = fsTier,
                        icon = Icons.Filled.Storage,
                        isAvailable = true
                    )
                }

                // 3. Quick Cleanup Strip (Expired Statuses + Device Cache)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = onDeleteExpiredStatuses,
                            enabled = summary.expiredStatusCount > 0 && !isDeleting,
                            shape = RoundedCornerShape(18.dp),
                            color = if (summary.expiredStatusCount > 0) palette.amberBg else palette.surfaceSoft,
                            border = BorderStroke(1.dp, palette.outline),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 64.dp)
                                .testTag("storage_delete_expired_button")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoDelete,
                                        contentDescription = null,
                                        tint = if (summary.expiredStatusCount > 0) palette.amberInk else palette.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Expired Statuses",
                                        style = XMaxType.caption1Semibold,
                                        color = if (summary.expiredStatusCount > 0) palette.amberInk else palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (summary.expiredStatusCount > 0) {
                                        "Clean ${summary.expiredStatusCount} (${formatStorageBytes(summary.expiredStatusBytes)})"
                                    } else {
                                        "None expired"
                                    },
                                    style = XMaxType.footnote,
                                    color = if (summary.expiredStatusCount > 0) palette.amberInk else palette.textSecondary
                                )
                            }
                        }

                        Surface(
                            onClick = onClearLocalCache,
                            enabled = summary.deviceCacheBytes > 0 && !isDeleting,
                            shape = RoundedCornerShape(18.dp),
                            color = palette.surfaceSoft,
                            border = BorderStroke(1.dp, palette.outline),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 64.dp)
                                .testTag("storage_clear_cache_button")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CleaningServices,
                                        contentDescription = null,
                                        tint = palette.accentViolet,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Device Cache",
                                        style = XMaxType.caption1Semibold,
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (summary.deviceCacheFileCount > 0) {
                                        "Clear ${formatStorageBytes(summary.deviceCacheBytes)}"
                                    } else {
                                        "0 B cached"
                                    },
                                    style = XMaxType.footnote,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }

                // 4. Filter Pills + Bulk Select Header
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Cloud Files (${filteredItems.size})",
                                style = XMaxType.headline,
                                color = palette.textPrimary
                            )
                            val deletableVisible = filteredItems.filter { it.canDelete }
                            if (deletableVisible.isNotEmpty()) {
                                val allSelected = deletableVisible.all { it.id in selectedIds }
                                TextButton(
                                    onClick = {
                                        selectedIds = if (allSelected) {
                                            selectedIds - deletableVisible.map { it.id }.toSet()
                                        } else {
                                            selectedIds + deletableVisible.map { it.id }
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (allSelected) "Clear Selection" else "Select All",
                                        style = XMaxType.caption1Semibold,
                                        color = palette.accentViolet
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StorageFilterMode.entries.forEach { mode ->
                                val selected = activeFilter == mode
                                Surface(
                                    onClick = { activeFilter = mode },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (selected) palette.buttonPrimaryBg else palette.surfaceSoft,
                                    border = if (selected) null else BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 38.dp)
                                        .testTag("storage_filter_${mode.name.lowercase()}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = mode.label,
                                            style = XMaxType.caption1Semibold,
                                            color = if (selected) palette.buttonPrimaryFg else palette.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. File List or Empty State
                if (isScanning && summary.deletableItems.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = palette.surfaceSoft,
                            border = BorderStroke(1.dp, palette.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.4.dp,
                                    color = palette.accentViolet
                                )
                                Text(
                                    text = "Scanning Firestore, Supabase / Cloudflare R2 & device cache…",
                                    style = XMaxType.subheadline,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                } else if (filteredItems.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Outlined.CloudDone,
                            title = "Cloud Storage Looks Clean",
                            subtitle = "No unwanted files match this filter right now."
                        )
                    }
                } else {
                    items(filteredItems, key = { it.id }) { item ->
                        DeletableCloudItemRow(
                            item = item,
                            selected = item.id in selectedIds,
                            enabled = !isDeleting,
                            onToggleSelect = {
                                if (!item.canDelete) return@DeletableCloudItemRow
                                selectedIds = if (item.id in selectedIds) {
                                    selectedIds - item.id
                                } else {
                                    selectedIds + item.id
                                }
                            },
                            onDeleteSingle = {
                                if (item.canDelete) {
                                    pendingConfirmItems = listOf(item)
                                }
                            }
                        )
                    }
                }
            }

            // Bulk Delete Sticky Action Bar
            AnimatedVisibility(visible = selectedItems.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.blushBg,
                    border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${selectedItems.size} file${if (selectedItems.size == 1) "" else "s"} selected",
                                style = XMaxType.subheadlineSemibold,
                                color = palette.blushInk
                            )
                            Text(
                                text = "Frees ~${formatStorageBytes(selectedBytes)}",
                                style = XMaxType.footnote.tabular(),
                                color = palette.blushInk.copy(alpha = 0.85f)
                            )
                        }
                        Button(
                            onClick = { pendingConfirmItems = selectedItems },
                            enabled = !isDeleting,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrimsonBlocked,
                                contentColor = PureWhite
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("storage_delete_selected_button")
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = PureWhite
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Delete Selected", style = XMaxType.buttonCompact)
                        }
                    }
                }
            }
        }
    }

    pendingConfirmItems?.let { targets ->
        val totalBytes = targets.sumOf { it.byteSize }
        AlertDialog(
            onDismissRequest = { if (!isDeleting) pendingConfirmItems = null },
            containerColor = palette.surfaceElevated,
            title = {
                Text(
                    text = "Delete ${targets.size} Cloud Item${if (targets.size == 1) "" else "s"}?",
                    style = XMaxType.secondaryTitle,
                    color = palette.textPrimary
                )
            },
            text = {
                Text(
                    text = "This permanently removes ${targets.size} cloud file${if (targets.size == 1) "" else "s"} (~${formatStorageBytes(totalBytes)}) from cloud storage to free up your free-tier quota.",
                    style = XMaxType.subheadline,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    enabled = !isDeleting,
                    onClick = {
                        val toDelete = targets
                        pendingConfirmItems = null
                        onDeleteItems(toDelete) {
                            selectedIds = selectedIds - toDelete.map { it.id }.toSet()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CrimsonBlocked,
                        contentColor = PureWhite
                    )
                ) {
                    Text("Delete Forever", style = XMaxType.buttonCompact)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { pendingConfirmItems = null }
                ) {
                    Text("Cancel", style = XMaxType.buttonCompact, color = palette.textPrimary)
                }
            }
        )
    }
}

@Composable
private fun StorageQuotaMeterCard(
    title: String,
    subtitle: String,
    usedBytes: Long,
    quotaBytes: Long,
    tier: StorageLimitTier,
    icon: ImageVector,
    isAvailable: Boolean
) {
    val palette = XMaxTheme.colors
    val fraction = if (isAvailable && quotaBytes > 0L) {
        (usedBytes.toDouble() / quotaBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f
    val pctText = String.format(Locale.US, "%.1f%%", fraction * 100f)

    val (barColor, badgeBg, badgeFg, badgeLabel) = when {
        !isAvailable -> StorageColors(palette.textSecondary, palette.surfaceMuted, palette.textSecondary, "Setup Needed")
        tier == StorageLimitTier.CRITICAL -> StorageColors(CrimsonBlocked, palette.blushBg, palette.blushInk, "Near Limit!")
        tier == StorageLimitTier.WARNING -> StorageColors(WarmAlarmAmber, palette.amberBg, palette.amberInk, "Moderate")
        else -> StorageColors(EmeraldApproved, palette.mintBg, palette.mintInk, "Safe")
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceSoft,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier.fillMaxWidth()
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(badgeBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = badgeFg,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = XMaxType.headline,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = XMaxType.footnote,
                            color = palette.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(label = badgeLabel, containerColor = badgeBg, contentColor = badgeFg)
            }

            LinearProgressIndicator(
                progress = { if (isAvailable) fraction.coerceAtLeast(0.015f) else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
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
                    text = if (isAvailable) {
                        "${formatStorageBytes(usedBytes)} / ${formatStorageBytes(quotaBytes)}"
                    } else {
                        "Limit: ${formatStorageBytes(quotaBytes)}"
                    },
                    style = XMaxType.footnoteSemibold.tabular(),
                    color = palette.textPrimary
                )
                if (isAvailable) {
                    Text(
                        text = "$pctText used",
                        style = XMaxType.footnoteMedium.tabular(),
                        color = badgeFg
                    )
                }
            }
        }
    }
}

private data class StorageColors(
    val bar: Color,
    val badgeBg: Color,
    val badgeFg: Color,
    val label: String
)

@Composable
private fun DeletableCloudItemRow(
    item: DeletableCloudItem,
    selected: Boolean,
    enabled: Boolean,
    onToggleSelect: () -> Unit,
    onDeleteSingle: () -> Unit
) {
    val palette = XMaxTheme.colors
    val (kindIcon, kindBg, kindInk) = when (item.kind) {
        CloudItemKind.EXPIRED_STATUS -> Triple(Icons.Outlined.HistoryToggleOff, palette.amberBg, palette.amberInk)
        CloudItemKind.ACTIVE_STATUS -> Triple(Icons.Outlined.Adjust, palette.kpiLavenderBg, palette.accentViolet)
        CloudItemKind.CHAT_MEDIA -> Triple(Icons.Outlined.PermMedia, palette.kpiSkyBg, palette.infoInk)
        CloudItemKind.CLOUD_BUCKET_OBJECT -> Triple(Icons.Outlined.InsertDriveFile, palette.mintBg, palette.mintInk)
    }
    val dateStr = remember(item.createdAtMillis) {
        if (item.createdAtMillis > 0L) {
            SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(item.createdAtMillis))
        } else ""
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) palette.kpiLavenderBg else palette.surfaceElevated,
        border = BorderStroke(1.dp, if (selected) palette.accentViolet else palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && item.canDelete, onClick = onToggleSelect)
            .testTag("storage_item_row_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (item.canDelete) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    enabled = enabled
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Admin or sender required",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(kindBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = kindIcon,
                    contentDescription = item.kind.label,
                    tint = kindInk,
                    modifier = Modifier.size(19.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = XMaxType.subheadlineSemibold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(item.subtitle)
                        if (dateStr.isNotBlank()) append(" • $dateStr")
                    },
                    style = XMaxType.caption1,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.kind.label} • ${formatStorageBytes(item.byteSize)}",
                    style = XMaxType.caption1Semibold.tabular(),
                    color = kindInk,
                    maxLines = 1
                )
            }

            if (item.canDelete) {
                IconButton(
                    onClick = onDeleteSingle,
                    enabled = enabled,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("storage_item_delete_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete ${item.title}",
                        tint = CrimsonBlocked,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
