package com.example.chat.remote

import android.util.Log
import com.example.BuildConfig
import com.example.model.ChatAttachmentType
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed class WorkerResult<out T> {
    data class Success<T>(val data: T) : WorkerResult<T>()
    data object NotConfigured : WorkerResult<Nothing>()
    data class RateLimited(val retryAfterSeconds: Int) : WorkerResult<Nothing>()
    data class Error(val message: String, val code: Int = 0) : WorkerResult<Nothing>()
}

data class CloudflareUploadResult(
    val provider: String,
    val bucket: String,
    val storagePath: String,
    val byteSize: Long,
    val mimeType: String,
    val sha256: String
)

data class SignedUploadTicket(
    val provider: String,
    val signedUrl: String,
    val method: String,
    val bucket: String,
    val storagePath: String,
    val byteSize: Long,
    val mimeType: String,
    val sha256: String
)

data class SignedDownloadTicket(
    val provider: String,
    val signedUrl: String,
    val bucket: String
)

data class WorkerHealthStatus(
    val ok: Boolean,
    val plan: String,
    val storageProvider: String,
    val firebaseProjectId: String,
    val firestoreDbId: String,
    val r2Configured: Boolean = false,
    val supabaseConfigured: Boolean = false,
    val fcmConfigured: Boolean = false,
    val workerVersion: String = ""
)

data class WorkerAuthVerifyStatus(
    val ok: Boolean,
    val uid: String,
    val email: String,
    val emailVerified: Boolean,
    val firebaseProjectId: String,
    val firestoreDbId: String,
    val storageProvider: String,
    val r2Configured: Boolean = false,
    val supabaseConfigured: Boolean = false,
    val fcmConfigured: Boolean = false,
    val memberStatus: String = "",
    val memberAccessLevel: String = "",
    val memberActive: Boolean = false
)

data class ResetChunkResult(
    val completed: Boolean,
    val nextCollection: String,
    val nextCursorDocId: String,
    val deletedDocsInChunk: Int,
    val deletedStorageInChunk: Int
)

data class CloudStorageCategoryUsage(
    val category: String,
    val bytes: Long,
    val count: Int
)

data class CloudStorageObjectInfo(
    val bucket: String,
    val storagePath: String,
    val fileName: String,
    val byteSize: Long,
    val category: String,
    val chatId: String = "",
    val uploaderUid: String = "",
    val updatedAtMillis: Long = 0L,
    val canDelete: Boolean = false
)

data class CloudStorageUsageReport(
    val provider: String,
    val quotaBytes: Long,
    val totalBytes: Long,
    val objectCount: Int,
    val truncated: Boolean,
    val categories: List<CloudStorageCategoryUsage>,
    val objects: List<CloudStorageObjectInfo>
)

data class CloudStorageDeleteTarget(
    val bucket: String,
    val storagePath: String,
    val chatId: String = ""
)

data class NudgeDispatchResult(
    val ok: Boolean,
    val mode: String,
    val whatsappDispatched: Boolean,
    val emailDispatched: Boolean,
    val note: String = ""
)

/**
 * Stateless Android client for the zero-cost hybrid backend:
 * X.MAX Android App -> Cloudflare Worker (Auth + Signed URLs + Proxy) -> Firebase + Supabase Storage.
 * Zero Cloudflare R2, Supabase service_role, or FCM secrets exist inside the Android APK.
 * Every storage and push operation is authenticated via the active user's Firebase Auth ID token.
 */
object WorkerBridgeClient {
    private const val TAG = "XMaxWorkerBridge"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val _transferProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val transferProgress: StateFlow<Map<String, Float>> = _transferProgress.asStateFlow()

    private val _lastHealthStatus = MutableStateFlow<WorkerHealthStatus?>(null)
    val lastHealthStatus: StateFlow<WorkerHealthStatus?> = _lastHealthStatus.asStateFlow()

    @Volatile
    private var cachedStorageProvider: String = ""

    fun updateTransferProgress(key: String, progress: Float?) {
        if (key.isBlank()) return
        val next = _transferProgress.value.toMutableMap()
        if (progress == null) next.remove(key) else next[key] = progress.coerceIn(0f, 1f)
        _transferProgress.value = next
    }

    fun isConfigured(): Boolean {
        val url = BuildConfig.CHAT_WORKER_BASE_URL.trim()
        return url.startsWith("https://") && !url.contains("your-subdomain")
    }

    fun isR2FreeTierActive(): Boolean {
        val h = _lastHealthStatus.value
        val sp = cachedStorageProvider.ifBlank { h?.storageProvider.orEmpty() }
        return h?.r2Configured == true || sp == "CLOUDFLARE_R2"
    }

