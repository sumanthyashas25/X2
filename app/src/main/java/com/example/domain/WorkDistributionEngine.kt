package com.example.domain

import com.example.model.AssignmentStatus
import com.example.model.ChecklistItem
import com.example.model.Deliverable
import com.example.model.DeliverableStatus
import com.example.model.Department
import com.example.model.JobRole
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.PricingMode
import com.example.model.ProjectStatus
import com.example.model.QuoteLineItem
import com.example.model.RoutingRule

data class DeliverableDraft(
    val draftId: String,
    val title: String,
    val description: String,
    val quantity: Int,
    val sourceCategory: String,
    val departmentId: String,
    val departmentName: String,
    val jobRoleId: String,
    val jobRoleName: String,
    val assignedToUid: String,
    val assignedToName: String,
    val assignmentStatus: AssignmentStatus,
    val matchingCandidates: List<Member>,
    val checklist: List<ChecklistItem>
)

object WorkDistributionEngine {

    fun buildDeliverableDrafts(
        quoteLines: List<QuoteLineItem>,
        routingRules: List<RoutingRule>,
        members: List<Member>,
        departments: List<Department>,
        jobRoles: List<JobRole>
    ): List<DeliverableDraft> {
        val activeMembers = members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active }
        val deptMap = departments.associateBy { it.id }
        val roleMap = jobRoles.associateBy { it.id }
        val sortedRules = routingRules.filter { it.active }.sortedByDescending { it.priority }

        val drafts = mutableListOf<DeliverableDraft>()
        var counter = 1

