package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.chat.ChatSettingsStore
import com.example.chat.ChatUserPreferences
import com.example.chat.StatusCircleColorCatalog
import com.example.chat.StatusCircleColorPreset
import com.example.model.AccessLevel
import com.example.model.CompanyProfile
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.StatusPrivacyMode
import com.example.ui.AgencyUiState
import com.example.ui.ChatViewModel
import com.example.ui.status.StatusPrivacyPanelDialog
import com.example.ui.status.StatusSettingsDialog
import com.example.ui.status.StatusTabScreen
import com.example.ui.status.SuperAdminStatusCircleColorDialog
import com.example.ui.theme.XMaxTheme
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusPrivacyAndThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var appContext: Application

    private val superAdmin = Member(
        uid = "uid_super",
        fullName = "X.MAX Founder",
        accessLevel = AccessLevel.SUPER_ADMIN.name,
        status = MembershipStatus.APPROVED.name,
        active = true
    )

    private val adminMember = Member(
        uid = "uid_admin",
        fullName = "Priya Manager",
        accessLevel = AccessLevel.ADMIN.name,
        status = MembershipStatus.APPROVED.name,
        active = true
    )

    private val staffMember = Member(
        uid = "uid_staff",
        fullName = "Aarav Designer",
        accessLevel = AccessLevel.STAFF.name,
        status = MembershipStatus.APPROVED.name,
        active = true
    )

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        ChatSettingsStore.setActiveProfile(appContext, "test_status_ui")
    }

    @Test
    fun `StatusCircleColorCatalog provides classic and future gradient presets and syncs via ChatSettingsStore`() {
        val classic = StatusCircleColorCatalog.presets.filter { !it.isFutureStyle }
        val future = StatusCircleColorCatalog.presets.filter { it.isFutureStyle }
        assertEquals(4, classic.size)
        assertEquals(4, future.size)
        assertTrue(future.all { it.gradientEnabled })

        val aurora = StatusCircleColorCatalog.findById("future_aurora")
        assertNotNull(aurora)
        ChatSettingsStore.syncCompanyStatusCircleTheme(
            context = appContext,
            colorId = aurora.id,
            unviewedHex = aurora.unviewedHex,
            accentHex = aurora.accentHex,
            ownHex = aurora.ownHex,
            gradientEnabled = aurora.gradientEnabled
        )
        val updated = ChatSettingsStore.prefsFlow.value
        assertEquals("future_aurora", updated.workspaceStatusCircleColorId)
        assertEquals(aurora.unviewedHex, updated.workspaceStatusUnviewedHex)
        assertEquals(aurora.accentHex, updated.workspaceStatusAccentHex)
        assertTrue(updated.workspaceStatusGradientEnabled)
    }

    @Test
    fun `StatusSettingsDialog exposes Future Status Circle Colors to Super Admin`() {
        composeRule.setContent {
            XMaxTheme {
                StatusSettingsDialog(
                    member = superAdmin,
                    prefs = ChatUserPreferences(),
                    onOpenPrivacy = {},
                    onOpenBlockedUsers = {},
                    onOpenStatusCircleColors = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_settings_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("status_privacy_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_blocked_users_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_circle_color_btn").assertIsDisplayed()
    }

    @Test
    fun `StatusSettingsDialog hides Future Status Circle Colors from Admin`() {
        composeRule.setContent {
            XMaxTheme {
                StatusSettingsDialog(
                    member = adminMember,
                    prefs = ChatUserPreferences(),
                    onOpenPrivacy = {},
                    onOpenBlockedUsers = {},
                    onOpenStatusCircleColors = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_privacy_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_circle_color_btn").assertDoesNotExist()
    }

    @Test
    fun `StatusSettingsDialog hides Future Status Circle Colors from Staff`() {
        composeRule.setContent {
            XMaxTheme {
                StatusSettingsDialog(
                    member = staffMember,
                    prefs = ChatUserPreferences(),
                    onOpenPrivacy = {},
                    onOpenBlockedUsers = {},
                    onOpenStatusCircleColors = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_privacy_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_circle_color_btn").assertDoesNotExist()
    }

    @Test
    fun `SuperAdminStatusCircleColorDialog blocks non-super-admins`() {
        composeRule.setContent {
            XMaxTheme {
                SuperAdminStatusCircleColorDialog(
                    member = adminMember,
                    activeColorId = "emerald_default",
                    onSelectPreset = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_circle_color_dialog").assertDoesNotExist()
    }

    @Test
    fun `SuperAdminStatusCircleColorDialog applies selected future preset for Super Admin`() {
        var appliedPreset: StatusCircleColorPreset? = null

        composeRule.setContent {
            XMaxTheme {
                SuperAdminStatusCircleColorDialog(
                    member = superAdmin,
                    activeColorId = "future_aurora",
                    onSelectPreset = { appliedPreset = it },
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_circle_color_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("circle_color_preset_emerald_default").performClick()
        composeRule.onNodeWithTag("apply_status_circle_color_btn").performClick()
        composeRule.waitForIdle()

        assertEquals("emerald_default", appliedPreset?.id)
        assertFalse(appliedPreset?.gradientEnabled == true)
    }

    @Test
    fun `StatusPrivacyPanelDialog renders screenshot-inspired UI and persists custom Close friends audience`() {
        var savedMode: StatusPrivacyMode? = null
        var savedAllowed: Set<String> = emptySet()
        var savedLabel = ""
        var savedEmoji = ""

        composeRule.setContent {
            XMaxTheme {
                StatusPrivacyPanelDialog(
                    myMember = staffMember,
                    allMembers = listOf(superAdmin, adminMember),
                    initialMode = StatusPrivacyMode.ALL_APPROVED,
                    initialAllowedUids = setOf(superAdmin.uid),
                    initialExcludedUids = emptySet(),
                    onSave = { mode, allowed, _, label, emoji ->
                        savedMode = mode
                        savedAllowed = allowed
                        savedLabel = label
                        savedEmoji = emoji
                    },
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Status privacy").assertIsDisplayed()
        composeRule.onNodeWithText("Audience who can see my status").assertIsDisplayed()
        composeRule.onNodeWithTag("status_privacy_preview_badge").assertExists()

        // Switch to Close friends (SELECTED_ONLY) and hit Continue
        composeRule.onNodeWithTag("status_privacy_mode_selected_only").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_privacy_save_btn").performClick()
        composeRule.waitForIdle()

        assertEquals(StatusPrivacyMode.SELECTED_ONLY, savedMode)
        assertTrue(superAdmin.uid in savedAllowed)
        assertEquals("Close friends", savedLabel)
        assertEquals("💜", savedEmoji)
    }

    @Test
    fun `StatusTabScreen consolidates top bar options behind single Settings icon`() {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }
        val chatVm = ChatViewModel(appContext)

        val state = AgencyUiState(
            company = CompanyProfile(
                id = "xmax",
                name = "X.MAX",
                statusCircleColorId = "future_cyber",
                statusCircleUnviewedHex = 0xFF22C55EL,
                statusCircleAccentHex = 0xFF3B82F6L,
                statusCircleOwnHex = 0xFF10B981L,
                statusCircleGradientEnabled = true
            ),
            myMember = superAdmin,
            members = listOf(superAdmin, adminMember, staffMember)
        )

        composeRule.setContent {
            XMaxTheme {
                StatusTabScreen(
                    state = state,
                    chatViewModel = chatVm,
                    onOpenDirectChannel = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Top bar should have Add + Settings icon, NOT separate privacy/block buttons
        composeRule.onNodeWithTag("status_tab_add_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_settings_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_privacy_btn").assertDoesNotExist()
        composeRule.onNodeWithTag("status_blocked_users_btn").assertDoesNotExist()

        // Tapping Settings opens StatusSettingsDialog with Privacy, Blocked Contacts & Super Admin Circle Colors
        composeRule.onNodeWithTag("status_settings_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_settings_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("status_privacy_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_blocked_users_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("status_circle_color_btn").assertIsDisplayed()
        assertFalse(ChatSettingsStore.prefsFlow.value.workspaceStatusCircleColorId.isBlank())
    }
}
