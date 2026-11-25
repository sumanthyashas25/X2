package com.example.data

import android.content.Context
import com.example.chat.ChatFileManager
import com.example.chat.remote.CloudStorageDeleteTarget
import com.example.chat.remote.CloudStorageUsageReport
import com.example.chat.remote.WorkerBridgeClient
import com.example.chat.remote.WorkerResult
import com.example.model.ChatAttachmentType
import com.example.model.ChatMessage
import com.example.model.ChatStatus
import com.example.ui.AgencyUiState
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

enum class StorageLimitTier {
    SAFE,
    WARNING,
    CRITICAL
}

enum class CloudItemKind(val label: String) {
    EXPIRED_STATUS("Expired Status"),
    ACTIVE_STATUS("Active Status"),
    CHAT_MEDIA("Chat Media"),
    CLOUD_BUCKET_OBJECT("Cloud File")
}

data class DeletableCloudItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: CloudItemKind,
    val byteSize: Long,
    val createdAtMillis: Long = 0L,
    val channelId: String = "",
    val messageId: String = "",
    val statusId: String = "",
    val bucket: String = "",
    val storagePath: String = "",
    val hasInlineFirestoreBytes: Boolean = false,
    val hasExternalCloudObject: Boolean = false,
    val canDelete: Boolean = true
)

