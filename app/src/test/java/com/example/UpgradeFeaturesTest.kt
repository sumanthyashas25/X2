package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.chat.ChatE2eeCrypto
import com.example.chat.ChatSettingsStore
import com.example.chat.realtime.MemberAvailability
import com.example.chat.realtime.MemberPresenceState
import com.example.chat.realtime.RtdbPresenceManager
import com.example.domain.DeliverableDraft
import com.example.domain.WorkDistributionEngine
import com.example.model.AccessLevel
import com.example.model.AssignmentStatus
import com.example.model.ChecklistItem
import com.example.model.Client
import com.example.model.CompanyProfile
import com.example.model.Deliverable
import com.example.model.DeliverableStatus
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.PaymentMethod
import com.example.model.PaymentRecord
import com.example.model.Project
import com.example.model.ProjectStatus
import com.example.model.Quotation
import com.example.model.TimeEntry
import com.example.pdf.QuotationPdfGenerator
import com.example.system.PaymentReminderAutomator
import com.example.ui.theme.AppAccentColor
import com.example.ui.theme.AppAppearanceStore
import com.example.ui.theme.AppThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeFeaturesTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        val ctx = context()
        AppAppearanceStore.setActiveProfile(ctx, null)
        ChatSettingsStore.setActiveProfile(ctx, null)
        runCatching {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @Test
    fun `chat e2ee roundtrip encrypts and decrypts for sender and peer and computes matching safety number`() {
        val ctx = context()
        val aliceUid = "user_alice_e2ee"
        val bobUid = "user_bob_e2ee"
        val charlieUid = "user_charlie_e2ee"

        val alicePub = ChatE2eeCrypto.getOrCreatePublicKeyBase64(ctx, aliceUid)
        val bobPub = ChatE2eeCrypto.getOrCreatePublicKeyBase64(ctx, bobUid)
        assertTrue(alicePub.isNotBlank())
        assertTrue(bobPub.isNotBlank())

        val secret = "Client approved final ₹1,80,000 retainer!"
        val encrypted = ChatE2eeCrypto.encryptForRecipients(
            context = ctx,
            senderUid = aliceUid,
            plaintext = secret,
            recipientPublicKeysByUid = mapOf(bobUid to bobPub)
        )
        assertNotNull(encrypted)
        encrypted!!

        val decBob = ChatE2eeCrypto.decryptPayload(
            context = ctx,
            myUid = bobUid,
            ciphertextB64 = encrypted.ciphertextBase64,
            ivB64 = encrypted.ivBase64,
            senderPublicKeyB64 = encrypted.senderPublicKeyBase64,
            envelopes = encrypted.envelopes
        )
        val decAlice = ChatE2eeCrypto.decryptPayload(
            context = ctx,
            myUid = aliceUid,
            ciphertextB64 = encrypted.ciphertextBase64,
            ivB64 = encrypted.ivBase64,
            senderPublicKeyB64 = encrypted.senderPublicKeyBase64,
            envelopes = encrypted.envelopes
        )
        val decCharlie = ChatE2eeCrypto.decryptPayload(
            context = ctx,
            myUid = charlieUid,
            ciphertextB64 = encrypted.ciphertextBase64,
            ivB64 = encrypted.ivBase64,
            senderPublicKeyB64 = encrypted.senderPublicKeyBase64,
            envelopes = encrypted.envelopes
        )

        assertEquals(secret, decBob)
        assertEquals(secret, decAlice)
        assertEquals(null, decCharlie)

        val s1 = ChatE2eeCrypto.pairSafetyNumber(alicePub, bobPub)
        val s2 = ChatE2eeCrypto.pairSafetyNumber(bobPub, alicePub)
        assertEquals(s1, s2)
        assertFalse(s1.startsWith("Pending"))
    }

    @Test
    fun `appearance and chat settings stores isolate preferences per profile uid`() {
        val ctx = context()
        val uidA = "profile_alpha_1"
        val uidB = "profile_beta_2"

        AppAppearanceStore.setActiveProfile(ctx, uidA)
        AppAppearanceStore.setThemeMode(ctx, AppThemeMode.DARK)
        AppAppearanceStore.setAccentColor(ctx, AppAccentColor.EMERALD)
        ChatSettingsStore.setActiveProfile(ctx, uidA)
        ChatSettingsStore.setWallpaper(ctx, "forest_dusk")

        AppAppearanceStore.setActiveProfile(ctx, uidB)
        AppAppearanceStore.setThemeMode(ctx, AppThemeMode.LIGHT)
        AppAppearanceStore.setAccentColor(ctx, AppAccentColor.CORAL)
        ChatSettingsStore.setActiveProfile(ctx, uidB)
        ChatSettingsStore.setWallpaper(ctx, "warm_amber")

        // Switch back to uidA and verify uidA settings were NOT overwritten by uidB
        AppAppearanceStore.setActiveProfile(ctx, uidA)
        ChatSettingsStore.setActiveProfile(ctx, uidA)
        assertEquals(AppThemeMode.DARK, AppAppearanceStore.themeModeFlow.value)
        assertEquals(AppAccentColor.EMERALD, AppAppearanceStore.accentColorFlow.value)
        assertEquals("forest_dusk", ChatSettingsStore.prefsFlow.value.globalWallpaperId)

        // Switch to uidB and verify uidB keeps its own theme & wallpaper
        AppAppearanceStore.setActiveProfile(ctx, uidB)
        ChatSettingsStore.setActiveProfile(ctx, uidB)
        assertEquals(AppThemeMode.LIGHT, AppAppearanceStore.themeModeFlow.value)
        assertEquals(AppAccentColor.CORAL, AppAppearanceStore.accentColorFlow.value)
        assertEquals("warm_amber", ChatSettingsStore.prefsFlow.value.globalWallpaperId)
    }

    @Test
    fun `resource allocation coach picks lowest workload matching member and analytics series forecasts revenue`() {
        val now = System.currentTimeMillis()
        val mBusy = Member(
            uid = "u_busy",
            fullName = "Busy Designer",
            accessLevel = AccessLevel.STAFF.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            departmentId = "dept_design",
            jobRoleId = "role_ui"
        )
        val mFree = Member(
            uid = "u_free",
            fullName = "Free Designer",
            accessLevel = AccessLevel.STAFF.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            departmentId = "dept_design",
            jobRoleId = "role_ui"
        )
        val draft = DeliverableDraft(
            draftId = "draft_1",
            title = "Landing Page UI",
            description = "Figma hero & components",
            quantity = 1,
            sourceCategory = "Web",
            departmentId = "dept_design",
            departmentName = "Design",
            jobRoleId = "role_ui",
            jobRoleName = "UI Designer",
            assignedToUid = "",
            assignedToName = "",
            assignmentStatus = AssignmentStatus.DEPT_QUEUE,
            matchingCandidates = listOf(mBusy, mFree),
            checklist = listOf(ChecklistItem("c1", "Wireframes", false))
        )
        val existingDelivs = listOf(
            Deliverable(id = "d1", assignedToUid = mBusy.uid, status = DeliverableStatus.IN_PROGRESS.name),
            Deliverable(id = "d2", assignedToUid = mBusy.uid, status = DeliverableStatus.BLOCKED.name)
        )
        val entries = listOf(
            TimeEntry(id = "t1", projectId = "p1", uid = mBusy.uid, workDateMillis = now - 3600_000L, durationMinutes = 240, billable = true, hourlyRatePaise = 150000L)
        )

        val recs = WorkDistributionEngine.recommendStaffAllocations(
            drafts = listOf(draft),
            deliverables = existingDelivs,
            timeEntries = entries,
            members = listOf(mBusy, mFree),
            nowMillis = now
        )
        assertEquals(1, recs.size)
        assertEquals(mFree.uid, recs.first().recommendedMember?.uid)
        assertTrue(recs.first().matchScore in 30..99)

        val projects = listOf(
            Project(
                id = "p1",
                title = "Brand Refresh",
                clientCompanyName = "Acme Corp",
                agreedValuePaise = 10_000_000L,
                paidPaise = 4_000_000L,
                status = ProjectStatus.IN_PROGRESS.name
            )
        )
        val payments = listOf(
            PaymentRecord(
                id = "pay1",
                projectId = "p1",
                amountPaise = 4_000_000L,
                paymentDateMillis = now,
                method = PaymentMethod.UPI.name
            )
        )
        val series = WorkDistributionEngine.computeAgencyAnalyticsSeries(
            projects = projects,
            deliverables = existingDelivs,
            payments = payments,
            timeEntries = entries,
            nowMillis = now
        )
        assertEquals(6, series.historyPoints.size)
        assertEquals(3, series.forecastPoints.size)
        assertEquals(40, series.collectionRatePercent)
        assertTrue(series.projectedNext3MonthsPaise > 0L)
    }

    @Test
    fun `tax invoice pdf generator produces valid gst summary, target file, and share intent`() {
        val ctx = context()
        val project = Project(
            id = "proj_inv_01",
            title = "Q4 Growth Campaign",
            clientCompanyName = "Nova Retail Pvt Ltd",
            approvedQuoteNumber = "QT-2026-009",
            agreedValuePaise = 11_800_000L,
            paidPaise = 5_900_000L
        )
        val quotation = Quotation(
            id = "q_01",
            quoteNumber = "QT-2026-009",
            gstEnabled = true,
            gstRatePercent = 18,
            subtotalPaise = 10_000_000L,
            gstAmountPaise = 1_800_000L,
            totalPaise = 11_800_000L
        )
        val company = CompanyProfile(
            name = "X.MAX Studio",
            gstin = "27AABCU9603R1ZM",
            gstEnabled = true,
            gstRatePercent = 18
        )
        val summary = QuotationPdfGenerator.computeTaxInvoiceSummary(
            project = project,
            quotation = quotation,
            company = company
        )
        assertEquals("INV-INV_01", summary.invoiceNumber)
        assertTrue(summary.gstEnabled)
        assertEquals(18, summary.gstRatePercent)
        assertEquals(10_000_000L, summary.taxablePaise)
        assertEquals(900_000L, summary.cgstPaise)
        assertEquals(900_000L, summary.sgstPaise)
        assertEquals(5_900_000L, summary.balanceDuePaise)

        val target = QuotationPdfGenerator.buildInvoiceTargetFile(ctx, project)
        target.writeText("%PDF-1.4 invoice")
        assertTrue(target.exists())
        assertTrue(target.absolutePath.contains("invoices/INV_proj_inv_01.pdf"))

        val shareIntent = QuotationPdfGenerator.buildInvoiceShareIntent(ctx, target, project)
        assertEquals(Intent.ACTION_SEND, shareIntent.action)
        assertEquals("application/pdf", shareIntent.type)
        assertTrue(shareIntent.getStringExtra(Intent.EXTRA_SUBJECT)!!.contains("Tax Invoice"))
        assertTrue(shareIntent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("₹59,000"))
    }

    @Test
    fun `presence helper accurately detects online heartbeat vs explicit offline signout`() {
        val now = 1_700_000_000_000L
        val activeMember = Member(
            uid = "u_on",
            availabilityStatus = "ONLINE",
            lastActiveAtMillis = now - 30_000L
        )
        val staleMember = Member(
            uid = "u_stale",
            availabilityStatus = "ONLINE",
            lastActiveAtMillis = now - 10 * 60_000L
        )
        val offlineState = MemberPresenceState(
            uid = "u_on",
            online = false,
            lastSeenMillis = now - 120_000L,
            availability = MemberAvailability.AWAY
        )

        assertTrue(RtdbPresenceManager.isMemberOnlineNow(activeMember, offlineState, now))
        assertFalse(RtdbPresenceManager.isMemberOnlineNow(staleMember, offlineState, now))
        assertEquals(now - 30_000L, RtdbPresenceManager.effectiveLastSeenMillis(activeMember, offlineState))
    }

    @Test
    fun `payment reminder automator composes nudge message and intents for whatsapp and email`() {
        val project = Project(
            id = "proj_ndg",
            title = "App Redesign",
            clientCompanyName = "Zenith Foods",
            agreedValuePaise = 5_000_000L,
            paidPaise = 2_000_000L
        )
        val client = Client(
            id = "cli_ndg",
            companyName = "Zenith Foods",
            contactPerson = "Riya Sharma",
            phone = "+91 9876543210",
            email = "riya@zenith.example"
        )
        val msg = PaymentReminderAutomator.composeClientNudgeMessage(
            project = project,
            client = client,
            amountDuePaise = 3_000_000L,
            portalUrl = "https://portal.xmax.example/p/ptk_demo"
        )
        assertTrue(msg.contains("Riya Sharma"))
        assertTrue(msg.contains("App Redesign"))
        assertTrue(msg.contains("ptk_demo"))

        val waIntent = PaymentReminderAutomator.buildWhatsAppNudgeIntent(client.phone, msg)
        val emailIntent = PaymentReminderAutomator.buildEmailNudgeIntent(client.email, "Balance Due", msg)
        assertTrue(waIntent.data.toString().startsWith("https://wa.me/919876543210"))
        assertTrue(emailIntent.data.toString().startsWith("mailto:riya%40zenith.example"))
    }
}
