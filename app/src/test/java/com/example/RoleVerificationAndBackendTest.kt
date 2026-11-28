package com.example

import com.example.chat.realtime.RtdbPresenceManager
import com.example.chat.remote.WorkerBridgeClient
import com.example.data.AuthenticationRepository
import com.example.data.UserRoleType
import com.example.data.VerifiedUserRole
import com.example.domain.CatalogueSeedData
import com.example.model.AccessLevel
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.ui.AgencyUiState
import com.example.ui.ApprovedGateKey
import com.example.ui.CommercialGateKey
import com.example.ui.PaymentGateKey
import com.example.ui.ProjectGateKey
import com.example.ui.friendlyFirestoreMessage
import com.example.ui.screens.AdminDashboardAccessEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleVerificationAndBackendTest {

    @Test
    fun `only xdotmax@gmail_com resolves to super_admin and bootstrap eligibility`() {
        val superAdminRole = AuthenticationRepository.verifyUserRole(
            email = "xdotmax@gmail.com",
            uid = "uid_super",
            isAuthenticated = true
        )
        assertEquals(UserRoleType.SUPER_ADMIN, superAdminRole.role)
        assertEquals("super_admin", superAdminRole.role.roleKey)
        assertTrue(superAdminRole.isSuperAdmin)
        assertTrue(superAdminRole.isAuthenticated)

        val mixedCaseSuperAdmin = AuthenticationRepository.verifyUserRole(
            email = "  XDotMax@GMAIL.com ",
            uid = "uid_super_2",
            isAuthenticated = true
        )
        assertEquals(UserRoleType.SUPER_ADMIN, mixedCaseSuperAdmin.role)
        assertTrue(mixedCaseSuperAdmin.isSuperAdmin)
        assertEquals("xdotmax@gmail.com", mixedCaseSuperAdmin.email)

        assertTrue(CatalogueSeedData.isSuperAdminEmail("xdotmax@gmail.com"))
        assertTrue(CatalogueSeedData.isBootstrapEmail("XDotMax@gmail.com"))
    }

    @Test
    fun `khushirathod014@gmail_com and other members resolve to standard_member`() {
        val khushiRole = AuthenticationRepository.verifyUserRole(
            email = "khushirathod014@gmail.com",
            uid = "uid_khushi",
            isAuthenticated = true
        )
        assertEquals(UserRoleType.STANDARD_MEMBER, khushiRole.role)
        assertEquals("standard_member", khushiRole.role.roleKey)
        assertFalse(khushiRole.isSuperAdmin)
        assertTrue(khushiRole.isAuthenticated)

        assertFalse(CatalogueSeedData.isSuperAdminEmail("khushirathod014@gmail.com"))
        assertFalse(CatalogueSeedData.isBootstrapEmail("khushirathod014@gmail.com"))

        val unauthRole = AuthenticationRepository.verifyUserRole(
            email = "xdotmax@gmail.com",
            uid = "",
            isAuthenticated = false
        )
        assertEquals(UserRoleType.UNAUTHENTICATED, unauthRole.role)
        assertFalse(unauthRole.isSuperAdmin)
        assertFalse(unauthRole.isAuthenticated)
    }

    @Test
    fun `admin dashboard evaluator grants access exclusively to xdotmax@gmail_com`() {
        val staleKhushiMember = Member(
            id = "uid_khushi",
            uid = "uid_khushi",
            fullName = "Team Admin",
            accessLevel = AccessLevel.SUPER_ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true
        )
        val khushiState = AgencyUiState(
            myMember = staleKhushiMember,
            verifiedUserRole = VerifiedUserRole(
                role = UserRoleType.STANDARD_MEMBER,
                email = "khushirathod014@gmail.com",
                uid = "uid_khushi",
                isSuperAdmin = false,
                isAuthenticated = true
            )
        )
        val khushiEval = AdminDashboardAccessEvaluator.evaluate(khushiState)
        assertFalse(khushiEval.isAuthorized)
        assertEquals(UserRoleType.STANDARD_MEMBER, khushiEval.resolvedRole)
        assertFalse(khushiState.isSuperAdmin)
        assertFalse(khushiState.isCurrentUserSuperAdminEmail)

        val xdotmaxState = AgencyUiState(
            myMember = staleKhushiMember.copy(uid = "uid_super", fullName = "X.MAX Director"),
            verifiedUserRole = VerifiedUserRole(
                role = UserRoleType.SUPER_ADMIN,
                email = "xdotmax@gmail.com",
                uid = "uid_super",
                isSuperAdmin = true,
                isAuthenticated = true
            )
        )
        val xdotmaxEval = AdminDashboardAccessEvaluator.evaluate(xdotmaxState)
        assertTrue(xdotmaxEval.isAuthorized)
        assertEquals(UserRoleType.SUPER_ADMIN, xdotmaxEval.resolvedRole)
        assertTrue(xdotmaxState.isSuperAdmin)
        assertTrue(xdotmaxState.isCurrentUserSuperAdminEmail)
    }

    @Test
    fun `worker bridge bucket routing, labels, and rtdb presence alignment behave accurately`() {
        assertEquals("chat-media", WorkerBridgeClient.bucketForAttachmentType(com.example.model.ChatAttachmentType.IMAGE))
        assertEquals("chat-videos", WorkerBridgeClient.bucketForAttachmentType(com.example.model.ChatAttachmentType.VIDEO))
        assertEquals("chat-voice", WorkerBridgeClient.bucketForAttachmentType(com.example.model.ChatAttachmentType.AUDIO))
        assertEquals("chat-docs", WorkerBridgeClient.bucketForAttachmentType(com.example.model.ChatAttachmentType.FILE))
        assertTrue(WorkerBridgeClient.activeStorageProviderLabel().isNotBlank())

        assertEquals(
            "https://gen-lang-client-0137668580-default-rtdb.firebaseio.com",
            RtdbPresenceManager.resolveRtdbUrl(
                configuredUrl = "",
                activeProjectId = "gen-lang-client-0137668580",
                optionsDatabaseUrl = null
            )
        )
    }

    @Test
    fun `firestore gate keys stay immutable across presence heartbeats and quota errors map cleanly`() {
        val m1 = Member(
            id = "u1",
            uid = "u1",
            status = MembershipStatus.APPROVED.name,
            accessLevel = AccessLevel.SUPER_ADMIN.name,
            active = true,
            departmentId = "dept_ops",
            messagingSuspended = false,
            lastActiveAtMillis = 1_000L,
            availabilityStatus = "OFFLINE"
        )
        val m2 = m1.copy(
            lastActiveAtMillis = 999_999L,
            availabilityStatus = "ACTIVE",
            e2eePublicKey = "pub_new"
        )
        val key1 = ApprovedGateKey(m1.uid, m1.status, m1.accessLevelEnum(), m1.active, m1.departmentId, m1.messagingSuspended)
        val key2 = ApprovedGateKey(m2.uid, m2.status, m2.accessLevelEnum(), m2.active, m2.departmentId, m2.messagingSuspended)
        assertEquals(key1, key2)
        assertEquals(CommercialGateKey("u1", true), CommercialGateKey("u1", true))
        assertEquals(ProjectGateKey("u1", true, "dept_ops"), ProjectGateKey("u1", true, "dept_ops"))
        assertEquals(PaymentGateKey("u1", true), PaymentGateKey("u1", true))

        val quotaEx = IllegalStateException("RESOURCE_EXHAUSTED: Quota exceeded.")
        val friendlyQuota = friendlyFirestoreMessage(quotaEx, "Fallback")
        assertTrue(friendlyQuota.contains("quota", ignoreCase = true))
        assertTrue(friendlyQuota.contains("Retry Sync", ignoreCase = true))

        val otherEx = IllegalStateException("Network reset")
        assertEquals("Network reset", friendlyFirestoreMessage(otherEx, "Fallback"))
    }
}
