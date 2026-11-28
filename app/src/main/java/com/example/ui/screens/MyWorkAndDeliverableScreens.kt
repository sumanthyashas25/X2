package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SyncedData
import com.example.domain.MoneyFormatter
import com.example.model.*
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DayCell(
    val dayOfMonth: Int,
    val dayKey: Long,
    val isCurrentMonth: Boolean
)

private fun startOfDayMillis(millis: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

@Composable
fun MyWorkScreen(
    state: AgencyUiState,
    onOpenDeliverable: (Deliverable) -> Unit,
    onStartDeliverable: (Deliverable) -> Unit = {},
    onClaimDepartmentTask: (Deliverable) -> Unit = {},
    onToggleTimer: (String) -> Unit = {},
    onQuickSubmitReview: (Deliverable) -> Unit = {},
    planModeRequestKey: Int = 0,
    onSavePlanItem: (StaffPlanItem, () -> Unit) -> Unit = { _, onDone -> onDone() },
    onTogglePlanCompleted: (StaffPlanItem) -> Unit = {},
    onReschedulePlanTomorrow: (StaffPlanItem) -> Unit = {},
    onCarryOverIncompletePlans: (List<StaffPlanItem>, Long) -> Unit = { _, _ -> },
    onDeletePlanItem: (String) -> Unit = {},
    onToggleFocusTimer: () -> Unit = {},
    onResetFocusTimer: () -> Unit = {},
    onSetFocusPreset: (Int) -> Unit = {},
    onLogTimeEntry: (Project, Deliverable?, Int, Boolean, Long, String) -> Unit = { _, _, _, _, _, _ -> },
    onCommitTimerToTimeEntry: (Deliverable) -> Unit = {},
    onDeleteTimeEntry: (String) -> Unit = {},
    deliverableTimerStateFlow: StateFlow<Pair<String?, Map<String, Long>>> = remember(state.activeTimerDeliverableId, state.timerSecondsByDeliverable) {
        MutableStateFlow(state.activeTimerDeliverableId to state.timerSecondsByDeliverable)
    },
    focusTimerStateFlow: StateFlow<Triple<Int, Long, Boolean>> = remember(state.focusPresetMinutes, state.focusSecondsRemaining, state.isFocusTimerRunning) {
        MutableStateFlow(Triple(state.focusPresetMinutes, state.focusSecondsRemaining, state.isFocusTimerRunning))
    }
) {
    val context = LocalContext.current
    val palette = XMaxTheme.colors
    val deliverableTimerState by deliverableTimerStateFlow.collectAsStateWithLifecycle()
    var showLogHoursDialog by remember { mutableStateOf(false) }
    val member = state.myMember
    val uid = member?.uid.orEmpty()
    val myDeptId = member?.departmentId.orEmpty()
    var filter by remember { mutableStateOf<DeliverableStatus?>(null) }
    var quickDueFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showQuickWeekStrip by remember { mutableStateOf(false) }

    val todayStart = remember { startOfDayMillis(System.currentTimeMillis()) }
    var selectedDayMillis by remember { mutableStateOf<Long?>(null) }
    var calendarMonthOffset by remember { mutableIntStateOf(0) }
    val photoByUid = remember(state.members) { state.members.associate { it.uid to it.photoBase64 } }

    val allMyDeliverables = remember(state.deliverables, uid) {
        state.deliverables
            .filter { it.assignedToUid == uid }
            .sortedBy { if (it.dueDateMillis > 0L) it.dueDateMillis else Long.MAX_VALUE }
    }

    val deptQueueTasks = remember(state.deliverables, myDeptId) {
        if (myDeptId.isBlank()) emptyList()
        else state.deliverables.filter {
            it.assignmentStatusEnum() == AssignmentStatus.DEPT_QUEUE &&
                it.assignedToUid.isBlank() &&
                it.departmentId == myDeptId &&
                it.statusEnum() != DeliverableStatus.APPROVED
        }.sortedBy { if (it.dueDateMillis > 0L) it.dueDateMillis else Long.MAX_VALUE }
    }

    val nowMillis = remember { System.currentTimeMillis() }
    val endOfTodayMillis = todayStart + 86_400_000L - 1L
    val overdueCount = remember(allMyDeliverables, todayStart) {
        allMyDeliverables.count {
            it.statusEnum() != DeliverableStatus.APPROVED && it.dueDateMillis in 1 until todayStart
        }
    }
    val dueTodayCount = remember(allMyDeliverables, todayStart, endOfTodayMillis) {
        allMyDeliverables.count {
            it.statusEnum() != DeliverableStatus.APPROVED && it.dueDateMillis in todayStart..endOfTodayMillis
        }
    }
    val urgentIssueCount = remember(allMyDeliverables) {
        allMyDeliverables.count {
            it.statusEnum() == DeliverableStatus.BLOCKED || it.statusEnum() == DeliverableStatus.CHANGES_REQUESTED
        }
    }

    val dueDotColorByDay = remember(allMyDeliverables) {
        val map = mutableMapOf<Long, Color>()
        allMyDeliverables.forEach { d ->
            if (d.dueDateMillis > 0L) {
                val key = startOfDayMillis(d.dueDateMillis)
                val dotColor = when (d.statusEnum()) {
                    DeliverableStatus.BLOCKED, DeliverableStatus.CHANGES_REQUESTED -> CrimsonBlocked
                    DeliverableStatus.APPROVED -> EmeraldApproved
                    DeliverableStatus.READY_FOR_REVIEW -> WarmAlarmAmber
                    else -> palette.lavenderInk
                }
                if (!map.containsKey(key) || dotColor == CrimsonBlocked) {
                    map[key] = dotColor
                }
            }
        }
        map
    }

    val myDeliverables = remember(allMyDeliverables, filter, quickDueFilter, searchQuery, selectedDayMillis, todayStart, endOfTodayMillis) {
        allMyDeliverables
            .filter { filter == null || it.statusEnum() == filter }
            .filter { d ->
                when (quickDueFilter) {
                    "TODAY" -> d.statusEnum() != DeliverableStatus.APPROVED && d.dueDateMillis in todayStart..endOfTodayMillis
                    "OVERDUE" -> d.statusEnum() != DeliverableStatus.APPROVED && d.dueDateMillis in 1 until todayStart
                    else -> true
                }
            }
            .filter { d ->
                selectedDayMillis == null || (d.dueDateMillis > 0L && startOfDayMillis(d.dueDateMillis) == selectedDayMillis)
            }
            .filter { d ->
                searchQuery.isBlank() ||
                    d.title.contains(searchQuery, ignoreCase = true) ||
                    d.projectTitle.contains(searchQuery, ignoreCase = true) ||
                    d.clientCompanyName.contains(searchQuery, ignoreCase = true)
            }
    }

    val approvedCount = remember(allMyDeliverables) {
        allMyDeliverables.count { it.statusEnum() == DeliverableStatus.APPROVED }
    }
    val inProgressCount = remember(allMyDeliverables) {
        allMyDeliverables.count {
            it.statusEnum() in listOf(
                DeliverableStatus.IN_PROGRESS,
                DeliverableStatus.READY_FOR_REVIEW,
                DeliverableStatus.CHANGES_REQUESTED
            )
        }
    }
    val completionPct = remember(allMyDeliverables, approvedCount) {
        if (allMyDeliverables.isEmpty()) 0 else ((approvedCount * 100f) / allMyDeliverables.size).toInt().coerceIn(0, 100)
    }

    val displayCal = remember(calendarMonthOffset, selectedDayMillis) {
        Calendar.getInstance().apply {
            if (selectedDayMillis != null && calendarMonthOffset == 0) {
                timeInMillis = selectedDayMillis!!
            } else {
                add(Calendar.MONTH, calendarMonthOffset)
            }
        }
    }
    val headerDateText = remember(displayCal.timeInMillis) {
        SimpleDateFormat("d MMM, yy", Locale.getDefault()).format(Date(displayCal.timeInMillis))
    }
    val headerWeekdayText = remember(displayCal.timeInMillis) {
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(displayCal.timeInMillis))
    }

    val weekDays = remember(displayCal.timeInMillis) {
        val c = Calendar.getInstance().apply {
            timeInMillis = displayCal.timeInMillis
            firstDayOfWeek = Calendar.SUNDAY
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        List(7) {
            val dayStart = startOfDayMillis(c.timeInMillis)
            val cell = DayCell(c.get(Calendar.DAY_OF_MONTH), dayStart, true)
            c.add(Calendar.DAY_OF_MONTH, 1)
            cell
        }
    }

    val monthCells = remember(displayCal.get(Calendar.YEAR), displayCal.get(Calendar.MONTH)) {
        val c = Calendar.getInstance().apply {
            timeInMillis = displayCal.timeInMillis
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val firstDayOffset = (c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
        val maxDays = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cells = mutableListOf<DayCell?>()
        repeat(firstDayOffset) { cells.add(null) }
        for (day in 1..maxDays) {
            c.set(Calendar.DAY_OF_MONTH, day)
            cells.add(DayCell(day, startOfDayMillis(c.timeInMillis), true))
        }
        while (cells.size % 7 != 0) cells.add(null)
        cells.chunked(7)
    }

    var showPlanMode by rememberSaveable { mutableStateOf(planModeRequestKey > 0) }
    LaunchedEffect(planModeRequestKey) {
        if (planModeRequestKey > 0) {
            showPlanMode = true
        }
    }

    if (showPlanMode) {
        StaffPlanCalendarSection(
            state = state,
            focusTimerStateFlow = focusTimerStateFlow,
            onSavePlanItem = onSavePlanItem,
            onTogglePlanCompleted = onTogglePlanCompleted,
            onRescheduleTomorrow = onReschedulePlanTomorrow,
            onCarryOverIncomplete = onCarryOverIncompletePlans,
            onDeletePlanItem = onDeletePlanItem,
            onOpenDeliverable = onOpenDeliverable,
            onToggleFocusTimer = onToggleFocusTimer,
            onResetFocusTimer = onResetFocusTimer,
            onSetFocusPreset = onSetFocusPreset,
            topModeSwitcher = {
                MyWorkPlanSwitcher(
                    showPlanMode = true,
                    taskCount = allMyDeliverables.size,
                    planCount = state.staffPlanItems.size,
                    onSelectPlan = { showPlanMode = it }
                )
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pastelAmbientBackdrop()
            .testTag("my_work_lazy_list"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            MyWorkPlanSwitcher(
                showPlanMode = false,
                taskCount = allMyDeliverables.size,
                planCount = state.staffPlanItems.size,
                onSelectPlan = { showPlanMode = it }
            )
        }
        // 1. Primary Page Title + Compact Inline Productivity Summary Pill
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = if (selectedDayMillis != null) "Day Schedule (${myDeliverables.size})" else "My Work (${myDeliverables.size})",
                        style = XMaxType.sectionHeading.tabular(),
                        color = palette.textPrimary
                    )
                    Text(
                        text = "$inProgressCount active • $approvedCount done",
                        style = XMaxType.footnoteMedium.tabular(),
                        color = palette.textSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filter != null || quickDueFilter != null || selectedDayMillis != null || searchQuery.isNotBlank()) {
                        TextButton(
                            onClick = {
                                filter = null
                                quickDueFilter = null
                                selectedDayMillis = null
                                searchQuery = ""
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Reset", style = XMaxType.footnoteSemibold, color = palette.lavenderInk)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val strokeWidth = 3.2.dp.toPx()
                                    drawArc(
                                        color = palette.softVioletBg,
                                        startAngle = 140f,
                                        sweepAngle = 260f,
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                    drawArc(
                                        color = palette.lavenderInk,
                                        startAngle = 140f,
                                        sweepAngle = 260f * (completionPct / 100f),
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Productivity",
                                    style = XMaxType.caption1Medium,
                                    color = palette.textSecondary
                                )
                                Text(
                                    text = "$completionPct%",
                                    style = XMaxType.footnoteSemibold.tabular(),
                                    color = palette.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Staff Priority Summary Strip + Plan Calendar Shortcut + Collapsible Week Strip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("staff_priority_strip"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = quickDueFilter == "OVERDUE",
                    onClick = {
                        AppHapticsAndNotifier.triggerTickHaptic(context)
                        quickDueFilter = if (quickDueFilter == "OVERDUE") null else "OVERDUE"
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CrimsonBlocked,
                        selectedLabelColor = PureWhite,
                        containerColor = if (overdueCount > 0) palette.blushBg else palette.mintBg,
                        labelColor = if (overdueCount > 0) palette.blushInk else palette.mintInk
                    ),
                    border = null,
                    label = {
                        Text(
                            if (overdueCount > 0) "Overdue ($overdueCount)" else "No Overdue",
                            style = XMaxType.footnoteMedium.tabular()
                        )
                    }
                )
                FilterChip(
                    selected = quickDueFilter == "TODAY",
                    onClick = {
                        AppHapticsAndNotifier.triggerTickHaptic(context)
                        quickDueFilter = if (quickDueFilter == "TODAY") null else "TODAY"
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.buttonPrimaryBg,
                        selectedLabelColor = palette.buttonPrimaryFg,
                        containerColor = if (dueTodayCount > 0) palette.butterBg else palette.surfaceMuted,
                        labelColor = if (dueTodayCount > 0) palette.butterInk else palette.textPrimary
                    ),
                    border = null,
                    label = { Text("Due Today ($dueTodayCount)", style = XMaxType.footnoteMedium.tabular()) }
                )
                if (urgentIssueCount > 0) {
                    StatusPill(
                        label = "Attention ($urgentIssueCount)",
                        containerColor = palette.blushBg,
                        contentColor = palette.blushInk
                    )
                }
                if (deptQueueTasks.isNotEmpty()) {
                    StatusPill(
                        label = "Dept Queue (${deptQueueTasks.size})",
                        containerColor = palette.lavenderBg,
                        contentColor = palette.lavenderInk
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "$headerDateText • $headerWeekdayText",
                                style = XMaxType.subheadlineSemibold.tabular(),
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Tap Plan tab above for monthly task blocks",
                                style = XMaxType.caption1,
                                color = palette.textSecondary
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    showPlanMode = true
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = palette.lavenderBg
                            ) {
                                Text(
                                    text = "Plan →",
                                    style = XMaxType.footnoteSemibold,
                                    color = palette.lavenderInk,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    showQuickWeekStrip = !showQuickWeekStrip
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (showQuickWeekStrip) palette.buttonPrimaryBg else palette.surfaceMuted,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("toggle_calendar_view_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (showQuickWeekStrip) Icons.Filled.ExpandLess else Icons.Filled.DateRange,
                                        contentDescription = if (showQuickWeekStrip) "Hide due dates" else "Filter by due date",
                                        tint = if (showQuickWeekStrip) palette.buttonPrimaryFg else palette.textPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showQuickWeekStrip,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Filter tasks by due day",
                                    style = XMaxType.caption1Medium,
                                    color = palette.textSecondary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(
                                        onClick = { calendarMonthOffset -= 1 },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = palette.textPrimary)
                                    }
                                    IconButton(
                                        onClick = { calendarMonthOffset += 1 },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = palette.textPrimary)
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { dLabel ->
                                    Text(
                                        text = dLabel,
                                        style = XMaxType.caption1Medium,
                                        color = palette.textSecondary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                weekDays.forEach { cell ->
                                    CalendarDayItem(
                                        cell = cell,
                                        isSelected = selectedDayMillis == cell.dayKey || (selectedDayMillis == null && cell.dayKey == todayStart),
                                        dotColor = dueDotColorByDay[cell.dayKey],
                                        onClick = {
                                            AppHapticsAndNotifier.triggerTickHaptic(context)
                                            selectedDayMillis = if (selectedDayMillis == cell.dayKey) null else cell.dayKey
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (selectedDayMillis != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Due on ${MoneyFormatter.formatDate(selectedDayMillis!!)}",
                                        style = XMaxType.caption1Semibold.tabular(),
                                        color = palette.lavenderInk
                                    )
                                    TextButton(
                                        onClick = { selectedDayMillis = null },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Show All Dates", style = XMaxType.caption1Semibold, color = palette.textPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Search + Status Filter Chips
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search assigned tasks or clients…"
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.buttonPrimaryBg,
                        selectedLabelColor = palette.buttonPrimaryFg,
                        containerColor = palette.surface,
                        labelColor = palette.textPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == null,
                        borderColor = palette.outline
                    ),
                    label = { Text("All", style = XMaxType.footnoteMedium) }
                )
                listOf(
                    DeliverableStatus.NOT_STARTED,
                    DeliverableStatus.IN_PROGRESS,
                    DeliverableStatus.BLOCKED,
                    DeliverableStatus.CHANGES_REQUESTED,
                    DeliverableStatus.READY_FOR_REVIEW,
                    DeliverableStatus.APPROVED
                ).forEach { st ->
                    FilterChip(
                        selected = filter == st,
                        onClick = { filter = st },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.buttonPrimaryBg,
                            selectedLabelColor = palette.buttonPrimaryFg,
                            containerColor = palette.surface,
                            labelColor = palette.textPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = filter == st,
                            borderColor = palette.outline
                        ),
                        label = { Text(st.label, style = XMaxType.footnoteMedium) }
                    )
                }
            }
        }

        // 4. Department Queue Pickup Section (for unclaimed tasks in staff's own department)
        if (deptQueueTasks.isNotEmpty()) {
            item {
                SectionHeader(title = "Department Queue (${deptQueueTasks.size})")
            }
            items(deptQueueTasks.size, key = { idx -> "queue_${deptQueueTasks[idx].id}" }) { idx ->
                val qTask = deptQueueTasks[idx]
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.lavenderBg,
                    border = BorderStroke(1.dp, palette.lavenderInk.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = formatTitleCase(qTask.title),
                                style = XMaxType.headline,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatTitleCase(qTask.projectTitle)} • ${MoneyFormatter.formatDate(qTask.dueDateMillis)}",
                                style = XMaxType.footnote.tabular(),
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = {
                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                onClaimDepartmentTask(qTask)
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("claim_task_${qTask.id}")
                        ) {
                            Text("Claim Task", style = XMaxType.buttonCompact)
                        }
                    }
                }
            }
        }

        // 5. Pastel Color-Coded Deliverable Schedule Cards
        if (myDeliverables.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Outlined.TaskAlt,
                    "No deliverables match this filter",
                    "Your assigned deliverables appear here ordered by due date with checklists and update actions."
                )
            }
        } else {
            items(myDeliverables.size, key = { idx -> myDeliverables[idx].id }) { idx ->
                val d = myDeliverables[idx]
                val tintBg = when (d.statusEnum()) {
                    DeliverableStatus.BLOCKED, DeliverableStatus.CHANGES_REQUESTED -> palette.blushBg
                    DeliverableStatus.APPROVED -> palette.mintBg
                    DeliverableStatus.READY_FOR_REVIEW -> palette.peachBg
                    DeliverableStatus.IN_PROGRESS -> palette.skyBg
                    DeliverableStatus.NOT_STARTED -> palette.lavenderBg
                }
                val elapsedSecs = deliverableTimerState.second[d.id] ?: 0L
                val isTimerRunning = deliverableTimerState.first == d.id
                PastelDeliverableScheduleCard(
                    deliverable = d,
                    cardColor = tintBg,
                    assigneePhotoBase64 = photoByUid[d.assignedToUid].orEmpty(),
                    todayStartMillis = todayStart,
                    nowMillis = nowMillis,
                    elapsedTimerSeconds = elapsedSecs,
                    isTimerRunning = isTimerRunning,
                    onToggleTimer = { onToggleTimer(d.id) },
                    onCommitTimerToTimeEntry = { onCommitTimerToTimeEntry(d) },
                    onSlideStart = { onStartDeliverable(d) },
                    onQuickSubmitReview = { onQuickSubmitReview(d) },
                    onClick = { onOpenDeliverable(d) }
                )
            }
        }

        // 6. Timesheet & Logged Hours Card
        item {
            val weekCutoff = todayStart - 6L * 86_400_000L
            val myRecentEntries = remember(state.timeEntries, uid, state.isSuperAdmin) {
                state.timeEntries.filter { it.uid == uid || state.isSuperAdmin || state.isAdmin }
            }
            val weekMins = remember(myRecentEntries, weekCutoff) {
                myRecentEntries.filter { it.workDateMillis >= weekCutoff }.sumOf { it.durationMinutes }
            }
            val weekBillablePaise = remember(myRecentEntries, weekCutoff) {
                myRecentEntries.filter { it.workDateMillis >= weekCutoff }.sumOf { it.billableAmountPaise() }
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("my_work_timesheet_card")
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Timesheet & Hours", style = XMaxType.headline, color = palette.textPrimary)
                            Text(
                                "${"%.1f".format(weekMins / 60.0)}h this week • ${MoneyFormatter.formatInr(weekBillablePaise)} billable",
                                style = XMaxType.footnote.tabular(),
                                color = palette.textSecondary
                            )
                        }
                        Button(
                            onClick = {
                                AppHapticsAndNotifier.triggerTickHaptic(context)
                                showLogHoursDialog = true
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(40.dp).testTag("open_log_hours_dialog")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Log Hours", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                        }
                    }

                    if (myRecentEntries.isEmpty()) {
                        Text(
                            "No hours logged yet. Start a deliverable timer or tap Log Hours to record billable project time.",
                            style = XMaxType.footnote,
                            color = palette.textSecondary
                        )
                    } else {
                        myRecentEntries.take(8).forEach { entry ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = palette.surfaceMuted,
                                modifier = Modifier.fillMaxWidth().testTag("time_entry_${entry.id}")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "${formatTitleCase(entry.projectTitle)} • ${entry.durationMinutes}m",
                                            style = XMaxType.subheadlineSemibold.tabular(),
                                            color = palette.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = buildString {
                                                if (entry.deliverableTitle.isNotBlank()) append("${formatTitleCase(entry.deliverableTitle)} • ")
                                                append(MoneyFormatter.formatDate(entry.workDateMillis))
                                                if (entry.billable) append(" • ${MoneyFormatter.formatInr(entry.billableAmountPaise())}")
                                            },
                                            style = XMaxType.caption1.tabular(),
                                            color = palette.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (entry.notes.isNotBlank()) {
                                            Text(entry.notes, style = XMaxType.caption1, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (entry.uid == uid || state.isSuperAdmin) {
                                        IconButton(
                                            onClick = { onDeleteTimeEntry(entry.id) },
                                            modifier = Modifier.size(40.dp).testTag("delete_time_${entry.id}")
                                        ) {
                                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete time entry", tint = CrimsonBlocked, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogHoursDialog) {
        val activeProjects = remember(state.projects) { state.projects.filter { !it.deleted } }
        var selectedProjectId by remember(activeProjects) { mutableStateOf(activeProjects.firstOrNull()?.id.orEmpty()) }
        val projectDeliverables = remember(state.deliverables, selectedProjectId) {
            state.deliverables.filter { it.projectId == selectedProjectId }
        }
        var selectedDeliverableId by remember(selectedProjectId) { mutableStateOf("") }
        var minutesText by remember { mutableStateOf("60") }
        var billable by remember { mutableStateOf(true) }
        var hourlyRateRupees by remember { mutableStateOf("1500") }
        var notes by remember { mutableStateOf("") }
        val selectedProj = activeProjects.firstOrNull { it.id == selectedProjectId }
        val selectedDeliv = projectDeliverables.firstOrNull { it.id == selectedDeliverableId }

        AlertDialog(
            onDismissRequest = { showLogHoursDialog = false },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Log Project Hours", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (activeProjects.isEmpty()) {
                        Text("No active projects available yet.", style = XMaxType.footnote, color = palette.textSecondary)
                    } else {
                        Text("Project", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            activeProjects.forEach { proj ->
                                FilterChip(
                                    selected = selectedProjectId == proj.id,
                                    onClick = { selectedProjectId = proj.id },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.buttonPrimaryBg,
                                        selectedLabelColor = palette.buttonPrimaryFg,
                                        containerColor = palette.surfaceMuted,
                                        labelColor = palette.textPrimary
                                    ),
                                    border = null,
                                    label = { Text(formatTitleCase(proj.title), style = XMaxType.caption1Semibold, maxLines = 1) }
                                )
                            }
                        }
                        if (projectDeliverables.isNotEmpty()) {
                            Text("Deliverable (optional)", style = XMaxType.caption1Semibold, color = palette.textSecondary)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = selectedDeliverableId.isBlank(),
                                    onClick = { selectedDeliverableId = "" },
                                    shape = RoundedCornerShape(999.dp),
                                    label = { Text("General", style = XMaxType.caption1) }
                                )
                                projectDeliverables.forEach { deliv ->
                                    FilterChip(
                                        selected = selectedDeliverableId == deliv.id,
                                        onClick = { selectedDeliverableId = deliv.id },
                                        shape = RoundedCornerShape(999.dp),
                                        label = { Text(formatTitleCase(deliv.title), style = XMaxType.caption1, maxLines = 1) }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { minutesText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Duration (minutes) *", style = XMaxType.inputLabel) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("log_hours_minutes_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Billable Time", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                            Switch(checked = billable, onCheckedChange = { billable = it })
                        }
                        if (billable) {
                            OutlinedTextField(
                                value = hourlyRateRupees,
                                onValueChange = { hourlyRateRupees = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Hourly Rate (₹)", style = XMaxType.inputLabel) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Work Notes", style = XMaxType.inputLabel) },
                            minLines = 2,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                val mins = minutesText.toIntOrNull() ?: 0
                val ratePaise = (hourlyRateRupees.toLongOrNull() ?: 0L) * 100L
                Button(
                    onClick = {
                        if (selectedProj != null && mins > 0) {
                            onLogTimeEntry(selectedProj, selectedDeliv, mins, billable, ratePaise, notes)
                            showLogHoursDialog = false
                        }
                    },
                    enabled = selectedProj != null && mins > 0,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                    modifier = Modifier.testTag("submit_log_hours_button")
                ) { Text("Save Hours", style = XMaxType.buttonCompact) }
            },
            dismissButton = {
                TextButton(onClick = { showLogHoursDialog = false }) {
                    Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun MyWorkPlanSwitcher(
    showPlanMode: Boolean,
    taskCount: Int,
    planCount: Int,
    onSelectPlan: (Boolean) -> Unit
) {
    val palette = XMaxTheme.colors
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("my_work_plan_switcher")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                onClick = { onSelectPlan(false) },
                shape = RoundedCornerShape(999.dp),
                color = if (!showPlanMode) palette.buttonPrimaryBg else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("my_work_mode_tasks")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.TaskAlt,
                        contentDescription = null,
                        tint = if (!showPlanMode) palette.buttonPrimaryFg else palette.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (taskCount > 0) "Tasks ($taskCount)" else "Tasks",
                        style = XMaxType.buttonCompact.tabular(),
                        color = if (!showPlanMode) palette.buttonPrimaryFg else palette.textPrimary
                    )
                }
            }

            Surface(
                onClick = { onSelectPlan(true) },
                shape = RoundedCornerShape(999.dp),
                color = if (showPlanMode) Color(0xFF6C3BF5) else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("my_work_mode_plan")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = if (showPlanMode) PureWhite else palette.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (planCount > 0) "Plan ($planCount)" else "Plan",
                        style = XMaxType.buttonCompact.tabular(),
                        color = if (showPlanMode) PureWhite else palette.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayItem(
    cell: DayCell,
    isSelected: Boolean,
    dotColor: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = XMaxTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isSelected) palette.buttonPrimaryBg else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cell.dayOfMonth.toString(),
                style = (if (isSelected) XMaxType.footnoteSemibold else XMaxType.footnoteMedium).tabular(),
                color = if (isSelected) palette.buttonPrimaryFg else palette.textPrimary
            )
        }
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor ?: Color.Transparent)
        )
    }
}

private fun formatCountdownLabel(dueMillis: Long, todayStartMillis: Long, nowMillis: Long): Pair<String, Boolean> {
    if (dueMillis <= 0L) return "Scheduled" to false
    val diffDays = ((startOfDayMillis(dueMillis) - todayStartMillis) / 86_400_000L).toInt()
    return when {
        diffDays < 0 -> "Overdue ${-diffDays}d" to true
        diffDays == 0 -> "Due Today" to false
        diffDays == 1 -> "Due Tomorrow" to false
        else -> "Due in ${diffDays}d" to false
    }
}

private fun formatElapsedTimer(seconds: Long): String {
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) "%d:%02d:%02d".format(hrs, mins, secs) else "%02d:%02d".format(mins, secs)
}

@Composable
private fun PastelDeliverableScheduleCard(
    deliverable: Deliverable,
    cardColor: Color,
    assigneePhotoBase64: String = "",
    todayStartMillis: Long = startOfDayMillis(System.currentTimeMillis()),
    nowMillis: Long = System.currentTimeMillis(),
    elapsedTimerSeconds: Long = 0L,
    isTimerRunning: Boolean = false,
    onToggleTimer: () -> Unit = {},
    onCommitTimerToTimeEntry: () -> Unit = {},
    onSlideStart: () -> Unit = {},
    onQuickSubmitReview: () -> Unit = {},
    onClick: () -> Unit
) {
    val palette = XMaxTheme.colors
    val st = deliverable.statusEnum()
    val (pillBg, pillFg) = statusColorsForDeliverable(st)
    val doneCount = deliverable.checklist.count { it.done }
    val totalCount = deliverable.checklist.size
    val avatarMembers = buildList {
        if (deliverable.assignedToName.isNotBlank()) add(deliverable.assignedToName to assigneePhotoBase64)
        if (deliverable.clientCompanyName.isNotBlank()) add(deliverable.clientCompanyName to "")
    }
    val (countdownText, isOverdue) = formatCountdownLabel(deliverable.dueDateMillis, todayStartMillis, nowMillis)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = formatTitleCase(deliverable.title),
                            style = XMaxType.headline,
                            color = palette.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatTitleCase(deliverable.projectTitle)} • ${formatTitleCase(deliverable.departmentName)}",
                            style = XMaxType.footnote,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusPill(st.label, pillBg, pillFg)
                        if (st != DeliverableStatus.APPROVED) {
                            StatusPill(
                                label = countdownText,
                                containerColor = if (isOverdue) CrimsonBlocked else palette.surface,
                                contentColor = if (isOverdue) PureWhite else palette.textPrimary
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = palette.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = MoneyFormatter.formatDate(deliverable.dueDateMillis) +
                                if (totalCount > 0) " • $doneCount/$totalCount steps" else "",
                            style = XMaxType.footnoteMedium.tabular(),
                            color = palette.textPrimary
                        )
                    }
                    if (avatarMembers.isNotEmpty()) {
                        OverlappingAvatarMembers(
                            members = avatarMembers,
                            avatarSize = 26.dp,
                            borderColor = cardColor
                        )
                    }
                }
            }

            if (st == DeliverableStatus.NOT_STARTED) {
                SlideToActionButton(
                    label = "SLIDE TO START WORK",
                    onSlideComplete = onSlideStart,
                    testTag = "slide_start_${deliverable.id}"
                )
            } else if (st == DeliverableStatus.IN_PROGRESS || st == DeliverableStatus.CHANGES_REQUESTED) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = onToggleTimer,
                        shape = RoundedCornerShape(999.dp),
                        color = if (isTimerRunning) palette.buttonPrimaryBg else palette.surface,
                        border = if (isTimerRunning) null else BorderStroke(1.dp, palette.outline),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("timer_toggle_${deliverable.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isTimerRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isTimerRunning) "Pause timer" else "Start timer",
                                tint = if (isTimerRunning) palette.buttonPrimaryFg else palette.textPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isTimerRunning || elapsedTimerSeconds > 0L) formatElapsedTimer(elapsedTimerSeconds) else "Start Timer",
                                style = if (isTimerRunning || elapsedTimerSeconds > 0L) XMaxType.monoTimer else XMaxType.footnoteSemibold,
                                color = if (isTimerRunning) palette.buttonPrimaryFg else palette.textPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (elapsedTimerSeconds > 0L) {
                        OutlinedButton(
                            onClick = onCommitTimerToTimeEntry,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, palette.outline),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("save_timesheet_${deliverable.id}")
                        ) {
                            Text("Save to Timesheet", style = XMaxType.caption1Semibold, color = palette.textPrimary, maxLines = 1, softWrap = false)
                        }
                    }

                    if (totalCount == 0 || doneCount == totalCount) {
                        Button(
                            onClick = onQuickSubmitReview,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("quick_submit_review_${deliverable.id}")
                        ) {
                            Text("Submit Review", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeliverableDetailDialog(
    state: AgencyUiState,
    deliverable: Deliverable,
    updatesFlow: Flow<SyncedData<List<DeliverableUpdate>>>,
    onSubmitProgress: (Deliverable, DeliverableStatus, List<ChecklistItem>, String, String, String, String) -> Unit,
    onReviewDeliverable: (Deliverable, UpdateActionType, String) -> Unit,
    onStartWork: (Deliverable) -> Unit = {},
    onToggleTimer: (String) -> Unit = {},
    onResetTimer: (String) -> Unit = {},
    onCommitTimerToTimeEntry: (Deliverable) -> Unit = {},
    onDismiss: () -> Unit,
    deliverableTimerStateFlow: StateFlow<Pair<String?, Map<String, Long>>> = remember(state.activeTimerDeliverableId, state.timerSecondsByDeliverable) {
        MutableStateFlow(state.activeTimerDeliverableId to state.timerSecondsByDeliverable)
    }
) {
    val context = LocalContext.current
    val palette = XMaxTheme.colors
    val updatesSynced by updatesFlow.collectAsStateWithLifecycle(initialValue = SyncedData(emptyList(), false))
    val deliverableTimerState by deliverableTimerStateFlow.collectAsStateWithLifecycle()
    val uid = state.myMember?.uid.orEmpty()
    val isAssignee = deliverable.assignedToUid == uid
    val canApprove = state.hasPerm(AppPermission.APPROVE_DELIVERABLES)

    var detailTab by remember(deliverable.id) { mutableStateOf(0) } // 0 = Details & Timeline, 1 = Checklist & Review
    var checklist by remember(deliverable.id) { mutableStateOf(deliverable.checklist) }
    var selectedStatus by remember(deliverable.id) {
        mutableStateOf(
            if (deliverable.statusEnum() in listOf(DeliverableStatus.NOT_STARTED, DeliverableStatus.IN_PROGRESS, DeliverableStatus.BLOCKED, DeliverableStatus.READY_FOR_REVIEW)) {
                deliverable.statusEnum()
            } else {
                DeliverableStatus.IN_PROGRESS
            }
        )
    }
    var note by remember(deliverable.id) { mutableStateOf("") }
    var previewUrl by remember(deliverable.id) { mutableStateOf(deliverable.previewUrl) }
    var blockedReason by remember(deliverable.id) { mutableStateOf(deliverable.blockedReason) }
    var helpNeeded by remember(deliverable.id) { mutableStateOf(deliverable.helpNeeded) }
    var reviewFeedback by remember { mutableStateOf("") }
    var savedFeedbackBanner by remember(deliverable.id) { mutableStateOf<String?>(null) }
    var justSavedButton by remember(deliverable.id) { mutableStateOf(false) }

    LaunchedEffect(savedFeedbackBanner) {
        if (!savedFeedbackBanner.isNullOrBlank()) {
            delay(3800L)
            savedFeedbackBanner = null
            justSavedButton = false
        }
    }

    val st = deliverable.statusEnum()
    val (stBg, stFg) = statusColorsForDeliverable(st)
    val started = st != DeliverableStatus.NOT_STARTED
    val approved = st == DeliverableStatus.APPROVED
    val ownerPhoto = state.members.firstOrNull { it.uid == deliverable.assignedToUid }?.photoBase64.orEmpty()
    val elapsedSecs = deliverableTimerState.second[deliverable.id] ?: 0L
    val isTimerRunning = deliverableTimerState.first == deliverable.id

    val timeFmt = remember { SimpleDateFormat("h:mma", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    FullScreenAgencyDialog(onDismissRequest = onDismiss) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.canvas
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .imePadding()
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = navBottom.coerceAtLeast(20.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                    }
                    Text("Deliverable Details", style = XMaxType.headline, color = palette.textPrimary)
                    Spacer(Modifier.size(48.dp))
                }

                AnimatedVisibility(
                    visible = !savedFeedbackBanner.isNullOrBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = palette.mintBg,
                        border = BorderStroke(1.dp, EmeraldApproved.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("deliverable_saved_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldApproved, modifier = Modifier.size(20.dp))
                            Text(
                                text = savedFeedbackBanner.orEmpty(),
                                style = XMaxType.footnoteSemibold,
                                color = palette.mintInk,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(palette.surfaceMuted),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Deliverable", style = XMaxType.caption1Medium, color = palette.textSecondary)
                        Text(
                            text = formatTitleCase(deliverable.title),
                            style = XMaxType.headline,
                            color = palette.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusPill(st.label, stBg, stFg)
                }

                if (isAssignee && st == DeliverableStatus.NOT_STARTED) {
                    SlideToActionButton(
                        label = "SLIDE TO START WORK",
                        onSlideComplete = {
                            AppHapticsAndNotifier.triggerCompletionVibration(context)
                            onStartWork(deliverable)
                            selectedStatus = DeliverableStatus.IN_PROGRESS
                            savedFeedbackBanner = "Progress saved — started work (In Progress)"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "dialog_slide_start_button"
                    )
                }

                StepProgressTimeline(
                    steps = listOf(
                        TimelineStep("Assigned", deliverable.departmentName, completed = true),
                        TimelineStep("In Progress", "${checklist.count { it.done }}/${checklist.size}", completed = started),
                        TimelineStep("Approved", MoneyFormatter.formatDate(deliverable.dueDateMillis), completed = approved)
                    )
                )

                // Segmented Pill Switcher
                SegmentedPillTabs(
                    options = listOf("Details & Timeline", if (isAssignee) "Checklist & Update" else "Review (${updatesSynced.data.size})"),
                    selectedIndex = detailTab,
                    onSelect = { detailTab = it }
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("deliverable_detail_list"),
                    contentPadding = PaddingValues(bottom = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (detailTab == 0) {
                        item {
                            val rows = buildList {
                                add(DetailKeyValueRow("Client", deliverable.clientCompanyName))
                                add(DetailKeyValueRow("Project", deliverable.projectTitle))
                                add(DetailKeyValueRow("Department", "${deliverable.departmentName} • ${deliverable.jobRoleName}"))
                                add(DetailKeyValueRow("Due Date", MoneyFormatter.formatDate(deliverable.dueDateMillis)))
                                add(DetailKeyValueRow("Revisions", "${deliverable.clientRevisionsUsed}/${deliverable.clientRevisionLimit} rounds used"))
                                if (deliverable.blockedReason.isNotBlank()) {
                                    add(DetailKeyValueRow("Blocked Note", deliverable.blockedReason, isWarning = true))
                                } else if (deliverable.latestFeedback.isNotBlank()) {
                                    add(DetailKeyValueRow("Note", deliverable.latestFeedback, isWarning = st == DeliverableStatus.CHANGES_REQUESTED))
                                }
                            }
                            DetailKeyValueCard(
                                title = "Delivery Details",
                                icon = Icons.Outlined.LocalShipping,
                                rows = rows
                            )
                        }

                        // Assignee Card
                        item {
                            val ownerName = deliverable.assignedToName.ifBlank { deliverable.assignmentStatusEnum().label }
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = palette.surfaceMuted,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    StudioAvatar(name = ownerName, size = 44.dp, photoBase64 = ownerPhoto)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(formatTitleCase(ownerName), style = XMaxType.headline, color = palette.textPrimary)
                                        Text("${formatTitleCase(deliverable.jobRoleName)} • Assignee", style = XMaxType.footnote, color = palette.textSecondary)
                                    }
                                    Surface(
                                        onClick = { detailTab = 1 },
                                        shape = CircleShape,
                                        color = palette.surface,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Update or Review", tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Vertical Dotted Update History matching Screen 2 Bottom Timeline
                        item {
                            val entries = if (updatesSynced.data.isEmpty()) {
                                listOf(
                                    VerticalTimelineEntry(
                                        id = "created",
                                        timeLabel = "Initial",
                                        dateLabel = MoneyFormatter.formatDate(deliverable.dueDateMillis),
                                        description = "Deliverable scheduled under ${deliverable.projectTitle} (${deliverable.checklist.size} checklist steps)."
                                    )
                                )
                            } else {
                                updatesSynced.data.map { upd ->
                                    val tsMillis = upd.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                                    val tStr = timeFmt.format(Date(tsMillis)).lowercase()
                                    val dStr = dateFmt.format(Date(tsMillis))
                                    val summary = buildString {
                                        append("${upd.authorName} marked ${upd.actionTypeEnum().label}")
                                        if (upd.note.isNotBlank()) append(" — ${upd.note}")
                                        if (upd.blockedReason.isNotBlank()) append(" (Blocked: ${upd.blockedReason})")
                                    }
                                    VerticalTimelineEntry(
                                        id = upd.id,
                                        timeLabel = tStr,
                                        dateLabel = dStr,
                                        description = summary,
                                        highlightRed = upd.actionTypeEnum() == UpdateActionType.BLOCKED || upd.actionTypeEnum() == UpdateActionType.CHANGES_REQUESTED
                                    )
                                }
                            }
                            VerticalTimelineLog(entries = entries)
                        }
                    } else {
                        if (deliverable.projectBrief.isNotBlank() || deliverable.latestFeedback.isNotBlank()) {
                            item {
                                Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceMuted) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (deliverable.projectBrief.isNotBlank()) {
                                            Text("Brief: ${deliverable.projectBrief}", style = XMaxType.footnote, color = palette.textSecondary)
                                        }
                                        if (deliverable.latestFeedback.isNotBlank()) {
                                            Text("Latest Feedback: ${deliverable.latestFeedback}", style = XMaxType.footnoteSemibold, color = CrimsonBlocked)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            val doneCount = checklist.count { it.done }
                            Text("Checklist ($doneCount of ${checklist.size} complete)", style = XMaxType.secondaryTitle.tabular(), color = palette.textPrimary)
                            Spacer(Modifier.height(6.dp))
                            checklist.forEachIndexed { idx, item ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = item.done,
                                            enabled = isAssignee,
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = palette.buttonPrimaryBg,
                                                checkmarkColor = palette.buttonPrimaryFg
                                            ),
                                            onCheckedChange = { checked ->
                                                AppHapticsAndNotifier.triggerTickHaptic(context)
                                                checklist = checklist.mapIndexed { i, c -> if (i == idx) c.copy(done = checked) else c }
                                            }
                                        )
                                        Text(item.title, style = XMaxType.subheadline, color = palette.textPrimary, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        if (isAssignee) {
                            if (!approved) {
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = palette.lavenderBg,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        @OptIn(ExperimentalLayoutApi::class)
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = "Focus Work Timer",
                                                    style = XMaxType.caption1Medium,
                                                    color = palette.lavenderInk
                                                )
                                                Text(
                                                    text = formatElapsedTimer(elapsedSecs),
                                                    style = XMaxType.numericSection.copy(fontFamily = MonoFontFamily),
                                                    color = palette.textPrimary
                                                )
                                            }
                                            @OptIn(ExperimentalLayoutApi::class)
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                                        onToggleTimer(deliverable.id)
                                                    },
                                                    shape = RoundedCornerShape(999.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dialog_timer_toggle_button")
                                                ) {
                                                    Icon(
                                                        if (isTimerRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (isTimerRunning) "Pause" else "Start", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false)
                                                }
                                                if (elapsedSecs > 0L) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            val stamped = "Logged ${formatElapsedTimer(elapsedSecs)}"
                                                            note = if (note.isBlank()) stamped else "$note\n$stamped"
                                                            onResetTimer(deliverable.id)
                                                        },
                                                        shape = RoundedCornerShape(999.dp),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                                    ) {
                                                        Text("Log Time", style = XMaxType.caption1Semibold, color = palette.textPrimary, maxLines = 1, softWrap = false)
                                                    }
                                                    Button(
                                                        onClick = {
                                                            onCommitTimerToTimeEntry(deliverable)
                                                            savedFeedbackBanner = "Saved ${formatElapsedTimer(elapsedSecs)} to project timesheet ✓"
                                                        },
                                                        shape = RoundedCornerShape(999.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = palette.mintBg, contentColor = palette.mintInk),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dialog_save_timesheet_button")
                                                    ) {
                                                        Text("Save Timesheet", style = XMaxType.caption1Semibold, maxLines = 1, softWrap = false)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Surface(shape = RoundedCornerShape(22.dp), color = palette.surfaceMuted) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Update Progress", style = XMaxType.headline, color = palette.textPrimary)
                                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf(
                                                DeliverableStatus.NOT_STARTED,
                                                DeliverableStatus.IN_PROGRESS,
                                                DeliverableStatus.BLOCKED,
                                                DeliverableStatus.READY_FOR_REVIEW
                                            ).forEach { opt ->
                                                FilterChip(
                                                    selected = selectedStatus == opt,
                                                    onClick = {
                                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                                        selectedStatus = opt
                                                    },
                                                    shape = RoundedCornerShape(999.dp),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = palette.buttonPrimaryBg,
                                                        selectedLabelColor = palette.buttonPrimaryFg,
                                                        containerColor = palette.surface,
                                                        labelColor = palette.textPrimary
                                                    ),
                                                    border = null,
                                                    label = { Text(opt.label, style = XMaxType.footnoteMedium) }
                                                )
                                            }
                                        }
                                        if (selectedStatus == DeliverableStatus.BLOCKED) {
                                            OutlinedTextField(
                                                value = blockedReason,
                                                onValueChange = { blockedReason = it },
                                                label = { Text("Blocked Reason *", style = XMaxType.inputLabel) },
                                                textStyle = XMaxType.body,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            OutlinedTextField(
                                                value = helpNeeded,
                                                onValueChange = { helpNeeded = it },
                                                label = { Text("Help / Input Needed", style = XMaxType.inputLabel) },
                                                textStyle = XMaxType.body,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        OutlinedTextField(
                                            value = previewUrl,
                                            onValueChange = { previewUrl = it },
                                            label = { Text("Preview / Drive / Staging URL", style = XMaxType.inputLabel) },
                                            textStyle = XMaxType.body,
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = note,
                                            onValueChange = { note = it },
                                            label = { Text("Progress Note", style = XMaxType.inputLabel) },
                                            textStyle = XMaxType.body,
                                            minLines = 2,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            item {
                                Button(
                                    onClick = {
                                        AppHapticsAndNotifier.triggerCompletionVibration(context)
                                        onSubmitProgress(deliverable, selectedStatus, checklist, note, previewUrl, blockedReason, helpNeeded)
                                        savedFeedbackBanner = "Progress saved (${selectedStatus.label}) • ${checklist.count { it.done }}/${checklist.size} steps"
                                        justSavedButton = true
                                        note = ""
                                    },
                                    enabled = selectedStatus != DeliverableStatus.BLOCKED || blockedReason.isNotBlank(),
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (justSavedButton) EmeraldApproved else palette.buttonPrimaryBg,
                                        contentColor = if (justSavedButton) PureWhite else palette.buttonPrimaryFg
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("submit_progress_button")
                                ) {
                                    Text(
                                        if (justSavedButton) "Saved ✓" else "Save Progress Update",
                                        style = XMaxType.button
                                    )
                                }
                            }
                        }

                        if (canApprove) {
                            item {
                                Surface(shape = RoundedCornerShape(22.dp), color = palette.surfaceMuted) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Manager Review & Client Revisions", style = XMaxType.headline, color = palette.textPrimary)
                                        OutlinedTextField(
                                            value = reviewFeedback,
                                            onValueChange = { reviewFeedback = it },
                                            label = { Text("Review Feedback / Revision Notes", style = XMaxType.inputLabel) },
                                            textStyle = XMaxType.body,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedButton(
                                                onClick = {
                                                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                    onReviewDeliverable(deliverable, UpdateActionType.CHANGES_REQUESTED, reviewFeedback.ifBlank { "Please refine deliverable per brief." })
                                                    savedFeedbackBanner = "Review saved: Changes Requested"
                                                    reviewFeedback = ""
                                                },
                                                shape = RoundedCornerShape(999.dp),
                                                border = BorderStroke(1.dp, palette.outline),
                                                modifier = Modifier.weight(1f).height(48.dp).testTag("request_changes_button")
                                            ) { Text("Request Changes", style = XMaxType.buttonCompact, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                            Button(
                                                onClick = {
                                                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                    onReviewDeliverable(deliverable, UpdateActionType.APPROVED, reviewFeedback.ifBlank { "Approved for client delivery." })
                                                    savedFeedbackBanner = "Deliverable Approved ✓"
                                                    reviewFeedback = ""
                                                },
                                                shape = RoundedCornerShape(999.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                                                modifier = Modifier.weight(1f).height(48.dp).testTag("approve_deliverable_button")
                                            ) { Text("Approve", style = XMaxType.buttonCompact) }
                                        }
                                        TextButton(
                                            onClick = {
                                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                onReviewDeliverable(deliverable, UpdateActionType.CLIENT_REVISION, reviewFeedback.ifBlank { "Client requested revision round." })
                                                savedFeedbackBanner = "Logged client revision round"
                                                reviewFeedback = ""
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Log Client Revision (+1 of ${deliverable.clientRevisionLimit} rounds)", style = XMaxType.footnoteSemibold.tabular(), color = palette.skyInk) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
