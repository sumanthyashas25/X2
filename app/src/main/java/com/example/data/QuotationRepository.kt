package com.example.data

import com.example.domain.MoneyFormatter
import com.example.model.ActivityCategory
import com.example.model.CompanyProfile
import com.example.model.CompanySnapshot
import com.example.model.Member
import com.example.model.NotificationCategory
import com.example.model.Quotation
import com.example.model.QuotationStatus
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.UUID

/**
 * Dedicated Firestore repository for managing [Quotation] documents, sequence
 * issuance, revisions, PDF metadata, and client approvals under `companies/{companyId}/quotations`.
 */
class QuotationRepository(private val core: AgencyRepository) {

    private val estimate = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    private fun quotationsCollection() = core.sub("quotations")
    private fun requestsCollection() = core.sub("requests")
    private fun projectsCollection() = core.sub("projects")
    private fun collectionPath() = "companies/${core.companyId}/quotations"

    fun observeQuotations(): Flow<List<Quotation>> = callbackFlow {
        val reg = quotationsCollection().addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, collectionPath())
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { doc ->
                doc.get("createdAt", estimate)
                doc.get("updatedAt", estimate)
                doc.toObject(Quotation::class.java)
            }.orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    fun observeQuotation(quoteId: String): Flow<Quotation?> = callbackFlow {
        if (quoteId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val path = "${collectionPath()}/$quoteId"
        val reg = quotationsCollection().document(quoteId).addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.GET, path)
                close(err)
                return@addSnapshotListener
            }
            snap?.get("createdAt", estimate)
            snap?.get("updatedAt", estimate)
            trySend(snap?.toObject(Quotation::class.java))
        }
        awaitClose { reg.remove() }
    }

    fun observeQuotationsForClient(clientId: String): Flow<List<Quotation>> = callbackFlow {
        if (clientId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val path = "${collectionPath()}?clientId=$clientId"
        val reg = quotationsCollection()
            .whereEqualTo("clientId", clientId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, path)
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", estimate)
                    doc.get("updatedAt", estimate)
                    doc.toObject(Quotation::class.java)
                }?.sortedByDescending { it.issuedDateMillis }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    fun observeQuotationsForRequest(requestId: String): Flow<List<Quotation>> = callbackFlow {
        if (requestId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val path = "${collectionPath()}?requestId=$requestId"
        val reg = quotationsCollection()
            .whereEqualTo("requestId", requestId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, path)
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", estimate)
                    doc.get("updatedAt", estimate)
                    doc.toObject(Quotation::class.java)
                }?.sortedByDescending { it.revision }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun getQuotation(quoteId: String): Result<Quotation?> {
        if (quoteId.isBlank()) {
            return Result.failure(IllegalArgumentException("Quotation ID cannot be blank."))
        }
        val path = "${collectionPath()}/$quoteId"
        return try {
            core.requireAuthenticatedUserId()
            val snap = quotationsCollection().document(quoteId).get().await()
            snap.get("createdAt", estimate)
            snap.get("updatedAt", estimate)
            Result.success(snap.toObject(Quotation::class.java))
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

    suspend fun saveOrIssueQuotation(
        draft: Quotation,
        company: CompanyProfile,
        issueNow: Boolean,
        actor: Member
    ): Result<Quotation> {
        if (draft.requestId.isBlank() || draft.clientId.isBlank()) {
            return Result.failure(IllegalArgumentException("Quotation must be linked to a valid client and request."))
        }
        val cleanTitle = draft.projectTitle.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > 160) {
            return Result.failure(IllegalArgumentException("Project title must be between 1 and 160 characters."))
        }

        val qId = draft.id.ifBlank { "qt_" + UUID.randomUUID().toString().take(8) }
        val path = "${collectionPath()}/$qId"
        val now = System.currentTimeMillis()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val expiryMillis = if (draft.expiryDateMillis > 0L) {
            draft.expiryDateMillis
        } else {
            now + company.defaultValidityDays * 86_400_000L
        }
        val companySnap = CompanySnapshot(
            name = company.name,
            tagline = company.tagline,
            address = company.address,
            phone = company.phone,
            email = company.email,
            gstin = company.gstin,
            gstEnabled = company.gstEnabled,
            gstRatePercent = company.gstRatePercent
        )

        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            val savedTriple = core.db.runTransaction { tx ->
                val qRef = quotationsCollection().document(qId)
                val existingQuote = tx.get(qRef)

                val compSnapDoc = tx.get(core.companyRef)
                val currentSeq = compSnapDoc.getLong("nextQuoteSeq") ?: 1L
                val needsNumber = draft.quoteNumber.isBlank() || draft.quoteNumber.startsWith("DRAFT")
                val finalNumber = if (issueNow && needsNumber) {
                    tx.update(
                        core.companyRef,
                        mapOf("nextQuoteSeq" to (currentSeq + 1L), "updatedAt" to FieldValue.serverTimestamp())
                    )
                    "QT-$year-${currentSeq.toString().padStart(4, '0')}"
                } else {
                    draft.quoteNumber.ifBlank { "DRAFT-$qId" }
                }

                val finalStatus = if (issueNow) QuotationStatus.ISSUED.name else QuotationStatus.DRAFT.name
                val preparedDraft = draft.copy(
                    id = qId,
                    quoteNumber = finalNumber,
                    projectTitle = cleanTitle,
                    status = finalStatus,
                    companySnapshot = companySnap,
                    issuedDateMillis = now,
                    expiryDateMillis = expiryMillis,
                    preparedByUid = actorUid,
                    preparedByName = actor.fullName
                )
                val qMap = quotationToMap(preparedDraft, isCreate = !existingQuote.exists())
                if (existingQuote.exists()) {
                    tx.update(qRef, qMap)
                } else {
                    tx.set(qRef, qMap)
                }

                if (draft.requestId.isNotBlank()) {
                    tx.update(
                        requestsCollection().document(draft.requestId),
                        mapOf(
                            "latestQuoteId" to qId,
                            "latestQuoteNumber" to finalNumber,
                            "latestQuoteRevision" to draft.revision,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                Triple(qId, finalNumber, finalStatus)
            }.await()

            core.logActivity(
                ActivityCategory.QUOTATION,
                if (issueNow) "Quotation Issued (${savedTriple.second})" else "Quotation Draft Saved",
                "${draft.clientSnapshot.companyName} • ${MoneyFormatter.formatInr(draft.totalPaise)}",
                actorUid,
                actor.fullName,
                savedTriple.first
            )

            Result.success(
                draft.copy(
                    id = savedTriple.first,
                    quoteNumber = savedTriple.second,
                    projectTitle = cleanTitle,
                    status = savedTriple.third,
                    companySnapshot = companySnap,
                    issuedDateMillis = now,
                    expiryDateMillis = expiryMillis,
                    preparedByUid = actorUid,
                    preparedByName = actor.fullName
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun createQuotationRevision(previous: Quotation, actor: Member): Result<Quotation> {
        if (previous.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot create revision for unsaved quotation."))
        }
        val newRevId = "qt_" + UUID.randomUUID().toString().take(8)
        val nextRev = (previous.revision + 1).coerceAtMost(100)
        val path = "${collectionPath()}/$newRevId"

        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            val revQuote = previous.copy(
                id = newRevId,
                revision = nextRev,
                parentQuoteId = previous.id,
                status = QuotationStatus.DRAFT.name,
                pdfLocalPath = "",
                pdfSha256 = "",
                sentAtMillis = 0L,
                clientApprovedAtMillis = 0L,
                clientApprovalNote = "",
                preparedByUid = actorUid,
                preparedByName = actor.fullName
            )
            core.db.runTransaction { tx ->
                if (previous.statusEnum() != QuotationStatus.DRAFT) {
                    tx.update(
                        quotationsCollection().document(previous.id),
                        mapOf("status" to QuotationStatus.SUPERSEDED.name, "updatedAt" to FieldValue.serverTimestamp())
                    )
                }
                tx.set(quotationsCollection().document(newRevId), quotationToMap(revQuote, isCreate = true))
                if (previous.requestId.isNotBlank()) {
                    tx.update(
                        requestsCollection().document(previous.requestId),
                        mapOf(
                            "latestQuoteId" to newRevId,
                            "latestQuoteNumber" to previous.quoteNumber,
                            "latestQuoteRevision" to nextRev,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
            core.logActivity(
                ActivityCategory.QUOTATION,
                "Created Revision v$nextRev",
                "${previous.quoteNumber} for ${previous.clientSnapshot.companyName}",
                actorUid,
                actor.fullName,
                newRevId
            )
            Result.success(revQuote)
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

    suspend fun savePdfMetadata(quoteId: String, localPath: String, sha256: String): Result<Unit> {
        if (quoteId.isBlank()) {
            return Result.failure(IllegalArgumentException("Quotation ID cannot be blank."))
        }
        val path = "${collectionPath()}/$quoteId"
        return try {
            core.requireAuthenticatedUserId()
            quotationsCollection().document(quoteId).update(
                mapOf(
                    "pdfLocalPath" to localPath,
                    "pdfSha256" to sha256,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
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

    suspend fun markQuotationSent(quote: Quotation, actor: Member): Result<Unit> {
        if (quote.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Quotation ID cannot be blank."))
        }
        val path = "${collectionPath()}/${quote.id}"
        val now = System.currentTimeMillis()
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            quotationsCollection().document(quote.id).update(
                mapOf(
                    "status" to QuotationStatus.SENT.name,
                    "sentAtMillis" to now,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            core.logActivity(
                ActivityCategory.QUOTATION,
                "Quotation Marked Sent",
                "${quote.quoteNumber} v${quote.revision}",
                actorUid,
                actor.fullName,
                quote.id
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

    suspend fun markQuotationClientApproved(quote: Quotation, approvalNote: String, actor: Member): Result<Unit> {
        if (quote.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Quotation ID cannot be blank."))
        }
        val path = "${collectionPath()}/${quote.id}"
        val now = System.currentTimeMillis()
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            core.db.runTransaction { tx ->
                val projectRef = if (quote.linkedProjectId.isNotBlank()) projectsCollection().document(quote.linkedProjectId) else null
                val projectSnap = projectRef?.let { tx.get(it) }

                tx.update(
                    quotationsCollection().document(quote.id),
                    mapOf(
                        "status" to QuotationStatus.CLIENT_APPROVED.name,
                        "clientApprovedAtMillis" to now,
                        "clientApprovalNote" to approvalNote.trim(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (quote.requestId.isNotBlank()) {
                    tx.update(
                        requestsCollection().document(quote.requestId),
                        mapOf("clientApprovedQuoteId" to quote.id, "updatedAt" to FieldValue.serverTimestamp())
                    )
                }
                if (projectSnap != null && projectSnap.exists()) {
                    val oldAgreed = projectSnap.getLong("agreedValuePaise") ?: 0L
                    val oldAdv = projectSnap.getLong("advanceDuePaise") ?: 0L
                    tx.update(
                        projectRef,
                        mapOf(
                            "agreedValuePaise" to (oldAgreed + quote.totalPaise),
                            "advanceDuePaise" to (oldAdv + quote.advancePaise),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()

            core.logActivity(
                ActivityCategory.QUOTATION,
                "Client Approved Quote",
                "${quote.quoteNumber} v${quote.revision} (${MoneyFormatter.formatInr(quote.totalPaise)})",
                actorUid,
                actor.fullName,
                quote.id
            )
            if (quote.advancePaise > 0L) {
                core.createNotification(
                    category = NotificationCategory.PAYMENT,
                    title = "ADVANCE PAYMENT REMINDER: ${quote.quoteNumber}".take(160),
                    body = "Client approved ${quote.projectTitle} (${quote.clientSnapshot.companyName}). Track advance of ${MoneyFormatter.formatInr(quote.advancePaise)} in Payment Tracker.".take(600),
                    actor = actor,
                    targetUid = "MANAGERS",
                    entityId = quote.linkedProjectId.ifBlank { quote.id }
                )
            }
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

    suspend fun deleteQuotation(quoteId: String, quoteNum: String, actor: Member): Result<Unit> {
        if (quoteId.isBlank()) {
            return Result.failure(IllegalArgumentException("Quotation ID cannot be blank."))
        }
        val path = "${collectionPath()}/$quoteId"
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            quotationsCollection().document(quoteId).delete().await()
            core.logActivity(
                ActivityCategory.QUOTATION,
                "Quotation Deleted",
                quoteNum.ifBlank { quoteId },
                actorUid,
                actor.fullName,
                quoteId
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

    private fun quotationToMap(q: Quotation, isCreate: Boolean): Map<String, Any> {
        val linesList = q.lines.map { line ->
            mutableMapOf<String, Any>(
                "lineId" to line.lineId,
                "catalogueItemId" to line.catalogueItemId,
                "kind" to line.kind,
                "category" to line.category,
                "title" to line.title,
                "description" to line.description,
                "pricingMode" to line.pricingMode,
                "quantity" to line.quantity,
                "unitPricePaise" to line.unitPricePaise,
                "lineTotalPaise" to line.lineTotalPaise,
                "billingFrequency" to line.billingFrequency,
                "separatelyBilled" to line.separatelyBilled,
                "urgentEligible" to line.urgentEligible,
                "inclusions" to line.inclusions.map { inc ->
                    mapOf(
                        "serviceId" to inc.serviceId,
                        "title" to inc.title,
                        "category" to inc.category,
                        "quantity" to inc.quantity,
                        "description" to inc.description
                    )
                },
                "sortOrder" to line.sortOrder
            ).apply {
                line.overridePricePaise?.let { put("overridePricePaise", it) }
            }
        }
        val map = mutableMapOf<String, Any>(
            "quoteNumber" to q.quoteNumber.take(60),
            "revision" to q.revision.coerceIn(1, 100),
            "parentQuoteId" to q.parentQuoteId,
            "requestId" to q.requestId,
            "linkedProjectId" to q.linkedProjectId,
            "clientId" to q.clientId,
            "projectTitle" to q.projectTitle.take(160),
            "scopeSummary" to q.scopeSummary,
            "status" to q.status,
            "companySnapshot" to mapOf(
                "name" to q.companySnapshot.name,
                "tagline" to q.companySnapshot.tagline,
                "address" to q.companySnapshot.address,
                "phone" to q.companySnapshot.phone,
                "email" to q.companySnapshot.email,
                "gstin" to q.companySnapshot.gstin,
                "gstEnabled" to q.companySnapshot.gstEnabled,
                "gstRatePercent" to q.companySnapshot.gstRatePercent
            ),
            "clientSnapshot" to mapOf(
                "clientId" to q.clientSnapshot.clientId,
                "companyName" to q.clientSnapshot.companyName,
                "contactPerson" to q.clientSnapshot.contactPerson,
                "phone" to q.clientSnapshot.phone,
                "email" to q.clientSnapshot.email,
                "address" to q.clientSnapshot.address
            ),
            "lines" to linesList,
            "subtotalPaise" to q.subtotalPaise.coerceAtLeast(0L),
            "discountPaise" to q.discountPaise.coerceAtLeast(0L),
            "surchargePaise" to q.surchargePaise.coerceAtLeast(0L),
            "surchargeBasePaise" to q.surchargeBasePaise.coerceAtLeast(0L),
            "gstEnabled" to q.gstEnabled,
            "gstRatePercent" to q.gstRatePercent.coerceIn(0, 28),
            "gstAmountPaise" to q.gstAmountPaise.coerceAtLeast(0L),
            "totalPaise" to q.totalPaise.coerceAtLeast(0L),
            "advancePercent" to q.advancePercent.coerceIn(0, 100),
            "advancePaise" to q.advancePaise.coerceAtLeast(0L),
            "balancePaise" to q.balancePaise.coerceAtLeast(0L),
            "paymentTerms" to q.paymentTerms,
            "revisionPolicy" to q.revisionPolicy,
            "timelineNote" to q.timelineNote,
            "separatelyBilledNote" to q.separatelyBilledNote,
            "approvalInstructions" to q.approvalInstructions,
            "issuedDateMillis" to q.issuedDateMillis,
            "expiryDateMillis" to q.expiryDateMillis,
            "preparedByUid" to q.preparedByUid,
            "preparedByName" to q.preparedByName.ifBlank { "Member" }.take(120),
            "pdfLocalPath" to q.pdfLocalPath,
            "pdfSha256" to q.pdfSha256,
            "sentAtMillis" to q.sentAtMillis,
            "clientApprovedAtMillis" to q.clientApprovedAtMillis,
            "clientApprovalNote" to q.clientApprovalNote,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (isCreate) map["createdAt"] = FieldValue.serverTimestamp()
        return map
    }
}
