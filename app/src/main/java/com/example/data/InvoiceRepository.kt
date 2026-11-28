package com.example.data

import com.example.domain.MoneyFormatter
import com.example.model.ActivityCategory
import com.example.model.ClientSnapshot
import com.example.model.CompanyProfile
import com.example.model.CompanySnapshot
import com.example.model.InvoiceLineItem
import com.example.model.InvoiceRecord
import com.example.model.InvoiceStatus
import com.example.model.Member
import com.example.model.NotificationCategory
import com.example.model.Project
import com.example.model.Quotation
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
 * Dedicated Firestore repository for managing [InvoiceRecord] documents under
 * `companies/{companyId}/invoices/{invoiceId}` with offline persistent cache support.
 */
class InvoiceRepository(private val core: AgencyRepository) {

    private val estimate = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    private fun invoicesCollection() = core.sub("invoices")
    private fun collectionPath() = "companies/${core.companyId}/invoices"

    fun observeInvoicesSynced(): Flow<SyncedData<List<InvoiceRecord>>> = callbackFlow {
        val reg = invoicesCollection().addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, collectionPath())
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { doc ->
                doc.get("createdAt", estimate)
                doc.get("updatedAt", estimate)
                doc.toObject(InvoiceRecord::class.java)
            }?.sortedByDescending {
                maxOf(it.issuedDateMillis, (it.createdAt?.seconds ?: 0L) * 1000L)
            }.orEmpty()
            val pending = snap?.metadata?.hasPendingWrites() == true
            trySend(SyncedData(list, pending))
        }
        awaitClose { reg.remove() }
    }

    suspend fun createInvoice(
        project: Project,
        quotation: Quotation?,
        amountPaise: Long,
        paid: Boolean,
        dueDateMillis: Long,
        notes: String,
        company: CompanyProfile?,
        actor: Member
    ): Result<InvoiceRecord> {
        if (amountPaise <= 0L) {
            return Result.failure(IllegalArgumentException("Invoice amount must be greater than zero."))
        }
        val id = "inv_" + UUID.randomUUID().toString().take(8)
        val path = "${collectionPath()}/$id"
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val invoiceNumber = "INV-$year-${UUID.randomUUID().toString().take(4).uppercase()}"
        val now = System.currentTimeMillis()
        val effectiveDue = dueDateMillis.takeIf { it > 0L } ?: (now + 7L * 86_400_000L)
        val statusEnum = if (paid) InvoiceStatus.PAID else InvoiceStatus.ISSUED
        val balanceDue = if (paid) 0L else amountPaise
        val credited = if (paid) amountPaise else 0L

        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            val clientSnap = quotation?.clientSnapshot?.takeIf { it.companyName.isNotBlank() }
                ?: ClientSnapshot(
                    clientId = project.clientId,
                    companyName = project.clientCompanyName.ifBlank { "Client" }
                )
            val compSnap = CompanySnapshot(
                name = company?.name ?: "X.MAX",
                tagline = company?.tagline.orEmpty(),
                address = company?.address.orEmpty(),
                phone = company?.phone.orEmpty(),
                email = company?.email.orEmpty(),
                gstin = company?.gstin.orEmpty(),
                gstEnabled = company?.gstEnabled ?: false,
                gstRatePercent = company?.gstRatePercent ?: 18
            )
            val lines = listOf(
                InvoiceLineItem(
                    lineId = "line_1",
                    title = project.title.ifBlank { "Project Billing" }.take(160),
                    description = notes.trim().ifBlank { "Agency milestone invoice" }.take(300),
                    quantity = 1,
                    unitPricePaise = amountPaise,
                    lineTotalPaise = amountPaise
                )
            )
            val payload = mapOf(
                "invoiceNumber" to invoiceNumber,
                "projectId" to project.id,
                "projectTitle" to project.title.take(160),
                "quotationId" to (quotation?.id ?: project.approvedQuoteId),
                "quotationNumber" to (quotation?.quoteNumber ?: project.approvedQuoteNumber),
                "clientId" to project.clientId,
                "clientSnapshot" to mapOf(
                    "clientId" to clientSnap.clientId,
                    "companyName" to clientSnap.companyName,
                    "contactPerson" to clientSnap.contactPerson,
                    "phone" to clientSnap.phone,
                    "email" to clientSnap.email,
                    "address" to clientSnap.address
                ),
                "companySnapshot" to mapOf(
                    "name" to compSnap.name,
                    "tagline" to compSnap.tagline,
                    "address" to compSnap.address,
                    "phone" to compSnap.phone,
                    "email" to compSnap.email,
                    "gstin" to compSnap.gstin,
                    "gstEnabled" to compSnap.gstEnabled,
                    "gstRatePercent" to compSnap.gstRatePercent
                ),
                "lines" to lines.map { line ->
                    mapOf(
                        "lineId" to line.lineId,
                        "title" to line.title,
                        "description" to line.description,
                        "quantity" to line.quantity,
                        "unitPricePaise" to line.unitPricePaise,
                        "lineTotalPaise" to line.lineTotalPaise
                    )
                },
                "subtotalPaise" to amountPaise,
                "discountPaise" to 0L,
                "gstEnabled" to false,
                "gstRatePercent" to 0,
                "gstAmountPaise" to 0L,
                "totalPaise" to amountPaise,
                "advanceCreditedPaise" to credited,
                "balanceDuePaise" to balanceDue,
                "issuedDateMillis" to now,
                "dueDateMillis" to effectiveDue,
                "notes" to notes.trim().take(600),
                "status" to statusEnum.name,
                "pdfLocalPath" to "",
                "pdfSha256" to "",
                "createdByUid" to actorUid,
                "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val writeTask = invoicesCollection().document(id).set(payload)
            if (NetworkConnectivityMonitor.isOnline.value) writeTask.await()

            core.logActivity(
                ActivityCategory.PAYMENT,
                "Invoice Created ($invoiceNumber)",
                "${project.title} • ${MoneyFormatter.formatInr(amountPaise)} • ${if (paid) "Paid" else "Pending"}",
                actorUid,
                actor.fullName,
                id
            )
            core.createNotification(
                category = NotificationCategory.PAYMENT,
                title = "INVOICE $invoiceNumber (${if (paid) "PAID" else "PENDING"})",
                body = "${project.title} • ${MoneyFormatter.formatInr(amountPaise)}",
                actor = actor,
                targetUid = "MANAGERS",
                entityId = id
            )

            Result.success(
                InvoiceRecord(
                    id = id,
                    invoiceNumber = invoiceNumber,
                    projectId = project.id,
                    projectTitle = project.title,
                    quotationId = quotation?.id ?: project.approvedQuoteId,
                    quotationNumber = quotation?.quoteNumber ?: project.approvedQuoteNumber,
                    clientId = project.clientId,
                    clientSnapshot = clientSnap,
                    companySnapshot = compSnap,
                    lines = lines,
                    subtotalPaise = amountPaise,
                    totalPaise = amountPaise,
                    advanceCreditedPaise = credited,
                    balanceDuePaise = balanceDue,
                    issuedDateMillis = now,
                    dueDateMillis = effectiveDue,
                    notes = notes.trim(),
                    status = statusEnum.name,
                    createdByUid = actorUid,
                    createdByName = actor.fullName
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

    suspend fun setInvoicePaidStatus(
        invoice: InvoiceRecord,
        paid: Boolean,
        actor: Member
    ): Result<Unit> {
        if (invoice.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Invoice ID cannot be blank."))
        }
        val path = "${collectionPath()}/${invoice.id}"
        val nextStatus = if (paid) InvoiceStatus.PAID.name else InvoiceStatus.ISSUED.name
        val nextBalance = if (paid) 0L else invoice.totalPaise.coerceAtLeast(0L)
        val nextCredited = if (paid) invoice.totalPaise.coerceAtLeast(0L) else 0L
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            val writeTask = invoicesCollection().document(invoice.id).update(
                mapOf(
                    "status" to nextStatus,
                    "balanceDuePaise" to nextBalance,
                    "advanceCreditedPaise" to nextCredited,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (NetworkConnectivityMonitor.isOnline.value) writeTask.await()
            core.logActivity(
                ActivityCategory.PAYMENT,
                "Invoice ${invoice.invoiceNumber} Marked ${if (paid) "Paid" else "Pending"}",
                "${invoice.projectTitle} • ${MoneyFormatter.formatInr(invoice.totalPaise)}",
                actorUid,
                actor.fullName,
                invoice.id
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

    suspend fun deleteInvoice(invoiceId: String, invoiceNumber: String, actor: Member): Result<Unit> {
        if (invoiceId.isBlank()) {
            return Result.failure(IllegalArgumentException("Invoice ID cannot be blank."))
        }
        val path = "${collectionPath()}/$invoiceId"
        return try {
            val uid = core.requireAuthenticatedUserId()
            val actorUid = actor.uid.ifBlank { uid }
            val writeTask = invoicesCollection().document(invoiceId).delete()
            if (NetworkConnectivityMonitor.isOnline.value) writeTask.await()
            core.logActivity(
                ActivityCategory.PAYMENT,
                "Invoice Deleted",
                invoiceNumber.ifBlank { invoiceId },
                actorUid,
                actor.fullName,
                invoiceId
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
