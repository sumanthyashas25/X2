package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.XMaxTheme
import com.example.ui.theme.XMaxType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object NetworkConnectivityMonitor {
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isSyncingPendingWrites = MutableStateFlow(false)
    val isSyncingPendingWrites: StateFlow<Boolean> = _isSyncingPendingWrites.asStateFlow()

    private val _lastSyncedAtMillis = MutableStateFlow(0L)
    val lastSyncedAtMillis: StateFlow<Long> = _lastSyncedAtMillis.asStateFlow()

    @Volatile
    private var registered = false

    @Volatile
    private var wasOffline = false

    @Volatile
    private var appContext: Context? = null

    private fun resolveFirestore(context: Context): FirebaseFirestore? =
        runCatching { FirestoreInitializationService.getInstance(context).currentReadyFirestore() }.getOrNull()

    private fun hasActiveInternet(cm: ConnectivityManager): Boolean {
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun handleOnlineRestored(context: Context) {
        val fs = resolveFirestore(context) ?: return
        fs.enableNetwork()
        val shouldMarkSyncing = wasOffline
        if (shouldMarkSyncing) {
            _isSyncingPendingWrites.value = true
        }
        fs.waitForPendingWrites().addOnCompleteListener { task ->
            _isSyncingPendingWrites.value = false
            if (task.isSuccessful && shouldMarkSyncing) {
                wasOffline = false
                _lastSyncedAtMillis.value = System.currentTimeMillis()
            }
        }
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (registered) return
        synchronized(this) {
            if (registered) return
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching {
                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _isOnline.value = true
                        handleOnlineRestored(appCtx)
                    }

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        val hasNet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        if (hasNet) {
                            _isOnline.value = true
                            handleOnlineRestored(appCtx)
                        } else {
                            val onlineNow = hasActiveInternet(cm)
                            if (!onlineNow) wasOffline = true
                            _isOnline.value = onlineNow
                        }
                    }

                    override fun onLost(network: Network) {
                        val stillOnline = hasActiveInternet(cm)
                        if (!stillOnline) wasOffline = true
                        _isOnline.value = stillOnline
                        if (stillOnline) {
                            handleOnlineRestored(appCtx)
                        }
                    }
                })
                registered = true
            }
            // Only mark offline if ConnectivityManager reports an explicit state and has no internet
            if (cm.activeNetwork != null) {
                val online = hasActiveInternet(cm)
                if (!online) wasOffline = true
                _isOnline.value = online
            }
        }
    }

    fun forceReconnect(context: Context? = appContext) {
        val ctx = context ?: return
        handleOnlineRestored(ctx)
    }
}

@Composable
fun FirestoreConnectionBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = XMaxTheme.colors
    val isOnline by NetworkConnectivityMonitor.isOnline.collectAsState()
    val isSyncingPending by NetworkConnectivityMonitor.isSyncingPendingWrites.collectAsState()
    val lastSyncedAt by NetworkConnectivityMonitor.lastSyncedAtMillis.collectAsState()
    var isAttemptingReconnect by remember { mutableStateOf(false) }
    var showSyncedConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        NetworkConnectivityMonitor.init(context)
    }

    LaunchedEffect(lastSyncedAt) {
        if (lastSyncedAt > 0L) {
            showSyncedConfirmation = true
            delay(3_500L)
            showSyncedConfirmation = false
        }
    }

    // Automatic non-blocking reconnect attempt loop when offline
    LaunchedEffect(isOnline) {
        if (!isOnline) {
            while (!isOnline) {
                delay(10_000L)
                isAttemptingReconnect = true
                NetworkConnectivityMonitor.forceReconnect(context)
                delay(1_500L)
                isAttemptingReconnect = false
            }
        }
    }

    AnimatedVisibility(
        visible = !isOnline || isSyncingPending || showSyncedConfirmation,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val containerColor = when {
            !isOnline -> MaterialTheme.colorScheme.errorContainer
            isSyncingPending -> palette.skyBg
            else -> palette.mintBg
        }
        val contentColor = when {
            !isOnline -> MaterialTheme.colorScheme.onErrorContainer
            isSyncingPending -> palette.skyInk
            else -> palette.mintInk
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("firestore_offline_banner"),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when {
                            !isOnline -> Icons.Filled.CloudOff
                            isSyncingPending -> Icons.Filled.CloudSync
                            else -> Icons.Filled.CloudDone
                        },
                        contentDescription = if (!isOnline) "Offline" else if (isSyncingPending) "Syncing" else "Synced",
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = when {
                                !isOnline && isAttemptingReconnect -> "Reconnecting to Firestore…"
                                !isOnline -> "Offline Mode — Cached Data Active"
                                isSyncingPending -> "Back Online — Syncing Queued Changes…"
                                else -> "Back Online — Projects & Invoices Synced"
                            },
                            style = XMaxType.subheadlineSemibold,
                            color = contentColor
                        )
                        Text(
                            text = when {
                                !isOnline -> "View cached projects & invoices. Edits sync automatically when online."
                                isSyncingPending -> "Uploading pending offline changes to Firestore."
                                else -> "All offline updates are now synced to cloud."
                            },
                            style = XMaxType.footnote,
                            color = contentColor.copy(alpha = 0.85f)
                        )
                    }
                }
                if (!isOnline) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isAttemptingReconnect = true
                                NetworkConnectivityMonitor.forceReconnect(context)
                                delay(1_200L)
                                isAttemptingReconnect = false
                            }
                        },
                        modifier = Modifier.testTag("firestore_reconnect_button")
                    ) {
                        if (isAttemptingReconnect) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = contentColor
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Retry Connection",
                                tint = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