    fun activeStorageProviderLabel(): String {
        val sp = cachedStorageProvider.ifBlank { _lastHealthStatus.value?.storageProvider.orEmpty() }
        return when {
            sp.contains("SUPABASE") && sp.contains("R2") -> "Cloudflare R2 + Supabase Storage"
            sp.contains("CLOUDFLARE") || sp.contains("R2") || _lastHealthStatus.value?.r2Configured == true ->
                "Cloudflare R2 (Free Tier • 10 GB)"
            sp.contains("SUPABASE") -> "Supabase Storage (Free Tier)"
            isConfigured() -> "Cloudflare R2 Worker Bridge Ready"
            else -> "Firestore Inline (Worker URL Unset)"
        }
    }

    internal fun baseUrl(): String = BuildConfig.CHAT_WORKER_BASE_URL.trim().trimEnd('/')

    fun bucketForAttachmentType(type: ChatAttachmentType): String = when (type) {
        ChatAttachmentType.IMAGE -> "chat-media"
        ChatAttachmentType.VIDEO -> "chat-videos"
        ChatAttachmentType.AUDIO -> "chat-voice"
        ChatAttachmentType.FILE, ChatAttachmentType.NONE -> "chat-docs"
    }

    internal fun buildUploadUrl(
        base: String,
        companyId: String,
        chatId: String,
        bucket: String,
        fileName: String,
        sha256: String
    ): String? {
        val parsed = base.trimEnd('/').toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .addPathSegments("v1/storage/upload")
            .addQueryParameter("companyId", companyId)
            .addQueryParameter("chatId", chatId)
            .addQueryParameter("bucket", bucket)
            .addQueryParameter("fileName", fileName)
            .addQueryParameter("sha256", sha256)
            .build()
            .toString()
    }

    internal fun buildDownloadUrl(
        base: String,
        companyId: String,
        chatId: String,
        storagePath: String
    ): String? {
        val parsed = base.trimEnd('/').toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .addPathSegments("v1/storage/download")
            .addQueryParameter("companyId", companyId)
            .addQueryParameter("chatId", chatId)
            .addQueryParameter("storagePath", storagePath)
            .build()
            .toString()
    }

    internal fun buildStatusUploadUrl(
        base: String,
        companyId: String,
        bucket: String,
        fileName: String,
        sha256: String
    ): String? {
        val parsed = base.trimEnd('/').toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .addPathSegments("v1/storage/upload")
            .addQueryParameter("companyId", companyId)
            .addQueryParameter("chatId", "status_feed")
            .addQueryParameter("scope", "status")
            .addQueryParameter("bucket", bucket)
            .addQueryParameter("fileName", fileName)
            .addQueryParameter("sha256", sha256)
            .build()
            .toString()
    }

    internal fun buildStatusDownloadUrl(
        base: String,
        companyId: String,
        storagePath: String,
        statusId: String = ""
    ): String? {
        val parsed = base.trimEnd('/').toHttpUrlOrNull() ?: return null
        val builder = parsed.newBuilder()
            .addPathSegments("v1/storage/download")
            .addQueryParameter("companyId", companyId)
            .addQueryParameter("chatId", "status_feed")
            .addQueryParameter("scope", "status")
            .addQueryParameter("storagePath", storagePath)
        if (statusId.isNotBlank()) builder.addQueryParameter("statusId", statusId)
        return builder.build().toString()
    }

    internal fun parseUploadResponse(json: JSONObject, fallbackBucket: String): CloudflareUploadResult {
        return CloudflareUploadResult(
            provider = json.optString("provider", "CLOUDFLARE_R2"),
            bucket = json.optString("bucket", fallbackBucket),
            storagePath = json.optString("storagePath"),
            byteSize = json.optLong("byteSize", 0L),
            mimeType = json.optString("mimeType"),
            sha256 = json.optString("sha256")
        )
    }

    internal fun parseSignedUploadResponse(json: JSONObject, fallbackBucket: String): SignedUploadTicket {
        return SignedUploadTicket(
            provider = json.optString("provider", "SUPABASE_STORAGE"),
            signedUrl = json.optString("signedUrl"),
            method = json.optString("method", "PUT").uppercase(),
            bucket = json.optString("bucket", fallbackBucket),
            storagePath = json.optString("storagePath"),
            byteSize = json.optLong("byteSize", 0L),
            mimeType = json.optString("mimeType"),
            sha256 = json.optString("sha256")
        )
    }

    internal fun parseSignedDownloadResponse(json: JSONObject, fallbackBucket: String): SignedDownloadTicket {
        return SignedDownloadTicket(
            provider = json.optString("provider", "SUPABASE_STORAGE"),
            signedUrl = json.optString("signedUrl"),
            bucket = json.optString("bucket", fallbackBucket)
        )
    }

    private suspend fun fetchFirebaseIdToken(forceRefresh: Boolean = false): String? {
        return runCatching {
            val user = FirebaseAuth.getInstance().currentUser ?: return null
            user.getIdToken(forceRefresh).await()?.token
        }.getOrNull()
    }

