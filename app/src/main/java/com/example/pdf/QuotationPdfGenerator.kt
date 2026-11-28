package com.example.pdf

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.R
import com.example.domain.MoneyFormatter
import com.example.model.CompanyProfile
import com.example.model.PaymentRecord
import com.example.model.Project
import com.example.model.Quotation
import com.example.model.TimeEntry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class GeneratedPdfResult(
    val file: File,
    val sha256: String
)

object QuotationPdfGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f
    private const val MARGIN_TOP = 38f
    private const val MARGIN_BOTTOM = 44f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 80f // 515f

    private const val COL_ITEM_X = MARGIN_X
    private const val COL_ITEM_W = 245f
    private const val COL_QTY_X = COL_ITEM_X + COL_ITEM_W + 8f
    private const val COL_QTY_W = 82f
    private const val COL_RATE_X = COL_QTY_X + COL_QTY_W + 6f
    private const val COL_RATE_W = 84f
    private const val COL_AMT_RIGHT = MARGIN_X + CONTENT_WIDTH

    private val indigoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 70, 229) }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 17, 25) }
    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 244, 252) }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 252) }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(209, 213, 226)
        strokeWidth = 0.8f
    }

    private fun makeTextPaint(
        size: Float,
        color: Int = Color.rgb(15, 23, 42),
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = align
    }

    fun buildPdfTargetFile(context: Context, quotation: Quotation): File {
        val dir = File(context.filesDir, "quotations").apply { mkdirs() }
        val safeNumber = quotation.quoteNumber.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "${safeNumber}_v${quotation.revision}.pdf")
    }

    fun generateAndSave(context: Context, quotation: Quotation): GeneratedPdfResult {
        val outFile = buildPdfTargetFile(context, quotation)

        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN_TOP

        val titlePaint = makeTextPaint(18f, Color.WHITE, bold = true)
        val subtitlePaint = makeTextPaint(9.5f, Color.rgb(224, 231, 255))
        val h2Paint = makeTextPaint(11.5f, Color.rgb(15, 23, 42), bold = true)
        val bodyPaint = makeTextPaint(9.2f, Color.rgb(30, 41, 59))
        val mutedPaint = makeTextPaint(8.5f, Color.rgb(100, 116, 139))
        val boldBodyPaint = makeTextPaint(9.2f, Color.rgb(15, 23, 42), bold = true)
        val rightPaint = makeTextPaint(9.2f, Color.rgb(15, 23, 42), bold = false, align = Paint.Align.RIGHT)
        val rightBoldPaint = makeTextPaint(9.6f, Color.rgb(15, 23, 42), bold = true, align = Paint.Align.RIGHT)

        fun startNewPage(drawTableHeader: Boolean) {
            drawPageFooter(canvas, quotation, pageNumber, mutedPaint)
            doc.finishPage(page)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN_TOP
            // Mini continuation header
            canvas.drawText("${quotation.companySnapshot.name} • Quotation ${quotation.quoteNumber} (Rev v${quotation.revision})", MARGIN_X, y + 10f, mutedPaint)
            y += 18f
            canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, dividerPaint)
            y += 10f
            if (drawTableHeader) {
                y = drawItemsTableHeader(canvas, y, boldBodyPaint, rightBoldPaint)
            }
        }

        // === TOP BRAND BANNER ===
        val bannerRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 74f)
        canvas.drawRoundRect(bannerRect, 10f, 10f, darkPaint)

        val logoBitmap = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.logo_xmax)
        }.getOrNull()

        if (logoBitmap != null && logoBitmap.width > 0 && logoBitmap.height > 0) {
            val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            val targetH = 22f
            val targetW = targetH * (logoBitmap.width.toFloat() / logoBitmap.height.toFloat())
            val logoRect = RectF(MARGIN_X + 16f, y + 16f, MARGIN_X + 16f + targetW, y + 16f + targetH)
            canvas.drawBitmap(logoBitmap, null, logoRect, logoPaint)
            if (!quotation.companySnapshot.name.equals("X.MAX", ignoreCase = true) &&
                quotation.companySnapshot.name.isNotBlank()
            ) {
                canvas.drawText(quotation.companySnapshot.name, logoRect.right + 10f, y + 34f, titlePaint)
            }
            canvas.drawText(
                quotation.companySnapshot.tagline.ifBlank { "Creative & Digital Marketing Agency" },
                MARGIN_X + 16f,
                y + 53f,
                subtitlePaint
            )
        } else {
            val badgeRect = RectF(MARGIN_X + 14f, y + 14f, MARGIN_X + 58f, y + 58f)
            canvas.drawRoundRect(badgeRect, 8f, 8f, indigoPaint)
            val markPaint = makeTextPaint(14f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
            canvas.drawText("X.M", badgeRect.centerX(), badgeRect.centerY() + 5f, markPaint)
            canvas.drawText(quotation.companySnapshot.name, MARGIN_X + 70f, y + 31f, titlePaint)
            canvas.drawText(
                quotation.companySnapshot.tagline.ifBlank { "Creative & Digital Marketing Agency" },
                MARGIN_X + 70f,
                y + 47f,
                subtitlePaint
            )
        }
        val qLabelRight = makeTextPaint(13f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        val qSubRight = makeTextPaint(9.5f, Color.rgb(199, 210, 254), bold = false, align = Paint.Align.RIGHT)
        canvas.drawText("QUOTATION", MARGIN_X + CONTENT_WIDTH - 16f, y + 29f, qLabelRight)
        canvas.drawText("${quotation.quoteNumber}  |  Rev v${quotation.revision}", MARGIN_X + CONTENT_WIDTH - 16f, y + 46f, qSubRight)
        val statusText = if (quotation.status == "CLIENT_APPROVED") "CLIENT APPROVED" else quotation.status
        canvas.drawText(statusText, MARGIN_X + CONTENT_WIDTH - 16f, y + 60f, qSubRight)

        y += 86f

        // === FROM (COMPANY SNAPSHOT) & PREPARED FOR (CLIENT SNAPSHOT) ===
        val colHalf = (CONTENT_WIDTH - 14f) / 2f
        val leftX = MARGIN_X
        val rightX = MARGIN_X + colHalf + 14f

        val fromLines = buildList {
            add("From: ${quotation.companySnapshot.name}")
            if (quotation.companySnapshot.address.isNotBlank()) add(quotation.companySnapshot.address)
            val contactLine = listOfNotNull(
                quotation.companySnapshot.phone.takeIf { it.isNotBlank() },
                quotation.companySnapshot.email.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (contactLine.isNotBlank()) add(contactLine)
            if (quotation.companySnapshot.gstEnabled && quotation.companySnapshot.gstin.isNotBlank()) {
                add("GSTIN: ${quotation.companySnapshot.gstin}")
            }
        }
        val toLines = buildList {
            add("Prepared For: ${quotation.clientSnapshot.companyName}")
            if (quotation.clientSnapshot.contactPerson.isNotBlank()) add("Attn: ${quotation.clientSnapshot.contactPerson}")
            val clientContact = listOfNotNull(
                quotation.clientSnapshot.phone.takeIf { it.isNotBlank() },
                quotation.clientSnapshot.email.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (clientContact.isNotBlank()) add(clientContact)
            if (quotation.clientSnapshot.address.isNotBlank()) add(quotation.clientSnapshot.address)
        }

        val leftLayout = buildStaticLayout(fromLines.joinToString("\n"), bodyPaint, colHalf.toInt() - 16)
        val rightLayout = buildStaticLayout(toLines.joinToString("\n"), bodyPaint, colHalf.toInt() - 16)
        val metaHeight = maxOf(leftLayout.height, rightLayout.height) + 18f

        canvas.drawRoundRect(RectF(leftX, y, leftX + colHalf, y + metaHeight), 8f, 8f, cardBgPaint)
        canvas.drawRoundRect(RectF(rightX, y, rightX + colHalf, y + metaHeight), 8f, 8f, cardBgPaint)
        drawStaticLayout(canvas, leftLayout, leftX + 10f, y + 9f)
        drawStaticLayout(canvas, rightLayout, rightX + 10f, y + 9f)
        y += metaHeight + 10f

        // === PROJECT & DATES STRIP ===
        val issuedStr = MoneyFormatter.formatDate(quotation.issuedDateMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
        val validStr = MoneyFormatter.formatDate(quotation.expiryDateMillis)
        canvas.drawText("Project: ${quotation.projectTitle}", MARGIN_X, y + 11f, h2Paint)
        canvas.drawText("Issued: $issuedStr  •  Valid Until: $validStr", MARGIN_X + CONTENT_WIDTH, y + 11f, rightPaint)
        y += 18f
        if (quotation.scopeSummary.isNotBlank()) {
            val scopeLayout = buildStaticLayout(quotation.scopeSummary, mutedPaint, CONTENT_WIDTH.toInt())
            drawStaticLayout(canvas, scopeLayout, MARGIN_X, y)
            y += scopeLayout.height + 8f
        }
        canvas.drawText("Prepared by: ${quotation.preparedByName}", MARGIN_X, y + 9f, mutedPaint)
        y += 18f

        // === ITEMIZED SERVICES & DELIVERABLES (CATALOGUE TICKET DESIGN) ===
        y = drawItemsTableHeader(canvas, y, boldBodyPaint, rightBoldPaint)

        for ((idx, line) in quotation.lines.withIndex()) {
            val palette = ticketPaletteForIndex(idx)
            val freqLabel = if (line.billingFrequency == "ONE_TIME") "" else " / ${line.billingFrequency.lowercase()}"
            val effectiveUnit = line.overridePricePaise ?: line.unitPricePaise
            val rateStr = if (line.separatelyBilled) "Actuals" else MoneyFormatter.formatInr(effectiveUnit)
            val amtStr = if (line.separatelyBilled) "Separately Billed" else MoneyFormatter.formatInr(line.lineTotalPaise)
            val qtyMeta = "Qty: ${line.quantity}$freqLabel  •  Rate: $rateStr"

            val whitePanelLeft = MARGIN_X + 64f
            val whitePanelRight = MARGIN_X + CONTENT_WIDTH - 8f
            val whitePanelInnerWidth = (whitePanelRight - whitePanelLeft - 24f).toInt()
            val amtWidthReserve = rightBoldPaint.measureText(amtStr) + 12f
            val titleWidth = (whitePanelInnerWidth - amtWidthReserve).toInt().coerceAtLeast(110)

            val titleLayout = buildStaticLayout(line.title, boldBodyPaint, titleWidth)
            val metaLayout = buildStaticLayout(qtyMeta, mutedPaint, whitePanelInnerWidth)
            val detailsText = buildString {
                if (line.description.isNotBlank()) append(line.description.trim())
                if (line.inclusions.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("Includes: ")
                    append(line.inclusions.joinToString(" • ") {
                        if (it.quantity > 1) "${it.quantity}× ${it.title}" else it.title
                    })
                }
            }
            val detailLayout = if (detailsText.isNotBlank()) {
                buildStaticLayout(detailsText, mutedPaint, whitePanelInnerWidth)
            } else null

            val panelContentH = titleLayout.height + 4f + metaLayout.height +
                (detailLayout?.let { 5f + it.height } ?: 0f) + 18f
            val cardHeight = maxOf(62f, panelContentH + 14f)

            if (y + cardHeight > PAGE_HEIGHT - MARGIN_BOTTOM - 16f) {
                startNewPage(drawTableHeader = true)
            }

            val cardRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + cardHeight)
            val ticketPath = buildTicketPath(cardRect, cornerRadius = 15f, notchRadius = 6.5f)

            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    cardRect.left, cardRect.top, cardRect.right, cardRect.top,
                    palette.startColor, palette.endColor,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(ticketPath, gradPaint)

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.1f
                color = palette.borderColor
            }
            canvas.drawPath(ticketPath, strokePaint)

            // Left circular category emblem centered beside ticket notch
            val emblemCx = MARGIN_X + 36f
            val emblemCy = cardRect.centerY()
            val emblemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.emblemBg }
            val emblemRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                color = Color.argb(180, 255, 255, 255)
            }
            canvas.drawCircle(emblemCx, emblemCy, 15.5f, emblemBgPaint)
            canvas.drawCircle(emblemCx, emblemCy, 15.5f, emblemRingPaint)
            val monogram = ticketMonogram(line.category, line.title)
            val emblemTextPaint = makeTextPaint(9.5f, palette.emblemTextColor, bold = true, align = Paint.Align.CENTER)
            canvas.drawText(monogram, emblemCx, emblemCy + 3.5f, emblemTextPaint)

            // Inset rounded crisp white detail panel matching CatalogueServiceTicketCard
            val panelRect = RectF(whitePanelLeft, y + 7f, whitePanelRight, y + cardHeight - 7f)
            val whitePanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(242, 255, 255, 255)
            }
            canvas.drawRoundRect(panelRect, 11f, 11f, whitePanelPaint)

            val contentX = panelRect.left + 12f
            var cy = panelRect.top + 9f
            drawStaticLayout(canvas, titleLayout, contentX, cy)
            canvas.drawText(amtStr, panelRect.right - 12f, cy + 10f, rightBoldPaint)
            cy += titleLayout.height + 4f
            drawStaticLayout(canvas, metaLayout, contentX, cy)
            cy += metaLayout.height
            if (detailLayout != null) {
                cy += 5f
                drawStaticLayout(canvas, detailLayout, contentX, cy)
            }

            y += cardHeight + 8f
        }

        // === TOTALS BLOCK (kept together on page) ===
        val totalsBlockHeight = 148f
        if (y + totalsBlockHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
            startNewPage(drawTableHeader = false)
        }
        y += 12f
        val totalsW = 265f
        val totalsX = MARGIN_X + CONTENT_WIDTH - totalsW
        val totalsRows = mutableListOf<Pair<String, String>>()
        totalsRows += "Subtotal" to MoneyFormatter.formatInr(quotation.subtotalPaise)
        if (quotation.surchargePaise > 0L) {
            totalsRows += "Urgent Delivery (25% on ${MoneyFormatter.formatInr(quotation.surchargeBasePaise)})" to MoneyFormatter.formatInr(quotation.surchargePaise)
        }
        if (quotation.discountPaise > 0L) {
            totalsRows += "Commercial Discount" to "-${MoneyFormatter.formatInr(quotation.discountPaise)}"
        }
        if (quotation.gstEnabled && quotation.gstAmountPaise > 0L) {
            totalsRows += "GST (${quotation.gstRatePercent}%)" to MoneyFormatter.formatInr(quotation.gstAmountPaise)
        }

        val boxHeight = totalsRows.size * 18f + 62f
        canvas.drawRoundRect(RectF(totalsX, y, MARGIN_X + CONTENT_WIDTH, y + boxHeight), 8f, 8f, cardBgPaint)
        var ty = y + 18f
        for ((k, v) in totalsRows) {
            canvas.drawText(k, totalsX + 12f, ty, mutedPaint)
            canvas.drawText(v, MARGIN_X + CONTENT_WIDTH - 12f, ty, rightPaint)
            ty += 18f
        }
        canvas.drawLine(totalsX + 10f, ty - 8f, MARGIN_X + CONTENT_WIDTH - 10f, ty - 8f, dividerPaint)
        ty += 8f
        val grandLabelPaint = makeTextPaint(11f, Color.rgb(79, 70, 229), bold = true)
        val grandValPaint = makeTextPaint(12f, Color.rgb(13, 17, 25), bold = true, align = Paint.Align.RIGHT)
        canvas.drawText("TOTAL", totalsX + 12f, ty, grandLabelPaint)
        canvas.drawText(MoneyFormatter.formatInr(quotation.totalPaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, grandValPaint)
        ty += 18f
        canvas.drawText("Advance (${quotation.advancePercent}%)", totalsX + 12f, ty, bodyPaint)
        canvas.drawText(MoneyFormatter.formatInr(quotation.advancePaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, rightBoldPaint)
        ty += 15f
        canvas.drawText("Balance on Delivery", totalsX + 12f, ty, mutedPaint)
        canvas.drawText(MoneyFormatter.formatInr(quotation.balancePaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, rightPaint)

        y += boxHeight + 16f

        // === COMMERCIAL TERMS & APPROVAL ===
        val termsText = buildString {
            appendLine("Commercial Terms & Approval:")
            if (quotation.paymentTerms.isNotBlank()) appendLine("• Payment Terms: ${quotation.paymentTerms}")
            if (quotation.revisionPolicy.isNotBlank()) appendLine("• Revisions: ${quotation.revisionPolicy}")
            if (quotation.timelineNote.isNotBlank()) appendLine("• Timeline: ${quotation.timelineNote}")
            if (quotation.separatelyBilledNote.isNotBlank()) appendLine("• Third-Party / Ad Spend: ${quotation.separatelyBilledNote}")
            if (quotation.approvalInstructions.isNotBlank()) appendLine("• Approval: ${quotation.approvalInstructions}")
        }.trim()
        val termsLayout = buildStaticLayout(termsText, bodyPaint, CONTENT_WIDTH.toInt())
        if (y + termsLayout.height + 24f > PAGE_HEIGHT - MARGIN_BOTTOM) {
            startNewPage(drawTableHeader = false)
        }
        drawStaticLayout(canvas, termsLayout, MARGIN_X, y)

        drawPageFooter(canvas, quotation, pageNumber, mutedPaint)
        doc.finishPage(page)

        FileOutputStream(outFile).use { fos ->
            doc.writeTo(fos)
        }
        doc.close()

        return GeneratedPdfResult(
            file = outFile,
            sha256 = sha256Hex(outFile)
        )
    }

    fun buildShareIntent(context: Context, file: File, quotation: Quotation): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "${quotation.companySnapshot.name} Quotation ${quotation.quoteNumber} (v${quotation.revision}) - ${quotation.projectTitle}"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "Please find attached quotation ${quotation.quoteNumber} (Revision v${quotation.revision}) for ${quotation.projectTitle}. Total: ${MoneyFormatter.formatInr(quotation.totalPaise)}."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launchShareSheet(context: Context, file: File, quotation: Quotation) {
        val shareIntent = buildShareIntent(context, file, quotation)
        val chooser = Intent.createChooser(shareIntent, "Share Quotation PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun buildInvoiceTargetFile(context: Context, project: Project): File {
        val dir = File(context.filesDir, "invoices").apply { mkdirs() }
        val safeId = project.id.ifBlank { project.title }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(40)
        return File(dir, "INV_${safeId}.pdf")
    }

    data class TaxInvoiceSummary(
        val invoiceNumber: String,
        val gstEnabled: Boolean,
        val gstRatePercent: Int,
        val taxablePaise: Long,
        val cgstPaise: Long,
        val sgstPaise: Long,
        val grossPaise: Long,
        val paidPaise: Long,
        val balanceDuePaise: Long
    )

    fun computeTaxInvoiceSummary(
        project: Project,
        quotation: Quotation?,
        company: CompanyProfile?
    ): TaxInvoiceSummary {
        val invoiceNo = "INV-${project.id.takeLast(6).uppercase().ifBlank { "XMAX" }}"
        val gstEnabled = quotation?.gstEnabled ?: (company?.gstEnabled ?: true)
        val gstPercent = quotation?.gstRatePercent ?: (company?.gstRatePercent ?: 18)
        val grossPaise = project.agreedValuePaise.coerceAtLeast(0L)
        val taxablePaise = if (gstEnabled && gstPercent > 0) {
            quotation?.takeIf { it.subtotalPaise > 0L }
                ?.let { (it.subtotalPaise + it.surchargePaise - it.discountPaise).coerceAtLeast(0L) }
                ?: ((grossPaise * 100L) / (100L + gstPercent))
        } else grossPaise
        val totalTaxPaise = if (gstEnabled && gstPercent > 0) {
            (grossPaise - taxablePaise).coerceAtLeast(0L)
        } else 0L
        val cgstPaise = totalTaxPaise / 2L
        val sgstPaise = totalTaxPaise - cgstPaise
        val paidPaise = project.paidPaise.coerceAtLeast(0L)
        val balancePaise = (grossPaise - paidPaise).coerceAtLeast(0L)
        return TaxInvoiceSummary(
            invoiceNumber = invoiceNo,
            gstEnabled = gstEnabled,
            gstRatePercent = gstPercent,
            taxablePaise = taxablePaise,
            cgstPaise = cgstPaise,
            sgstPaise = sgstPaise,
            grossPaise = grossPaise,
            paidPaise = paidPaise,
            balanceDuePaise = balancePaise
        )
    }

    fun generateTaxInvoicePdf(
        context: Context,
        project: Project,
        quotation: Quotation?,
        payments: List<PaymentRecord>,
        company: CompanyProfile?,
        timeEntries: List<TimeEntry> = emptyList()
    ): GeneratedPdfResult {
        val outFile = buildInvoiceTargetFile(context, project)
        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN_TOP

        val titlePaint = makeTextPaint(18f, Color.WHITE, bold = true)
        val subtitlePaint = makeTextPaint(9.5f, Color.rgb(224, 231, 255))
        val h2Paint = makeTextPaint(11.5f, Color.rgb(15, 23, 42), bold = true)
        val bodyPaint = makeTextPaint(9.2f, Color.rgb(30, 41, 59))
        val mutedPaint = makeTextPaint(8.5f, Color.rgb(100, 116, 139))
        val boldBodyPaint = makeTextPaint(9.2f, Color.rgb(15, 23, 42), bold = true)
        val rightPaint = makeTextPaint(9.2f, Color.rgb(15, 23, 42), bold = false, align = Paint.Align.RIGHT)
        val rightBoldPaint = makeTextPaint(9.6f, Color.rgb(15, 23, 42), bold = true, align = Paint.Align.RIGHT)

        val invoiceNo = "INV-${project.id.takeLast(6).uppercase().ifBlank { "XMAX" }}"
        val companyName = company?.name?.ifBlank { quotation?.companySnapshot?.name } ?: "X.MAX Agency"
        val companyTagline = company?.tagline?.ifBlank { "Creative & Digital Marketing Agency" }
            ?: "Creative & Digital Marketing Agency"
        val companyAddress = company?.address?.ifBlank { quotation?.companySnapshot?.address } ?: ""
        val companyPhone = company?.phone?.ifBlank { quotation?.companySnapshot?.phone } ?: ""
        val companyEmail = company?.email?.ifBlank { quotation?.companySnapshot?.email } ?: ""
        val gstin = company?.gstin?.ifBlank { quotation?.companySnapshot?.gstin } ?: ""
        val gstEnabled = quotation?.gstEnabled ?: (company?.gstEnabled ?: true)
        val gstPercent = quotation?.gstRatePercent ?: (company?.gstRatePercent ?: 18)

        fun drawInvoiceFooter() {
            val footerY = PAGE_HEIGHT - 22f
            canvas.drawLine(MARGIN_X, footerY - 10f, MARGIN_X + CONTENT_WIDTH, footerY - 10f, dividerPaint)
            canvas.drawText("$companyName • Tax Invoice $invoiceNo • SAC 998313", MARGIN_X, footerY, mutedPaint)
            val rightMuted = TextPaint(mutedPaint).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText("Page $pageNumber", MARGIN_X + CONTENT_WIDTH, footerY, rightMuted)
        }

        fun startInvoiceNewPage() {
            drawInvoiceFooter()
            doc.finishPage(page)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN_TOP
            canvas.drawText("$companyName • Tax Invoice $invoiceNo", MARGIN_X, y + 10f, mutedPaint)
            y += 18f
            canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, dividerPaint)
            y += 12f
        }

        // === TOP BANNER ===
        val bannerRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 74f)
        canvas.drawRoundRect(bannerRect, 10f, 10f, darkPaint)
        canvas.drawText(companyName, MARGIN_X + 16f, y + 31f, titlePaint)
        canvas.drawText(companyTagline, MARGIN_X + 16f, y + 51f, subtitlePaint)
        val invRight = makeTextPaint(13f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        val invSubRight = makeTextPaint(9.5f, Color.rgb(199, 210, 254), bold = false, align = Paint.Align.RIGHT)
        canvas.drawText("TAX INVOICE", MARGIN_X + CONTENT_WIDTH - 16f, y + 29f, invRight)
        canvas.drawText("$invoiceNo  |  SAC: 998313", MARGIN_X + CONTENT_WIDTH - 16f, y + 46f, invSubRight)
        canvas.drawText("Date: ${MoneyFormatter.formatDate(System.currentTimeMillis())}", MARGIN_X + CONTENT_WIDTH - 16f, y + 60f, invSubRight)
        y += 86f

        // === SUPPLIER & BILL TO ===
        val colHalf = (CONTENT_WIDTH - 14f) / 2f
        val leftX = MARGIN_X
        val rightX = MARGIN_X + colHalf + 14f
        val supplierLines = buildList {
            add("Supplier: $companyName")
            if (companyAddress.isNotBlank()) add(companyAddress)
            val cLine = listOfNotNull(companyPhone.takeIf { it.isNotBlank() }, companyEmail.takeIf { it.isNotBlank() }).joinToString(" • ")
            if (cLine.isNotBlank()) add(cLine)
            if (gstin.isNotBlank()) add("GSTIN: $gstin")
        }
        val billToLines = buildList {
            add("Bill To: ${project.clientCompanyName.ifBlank { quotation?.clientSnapshot?.companyName ?: "Client" }}")
            quotation?.clientSnapshot?.contactPerson?.takeIf { it.isNotBlank() }?.let { add("Attn: $it") }
            quotation?.clientSnapshot?.let { cs ->
                val c = listOfNotNull(cs.phone.takeIf { it.isNotBlank() }, cs.email.takeIf { it.isNotBlank() }).joinToString(" • ")
                if (c.isNotBlank()) add(c)
                if (cs.address.isNotBlank()) add(cs.address)
            }
        }
        val leftLayout = buildStaticLayout(supplierLines.joinToString("\n"), bodyPaint, colHalf.toInt() - 16)
        val rightLayout = buildStaticLayout(billToLines.joinToString("\n"), bodyPaint, colHalf.toInt() - 16)
        val metaH = maxOf(leftLayout.height, rightLayout.height) + 18f
        canvas.drawRoundRect(RectF(leftX, y, leftX + colHalf, y + metaH), 8f, 8f, cardBgPaint)
        canvas.drawRoundRect(RectF(rightX, y, rightX + colHalf, y + metaH), 8f, 8f, cardBgPaint)
        drawStaticLayout(canvas, leftLayout, leftX + 10f, y + 9f)
        drawStaticLayout(canvas, rightLayout, rightX + 10f, y + 9f)
        y += metaH + 12f

        canvas.drawText("Project: ${project.title}", MARGIN_X, y + 11f, h2Paint)
        quotation?.quoteNumber?.takeIf { it.isNotBlank() }?.let { qn ->
            canvas.drawText("Ref Quote: $qn", MARGIN_X + CONTENT_WIDTH, y + 11f, rightPaint)
        }
        y += 22f

        // === SERVICE LINE ITEMS ===
        canvas.drawRoundRect(RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 24f), 8f, 8f, headerBgPaint)
        canvas.drawText("Description / SAC 998313", MARGIN_X + 10f, y + 16f, boldBodyPaint)
        canvas.drawText("Amount", MARGIN_X + CONTENT_WIDTH - 10f, y + 16f, rightBoldPaint)
        y += 30f

        val lines = quotation?.lines.orEmpty()
        if (lines.isNotEmpty()) {
            lines.forEach { line ->
                val lineTitle = buildStaticLayout("${line.title} (Qty: ${line.quantity})", bodyPaint, 340)
                val rowH = lineTitle.height + 12f
                if (y + rowH > PAGE_HEIGHT - MARGIN_BOTTOM - 20f) startInvoiceNewPage()
                drawStaticLayout(canvas, lineTitle, MARGIN_X + 8f, y + 4f)
                val amtStr = if (line.separatelyBilled) "Actuals" else MoneyFormatter.formatInr(line.lineTotalPaise)
                canvas.drawText(amtStr, MARGIN_X + CONTENT_WIDTH - 8f, y + 14f, rightPaint)
                y += rowH
                canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, dividerPaint)
                y += 4f
            }
        } else {
            val basePaise = if (gstEnabled && gstPercent > 0) {
                (project.agreedValuePaise * 100L) / (100L + gstPercent)
            } else project.agreedValuePaise
            canvas.drawText("${project.title} — Agency Deliverables", MARGIN_X + 8f, y + 14f, bodyPaint)
            canvas.drawText(MoneyFormatter.formatInr(basePaise), MARGIN_X + CONTENT_WIDTH - 8f, y + 14f, rightPaint)
            y += 24f
            canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, dividerPaint)
            y += 6f
        }

        // === BILLABLE HOURS SUMMARY ===
        val projEntries = timeEntries.filter { it.projectId == project.id && it.billable }
        if (projEntries.isNotEmpty()) {
            val totalMins = projEntries.sumOf { it.durationMinutes }
            val hoursLabel = String.format(java.util.Locale.US, "%.1f hrs logged (%d entries)", totalMins / 60f, projEntries.size)
            if (y + 28f > PAGE_HEIGHT - MARGIN_BOTTOM) startInvoiceNewPage()
            canvas.drawText("Tracked Billable Effort: $hoursLabel", MARGIN_X + 8f, y + 12f, mutedPaint)
            y += 20f
        }

        // === TAX & PAYMENT SUMMARY ===
        val invSummary = computeTaxInvoiceSummary(project, quotation, company)
        val grossPaise = invSummary.grossPaise
        val taxablePaise = invSummary.taxablePaise
        val cgstPaise = invSummary.cgstPaise
        val sgstPaise = invSummary.sgstPaise
        val totalTaxPaise = cgstPaise + sgstPaise
        val paidPaise = invSummary.paidPaise
        val balancePaise = invSummary.balanceDuePaise

        val taxRows = mutableListOf<Pair<String, String>>()
        taxRows += "Taxable Value" to MoneyFormatter.formatInr(taxablePaise)
        if (gstEnabled && totalTaxPaise > 0L) {
            val halfRate = gstPercent / 2.0
            taxRows += "CGST (${String.format(java.util.Locale.US, "%.1f", halfRate)}%)" to MoneyFormatter.formatInr(cgstPaise)
            taxRows += "SGST (${String.format(java.util.Locale.US, "%.1f", halfRate)}%)" to MoneyFormatter.formatInr(sgstPaise)
        }
        val boxH = taxRows.size * 18f + 78f
        if (y + boxH > PAGE_HEIGHT - MARGIN_BOTTOM) startInvoiceNewPage()
        y += 8f
        val totalsW = 265f
        val totalsX = MARGIN_X + CONTENT_WIDTH - totalsW
        canvas.drawRoundRect(RectF(totalsX, y, MARGIN_X + CONTENT_WIDTH, y + boxH), 8f, 8f, cardBgPaint)
        var ty = y + 18f
        taxRows.forEach { (k, v) ->
            canvas.drawText(k, totalsX + 12f, ty, mutedPaint)
            canvas.drawText(v, MARGIN_X + CONTENT_WIDTH - 12f, ty, rightPaint)
            ty += 18f
        }
        canvas.drawLine(totalsX + 10f, ty - 8f, MARGIN_X + CONTENT_WIDTH - 10f, ty - 8f, dividerPaint)
        ty += 8f
        canvas.drawText("INVOICE TOTAL", totalsX + 12f, ty, makeTextPaint(10.5f, Color.rgb(79, 70, 229), bold = true))
        canvas.drawText(MoneyFormatter.formatInr(grossPaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, rightBoldPaint)
        ty += 18f
        canvas.drawText("Amount Received", totalsX + 12f, ty, bodyPaint)
        canvas.drawText(MoneyFormatter.formatInr(paidPaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, rightPaint)
        ty += 17f
        canvas.drawText("BALANCE DUE", totalsX + 12f, ty, boldBodyPaint)
        canvas.drawText(MoneyFormatter.formatInr(balancePaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, rightBoldPaint)
        y += boxH + 14f

        // === PAYMENT RECEIPTS LEDGER ===
        val projPayments = payments.filter { it.projectId == project.id }.sortedBy { it.paymentDateMillis }
        if (projPayments.isNotEmpty()) {
            if (y + 40f > PAGE_HEIGHT - MARGIN_BOTTOM) startInvoiceNewPage()
            canvas.drawText("Payment Receipts Ledger", MARGIN_X, y + 10f, h2Paint)
            y += 18f
            projPayments.forEach { p ->
                if (y + 18f > PAGE_HEIGHT - MARGIN_BOTTOM) startInvoiceNewPage()
                val refLabel = if (p.reference.isNotBlank()) " • Ref ${p.reference}" else ""
                canvas.drawText(
                    "${MoneyFormatter.formatDate(p.paymentDateMillis)} • ${p.method}$refLabel",
                    MARGIN_X + 4f,
                    y + 10f,
                    mutedPaint
                )
                canvas.drawText(MoneyFormatter.formatInr(p.amountPaise), MARGIN_X + CONTENT_WIDTH - 4f, y + 10f, rightPaint)
                y += 16f
            }
        }

        drawInvoiceFooter()
        doc.finishPage(page)
        FileOutputStream(outFile).use { fos -> doc.writeTo(fos) }
        doc.close()

        return GeneratedPdfResult(
            file = outFile,
            sha256 = sha256Hex(outFile)
        )
    }

    fun buildInvoiceShareIntent(context: Context, file: File, project: Project): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Tax Invoice - ${project.title} (${project.clientCompanyName})")
            putExtra(
                Intent.EXTRA_TEXT,
                "Please find attached the Tax Invoice for ${project.title}. Balance due: ${MoneyFormatter.formatInr((project.agreedValuePaise - project.paidPaise).coerceAtLeast(0L))}."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launchInvoiceShareSheet(context: Context, file: File, project: Project) {
        val intent = buildInvoiceShareIntent(context, file, project)
        val chooser = Intent.createChooser(intent, "Share Tax Invoice PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    internal data class PdfTicketPalette(
        val startColor: Int,
        val endColor: Int,
        val borderColor: Int,
        val emblemBg: Int,
        val emblemTextColor: Int
    )

    internal fun ticketPaletteForIndex(index: Int): PdfTicketPalette = when (index % 4) {
        0 -> PdfTicketPalette(
            startColor = Color.rgb(191, 224, 254),
            endColor = Color.rgb(229, 243, 255),
            borderColor = Color.rgb(171, 210, 252),
            emblemBg = Color.rgb(0, 103, 197),
            emblemTextColor = Color.WHITE
        )
        1 -> PdfTicketPalette(
            startColor = Color.rgb(206, 230, 244),
            endColor = Color.rgb(253, 243, 196),
            borderColor = Color.rgb(230, 230, 198),
            emblemBg = Color.WHITE,
            emblemTextColor = Color.rgb(13, 17, 25)
        )
        2 -> PdfTicketPalette(
            startColor = Color.rgb(251, 213, 197),
            endColor = Color.rgb(253, 242, 218),
            borderColor = Color.rgb(246, 200, 181),
            emblemBg = Color.rgb(241, 90, 27),
            emblemTextColor = Color.WHITE
        )
        else -> PdfTicketPalette(
            startColor = Color.rgb(200, 243, 220),
            endColor = Color.rgb(237, 252, 243),
            borderColor = Color.rgb(179, 232, 203),
            emblemBg = Color.rgb(22, 163, 74),
            emblemTextColor = Color.WHITE
        )
    }

    private fun buildTicketPath(rect: RectF, cornerRadius: Float, notchRadius: Float): Path {
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val cr = cornerRadius
        val nr = notchRadius
        val cy = rect.centerY()

        return Path().apply {
            moveTo(l + cr, t)
            lineTo(r - cr, t)
            quadTo(r, t, r, t + cr)
            lineTo(r, b - cr)
            quadTo(r, b, r - cr, b)
            lineTo(l + cr, b)
            quadTo(l, b, l, b - cr)
            lineTo(l, cy + nr)
            arcTo(
                RectF(l - nr, cy - nr, l + nr, cy + nr),
                90f,
                -180f,
                false
            )
            lineTo(l, t + cr)
            quadTo(l, t, l + cr, t)
            close()
        }
    }

    internal fun ticketMonogram(category: String, title: String): String {
        val text = "$category $title".lowercase()
        return when {
            text.contains("brand") || text.contains("logo") || text.contains("design") -> "BD"
            text.contains("web") || text.contains("site") || text.contains("app") -> "WB"
            text.contains("video") || text.contains("reel") || text.contains("motion") -> "VD"
            text.contains("seo") || text.contains("analytics") -> "SE"
            text.contains("social") || text.contains("insta") -> "SM"
            text.contains("ads") || text.contains("market") || text.contains("campaign") -> "MK"
            text.contains("launch") || text.contains("package") -> "PK"
            text.contains("extra") || text.contains("add") -> "AD"
            else -> "SR"
        }
    }

    private fun drawItemsTableHeader(
        canvas: Canvas,
        y: Float,
        leftBold: TextPaint,
        rightBold: TextPaint
    ): Float {
        canvas.drawRoundRect(RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 24f), 8f, 8f, headerBgPaint)
        canvas.drawText("Catalogue Services & Pricing", MARGIN_X + 12f, y + 16f, leftBold)
        canvas.drawText("Amount", COL_AMT_RIGHT - 12f, y + 16f, rightBold)
        return y + 30f
    }

    private fun drawPageFooter(canvas: Canvas, quotation: Quotation, pageNumber: Int, mutedPaint: TextPaint) {
        val footerY = PAGE_HEIGHT - 22f
        canvas.drawLine(MARGIN_X, footerY - 10f, MARGIN_X + CONTENT_WIDTH, footerY - 10f, dividerPaint)
        canvas.drawText("${quotation.companySnapshot.name} • ${quotation.quoteNumber} v${quotation.revision}", MARGIN_X, footerY, mutedPaint)
        val rightMuted = TextPaint(mutedPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("Page $pageNumber", MARGIN_X + CONTENT_WIDTH, footerY, rightMuted)
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(40))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.05f)
            .setIncludePad(false)
            .build()
    }

    private fun drawStaticLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
