package com.example.data

import com.example.domain.DeliverableDraft
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine
import com.example.model.*
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CommercialWorkflowRepository(private val core: AgencyRepository) {

    val clients = ClientRepository(core)
    val projects = ProjectRepository(core)
    val quotations = QuotationRepository(core)

    private val db get() = core.db
    private fun sub(name: String) = core.sub(name)
    private val est = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE

    // --- Real-time Commercial & Delivery Streams ---

    fun observeClients(): Flow<List<Client>> = clients.observeClients()

    fun observeRequests(): Flow<List<ProjectRequest>> = projects.observeRequests()

    fun observeQuotations(): Flow<List<Quotation>> = quotations.observeQuotations()

    fun observeProjects(): Flow<List<Project>> = projects.observeProjects()

    fun observePayments(): Flow<List<PaymentRecord>> =
        core.observeCollection("payments") { it.toObject(PaymentRecord::class.java) }

    fun observeManagerDeliverables(): Flow<SyncedData<List<Deliverable>>> = callbackFlow {
        val reg = sub("deliverables").addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables")
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { it.toObject(Deliverable::class.java) }.orEmpty()
            val pending = snap?.metadata?.hasPendingWrites() == true
            trySend(SyncedData(list, pending))
        }
        awaitClose { reg.remove() }
    }

    fun observeStaffDeliverables(uid: String): Flow<SyncedData<List<Deliverable>>> = callbackFlow {
        val reg = sub("deliverables")
            .whereEqualTo("assignedToUid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables?assignedToUid=$uid")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(Deliverable::class.java) }.orEmpty()
                val pending = snap?.metadata?.hasPendingWrites() == true
                trySend(SyncedData(list, pending))
            }
        awaitClose { reg.remove() }
    }

    fun observeDeptQueueDeliverables(departmentId: String): Flow<List<Deliverable>> = callbackFlow {
        if (departmentId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val reg = sub("deliverables")
            .whereEqualTo("departmentId", departmentId)
            .whereEqualTo("assignmentStatus", AssignmentStatus.DEPT_QUEUE.name)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables?deptQueue=$departmentId")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(Deliverable::class.java) }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    fun observeDeliverableUpdates(deliverableId: String): Flow<SyncedData<List<DeliverableUpdate>>> = callbackFlow {
        val reg = sub("deliverables").document(deliverableId).collection("updates")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables/$deliverableId/updates")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull {
                    it.get("createdAt", est)
                    it.toObject(DeliverableUpdate::class.java)
                }.orEmpty()
                val pending = snap?.metadata?.hasPendingWrites() == true
                trySend(SyncedData(list, pending))
            }
        awaitClose { reg.remove() }
    }

    // --- Clients & Requests ---

    suspend fun saveClient(client: Client, actor: Member): Client =
        clients.saveClient(client, actor).getOrThrow()

    suspend fun createRequest(
        client: Client,
        projectTitle: String,
        brief: String,
        desiredServices: List<String>,
        targetDateMillis: Long,
        responsibleManager: Member,
        actor: Member
    ): ProjectRequest =
        projects.createRequest(client, projectTitle, brief, desiredServices, targetDateMillis, responsibleManager, actor).getOrThrow()

    // --- Quotations, Revisions, PDF Metadata, Sent & Client Approval ---

    suspend fun saveOrIssueQuotation(
        draft: Quotation,
        company: CompanyProfile,
        issueNow: Boolean,
        actor: Member
    ): Quotation =
        quotations.saveOrIssueQuotation(draft, company, issueNow, actor).getOrThrow()

    suspend fun createQuotationRevision(previous: Quotation, actor: Member): Quotation =
        quotations.createQuotationRevision(previous, actor).getOrThrow()

    suspend fun savePdfMetadata(quoteId: String, localPath: String, sha256: String) {
        quotations.savePdfMetadata(quoteId, localPath, sha256).getOrThrow()
    }

    suspend fun markQuotationSent(quote: Quotation, actor: Member) {
        quotations.markQuotationSent(quote, actor).getOrThrow()
    }

    suspend fun markQuotationClientApproved(quote: Quotation, approvalNote: String, actor: Member) {
        quotations.markQuotationClientApproved(quote, approvalNote, actor).getOrThrow()
    }

    // --- Project Acceptance (Concurrency-Safe Transaction) & Decline / Delete ---

    suspend fun acceptRequestAndCreateProject(
        request: ProjectRequest,
        approvedQuote: Quotation,
        deliverableDrafts: List<DeliverableDraft>,
        company: CompanyProfile,
        actor: Member
    ): String =
        projects.acceptRequestAndCreateProject(request, approvedQuote, deliverableDrafts, company, actor).getOrThrow()

    suspend fun declineRequest(request: ProjectRequest, note: String, actor: Member) {
        projects.declineRequest(request, note, actor).getOrThrow()
    }

    suspend fun setProjectDeleted(project: Project, deleted: Boolean, actor: Member) {
        projects.setProjectDeleted(project, deleted, actor).getOrThrow()
    }

    // --- Deliverable Assignment, Staff Progress, Manager Review & Payments ---

    suspend fun assignDeliverable(deliverable: Deliverable, assignee: Member, dueDateMillis: Long, actor: Member) {
        sub("deliverables").document(deliverable.id).update(
            mapOf(
                "assignedToUid" to assignee.uid,
                "assignedToName" to assignee.fullName,
                "assignmentStatus" to AssignmentStatus.ASSIGNED.name,
                "departmentId" to assignee.departmentId.ifBlank { deliverable.departmentId },
                "departmentName" to assignee.departmentName.ifBlank { deliverable.departmentName },
                "jobRoleId" to assignee.jobRoleId.ifBlank { deliverable.jobRoleId },
                "jobRoleName" to assignee.jobRoleName.ifBlank { deliverable.jobRoleName },
                "dueDateMillis" to dueDateMillis,
                "lastUpdatedByUid" to actor.uid,
                "lastUpdatedByName" to actor.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(ActivityCategory.ASSIGNMENT, "Assigned Deliverable", "${deliverable.title} -> ${assignee.fullName}", actor.uid, actor.fullName, deliverable.id)
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "NEW TASK ASSIGNED",
            body = "${deliverable.title} (${deliverable.projectTitle}) assigned to ${assignee.fullName}.",
            actor = actor,
            targetUid = assignee.uid,
            entityId = deliverable.id
        )
    }

    suspend fun claimDepartmentDeliverable(deliverable: Deliverable, staff: Member) {
        sub("deliverables").document(deliverable.id).update(
            mapOf(
                "assignedToUid" to staff.uid,
                "assignedToName" to staff.fullName,
                "assignmentStatus" to AssignmentStatus.ASSIGNED.name,
                "lastUpdatedByUid" to staff.uid,
                "lastUpdatedByName" to staff.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(
            ActivityCategory.ASSIGNMENT,
            "Queue Task Claimed",
            "${staff.fullName} claimed ${deliverable.title}",
            staff.uid,
            staff.fullName,
            deliverable.id
        )
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "TASK CLAIMED FROM QUEUE",
            body = "${staff.fullName} picked up ${deliverable.title} (${deliverable.projectTitle}).",
            actor = staff,
            targetUid = deliverable.responsibleManagerUid.ifBlank { "MANAGERS" },
            entityId = deliverable.id
        )
    }

    suspend fun startDeliverableWork(deliverable: Deliverable, staff: Member) {
        val completed = deliverable.checklist.count { it.done }
        val batch = db.batch()
        batch.update(
            sub("deliverables").document(deliverable.id),
            mapOf(
                "status" to DeliverableStatus.IN_PROGRESS.name,
                "latestNote" to "Work started by ${staff.fullName}",
                "lastUpdatedByUid" to staff.uid,
                "lastUpdatedByName" to staff.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val updId = "upd_" + UUID.randomUUID().toString().take(10)
        batch.set(
            sub("deliverables").document(deliverable.id).collection("updates").document(updId),
            mapOf(
                "deliverableId" to deliverable.id,
                "projectId" to deliverable.projectId,
                "assignedToUid" to deliverable.assignedToUid.ifBlank { staff.uid },
                "departmentId" to deliverable.departmentId,
                "authorUid" to staff.uid,
                "authorName" to staff.fullName,
                "actionType" to UpdateActionType.PROGRESS.name,
                "statusAfter" to DeliverableStatus.IN_PROGRESS.name,
                "note" to "Started work on ${deliverable.title}",
                "previewUrl" to deliverable.previewUrl,
                "blockedReason" to "",
                "helpNeeded" to "",
                "completedCount" to completed,
                "totalCount" to deliverable.checklist.size,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "PROJECT STARTED: ${deliverable.title.uppercase()}",
            body = "${staff.fullName} started working on ${deliverable.projectTitle}.",
            actor = staff,
            targetUid = deliverable.responsibleManagerUid.ifBlank { "MANAGERS" },
            entityId = deliverable.id
        )
    }

    suspend fun submitStaffProgress(
        deliverable: Deliverable,
        newStatus: DeliverableStatus,
        checklist: List<ChecklistItem>,
        note: String,
        previewUrl: String,
        blockedReason: String,
        helpNeeded: String,
        actor: Member
    ) {
        val completed = checklist.count { it.done }
        val actionType = when (newStatus) {
            DeliverableStatus.BLOCKED -> UpdateActionType.BLOCKED
            DeliverableStatus.READY_FOR_REVIEW -> UpdateActionType.SUBMIT_REVIEW
            else -> UpdateActionType.PROGRESS
        }
        val batch = db.batch()
        batch.update(
            sub("deliverables").document(deliverable.id),
            mapOf(
                "status" to newStatus.name,
                "checklist" to checklist.map { mapOf("id" to it.id, "title" to it.title, "done" to it.done) },
                "completedChecklistCount" to completed,
                "previewUrl" to previewUrl.trim(),
                "latestNote" to note.trim(),
                "blockedReason" to if (newStatus == DeliverableStatus.BLOCKED) blockedReason.trim() else "",
                "helpNeeded" to if (newStatus == DeliverableStatus.BLOCKED) helpNeeded.trim() else "",
                "lastUpdatedByUid" to actor.uid,
                "lastUpdatedByName" to actor.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val updId = "upd_" + UUID.randomUUID().toString().take(10)
        batch.set(
            sub("deliverables").document(deliverable.id).collection("updates").document(updId),
            mapOf(
                "deliverableId" to deliverable.id,
                "projectId" to deliverable.projectId,
                "assignedToUid" to deliverable.assignedToUid,
                "departmentId" to deliverable.departmentId,
                "authorUid" to actor.uid,
                "authorName" to actor.fullName,
                "actionType" to actionType.name,
                "statusAfter" to newStatus.name,
                "note" to note.trim(),
                "previewUrl" to previewUrl.trim(),
                "blockedReason" to blockedReason.trim(),
                "helpNeeded" to helpNeeded.trim(),
                "completedCount" to completed,
                "totalCount" to checklist.size,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        if (newStatus == DeliverableStatus.BLOCKED || newStatus == DeliverableStatus.READY_FOR_REVIEW) {
            core.createNotification(
                category = NotificationCategory.DELIVERABLE,
                title = if (newStatus == DeliverableStatus.BLOCKED) "BLOCKER FLAGGED" else "READY FOR REVIEW",
                body = "${actor.fullName}: ${deliverable.title} (${deliverable.projectTitle})",
                actor = actor,
                targetUid = deliverable.responsibleManagerUid.ifBlank { "MANAGERS" },
                entityId = deliverable.id
            )
        }
        if (newStatus == DeliverableStatus.READY_FOR_REVIEW && deliverable.projectId.isNotBlank()) {
            runCatching {
                val allDelivs = sub("deliverables").whereEqualTo("projectId", deliverable.projectId).get().await()
                val docs = allDelivs.documents
                val allReadyOrApproved = docs.isNotEmpty() && docs.all { doc ->
                    val st = doc.getString("status").orEmpty()
                    st == DeliverableStatus.READY_FOR_REVIEW.name || st == DeliverableStatus.APPROVED.name
                }
                if (allReadyOrApproved) {
                    val projSnap = sub("projects").document(deliverable.projectId).get().await()
                    val agreed = projSnap.getLong("agreedValuePaise") ?: 0L
                    val paid = projSnap.getLong("paidPaise") ?: 0L
                    val outstanding = (agreed - paid).coerceAtLeast(0L)
                    if (outstanding > 0L) {
                        core.createNotification(
                            category = NotificationCategory.PAYMENT,
                            title = "FINAL PAYMENT REMINDER: ${deliverable.projectTitle}".take(160),
                            body = "All deliverables submitted for ${deliverable.projectTitle}. Collect remaining balance of ${MoneyFormatter.formatInr(outstanding)} in Payment Tracker.".take(600),
                            actor = actor,
                            targetUid = "MANAGERS",
                            entityId = deliverable.projectId
                        )
                    }
                }
            }
        }
    }

    suspend fun reviewDeliverable(
        deliverable: Deliverable,
        action: UpdateActionType,
        feedback: String,
        projectDeliverables: List<Deliverable>,
        actor: Member
    ) {
        val nextStatus = when (action) {
            UpdateActionType.APPROVED -> DeliverableStatus.APPROVED
            UpdateActionType.CHANGES_REQUESTED -> DeliverableStatus.CHANGES_REQUESTED
            UpdateActionType.CLIENT_REVISION -> DeliverableStatus.CHANGES_REQUESTED
            else -> deliverable.statusEnum()
        }
        val nextClientRev = if (action == UpdateActionType.CLIENT_REVISION) deliverable.clientRevisionsUsed + 1 else deliverable.clientRevisionsUsed

        val batch = db.batch()
        batch.update(
            sub("deliverables").document(deliverable.id),
            mapOf(
                "status" to nextStatus.name,
                "latestFeedback" to feedback.trim(),
                "clientRevisionsUsed" to nextClientRev,
                "lastUpdatedByUid" to actor.uid,
                "lastUpdatedByName" to actor.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val updId = "upd_" + UUID.randomUUID().toString().take(10)
        batch.set(
            sub("deliverables").document(deliverable.id).collection("updates").document(updId),
            mapOf(
                "deliverableId" to deliverable.id,
                "projectId" to deliverable.projectId,
                "assignedToUid" to deliverable.assignedToUid,
                "departmentId" to deliverable.departmentId,
                "authorUid" to actor.uid,
                "authorName" to actor.fullName,
                "actionType" to action.name,
                "statusAfter" to nextStatus.name,
                "note" to feedback.trim(),
                "previewUrl" to deliverable.previewUrl,
                "blockedReason" to "",
                "helpNeeded" to "",
                "completedCount" to deliverable.completedChecklistCount,
                "totalCount" to deliverable.totalChecklistCount,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        // Update rolled-up counters on Project for quick overview
        val updatedList = projectDeliverables.map { if (it.id == deliverable.id) it.copy(status = nextStatus.name) else it }
        val approved = updatedList.count { it.statusEnum() == DeliverableStatus.APPROVED }
        val inRev = updatedList.count { it.statusEnum() == DeliverableStatus.READY_FOR_REVIEW }
        val blocked = updatedList.count { it.statusEnum() == DeliverableStatus.BLOCKED }
        batch.update(
            sub("projects").document(deliverable.projectId),
            mapOf(
                "totalDeliverables" to updatedList.size,
                "approvedDeliverables" to approved,
                "inReviewDeliverables" to inRev,
                "blockedDeliverables" to blocked,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        core.logActivity(ActivityCategory.DELIVERABLE, "${action.label}: ${deliverable.title}", feedback.trim(), actor.uid, actor.fullName, deliverable.id)
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "${action.label.uppercase()}: ${deliverable.title.uppercase()}".take(160),
            body = feedback.trim().ifBlank { "Reviewed by ${actor.fullName}." }.take(600),
            actor = actor,
            targetUid = deliverable.assignedToUid.ifBlank { "ALL" },
            entityId = deliverable.id
        )
        if (nextStatus == DeliverableStatus.APPROVED &&
            updatedList.isNotEmpty() &&
            updatedList.all {
                val st = it.statusEnum()
                st == DeliverableStatus.APPROVED || st == DeliverableStatus.READY_FOR_REVIEW
            }
        ) {
            val projSnap = runCatching { sub("projects").document(deliverable.projectId).get().await() }.getOrNull()
            val agreed = projSnap?.getLong("agreedValuePaise") ?: 0L
            val paid = projSnap?.getLong("paidPaise") ?: 0L
            val outstanding = (agreed - paid).coerceAtLeast(0L)
            if (outstanding > 0L) {
                core.createNotification(
                    category = NotificationCategory.PAYMENT,
                    title = "FINAL PAYMENT REMINDER: ${deliverable.projectTitle}".take(160),
                    body = "All deliverables submitted/approved for ${deliverable.projectTitle}. Collect remaining balance of ${MoneyFormatter.formatInr(outstanding)} in Payment Tracker.".take(600),
                    actor = actor,
                    targetUid = "MANAGERS",
                    entityId = deliverable.projectId
                )
            }
        }
    }

    suspend fun recordPayment(
        project: Project,
        amountPaise: Long,
        paymentDateMillis: Long,
        method: PaymentMethod,
        reference: String,
        note: String,
        actor: Member
    ) {
        val payId = "pay_" + UUID.randomUUID().toString().take(10)
        db.runTransaction { tx ->
            val projRef = sub("projects").document(project.id)
            val snap = tx.get(projRef)
            val currentPaid = snap.getLong("paidPaise") ?: 0L
            val newPaid = currentPaid + amountPaise
            val advanceDue = snap.getLong("advanceDuePaise") ?: 0L
            val agreed = snap.getLong("agreedValuePaise") ?: 0L
            val currentStatus = snap.getString("status") ?: ProjectStatus.AWAITING_ADVANCE.name

            val nextStatus = when {
                agreed > 0L && newPaid >= agreed && (snap.getLong("totalDeliverables") ?: 0L) == (snap.getLong("approvedDeliverables") ?: -1L) ->
                    ProjectStatus.COMPLETED.name
                currentStatus == ProjectStatus.AWAITING_ADVANCE.name && newPaid >= advanceDue && advanceDue > 0L ->
                    ProjectStatus.IN_PROGRESS.name
                else -> currentStatus
            }

            tx.set(
                sub("payments").document(payId),
                mapOf(
                    "projectId" to project.id,
                    "projectTitle" to project.title,
                    "clientCompanyName" to project.clientCompanyName,
                    "amountPaise" to amountPaise,
                    "paymentDateMillis" to paymentDateMillis,
                    "method" to method.name,
                    "reference" to reference.trim(),
                    "note" to note.trim(),
                    "recordedByUid" to actor.uid,
                    "recordedByName" to actor.fullName,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            tx.update(
                projRef,
                mapOf("paidPaise" to newPaid, "status" to nextStatus, "updatedAt" to FieldValue.serverTimestamp())
            )
        }.await()

        core.logActivity(
            ActivityCategory.PAYMENT,
            "Payment Recorded (${MoneyFormatter.formatInr(amountPaise)})",
            "${project.title} via ${method.label} ${reference.trim()}",
            actor.uid,
            actor.fullName,
            payId
        )
        core.createNotification(
            category = NotificationCategory.PAYMENT,
            title = "PAYMENT RECORDED: ${MoneyFormatter.formatInr(amountPaise)}",
            body = "${project.title} • ${method.label}",
            actor = actor,
            targetUid = "MANAGERS",
            entityId = project.id
        )
    }

    suspend fun deleteQuotation(quoteId: String, quoteNum: String, actor: Member) {
        quotations.deleteQuotation(quoteId, quoteNum, actor).getOrThrow()
    }

    // --- Time Tracking ---

    fun observeTimeEntries(uid: String, isManager: Boolean): Flow<List<TimeEntry>> = callbackFlow {
        val query = if (isManager) {
            sub("time_entries").orderBy("workDateMillis", Query.Direction.DESCENDING).limit(200)
        } else {
            sub("time_entries").whereEqualTo("uid", uid).limit(150)
        }
        val reg = query.addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/time_entries")
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { doc ->
                doc.get("createdAt", est)
                doc.get("updatedAt", est)
                doc.toObject(TimeEntry::class.java)
            }?.sortedByDescending { it.workDateMillis }.orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    suspend fun logTimeEntry(
        project: Project,
        deliverable: Deliverable?,
        durationMinutes: Int,
        billable: Boolean,
        hourlyRatePaise: Long,
        notes: String,
        workDateMillis: Long,
        actor: Member
    ): TimeEntry {
        val safeMinutes = durationMinutes.coerceIn(1, 24 * 60)
        val entryId = "time_" + UUID.randomUUID().toString().take(10)
        val payload = mapOf(
            "projectId" to project.id,
            "projectTitle" to project.title.take(160),
            "deliverableId" to (deliverable?.id ?: ""),
            "deliverableTitle" to (deliverable?.title?.take(160) ?: ""),
            "uid" to actor.uid,
            "memberName" to actor.fullName.ifBlank { "Member" }.take(120),
            "departmentId" to (deliverable?.departmentId?.ifBlank { actor.departmentId } ?: actor.departmentId),
            "workDateMillis" to workDateMillis,
            "durationMinutes" to safeMinutes,
            "billable" to billable,
            "hourlyRatePaise" to hourlyRatePaise.coerceAtLeast(0L),
            "notes" to notes.trim().take(600),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        sub("time_entries").document(entryId).set(payload).await()
        core.logActivity(
            ActivityCategory.DELIVERABLE,
            "Logged ${safeMinutes}m on ${project.title}",
            deliverable?.title?.ifBlank { notes.trim() } ?: notes.trim(),
            actor.uid,
            actor.fullName,
            entryId
        )
        return TimeEntry(
            id = entryId,
            projectId = project.id,
            projectTitle = project.title,
            deliverableId = deliverable?.id ?: "",
            deliverableTitle = deliverable?.title ?: "",
            uid = actor.uid,
            memberName = actor.fullName,
            departmentId = deliverable?.departmentId ?: actor.departmentId,
            workDateMillis = workDateMillis,
            durationMinutes = safeMinutes,
            billable = billable,
            hourlyRatePaise = hourlyRatePaise.coerceAtLeast(0L),
            notes = notes.trim()
        )
    }

    suspend fun deleteTimeEntry(entryId: String) {
        if (entryId.isBlank()) return
        sub("time_entries").document(entryId).delete().await()
    }

    // --- Client Portal Guest Links ---

    fun observePortalLinks(): Flow<List<ClientPortalLink>> = callbackFlow {
        val reg = sub("client_portal_links")
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)
            .limit(80)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/client_portal_links")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", est)
                    doc.get("updatedAt", est)
                    doc.toObject(ClientPortalLink::class.java)
                }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun createClientPortalLink(
        project: Project,
        quotationId: String,
        validDays: Int,
        actor: Member
    ): ClientPortalLink {
        val bytes = ByteArray(18).also { java.security.SecureRandom().nextBytes(it) }
        val token = "ptk_" + bytes.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val expires = now + validDays.coerceIn(1, 90) * 86_400_000L
        val payload = mapOf(
            "token" to token,
            "projectId" to project.id,
            "projectTitle" to project.title.take(160),
            "quotationId" to quotationId.ifBlank { project.approvedQuoteId },
            "clientId" to project.clientId,
            "clientCompanyName" to project.clientCompanyName.take(150),
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "expiresAtMillis" to expires,
            "revoked" to false,
            "clientApprovedAtMillis" to 0L,
            "clientApprovalNote" to "",
            "createdAtMillis" to now,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val batch = db.batch()
        batch.set(sub("client_portal_links").document(token), payload)
        // Top-level lookup index for public Cloudflare Worker portal endpoints (guest token read-only access)
        batch.set(
            db.collection("portal_tokens").document(token),
            mapOf(
                "companyId" to core.companyId,
                "token" to token,
                "projectId" to project.id,
                "quotationId" to quotationId.ifBlank { project.approvedQuoteId },
                "expiresAtMillis" to expires,
                "revoked" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        core.logActivity(
            ActivityCategory.PROJECT,
            "Client Portal Link Created",
            "${project.clientCompanyName} • ${project.title}",
            actor.uid,
            actor.fullName,
            token
        )
        return ClientPortalLink(
            id = token,
            token = token,
            projectId = project.id,
            projectTitle = project.title,
            quotationId = quotationId.ifBlank { project.approvedQuoteId },
            clientId = project.clientId,
            clientCompanyName = project.clientCompanyName,
            createdByUid = actor.uid,
            createdByName = actor.fullName,
            expiresAtMillis = expires,
            createdAtMillis = now
        )
    }

    suspend fun revokeClientPortalLink(token: String, actor: Member) {
        if (token.isBlank()) return
        val batch = db.batch()
        batch.update(
            sub("client_portal_links").document(token),
            mapOf("revoked" to true, "updatedAt" to FieldValue.serverTimestamp())
        )
        batch.set(
            db.collection("portal_tokens").document(token),
            mapOf("revoked" to true, "updatedAt" to FieldValue.serverTimestamp()),
            com.google.firebase.firestore.SetOptions.merge()
        )
        batch.commit().await()
        core.logActivity(
            ActivityCategory.PROJECT,
            "Client Portal Link Revoked",
            "Revoked guest access token",
            actor.uid,
            actor.fullName,
            token
        )
    }

    // --- Automated Payment Nudge Logs ---

    fun observeNudgeLogs(): Flow<List<PaymentNudgeLog>> = callbackFlow {
        val reg = sub("nudge_logs")
            .orderBy("sentAtMillis", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/nudge_logs")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", est)
                    doc.toObject(PaymentNudgeLog::class.java)
                }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun logPaymentNudge(
        project: Project,
        quotationId: String,
        channel: String,
        recipient: String,
        amountDuePaise: Long,
        status: String,
        summary: String,
        actor: Member
    ): PaymentNudgeLog {
        val logId = "ndg_" + UUID.randomUUID().toString().take(10)
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "projectId" to project.id,
            "projectTitle" to project.title.take(160),
            "quotationId" to quotationId.take(128),
            "clientCompanyName" to project.clientCompanyName.take(150),
            "channel" to channel.take(32),
            "recipient" to recipient.take(160),
            "amountDuePaise" to amountDuePaise.coerceAtLeast(0L),
            "status" to status.take(32),
            "summary" to summary.take(400),
            "sentByUid" to actor.uid,
            "sentAtMillis" to now,
            "createdAt" to FieldValue.serverTimestamp()
        )
        sub("nudge_logs").document(logId).set(payload).await()
        return PaymentNudgeLog(
            id = logId,
            projectId = project.id,
            projectTitle = project.title,
            quotationId = quotationId,
            clientCompanyName = project.clientCompanyName,
            channel = channel,
            recipient = recipient,
            amountDuePaise = amountDuePaise,
            status = status,
            summary = summary,
            sentByUid = actor.uid,
            sentAtMillis = now
        )
    }
}