data class CloudStorageSummary(
    val firestoreUsedBytes: Long = 0L,
    val firestoreQuotaBytes: Long = FIRESTORE_SPARK_QUOTA_BYTES,
    val firestoreInlineMediaBytes: Long = 0L,
    val firestoreRecordsBytes: Long = 0L,
    val expiredStatusCount: Int = 0,
    val expiredStatusBytes: Long = 0L,
    val externalProvider: String = "UNCONFIGURED",
    val externalUsedBytes: Long = 0L,
    val externalQuotaBytes: Long = SUPABASE_FREE_QUOTA_BYTES,
    val externalObjectCount: Int = 0,
    val externalTruncated: Boolean = false,
    val externalConfigured: Boolean = false,
    val externalError: String? = null,
    val deviceCacheBytes: Long = 0L,
    val deviceCacheFileCount: Int = 0,
    val deletableItems: List<DeletableCloudItem> = emptyList(),
    val lastUpdatedMillis: Long = 0L
) {
    val primaryUsedBytes: Long
        get() = if (externalConfigured && externalError == null && externalProvider != "UNCONFIGURED") {
            externalUsedBytes
        } else {
            firestoreUsedBytes
        }

    val primaryQuotaBytes: Long
        get() = if (externalConfigured && externalError == null && externalProvider != "UNCONFIGURED") {
            externalQuotaBytes
        } else {
            firestoreQuotaBytes
        }

    val primaryFraction: Float
        get() = (primaryUsedBytes.toDouble() / primaryQuotaBytes.coerceAtLeast(1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)

    val firestoreFraction: Float
        get() = (firestoreUsedBytes.toDouble() / firestoreQuotaBytes.coerceAtLeast(1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)

    val externalFraction: Float
        get() = (externalUsedBytes.toDouble() / externalQuotaBytes.coerceAtLeast(1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)

    val overallHealth: StorageLimitTier
        get() = maxOf(
            computeStorageLimitTier(firestoreUsedBytes, firestoreQuotaBytes),
            if (externalConfigured && externalError == null) {
                computeStorageLimitTier(externalUsedBytes, externalQuotaBytes)
            } else StorageLimitTier.SAFE
        )

    val primaryLabel: String
        get() = when {
            externalConfigured && externalError == null && externalProvider == "CLOUDFLARE_R2" ->
                "Cloudflare R2 (10 GB Free)"
            externalConfigured && externalError == null && externalProvider == "SUPABASE_STORAGE" ->
                "Supabase Storage (1 GB Free)"
            else -> "Firestore Spark (1 GB Free)"
        }
}

const val FIRESTORE_SPARK_QUOTA_BYTES = 1_073_741_824L // 1 GiB Spark Free Tier
const val SUPABASE_FREE_QUOTA_BYTES = 1_073_741_824L // 1 GB Supabase Storage Free Tier
const val R2_FREE_QUOTA_BYTES = 10L * 1024L * 1024L * 1024L // 10 GB Cloudflare R2 Free Tier

fun computeStorageLimitTier(usedBytes: Long, quotaBytes: Long): StorageLimitTier {
    if (quotaBytes <= 0L) return StorageLimitTier.SAFE
    val ratio = usedBytes.toDouble() / quotaBytes.toDouble()
    return when {
        ratio >= 0.85 -> StorageLimitTier.CRITICAL
        ratio >= 0.65 -> StorageLimitTier.WARNING
        else -> StorageLimitTier.SAFE
    }
}

fun formatStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
    }
}

class CloudStorageTracker(private val core: AgencyRepository) {

    private fun sub(name: String) = core.sub(name)

    suspend fun scanCloudStorage(
        context: Context,
        state: AgencyUiState
    ): CloudStorageSummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val myUid = state.myMember?.uid.orEmpty()
        val isSuperAdmin = state.isSuperAdmin
        val isAdmin = state.isAdmin

        // 1. On-device media cache stats
        val localStats = runCatching { ChatFileManager.getChatMediaCacheStats(context) }
            .getOrDefault(ChatFileManager.ChatCacheStats(0, 0L))

        // 2. External Cloud Storage (Supabase Storage / Cloudflare R2 via Worker)
        val workerConfigured = WorkerBridgeClient.isConfigured()
        var externalReport: CloudStorageUsageReport? = null
        var externalError: String? = null
        if (workerConfigured && myUid.isNotBlank()) {
            when (val res = WorkerBridgeClient.fetchCloudStorageUsage(core.companyId)) {
                is WorkerResult.Success -> externalReport = res.data
                is WorkerResult.NotConfigured -> Unit
                is WorkerResult.RateLimited -> externalError = "Rate limited; retry in ${res.retryAfterSeconds}s"
                is WorkerResult.Error -> externalError = res.message
            }
        }

        // 3. Firestore media + document footprint scan (Spark 1 GiB limit)
        var inlineMediaBytes = 0L
        var expiredStatusCount = 0
        var expiredStatusBytes = 0L
        val deletableMap = linkedMapOf<String, DeletableCloudItem>()

        if (core.firestoreInitService.isReady && myUid.isNotBlank()) {
            // Scan all chat_statuses (including expired statuses still consuming Firestore/Cloud quota)
            val statusDocs = runCatching {
                sub("chat_statuses")
                    .orderBy("createdAtMillis", Query.Direction.DESCENDING)
                    .limit(150)
                    .get()
                    .await()
                    .documents
            }.getOrDefault(emptyList())

            statusDocs.forEach { doc ->
                val st = runCatching { doc.toObject(ChatStatus::class.java) }.getOrNull() ?: return@forEach
                val inlineBytes = st.imageDataBase64.length.toLong() +
                    st.ownerPhotoBase64.length.toLong() +
                    st.text.length.toLong() + 512L
                inlineMediaBytes += inlineBytes

                val isExpired = !st.isActive(now)
                val cloudPath = st.effectiveStoragePath()
                val approxCloudBytes = if (st.mediaByteSize > 0L) st.mediaByteSize else inlineBytes
                if (isExpired) {
                    expiredStatusCount += 1
                    expiredStatusBytes += inlineBytes
                }
                val canDelete = isSuperAdmin || st.ownerUid == myUid
                val hasMediaOrExpired = isExpired || st.imageDataBase64.isNotBlank() || cloudPath.isNotBlank()
                if (canDelete && hasMediaOrExpired) {
                    val key = if (cloudPath.isNotBlank()) "path:$cloudPath" else "status:${st.id}"
                    deletableMap[key] = DeletableCloudItem(
                        id = key,
                        title = st.effectiveDisplayText().ifBlank { "${st.kindEnum().label} Status" }.take(64),
                        subtitle = buildString {
                            append(if (isExpired) "Expired status" else "Active status")
                            if (st.ownerName.isNotBlank()) append(" • ${st.ownerName.substringBefore(" ")}")
                        },
                        kind = if (isExpired) CloudItemKind.EXPIRED_STATUS else CloudItemKind.ACTIVE_STATUS,
                        byteSize = approxCloudBytes.coerceAtLeast(inlineBytes),
                        createdAtMillis = st.createdAtMillis,
                        statusId = st.id,
                        bucket = st.effectiveBucket(),
                        storagePath = cloudPath,
                        hasInlineFirestoreBytes = st.imageDataBase64.isNotBlank() || isExpired,
                        hasExternalCloudObject = cloudPath.isNotBlank(),
                        canDelete = true
                    )
                }
            }

            // Scan recent messages across channels the user participates in
            val channelsToScan = state.chatChannels.take(15)
            val channelTitles = state.chatChannels.associate { it.id to it.title.ifBlank { "Chat" } }
            for (channel in channelsToScan) {
                val isChannelAdmin = channel.isChannelAdmin(myUid, isSuperAdmin)
                val msgs = runCatching {
                    sub("chat_channels").document(channel.id).collection("messages")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(50)
                        .get()
                        .await()
                        .documents
                        .mapNotNull { it.toObject(ChatMessage::class.java) }
                }.getOrDefault(emptyList())

                msgs.forEach { msg ->
                    val inlineBytes = msg.attachmentDataBase64.length.toLong()
                    inlineMediaBytes += inlineBytes
                    val hasInline = inlineBytes > 0L
                    val hasCloud = msg.attachmentStoragePath.isNotBlank()
                    if ((hasInline || hasCloud) && !msg.isDeletedForEveryone) {
                        val canDelete = isSuperAdmin || isChannelAdmin || msg.senderUid == myUid
                        val size = when {
                            msg.attachmentSizeBytes > 0L -> msg.attachmentSizeBytes
                            inlineBytes > 0L -> inlineBytes
                            else -> 0L
                        }
                        val key = if (hasCloud) "path:${msg.attachmentStoragePath}" else "msg:${channel.id}:${msg.id}"
                        deletableMap[key] = DeletableCloudItem(
                            id = key,
                            title = msg.attachmentName.ifBlank {
                                msg.body.ifBlank { msg.attachmentTypeEnum().name.lowercase() + " file" }
                            }.take(64),
                            subtitle = "${channelTitles[channel.id] ?: "Chat"} • ${if (hasCloud) "Cloud file" else "Firestore inline"}",
                            kind = CloudItemKind.CHAT_MEDIA,
                            byteSize = size.coerceAtLeast(inlineBytes),
                            createdAtMillis = msg.createdAt?.toDate()?.time ?: 0L,
                            channelId = channel.id,
                            messageId = msg.id,
                            bucket = msg.attachmentBucket,
                            storagePath = msg.attachmentStoragePath,
                            hasInlineFirestoreBytes = hasInline,
                            hasExternalCloudObject = hasCloud,
                            canDelete = canDelete
                        )
                    }
                }
            }
        }

        // Merge any external bucket objects (Supabase/R2) not already represented via Firestore messages/statuses
        externalReport?.objects?.forEach { obj ->
            val key = "path:${obj.storagePath}"
            val existing = deletableMap[key]
            if (existing == null) {
                val itemCanDelete = isSuperAdmin || isAdmin || obj.canDelete
                deletableMap[key] = DeletableCloudItem(
                    id = key,
                    title = obj.fileName.ifBlank { obj.storagePath.substringAfterLast("/") }.take(64),
                    subtitle = "${obj.bucket} • ${obj.category}",
                    kind = CloudItemKind.CLOUD_BUCKET_OBJECT,
                    byteSize = obj.byteSize,
                    createdAtMillis = obj.updatedAtMillis,
                    channelId = obj.chatId,
                    bucket = obj.bucket,
                    storagePath = obj.storagePath,
                    hasInlineFirestoreBytes = false,
                    hasExternalCloudObject = true,
                    canDelete = itemCanDelete
                )
            } else if (existing.byteSize < obj.byteSize) {
                deletableMap[key] = existing.copy(
                    byteSize = obj.byteSize,
                    bucket = obj.bucket,
                    hasExternalCloudObject = true
                )
            }
        }

        // Estimate remaining Firestore documents footprint (avatars, groups, projects, quotes, logs)
        val avatarBytes = state.members.sumOf { it.photoBase64.length.toLong() + 768L }
        val channelBytes = state.chatChannels.sumOf { it.photoBase64.length.toLong() + 1024L }
        val coreDocCount = state.clients.size + state.requests.size + state.quotations.size +
            state.projects.size + state.deliverables.size + state.payments.size +
            state.activityLog.size + state.notifications.size + state.staffPlanItems.size
        val firestoreRecordsBytes = avatarBytes + channelBytes + (coreDocCount * 2048L)
        val totalFirestoreBytes = inlineMediaBytes + firestoreRecordsBytes

        val sortedItems = deletableMap.values.sortedWith(
            compareByDescending<DeletableCloudItem> { it.kind == CloudItemKind.EXPIRED_STATUS }
                .thenByDescending { it.byteSize }
        )

        CloudStorageSummary(
            firestoreUsedBytes = totalFirestoreBytes,
            firestoreQuotaBytes = FIRESTORE_SPARK_QUOTA_BYTES,
            firestoreInlineMediaBytes = inlineMediaBytes,
            firestoreRecordsBytes = firestoreRecordsBytes,
            expiredStatusCount = expiredStatusCount,
            expiredStatusBytes = expiredStatusBytes,
            externalProvider = externalReport?.provider ?: "UNCONFIGURED",
            externalUsedBytes = externalReport?.totalBytes ?: 0L,
            externalQuotaBytes = externalReport?.quotaBytes ?: SUPABASE_FREE_QUOTA_BYTES,
            externalObjectCount = externalReport?.objectCount ?: 0,
            externalTruncated = externalReport?.truncated ?: false,
            externalConfigured = workerConfigured,
            externalError = externalError,
            deviceCacheBytes = localStats.totalBytes,
            deviceCacheFileCount = localStats.fileCount,
            deletableItems = sortedItems,
            lastUpdatedMillis = now
        )
    }

    suspend fun deleteCloudItems(
        items: List<DeletableCloudItem>
    ): Result<Pair<Int, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val authorized = items.filter { it.canDelete }
            if (authorized.isEmpty()) error("No authorized cloud files selected.")

            var deletedCount = 0
            var freedBytes = 0L

            // 1. Batch delete external cloud objects (Supabase / Cloudflare R2) via Worker
            val externalTargets = authorized
                .filter { it.hasExternalCloudObject && it.storagePath.isNotBlank() }
                .map { CloudStorageDeleteTarget(bucket = it.bucket, storagePath = it.storagePath, chatId = it.channelId) }
                .distinctBy { it.storagePath }

            if (externalTargets.isNotEmpty() && WorkerBridgeClient.isConfigured()) {
                WorkerBridgeClient.deleteCloudStorageBatch(core.companyId, externalTargets)
            }

            // 2. Clean up Firestore status docs and message attachment payloads
            authorized.forEach { item ->
                when {
                    item.statusId.isNotBlank() -> {
                        val res = runCatching {
                            sub("chat_statuses").document(item.statusId).delete().await()
                        }
                        if (res.isSuccess || item.hasExternalCloudObject) {
                            deletedCount += 1
                            freedBytes += item.byteSize
                        }
                    }
                    item.channelId.isNotBlank() && item.messageId.isNotBlank() -> {
                        val res = runCatching {
                            sub("chat_channels").document(item.channelId)
                                .collection("messages").document(item.messageId)
                                .update(
                                    mapOf(
                                        "isDeletedForEveryone" to true,
                                        "body" to "Attachment removed to free cloud storage",
                                        "attachmentType" to ChatAttachmentType.NONE.name,
                                        "attachmentDataBase64" to "",
                                        "attachmentName" to "",
                                        "attachmentStoragePath" to ""
                                    )
                                ).await()
                        }
                        if (res.isSuccess || item.hasExternalCloudObject) {
                            deletedCount += 1
                            freedBytes += item.byteSize
                        }
                    }
                    item.hasExternalCloudObject -> {
                        deletedCount += 1
                        freedBytes += item.byteSize
                    }
                }
            }
            deletedCount to freedBytes
        }
    }

    suspend fun deleteExpiredStatuses(
        summary: CloudStorageSummary
    ): Result<Pair<Int, Long>> {
        val expired = summary.deletableItems.filter { it.kind == CloudItemKind.EXPIRED_STATUS && it.canDelete }
        if (expired.isEmpty()) return Result.success(0 to 0L)
        return deleteCloudItems(expired)
    }
}
