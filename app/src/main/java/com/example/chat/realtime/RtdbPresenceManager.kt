package com.example.chat.realtime

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.BuildConfig
import com.example.data.AgencyRepository
import com.example.data.FirebaseStartup
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

enum class MemberAvailability(val label: String) {
    ACTIVE("Active"),
    BUSY("Busy"),
    AWAY("Away")
}

data class MemberPresenceState(
    val uid: String = "",
    val online: Boolean = false,
    val lastSeenMillis: Long = 0L,
    val availability: MemberAvailability = MemberAvailability.AWAY
)

data class EphemeralChatActivity(
    val typingNames: List<String> = emptyList(),
    val recordingNames: List<String> = emptyList()
)

/**
 * Spark-tier Firebase Realtime Database manager for ephemeral online presence,
 * member availability (Active / Busy / Away), typing indicators, and voice-recording indicators.
 * Disconnects cleanly on background to conserve Spark's 100 concurrent connection limit.
 */
object RtdbPresenceManager : DefaultLifecycleObserver {
    private const val TAG = "XMaxRtdbPresence"
    private const val TYPING_TTL_MS = 4500L
    private const val RECORDING_TTL_MS = 125_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerRegistered = false
    private var currentUid: String = ""
    private var currentCompanyId: String = "xmax"
    private var currentAvailability: MemberAvailability = MemberAvailability.ACTIVE
    private var currentDeviceId: String = "android"
    private var typingClearJob: Job? = null
    private var activeTypingChatId: String = ""
    private var lastFirestorePresenceKey: String = ""
    private var lastFirestorePresenceAtMs: Long = 0L

    internal fun resolveRtdbUrl(
        configuredUrl: String = BuildConfig.FIREBASE_RTDB_URL,
        activeProjectId: String? = null,
        optionsDatabaseUrl: String? = null
    ): String {
        val trimmed = configuredUrl.trim()
        val projectId = activeProjectId?.trim().orEmpty().ifBlank { FirebaseStartup.EXPECTED_PROJECT_ID }
        val optionsUrl = optionsDatabaseUrl?.trim().orEmpty()
        if (trimmed.isNotEmpty() && (!trimmed.contains("-default-rtdb.") || trimmed.contains(projectId))) {
            return trimmed
        }
        if (optionsUrl.isNotEmpty() && (!optionsUrl.contains("-default-rtdb.") || optionsUrl.contains(projectId))) {
            return optionsUrl
        }
        return "https://$projectId-default-rtdb.firebaseio.com"
    }

    private fun resolveDb(context: Context? = null): FirebaseDatabase? {
        FirebaseStartup.ensureInitialized(context)
        return runCatching {
            val options = com.google.firebase.FirebaseApp.getInstance().options
            val url = resolveRtdbUrl(
                configuredUrl = BuildConfig.FIREBASE_RTDB_URL,
                activeProjectId = options.projectId,
                optionsDatabaseUrl = options.databaseUrl
            )
            FirebaseDatabase.getInstance(url)
        }.onFailure { err ->
            Log.d(TAG, "RTDB unavailable (configure FIREBASE_RTDB_URL if RTDB region differs): ${err.message}")
        }.getOrNull()
    }

    private fun resolveFirestore(context: Context? = null): FirebaseFirestore? {
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return null
        val dbId = runCatching {
            context?.getString(com.example.R.string.firestore_database_id)?.trim()
        }.getOrNull().orEmpty().ifBlank {
            AgencyRepository.TARGET_FIRESTORE_DATABASE_ID
        }
        return runCatching { FirebaseFirestore.getInstance(app, dbId) }
            .getOrElse { runCatching { FirebaseFirestore.getInstance(dbId) }.getOrNull() }
    }

    private fun rootRef(companyId: String, context: Context? = null): DatabaseReference? {
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        return resolveDb(context)?.reference?.child("companies")?.child(safeCompany)
    }

