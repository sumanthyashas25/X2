package com.example.data

import com.example.domain.DeliverableDraft
import com.example.model.ActivityCategory
import com.example.model.Client
import com.example.model.CompanyProfile
import com.example.model.DecisionStatus
import com.example.model.DeliverableStatus
import com.example.model.Member
import com.example.model.NotificationCategory
import com.example.model.Project
import com.example.model.ProjectRequest
import com.example.model.ProjectStatus
import com.example.model.Quotation
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
 * Dedicated Firestore repository for managing [ProjectRequest] and [Project]
 * lifecycles under `companies/{companyId}/requests` and `companies/{companyId}/projects`.
 */
class ProjectRepository(private val core: AgencyRepository) {

    private val estimate = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    private fun requestsCollection() = core.sub("requests")
    private fun projectsCollection() = core.sub("projects")
    private fun deliverablesCollection() = core.sub("deliverables")

    fun observeRequests(): Flow<List<ProjectRequest>> = callbackFlow {
        val path = "companies/${core.companyId}/requests"
        val reg = requestsCollection().addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, path)
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { doc ->
                doc.get("createdAt", estimate)
                doc.get("updatedAt", estimate)
                doc.toObject(ProjectRequest::class.java)
            }.orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    fun observeProjects(): Flow<List<Project>> = callbackFlow {
        val path = "companies/${core.companyId}/projects"
        val reg = projectsCollection().addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, path)
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { doc ->
                doc.get("createdAt", estimate)
                doc.get("updatedAt", estimate)
                doc.toObject(Project::class.java)
            }.orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    fun observeProject(projectId: String): Flow<Project?> = callbackFlow {
        if (projectId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val path = "companies/${core.companyId}/projects/$projectId"
        val reg = projectsCollection().document(projectId).addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.GET, path)
                close(err)
                return@addSnapshotListener
            }
            snap?.get("createdAt", estimate)
            snap?.get("updatedAt", estimate)
            trySend(snap?.toObject(Project::class.java))
        }
        awaitClose { reg.remove() }
    }

    suspend fun getProject(projectId: String): Result<Project?> {
        if (projectId.isBlank()) {
            return Result.failure(IllegalArgumentException("Project ID cannot be blank."))
        }
        val path = "companies/${core.companyId}/projects/$projectId"
        return try {
            core.requireAuthenticatedUserId()
            val snap = projectsCollection().document(projectId).get().await()
            snap.get("createdAt", estimate)
            snap.get("updatedAt", estimate)
            Result.success(snap.toObject(Project::class.java))
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

    suspend fun createRequest(
        client: Client,
        projectTitle: String,
        brief: String,
        desiredServices: List<String>,
        targetDateMillis: Long,
        responsibleManager: Member,
        actor: Member
    ): Result<ProjectRequest> {
        val cleanTitle = projectTitle.trim()
        val cleanBrief = brief.trim()
        if (client.id.isBlank() || client.companyName.isBlank()) {
            return Result.failure(IllegalArgumentException("Select a valid client before creating a request."))
        }
        if (cleanTitle.isEmpty() || cleanTitle.length > 160) {
            return Result.failure(IllegalArgumentException("Project title must be between 1 and 160 characters."))
        }
        if (cleanBrief.length > 4000) {
            return Result.failure(IllegalArgumentException("Brief cannot exceed 4000 characters."))
        }

        val id = "req_" + UUID.randomUUID().toString().take(8)
        val path = "companies/${core.companyId}/requests/$id"
        val cappedServices = desiredServices.take(40)

        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            requestsCollection().document(id).set(
                mapOf(
                    "clientId" to client.id,
                    "clientCompanyName" to client.companyName.trim().take(150),
                    "projectTitle" to cleanTitle,
                    "brief" to cleanBrief,
                    "desiredServices" to cappedServices,
                    "targetDateMillis" to targetDateMillis,
                    "responsibleManagerUid" to responsibleManager.uid.take(128),
                    "responsibleManagerName" to responsibleManager.fullName,
                    "decisionStatus" to DecisionStatus.PENDING.name,
                    "decisionByUid" to "",
                    "decisionByName" to "",
                    "decisionAtMillis" to 0L,
                    "decisionNote" to "",
                    "acceptedProjectId" to "",
                    "latestQuoteId" to "",
                    "latestQuoteNumber" to "",
                    "latestQuoteRevision" to 0,
                    "clientApprovedQuoteId" to "",
                    "createdByUid" to actorUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            core.logActivity(
                ActivityCategory.PROJECT,
                "New Request Captured",
                "${client.companyName} — $cleanTitle",
                actorUid,
                actor.fullName,
                id
            )
            Result.success(
                ProjectRequest(
                    id = id,
                    clientId = client.id,
                    clientCompanyName = client.companyName.trim(),
                    projectTitle = cleanTitle,
                    brief = cleanBrief,
                    desiredServices = cappedServices,
                    targetDateMillis = targetDateMillis,
                    responsibleManagerUid = responsibleManager.uid,
                    responsibleManagerName = responsibleManager.fullName,
                    createdByUid = actorUid
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun acceptRequestAndCreateProject(
        request: ProjectRequest,
        approvedQuote: Quotation,
        deliverableDrafts: List<DeliverableDraft>,
        company: CompanyProfile,
        actor: Member
    ): Result<String> {
        if (request.id.isBlank() || approvedQuote.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Valid request and approved quotation are required."))
        }
        val projectId = "proj_${request.id}"
        val path = "companies/${core.companyId}/projects/$projectId"
        val now = System.currentTimeMillis()
        val dueDefaultMillis = if (request.targetDateMillis > now) request.targetDateMillis else now + 14L * 86_400_000L

        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            core.db.runTransaction { tx ->
                val reqRef = requestsCollection().document(request.id)
                val projRef = projectsCollection().document(projectId)
                val reqSnap = tx.get(reqRef)
                val projSnap = tx.get(projRef)

                if (reqSnap.getString("decisionStatus") == DecisionStatus.ACCEPTED.name || projSnap.exists()) {
                    throw FirebaseFirestoreException(
                        "This request has already been accepted into a project.",
                        FirebaseFirestoreException.Code.ABORTED
                    )
                }

                tx.update(
                    reqRef,
                    mapOf(
                        "decisionStatus" to DecisionStatus.ACCEPTED.name,
                        "decisionByUid" to actorUid,
                        "decisionByName" to actor.fullName,
                        "decisionAtMillis" to now,
                        "acceptedProjectId" to projectId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                tx.set(
                    projRef,
                    mapOf(
                        "requestId" to request.id,
                        "clientId" to request.clientId,
                        "clientCompanyName" to request.clientCompanyName.take(150),
                        "title" to request.projectTitle.take(160),
                        "brief" to request.brief.take(4000),
                        "responsibleManagerUid" to request.responsibleManagerUid.ifBlank { actorUid },
                        "responsibleManagerName" to request.responsibleManagerName.ifBlank { actor.fullName },
                        "approvedQuoteId" to approvedQuote.id,
                        "approvedQuoteNumber" to approvedQuote.quoteNumber,
                        "approvedQuoteRevision" to approvedQuote.revision,
                        "agreedValuePaise" to approvedQuote.totalPaise,
                        "advanceDuePaise" to approvedQuote.advancePaise,
                        "paidPaise" to 0L,
                        "paymentTerms" to approvedQuote.paymentTerms,
                        "revisionLimitPerDeliverable" to company.defaultRevisionRounds,
                        "status" to ProjectStatus.AWAITING_ADVANCE.name,
                        "deleted" to false,
                        "deletedByUid" to "",
                        "deletedAtMillis" to 0L,
                        "totalDeliverables" to deliverableDrafts.size,
                        "approvedDeliverables" to 0,
                        "inReviewDeliverables" to 0,
                        "blockedDeliverables" to 0,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                deliverableDrafts.forEachIndexed { idx, draft ->
                    val dId = "deliv_${request.id}_${idx + 1}"
                    tx.set(
                        deliverablesCollection().document(dId),
                        mapOf(
                            "projectId" to projectId,
                            "projectTitle" to request.projectTitle.take(160),
                            "clientCompanyName" to request.clientCompanyName.take(150),
                            "projectBrief" to request.brief.take(4000),
                            "departmentId" to draft.departmentId,
                            "departmentName" to draft.departmentName,
                            "jobRoleId" to draft.jobRoleId,
                            "jobRoleName" to draft.jobRoleName,
                            "title" to draft.title.take(160),
                            "description" to draft.description,
                            "quantity" to draft.quantity.coerceAtLeast(1),
                            "responsibleManagerUid" to request.responsibleManagerUid.ifBlank { actorUid },
                            "assignedToUid" to draft.assignedToUid,
                            "assignedToName" to draft.assignedToName,
                            "assignmentStatus" to draft.assignmentStatus.name,
                            "dueDateMillis" to dueDefaultMillis,
                            "status" to DeliverableStatus.NOT_STARTED.name,
                            "checklist" to draft.checklist.map { mapOf("id" to it.id, "title" to it.title, "done" to it.done) },
                            "completedChecklistCount" to 0,
                            "totalChecklistCount" to draft.checklist.size,
                            "previewUrl" to "",
                            "latestNote" to "",
                            "blockedReason" to "",
                            "helpNeeded" to "",
                            "latestFeedback" to "",
                            "clientRevisionsUsed" to 0,
                            "clientRevisionLimit" to company.defaultRevisionRounds,
                            "lastUpdatedByUid" to actorUid,
                            "lastUpdatedByName" to actor.fullName,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()

            core.logActivity(
                ActivityCategory.PROJECT,
                "Project Accepted & Distributed",
                "${request.projectTitle} • ${deliverableDrafts.size} deliverables generated",
                actorUid,
                actor.fullName,
                projectId
            )
            core.createNotification(
                category = NotificationCategory.PROJECT,
                title = "PROJECT ACCEPTED: ${request.projectTitle.uppercase()}",
                body = "${request.clientCompanyName} approved with ${deliverableDrafts.size} deliverables ready.",
                actor = actor,
                targetUid = "ALL",
                entityId = projectId
            )
            Result.success(projectId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun declineRequest(request: ProjectRequest, note: String, actor: Member): Result<Unit> {
        if (request.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Request ID cannot be blank."))
        }
        val path = "companies/${core.companyId}/requests/${request.id}"
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            requestsCollection().document(request.id).update(
                mapOf(
                    "decisionStatus" to DecisionStatus.DECLINED.name,
                    "decisionByUid" to actorUid,
                    "decisionByName" to actor.fullName,
                    "decisionAtMillis" to System.currentTimeMillis(),
                    "decisionNote" to note.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            core.logActivity(
                ActivityCategory.PROJECT,
                "Request Declined",
                "${request.projectTitle} — ${note.trim()}",
                actorUid,
                actor.fullName,
                request.id
            )
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

    suspend fun setProjectDeleted(project: Project, deleted: Boolean, actor: Member): Result<Unit> {
        if (project.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Project ID cannot be blank."))
        }
        val path = "companies/${core.companyId}/projects/${project.id}"
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            projectsCollection().document(project.id).update(
                mapOf(
                    "deleted" to deleted,
                    "deletedByUid" to if (deleted) actorUid else "",
                    "deletedAtMillis" to if (deleted) System.currentTimeMillis() else 0L,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            core.logActivity(
                ActivityCategory.PROJECT,
                if (deleted) "Project Soft-Deleted" else "Project Restored",
                project.title,
                actorUid,
                actor.fullName,
                project.id
            )
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

    suspend fun deleteProjectCascade(project: Project, actor: Member): Result<Unit> {
        if (project.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Project ID cannot be blank."))
        }
        val path = "companies/${core.companyId}/projects/${project.id}"
        return try {
            core.requireAuthenticatedUserId()
            core.deleteProjectCascade(project, actor)
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