    internal fun parseHealthResponse(json: JSONObject): WorkerHealthStatus {
        return WorkerHealthStatus(
            ok = json.optBoolean("ok", false),
            plan = json.optString("plan", "free"),
            storageProvider = json.optString("storageProvider", "UNKNOWN"),
            firebaseProjectId = json.optString("firebaseProjectId"),
            firestoreDbId = json.optString("firestoreDbId"),
            r2Configured = json.optBoolean("r2Configured", false),
            supabaseConfigured = json.optBoolean("supabaseConfigured", false),
            fcmConfigured = json.optBoolean("fcmConfigured", false),
            workerVersion = json.optString("workerVersion", "")
        )
    }

    internal fun parseAuthVerifyResponse(json: JSONObject): WorkerAuthVerifyStatus {
        val memberObj = json.optJSONObject("member")
        return WorkerAuthVerifyStatus(
            ok = json.optBoolean("ok", false),
            uid = json.optString("uid"),
            email = json.optString("email"),
            emailVerified = json.optBoolean("emailVerified", false),
            firebaseProjectId = json.optString("firebaseProjectId"),
            firestoreDbId = json.optString("firestoreDbId"),
            storageProvider = json.optString("storageProvider", "UNKNOWN"),
            r2Configured = json.optBoolean("r2Configured", false),
            supabaseConfigured = json.optBoolean("supabaseConfigured", false),
            fcmConfigured = json.optBoolean("fcmConfigured", false),
            memberStatus = memberObj?.optString("status").orEmpty(),
            memberAccessLevel = memberObj?.optString("accessLevel").orEmpty(),
            memberActive = memberObj?.optBoolean("active", false) ?: false
        )
    }

