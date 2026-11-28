package com.example

import com.example.data.AuthenticationRepository
import com.example.data.UserRoleType
import com.example.domain.CatalogueSeedData
import com.example.domain.ProjectRequirementInput
import com.example.domain.ProjectRequirementsQuoteEngine
import com.example.domain.RequirementServiceSelection
import com.example.model.AccessLevel
import com.example.model.AppPermission
import com.example.model.Client
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.PermissionGrant
import com.example.model.QuotationStatus
import com.example.ui.AgencyUiState
import com.example.ui.components.WorkspaceRoleTier
import com.example.ui.components.WorkspaceUiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequirementsQuoteAndRoleUiTest {

    @Test
    fun `project requirements form engine calculates totals, urgent surcharge, gst, and structured client summary`() {
        val catalogue = CatalogueSeedData.defaultCatalogue()
        val logoItem = catalogue.first { it.id == "srv_01" } // 15,000 INR = 1,500,000 paise
        val brandDeckItem = catalogue.first { it.id == "srv_02" }

        val input = ProjectRequirementInput(
            selectedClientId = "cli_1",
            clientCompanyName = "Acme Ventures",
            clientContactPerson = "Riya Sharma",
            clientEmail = "riya@acme.in",
            projectTitle = "2026 Brand Identity Refresh",
            requirementsBrief = "Modernize visual identity for B2B product launch.",
            deliverablesText = "• Primary logo & wordmark\n- Color & typography system\n• Social launch kit",
            selectedServices = listOf(
                RequirementServiceSelection(item = logoItem, quantity = 1),
                RequirementServiceSelection(item = brandDeckItem, quantity = 2)
            ),
            customLineTitle = "Print Packaging Setup",
            customLinePricePaise = 5_000_00L, // ₹5,000
            urgentDelivery = true,
            includeGst = true,
            gstRatePercent = 18,
            discountPaise = 0L,
            advancePercent = 50,
            timelineNote = "10 business days",
            validityDays = 15,
            revisionRounds = 2
        )

        val summary = ProjectRequirementsQuoteEngine.generateSummary(input, companyName = "X.MAX")

        assertTrue(summary.isReady)
        assertTrue(summary.validationHints.isEmpty())
        assertEquals("Acme Ventures", summary.clientDisplayName)
        assertEquals("Riya Sharma", summary.contactDisplayName)
        assertEquals(4, summary.scopeHighlights.size) // 1 brief + 3 parsed deliverables
        assertEquals(3, summary.calculation.computedLines.size)

        val expectedSubtotal = logoItem.pricePaise + (brandDeckItem.pricePaise * 2) + 5_000_00L
        assertEquals(expectedSubtotal, summary.calculation.subtotalPaise)
        assertTrue(summary.calculation.surchargePaise > 0L)
        assertTrue(summary.calculation.gstAmountPaise > 0L)
        assertEquals(
            summary.calculation.totalPaise,
            summary.calculation.advancePaise + summary.calculation.balancePaise
        )

        assertTrue(summary.formattedClientMessage.contains("X.MAX — QUOTATION SUMMARY"))
        assertTrue(summary.formattedClientMessage.contains("Client: Acme Ventures (Riya Sharma)"))
        assertTrue(summary.formattedClientMessage.contains("Project: 2026 Brand Identity Refresh"))
        assertTrue(summary.formattedClientMessage.contains("Print Packaging Setup"))
        assertTrue(summary.formattedClientMessage.contains("Urgent Delivery (+25%)"))
        assertTrue(summary.formattedClientMessage.contains("GST (18%)"))

        val client = Client(id = "cli_1", companyName = "Acme Ventures", contactPerson = "Riya Sharma")
        val draftQuote = ProjectRequirementsQuoteEngine.toDraftQuotation(
            input = input,
            summary = summary,
            client = client,
            company = CatalogueSeedData.defaultCompanyProfile("u_super")
        )
        assertEquals("cli_1", draftQuote.clientId)
        assertEquals("2026 Brand Identity Refresh", draftQuote.projectTitle)
        assertEquals(QuotationStatus.DRAFT.name, draftQuote.status)
        assertEquals(summary.calculation.totalPaise, draftQuote.totalPaise)
        assertEquals(3, draftQuote.lines.size)
    }

    @Test
    fun `incomplete requirements input surfaces clear validation hints`() {
        val summary = ProjectRequirementsQuoteEngine.generateSummary(
            ProjectRequirementInput(
                clientCompanyName = "",
                projectTitle = "",
                requirementsBrief = ""
            )
        )
        assertFalse(summary.isReady)
        assertTrue(summary.validationHints.any { it.contains("client", ignoreCase = true) })
        assertTrue(summary.validationHints.any { it.contains("project title", ignoreCase = true) })
        assertTrue(summary.validationHints.any { it.contains("at least one service", ignoreCase = true) })
    }

    @Test
    fun `conditional ui policy unlocks full admin controls exclusively for xdotmax super admin`() {
        val superMember = Member(
            id = "u_super",
            uid = "u_super",
            fullName = "Max Director",
            accessLevel = AccessLevel.SUPER_ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true
        )
        val verified = AuthenticationRepository.verifyUserRole(
            email = "xdotmax@gmail.com",
            uid = "u_super",
            isAuthenticated = true
        )
        val state = AgencyUiState(
            myMember = superMember,
            memberLoaded = true,
            verifiedUserRole = verified
        )
        val policy = WorkspaceUiPolicy.fromState(state)

        assertEquals(WorkspaceRoleTier.SUPER_ADMIN, policy.roleTier)
        assertEquals(UserRoleType.SUPER_ADMIN, verified.role)
        assertTrue(policy.isSuperAdmin)
        assertTrue(policy.isAdminOrAbove)
        assertFalse(policy.isStandardMember)
        assertTrue(policy.canAccessAdminDashboard)
        assertTrue(policy.canManageProjectsLifecycle)
        assertTrue(policy.canManageTeamAndGovernance)
        assertTrue(policy.canManageCommercialQuotes)
        assertTrue(policy.showAdminOverviewBanner)
        assertTrue(policy.showAdminDrawerCounters)
        assertFalse(policy.showStandardMemberFocusedHome)
        assertTrue(SecondaryDestination.ADMIN_DASHBOARD in policy.permittedSecondaryDestinations)
        assertTrue(SecondaryDestination.COMPANY_SETTINGS in policy.permittedSecondaryDestinations)
        assertTrue(PrimaryTab.HOME in policy.visiblePrimaryTabs)
    }

    @Test
    fun `conditional ui policy hides admin controls and provides clean workspace for standard team members`() {
        val khushiStaff = Member(
            id = "u_khushi",
            uid = "u_khushi",
            fullName = "Khushi Rathod",
            accessLevel = AccessLevel.STAFF.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            departmentId = "dept_creative",
            departmentName = "Creative"
        )
        val verified = AuthenticationRepository.verifyUserRole(
            email = "khushirathod014@gmail.com",
            uid = "u_khushi",
            isAuthenticated = true
        )
        val state = AgencyUiState(
            myMember = khushiStaff,
            memberLoaded = true,
            verifiedUserRole = verified
        )
        val policy = WorkspaceUiPolicy.fromState(state)

        assertEquals(WorkspaceRoleTier.STANDARD_MEMBER, policy.roleTier)
        assertEquals(UserRoleType.STANDARD_MEMBER, verified.role)
        assertFalse(policy.isSuperAdmin)
        assertFalse(policy.isAdminOrAbove)
        assertTrue(policy.isStandardMember)
        assertFalse(policy.canAccessAdminDashboard)
        assertFalse(policy.canManageProjectsLifecycle)
        assertFalse(policy.canManageTeamAndGovernance)
        assertFalse(policy.canManageCommercialQuotes)
        assertFalse(policy.canViewAnalyticsAndFinance)
        assertFalse(policy.showAdminOverviewBanner)
        assertFalse(policy.showAdminDrawerCounters)
        assertTrue(policy.showStandardMemberFocusedHome)
        assertFalse(SecondaryDestination.ADMIN_DASHBOARD in policy.permittedSecondaryDestinations)
        assertFalse(SecondaryDestination.TEAM in policy.permittedSecondaryDestinations)
        assertFalse(SecondaryDestination.ORG_ROUTING in policy.permittedSecondaryDestinations)
        assertFalse(SecondaryDestination.COMPANY_SETTINGS in policy.permittedSecondaryDestinations)
        assertFalse(SecondaryDestination.ACTIVITY in policy.permittedSecondaryDestinations)
        assertEquals(listOf(PrimaryTab.MY_WORK, PrimaryTab.CHAT), policy.visiblePrimaryTabs)
    }

    @Test
    fun `non-super admin cannot access destructive project lifecycle controls`() {
        val adminMgr = Member(
            id = "u_mgr",
            uid = "u_mgr",
            fullName = "Agency Manager",
            accessLevel = AccessLevel.ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            permissions = mapOf(
                AppPermission.CREATE_QUOTATIONS.name to PermissionGrant(allowed = true),
                AppPermission.DELETE_PROJECT.name to PermissionGrant(allowed = true)
            )
        )
        val verified = AuthenticationRepository.verifyUserRole(
            email = "manager@agency.com",
            uid = "u_mgr",
            isAuthenticated = true
        )
        val state = AgencyUiState(
            myMember = adminMgr,
            memberLoaded = true,
            verifiedUserRole = verified
        )
        val policy = WorkspaceUiPolicy.fromState(state)

        assertEquals(WorkspaceRoleTier.ADMIN, policy.roleTier)
        assertFalse(policy.isSuperAdmin)
        assertTrue(policy.isAdminOrAbove)
        assertFalse(policy.canAccessAdminDashboard)
        assertFalse(SecondaryDestination.ADMIN_DASHBOARD in policy.permittedSecondaryDestinations)
        assertFalse(policy.canManageProjectsLifecycle)
        assertTrue(policy.canManageCommercialQuotes)
        assertFalse(SecondaryDestination.COMPANY_SETTINGS in policy.permittedSecondaryDestinations)
    }
}
