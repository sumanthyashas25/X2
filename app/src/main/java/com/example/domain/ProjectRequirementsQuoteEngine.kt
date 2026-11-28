package com.example.domain

import com.example.model.BillingFrequency
import com.example.model.CatalogueItem
import com.example.model.CatalogueKind
import com.example.model.Client
import com.example.model.ClientSnapshot
import com.example.model.CompanyProfile
import com.example.model.CompanySnapshot
import com.example.model.PricingMode
import com.example.model.Quotation
import com.example.model.QuotationStatus
import com.example.model.QuoteLineItem

data class RequirementServiceSelection(
    val item: CatalogueItem,
    val quantity: Int = 1
)

data class ProjectRequirementInput(
    val selectedClientId: String = "",
    val clientCompanyName: String = "",
    val clientContactPerson: String = "",
    val clientEmail: String = "",
    val clientPhone: String = "",
    val projectTitle: String = "",
    val requirementsBrief: String = "",
    val deliverablesText: String = "",
    val selectedServices: List<RequirementServiceSelection> = emptyList(),
    val customLineTitle: String = "",
    val customLinePricePaise: Long = 0L,
    val urgentDelivery: Boolean = false,
    val includeGst: Boolean = false,
    val gstRatePercent: Int = 18,
    val discountPaise: Long = 0L,
    val advancePercent: Int = 50,
    val timelineNote: String = "10-14 business days from advance & asset handover",
    val validityDays: Int = 15,
    val revisionRounds: Int = 2
)

data class StructuredQuotationSummary(
    val isReady: Boolean,
    val validationHints: List<String>,
    val clientDisplayName: String,
    val contactDisplayName: String,
    val projectTitle: String,
    val scopeHighlights: List<String>,
    val calculation: QuoteCalculationResult,
    val timelineNote: String,
    val paymentTermsSummary: String,
    val revisionPolicySummary: String,
    val validityLabel: String,
    val formattedClientMessage: String
)

object ProjectRequirementsQuoteEngine {