    fun bindUserSession(context: Context, companyId: String, uid: String) {
        currentUid = uid
        currentCompanyId = companyId.trim().ifBlank { "xmax" }
        currentDeviceId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                ?.take(32)
        }.getOrNull().orEmpty().ifBlank { "device_default" }

        if (!observerRegistered) {
            observerRegistered = true
            CoroutineScope(Dispatchers.Main).launch {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this@RtdbPresenceManager)
            }
        }
        setOnline(context, currentCompanyId, uid, online = true)
    }

    fun unbindUserSession(context: Context, companyId: String) {
        val uid = currentUid
        val cid = companyId.trim().ifBlank { currentCompanyId }
        if (uid.isNotBlank()) {
            setOnline(context, cid, uid, online = false)
        }
        currentUid = ""
    }

    override fun onStart(owner: LifecycleOwner) {
        val uid = currentUid
        if (uid.isNotBlank()) {
            setOnline(null, currentCompanyId, uid, online = true)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val uid = currentUid
        if (uid.isNotBlank()) {
            setOnline(null, currentCompanyId, uid, online = false)
        }
    }

    fun setAvailability(
        context: Context? = null,
        companyId: String = currentCompanyId,
        uid: String = currentUid,
        availability: MemberAvailability
    ) {
        currentAvailability = availability
        if (uid.isBlank()) return
        val cid = companyId.trim().ifBlank { currentCompanyId }
        val isOnline = availability != MemberAvailability.AWAY
        val ref = rootRef(cid, context)?.child("presence")?.child(uid)?.child(currentDeviceId)
        if (ref != null) {
            runCatching {
                ref.onDisconnect().setValue(
                    mapOf(
                        "online" to false,
                        "status" to MemberAvailability.AWAY.name,
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                )
                ref.setValue(
                    mapOf(
                        "online" to isOnline,
                        "status" to availability.name,
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                )
            }
            return
        }
        val firestore = resolveFirestore(context) ?: return
        scope.launch {
            runCatching {
                val payload = mapOf(
                    "uid" to uid,
                    "isOnline" to isOnline,
                    "statusMessage" to availability.label,
                    "lastSeenAtMillis" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("companies").document(cid)
                    .collection("presence").document(uid).set(payload)
            }
        }
    }

    private fun setOnline(context: Context?, companyId: String, uid: String, online: Boolean) {
        if (uid.isBlank()) return
        val ref = rootRef(companyId, context)?.child("presence")?.child(uid)?.child(currentDeviceId)
        if (ref != null) {
            runCatching {
                if (online) {
                    val offlinePayload = mapOf(
                        "online" to false,
                        "status" to MemberAvailability.AWAY.name,
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                    ref.onDisconnect().setValue(offlinePayload)
                    ref.setValue(
                        mapOf(
                            "online" to true,
                            "status" to currentAvailability.name,
                            "lastSeen" to ServerValue.TIMESTAMP
                        )
                    )
                } else {
                    ref.setValue(
                        mapOf(
                            "online" to false,
                            "status" to MemberAvailability.AWAY.name,
                            "lastSeen" to ServerValue.TIMESTAMP
                        )
                    )
                }
            }
            return
        }
        val firestore = resolveFirestore(context) ?: return
        val now = System.currentTimeMillis()
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        val writeKey = "$safeCompany|$uid|$online|${currentAvailability.name}"
        val shouldWrite = !online ||
            writeKey != lastFirestorePresenceKey ||
            (now - lastFirestorePresenceAtMs) > 20_000L
        if (shouldWrite) {
            lastFirestorePresenceKey = writeKey
            lastFirestorePresenceAtMs = now
            scope.launch {
                runCatching {
                    val payload = mapOf(
                        "uid" to uid,
                        "isOnline" to online,
                        "statusMessage" to currentAvailability.label,
                        "lastSeenAtMillis" to System.currentTimeMillis(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    firestore.collection("companies").document(safeCompany)
                        .collection("presence").document(uid).set(payload)
                }
            }
        }
    }

    fun setTyping(companyId: String, chatId: String, uid: String, displayName: String, isTyping: Boolean) {
        if (chatId.isBlank() || uid.isBlank()) return
        val ref = rootRef(companyId)?.child("typing")?.child(chatId)?.child(uid)
        if (ref != null) {
            typingClearJob?.cancel()
            runCatching {
                if (isTyping) {
                    activeTypingChatId = chatId
                    ref.onDisconnect().removeValue()
                    ref.setValue(
                        mapOf(
                            "name" to displayName.take(60),
                            "ts" to System.currentTimeMillis()
                        )
                    )
                    typingClearJob = scope.launch {
                        delay(TYPING_TTL_MS)
                        runCatching { ref.removeValue() }
                    }
                } else {
                    ref.removeValue()
                }
            }
            return
        }
        val firestore = resolveFirestore() ?: return
        scope.launch {
            runCatching {
                val safeCompany = companyId.trim().ifBlank { "xmax" }
                val payload = mapOf(
                    "isTyping" to isTyping,
                    "isRecording" to false,
                    "userName" to displayName.take(60),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("companies").document(safeCompany)
                    .collection("chat_channels").document(chatId)
                    .collection("ephemeral").document(uid).set(payload)
            }
        }
    }

    fun setRecording(companyId: String, chatId: String, uid: String, displayName: String, isRecording: Boolean) {
        if (chatId.isBlank() || uid.isBlank()) return
        val ref = rootRef(companyId)?.child("recording")?.child(chatId)?.child(uid)
        if (ref != null) {
            runCatching {
                if (isRecording) {
                    ref.onDisconnect().removeValue()
                    ref.setValue(
                        mapOf(
                            "name" to displayName.take(60),
                            "ts" to System.currentTimeMillis()
                        )
                    )
                } else {
                    ref.removeValue()
                }
            }
            return
        }
        val firestore = resolveFirestore() ?: return
        scope.launch {
            runCatching {
                val safeCompany = companyId.trim().ifBlank { "xmax" }
                val payload = mapOf(
                    "isTyping" to false,
                    "isRecording" to isRecording,
                    "userName" to displayName.take(60),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("companies").document(safeCompany)
                    .collection("chat_channels").document(chatId)
                    .collection("ephemeral").document(uid).set(payload)
            }
        }
    }

    private fun parseMemberSnapshot(uid: String, snapshot: DataSnapshot): MemberPresenceState {
        var anyOnline = false
        var anyBusy = false
        var anyActive = false
        var latestSeen = 0L
        for (child in snapshot.children) {
            val isOnline = child.child("online").getValue(Boolean::class.java) == true
            val seen = child.child("lastSeen").getValue(Long::class.java) ?: 0L
            val rawStatus = child.child("status").getValue(String::class.java).orEmpty()
            val parsedStatus = runCatching { MemberAvailability.valueOf(rawStatus.uppercase()) }.getOrNull()
            if (isOnline) {
                anyOnline = true
                when (parsedStatus) {
                    MemberAvailability.BUSY -> anyBusy = true
                    MemberAvailability.ACTIVE, null -> anyActive = true
                    MemberAvailability.AWAY -> Unit
                }
            }
            if (seen > latestSeen) latestSeen = seen
        }
        val availability = when {
            !anyOnline -> MemberAvailability.AWAY
            anyBusy -> MemberAvailability.BUSY
            anyActive -> MemberAvailability.ACTIVE
            else -> MemberAvailability.AWAY
        }
        return MemberPresenceState(
            uid = uid,
            online = anyOnline,
            lastSeenMillis = latestSeen,
            availability = availability
        )
    }

    fun resolveAvailability(member: com.example.model.Member?, presence: MemberPresenceState?): MemberAvailability {
        if (presence != null) {
            if (!presence.online) return MemberAvailability.AWAY
            return presence.availability
        }
        val lastActive = member?.lastActiveAtMillis ?: 0L
        return if (lastActive > 0L && (System.currentTimeMillis() - lastActive) < 15 * 60_000L) {
            MemberAvailability.ACTIVE
        } else {
            MemberAvailability.AWAY
        }
    }

    fun isMemberOnlineNow(
        member: com.example.model.Member?,
        presence: MemberPresenceState?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (presence != null) {
            if (presence.online) return true
            val memberActiveAt = member?.lastActiveAtMillis ?: 0L
            val memberStatus = member?.availabilityStatus?.uppercase().orEmpty()
            return memberActiveAt > presence.lastSeenMillis &&
                (nowMillis - memberActiveAt) < 5 * 60_000L &&
                memberStatus != "AWAY"
        }
        val lastActive = member?.lastActiveAtMillis ?: 0L
        return lastActive > 0L &&
            (nowMillis - lastActive) < 5 * 60_000L &&
            member?.availabilityStatus?.uppercase() != "AWAY"
    }

    fun effectiveLastSeenMillis(member: com.example.model.Member?, presence: MemberPresenceState?): Long {
        val rtdbSeen = presence?.lastSeenMillis ?: 0L
        val memberSeen = member?.lastActiveAtMillis ?: 0L
        return maxOf(rtdbSeen, memberSeen)
    }

    fun observeMemberPresence(companyId: String, targetUid: String): Flow<MemberPresenceState> {
        if (targetUid.isBlank()) return flowOf(MemberPresenceState())
        val ref = rootRef(companyId)?.child("presence")?.child(targetUid)
        if (ref != null) {
            return callbackFlow {
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        trySend(parseMemberSnapshot(targetUid, snapshot))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        trySend(MemberPresenceState(uid = targetUid))
                    }
                }
                ref.addValueEventListener(listener)
                awaitClose { ref.removeEventListener(listener) }
            }
        }
        val firestore = resolveFirestore() ?: return flowOf(MemberPresenceState(uid = targetUid))
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        return callbackFlow {
            val reg = firestore.collection("companies").document(safeCompany)
                .collection("presence").document(targetUid)
                .addSnapshotListener { doc, err ->
                    if (err != null) {
                        trySend(MemberPresenceState(uid = targetUid))
                        return@addSnapshotListener
                    }
                    if (doc != null && doc.exists()) {
                        val online = doc.getBoolean("isOnline") == true
                        val lastSeen = doc.getLong("lastSeenAtMillis") ?: 0L
                        val statusMsg = doc.getString("statusMessage").orEmpty()
                        val avail = if (online) {
                            if (statusMsg.contains("Busy", ignoreCase = true)) MemberAvailability.BUSY else MemberAvailability.ACTIVE
                        } else MemberAvailability.AWAY
                        trySend(MemberPresenceState(uid = targetUid, online = online, lastSeenMillis = lastSeen, availability = avail))
                    } else {
                        trySend(MemberPresenceState(uid = targetUid))
                    }
                }
            awaitClose { reg.remove() }
        }
    }

    fun observeCompanyPresence(companyId: String): Flow<Map<String, MemberPresenceState>> {
        val ref = rootRef(companyId)?.child("presence")
        if (ref != null) {
            return callbackFlow {
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val map = buildMap<String, MemberPresenceState> {
                            for (userChild in snapshot.children) {
                                val uid = userChild.key ?: continue
                                put(uid, parseMemberSnapshot(uid, userChild))
                            }
                        }
                        trySend(map)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        trySend(emptyMap())
                    }
                }
                ref.addValueEventListener(listener)
                awaitClose { ref.removeEventListener(listener) }
            }
        }
        val firestore = resolveFirestore() ?: return flowOf(emptyMap())
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        return callbackFlow {
            val reg = firestore.collection("companies").document(safeCompany)
                .collection("presence")
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyMap())
                        return@addSnapshotListener
                    }
                    val map = mutableMapOf<String, MemberPresenceState>()
                    snap?.documents?.forEach { doc ->
                        val uid = doc.id
                        val online = doc.getBoolean("isOnline") == true
                        val lastSeen = doc.getLong("lastSeenAtMillis") ?: 0L
                        val statusMsg = doc.getString("statusMessage").orEmpty()
                        val avail = if (online) {
                            if (statusMsg.contains("Busy", ignoreCase = true)) MemberAvailability.BUSY else MemberAvailability.ACTIVE
                        } else MemberAvailability.AWAY
                        map[uid] = MemberPresenceState(uid = uid, online = online, lastSeenMillis = lastSeen, availability = avail)
                    }
                    trySend(map)
                }
            awaitClose { reg.remove() }
        }
    }

    fun observeChatActivity(companyId: String, chatId: String, myUid: String): Flow<EphemeralChatActivity> {
        if (chatId.isBlank()) return flowOf(EphemeralChatActivity())
        val base = rootRef(companyId)
        if (base != null) {
            val typingRef = base.child("typing").child(chatId)
            val recordingRef = base.child("recording").child(chatId)

            return callbackFlow {
                var latestTyping = emptyList<String>()
                var latestRecording = emptyList<String>()

                fun emitCombined() {
                    trySend(EphemeralChatActivity(typingNames = latestTyping, recordingNames = latestRecording))
                }

                val typingListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val now = System.currentTimeMillis()
                        latestTyping = snapshot.children.mapNotNull { child ->
                            if (child.key == myUid) return@mapNotNull null
                            val ts = child.child("ts").getValue(Long::class.java) ?: 0L
                            if (now - ts > TYPING_TTL_MS + 2000L) return@mapNotNull null
                            child.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                        }
                        emitCombined()
                    }
                    override fun onCancelled(error: DatabaseError) {}
                }

                val recordingListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val now = System.currentTimeMillis()
                        latestRecording = snapshot.children.mapNotNull { child ->
                            if (child.key == myUid) return@mapNotNull null
                            val ts = child.child("ts").getValue(Long::class.java) ?: 0L
                            if (now - ts > RECORDING_TTL_MS) return@mapNotNull null
                            child.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                        }
                        emitCombined()
                    }
                    override fun onCancelled(error: DatabaseError) {}
                }

                typingRef.addValueEventListener(typingListener)
                recordingRef.addValueEventListener(recordingListener)
                awaitClose {
                    typingRef.removeEventListener(typingListener)
                    recordingRef.removeEventListener(recordingListener)
                }
            }
        }
        val firestore = resolveFirestore() ?: return flowOf(EphemeralChatActivity())
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        return callbackFlow {
            val reg = firestore.collection("companies").document(safeCompany)
                .collection("chat_channels").document(chatId)
                .collection("ephemeral")
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(EphemeralChatActivity())
                        return@addSnapshotListener
                    }
                    val typers = mutableListOf<String>()
                    val recorders = mutableListOf<String>()
                    snap?.documents?.forEach { doc ->
                        if (doc.id == myUid) return@forEach
                        val isTyping = doc.getBoolean("isTyping") == true
                        val isRecording = doc.getBoolean("isRecording") == true
                        val userName = doc.getString("userName")?.ifBlank { "Member" } ?: "Member"
                        if (isTyping) typers.add(userName)
                        if (isRecording) recorders.add(userName)
                    }
                    trySend(EphemeralChatActivity(typingNames = typers, recordingNames = recorders))
                }
            awaitClose { reg.remove() }
        }
    }

    suspend fun clearAllEphemeralState(companyId: String) {
        val base = rootRef(companyId) ?: return
        runCatching { base.child("typing").removeValue() }
        runCatching { base.child("recording").removeValue() }
    }
}