    suspend fun checkHealth(): WorkerResult<WorkerHealthStatus> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        runCatching {
            val req = Request.Builder()
                .url("${baseUrl()}/v1/health")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use WorkerResult.Error("Health check failed (${resp.code})", resp.code)
                }
                val json = JSONObject(text)
                val status = parseHealthResponse(json)
                cachedStorageProvider = status.storageProvider
                _lastHealthStatus.value = status
                WorkerResult.Success(status)
            }
        }.getOrElse { err ->
            WorkerResult.Error(err.message ?: "Worker health check error")
        }
    }

    suspend fun verifyAuth(companyId: String = "xmax"): WorkerResult<WorkerAuthVerifyStatus> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required to verify Worker auth.")
        val parsedUrl = baseUrl().toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("v1/auth/verify")
            ?.addQueryParameter("companyId", companyId)
            ?.build()
            ?.toString()
            ?: return@withContext WorkerResult.NotConfigured

        runCatching {
            val req = Request.Builder()
                .url(parsedUrl)
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        .orEmpty().ifBlank { "Auth verify failed (${resp.code})" }
                    return@use WorkerResult.Error(msg, resp.code)
                }
                val status = parseAuthVerifyResponse(JSONObject(text))
                cachedStorageProvider = status.storageProvider
                WorkerResult.Success(status)
            }
        }.getOrElse { err ->
            WorkerResult.Error(err.message ?: "Worker auth verification error")
        }
    }

    private fun shouldPreferSignedSupabase(explicitHint: String = ""): Boolean {
        if (explicitHint == "SUPABASE_STORAGE") return true
        if (explicitHint == "CLOUDFLARE_R2") return false
        val sp = cachedStorageProvider.ifBlank { _lastHealthStatus.value?.storageProvider.orEmpty() }
        if (sp == "CLOUDFLARE_R2" || _lastHealthStatus.value?.r2Configured == true) return false
        return sp.contains("SUPABASE")
    }

    private fun requestSignedUploadTicket(
        idToken: String,
        companyId: String,
        chatId: String,
        scope: String,
        bucket: String,
        fileName: String,
        mimeType: String,
        byteSize: Long,
        sha256: String
    ): WorkerResult<SignedUploadTicket> {
        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("chatId", chatId)
            if (scope.isNotBlank()) put("scope", scope)
            put("bucket", bucket)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("byteSize", byteSize)
            if (sha256.isNotBlank()) put("sha256", sha256)
        }
        return executeWorkerPost("/v1/storage/sign-upload", idToken, payload) { json ->
            parseSignedUploadResponse(json, bucket)
        }
    }

    private fun requestSignedDownloadTicket(
        idToken: String,
        companyId: String,
        chatId: String,
        storagePath: String,
        statusId: String = ""
    ): WorkerResult<SignedDownloadTicket> {
        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("chatId", chatId)
            put("storagePath", storagePath)
            if (statusId.isNotBlank()) put("statusId", statusId)
        }
        return executeWorkerPost("/v1/storage/sign-download", idToken, payload) { json ->
            parseSignedDownloadResponse(json, "")
        }
    }

    private fun buildCountingRequestBody(
        mimeType: String,
        file: File,
        progressKey: String,
        onProgress: (Float) -> Unit
    ): RequestBody {
        val mediaType = mimeType.ifBlank { "application/octet-stream" }.toMediaType()
        val totalBytes = file.length().coerceAtLeast(1L)
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = totalBytes
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(16 * 1024)
                var uploaded = 0L
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                        uploaded += read
                        val frac = (uploaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.96f)
                        updateTransferProgress(progressKey, frac)
                        onProgress(frac)
                    }
                }
            }
        }
    }

    private fun executeSignedDirectPutUpload(
        ticket: SignedUploadTicket,
        mimeType: String,
        file: File,
        progressKey: String,
        onProgress: (Float) -> Unit,
        onCallCreated: (okhttp3.Call) -> Unit
    ): WorkerResult<CloudflareUploadResult> {
        if (!ticket.signedUrl.startsWith("https://") || ticket.storagePath.isBlank()) {
            return WorkerResult.Error("Invalid Supabase signed upload URL", 502)
        }
        return runCatching {
            val countingBody = buildCountingRequestBody(mimeType, file, progressKey, onProgress)
            val reqBuilder = Request.Builder()
                .url(ticket.signedUrl)
                .header("x-upsert", "true")
            val req = if (ticket.method == "POST") {
                reqBuilder.post(countingBody).build()
            } else {
                reqBuilder.put(countingBody).build()
            }
            val call = client.newCall(req)
            onCallCreated(call)
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty().take(180)
                    return@use WorkerResult.Error(
                        errText.ifBlank { "Supabase direct upload failed (${resp.code})" },
                        resp.code
                    )
                }
                cachedStorageProvider = ticket.provider
                updateTransferProgress(progressKey, 1f)
                onProgress(1f)
                WorkerResult.Success(
                    CloudflareUploadResult(
                        provider = ticket.provider.ifBlank { "SUPABASE_STORAGE" },
                        bucket = ticket.bucket,
                        storagePath = ticket.storagePath,
                        byteSize = file.length(),
                        mimeType = mimeType,
                        sha256 = ticket.sha256
                    )
                )
            }
        }.getOrElse { err ->
            Log.w(TAG, "Supabase direct signed upload error: ${err.message}")
            WorkerResult.Error(err.message ?: "Supabase signed upload error")
        }
    }

    private fun executeUploadWithSignedPreferred(
        idToken: String,
        companyId: String,
        chatId: String,
        scope: String,
        bucket: String,
        fileName: String,
        mimeType: String,
        file: File,
        sha256: String,
        proxyUploadUrl: String,
        progressKey: String,
        onProgress: (Float) -> Unit,
        onCallCreated: (okhttp3.Call) -> Unit
    ): WorkerResult<CloudflareUploadResult> {
        if (shouldPreferSignedSupabase()) {
            val ticketRes = requestSignedUploadTicket(
                idToken = idToken,
                companyId = companyId,
                chatId = chatId,
                scope = scope,
                bucket = bucket,
                fileName = fileName,
                mimeType = mimeType,
                byteSize = file.length(),
                sha256 = sha256
            )
            when (ticketRes) {
                is WorkerResult.Success -> {
                    val directRes = executeSignedDirectPutUpload(
                        ticket = ticketRes.data,
                        mimeType = mimeType,
                        file = file,
                        progressKey = progressKey,
                        onProgress = onProgress,
                        onCallCreated = onCallCreated
                    )
                    if (directRes is WorkerResult.Success) return directRes
                    Log.w(TAG, "Direct Supabase upload failed, falling back to Worker proxy upload.")
                }
                is WorkerResult.Error -> {
                    if (ticketRes.code == 401 || ticketRes.code == 403 || ticketRes.code == 413 || ticketRes.code == 415) {
                        return ticketRes
                    }
                    // e.g. 500 "Supabase Storage is not configured" -> mark R2 & fall back to /v1/storage/upload
                    if (ticketRes.message.contains("not configured", ignoreCase = true)) {
                        cachedStorageProvider = "CLOUDFLARE_R2"
                    }
                }
                is WorkerResult.RateLimited -> return ticketRes
                WorkerResult.NotConfigured -> Unit
            }
        }
        return executeFileUpload(
            uploadUrl = proxyUploadUrl,
            idToken = idToken,
            mimeType = mimeType,
            file = file,
            fallbackBucket = bucket,
            progressKey = progressKey,
            onProgress = onProgress,
            onCallCreated = onCallCreated
        )
    }

    suspend fun uploadChatFileToCloudflare(
        companyId: String,
        chatId: String,
        bucket: String,
        fileName: String,
        mimeType: String,
        file: File,
        sha256: String,
        progressKey: String = sha256.ifBlank { fileName }
    ): WorkerResult<CloudflareUploadResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (!file.exists() || file.length() <= 0L) {
            return@withContext WorkerResult.Error("Attachment file does not exist locally.")
        }
        val uploadUrl = buildUploadUrl(baseUrl(), companyId, chatId, bucket, fileName, sha256)
            ?: return@withContext WorkerResult.NotConfigured

        val initialToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required to authorize upload.")

        updateTransferProgress(progressKey, 0.05f)
        val firstAttempt = executeUploadWithSignedPreferred(
            idToken = initialToken,
            companyId = companyId,
            chatId = chatId,
            scope = "",
            bucket = bucket,
            fileName = fileName,
            mimeType = mimeType,
            file = file,
            sha256 = sha256,
            proxyUploadUrl = uploadUrl,
            progressKey = progressKey,
            onProgress = {},
            onCallCreated = {}
        )
        val finalResult = if (firstAttempt is WorkerResult.Error && firstAttempt.code == 401) {
            val refreshed = fetchFirebaseIdToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != initialToken) {
                executeUploadWithSignedPreferred(
                    idToken = refreshed,
                    companyId = companyId,
                    chatId = chatId,
                    scope = "",
                    bucket = bucket,
                    fileName = fileName,
                    mimeType = mimeType,
                    file = file,
                    sha256 = sha256,
                    proxyUploadUrl = uploadUrl,
                    progressKey = progressKey,
                    onProgress = {},
                    onCallCreated = {}
                )
            } else firstAttempt
        } else firstAttempt
        updateTransferProgress(progressKey, null)
        finalResult
    }

    suspend fun uploadStatusMediaToCloudflare(
        companyId: String,
        bucket: String,
        fileName: String,
        mimeType: String,
        file: File,
        sha256: String,
        progressKey: String,
        onProgress: (Float) -> Unit = {},
        onCallCreated: (okhttp3.Call) -> Unit = {}
    ): WorkerResult<CloudflareUploadResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (!file.exists() || file.length() <= 0L) {
            return@withContext WorkerResult.Error("Status media file does not exist locally.")
        }
        val uploadUrl = buildStatusUploadUrl(baseUrl(), companyId, bucket, fileName, sha256)
            ?: return@withContext WorkerResult.NotConfigured

        val initialToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required to upload status media.")

        updateTransferProgress(progressKey, 0.05f)
        onProgress(0.05f)
        val firstAttempt = executeUploadWithSignedPreferred(
            idToken = initialToken,
            companyId = companyId,
            chatId = "status_feed",
            scope = "status",
            bucket = bucket,
            fileName = fileName,
            mimeType = mimeType,
            file = file,
            sha256 = sha256,
            proxyUploadUrl = uploadUrl,
            progressKey = progressKey,
            onProgress = onProgress,
            onCallCreated = onCallCreated
        )
        val finalResult = if (firstAttempt is WorkerResult.Error && firstAttempt.code == 401) {
            val refreshed = fetchFirebaseIdToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != initialToken) {
                executeUploadWithSignedPreferred(
                    idToken = refreshed,
                    companyId = companyId,
                    chatId = "status_feed",
                    scope = "status",
                    bucket = bucket,
                    fileName = fileName,
                    mimeType = mimeType,
                    file = file,
                    sha256 = sha256,
                    proxyUploadUrl = uploadUrl,
                    progressKey = progressKey,
                    onProgress = onProgress,
                    onCallCreated = onCallCreated
                )
            } else firstAttempt
        } else firstAttempt
        updateTransferProgress(progressKey, null)
        finalResult
    }

    private fun executeDownloadWithSignedPreferred(
        idToken: String,
        companyId: String,
        chatId: String,
        storagePath: String,
        statusId: String = "",
        proxyDownloadUrl: String,
        destination: File,
        progressKey: String
    ): WorkerResult<File> {
        if (shouldPreferSignedSupabase()) {
            val ticketRes = requestSignedDownloadTicket(
                idToken = idToken,
                companyId = companyId,
                chatId = chatId,
                storagePath = storagePath,
                statusId = statusId
            )
            when (ticketRes) {
                is WorkerResult.Success -> {
                    val signedUrl = ticketRes.data.signedUrl
                    if (signedUrl.startsWith("https://")) {
                        val directRes = executeAuthenticatedDownload(
                            downloadUrl = signedUrl,
                            idToken = null,
                            destination = destination,
                            progressKey = progressKey
                        )
                        if (directRes is WorkerResult.Success) {
                            cachedStorageProvider = ticketRes.data.provider
                            return directRes
                        }
                    }
                }
                is WorkerResult.Error -> {
                    if (ticketRes.code == 401 || ticketRes.code == 403 || ticketRes.code == 404) {
                        return ticketRes
                    }
                    if (ticketRes.message.contains("not configured", ignoreCase = true)) {
                        cachedStorageProvider = "CLOUDFLARE_R2"
                    }
                }
                is WorkerResult.RateLimited -> return ticketRes
                WorkerResult.NotConfigured -> Unit
            }
        }
        return executeAuthenticatedDownload(proxyDownloadUrl, idToken, destination, progressKey)
    }

    suspend fun downloadStatusMediaFromCloudflare(
        companyId: String,
        storagePath: String,
        destination: File,
        statusId: String = "",
        progressKey: String = storagePath
    ): WorkerResult<File> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (storagePath.isBlank()) {
            return@withContext WorkerResult.Error("Missing cloud storage path.", 400)
        }
        val downloadUrl = buildStatusDownloadUrl(baseUrl(), companyId, storagePath, statusId)
            ?: return@withContext WorkerResult.NotConfigured
        val initialToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required to download status media.")

        updateTransferProgress(progressKey, 0.05f)
        val firstAttempt = executeDownloadWithSignedPreferred(
            idToken = initialToken,
            companyId = companyId,
            chatId = "status_feed",
            storagePath = storagePath,
            statusId = statusId,
            proxyDownloadUrl = downloadUrl,
            destination = destination,
            progressKey = progressKey
        )
        val finalResult = if (firstAttempt is WorkerResult.Error && firstAttempt.code == 401) {
            val refreshed = fetchFirebaseIdToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != initialToken) {
                executeDownloadWithSignedPreferred(
                    idToken = refreshed,
                    companyId = companyId,
                    chatId = "status_feed",
                    storagePath = storagePath,
                    statusId = statusId,
                    proxyDownloadUrl = downloadUrl,
                    destination = destination,
                    progressKey = progressKey
                )
            } else firstAttempt
        } else firstAttempt
        updateTransferProgress(progressKey, null)
        finalResult
    }

    suspend fun deleteStatusMediaFromCloudflare(
        companyId: String,
        storagePath: String
    ): WorkerResult<Unit> = deleteChatFileFromCloudflare(companyId, "status_feed", storagePath)

    private fun executeFileUpload(
        uploadUrl: String,
        idToken: String,
        mimeType: String,
        file: File,
        fallbackBucket: String,
        progressKey: String = "",
        onProgress: (Float) -> Unit = {},
        onCallCreated: (okhttp3.Call) -> Unit = {}
    ): WorkerResult<CloudflareUploadResult> {
        return runCatching {
            val countingBody = buildCountingRequestBody(mimeType, file, progressKey, onProgress)
            val req = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $idToken")
                .post(countingBody)
                .build()
            val call = client.newCall(req)
            onCallCreated(call)
            call.execute().use { resp ->
                if (resp.code == 429) {
                    val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
                    return@use WorkerResult.RateLimited(retry)
                }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        .orEmpty().ifBlank { "Cloud upload failed (${resp.code})" }
                    return@use WorkerResult.Error(msg, resp.code)
                }
                val json = JSONObject(text)
                val parsed = parseUploadResponse(json, fallbackBucket)
                if (parsed.storagePath.isBlank()) {
                    return@use WorkerResult.Error("Worker returned empty storage path.", 502)
                }
                cachedStorageProvider = parsed.provider
                updateTransferProgress(progressKey, 1f)
                onProgress(1f)
                WorkerResult.Success(parsed)
            }
        }.getOrElse { err ->
            Log.w(TAG, "Worker proxy upload failed: ${err.message}")
            WorkerResult.Error(err.message ?: "Network upload failure")
        }
    }

    suspend fun downloadChatFileFromCloudflare(
        companyId: String,
        chatId: String,
        storagePath: String,
        destination: File,
        progressKey: String = storagePath
    ): WorkerResult<File> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (storagePath.isBlank()) {
            return@withContext WorkerResult.Error("Missing cloud storage path.", 400)
        }
        val downloadUrl = buildDownloadUrl(baseUrl(), companyId, chatId, storagePath)
            ?: return@withContext WorkerResult.NotConfigured

        val initialToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required to download attachment.")

        updateTransferProgress(progressKey, 0.05f)
        val firstAttempt = executeDownloadWithSignedPreferred(
            idToken = initialToken,
            companyId = companyId,
            chatId = chatId,
            storagePath = storagePath,
            proxyDownloadUrl = downloadUrl,
            destination = destination,
            progressKey = progressKey
        )
        val finalResult = if (firstAttempt is WorkerResult.Error && firstAttempt.code == 401) {
            val refreshed = fetchFirebaseIdToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != initialToken) {
                executeDownloadWithSignedPreferred(
                    idToken = refreshed,
                    companyId = companyId,
                    chatId = chatId,
                    storagePath = storagePath,
                    proxyDownloadUrl = downloadUrl,
                    destination = destination,
                    progressKey = progressKey
                )
            } else firstAttempt
        } else firstAttempt
        updateTransferProgress(progressKey, null)
        finalResult
    }

    private fun executeAuthenticatedDownload(
        downloadUrl: String,
        idToken: String?,
        destination: File,
        progressKey: String = ""
    ): WorkerResult<File> {
        return runCatching {
            destination.parentFile?.mkdirs()
            val tempFile = File(destination.parentFile, "${destination.name}.part")
            val reqBuilder = Request.Builder()
                .url(downloadUrl)
                .get()
            if (!idToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $idToken")
            }
            val req = reqBuilder.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
                    return@use WorkerResult.RateLimited(retry)
                }
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        .orEmpty().ifBlank { "Cloud download failed (${resp.code})" }
                    return@use WorkerResult.Error(msg, resp.code)
                }
                val body = resp.body ?: return@use WorkerResult.Error("Empty response body", 502)
                val total = body.contentLength()
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0L) {
                                updateTransferProgress(progressKey, downloaded.toFloat() / total.toFloat())
                            }
                        }
                    }
                }
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    return@use WorkerResult.Error("Downloaded empty attachment", 502)
                }
                if (destination.exists()) destination.delete()
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
                updateTransferProgress(progressKey, 1f)
                WorkerResult.Success(destination)
            }
        }.getOrElse { err ->
            Log.w(TAG, "Cloud download failed: ${err.message}")
            WorkerResult.Error(err.message ?: "File download failed")
        }
    }

    suspend fun deleteChatFileFromCloudflare(
        companyId: String,
        chatId: String,
        storagePath: String
    ): WorkerResult<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured() || storagePath.isBlank()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("chatId", chatId)
            put("storagePath", storagePath)
        }
        runCatching {
            val req = Request.Builder()
                .url("${baseUrl()}/v1/storage/object")
                .header("Authorization", "Bearer $idToken")
                .delete(payload.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    WorkerResult.Success(Unit)
                } else {
                    WorkerResult.Error("Storage delete failed (${resp.code})", resp.code)
                }
            }
        }.getOrElse { err ->
            WorkerResult.Error(err.message ?: "Worker delete failure")
        }
    }

    internal fun parseStorageUsageResponse(json: JSONObject): CloudStorageUsageReport {
        val provider = json.optString("provider", "UNCONFIGURED")
        val defaultQuota = if (provider == "CLOUDFLARE_R2") 10L * 1024L * 1024L * 1024L else 1024L * 1024L * 1024L
        val quotaBytes = json.optLong("quotaBytes", defaultQuota).coerceAtLeast(1L)
        val totalBytes = json.optLong("totalBytes", 0L).coerceAtLeast(0L)
        val objectCount = json.optInt("objectCount", 0).coerceAtLeast(0)
        val truncated = json.optBoolean("truncated", false)

        val catsObj = json.optJSONObject("byCategory") ?: JSONObject()
        val categories = buildList {
            val keys = catsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = catsObj.optJSONObject(key) ?: continue
                add(
                    CloudStorageCategoryUsage(
                        category = key,
                        bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                        count = item.optInt("count", 0).coerceAtLeast(0)
                    )
                )
            }
        }.sortedByDescending { it.bytes }

        val objArr = json.optJSONArray("objects") ?: JSONArray()
        val objects = buildList {
            for (i in 0 until objArr.length()) {
                val item = objArr.optJSONObject(i) ?: continue
                val path = item.optString("storagePath")
                if (path.isBlank()) continue
                add(
                    CloudStorageObjectInfo(
                        bucket = item.optString("bucket", "chat-docs"),
                        storagePath = path,
                        fileName = item.optString("fileName", path.substringAfterLast("/")),
                        byteSize = item.optLong("byteSize", 0L).coerceAtLeast(0L),
                        category = item.optString("category", "other"),
                        chatId = item.optString("chatId"),
                        uploaderUid = item.optString("uploaderUid"),
                        updatedAtMillis = item.optLong("updatedAtMillis", 0L),
                        canDelete = item.optBoolean("canDelete", false)
                    )
                )
            }
        }
        return CloudStorageUsageReport(
            provider = provider,
            quotaBytes = quotaBytes,
            totalBytes = totalBytes,
            objectCount = objectCount,
            truncated = truncated,
            categories = categories,
            objects = objects
        )
    }

    suspend fun fetchCloudStorageUsage(companyId: String): WorkerResult<CloudStorageUsageReport> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext WorkerResult.NotConfigured
            val idToken = fetchFirebaseIdToken(forceRefresh = false)
                ?: return@withContext WorkerResult.Error("Sign in required.")
            val parsedUrl = baseUrl().toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegments("v1/storage/usage")
                ?.addQueryParameter("companyId", companyId)
                ?.build()
                ?.toString()
                ?: return@withContext WorkerResult.NotConfigured

            runCatching {
                val req = Request.Builder()
                    .url(parsedUrl)
                    .header("Authorization", "Bearer $idToken")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 429) {
                        val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
                        return@use WorkerResult.RateLimited(retry)
                    }
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                            .orEmpty().ifBlank { "Storage usage failed (${resp.code})" }
                        return@use WorkerResult.Error(msg, resp.code)
                    }
                    val json = JSONObject(text)
                    val report = parseStorageUsageResponse(json)
                    if (report.provider.isNotBlank() && report.provider != "UNCONFIGURED") {
                        cachedStorageProvider = report.provider
                    }
                    WorkerResult.Success(report)
                }
            }.getOrElse { err ->
                WorkerResult.Error(err.message ?: "Failed to fetch cloud storage usage")
            }
        }

    suspend fun deleteCloudStorageBatch(
        companyId: String,
        targets: List<CloudStorageDeleteTarget>
    ): WorkerResult<Int> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (targets.isEmpty()) return@withContext WorkerResult.Success(0)
        val idToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val arr = JSONArray()
        targets.take(60).forEach { target ->
            arr.put(
                JSONObject().apply {
                    put("bucket", target.bucket)
                    put("storagePath", target.storagePath)
                }
            )
        }
        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("items", arr)
        }
        val batchRes = executeWorkerPost("/v1/storage/delete-batch", idToken, payload) { json ->
            json.optInt("deletedCount", 0)
        }
        if (batchRes is WorkerResult.Error && batchRes.code == 404) {
            var count = 0
            targets.forEach { t ->
                val res = deleteChatFileFromCloudflare(companyId, t.chatId.ifBlank { "general" }, t.storagePath)
                if (res is WorkerResult.Success) count += 1
            }
            return@withContext WorkerResult.Success(count)
        }
        batchRes
    }

    suspend fun dispatchPushNotification(
        companyId: String,
        chatId: String,
        title: String,
        body: String,
        targetUids: List<String>,
        businessKind: String = "",
        businessId: String = "",
        priority: String = "NORMAL"
    ): WorkerResult<Int> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (targetUids.isEmpty()) return@withContext WorkerResult.Success(0)
        val idToken = fetchFirebaseIdToken()
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("chatId", chatId)
            put("title", title.take(120))
            put("body", body.take(240))
            put("targetUids", JSONArray(targetUids.take(35)))
            put("businessKind", businessKind)
            put("businessId", businessId)
            put("priority", priority)
        }
        executeWorkerPost("/v1/notifications/dispatch", idToken, payload) { json ->
            json.optInt("sentCount", 0)
        }
    }

    suspend fun executeResetChunk(
        companyId: String,
        operationId: String,
        cursorCollection: String,
        cursorDocId: String
    ): WorkerResult<ResetChunkResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken()
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("operationId", operationId)
            put("cursorCollection", cursorCollection)
            put("cursorDocId", cursorDocId)
        }
        executeWorkerPost("/v1/admin/reset-chunk", idToken, payload) { json ->
            ResetChunkResult(
                completed = json.optBoolean("completed", false),
                nextCollection = json.optString("nextCollection", ""),
                nextCursorDocId = json.optString("nextCursorDocId", ""),
                deletedDocsInChunk = json.optInt("deletedDocsInChunk", 0),
                deletedStorageInChunk = json.optInt("deletedStorageInChunk", 0)
            )
        }
    }

    fun buildPublicPortalUrl(token: String): String {
        val base = baseUrl()
        return if (base.isNotBlank()) "$base/portal/$token" else "https://portal.xmax.agency/portal/$token"
    }

    suspend fun dispatchPaymentNudge(
        companyId: String,
        projectId: String,
        clientName: String,
        phone: String,
        email: String,
        amountDueFormatted: String,
        dueLabel: String,
        channels: String,
        messageBody: String,
        portalUrl: String = "",
        dispatchKey: String = ""
    ): WorkerResult<NudgeDispatchResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken()
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("projectId", projectId)
            put("clientName", clientName.take(120))
            put("phone", phone.take(40))
            put("email", email.take(160))
            put("amountDueFormatted", amountDueFormatted.take(40))
            put("dueLabel", dueLabel.take(80))
            put("channels", channels)
            put("messageBody", messageBody.take(1000))
            put("portalUrl", portalUrl.take(300))
            put("dispatchKey", dispatchKey.take(120))
        }
        executeWorkerPost("/v1/nudges/dispatch", idToken, payload) { json ->
            NudgeDispatchResult(
                ok = json.optBoolean("ok", true),
                mode = json.optString("mode", "LINK_FALLBACK"),
                whatsappDispatched = json.optBoolean("whatsappDispatched", false),
                emailDispatched = json.optBoolean("emailDispatched", false),
                note = json.optString("note", "")
            )
        }
    }

    private fun <T> executeWorkerPost(
        path: String,
        idToken: String,
        payload: JSONObject,
        parser: (JSONObject) -> T
    ): WorkerResult<T> {
        return runCatching {
            val req = Request.Builder()
                .url("${baseUrl()}$path")
                .header("Authorization", "Bearer $idToken")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
                    return@use WorkerResult.RateLimited(retry)
                }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        .orEmpty().ifBlank { "Worker error ${resp.code}" }
                    return@use WorkerResult.Error(msg, resp.code)
                }
                val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                WorkerResult.Success(parser(json))
            }
        }.getOrElse { err ->
            Log.w(TAG, "Worker call $path failed: ${err.message}")
            WorkerResult.Error(err.message ?: "Worker connection error")
        }
    }
}