    fun parseDeliverablesList(deliverablesText: String): List<String> {
        return deliverablesText
            .lineSequence()
            .map { it.trim().removePrefix("•").removePrefix("-").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    fun buildLineItems(input: ProjectRequirementInput): List<QuoteLineItem> {
        val catalogueLines = input.selectedServices.mapIndexed { idx, selection ->
            val base = QuoteCalculator.fromCatalogueItem(
                item = selection.item,
                lineId = "req_${selection.item.id}_$idx"
            )
            base.copy(quantity = selection.quantity.coerceAtLeast(1))
        }

        val customTitle = input.customLineTitle.trim()
        val hasCustom = customTitle.isNotBlank() && input.customLinePricePaise > 0L
        if (!hasCustom) return catalogueLines

        val customLine = QuoteLineItem(
            lineId = "req_custom_line",
            catalogueItemId = "",
            kind = CatalogueKind.SERVICE.name,
            category = "Custom Requirement",
            title = customTitle,
            description = "Custom scope requirement captured during briefing",
            pricingMode = PricingMode.FIXED.name,
            quantity = 1,
            unitPricePaise = input.customLinePricePaise,
            overridePricePaise = null,
            lineTotalPaise = input.customLinePricePaise,
            billingFrequency = BillingFrequency.ONE_TIME.name,
            separatelyBilled = false,
            urgentEligible = true,
            sortOrder = catalogueLines.size
        )
        return catalogueLines + customLine
    }

    fun generateSummary(
        input: ProjectRequirementInput,
        companyName: String = "X.MAX"
    ): StructuredQuotationSummary {
        val cleanCompany = companyName.trim().ifBlank { "X.MAX" }
        val cleanClient = input.clientCompanyName.trim().ifBlank { "Prospective Client" }
        val cleanContact = input.clientContactPerson.trim()
        val cleanTitle = input.projectTitle.trim().ifBlank { "Untitled Project Proposal" }
        val cleanTimeline = input.timelineNote.trim().ifBlank { "10-14 business days from advance & asset handover" }
        val clampedAdvance = input.advancePercent.coerceIn(0, 100)
        val clampedValidity = input.validityDays.coerceIn(1, 90)
        val clampedRevisions = input.revisionRounds.coerceIn(0, 10)
        val deliverables = parseDeliverablesList(input.deliverablesText)

        val rawLines = buildLineItems(input)
        val calculation = QuoteCalculator.calculate(
            rawLines = rawLines,
            urgentDeliveryEnabled = input.urgentDelivery,
            urgentSurchargePercent = 25,
            discountPaise = input.discountPaise.coerceAtLeast(0L),
            gstEnabled = input.includeGst,
            gstRatePercent = input.gstRatePercent.coerceAtLeast(0),
            advancePercent = clampedAdvance,
            allowUnpricedDraft = true
        )

        val hints = mutableListOf<String>()
        if (input.clientCompanyName.isBlank()) hints += "Enter a client company or select an existing client."
        if (input.projectTitle.isBlank()) hints += "Enter a clear project title."
        if (rawLines.isEmpty()) hints += "Select at least one service, package, or custom line item."
        if (input.requirementsBrief.isBlank() && deliverables.isEmpty()) {
            hints += "Add brief requirements or expected deliverables for a clearer client summary."
        }
        hints += calculation.validationErrors

        val scopeHighlights = buildList {
            if (input.requirementsBrief.isNotBlank()) {
                add(input.requirementsBrief.trim())
            }
            deliverables.forEach { add(it) }
        }

        val balancePct = (100 - clampedAdvance).coerceAtLeast(0)
        val paymentTermsSummary = "$clampedAdvance% advance (${MoneyFormatter.formatInr(calculation.advancePaise)}) to initiate, " +
            "$balancePct% balance (${MoneyFormatter.formatInr(calculation.balancePaise)}) on final delivery."
        val revisionPolicySummary = "Includes $clampedRevisions structured revision round${if (clampedRevisions == 1) "" else "s"} per deliverable."
        val validityLabel = "Valid for $clampedValidity days"

        val formattedMessage = buildString {
            appendLine("$cleanCompany — QUOTATION SUMMARY")
            appendLine("====================================")
            append("Client: $cleanClient")
            if (cleanContact.isNotBlank()) append(" ($cleanContact)")
            appendLine()
            if (input.clientEmail.isNotBlank()) appendLine("Email: ${input.clientEmail.trim()}")
            if (input.clientPhone.isNotBlank()) appendLine("Phone: ${input.clientPhone.trim()}")
            appendLine("Project: $cleanTitle")
            appendLine("Validity: $validityLabel")
            appendLine()

            if (scopeHighlights.isNotEmpty()) {
                appendLine("PROJECT REQUIREMENTS & SCOPE")
                scopeHighlights.forEach { bullet ->
                    appendLine("• $bullet")
                }
                appendLine()
            }

            appendLine("ITEMIZED PRICING SCHEDULE")
            if (calculation.computedLines.isEmpty()) {
                appendLine("• No services selected yet")
            } else {
                calculation.computedLines.forEachIndexed { index, line ->
                    val amountLabel = when {
                        line.separatelyBilled -> "Separately Billed"
                        line.lineTotalPaise <= 0L -> "Custom Quote"
                        else -> MoneyFormatter.formatInr(line.lineTotalPaise)
                    }
                    val qtyPrefix = if (line.quantity > 1) "${line.quantity} x " else ""
                    appendLine("${index + 1}. $qtyPrefix${line.title} — $amountLabel")
                }
            }
            appendLine()

            appendLine("COMMERCIAL SUMMARY")
            appendLine("• Subtotal: ${MoneyFormatter.formatInr(calculation.subtotalPaise)}")
            if (calculation.surchargePaise > 0L) {
                appendLine("• Urgent Delivery (+25%): ${MoneyFormatter.formatInr(calculation.surchargePaise)}")
            }
            if (calculation.discountPaise > 0L) {
                appendLine("• Discount: -${MoneyFormatter.formatInr(calculation.discountPaise)}")
            }
            if (input.includeGst && calculation.gstAmountPaise > 0L) {
                appendLine("• GST (${input.gstRatePercent}%): ${MoneyFormatter.formatInr(calculation.gstAmountPaise)}")
            }
            appendLine("• Grand Total: ${MoneyFormatter.formatInr(calculation.totalPaise)}")
            appendLine("• Advance Due ($clampedAdvance%): ${MoneyFormatter.formatInr(calculation.advancePaise)}")
            appendLine("• Balance on Completion: ${MoneyFormatter.formatInr(calculation.balancePaise)}")
            appendLine()
            appendLine("TIMELINE & TERMS")
            appendLine("• Delivery Timeline: $cleanTimeline")
            appendLine("• Revisions: $revisionPolicySummary")
            append("• Payment Terms: $paymentTermsSummary")
        }

        val ready = input.clientCompanyName.isNotBlank() &&
            input.projectTitle.isNotBlank() &&
            rawLines.isNotEmpty() &&
            calculation.validationErrors.isEmpty()

        return StructuredQuotationSummary(
            isReady = ready,
            validationHints = hints.distinct(),
            clientDisplayName = cleanClient,
            contactDisplayName = cleanContact,
            projectTitle = cleanTitle,
            scopeHighlights = scopeHighlights,
            calculation = calculation,
            timelineNote = cleanTimeline,
            paymentTermsSummary = paymentTermsSummary,
            revisionPolicySummary = revisionPolicySummary,
            validityLabel = validityLabel,
            formattedClientMessage = formattedMessage
        )
    }

    fun toDraftQuotation(
        input: ProjectRequirementInput,
        summary: StructuredQuotationSummary,
        client: Client?,
        company: CompanyProfile?
    ): Quotation {
        val calc = summary.calculation
        val comp = company ?: CompanyProfile()
        val clientSnap = client?.let {
            ClientSnapshot(
                clientId = it.id,
                companyName = it.companyName,
                contactPerson = it.contactPerson,
                phone = it.phone,
                email = it.email,
                address = it.address
            )
        } ?: ClientSnapshot(
            clientId = input.selectedClientId,
            companyName = input.clientCompanyName.trim(),
            contactPerson = input.clientContactPerson.trim(),
            phone = input.clientPhone.trim(),
            email = input.clientEmail.trim()
        )
        val combinedBrief = buildString {
            if (input.requirementsBrief.isNotBlank()) append(input.requirementsBrief.trim())
            val delivs = parseDeliverablesList(input.deliverablesText)
            if (delivs.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Key Deliverables:\n")
                append(delivs.joinToString("\n") { "• $it" })
            }
        }
        return Quotation(
            clientId = client?.id ?: input.selectedClientId,
            projectTitle = summary.projectTitle,
            scopeSummary = combinedBrief,
            status = QuotationStatus.DRAFT.name,
            companySnapshot = CompanySnapshot(
                name = comp.name,
                tagline = comp.tagline,
                address = comp.address,
                phone = comp.phone,
                email = comp.email,
                gstin = comp.gstin,
                gstEnabled = input.includeGst,
                gstRatePercent = input.gstRatePercent
            ),
            clientSnapshot = clientSnap,
            lines = calc.computedLines,
            subtotalPaise = calc.subtotalPaise,
            discountPaise = calc.discountPaise,
            surchargeBasePaise = calc.surchargeBasePaise,
            surchargePaise = calc.surchargePaise,
            gstEnabled = input.includeGst,
            gstRatePercent = input.gstRatePercent,
            gstAmountPaise = calc.gstAmountPaise,
            totalPaise = calc.totalPaise,
            advancePercent = input.advancePercent.coerceIn(0, 100),
            advancePaise = calc.advancePaise,
            balancePaise = calc.balancePaise,
            paymentTerms = summary.paymentTermsSummary,
            revisionPolicy = summary.revisionPolicySummary,
            timelineNote = summary.timelineNote,
            separatelyBilledNote = if (calc.computedLines.any { it.separatelyBilled }) {
                "Items marked 'Separately Billed' are paid directly by the client or billed at actuals."
            } else ""
        )
    }
}
