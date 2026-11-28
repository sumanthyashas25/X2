package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AgencyRepository
import com.example.data.ClientRepository
import com.example.data.FirebaseStartup
import com.example.data.FirestoreInitState
import com.example.data.FirestoreInitializationService
import com.example.data.ProjectRepository
import com.example.data.QuotationRepository
import com.example.model.Client
import com.example.model.CompanyProfile
import com.example.model.Member
import com.example.model.Project
import com.example.model.ProjectRequest
import com.example.model.Quotation
import com.example.ui.AgencyViewModel
import com.example.ui.UserViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirestoreInitAndRepoTest {

    private lateinit var appContext: Application
    private lateinit var initService: FirestoreInitializationService
    private lateinit var agencyRepo: AgencyRepository
    private lateinit var clientRepo: ClientRepository
    private lateinit var projectRepo: ProjectRepository
    private lateinit var quotationRepo: QuotationRepository

    private val dummyActor = Member(
        id = "u_actor",
        uid = "u_actor",
        fullName = "Test Director"
    )

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        FirebaseStartup.ensureInitialized(appContext)
        FirebaseAuth.getInstance().signOut()
        initService = FirestoreInitializationService.getInstance(appContext)
        initService.onSignedOut()
        agencyRepo = AgencyRepository(appContext)
        clientRepo = ClientRepository(agencyRepo)
        projectRepo = ProjectRepository(agencyRepo)
        quotationRepo = QuotationRepository(agencyRepo)
    }

    @Test
    fun `firestore initialization service stays uninitialized before authentication and targets free named db`() = runBlocking {
        assertEquals(FirestoreInitState.AwaitingAuthentication, initService.initState.value)
        assertFalse(initService.isReady)
        assertNull(initService.currentReadyFirestore())
        assertFalse(initService.isReadyForUser("uid_123"))
        assertFalse(initService.isReadyForUser(null))
        assertFalse(initService.isReadyForUser(""))

        assertEquals(
            "ai-studio-android-x2-f008d1bb-6c44-4fa2-9fd3-87ddefd4d583",
            initService.resolveDatabaseId()
        )
        assertEquals(50L * 1024L * 1024L, FirestoreInitializationService.SPARK_FREE_CACHE_BYTES)

        val dbAccessError = runCatching { initService.requireFirestore() }.exceptionOrNull()
        assertTrue(dbAccessError is IllegalStateException)

        val retryResult = initService.retryInitialization(null)
        assertTrue(retryResult.isFailure)
        assertEquals(FirestoreInitState.AwaitingAuthentication, initService.initState.value)
    }

    @Test
    fun `constructing repositories and viewmodels does not eagerly initialize firestore while signed out`() {
        val userVm = UserViewModel(appContext)
        val agencyVm = AgencyViewModel(appContext)

        assertEquals(FirestoreInitState.AwaitingAuthentication, initService.initState.value)
        assertFalse(initService.isReady)

        val authErr = runCatching { agencyRepo.requireAuthenticatedUserId() }.exceptionOrNull()
        assertTrue(authErr is IllegalStateException)

        val dbErr = runCatching { agencyRepo.db }.exceptionOrNull()
        assertTrue(dbErr is IllegalStateException)

        // Ensure ViewModels start in unauthenticated/signed-out states cleanly without touching Firestore
        assertTrue(userVm.userProfileState.value is com.example.ui.UserProfileState)
        assertEquals(com.example.data.AuthSessionStatus.Initializing, agencyVm.uiState.value.authSessionStatus)
    }

    @Test
    fun `client repository validates inputs and blocks unauthenticated reads and writes`() = runBlocking {
        assertTrue(clientRepo.getClient("").isFailure)
        assertTrue(clientRepo.getClient("cli_1").isFailure)

        assertTrue(clientRepo.saveClient(Client(companyName = "", contactPerson = "Asha"), dummyActor).isFailure)
        assertTrue(
            clientRepo.saveClient(
                Client(companyName = "A".repeat(151), contactPerson = "Asha"),
                dummyActor
            ).isFailure
        )
        assertTrue(clientRepo.saveClient(Client(companyName = "Acme", contactPerson = ""), dummyActor).isFailure)
        assertTrue(
            clientRepo.saveClient(
                Client(companyName = "Acme", contactPerson = "B".repeat(121)),
                dummyActor
            ).isFailure
        )
        assertTrue(
            clientRepo.saveClient(
                Client(companyName = "Acme", contactPerson = "Asha"),
                dummyActor
            ).isFailure
        )

        assertTrue(clientRepo.deleteClient("", "Acme", dummyActor).isFailure)
        assertTrue(clientRepo.deleteClient("cli_1", "Acme", dummyActor).isFailure)
    }

    @Test
    fun `project repository validates inputs and blocks unauthenticated reads and writes`() = runBlocking {
        assertTrue(projectRepo.getProject("").isFailure)
        assertTrue(projectRepo.getProject("proj_1").isFailure)

        val validClient = Client(id = "cli_1", companyName = "Acme Co", contactPerson = "Asha")
        assertTrue(
            projectRepo.createRequest(
                client = Client(id = "", companyName = ""),
                projectTitle = "Launch",
                brief = "Brief",
                desiredServices = emptyList(),
                targetDateMillis = 0L,
                responsibleManager = dummyActor,
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            projectRepo.createRequest(
                client = validClient,
                projectTitle = "   ",
                brief = "Brief",
                desiredServices = emptyList(),
                targetDateMillis = 0L,
                responsibleManager = dummyActor,
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            projectRepo.createRequest(
                client = validClient,
                projectTitle = "T".repeat(161),
                brief = "Brief",
                desiredServices = emptyList(),
                targetDateMillis = 0L,
                responsibleManager = dummyActor,
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            projectRepo.createRequest(
                client = validClient,
                projectTitle = "Launch",
                brief = "B".repeat(4001),
                desiredServices = emptyList(),
                targetDateMillis = 0L,
                responsibleManager = dummyActor,
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            projectRepo.createRequest(
                client = validClient,
                projectTitle = "Launch",
                brief = "Brief",
                desiredServices = listOf("Branding"),
                targetDateMillis = 0L,
                responsibleManager = dummyActor,
                actor = dummyActor
            ).isFailure
        )

        assertTrue(
            projectRepo.acceptRequestAndCreateProject(
                request = ProjectRequest(id = ""),
                approvedQuote = Quotation(id = "qt_1"),
                deliverableDrafts = emptyList(),
                company = CompanyProfile(),
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            projectRepo.acceptRequestAndCreateProject(
                request = ProjectRequest(id = "req_1", clientId = "cli_1", clientCompanyName = "Acme", projectTitle = "Launch"),
                approvedQuote = Quotation(id = "qt_1"),
                deliverableDrafts = emptyList(),
                company = CompanyProfile(),
                actor = dummyActor
            ).isFailure
        )

        assertTrue(projectRepo.declineRequest(ProjectRequest(id = ""), "No", dummyActor).isFailure)
        assertTrue(projectRepo.declineRequest(ProjectRequest(id = "req_1"), "No", dummyActor).isFailure)
        assertTrue(projectRepo.setProjectDeleted(Project(id = ""), true, dummyActor).isFailure)
        assertTrue(projectRepo.setProjectDeleted(Project(id = "proj_1"), true, dummyActor).isFailure)
        assertTrue(projectRepo.deleteProjectCascade(Project(id = ""), dummyActor).isFailure)
        assertTrue(projectRepo.deleteProjectCascade(Project(id = "proj_1"), dummyActor).isFailure)
    }

    @Test
    fun `quotation repository validates inputs and blocks unauthenticated reads and writes`() = runBlocking {
        assertTrue(quotationRepo.getQuotation("").isFailure)
        assertTrue(quotationRepo.getQuotation("qt_1").isFailure)

        assertTrue(
            quotationRepo.saveOrIssueQuotation(
                draft = Quotation(requestId = "", clientId = "cli_1", projectTitle = "Quote"),
                company = CompanyProfile(),
                issueNow = false,
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            quotationRepo.saveOrIssueQuotation(
                draft = Quotation(requestId = "req_1", clientId = "cli_1", projectTitle = ""),
                company = CompanyProfile(),
                issueNow = false,
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            quotationRepo.saveOrIssueQuotation(
                draft = Quotation(requestId = "req_1", clientId = "cli_1", projectTitle = "Q".repeat(161)),
                company = CompanyProfile(),
                issueNow = false,
                actor = dummyActor
            ).isFailure
        )
        assertTrue(
            quotationRepo.saveOrIssueQuotation(
                draft = Quotation(requestId = "req_1", clientId = "cli_1", projectTitle = "Valid Quote"),
                company = CompanyProfile(),
                issueNow = true,
                actor = dummyActor
            ).isFailure
        )

        assertTrue(quotationRepo.createQuotationRevision(Quotation(id = ""), dummyActor).isFailure)
        assertTrue(quotationRepo.createQuotationRevision(Quotation(id = "qt_1", requestId = "req_1"), dummyActor).isFailure)
        assertTrue(quotationRepo.savePdfMetadata("", "/tmp/a.pdf", "abc").isFailure)
        assertTrue(quotationRepo.savePdfMetadata("qt_1", "/tmp/a.pdf", "abc").isFailure)
        assertTrue(quotationRepo.markQuotationSent(Quotation(id = ""), dummyActor).isFailure)
        assertTrue(quotationRepo.markQuotationSent(Quotation(id = "qt_1"), dummyActor).isFailure)
        assertTrue(quotationRepo.markQuotationClientApproved(Quotation(id = ""), "ok", dummyActor).isFailure)
        assertTrue(quotationRepo.markQuotationClientApproved(Quotation(id = "qt_1"), "ok", dummyActor).isFailure)
        assertTrue(quotationRepo.deleteQuotation("", "QT-1", dummyActor).isFailure)
        assertTrue(quotationRepo.deleteQuotation("qt_1", "QT-1", dummyActor).isFailure)
    }
}
