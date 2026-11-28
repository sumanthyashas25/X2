package com.example.data

import android.content.Context
import com.example.BuildConfig
import com.example.chat.remote.WorkerBridgeClient
import com.example.chat.remote.WorkerHealthStatus
import com.example.chat.remote.WorkerResult
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class ServiceHealthState(val label: String) {
    UNKNOWN("Not Checked"),
    CHECKING("Checking…"),
    HEALTHY("Working"),
    NOT_CONFIGURED("Setup Required"),
    DEGRADED("Partial"),
    ERROR("Issue Detected")
}

data class ServiceDiagnosticStatus(
    val serviceName: String,
    val state: ServiceHealthState = ServiceHealthState.UNKNOWN,
    val summary: String = "Tap Run Check to inspect connection.",
    val detail: String = "",
    val checkedAtMillis: Long = 0L
)

data class BackendDiagnosticReport(
    val firebase: ServiceDiagnosticStatus = ServiceDiagnosticStatus(serviceName = "Firebase (Auth & Firestore)"),
    val cloudflare: ServiceDiagnosticStatus = ServiceDiagnosticStatus(serviceName = "Cloudflare Worker & R2"),
    val supabase: ServiceDiagnosticStatus = ServiceDiagnosticStatus(serviceName = "Supabase Storage"),
    val isRunning: Boolean = false,
    val lastCheckedMillis: Long = 0L
)

object BackendHealthDiagnostics {

