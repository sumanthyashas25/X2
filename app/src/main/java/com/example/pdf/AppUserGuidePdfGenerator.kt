package com.example.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

object AppUserGuidePdfGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f
    private const val MARGIN_TOP = 40f
    private const val MARGIN_BOTTOM = 50f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 80f

    fun buildGuidePdfFile(context: Context): File {
        val dir = File(context.filesDir, "documents").apply { mkdirs() }
        val file = File(dir, "X_MAX_Agency_User_Guide.pdf")

        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN_TOP

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            color = Color.rgb(79, 70, 229)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val h1Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            color = Color.rgb(15, 23, 42)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val h2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = Color.rgb(79, 70, 229)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f
            color = Color.rgb(30, 41, 59)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f
            color = Color.rgb(100, 116, 139)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(209, 213, 226)
            strokeWidth = 0.8f
        }

        fun checkPageBreak(requiredHeight: Float) {
            if (y + requiredHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                // Footer
                canvas.drawText("X.MAX Agency Workspace • User Guide (Page $pageNumber)", MARGIN_X, PAGE_HEIGHT - 25f, mutedPaint)
                doc.finishPage(page)
                pageNumber++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = MARGIN_TOP
            }
        }

        fun drawParagraph(text: String, paint: TextPaint = bodyPaint, spacingAfter: Float = 8f) {
            val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1f, 1.15f)
                .build()
            checkPageBreak(staticLayout.height.toFloat() + spacingAfter)
            canvas.save()
            canvas.translate(MARGIN_X, y)
            staticLayout.draw(canvas)
            canvas.restore()
            y += staticLayout.height + spacingAfter
        }

        // Title
        canvas.drawText("X.MAX Workspace • Comprehensive User Guide", MARGIN_X, y + 16f, titlePaint)
        y += 28f
        canvas.drawText("Professional Agency Operations, Quotations, Chat & Real-Time WebRTC Calls", MARGIN_X, y, mutedPaint)
        y += 16f
        canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, dividerPaint)
        y += 14f

        // Section 1
        checkPageBreak(30f)
        canvas.drawText("1. Getting Started & Super Admin Setup", MARGIN_X, y + 12f, h1Paint)
        y += 22f
        drawParagraph("Welcome to X.MAX, an enterprise-grade digital marketing and creative agency workspace. Upon first launch, sign in securely using Google Sign-In via Credential Manager. The primary bootstrap email (xdotmax@gmail.com) automatically receives Super Admin privileges, unlocking governance, team management, billing controls, and company-wide oversight.")

        // Section 2
        checkPageBreak(30f)
        canvas.drawText("2. Catalogue & Quotation Builder System", MARGIN_X, y + 12f, h1Paint)
        y += 22f
        drawParagraph("The Quotation and Catalogue engine allows staff to draft professional client estimates with automatic pricing calculations, GST integration, advance percentage rules, and revision tracking.")
        drawParagraph("• Catalogue Items: Browse pre-configured packages (e.g., Business Launch, Performance Ads, Brand Identity) and individual add-ons.")
        drawParagraph("• PDF Export & Sharing: Instantly generate publication-grade PDF quotations with secure file hashing and share them directly with clients.")

        // Section 3
        checkPageBreak(30f)
        canvas.drawText("3. Real-Time Chat & Advanced Messaging", MARGIN_X, y + 12f, h1Paint)
        y += 22f
        drawParagraph("X.MAX features real-time Firestore-backed team communication supporting 1:1 direct messages, department channels, and project-linked war rooms.")
        drawParagraph("• Rich Attachments: Share images, documents, voice notes, and quotations seamlessly.")
        drawParagraph("• Ephemeral Presence: Live typing indicators and read receipts keep communication responsive.")

        // Section 4
        checkPageBreak(30f)
        canvas.drawText("4. Real-Time WebRTC Voice & Video Calls", MARGIN_X, y + 12f, h1Paint)
        y += 22f
        drawParagraph("Powered by Firebase Realtime Database signaling and WebRTC, X.MAX provides crystal-clear audio and HD video calls between team members.")
        drawParagraph("• Call Lifecycle: Handled via robust CallState (Incoming, Connecting, Active, Disconnected) with real telecom ringtones and ringback tones.")
        drawParagraph("• Hardware Controls: Toggle microphone mute, front/rear camera flip, speakerphone audio routing, and picture-in-picture mode.")

        // Section 5
        checkPageBreak(30f)
        canvas.drawText("5. Project Management & Notifications", MARGIN_X, y + 12f, h1Paint)
        y += 22f
        drawParagraph("• Project Workflows: Track deliverables, milestones, time entries, and client approvals in real time.")
        drawParagraph("• Notification System: Receive instant alerts for task assignments, payments, and approvals. Notifications automatically mark as read upon opening.")

        // Final Footer
        canvas.drawText("X.MAX Agency Workspace • User Guide (Page $pageNumber)", MARGIN_X, PAGE_HEIGHT - 25f, mutedPaint)
        doc.finishPage(page)

        FileOutputStream(file).use { out ->
            doc.writeTo(out)
        }
        doc.close()
        return file
    }
}
