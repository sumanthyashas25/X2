package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.DeliverableDraft
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun RequestsScreen(
    state: AgencyUiState,
    onOpenQuoteBuilder: (ProjectRequest?, Quotation?) -> Unit,
    onCreateRequest: (Client, String, String, List<String>, Long, Member) -> Unit,
    onAcceptRequest: (ProjectRequest, Quotation, List<DeliverableDraft>) -> Unit,
    onDeclineRequest: (ProjectRequest, String) -> Unit
) {
    var filter by remember { mutableStateOf<DecisionStatus?>(DecisionStatus.PENDING) }
    var searchQuery by remember { mutableStateOf("") }
    var showNewRequestDialog by remember { mutableStateOf(false) }
    var selectedRequest by remember { mutableStateOf<ProjectRequest?>(null) }

    val canCreateReq = state.hasPerm(AppPermission.CREATE_REQUESTS) || state.hasPerm(AppPermission.CREATE_QUOTATIONS)
    val canAccept = state.hasPerm(AppPermission.ACCEPT_PROJECT)
    val canDecline = state.hasPerm(AppPermission.DECLINE_PROJECT)

    val filtered = remember(state.requests, filter, searchQuery) {
        state.requests.filter { req ->
            val stOk = filter == null || req.decisionStatusEnum() == filter
            val qOk = searchQuery.isBlank() ||
                req.projectTitle.contains(searchQuery, ignoreCase = true) ||
                req.clientCompanyName.contains(searchQuery, ignoreCase = true) ||
                req.latestQuoteNumber.contains(searchQuery, ignoreCase = true)
            stOk && qOk
        }
    }

    val palette = XMaxTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize().pastelAmbientBackdrop(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Client Requests", style = XMaxType.sectionHeading, color = palette.textPrimary)
                if (canCreateReq && state.clients.isNotEmpty()) {
                    Button(
                        onClick = { showNewRequestDialog = true },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                        modifier = Modifier.height(42.dp).testTag("new_request_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Request", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search client requests or quote ID…"
            )
        }
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == null, borderColor = palette.outline),
                    label = { Text("All (${state.requests.size})", style = XMaxType.footnoteMedium.tabular()) }
                )
                DecisionStatus.entries.forEach { d ->
                    FilterChip(
                        selected = filter == d,
                        onClick = { filter = d },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.buttonPrimaryBg,
                            selectedLabelColor = palette.buttonPrimaryFg,
                            containerColor = palette.surface,
                            labelColor = palette.textPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == d, borderColor = palette.outline),
                        label = { Text("${d.label} (${state.requests.count { it.decisionStatusEnum() == d }})", style = XMaxType.footnoteMedium.tabular()) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Outlined.Inbox,
                    "No matching requests",
                    "Capture a client request or build a new quotation in a meeting.",
                    actionLabel = if (canCreateReq) "Build Quote" else null,
                    onAction = { onOpenQuoteBuilder(null, null) }
                )
            }
        } else {
            items(filtered, key = { it.id }) { req ->
                val st = req.decisionStatusEnum()
                val (bg, fg) = when (st) {
                    DecisionStatus.ACCEPTED -> palette.mintBg to palette.mintInk
                    DecisionStatus.DECLINED -> palette.blushBg to palette.blushInk
                    DecisionStatus.PENDING -> palette.lavenderBg to palette.lavenderInk
                }
                Surface(
                    onClick = { selectedRequest = req },
                    shape = RoundedCornerShape(22.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(formatTitleCase(req.projectTitle), style = XMaxType.headline, color = palette.textPrimary)
                                Text(
                                    "${formatTitleCase(req.clientCompanyName)} • Lead: ${formatTitleCase(req.responsibleManagerName.ifBlank { "Unassigned" })}",
                                    style = XMaxType.footnote,
                                    color = palette.textSecondary
                                )
                            }
                            StatusPill(st.label, bg, fg)
                        }
                        if (req.latestQuoteNumber.isNotBlank() || req.clientApprovedQuoteId.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (req.latestQuoteNumber.isNotBlank()) {
                                    StatusPill("${req.latestQuoteNumber} v${req.latestQuoteRevision}", palette.lavenderBg, palette.lavenderInk)
                                }
                                if (req.clientApprovedQuoteId.isNotBlank()) {
                                    StatusPill("Client Approved", palette.mintBg, palette.mintInk)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedRequest?.let { req ->
        RequestDecisionDialog(
            state = state,
            request = state.requests.firstOrNull { it.id == req.id } ?: req,
            canAccept = canAccept,
            canDecline = canDecline,
            onOpenQuoteBuilder = { q ->
                selectedRequest = null
                onOpenQuoteBuilder(req, q)
            },
            onAccept = { quote, drafts ->
                onAcceptRequest(req, quote, drafts)
                selectedRequest = null
            },
            onDecline = { note ->
                onDeclineRequest(req, note)
                selectedRequest = null
            },
            onDismiss = { selectedRequest = null }
        )
    }

    if (showNewRequestDialog) {
        state.myMember?.let { fallbackMgr ->
            NewRequestDialog(
                clients = state.clients,
                managers = state.members.filter {
                    it.statusEnum() == MembershipStatus.APPROVED &&
                        (it.accessLevelEnum() == AccessLevel.SUPER_ADMIN || it.accessLevelEnum() == AccessLevel.ADMIN)
                },
                fallbackManager = fallbackMgr,
                onCreate = { c, title, brief, target, mgr ->
                    onCreateRequest(c, title, brief, emptyList(), target, mgr)
                    showNewRequestDialog = false
                },
                onDismiss = { showNewRequestDialog = false }
            )
        }
    }
}

@Composable
fun RequestDecisionDialog(
    state: AgencyUiState,
    request: ProjectRequest,
    canAccept: Boolean,
    canDecline: Boolean,
    onOpenQuoteBuilder: (Quotation?) -> Unit,
    onAccept: (Quotation, List<DeliverableDraft>) -> Unit,
    onDecline: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val quotes = state.quotations.filter { it.requestId == request.id }
    val targetQuote = quotes.firstOrNull { it.id == request.clientApprovedQuoteId }
        ?: quotes.firstOrNull { it.statusEnum() == QuotationStatus.CLIENT_APPROVED }
        ?: quotes.firstOrNull { it.id == request.latestQuoteId }
        ?: quotes.firstOrNull()

    var drafts by remember(targetQuote?.id, state.routingRules, state.members) {
        mutableStateOf(
            if (targetQuote != null) {
                WorkDistributionEngine.buildDeliverableDrafts(
                    quoteLines = targetQuote.lines,
                    routingRules = state.routingRules,
                    members = state.members,
                    departments = state.departments,
                    jobRoles = state.jobRoles
                )
            } else emptyList()
        )
    }

    var declineNote by remember { mutableStateOf("") }
    var showDeclineConfirm by remember { mutableStateOf(false) }

    val palette = XMaxTheme.colors
    val st = request.decisionStatusEnum()
    val (stBg, stFg) = when (st) {
        DecisionStatus.ACCEPTED -> palette.mintBg to palette.mintInk
        DecisionStatus.DECLINED -> palette.blushBg to palette.blushInk
        DecisionStatus.PENDING -> palette.skyBg to palette.skyInk
    }

    FullScreenAgencyDialog(onDismissRequest = onDismiss) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.canvas,
            contentColor = palette.textPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar anchored safely below status bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                    }
                    Text("Request Details", style = XMaxType.headline, color = palette.textPrimary)
                    Spacer(Modifier.size(48.dp))
                }

                // Scrollable body (summary strip, 3-step timeline, quotation CTA, key/value details, routing drafts)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(palette.surfaceMuted),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Description, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Request Title", style = XMaxType.caption1Medium, color = palette.textSecondary)
                                Text(
                                    text = formatTitleCase(request.projectTitle),
                                    style = XMaxType.headline,
                                    color = palette.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusPill(st.label, stBg, stFg)
                        }
                    }

                    item {
                        StepProgressTimeline(
                            steps = listOf(
                                TimelineStep("Received", "Captured", completed = true),
                                TimelineStep("Quoted", targetQuote?.quoteNumber ?: "Pending", completed = targetQuote != null),
                                TimelineStep("Approved", st.label, completed = st == DecisionStatus.ACCEPTED)
                            )
                        )
                    }

                    item {
                        Button(
                            onClick = { onOpenQuoteBuilder(targetQuote) },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (targetQuote == null) "Build Quotation" else "Open Quotation (${targetQuote.quoteNumber})", style = XMaxType.button)
                        }
                    }

                    item {
                        val rows = buildList {
                            add(DetailKeyValueRow("Client", request.clientCompanyName))
                            add(DetailKeyValueRow("Manager", request.responsibleManagerName.ifBlank { "Unassigned" }))
                            if (targetQuote != null) {
                                add(DetailKeyValueRow("Quote Total", MoneyFormatter.formatInr(targetQuote.totalPaise)))
                                add(DetailKeyValueRow("Advance Due", MoneyFormatter.formatInr(targetQuote.advancePaise)))
                            }
                            if (request.brief.isNotBlank()) {
                                add(DetailKeyValueRow("Brief", request.brief))
                            }
                        }
                        DetailKeyValueCard(
                            title = "Request Details",
                            icon = Icons.Outlined.Inventory2,
                            rows = rows
                        )
                    }

                    if (request.decisionStatusEnum() == DecisionStatus.PENDING && targetQuote != null) {
                        item {
                            Text("Deliverables & Routing (${drafts.size})", style = XMaxType.secondaryTitle.tabular(), color = palette.textPrimary)
                        }
                        items(drafts, key = { it.draftId }) { draft ->
                            var expanded by remember { mutableStateOf(false) }
                            val eligible = state.members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active }
                            Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceMuted) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("${draft.title} (×${draft.quantity})", style = XMaxType.headline.tabular(), color = palette.textPrimary, modifier = Modifier.weight(1f))
                                        StatusPill(
                                            draft.assignmentStatus.label,
                                            if (draft.assignmentStatus == AssignmentStatus.ASSIGNED) palette.mintBg else palette.butterBg,
                                            if (draft.assignmentStatus == AssignmentStatus.ASSIGNED) palette.mintInk else palette.butterInk
                                        )
                                    }
                                    Text("${draft.departmentName} • ${draft.jobRoleName}", style = XMaxType.footnote, color = palette.textSecondary)
                                    Box {
                                        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) {
                                            Text("Assignee: ${draft.assignedToName.ifBlank { "Pick Employee" }}", style = XMaxType.footnoteSemibold, color = palette.skyInk)
                                        }
                                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                            DropdownMenuItem(text = { Text("Leave Unassigned", style = XMaxType.subheadline) }, onClick = {
                                                drafts = drafts.map { if (it.draftId == draft.draftId) WorkDistributionEngine.applyOverride(it, null) else it }
                                                expanded = false
                                            })
                                            eligible.forEach { m ->
                                                DropdownMenuItem(
                                                    text = { Text("${m.fullName} (${m.departmentName} / ${m.jobRoleName})", style = XMaxType.subheadline) },
                                                    onClick = {
                                                        drafts = drafts.map { if (it.draftId == draft.draftId) WorkDistributionEngine.applyOverride(it, m) else it }
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Anchored Safe-Inset Bottom Decision Bar (never clipped behind gesture nav or tall headers)
                if (request.decisionStatusEnum() == DecisionStatus.PENDING && (canAccept || canDecline)) {
                    Surface(
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.outline),
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .imePadding()
                                .padding(
                                    start = 18.dp,
                                    end = 18.dp,
                                    top = 12.dp,
                                    bottom = navBottom.coerceAtLeast(20.dp)
                                )
                        ) {
                            if (canAccept) {
                                SlideToActionButton(
                                    label = "SLIDE TO ACCEPT",
                                    enabled = targetQuote != null && drafts.isNotEmpty(),
                                    onSlideComplete = { if (targetQuote != null) onAccept(targetQuote, drafts) },
                                    modifier = Modifier.fillMaxWidth(),
                                    testTag = "accept_project_button"
                                )
                            }
                            if (canDecline) {
                                OutlinedButton(
                                    onClick = { showDeclineConfirm = true },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("decline_request_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked)
                                ) {
                                    Text("Decline Request", style = XMaxType.button)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeclineConfirm) {
        AlertDialog(
            onDismissRequest = { showDeclineConfirm = false },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            title = { Text("Decline Request", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = declineNote,
                    onValueChange = { declineNote = it },
                    textStyle = XMaxType.body,
                    label = { Text("Reason / Internal Note", style = XMaxType.inputLabel) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeclineConfirm = false
                        onDecline(declineNote.ifBlank { "Declined internally" })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Confirm Decline", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { showDeclineConfirm = false }) { Text("Cancel", style = XMaxType.buttonCompact) } }
        )
    }
}

@Composable
private fun NewRequestDialog(
    clients: List<Client>,
    managers: List<Member>,
    fallbackManager: Member,
    onCreate: (Client, String, String, Long, Member) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedClient by remember { mutableStateOf(clients.firstOrNull()) }
    var title by remember { mutableStateOf("") }
    var brief by remember { mutableStateOf("") }
    var daysStr by remember { mutableStateOf("14") }
    var selectedMgr by remember { mutableStateOf(managers.firstOrNull() ?: fallbackManager) }
    val palette = XMaxTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        title = { Text("Capture Client Request", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    clients.forEach { cli ->
                        FilterChip(selected = selectedClient?.id == cli.id, onClick = { selectedClient = cli }, label = { Text(cli.companyName, style = XMaxType.footnoteMedium) })
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, textStyle = XMaxType.body, label = { Text("Project Title *", style = XMaxType.inputLabel) }, singleLine = true)
                OutlinedTextField(value = brief, onValueChange = { brief = it }, textStyle = XMaxType.body, label = { Text("Brief / Goals", style = XMaxType.inputLabel) }, minLines = 2)
                OutlinedTextField(value = daysStr, onValueChange = { daysStr = it.filter(Char::isDigit).take(3) }, textStyle = XMaxType.body.tabular(), label = { Text("Target Turnaround (days)", style = XMaxType.inputLabel) }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val days = (daysStr.toLongOrNull() ?: 14L).coerceIn(1L, 365L)
                    selectedClient?.let { c -> onCreate(c, title, brief, System.currentTimeMillis() + days * 86_400_000L, selectedMgr) }
                },
                enabled = selectedClient != null && title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
            ) { Text("Save Request", style = XMaxType.buttonCompact) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = XMaxType.buttonCompact) } }
    )
}

@Composable
fun ProjectsScreen(
    state: AgencyUiState,
    onOpenDeliverable: (Deliverable) -> Unit,
    onAssignDeliverable: (Deliverable, Member, Long) -> Unit,
    onOpenAddOnQuote: (Project) -> Unit,
    onToggleDeleteProject: (Project, Boolean) -> Unit,
    onPermanentlyDeleteProject: (Project) -> Unit = {},
    onCleanupOldDoneProjects: () -> Unit = {},
    onOpenPayments: () -> Unit = {}
) {
    var showDeleted by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf<Project?>(null) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    val canDelete = state.isSuperAdmin
    val canAssign = state.hasPerm(AppPermission.ASSIGN_WORK)
    val canQuote = state.hasPerm(AppPermission.CREATE_QUOTATIONS) || state.hasPerm(AppPermission.ISSUE_QUOTATIONS)
    val photoByUid = remember(state.members) { state.members.associate { it.uid to it.photoBase64 } }
    val cleanableCount = remember(state.projects) {
        state.projects.count { it.statusEnum() == ProjectStatus.COMPLETED || it.deleted }
    }

    val list = remember(state.projects, showDeleted, searchQuery) {
        state.projects.filter { proj ->
            proj.deleted == showDeleted && (
                searchQuery.isBlank() ||
                    proj.title.contains(searchQuery, ignoreCase = true) ||
                    proj.clientCompanyName.contains(searchQuery, ignoreCase = true) ||
                    proj.approvedQuoteNumber.contains(searchQuery, ignoreCase = true)
                )
        }
    }

    val palette = XMaxTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize().pastelAmbientBackdrop(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Projects (${list.size})", style = XMaxType.sectionHeading.tabular(), color = palette.textPrimary)
                if (canDelete) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (cleanableCount > 0) {
                            OutlinedButton(
                                onClick = { showCleanupConfirm = true },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.45f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(38.dp).testTag("cleanup_old_projects_button")
                            ) {
                                Text("CLEAN DONE ($cleanableCount)", style = XMaxType.caption1Semibold.tabular())
                            }
                        }
                        FilterChip(
                            selected = showDeleted,
                            onClick = { showDeleted = !showDeleted },
                            shape = RoundedCornerShape(999.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.buttonPrimaryBg,
                                selectedLabelColor = palette.buttonPrimaryFg,
                                containerColor = palette.surface,
                                labelColor = palette.textPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = showDeleted, borderColor = palette.outline),
                            label = { Text(if (showDeleted) "DELETED" else "ACTIVE ONLY", style = XMaxType.caption1Semibold) }
                        )
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search projects by title, client, or quote ID…"
            )
        }

        if (list.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Outlined.Inventory2,
                    if (showDeleted) "No deleted projects" else "No active projects yet",
                    "Accept an approved client quotation on the Requests tab to spin up a project and distribute work."
                )
            }
        } else {
            items(list, key = { it.id }) { proj ->
                val projDelivs = state.deliverables.filter { it.projectId == proj.id }
                val done = projDelivs.count { it.statusEnum() == DeliverableStatus.APPROVED }
                val started = projDelivs.any { it.statusEnum() != DeliverableStatus.NOT_STARTED }
                val allDone = projDelivs.isNotEmpty() && done == projDelivs.size
                val assigneePairs = projDelivs
                    .filter { it.assignedToName.isNotBlank() }
                    .distinctBy { it.assignedToUid.ifBlank { it.assignedToName } }
                    .map { it.assignedToName to (photoByUid[it.assignedToUid] ?: "") }

                Surface(
                    onClick = { selectedProject = proj },
                    shape = RoundedCornerShape(22.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(formatTitleCase(proj.title), style = XMaxType.headline, color = palette.textPrimary)
                                Text("${formatTitleCase(proj.clientCompanyName)} • ${proj.approvedQuoteNumber} v${proj.approvedQuoteRevision}", style = XMaxType.footnote.tabular(), color = palette.textSecondary)
                            }
                            StatusPill(
                                proj.statusEnum().label,
                                if (allDone) palette.mintBg else palette.skyBg,
                                if (allDone) palette.mintInk else palette.skyInk
                            )
                        }

                        StepProgressTimeline(
                            steps = listOf(
                                TimelineStep("Accepted", "${projDelivs.size} items", completed = true),
                                TimelineStep("In Progress", "$done/${projDelivs.size}", completed = started || allDone),
                                TimelineStep("Delivered", if (allDone) "Done" else "Open", completed = allDone)
                            )
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            if (assigneePairs.isNotEmpty()) {
                                OverlappingAvatarMembers(members = assigneePairs, avatarSize = 26.dp, borderColor = palette.surface)
                            } else {
                                Text(WorkDistributionEngine.summarizeProjectProgress(projDelivs), style = XMaxType.caption1, color = palette.textSecondary)
                            }
                            if (state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) {
                                StatusPill(
                                    "BAL: ${MoneyFormatter.formatInr(proj.outstandingPaise)}",
                                    palette.mintBg,
                                    palette.mintInk
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            containerColor = palette.surface,
            title = { Text("DELETE ALL OLD DONE PROJECTS?", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = { Text("This permanently deletes $cleanableCount completed or archived project(s) and their deliverables.", style = XMaxType.subheadline.tabular(), color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        onCleanupOldDoneProjects()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("DELETE FOREVER", style = XMaxType.buttonCompact) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text("CANCEL", style = XMaxType.buttonCompact) }
            }
        )
    }

    selectedProject?.let { p ->
        val fresh = state.projects.firstOrNull { it.id == p.id } ?: p
        val projDelivs = state.deliverables.filter { it.projectId == fresh.id }
        ProjectDetailDialog(
            state = state,
            project = fresh,
            deliverables = projDelivs,
            canAssign = canAssign,
            canQuote = canQuote,
            canDelete = canDelete,
            onOpenDeliverable = { d ->
                selectedProject = null
                onOpenDeliverable(d)
            },
            onAssign = onAssignDeliverable,
            onAddOnQuote = {
                selectedProject = null
                onOpenAddOnQuote(fresh)
            },
            onToggleDelete = { del ->
                onToggleDeleteProject(fresh, del)
                selectedProject = null
            },
            onPermanentDelete = {
                onPermanentlyDeleteProject(fresh)
                selectedProject = null
            },
            onOpenPayments = {
                selectedProject = null
                onOpenPayments()
            },
            onDismiss = { selectedProject = null }
        )
    }
}

@Composable
private fun ProjectDetailDialog(
    state: AgencyUiState,
    project: Project,
    deliverables: List<Deliverable>,
    canAssign: Boolean,
    canQuote: Boolean,
    canDelete: Boolean,
    onOpenDeliverable: (Deliverable) -> Unit,
    onAssign: (Deliverable, Member, Long) -> Unit,
    onAddOnQuote: () -> Unit,
    onToggleDelete: (Boolean) -> Unit,
    onPermanentDelete: () -> Unit,
    onOpenPayments: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var detailTab by remember { mutableStateOf(0) } // 0 = Details & Deliverables, 1 = Summary & History
    var showPermanentDeleteConfirm by remember { mutableStateOf(false) }
    val done = deliverables.count { it.statusEnum() == DeliverableStatus.APPROVED }
    val allDone = deliverables.isNotEmpty() && done == deliverables.size
    val anyStarted = deliverables.any { it.statusEnum() != DeliverableStatus.NOT_STARTED }
    val anyBlocked = deliverables.any { it.statusEnum() == DeliverableStatus.BLOCKED }
    val earliestDue = deliverables.mapNotNull { it.dueDateMillis.takeIf { d -> d > 0L } }.minOrNull() ?: 0L
    val paymentReminder = remember(project, deliverables) {
        WorkDistributionEngine.computePaymentReminders(
            projects = listOf(project),
            deliverables = deliverables,
            quotations = emptyList()
        ).projectReminders.firstOrNull()
    }

    val palette = XMaxTheme.colors
    FullScreenAgencyDialog(onDismissRequest = onDismiss) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.canvas,
            contentColor = palette.textPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .imePadding()
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = navBottom.coerceAtLeast(20.dp)),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                    }
                    Text("Project Details", style = XMaxType.headline, color = palette.textPrimary)
                    Spacer(Modifier.size(48.dp))
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
                        Text("Project", style = XMaxType.caption1Medium, color = palette.textSecondary)
                        Text(
                            text = "${project.approvedQuoteNumber.ifBlank { project.id.take(10) }} • ${formatTitleCase(project.title)}",
                            style = XMaxType.headline.tabular(),
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusPill(
                        label = project.statusEnum().label,
                        containerColor = if (allDone) palette.mintBg else palette.skyBg,
                        contentColor = if (allDone) palette.mintInk else palette.skyInk
                    )
                }

                // 3-Step Horizontal Node Timeline
                StepProgressTimeline(
                    steps = listOf(
                        TimelineStep("Accepted", "${deliverables.size} tasks", completed = true),
                        TimelineStep("In Progress", "$done/${deliverables.size} done", completed = anyStarted || allDone),
                        TimelineStep("Delivered", if (earliestDue > 0L) MoneyFormatter.formatDate(earliestDue) else "Target", completed = allDone)
                    )
                )

                // Full-width Primary CTA Pill
                if (canQuote) {
                    Button(
                        onClick = onAddOnQuote,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Outlined.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scope Add-On Quote", style = XMaxType.button)
                    }
                }

                SegmentedPillTabs(
                    options = listOf("Deliverables (${deliverables.size})", "Project Details"),
                    selectedIndex = detailTab,
                    onSelect = { detailTab = it }
                )

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (detailTab == 0) {
                        items(deliverables, key = { it.id }) { d ->
                            var assignMenu by remember { mutableStateOf(false) }
                            val st = d.statusEnum()
                            val (pillBg, pillFg) = statusColorsForDeliverable(st)

                            Surface(
                                onClick = { onOpenDeliverable(d) },
                                shape = RoundedCornerShape(20.dp),
                                color = palette.surfaceMuted
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(
                                            modifier = Modifier.size(34.dp).clip(CircleShape).background(palette.surface),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (st == DeliverableStatus.APPROVED) Icons.Filled.Check else Icons.Outlined.TaskAlt,
                                                contentDescription = null,
                                                tint = palette.textPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(d.title, style = XMaxType.headline, color = palette.textPrimary)
                                            Text("${d.departmentName} • ${d.assignedToName.ifBlank { d.assignmentStatusEnum().label }}", style = XMaxType.footnote, color = palette.textSecondary)
                                        }
                                        StatusPill(st.label, pillBg, pillFg)
                                    }
                                    if (canAssign) {
                                        Box {
                                            TextButton(onClick = { assignMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                                Text("Reassign / Set Assignee", style = XMaxType.footnoteSemibold, color = palette.skyInk)
                                            }
                                            DropdownMenu(expanded = assignMenu, onDismissRequest = { assignMenu = false }) {
                                                state.members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active }.forEach { m ->
                                                    DropdownMenuItem(
                                                        text = { Text("${m.fullName} (${m.departmentName} / ${m.jobRoleName})", style = XMaxType.subheadline) },
                                                        onClick = {
                                                            onAssign(d, m, d.dueDateMillis.takeIf { it > 0L } ?: (System.currentTimeMillis() + 7L * 86_400_000L))
                                                            assignMenu = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            val rows = buildList {
                                add(DetailKeyValueRow("Client", project.clientCompanyName))
                                add(DetailKeyValueRow("Quote Ref", "${project.approvedQuoteNumber} v${project.approvedQuoteRevision}"))
                                if (earliestDue > 0L) {
                                    add(DetailKeyValueRow("Next Deadline", MoneyFormatter.formatDate(earliestDue)))
                                }
                                if (state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) {
                                    add(DetailKeyValueRow("Agreed Value", MoneyFormatter.formatInr(project.agreedValuePaise)))
                                    add(DetailKeyValueRow("Balance Due", MoneyFormatter.formatInr(project.outstandingPaise)))
                                }
                                if (anyBlocked) {
                                    add(DetailKeyValueRow("Alert", "Contains blocked deliverables", isWarning = true))
                                }
                            }
                            DetailKeyValueCard(
                                title = "Project Details",
                                icon = Icons.Outlined.LocalShipping,
                                rows = rows
                            )
                        }

                        if ((state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) && paymentReminder != null) {
                            item {
                                val reminder = paymentReminder
                                val isFinal = reminder.type != WorkDistributionEngine.PaymentReminderType.ADVANCE_DUE
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (isFinal) palette.blushBg else palette.butterBg,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isFinal) CrimsonBlocked.copy(alpha = 0.35f) else palette.butterInk.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("project_payment_reminder_card")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.NotificationsActive,
                                                    contentDescription = null,
                                                    tint = if (isFinal) CrimsonBlocked else palette.butterInk,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = reminder.type.label,
                                                    style = XMaxType.subheadlineSemibold,
                                                    color = palette.textPrimary
                                                )
                                            }
                                            Text(
                                                text = "${reminder.detail} • Due ${MoneyFormatter.formatInr(reminder.amountDuePaise)}",
                                                style = XMaxType.footnote.tabular(),
                                                color = palette.textSecondary
                                            )
                                            Button(
                                                onClick = onOpenPayments,
                                                shape = RoundedCornerShape(999.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                                                modifier = Modifier.fillMaxWidth().height(42.dp)
                                            ) {
                                                Text("Track in Payment Tracker →", style = XMaxType.buttonCompact)
                                            }
                                        }
                                    }
                            }
                        }

                        // Contact / Manager Row Card
                        item {
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
                                    val mgrPhoto = state.members.firstOrNull { it.uid == project.responsibleManagerUid }?.photoBase64.orEmpty()
                                    StudioAvatar(
                                        name = project.responsibleManagerName.ifBlank { "X.MAX Lead" },
                                        size = 44.dp,
                                        photoBase64 = mgrPhoto
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = formatTitleCase(project.responsibleManagerName.ifBlank { "Studio Lead" }),
                                            style = XMaxType.headline,
                                            color = palette.textPrimary
                                        )
                                        Text(
                                            text = WorkDistributionEngine.summarizeProjectProgress(deliverables),
                                            style = XMaxType.footnote.tabular(),
                                            color = palette.textSecondary
                                        )
                                    }
                                }
                            }
                        }

                        if (canDelete) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onToggleDelete(!project.deleted) },
                                        shape = RoundedCornerShape(999.dp),
                                        border = BorderStroke(1.dp, palette.outline),
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delete_restore_project_button"),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary)
                                    ) { Text(if (project.deleted) "RESTORE PROJECT" else "ARCHIVE PROJECT", style = XMaxType.button) }

                                    Button(
                                        onClick = { showPermanentDeleteConfirm = true },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("permanent_delete_project_button")
                                    ) { Text("DELETE PROJECT PERMANENTLY", style = XMaxType.button) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPermanentDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showPermanentDeleteConfirm = false },
            containerColor = palette.surface,
            title = { Text("DELETE PROJECT FOREVER?", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = { Text("Permanently remove ${project.title.uppercase()} and all ${deliverables.size} deliverable(s)?", style = XMaxType.subheadline.tabular(), color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showPermanentDeleteConfirm = false
                        onPermanentDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("DELETE PERMANENTLY", style = XMaxType.buttonCompact) }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDeleteConfirm = false }) { Text("CANCEL", style = XMaxType.buttonCompact) }
            }
        )
    }
}