    suspend fun runFullDiagnostics(
        context: Context,
        companyId: String
    ): BackendDiagnosticReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val firebaseStatus = checkFirebase(context, companyId, now)
        val (cloudflareStatus, healthData) = checkCloudflare(companyId, now)
        val supabaseStatus = checkSupabase(companyId, healthData, now)
        BackendDiagnosticReport(
            firebase = firebaseStatus,
            cloudflare = cloudflareStatus,
            supabase = supabaseStatus,
            isRunning = false,
            lastCheckedMillis = now
        )
    }

    private suspend fun checkFirebase(
        context: Context,
        companyId: String,
        checkedAt: Long
    ): ServiceDiagnosticStatus {
        val config = FirebaseStartup.inspectConfig(context)
        if (!config.isHealthy) {
            return ServiceDiagnosticStatus(
                serviceName = "Firebase (Auth & Firestore)",
                state = ServiceHealthState.ERROR,
                summary = config.issueMessage ?: "Firebase configuration invalid.",
                detail = "Project=${config.projectId.ifBlank { "none" }} • DB=${config.databaseId.ifBlank { "none" }}",
                checkedAtMillis = checkedAt
            )
        }

        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (currentUser == null) {
            return ServiceDiagnosticStatus(
                serviceName = "Firebase (Auth & Firestore)",
                state = ServiceHealthState.DEGRADED,
                summary = "Config valid; no signed-in user session.",
                detail = "Project ${config.projectId} • DB ${config.databaseId}",
                checkedAtMillis = checkedAt
            )
        }

        val tokenOk = runCatching { currentUser.getIdToken(false).await()?.token.orEmpty().isNotBlank() }.getOrDefault(false)
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull()
        val db = app?.let { runCatching { FirebaseFirestore.getInstance(it, config.databaseId) }.getOrNull() }
            ?: return ServiceDiagnosticStatus(
                serviceName = "Firebase (Auth & Firestore)",
                state = ServiceHealthState.ERROR,
                summary = "Named Firestore instance unavailable.",
                detail = "DB=${config.databaseId}",
                checkedAtMillis = checkedAt
            )

        return runCatching {
            val companyDoc = db.collection("companies").document(companyId).get(Source.SERVER).await()
            val memberDoc = db.collection("companies").document(companyId)
                .collection("members").document(currentUser.uid).get(Source.SERVER).await()
            val detailText = buildString {
                append("Project: ").append(config.projectId)
                append(" • Token: ").append(if (tokenOk) "OK" else "Missing")
                append(" • Workspace Doc: ").append(if (companyDoc.exists()) "Live" else "Not Bootstrapped")
                append(" • Member Doc: ").append(if (memberDoc.exists()) "Synced" else "Missing")
            }
            ServiceDiagnosticStatus(
                serviceName = "Firebase (Auth & Firestore)",
                state = ServiceHealthState.HEALTHY,
                summary = "Connected live to Firebase Auth & Firestore server.",
                detail = detailText,
                checkedAtMillis = checkedAt
            )
        }.getOrElse { err ->
            val fallbackCacheOk = runCatching {
                db.collection("companies").document(companyId).get(Source.CACHE).await().exists()
            }.getOrDefault(false)
            ServiceDiagnosticStatus(
                serviceName = "Firebase (Auth & Firestore)",
                state = if (fallbackCacheOk) ServiceHealthState.DEGRADED else ServiceHealthState.ERROR,
                summary = err.localizedMessage ?: "Firestore server check failed.",
                detail = "Project ${config.projectId} • DB ${config.databaseId} • Cache=${if (fallbackCacheOk) "Warm" else "Empty"}",
                checkedAtMillis = checkedAt
            )
        }
    }

    private suspend fun checkCloudflare(
        companyId: String,
        checkedAt: Long
    ): Pair<ServiceDiagnosticStatus, WorkerHealthStatus?> {
        if (!WorkerBridgeClient.isConfigured()) {
            val currentVal = BuildConfig.CHAT_WORKER_BASE_URL.ifBlank { "(unset)" }
            return ServiceDiagnosticStatus(
                serviceName = "Cloudflare Worker & R2",
                state = ServiceHealthState.NOT_CONFIGURED,
                summary = "Worker URL not configured in Secrets (CHAT_WORKER_BASE_URL).",
                detail = "Current URL: $currentVal. Deploy backend/cloudflare-worker via Wrangler and add CHAT_WORKER_BASE_URL in AI Studio Secrets.",
                checkedAtMillis = checkedAt
            ) to null
        }

        return when (val health = WorkerBridgeClient.checkHealth()) {
            is WorkerResult.Success -> {
                val h = health.data
                val projectMatches = h.firebaseProjectId == FirebaseStartup.EXPECTED_PROJECT_ID &&
                    h.firestoreDbId == FirebaseStartup.EXPECTED_DATABASE_ID
                val authRes = WorkerBridgeClient.verifyAuth(companyId)
                val authLine = when (authRes) {
                    is WorkerResult.Success -> "Auth verify OK (${authRes.data.email.ifBlank { authRes.data.uid }})"
                    is WorkerResult.Error -> "Auth verify issue: ${authRes.message}"
                    is WorkerResult.RateLimited -> "Auth verify rate-limited"
                    WorkerResult.NotConfigured -> "Worker URL unset"
                }
                val state = when {
                    !h.ok || !projectMatches -> ServiceHealthState.DEGRADED
                    authRes is WorkerResult.Success -> ServiceHealthState.HEALTHY
                    else -> ServiceHealthState.DEGRADED
                }
                ServiceDiagnosticStatus(
                    serviceName = "Cloudflare Worker & R2",
                    state = state,
                    summary = if (state == ServiceHealthState.HEALTHY) {
                        "Cloudflare Worker reachable (${h.storageProvider}, v${h.workerVersion.ifBlank { "1" }})."
                    } else {
                        "Cloudflare Worker reachable with warnings."
                    },
                    detail = "R2=${if (h.r2Configured) "Bound" else "Not Bound"} • FCM=${if (h.fcmConfigured) "Ready" else "Unset"} • $authLine",
                    checkedAtMillis = checkedAt
                ) to h
            }
            is WorkerResult.Error -> ServiceDiagnosticStatus(
                serviceName = "Cloudflare Worker & R2",
                state = ServiceHealthState.ERROR,
                summary = health.message,
                detail = "Endpoint: ${BuildConfig.CHAT_WORKER_BASE_URL}",
                checkedAtMillis = checkedAt
            ) to null
            is WorkerResult.RateLimited -> ServiceDiagnosticStatus(
                serviceName = "Cloudflare Worker & R2",
                state = ServiceHealthState.DEGRADED,
                summary = "Rate limited; retry in ${health.retryAfterSeconds}s.",
                detail = "Endpoint: ${BuildConfig.CHAT_WORKER_BASE_URL}",
                checkedAtMillis = checkedAt
            ) to null
            WorkerResult.NotConfigured -> ServiceDiagnosticStatus(
                serviceName = "Cloudflare Worker & R2",
                state = ServiceHealthState.NOT_CONFIGURED,
                summary = "Worker URL not configured.",
                detail = "Set CHAT_WORKER_BASE_URL in AI Studio Secrets.",
                checkedAtMillis = checkedAt
            ) to null
        }
    }

    private suspend fun checkSupabase(
        companyId: String,
        health: WorkerHealthStatus?,
        checkedAt: Long
    ): ServiceDiagnosticStatus {
        if (!WorkerBridgeClient.isConfigured()) {
            return ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.NOT_CONFIGURED,
                summary = "Requires Cloudflare Worker bridge (CHAT_WORKER_BASE_URL).",
                detail = "Run backend/supabase/storage_setup.sql in Supabase SQL Editor and set SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY via Wrangler secrets.",
                checkedAtMillis = checkedAt
            )
        }
        if (health == null) {
            return ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.ERROR,
                summary = "Cannot inspect Supabase because Cloudflare Worker did not respond.",
                detail = "Fix CHAT_WORKER_BASE_URL connectivity first.",
                checkedAtMillis = checkedAt
            )
        }
        if (!health.supabaseConfigured) {
            return ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.NOT_CONFIGURED,
                summary = if (health.r2Configured) {
                    "Supabase secrets unset on Worker (R2 fallback active)."
                } else {
                    "Supabase secrets unset on Cloudflare Worker."
                },
                detail = "Run: npx wrangler secret put SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY.",
                checkedAtMillis = checkedAt
            )
        }

        if (health.storageProvider == "CLOUDFLARE_R2") {
            return ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.HEALTHY,
                summary = "Supabase configured and standing by (Cloudflare R2 primary).",
                detail = "Active provider=${health.storageProvider} • Signed Supabase endpoints ready.",
                checkedAtMillis = checkedAt
            )
        }

        return when (val usage = WorkerBridgeClient.fetchCloudStorageUsage(companyId)) {
            is WorkerResult.Success -> ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.HEALTHY,
                summary = "Supabase Storage live (${usage.data.objectCount} objects, ${formatStorageBytes(usage.data.totalBytes)}).",
                detail = "Provider=${usage.data.provider} • Quota=${formatStorageBytes(usage.data.quotaBytes)}",
                checkedAtMillis = checkedAt
            )
            is WorkerResult.Error -> ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.ERROR,
                summary = usage.message,
                detail = "Verify storage_setup.sql buckets (avatars, group-photos, chat-media, chat-voice, chat-videos, chat-docs) and service_role key.",
                checkedAtMillis = checkedAt
            )
            is WorkerResult.RateLimited -> ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.DEGRADED,
                summary = "Storage probe rate-limited (${usage.retryAfterSeconds}s).",
                detail = "",
                checkedAtMillis = checkedAt
            )
            WorkerResult.NotConfigured -> ServiceDiagnosticStatus(
                serviceName = "Supabase Storage",
                state = ServiceHealthState.NOT_CONFIGURED,
                summary = "Worker bridge not configured.",
                detail = "",
                checkedAtMillis = checkedAt
            )
        }
    }
}