        for (line in quoteLines) {
            if (line.pricingModeEnum() == PricingMode.PERCENTAGE_SURCHARGE || line.separatelyBilled) {
                continue
            }

            if (line.inclusions.isNotEmpty()) {
                for (inc in line.inclusions) {
                    val qty = (inc.quantity.coerceAtLeast(1)) * line.quantity.coerceAtLeast(1)
                    drafts += resolveSingleDraft(
                        draftId = "draft_${counter++}",
                        title = inc.title,
                        description = inc.description.ifBlank { "Included from ${line.title}" },
                        quantity = qty,
                        category = inc.category.ifBlank { line.category },
                        sortedRules = sortedRules,
                        activeMembers = activeMembers,
                        deptMap = deptMap,
                        roleMap = roleMap
                    )
                }
            } else {
                drafts += resolveSingleDraft(
                    draftId = "draft_${counter++}",
                    title = line.title,
                    description = line.description,
                    quantity = line.quantity.coerceAtLeast(1),
                    category = line.category,
                    sortedRules = sortedRules,
                    activeMembers = activeMembers,
                    deptMap = deptMap,
                    roleMap = roleMap
                )
            }
        }
        return drafts
    }

    private fun resolveSingleDraft(
        draftId: String,
        title: String,
        description: String,
        quantity: Int,
        category: String,
        sortedRules: List<RoutingRule>,
        activeMembers: List<Member>,
        deptMap: Map<String, Department>,
        roleMap: Map<String, JobRole>
    ): DeliverableDraft {
        val text = "$title $description $category".lowercase()
        val matchedRule = sortedRules.firstOrNull { rule ->
            val kwMatch = rule.keywords.any { kw -> kw.isNotBlank() && text.contains(kw.lowercase()) }
            val catMatch = rule.serviceCategories.any { c -> c.equals(category, ignoreCase = true) }
            kwMatch || catMatch
        }

        val deptId = matchedRule?.departmentId ?: "dept_ops"
        val deptName = deptMap[deptId]?.name ?: matchedRule?.departmentName ?: "Operations"
        val roleId = matchedRule?.jobRoleId ?: "role_pm"
        val roleName = roleMap[roleId]?.name ?: matchedRule?.jobRoleName ?: "Project Manager"

        val candidates = activeMembers.filter { m ->
            m.departmentId == deptId && m.jobRoleId == roleId
        }

        val (assignedUid, assignedName, assignmentStatus) = when (candidates.size) {
            1 -> Triple(candidates.first().uid, candidates.first().fullName, AssignmentStatus.ASSIGNED)
            in 2..Int.MAX_VALUE -> Triple("", "", AssignmentStatus.DEPT_QUEUE)
            else -> Triple("", "", AssignmentStatus.NEEDS_ASSIGNMENT)
        }

        val checklist = buildDefaultChecklist(title, category)

        return DeliverableDraft(
            draftId = draftId,
            title = title,
            description = description,
            quantity = quantity,
            sourceCategory = category,
            departmentId = deptId,
            departmentName = deptName,
            jobRoleId = roleId,
            jobRoleName = roleName,
            assignedToUid = assignedUid,
            assignedToName = assignedName,
            assignmentStatus = assignmentStatus,
            matchingCandidates = candidates,
            checklist = checklist
        )
    }

    fun buildDefaultChecklist(title: String, category: String): List<ChecklistItem> {
        val t = "$title $category".lowercase()
        return when {
            t.contains("reel") || t.contains("video") || t.contains("motion") -> listOf(
                ChecklistItem("c1", "Review brief & footage / script"),
                ChecklistItem("c2", "Assemble rough cut & pacing"),
                ChecklistItem("c3", "Add captions, sound design & color grade"),
                ChecklistItem("c4", "Export final master & preview link")
            )
            t.contains("web") || t.contains("landing") || t.contains("page") || t.contains("store") || t.contains("seo") -> listOf(
                ChecklistItem("c1", "Confirm wireframe / content brief"),
                ChecklistItem("c2", "Build responsive layout & core sections"),
                ChecklistItem("c3", "QA mobile breakpoints, forms & speed"),
                ChecklistItem("c4", "Publish staging preview for manager review")
            )
            t.contains("ad") || t.contains("meta") || t.contains("google") || t.contains("lead") -> listOf(
                ChecklistItem("c1", "Verify pixel / conversion tracking"),
                ChecklistItem("c2", "Configure audience targeting & campaign structure"),
                ChecklistItem("c3", "Upload ad creatives & primary copy"),
                ChecklistItem("c4", "Final launch readiness review")
            )
            else -> listOf(
                ChecklistItem("c1", "Review client brief & brand references"),
                ChecklistItem("c2", "Create initial concept / draft"),
                ChecklistItem("c3", "Refine typography, layout & brand consistency"),
                ChecklistItem("c4", "Prepare export & preview link for review")
            )
        }
    }

    fun applyOverride(
        draft: DeliverableDraft,
        assignee: Member?
    ): DeliverableDraft {
        return if (assignee == null) {
            draft.copy(
                assignedToUid = "",
                assignedToName = "",
                assignmentStatus = AssignmentStatus.NEEDS_ASSIGNMENT
            )
        } else {
            draft.copy(
                assignedToUid = assignee.uid,
                assignedToName = assignee.fullName,
                assignmentStatus = AssignmentStatus.ASSIGNED,
                departmentId = assignee.departmentId.ifBlank { draft.departmentId },
                departmentName = assignee.departmentName.ifBlank { draft.departmentName },
                jobRoleId = assignee.jobRoleId.ifBlank { draft.jobRoleId },
                jobRoleName = assignee.jobRoleName.ifBlank { draft.jobRoleName }
            )
        }
    }

    fun summarizeProjectProgress(deliverables: List<Deliverable>): String {
        if (deliverables.isEmpty()) return "No deliverables created yet"
        val total = deliverables.size
        val approved = deliverables.count { it.statusEnum() == DeliverableStatus.APPROVED }
        val inReview = deliverables.count { it.statusEnum() == DeliverableStatus.READY_FOR_REVIEW }
        val blocked = deliverables.count { it.statusEnum() == DeliverableStatus.BLOCKED }
        val parts = mutableListOf("$approved of $total deliverables approved")
        if (inReview > 0) parts += "$inReview in review"
        if (blocked > 0) parts += "$blocked blocked"
        return parts.joinToString(", ")
    }

    enum class PaymentReminderType(val label: String) {
        ADVANCE_DUE("Advance Payment Due"),
        FINAL_SUBMITTED("Work Submitted • Final Payment Due"),
        FINAL_DEADLINE("Deadline Near • Final Payment Due")
    }

    data class QuotationAdvanceReminder(
        val quotation: com.example.model.Quotation,
        val advanceDuePaise: Long,
        val linkedProject: com.example.model.Project?
    )

    data class ProjectPaymentReminder(
        val project: com.example.model.Project,
        val type: PaymentReminderType,
        val amountDuePaise: Long,
        val detail: String,
        val priority: Int
    )

    data class PaymentReminderSummary(
        val quoteAdvanceReminders: List<QuotationAdvanceReminder>,
        val projectReminders: List<ProjectPaymentReminder>
    ) {
        val totalCount: Int get() = quoteAdvanceReminders.size + projectReminders.size
        val advanceCount: Int get() = quoteAdvanceReminders.size + projectReminders.count { it.type == PaymentReminderType.ADVANCE_DUE }
        val finalCount: Int get() = projectReminders.count {
            it.type == PaymentReminderType.FINAL_SUBMITTED || it.type == PaymentReminderType.FINAL_DEADLINE
        }
    }

    fun computePaymentReminders(
        projects: List<com.example.model.Project>,
        deliverables: List<Deliverable>,
        quotations: List<com.example.model.Quotation>,
        nowMillis: Long = System.currentTimeMillis()
    ): PaymentReminderSummary {
        val activeProjects = projects.filter { !it.deleted }
        val deadlineHorizonMillis = nowMillis + 3L * 86_400_000L

        val quoteAdvanceReminders = quotations
            .filter {
                it.statusEnum() == com.example.model.QuotationStatus.CLIENT_APPROVED && it.advancePaise > 0L
            }
            .mapNotNull { quote ->
                val matchingProject = activeProjects.firstOrNull { p ->
                    p.approvedQuoteId == quote.id ||
                        (quote.linkedProjectId.isNotBlank() && p.id == quote.linkedProjectId) ||
                        (quote.requestId.isNotBlank() && p.requestId == quote.requestId)
                }
                val alreadyPaidAdvance = matchingProject != null && matchingProject.paidPaise >= quote.advancePaise
                if (alreadyPaidAdvance) {
                    null
                } else {
                    QuotationAdvanceReminder(
                        quotation = quote,
                        advanceDuePaise = if (matchingProject != null) {
                            (quote.advancePaise - matchingProject.paidPaise).coerceAtLeast(0L)
                        } else {
                            quote.advancePaise
                        },
                        linkedProject = matchingProject
                    )
                }
            }

        val projectReminders = activeProjects.mapNotNull { proj ->
            if (proj.outstandingPaise <= 0L) return@mapNotNull null

            val projDelivs = deliverables.filter { it.projectId == proj.id }
            val readyOrApprovedCount = projDelivs.count {
                val st = it.statusEnum()
                st == DeliverableStatus.READY_FOR_REVIEW || st == DeliverableStatus.APPROVED
            }
            val anyReadyForReview = projDelivs.any { it.statusEnum() == DeliverableStatus.READY_FOR_REVIEW }
            val allSubmittedOrApproved = projDelivs.isNotEmpty() && readyOrApprovedCount == projDelivs.size
            val pendingDeliverables = projDelivs.filter { it.statusEnum() != DeliverableStatus.APPROVED }
            val nearestDueMillis = pendingDeliverables
                .mapNotNull { it.dueDateMillis.takeIf { d -> d > 0L } }
                .minOrNull()
            val deadlineNear = nearestDueMillis != null && nearestDueMillis <= deadlineHorizonMillis

            val advanceUnpaid = proj.advanceDuePaise > 0L && proj.paidPaise < proj.advanceDuePaise
            when {
                proj.statusEnum() == com.example.model.ProjectStatus.COMPLETED || allSubmittedOrApproved -> {
                    val detail = if (proj.statusEnum() == com.example.model.ProjectStatus.COMPLETED) {
                        "Project completed with ${MoneyFormatter.formatInr(proj.outstandingPaise)} final balance pending"
                    } else {
                        "All ${projDelivs.size} deliverables submitted/approved — collect final ${MoneyFormatter.formatInr(proj.outstandingPaise)}"
                    }
                    ProjectPaymentReminder(
                        project = proj,
                        type = PaymentReminderType.FINAL_SUBMITTED,
                        amountDuePaise = proj.outstandingPaise,
                        detail = detail,
                        priority = 1
                    )
                }
                anyReadyForReview && !advanceUnpaid -> {
                    ProjectPaymentReminder(
                        project = proj,
                        type = PaymentReminderType.FINAL_SUBMITTED,
                        amountDuePaise = proj.outstandingPaise,
                        detail = "$readyOrApprovedCount of ${projDelivs.size} deliverables submitted/approved — track final ${MoneyFormatter.formatInr(proj.outstandingPaise)}",
                        priority = 2
                    )
                }
                deadlineNear -> {
                    val isOverdue = nearestDueMillis!! < nowMillis
                    val dateLabel = MoneyFormatter.formatDate(nearestDueMillis)
                    ProjectPaymentReminder(
                        project = proj,
                        type = PaymentReminderType.FINAL_DEADLINE,
                        amountDuePaise = proj.outstandingPaise,
                        detail = if (isOverdue) {
                            "Deadline passed ($dateLabel) — follow up on ${MoneyFormatter.formatInr(proj.outstandingPaise)} balance"
                        } else {
                            "Deadline due $dateLabel — prepare final ${MoneyFormatter.formatInr(proj.outstandingPaise)} collection"
                        },
                        priority = 2
                    )
                }
                advanceUnpaid -> {
                    val remainingAdv = (proj.advanceDuePaise - proj.paidPaise).coerceAtLeast(0L)
                    ProjectPaymentReminder(
                        project = proj,
                        type = PaymentReminderType.ADVANCE_DUE,
                        amountDuePaise = remainingAdv,
                        detail = "Client approved scope — collect ${MoneyFormatter.formatInr(remainingAdv)} advance payment",
                        priority = 3
                    )
                }
                else -> null
            }
        }.sortedBy { it.priority }

        return PaymentReminderSummary(
            quoteAdvanceReminders = quoteAdvanceReminders,
            projectReminders = projectReminders
        )
    }

    // --- Resource Allocation Coach (Deterministic Live Workload Engine) ---

    data class StaffAllocationRecommendation(
        val draftId: String,
        val recommendedMember: Member?,
        val matchScore: Int,
        val activeDeliverablesCount: Int,
        val blockedCount: Int,
        val recentHoursLogged: Double,
        val rationale: String
    )

    fun recommendStaffAllocations(
        drafts: List<DeliverableDraft>,
        deliverables: List<Deliverable>,
        timeEntries: List<com.example.model.TimeEntry>,
        members: List<Member>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<StaffAllocationRecommendation> {
        val activeMembers = members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active }
        if (activeMembers.isEmpty()) return emptyList()
        val sevenDaysAgo = nowMillis - 7L * 86_400_000L

        val activeCountByUid = deliverables
            .filter { it.statusEnum() != DeliverableStatus.APPROVED }
            .groupingBy { it.assignedToUid }
            .eachCount()
        val blockedByUid = deliverables
            .filter { it.statusEnum() == DeliverableStatus.BLOCKED }
            .groupingBy { it.assignedToUid }
            .eachCount()
        val recentMinutesByUid = timeEntries
            .filter { it.workDateMillis >= sevenDaysAgo }
            .groupBy { it.uid }
            .mapValues { (_, list) -> list.sumOf { it.durationMinutes } }

        // Track virtual increments across drafts in the same project so one person isn't overloaded
        val virtualExtraByUid = mutableMapOf<String, Int>()

        return drafts.map { draft ->
            val roleMatched = activeMembers.filter { it.departmentId == draft.departmentId && it.jobRoleId == draft.jobRoleId }
            val deptMatched = activeMembers.filter { it.departmentId == draft.departmentId }
            val pool = when {
                roleMatched.isNotEmpty() -> roleMatched
                deptMatched.isNotEmpty() -> deptMatched
                else -> activeMembers
            }

            val best = pool.minByOrNull { m ->
                val baseActive = (activeCountByUid[m.uid] ?: 0) + (virtualExtraByUid[m.uid] ?: 0)
                val blocked = blockedByUid[m.uid] ?: 0
                val hours = (recentMinutesByUid[m.uid] ?: 0) / 60.0
                baseActive * 10.0 + blocked * 15.0 + hours * 1.5
            }

            if (best != null) {
                virtualExtraByUid[best.uid] = (virtualExtraByUid[best.uid] ?: 0) + 1
                val activeCount = activeCountByUid[best.uid] ?: 0
                val blocked = blockedByUid[best.uid] ?: 0
                val hours = ((recentMinutesByUid[best.uid] ?: 0) / 60.0)
                val exactRole = best.departmentId == draft.departmentId && best.jobRoleId == draft.jobRoleId
                val matchScore = (if (exactRole) 95 else if (best.departmentId == draft.departmentId) 80 else 65) -
                    (activeCount * 5).coerceAtMost(25) - (blocked * 8).coerceAtMost(20)
                val why = buildString {
                    append(if (exactRole) "Exact role match" else if (best.departmentId == draft.departmentId) "Same department" else "Cross-team capacity")
                    append(" • $activeCount active task")
                    if (activeCount != 1) append("s")
                    if (hours > 0.1) append(" • ${"%.1f".format(hours)}h this week")
                }
                StaffAllocationRecommendation(
                    draftId = draft.draftId,
                    recommendedMember = best,
                    matchScore = matchScore.coerceIn(30, 99),
                    activeDeliverablesCount = activeCount,
                    blockedCount = blocked,
                    recentHoursLogged = hours,
                    rationale = why
                )
            } else {
                StaffAllocationRecommendation(
                    draftId = draft.draftId,
                    recommendedMember = null,
                    matchScore = 0,
                    activeDeliverablesCount = 0,
                    blockedCount = 0,
                    recentHoursLogged = 0.0,
                    rationale = "No active approved team member available"
                )
            }
        }
    }

    // --- Cumulative Progress, Payment Trends & Monthly Revenue Forecast ---

    data class MonthlyAnalyticsPoint(
        val label: String,
        val monthStartMillis: Long,
        val cumulativeCompletionPct: Float,
        val collectedPaise: Long,
        val contractedPaise: Long,
        val forecastPaise: Long,
        val loggedMinutes: Int,
        val isForecast: Boolean = false
    )

    data class AgencyAnalyticsSeries(
        val historyPoints: List<MonthlyAnalyticsPoint>,
        val forecastPoints: List<MonthlyAnalyticsPoint>,
        val projectedNext3MonthsPaise: Long,
        val collectionRatePercent: Int,
        val averageCompletionPercent: Int
    )

    private fun deliverableProgressFraction(d: Deliverable): Float = when (d.statusEnum()) {
        DeliverableStatus.APPROVED -> 1f
        DeliverableStatus.READY_FOR_REVIEW -> 0.8f
        DeliverableStatus.IN_PROGRESS, DeliverableStatus.CHANGES_REQUESTED -> {
            val total = if (d.checklist.isNotEmpty()) d.checklist.size else d.totalChecklistCount
            val done = if (d.checklist.isNotEmpty()) d.checklist.count { it.done } else d.completedChecklistCount
            if (total > 0) (0.2f + 0.55f * (done.toFloat() / total.toFloat())).coerceIn(0.2f, 0.75f)
            else 0.35f
        }
        DeliverableStatus.BLOCKED -> 0.25f
        DeliverableStatus.NOT_STARTED -> 0f
    }

    private fun deliverableEventMillis(d: Deliverable, fallbackMillis: Long): Long {
        val ts = (d.updatedAt?.seconds ?: d.createdAt?.seconds ?: 0L) * 1000L
        return when {
            ts > 0L -> ts
            d.dueDateMillis in 1L..fallbackMillis -> d.dueDateMillis
            else -> fallbackMillis
        }
    }

    fun computeAgencyAnalyticsSeries(
        projects: List<com.example.model.Project>,
        deliverables: List<Deliverable>,
        payments: List<com.example.model.PaymentRecord>,
        timeEntries: List<com.example.model.TimeEntry>,
        nowMillis: Long = System.currentTimeMillis()
    ): AgencyAnalyticsSeries {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)

        val monthFormat = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
        val activeProjects = projects.filter { !it.deleted }

        // 5 historical months + current month = 6 bars
        val monthStarts = (-5..0).map { offset ->
            val c = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, offset) }
            c.timeInMillis
        }

        // Reconcile Project.paidPaise deltas if payments list doesn't contain matching PaymentRecord rows
        val recordedPaidByProject = payments.groupBy { it.projectId }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }
        val syntheticPaidBuckets = mutableListOf<Pair<Long, Long>>()
        activeProjects.forEach { proj ->
            val recorded = recordedPaidByProject[proj.id] ?: 0L
            val delta = (proj.paidPaise - recorded).coerceAtLeast(0L)
            if (delta > 0L) {
                val ts = ((proj.updatedAt?.seconds ?: proj.createdAt?.seconds ?: 0L) * 1000L)
                    .let { if (it > 0L) it else nowMillis }
                syntheticPaidBuckets += ts to delta
            }
        }

        val currentCompletionPct = if (deliverables.isNotEmpty()) {
            val totalWeight = deliverables.sumOf { deliverableProgressFraction(it).toDouble() }
            ((totalWeight * 100.0) / deliverables.size.toDouble()).toFloat().coerceIn(0f, 100f)
        } else if (activeProjects.isNotEmpty()) {
            val projectPcts = activeProjects.map { p ->
                when {
                    p.statusEnum() == ProjectStatus.COMPLETED -> 100f
                    p.totalDeliverables > 0 -> (p.approvedDeliverables * 100f / p.totalDeliverables).coerceIn(0f, 100f)
                    p.statusEnum() == ProjectStatus.IN_PROGRESS -> 35f
                    else -> 0f
                }
            }
            projectPcts.average().toFloat().coerceIn(0f, 100f)
        } else {
            0f
        }

        val history = monthStarts.mapIndexed { idx, startMs ->
            val nextCal = java.util.Calendar.getInstance().apply {
                timeInMillis = startMs
                add(java.util.Calendar.MONTH, 1)
            }
            val endMs = nextCal.timeInMillis
            val label = monthFormat.format(java.util.Date(startMs))

            val recordedMonthCollected = payments
                .filter { it.paymentDateMillis in startMs until endMs }
                .sumOf { it.amountPaise }
            val syntheticMonthCollected = syntheticPaidBuckets
                .filter { (ts, _) -> ts in startMs until endMs || (idx == monthStarts.lastIndex && ts >= endMs) || (idx == monthStarts.lastIndex && ts < monthStarts.first()) }
                .sumOf { (_, amt) -> amt }
            val monthCollected = recordedMonthCollected + syntheticMonthCollected

            val monthContracted = activeProjects
                .filter { p ->
                    val createdMs = ((p.createdAt?.seconds ?: p.updatedAt?.seconds ?: 0L) * 1000L)
                        .let { if (it > 0L) it else nowMillis }
                    createdMs in startMs until endMs
                }
                .sumOf { it.agreedValuePaise }
            val monthMinutes = timeEntries
                .filter { it.workDateMillis in startMs until endMs }
                .sumOf { it.durationMinutes }

            val pct = if (idx == monthStarts.lastIndex) {
                currentCompletionPct
            } else if (deliverables.isNotEmpty()) {
                val weightedByEnd = deliverables.sumOf { d ->
                    val ts = deliverableEventMillis(d, nowMillis)
                    if (ts in 1L until endMs) deliverableProgressFraction(d).toDouble() else 0.0
                }
                ((weightedByEnd * 100.0) / deliverables.size.toDouble()).toFloat().coerceIn(0f, currentCompletionPct)
            } else {
                0f
            }

            MonthlyAnalyticsPoint(
                label = label,
                monthStartMillis = startMs,
                cumulativeCompletionPct = pct,
                collectedPaise = monthCollected,
                contractedPaise = monthContracted,
                forecastPaise = 0L,
                loggedMinutes = monthMinutes,
                isForecast = false
            )
        }

        // Forecast next 3 months from outstanding balances + 3-month trailing collection average
        val trailingAvg = history.takeLast(3).map { it.collectedPaise }.average().toLong().coerceAtLeast(0L)
        val totalOutstanding = activeProjects.sumOf { it.outstandingPaise }.coerceAtLeast(0L)
        val hasPipeline = totalOutstanding > 0L || trailingAvg > 0L

        val forecast = (1..3).map { offset ->
            val c = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, offset) }
            val outstandingWeight = when (offset) {
                1 -> 0.48
                2 -> 0.32
                else -> 0.20
            }
            val trailingWeight = when (offset) {
                1 -> 0.40
                2 -> 0.30
                else -> 0.20
            }
            val projected = if (hasPipeline) {
                ((trailingAvg * trailingWeight) + (totalOutstanding * outstandingWeight)).toLong()
                    .coerceAtLeast(0L)
            } else {
                0L
            }
            val remainingPct = (100f - currentCompletionPct).coerceAtLeast(0f)
            val estCompletion = if (deliverables.isEmpty() && activeProjects.isEmpty()) {
                0f
            } else {
                (currentCompletionPct + remainingPct * (offset / 3.5f)).coerceIn(currentCompletionPct, 100f)
            }
            MonthlyAnalyticsPoint(
                label = monthFormat.format(c.time) + "*",
                monthStartMillis = c.timeInMillis,
                cumulativeCompletionPct = estCompletion,
                collectedPaise = 0L,
                contractedPaise = 0L,
                forecastPaise = projected,
                loggedMinutes = 0,
                isForecast = true
            )
        }

        val totalAgreed = activeProjects.sumOf { it.agreedValuePaise }
        val totalPaid = maxOf(activeProjects.sumOf { it.paidPaise }, payments.sumOf { it.amountPaise })
        val collectionRate = if (totalAgreed > 0L) ((totalPaid * 100L) / totalAgreed).toInt().coerceIn(0, 100) else 0

        return AgencyAnalyticsSeries(
            historyPoints = history,
            forecastPoints = forecast,
            projectedNext3MonthsPaise = forecast.sumOf { it.forecastPaise },
            collectionRatePercent = collectionRate,
            averageCompletionPercent = currentCompletionPct.toInt()
        )
    }

    data class RevenueAndInvoiceSummary(
        val completedProjectsCount: Int = 0,
        val completedProjectsRevenuePaise: Long = 0L,
        val completedProjectsCollectedPaise: Long = 0L,
        val totalInvoicesCount: Int = 0,
        val paidInvoicesCount: Int = 0,
        val paidInvoicesTotalPaise: Long = 0L,
        val pendingInvoicesCount: Int = 0,
        val pendingInvoicesTotalPaise: Long = 0L
    )

    fun computeRevenueAndInvoiceSummary(
        projects: List<com.example.model.Project>,
        invoices: List<com.example.model.InvoiceRecord>
    ): RevenueAndInvoiceSummary {
        val activeProjects = projects.filter { !it.deleted }
        val completedProjects = activeProjects.filter { it.statusEnum() == ProjectStatus.COMPLETED }
        val completedRevenue = completedProjects.sumOf { p ->
            maxOf(p.agreedValuePaise, p.paidPaise).coerceAtLeast(0L)
        }
        val completedCollected = completedProjects.sumOf { it.paidPaise.coerceAtLeast(0L) }

        val nonCancelledInvoices = invoices.filter {
            it.statusEnum() != com.example.model.InvoiceStatus.CANCELLED
        }
        val paidInvoices = nonCancelledInvoices.filter {
            it.statusEnum() == com.example.model.InvoiceStatus.PAID
        }
        val pendingInvoices = nonCancelledInvoices.filter { inv ->
            val st = inv.statusEnum()
            st == com.example.model.InvoiceStatus.ISSUED ||
                st == com.example.model.InvoiceStatus.PARTIALLY_PAID ||
                st == com.example.model.InvoiceStatus.OVERDUE ||
                st == com.example.model.InvoiceStatus.DRAFT
        }
        val paidTotal = paidInvoices.sumOf { it.totalPaise.coerceAtLeast(0L) }
        val pendingTotal = pendingInvoices.sumOf { inv ->
            val due = if (inv.balanceDuePaise > 0L) inv.balanceDuePaise else inv.totalPaise
            due.coerceAtLeast(0L)
        }

        return RevenueAndInvoiceSummary(
            completedProjectsCount = completedProjects.size,
            completedProjectsRevenuePaise = completedRevenue,
            completedProjectsCollectedPaise = completedCollected,
            totalInvoicesCount = nonCancelledInvoices.size,
            paidInvoicesCount = paidInvoices.size,
            paidInvoicesTotalPaise = paidTotal,
            pendingInvoicesCount = pendingInvoices.size,
            pendingInvoicesTotalPaise = pendingTotal
        )
    }
}
