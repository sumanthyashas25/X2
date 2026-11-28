package com.example.data

import com.example.model.ActivityCategory
import com.example.model.Client
import com.example.model.Member
import com.example.model.Project
import com.example.model.ProjectStatus
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Dedicated Firestore repository for managing [Client] documents under
 * `companies/{companyId}/clients`.
 */
class ClientRepository(private val core: AgencyRepository) {

    private val estimate = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    private fun clientsCollection() = core.sub("clients")
    private fun collectionPath() = "companies/${core.companyId}/clients"

    companion object {
        fun countActiveProjectsForClient(client: Client, projects: List<Project>): Int {
            return projects.count { p ->
                !p.deleted &&
                    p.statusEnum() != ProjectStatus.COMPLETED &&
                    (
                        (client.id.isNotBlank() && p.clientId == client.id) ||
                            (client.companyName.isNotBlank() && p.clientCompanyName.equals(client.companyName, ignoreCase = true))
                        )
            }
        }
    }

    fun observeClients(): Flow<List<Client>> = callbackFlow {
        val reg = clientsCollection().addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, collectionPath())
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { doc ->
                doc.get("createdAt", estimate)
                doc.get("updatedAt", estimate)
                doc.toObject(Client::class.java)
            }?.sortedBy { it.companyName.lowercase() }.orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    fun observeClient(clientId: String): Flow<Client?> = callbackFlow {
        if (clientId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val path = "${collectionPath()}/$clientId"
        val reg = clientsCollection().document(clientId).addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.GET, path)
                close(err)
                return@addSnapshotListener
            }
            snap?.get("createdAt", estimate)
            snap?.get("updatedAt", estimate)
            trySend(snap?.toObject(Client::class.java))
        }
        awaitClose { reg.remove() }
    }

    suspend fun getClient(clientId: String): Result<Client?> {
        if (clientId.isBlank()) {
            return Result.failure(IllegalArgumentException("Client ID cannot be blank."))
        }
        val path = "${collectionPath()}/$clientId"
        return try {
            core.requireAuthenticatedUserId()
            val snap = clientsCollection().document(clientId).get().await()
            snap.get("createdAt", estimate)
            snap.get("updatedAt", estimate)
            Result.success(snap.toObject(Client::class.java))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun saveClient(client: Client, actor: Member): Result<Client> {
        val cleanCompanyName = client.companyName.trim()
        val cleanContactPerson = client.contactPerson.trim()
        if (cleanCompanyName.isEmpty() || cleanCompanyName.length > 150) {
            return Result.failure(IllegalArgumentException("Company name must be between 1 and 150 characters."))
        }
        if (cleanContactPerson.isEmpty() || cleanContactPerson.length > 120) {
            return Result.failure(IllegalArgumentException("Contact person name must be between 1 and 120 characters."))
        }

        val isCreate = client.id.isBlank()
        val id = client.id.ifBlank { "cli_" + UUID.randomUUID().toString().take(8) }
        val path = "${collectionPath()}/$id"
        val safeActiveCount = client.activeProjectCount.coerceAtLeast(0)

        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            val payload = mutableMapOf<String, Any>(
                "companyName" to cleanCompanyName,
                "contactPerson" to cleanContactPerson,
                "phone" to client.phone.trim(),
                "email" to client.email.trim(),
                "address" to client.address.trim(),
                "meetingNotes" to client.meetingNotes.trim(),
                "followUpDateMillis" to client.followUpDateMillis.coerceAtLeast(0L),
                "activeProjectCount" to safeActiveCount,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isCreate) {
                payload["createdByUid"] = actorUid
                payload["createdAt"] = FieldValue.serverTimestamp()
                val writeTask = clientsCollection().document(id).set(payload)
                if (NetworkConnectivityMonitor.isOnline.value) writeTask.await()
            } else {
                val writeTask = clientsCollection().document(id).update(payload)
                if (NetworkConnectivityMonitor.isOnline.value) writeTask.await()
            }
            core.logActivity(
                ActivityCategory.QUOTATION,
                "Client Saved",
                cleanCompanyName,
                actorUid,
                actor.fullName,
                id
            )
            Result.success(
                client.copy(
                    id = id,
                    companyName = cleanCompanyName,
                    contactPerson = cleanContactPerson,
                    activeProjectCount = safeActiveCount,
                    createdByUid = if (isCreate) actorUid else client.createdByUid
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, if (isCreate) OperationType.CREATE else OperationType.UPDATE, path)
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, if (isCreate) OperationType.CREATE else OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateActiveProjectCount(clientId: String, activeProjectCount: Int): Result<Unit> {
        if (clientId.isBlank()) {
            return Result.failure(IllegalArgumentException("Client ID cannot be blank."))
        }
        val path = "${collectionPath()}/$clientId"
        return try {
            core.requireAuthenticatedUserId()
            val writeTask = clientsCollection().document(clientId).update(
                mapOf(
                    "activeProjectCount" to activeProjectCount.coerceAtLeast(0),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (NetworkConnectivityMonitor.isOnline.value) writeTask.await()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun syncClientActiveProjectCounts(
        projects: List<Project>,
        currentClients: List<Client>
    ): Result<Int> {
        if (currentClients.isEmpty()) return Result.success(0)
        return try {
            core.requireAuthenticatedUserId()
            val updates = currentClients.mapNotNull { cli ->
                val computed = countActiveProjectsForClient(cli, projects)
                if (cli.id.isNotBlank() && cli.activeProjectCount != computed) {
                    cli.id to computed
                } else {
                    null
                }
            }
            if (updates.isEmpty()) return Result.success(0)
            val batch = core.db.batch()
            updates.take(50).forEach { (clientId, count) ->
                batch.update(
                    clientsCollection().document(clientId),
                    mapOf(
                        "activeProjectCount" to count,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            val commitTask = batch.commit()
            if (NetworkConnectivityMonitor.isOnline.value) commitTask.await()
            Result.success(updates.size)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, OperationType.UPDATE, collectionPath())
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, collectionPath())
            Result.failure(e)
        }
    }

    suspend fun deleteClient(clientId: String, companyName: String, actor: Member): Result<Unit> {
        if (clientId.isBlank()) {
            return Result.failure(IllegalArgumentException("Client ID cannot be blank."))
        }
        val path = "${collectionPath()}/$clientId"
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            clientsCollection().document(clientId).delete().await()
            core.logActivity(
                ActivityCategory.COMPANY,
                "Client Deleted",
                companyName.ifBlank { clientId },
                actorUid,
                actor.fullName,
                clientId
            )
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }
}
