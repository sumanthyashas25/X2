package com.example

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.data.AuthSessionStatus
import com.example.data.FirebaseStartup
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.AgencyViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SignInScreen
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.LocalXMaxTypography
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.XMaxTheme
import com.example.ui.theme.XMaxType
import com.example.ui.theme.XMaxTypography
import com.example.ui.theme.tabular
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypographyVerificationTest {

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            FirebaseStartup.ensureInitialized(ctx)
        }
    }).around(composeTestRule)

    @Test
    fun `bundled Inter and JetBrains Mono font resources load offline across all weights 200 to 900`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedInterFonts = listOf(
            R.font.inter_extralight to FontWeight.ExtraLight,
            R.font.inter_light to FontWeight.Light,
            R.font.inter_regular to FontWeight.Normal,
            R.font.inter_medium to FontWeight.Medium,
            R.font.inter_semibold to FontWeight.SemiBold,
            R.font.inter_bold to FontWeight.Bold,
            R.font.inter_extrabold to FontWeight.ExtraBold,
            R.font.inter_black to FontWeight.Black
        )
        assertEquals(8, expectedInterFonts.map { it.first }.toSet().size)

        val loadedTypefaces = expectedInterFonts.map { (resId, weight) ->
            val tf = ResourcesCompat.getFont(context, resId)
            assertNotNull("Expected offline font resource $resId ($weight) to load", tf)
            tf!!
        }

        // Verify Regular (400) and Bold (700) resolve to distinct Typeface instances/resources
        assertNotEquals(R.font.inter_regular, R.font.inter_bold)
        assertNotEquals(loadedTypefaces[2], loadedTypefaces[5])

        val interResourceFonts = (InterFontFamily as FontListFontFamily).fonts.filterIsInstance<ResourceFont>()
        assertEquals(8, interResourceFonts.size)
        expectedInterFonts.forEachIndexed { index, (resId, weight) ->
            assertEquals(resId, interResourceFonts[index].resId)
            assertEquals(weight, interResourceFonts[index].weight)
        }

        val monoFonts = listOf(
            R.font.jetbrains_mono to FontWeight.Normal,
            R.font.jetbrains_mono_medium to FontWeight.Medium,
            R.font.jetbrains_mono_bold to FontWeight.Bold
        )
        monoFonts.forEach { (resId, weight) ->
            assertNotNull("Expected mono font $resId ($weight) to load", ResourcesCompat.getFont(context, resId))
        }
        assertEquals(3, (MonoFontFamily as FontListFontFamily).fonts.filterIsInstance<ResourceFont>().size)
    }

    @Test
    fun `centralized Apple-inspired typography hierarchy and MaterialTheme scale match spec`() {
        assertEquals(34.sp, XMaxType.largeTitle.fontSize)
        assertEquals(FontWeight.Bold, XMaxType.largeTitle.fontWeight)

        assertEquals(28.sp, XMaxType.primaryTitle.fontSize)
        assertEquals(FontWeight.Bold, XMaxType.primaryTitle.fontWeight)

        assertEquals(22.sp, XMaxType.sectionHeading.fontSize)
        assertEquals(FontWeight.Bold, XMaxType.sectionHeading.fontWeight)

        assertEquals(20.sp, XMaxType.secondaryTitle.fontSize)
        assertEquals(FontWeight.SemiBold, XMaxType.secondaryTitle.fontWeight)

        assertEquals(17.sp, XMaxType.headline.fontSize)
        assertEquals(FontWeight.SemiBold, XMaxType.headline.fontWeight)

        assertEquals(17.sp, XMaxType.userNameDashboard.fontSize)
        assertEquals(FontWeight.Bold, XMaxType.userNameDashboard.fontWeight)

        assertEquals(17.sp, XMaxType.body.fontSize)
        assertEquals(FontWeight.Normal, XMaxType.body.fontWeight)

        assertEquals(16.sp, XMaxType.callout.fontSize)
        assertEquals(FontWeight.Normal, XMaxType.callout.fontWeight)

        assertEquals(15.sp, XMaxType.subheadline.fontSize)
        assertEquals(FontWeight.Normal, XMaxType.subheadline.fontWeight)

        assertEquals(17.sp, XMaxType.button.fontSize)
        assertEquals(FontWeight.SemiBold, XMaxType.button.fontWeight)

        assertEquals(15.sp, XMaxType.inputLabel.fontSize)
        assertEquals(FontWeight.Medium, XMaxType.inputLabel.fontWeight)

        assertEquals(34.sp, XMaxType.statValue.fontSize)
        assertEquals(FontWeight.Bold, XMaxType.statValue.fontWeight)
        assertEquals("tnum", XMaxType.statValue.fontFeatureSettings)

        assertEquals(13.sp, XMaxType.footnote.fontSize)
        assertEquals(FontWeight.Normal, XMaxType.footnote.fontWeight)

        assertEquals(12.sp, XMaxType.caption1.fontSize)
        assertEquals(FontWeight.Normal, XMaxType.caption1.fontWeight)

        assertEquals(11.sp, XMaxType.caption2.fontSize)
        assertEquals(FontWeight.Normal, XMaxType.caption2.fontWeight)

        assertTrue(XMaxType.navLabel.fontSize.value in 11f..12f)
        assertEquals(FontWeight.Medium, XMaxType.navLabel.fontWeight)

        assertEquals("tnum", XMaxType.body.tabular().fontFeatureSettings)
        assertEquals("tnum", XMaxType.numericTitle.fontFeatureSettings)
        assertEquals("tnum", XMaxType.numericSection.fontFeatureSettings)
        assertEquals("tnum", XMaxType.numericHeadline.fontFeatureSettings)
        assertEquals("tnum", XMaxType.numericBody.fontFeatureSettings)
        assertEquals("tnum", XMaxType.numericSubheadline.fontFeatureSettings)
        assertEquals("tnum", XMaxType.numericFootnote.fontFeatureSettings)
        assertEquals("tnum", XMaxType.numericCaption1.fontFeatureSettings)
        assertEquals("tnum", XMaxType.monoTimer.fontFeatureSettings)

        var resolvedM3HeadingFamily: FontFamily? = null
        var resolvedTokenRef = XMaxType
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                XMaxTheme(darkTheme = false) {
                    resolvedM3HeadingFamily = MaterialTheme.typography.headlineMedium.fontFamily
                    resolvedTokenRef = LocalXMaxTypography.current
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(InterFontFamily, resolvedM3HeadingFamily)
        assertEquals(XMaxTypography.headlineMedium, XMaxType.sectionHeading)
        assertEquals(XMaxType, resolvedTokenRef)
    }

    @Test
    fun `dashboard header, overview, projects, tracking, and signin render across light dark phone tablet and scaled fonts`() {
        val member = Member(
            id = "u_admin",
            uid = "u_admin",
            companyNameInput = "X.MAX",
            fullName = "Sumanth Yashas",
            accessLevel = AccessLevel.SUPER_ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            departmentId = "dept_creative",
            departmentName = "Creative",
            jobRoleName = "Director"
        )
        val project = Project(
            id = "proj_1",
            title = "Enterprise Brand System",
            clientCompanyName = "Northwind Labs",
            approvedQuoteNumber = "QT-2026-014",
            agreedValuePaise = 250_000_00L,
            paidPaise = 125_000_00L,
            status = ProjectStatus.IN_PROGRESS.name
        )
        val deliverable = Deliverable(
            id = "deliv_1",
            projectId = "proj_1",
            projectTitle = "Enterprise Brand System",
            clientCompanyName = "Northwind Labs",
            title = "Design Token Spec",
            assignedToUid = "u_admin",
            assignedToName = "Sumanth Yashas",
            status = DeliverableStatus.IN_PROGRESS.name,
            dueDateMillis = System.currentTimeMillis() + 86_400_000L
        )
        val richState = AgencyUiState(
            authSessionStatus = AuthSessionStatus.SignedOut,
            company = CatalogueSeedData.defaultCompanyProfile("u_admin"),
            companyLoaded = true,
            myMember = member,
            memberLoaded = true,
            members = listOf(member),
            departments = CatalogueSeedData.defaultDepartments(),
            catalogue = CatalogueSeedData.defaultCatalogue(),
            projects = listOf(project),
            deliverables = listOf(deliverable)
        )

        // 1. ApprovedWorkspace renders Dashboard Welcome (Regular), User Name (Bold), Overview, Projects
        composeTestRule.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[AgencyViewModel::class.java]
            activity.setContent {
                XMaxTheme(darkTheme = false) {
                    ApprovedWorkspace(state = richState, viewModel = vm)
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Welcome").assertExists()
        composeTestRule.onAllNodesWithText("Sumanth Yashas").assertCountEquals(2)
        composeTestRule.onNodeWithText("Overview").assertExists()
        composeTestRule.onNodeWithTag("track_projects_pill_button").assertExists()

        // 2. HomeScreen Current Tracking section + dark theme + tablet width + 1.3x accessibility fontScale
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 2f, fontScale = 1.3f)
                ) {
                    XMaxTheme(darkTheme = true) {
                        Box(modifier = Modifier.width(840.dp)) {
                            HomeScreen(
                                state = richState,
                                onOpenNewQuote = {},
                                onNavigateRequests = {},
                                onNavigateProjects = {},
                                onNavigateMyWork = {},
                                onNavigateTeam = {},
                                onNavigatePayments = {},
                                onOpenDeliverable = {}
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Overview").assertExists()
        assertTrue(composeTestRule.onAllNodesWithText("Projects").fetchSemanticsNodes().isNotEmpty())

        // 3. SignInScreen phone compact width + 1.3x fontScale
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 2f, fontScale = 1.3f)
                ) {
                    XMaxTheme(darkTheme = false) {
                        Box(modifier = Modifier.width(360.dp)) {
                            SignInScreen(
                                authInProgress = false,
                                errorMessage = null,
                                onGoogleSignIn = {}
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sign in to continue").assertExists()
        composeTestRule.onNodeWithTag("continue_with_google_button").assertExists()
    }
}
