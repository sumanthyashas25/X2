package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.chat.remote.WorkerBridgeClient
import com.example.data.ClientRepository
import com.example.data.FirebaseStartup
import com.example.data.FirestoreInitializationService
import com.example.data.NetworkConnectivityMonitor
import com.example.domain.WorkDistributionEngine
import com.example.model.AccessLevel
import com.example.model.Client
import com.example.model.ClientSnapshot
import com.example.model.InvoiceRecord
import com.example.model.InvoiceStatus
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.Project
import com.example.model.ProjectStatus
import com.example.ui.AgencyUiState
import com.example.ui.screens.ClientsScreen
import com.example.ui.screens.PaymentsScreen
import com.example.ui.theme.XMaxTheme
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentClientR2AppCheckTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Client model stores contact details and computes active project count`() {
        val client = Client(
            id = "cli_alpha",
            companyName = "Alpha Retail Pvt Ltd",
            contactPerson = "Meera Nair",
            phone = "+91 9876543210",
            email = "meera@alpharetail.in",
            address = "Indiranagar, Bengaluru",
            activeProjectCount = 2
        )

        val projects = listOf(
            Project(
                id = "proj_1",
                clientId = "cli_alpha",
                clientCompanyName = "Alpha Retail Pvt Ltd",
                title = "Brand Film",
                status = ProjectStatus.IN_PROGRESS.name,
                deleted = false
            ),
            Project(
                id = "proj_2",
                clientId = "",
                clientCompanyName = "alpha retail pvt ltd",
                title = "Social Retainer",
                status = ProjectStatus.AWAITING_ADVANCE.name,
                deleted = false
            ),
            Project(
                id = "proj_3",
                clientId = "cli_alpha",
                clientCompanyName = "Alpha Retail Pvt Ltd",
                title = "Website Launch",
                status = ProjectStatus.COMPLETED.name,
                deleted = false
            ),
            Project(
                id = "proj_4",
                clientId = "cli_alpha",
                clientCompanyName = "Alpha Retail Pvt Ltd",
                title = "Archived Shoot",
                status = ProjectStatus.IN_PROGRESS.name,
                deleted = true
            )
        )

        assertEquals("Alpha Retail Pvt Ltd", client.companyName)
        assertEquals("+91 9876543210", client.phone)
        assertEquals("meera@alpharetail.in", client.email)
        assertEquals(2, ClientRepository.countActiveProjectsForClient(client, projects))
    }

    @Test
    fun `WorkDistributionEngine calculates completed project revenue and paid vs pending invoices`() {
        val projects = listOf(
            Project(
                id = "p_done_1",
                title = "E-Commerce Launch",
                agreedValuePaise = 8500000L, // ₹85,000
                paidPaise = 8500000L,
                status = ProjectStatus.COMPLETED.name,
                deleted = false
            ),
            Project(
                id = "p_done_2",
                title = "Product Shoot",
                agreedValuePaise = 4000000L, // ₹40,000
                paidPaise = 3000000L,
                status = ProjectStatus.COMPLETED.name,
                deleted = false
            ),
            Project(
                id = "p_active",
                title = "Ongoing Ads",
                agreedValuePaise = 6000000L,
                paidPaise = 2000000L,
                status = ProjectStatus.IN_PROGRESS.name,
                deleted = false
            ),
            Project(
                id = "p_deleted",
                title = "Deleted Done Project",
                agreedValuePaise = 5000000L,
                paidPaise = 5000000L,
                status = ProjectStatus.COMPLETED.name,
                deleted = true
            )
        )

        val invoices = listOf(
            InvoiceRecord(
                id = "inv_1",
                invoiceNumber = "INV-2026-0001",
                projectId = "p_done_1",
                totalPaise = 8500000L,
                balanceDuePaise = 0L,
                status = InvoiceStatus.PAID.name
            ),
            InvoiceRecord(
                id = "inv_2",
                invoiceNumber = "INV-2026-0002",
                projectId = "p_active",
                totalPaise = 3000000L,
                balanceDuePaise = 3000000L,
                status = InvoiceStatus.ISSUED.name
            ),
            InvoiceRecord(
                id = "inv_3",
                invoiceNumber = "INV-2026-0003",
                projectId = "p_done_2",
                totalPaise = 1000000L,
                balanceDuePaise = 1000000L,
                status = InvoiceStatus.OVERDUE.name
            ),
            InvoiceRecord(
                id = "inv_4",
                invoiceNumber = "INV-2026-0004",
                projectId = "p_active",
                totalPaise = 500000L,
                balanceDuePaise = 0L,
                status = InvoiceStatus.CANCELLED.name
            )
        )

        val summary = WorkDistributionEngine.computeRevenueAndInvoiceSummary(projects, invoices)
        assertEquals(2, summary.completedProjectsCount)
        assertEquals(12500000L, summary.completedProjectsRevenuePaise)
        assertEquals(11500000L, summary.completedProjectsCollectedPaise)
        assertEquals(3, summary.totalInvoicesCount)
        assertEquals(1, summary.paidInvoicesCount)
        assertEquals(8500000L, summary.paidInvoicesTotalPaise)
        assertEquals(2, summary.pendingInvoicesCount)
        assertEquals(4000000L, summary.pendingInvoicesTotalPaise)
    }

    @Test
    fun `Cloudflare R2 Free Tier bridge parses health, upload urls and provider payload`() {
        val healthJson = JSONObject(
            """
            {
              "ok": true,
              "plan": "free",
              "storageProvider": "CLOUDFLARE_R2",
              "firebaseProjectId": "gen-lang-client-0411475132",
              "firestoreDbId": "ai-studio-android-x2-f008d1bb-6c44-4fa2-9fd3-87ddefd4d583",
              "r2Configured": true,
              "supabaseConfigured": false,
              "fcmConfigured": false,
              "workerVersion": "2026.11.27"
            }
            """.trimIndent()
        )
        val health = WorkerBridgeClient.parseHealthResponse(healthJson)
        assertTrue(health.ok)
        assertEquals("free", health.plan)
        assertEquals("CLOUDFLARE_R2", health.storageProvider)
        assertTrue(health.r2Configured)

        val uploadUrl = WorkerBridgeClient.buildUploadUrl(
            base = "https://xmax-chat.example.workers.dev/",
            companyId = "xmax",
            chatId = "ch_general",
            bucket = "chat-media",
            fileName = "frame.jpg",
            sha256 = "abc123"
        )
        assertNotNull(uploadUrl)
        assertTrue(uploadUrl!!.contains("/v1/storage/upload"))
        assertTrue(uploadUrl.contains("bucket=chat-media"))

        val uploadParsed = WorkerBridgeClient.parseUploadResponse(
            JSONObject(
                """
                {
                  "provider": "CLOUDFLARE_R2",
                  "bucket": "chat-media",
                  "storagePath": "xmax/ch_general/abc123.jpg",
                  "byteSize": 20480,
                  "mimeType": "image/jpeg",
                  "sha256": "abc123"
                }
                """.trimIndent()
            ),
            fallbackBucket = "chat-media"
        )
        assertEquals("CLOUDFLARE_R2", uploadParsed.provider)
        assertEquals("xmax/ch_general/abc123.jpg", uploadParsed.storagePath)
        assertEquals(20480L, uploadParsed.byteSize)
    }

    @Test
    fun `Firebase App Check Play Integrity factory and Firestore offline persistence are configured`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = FirebaseStartup.inspectConfig(context)
        assertEquals(FirebaseStartup.EXPECTED_PROJECT_ID, config.projectId)
        assertEquals(FirebaseStartup.EXPECTED_DATABASE_ID, config.databaseId)
        assertTrue(config.isHealthy)

        // PlayIntegrityAppCheckProviderFactory is compiled & accessible
        assertNotNull(PlayIntegrityAppCheckProviderFactory.getInstance())
        assertEquals(50L * 1024L * 1024L, FirestoreInitializationService.SPARK_FREE_CACHE_BYTES)
        assertEquals(
            FirebaseStartup.EXPECTED_DATABASE_ID,
            FirestoreInitializationService.getInstance(context).resolveDatabaseId()
        )
        assertNotNull(NetworkConnectivityMonitor.isOnline)
        assertNotNull(NetworkConnectivityMonitor.isSyncingPendingWrites)
        assertNotNull(NetworkConnectivityMonitor.lastSyncedAtMillis)
    }

    @Test
    fun `ClientsScreen displays activeProjectCount badge and PaymentsScreen renders Completed Revenue & Paid-Pending Invoices`() {
        val superMember = Member(
            id = "uid_super",
            uid = "uid_super",
            fullName = "X.MAX Director",
            accessLevel = AccessLevel.SUPER_ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true
        )
        val client = Client(
            id = "cli_nova",
            companyName = "Nova Cosmetics",
            contactPerson = "Ananya Roy",
            phone = "+91 9822001122",
            email = "ananya@nova.in",
            activeProjectCount = 2
        )
        val completedProj = Project(
            id = "proj_done",
            clientId = "cli_nova",
            clientCompanyName = "Nova Cosmetics",
            title = "Diwali Launch Reel",
            agreedValuePaise = 5000000L,
            paidPaise = 5000000L,
            status = ProjectStatus.COMPLETED.name
        )
        val activeProj = Project(
            id = "proj_live",
            clientId = "cli_nova",
            clientCompanyName = "Nova Cosmetics",
            title = "Winter Ad Retainer",
            agreedValuePaise = 3000000L,
            paidPaise = 1000000L,
            status = ProjectStatus.IN_PROGRESS.name
        )
        val paidInv = InvoiceRecord(
            id = "inv_paid",
            invoiceNumber = "INV-2026-1001",
            projectId = "proj_done",
            projectTitle = "Diwali Launch Reel",
            clientSnapshot = ClientSnapshot(companyName = "Nova Cosmetics"),
            totalPaise = 5000000L,
            balanceDuePaise = 0L,
            status = InvoiceStatus.PAID.name
        )
        val pendingInv = InvoiceRecord(
            id = "inv_pending",
            invoiceNumber = "INV-2026-1002",
            projectId = "proj_live",
            projectTitle = "Winter Ad Retainer",
            clientSnapshot = ClientSnapshot(companyName = "Nova Cosmetics"),
            totalPaise = 2000000L,
            balanceDuePaise = 2000000L,
            status = InvoiceStatus.ISSUED.name
        )

        val state = AgencyUiState(
            myMember = superMember,
            members = listOf(superMember),
            clients = listOf(client),
            projects = listOf(completedProj, activeProj),
            invoices = listOf(paidInv, pendingInv)
        )

        var toggledInvoicePaid: Pair<String, Boolean>? = null
        composeRule.setContent {
            XMaxTheme(darkTheme = false) {
                PaymentsScreen(
                    state = state,
                    onRecordPayment = { _, _, _, _, _, _ -> },
                    onToggleInvoicePaid = { inv, paid -> toggledInvoicePaid = inv.id to paid }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("payments_lazy_list")
            .performScrollToNode(hasTestTag("completed_revenue_invoice_summary_card"))
        composeRule.onNodeWithText("Completed Revenue & Invoices").assertIsDisplayed()

        composeRule.onNodeWithTag("payments_lazy_list")
            .performScrollToNode(hasTestTag("invoice_card_inv_pending"))
        composeRule.onNodeWithText("INV-2026-1002").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_invoice_status_inv_pending").performClick()
        assertEquals("inv_pending" to true, toggledInvoicePaid)
    }

    @Test
    fun `ClientsScreen shows client activeProjectCount badge`() {
        val superMember = Member(
            id = "uid_super",
            uid = "uid_super",
            fullName = "X.MAX Director",
            accessLevel = AccessLevel.SUPER_ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true
        )
        val client = Client(
            id = "cli_nova",
            companyName = "Nova Cosmetics",
            contactPerson = "Ananya Roy",
            phone = "+91 9822001122",
            activeProjectCount = 3
        )
        val state = AgencyUiState(
            myMember = superMember,
            members = listOf(superMember),
            clients = listOf(client)
        )
        composeRule.setContent {
            XMaxTheme(darkTheme = false) {
                ClientsScreen(state = state, onSaveClient = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 Active").assertIsDisplayed()
    }
}
