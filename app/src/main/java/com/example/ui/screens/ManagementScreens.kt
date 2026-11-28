package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.remote.WorkerBridgeClient
import com.example.data.NetworkConnectivityMonitor
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine
import com.example.model.*
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.components.AnalyticsVisualizer
import com.example.ui.components.CatalogueServiceTicketCard
import com.example.ui.components.CommercialFeatureGate
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.MetricStatCard
import com.example.ui.components.MonochromeSearchBar
import com.example.ui.components.StatusPill
import com.example.ui.components.WorkspaceUiPolicy
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*

@Composable
fun ClientsScreen(
    state: AgencyUiState,
    onSaveClient: (Client) -> Unit,
    onDeleteClient: (Client) -> Unit = {}
) {
    val palette = XMaxTheme.colors
    var query by remember { mutableStateOf("") }
    var editingClient by remember { mutableStateOf<Client?>(null) }
    var deleteConfirmClient by remember { mutableStateOf<Client?>(null) }
    val canEdit = state.hasPerm(AppPermission.MANAGE_CLIENTS) || state.hasPerm(AppPermission.CREATE_QUOTATIONS)
    val canDeleteClient = state.isSuperAdmin

    val filtered = remember(state.clients, query) {
        state.clients.filter {
            query.isBlank() ||
                it.companyName.contains(query, ignoreCase = true) ||
                it.contactPerson.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Clients (${filtered.size})", style = XMaxType.sectionHeading.tabular(), color = palette.textPrimary)
                if (canEdit) {
                    Button(
                        onClick = { editingClient = Client() },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                        modifier = Modifier.height(42.dp).testTag("add_client_button")
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Client", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search client company or contact…"
            )
        }
        if (filtered.isEmpty()) {
            item { EmptyStateCard(Icons.Filled.Contacts, "No Clients Found", "Add your first client during a meeting or from here.") }
        } else {
            items(filtered, key = { it.id }) { cli ->
                Surface(
                    onClick = { if (canEdit) editingClient = cli },
                    shape = RoundedCornerShape(22.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(palette.skyBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Business, contentDescription = null, tint = palette.skyInk, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(formatTitleCase(cli.companyName), style = XMaxType.headline, color = palette.textPrimary)
                            Text("${formatTitleCase(cli.contactPerson)} • ${cli.phone.ifBlank { cli.email.ifBlank { "No contact" } }}", style = XMaxType.footnote.tabular(), color = palette.textSecondary)
                            if (cli.meetingNotes.isNotBlank()) {
                                Text("Notes: ${cli.meetingNotes}", style = XMaxType.caption1, color = palette.textSecondary, maxLines = 2)
                            }
                        }
                        if (canDeleteClient) {
                            IconButton(
                                onClick = { deleteConfirmClient = cli },
                                modifier = Modifier.size(48.dp).testTag("delete_client_${cli.id}")
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete client", tint = CrimsonBlocked)
                            }
                        }
                    }
                }
            }
        }
    }

    deleteConfirmClient?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmClient = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Delete Client?", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = { Text("Permanently remove ${formatTitleCase(target.companyName)} from your workspace clients? Existing projects keep their stored client name.", style = XMaxType.subheadline, color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteClient(target)
                        deleteConfirmClient = null
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                    modifier = Modifier.testTag("confirm_delete_client_button")
                ) { Text("Delete", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmClient = null }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }

    editingClient?.let { cli ->
        var companyName by remember { mutableStateOf(cli.companyName) }
        var contactPerson by remember { mutableStateOf(cli.contactPerson) }
        var phone by remember { mutableStateOf(cli.phone) }
        var email by remember { mutableStateOf(cli.email) }
        var address by remember { mutableStateOf(cli.address) }
        var notes by remember { mutableStateOf(cli.meetingNotes) }

        AlertDialog(
            onDismissRequest = { editingClient = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text(if (cli.id.isBlank()) "New Client" else "Edit Client", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = companyName, onValueChange = { companyName = it }, textStyle = XMaxType.body, label = { Text("Company Name *", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = contactPerson, onValueChange = { contactPerson = it }, textStyle = XMaxType.body, label = { Text("Contact Person *", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, textStyle = XMaxType.body.tabular(), label = { Text("Phone", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it }, textStyle = XMaxType.body, label = { Text("Email", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = address, onValueChange = { address = it }, textStyle = XMaxType.body, label = { Text("Address", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, textStyle = XMaxType.body, label = { Text("Meeting Notes", style = XMaxType.inputLabel) }, minLines = 2, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveClient(cli.copy(companyName = companyName, contactPerson = contactPerson, phone = phone, email = email, address = address, meetingNotes = notes))
                        editingClient = null
                    },
                    enabled = companyName.isNotBlank() && contactPerson.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Save", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { editingClient = null }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }
}

@Composable
fun QuotationsScreen(
    state: AgencyUiState,
    onOpenQuote: (Quotation?) -> Unit,
    onDeleteQuotation: (Quotation) -> Unit = {},
    onOpenPayments: () -> Unit = {},
    onShareQuotePdf: (Quotation) -> Unit = {},
    onOpenRequirementsQuote: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = XMaxTheme.colors
    val uiPolicy = remember(state.verifiedEmail, state.verifiedUserRole, state.myMember, state.members) {
        WorkspaceUiPolicy.fromState(state)
    }
    var statusFilter by remember { mutableStateOf<QuotationStatus?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var deleteConfirmQuote by remember { mutableStateOf<Quotation?>(null) }
    val isSuper = uiPolicy.isSuperAdmin

    val filtered = remember(state.quotations, statusFilter, searchQuery) {
        state.quotations.filter { q ->
            val stOk = statusFilter == null || q.statusEnum() == statusFilter
            val qOk = searchQuery.isBlank() ||
                q.quoteNumber.contains(searchQuery, ignoreCase = true) ||
                q.projectTitle.contains(searchQuery, ignoreCase = true) ||
                q.clientSnapshot.companyName.contains(searchQuery, ignoreCase = true)
            stOk && qOk
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Quotations (${filtered.size})",
                    style = XMaxType.sectionHeading.tabular(),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                CommercialFeatureGate(policy = uiPolicy) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (onOpenRequirementsQuote != null) {
                            OutlinedButton(
                                onClick = onOpenRequirementsQuote,
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, palette.accentViolet.copy(alpha = 0.45f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("quotations_from_brief_button")
                            ) { Text("From Brief", style = XMaxType.buttonCompact, color = palette.textPrimary, maxLines = 1, softWrap = false) }
                        }
                        Button(
                            onClick = { onOpenQuote(null) },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("+ New Quote", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search quote number, client or project…"
            )
        }
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { statusFilter = null },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.buttonPrimaryBg,
                        selectedLabelColor = palette.buttonPrimaryFg,
                        containerColor = palette.surface,
                        labelColor = palette.textPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = statusFilter == null, borderColor = palette.outline),
                    label = { Text("All", style = XMaxType.footnoteMedium) }
                )
                QuotationStatus.entries.forEach { s ->
                    FilterChip(
                        selected = statusFilter == s,
                        onClick = { statusFilter = s },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.buttonPrimaryBg,
                            selectedLabelColor = palette.buttonPrimaryFg,
                            containerColor = palette.surface,
                            labelColor = palette.textPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = statusFilter == s, borderColor = palette.outline),
                        label = { Text(s.label, style = XMaxType.footnoteMedium) }
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyStateCard(Icons.AutoMirrored.Filled.ReceiptLong, "No quotations yet", "Build a quotation from Home or Requests.") }
        } else {
            items(filtered, key = { it.id }) { q ->
                val (bg, fg) = when (q.statusEnum()) {
                    QuotationStatus.CLIENT_APPROVED -> palette.mintBg to palette.mintInk
                    QuotationStatus.ISSUED, QuotationStatus.SENT -> palette.lavenderBg to palette.lavenderInk
                    QuotationStatus.SUPERSEDED -> palette.surfaceMuted to palette.textSecondary
                    QuotationStatus.DRAFT -> palette.butterBg to palette.butterInk
                }
                Surface(
                    onClick = { onOpenQuote(q) },
                    shape = RoundedCornerShape(22.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${q.quoteNumber} (v${q.revision})", style = XMaxType.headline.tabular(), color = palette.textPrimary)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusPill(q.statusEnum().label, bg, fg)
                                FilledTonalIconButton(
                                    onClick = {
                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                        onShareQuotePdf(q)
                                    },
                                    modifier = Modifier.size(36.dp).testTag("quote_pdf_${q.id}")
                                ) {
                                    Icon(Icons.Filled.PictureAsPdf, contentDescription = "Share Quote PDF", tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                                }
                                if (isSuper) {
                                    IconButton(
                                        onClick = { deleteConfirmQuote = q },
                                        modifier = Modifier.size(36.dp).testTag("delete_quote_${q.id}")
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete quote", tint = CrimsonBlocked, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Text("${formatTitleCase(q.projectTitle)} • ${formatTitleCase(q.clientSnapshot.companyName)}", style = XMaxType.footnoteMedium, color = palette.textSecondary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Total: ${MoneyFormatter.formatInr(q.totalPaise)}",
                                style = XMaxType.subheadlineSemibold.tabular(),
                                color = palette.textPrimary
                            )
                            Text(
                                "Advance: ${MoneyFormatter.formatInr(q.advancePaise)}",
                                style = XMaxType.footnote.tabular(),
                                color = palette.textSecondary
                            )
                        }
                        if (q.statusEnum() == QuotationStatus.CLIENT_APPROVED && q.advancePaise > 0L) {
                            val linkedProj = state.projects.firstOrNull { !it.deleted && (it.approvedQuoteId == q.id || (q.linkedProjectId.isNotBlank() && it.id == q.linkedProjectId)) }
                            val advancePending = linkedProj == null || linkedProj.advanceDuePaise > 0L
                            if (advancePending) {
                                Surface(
                                    onClick = onOpenPayments,
                                    shape = RoundedCornerShape(14.dp),
                                    color = palette.butterBg,
                                    border = BorderStroke(1.dp, palette.butterInk.copy(alpha = 0.28f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = palette.butterInk, modifier = Modifier.size(16.dp))
                                            Text(
                                                "Advance ${MoneyFormatter.formatInr(linkedProj?.advanceDuePaise ?: q.advancePaise)} reminder",
                                                style = XMaxType.footnoteSemibold.tabular(),
                                                color = palette.butterInk
                                            )
                                        }
                                        Text("Track →", style = XMaxType.footnoteSemibold, color = palette.textPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteConfirmQuote?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmQuote = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Delete Quotation?", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = { Text("Permanently delete quotation ${target.quoteNumber} (v${target.revision}) for ${formatTitleCase(target.clientSnapshot.companyName)}?", style = XMaxType.subheadline.tabular(), color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteQuotation(target)
                        deleteConfirmQuote = null
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                    modifier = Modifier.testTag("confirm_delete_quote_button")
                ) { Text("Delete", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmQuote = null }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }
}

@Composable
fun CatalogueScreen(
    state: AgencyUiState,
    onSaveItem: (CatalogueItem) -> Unit,
    onToggleArchive: (CatalogueItem, Boolean) -> Unit,
    onSyncOfficialCatalogue: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = XMaxTheme.colors
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CatalogueItem?>(null) }
    var viewingItem by remember { mutableStateOf<CatalogueItem?>(null) }
    val canEdit = state.hasPerm(AppPermission.EDIT_CATALOGUE_PRICES)

    val filtered = remember(state.catalogue, query, showArchived) {
        state.catalogue.filter {
            (showArchived || !it.archived) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Catalogue (${filtered.size})",
                    style = XMaxType.sectionHeading.tabular(),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                if (canEdit) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (onSyncOfficialCatalogue != null) {
                            OutlinedButton(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    onSyncOfficialCatalogue()
                                },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, palette.outline),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("sync_official_catalogue_button")
                            ) {
                                Text("Sync PDF", style = XMaxType.caption1Semibold, color = palette.textPrimary, maxLines = 1, softWrap = false)
                            }
                        }
                        FilterChip(
                            selected = showArchived,
                            onClick = { showArchived = !showArchived },
                            shape = RoundedCornerShape(999.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.buttonPrimaryBg,
                                selectedLabelColor = palette.buttonPrimaryFg,
                                containerColor = palette.surfaceMuted,
                                labelColor = palette.textPrimary
                            ),
                            border = null,
                            label = { Text("Archived", style = XMaxType.footnoteMedium, maxLines = 1, softWrap = false) }
                        )
                        Button(
                            onClick = { editing = CatalogueItem(sortOrder = state.catalogue.size + 1) },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("+ Add", style = XMaxType.buttonCompact, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Filter services, packages, or add-ons…"
            )
        }
        itemsIndexed(filtered, key = { _, it -> it.id }) { index, item ->
            CatalogueServiceTicketCard(
                item = item,
                index = index,
                buttonLabel = if (canEdit) "Edit" else "Details",
                expandable = true,
                onButtonClick = {
                    AppHapticsAndNotifier.triggerTickHaptic(context)
                    if (canEdit) editing = item else viewingItem = item
                }
            )
        }
    }

    viewingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { viewingItem = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text(item.name, style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        label = "${item.kindEnum().label} • ${item.category}",
                        containerColor = palette.lavenderBg,
                        contentColor = palette.lavenderInk
                    )
                    Text(
                        text = when (item.pricingModeEnum()) {
                            PricingMode.PERCENTAGE_SURCHARGE -> "+${item.surchargePercent}% Urgent Surcharge"
                            PricingMode.CUSTOM_QUOTE -> "Custom Quote"
                            PricingMode.ACTUAL_COST -> "Actual Cost"
                            else -> MoneyFormatter.formatInr(item.pricePaise)
                        },
                        style = XMaxType.numericHeadline,
                        color = palette.textPrimary
                    )
                    if (item.description.isNotBlank()) {
                        Text(item.description, style = XMaxType.subheadline, color = palette.textSecondary)
                    }
                    if (item.inclusions.isNotEmpty()) {
                        Text("Included Deliverables", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                        item.inclusions.forEach { inc ->
                            Text("• ${inc.title} (x${inc.quantity})", style = XMaxType.footnote.tabular(), color = palette.textSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewingItem = null },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Close", style = XMaxType.buttonCompact) }
            }
        )
    }

    editing?.let { item ->
        var name by remember { mutableStateOf(item.name) }
        var category by remember { mutableStateOf(item.category.ifBlank { "Branding & Identity" }) }
        var desc by remember { mutableStateOf(item.description) }
        var priceRupees by remember { mutableStateOf(MoneyFormatter.paiseToEditableRupees(item.pricePaise)) }

        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text(if (item.id.isBlank()) "Add Catalogue Item" else "Edit ${item.name}", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, textStyle = XMaxType.body, label = { Text("Service Name *", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = category, onValueChange = { category = it }, textStyle = XMaxType.body, label = { Text("Category *", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(
                        value = priceRupees,
                        onValueChange = { priceRupees = it },
                        textStyle = XMaxType.body.tabular(),
                        label = { Text("Price (₹)", style = XMaxType.inputLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, textStyle = XMaxType.body, label = { Text("Description", style = XMaxType.inputLabel) }, minLines = 2, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val paise = MoneyFormatter.rupeesToPaise(priceRupees) ?: 0L
                        onSaveItem(item.copy(name = name, category = category, description = desc, pricePaise = paise))
                        editing = null
                    },
                    enabled = name.isNotBlank() && category.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Save", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentsScreen(
    state: AgencyUiState,
    onRecordPayment: (Project, Long, Long, PaymentMethod, String, String) -> Unit,
    onGenerateTaxInvoice: (Project, Quotation?) -> Unit = { _, _ -> },
    onDispatchNudge: (Project, Client?, String, String) -> Unit = { _, _, _, _ -> },
    onCreatePortalLink: (Project, Quotation?, (ClientPortalLink) -> Unit) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val palette = XMaxTheme.colors
    val isOnline by NetworkConnectivityMonitor.isOnline.collectAsStateWithLifecycle()
    val analyticsSeries = remember(state.projects, state.deliverables, state.payments, state.timeEntries) {
        WorkDistributionEngine.computeAgencyAnalyticsSeries(
            projects = state.projects,
            deliverables = state.deliverables,
            payments = state.payments,
            timeEntries = state.timeEntries
        )
    }
    val reminderSummary = remember(state.projects, state.deliverables, state.quotations) {
        WorkDistributionEngine.computePaymentReminders(
            projects = state.projects,
            deliverables = state.deliverables,
            quotations = state.quotations
        )
    }
    var recordTarget by remember { mutableStateOf<Project?>(null) }
    var prefillAmountPaise by remember { mutableStateOf<Long?>(null) }
    var prefillNote by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }

    val activeProjects = remember(state.projects) { state.projects.filter { !it.deleted } }
    val totalAgreed = remember(activeProjects) { activeProjects.sumOf { it.agreedValuePaise } }
    val totalPaid = remember(activeProjects) { activeProjects.sumOf { it.paidPaise } }
    val totalBalance = remember(activeProjects) { activeProjects.sumOf { it.outstandingPaise } }
    val collectionPct = remember(totalAgreed, totalPaid) {
        if (totalAgreed <= 0L) 0 else ((totalPaid.toDouble() / totalAgreed.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }
    val animatedCollectionProgress by animateFloatAsState(
        targetValue = (collectionPct / 100f).coerceIn(0f, 1f),
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "collection_progress"
    )
    val advanceDueCount = remember(activeProjects) { activeProjects.count { it.advanceDuePaise > 0L } }
    val balanceDueCount = remember(activeProjects) { activeProjects.count { it.outstandingPaise > 0L } }
    val settledCount = remember(activeProjects) { activeProjects.count { it.outstandingPaise <= 0L } }
    val canRecord = state.hasPerm(AppPermission.RECORD_PAYMENTS)
    val canSeeCommercialDocs = canRecord || state.hasPerm(AppPermission.ISSUE_QUOTATIONS) || state.isSuperAdmin || state.isAdmin

    val filteredProjects = remember(activeProjects, statusFilter, searchQuery) {
        activeProjects.filter { p ->
            val filterOk = when (statusFilter) {
                "ADVANCE_DUE" -> p.advanceDuePaise > 0L
                "OPEN" -> p.outstandingPaise > 0L
                "SETTLED" -> p.outstandingPaise <= 0L
                else -> true
            }
            val queryOk = searchQuery.isBlank() ||
                p.title.contains(searchQuery, ignoreCase = true) ||
                p.clientCompanyName.contains(searchQuery, ignoreCase = true)
            filterOk && queryOk
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .testTag("payments_lazy_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isOnline || state.deliverablesPendingSync) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.butterBg,
                    border = BorderStroke(1.dp, palette.butterInk.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth().testTag("payments_offline_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (!isOnline) Icons.Filled.CloudOff else Icons.Filled.CloudSync,
                            contentDescription = null,
                            tint = palette.butterInk,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (!isOnline) "Offline Ledger Mode — showing cached invoices & balances. Changes will queue for sync." else "Syncing queued financial updates with cloud…",
                            style = XMaxType.footnoteSemibold,
                            color = palette.butterInk
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Payment Tracker", style = XMaxType.sectionHeading, color = palette.textPrimary)
                    Text("${activeProjects.size} projects • ${state.payments.size} receipts logged", style = XMaxType.footnote.tabular(), color = palette.textSecondary)
                }
                StatusPill("$collectionPct% Collected", palette.mintBg, palette.mintInk)
            }
        }

        // Collection Progress Tracker Overview
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("payment_tracker_overview")
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Collection Progress", style = XMaxType.headline, color = palette.textPrimary)
                        Text(
                            "${MoneyFormatter.formatInr(totalPaid)} / ${MoneyFormatter.formatInr(totalAgreed)}",
                            style = XMaxType.footnoteSemibold.tabular(),
                            color = palette.lavenderInk
                        )
                    }
                    LinearProgressIndicator(
                        progress = { animatedCollectionProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = EmeraldApproved,
                        trackColor = palette.surfaceMuted
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusPill("Settled: $settledCount", palette.mintBg, palette.mintInk)
                        if (advanceDueCount > 0) {
                            StatusPill("Advance Due: $advanceDueCount", palette.butterBg, palette.butterInk)
                        }
                        if (balanceDueCount > 0) {
                            StatusPill("Open Balances: $balanceDueCount", palette.lavenderBg, palette.lavenderInk)
                        }
                    }
                }
            }
        }

        if (reminderSummary.totalCount > 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.butterInk.copy(alpha = 0.28f)),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_reminders_section")
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = palette.butterInk, modifier = Modifier.size(20.dp))
                                Text("Payment Reminders (${reminderSummary.totalCount})", style = XMaxType.headline.tabular(), color = palette.textPrimary)
                            }
                            StatusPill(
                                label = "${reminderSummary.finalCount} Final • ${reminderSummary.advanceCount} Advance",
                                containerColor = palette.butterBg,
                                contentColor = palette.butterInk
                            )
                        }

                        reminderSummary.projectReminders.forEach { rem ->
                            val isFinal = rem.type != WorkDistributionEngine.PaymentReminderType.ADVANCE_DUE
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isFinal) palette.blushBg else palette.butterBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            rem.type.label,
                                            style = XMaxType.footnoteSemibold,
                                            color = if (isFinal) CrimsonBlocked else palette.butterInk
                                        )
                                        Text(
                                            MoneyFormatter.formatInr(rem.amountDuePaise),
                                            style = XMaxType.numericHeadline,
                                            color = palette.textPrimary
                                        )
                                    }
                                    Text(
                                        "${formatTitleCase(rem.project.title)} • ${formatTitleCase(rem.project.clientCompanyName)}",
                                        style = XMaxType.subheadlineSemibold,
                                        color = palette.textPrimary
                                    )
                                    Text(rem.detail, style = XMaxType.footnote, color = palette.textSecondary)
                                    if (canRecord) {
                                        Button(
                                            onClick = {
                                                AppHapticsAndNotifier.triggerTickHaptic(context)
                                                prefillAmountPaise = rem.amountDuePaise
                                                prefillNote = if (isFinal) "Final balance settlement" else "Project advance payment"
                                                recordTarget = rem.project
                                            },
                                            shape = RoundedCornerShape(999.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                                            modifier = Modifier.fillMaxWidth().height(40.dp)
                                        ) {
                                            Text(if (isFinal) "Record Final Payment" else "Record Advance Payment", style = XMaxType.buttonCompact)
                                        }
                                    }
                                }
                            }
                        }

                        reminderSummary.quoteAdvanceReminders.forEach { qRem ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = palette.lavenderBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Approved Quote • Advance Due", style = XMaxType.footnoteSemibold, color = palette.lavenderInk)
                                        Text(MoneyFormatter.formatInr(qRem.advanceDuePaise), style = XMaxType.numericHeadline, color = palette.textPrimary)
                                    }
                                    Text(
                                        "${qRem.quotation.quoteNumber} • ${formatTitleCase(qRem.quotation.projectTitle)} (${formatTitleCase(qRem.quotation.clientSnapshot.companyName)})",
                                        style = XMaxType.subheadlineSemibold.tabular(),
                                        color = palette.textPrimary
                                    )
                                    Text("Client approved quotation — accept on Requests tab to activate project billing.", style = XMaxType.caption1, color = palette.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            AnalyticsVisualizer(
                series = analyticsSeries,
                title = "Payment Trends & Revenue Forecast"
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricStatCard("Collected", MoneyFormatter.formatInr(totalPaid), "of ${MoneyFormatter.formatInr(totalAgreed)}", Icons.Filled.AccountBalanceWallet, containerColor = palette.mintBg, accentColor = palette.mintInk, modifier = Modifier.weight(1f))
                MetricStatCard("Outstanding", MoneyFormatter.formatInr(totalBalance), "$balanceDueCount open projects", Icons.Filled.Schedule, containerColor = palette.surfaceMuted, accentColor = palette.textPrimary, modifier = Modifier.weight(1f))
            }
        }

        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search project or client payment status…"
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to "All (${activeProjects.size})",
                    "ADVANCE_DUE" to "Advance Due ($advanceDueCount)",
                    "OPEN" to "Balance Due ($balanceDueCount)",
                    "SETTLED" to "Settled ($settledCount)"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = statusFilter == key,
                        onClick = {
                            AppHapticsAndNotifier.triggerTickHaptic(context)
                            statusFilter = key
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.buttonPrimaryBg,
                            selectedLabelColor = palette.buttonPrimaryFg,
                            containerColor = palette.surface,
                            labelColor = palette.textPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = statusFilter == key, borderColor = palette.outline),
                        label = { Text(label, style = XMaxType.footnoteMedium.tabular()) }
                    )
                }
            }
        }

        item { Text("Project Balances (${filteredProjects.size})", style = XMaxType.sectionHeading.tabular(), color = palette.textPrimary) }

        if (filteredProjects.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Filled.AccountBalanceWallet,
                    "No Matching Payment Records",
                    "Try clearing the filter or search query to see all projects."
                )
            }
        } else {
            items(filteredProjects, key = { it.id }) { proj ->
                val projPct = if (proj.agreedValuePaise <= 0L) 100 else ((proj.paidPaise.toDouble() / proj.agreedValuePaise.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                val matchedQuote = remember(state.quotations, proj.approvedQuoteId, proj.id) {
                    state.quotations.firstOrNull { it.id == proj.approvedQuoteId || it.linkedProjectId == proj.id }
                }
                val matchedClient = remember(state.clients, proj.clientId, proj.clientCompanyName) {
                    state.clients.firstOrNull { it.id == proj.clientId || it.companyName.equals(proj.clientCompanyName, ignoreCase = true) }
                }
                val activeLink = remember(state.portalLinks, proj.id) {
                    state.portalLinks.firstOrNull { it.projectId == proj.id && it.isActive() }
                }
                val projTime = remember(state.timeEntries, proj.id) { state.timeEntries.filter { it.projectId == proj.id } }
                val loggedMins = projTime.sumOf { it.durationMinutes }
                val laborCostPaise = projTime.sumOf { it.billableAmountPaise() }
                val (badgeBg, badgeFg, badgeLabel) = when {
                    proj.outstandingPaise <= 0L -> Triple(palette.mintBg, palette.mintInk, "Settled ✓")
                    proj.advanceDuePaise > 0L -> Triple(palette.butterBg, palette.butterInk, "Advance Due")
                    else -> Triple(palette.skyBg, palette.skyInk, "$projPct% Paid")
                }
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(formatTitleCase(proj.title), style = XMaxType.headline, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatTitleCase(proj.clientCompanyName), style = XMaxType.footnote, color = palette.textSecondary)
                            }
                            StatusPill(badgeLabel, badgeBg, badgeFg)
                        }
                        LinearProgressIndicator(
                            progress = { (projPct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = if (proj.outstandingPaise <= 0L) EmeraldApproved else palette.lavenderInk,
                            trackColor = palette.surfaceMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Agreed: ${MoneyFormatter.formatInr(proj.agreedValuePaise)}", style = XMaxType.caption1Medium.tabular(), color = palette.textSecondary)
                            Text("Paid: ${MoneyFormatter.formatInr(proj.paidPaise)}", style = XMaxType.caption1Semibold.tabular(), color = palette.mintInk)
                            Text("Due: ${MoneyFormatter.formatInr(proj.outstandingPaise)}", style = XMaxType.caption1Semibold.tabular(), color = if (proj.outstandingPaise > 0L) palette.blushInk else palette.mintInk)
                        }
                        if (loggedMins > 0) {
                            val hrsStr = "%.1f".format(loggedMins / 60.0)
                            Text(
                                "Tracked Time: ${hrsStr}h • Billable Labor: ${MoneyFormatter.formatInr(laborCostPaise)}",
                                style = XMaxType.caption1Medium.tabular(),
                                color = palette.skyInk
                            )
                        }
                        if (proj.advanceDuePaise > 0L) {
                            Text(
                                "Pending Advance: ${MoneyFormatter.formatInr(proj.advanceDuePaise)}",
                                style = XMaxType.footnoteMedium.tabular(),
                                color = palette.butterInk
                            )
                        }
                        if (canSeeCommercialDocs) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                        onGenerateTaxInvoice(proj, matchedQuote)
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, palette.outline),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(40.dp).testTag("invoice_pdf_${proj.id}")
                                ) {
                                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Tax Invoice PDF", style = XMaxType.caption1Semibold, color = palette.textPrimary, maxLines = 1, softWrap = false)
                                }
                                if (proj.outstandingPaise > 0L) {
                                    val dispatchWithUrl: (String) -> Unit = { ch ->
                                        if (activeLink != null) {
                                            onDispatchNudge(proj, matchedClient, ch, WorkerBridgeClient.buildPublicPortalUrl(activeLink.token))
                                        } else {
                                            onCreatePortalLink(proj, matchedQuote) { newLink ->
                                                onDispatchNudge(proj, matchedClient, ch, WorkerBridgeClient.buildPublicPortalUrl(newLink.token))
                                            }
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            AppHapticsAndNotifier.triggerTickHaptic(context)
                                            dispatchWithUrl("WHATSAPP")
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        border = BorderStroke(1.dp, EmeraldApproved.copy(alpha = 0.45f)),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = palette.mintBg, contentColor = palette.mintInk),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(40.dp).testTag("nudge_wa_${proj.id}")
                                    ) {
                                        Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("WhatsApp Nudge", style = XMaxType.caption1Semibold, maxLines = 1, softWrap = false)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            AppHapticsAndNotifier.triggerTickHaptic(context)
                                            dispatchWithUrl("EMAIL")
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        border = BorderStroke(1.dp, palette.lavenderInk.copy(alpha = 0.35f)),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = palette.lavenderBg, contentColor = palette.lavenderInk),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(40.dp).testTag("nudge_email_${proj.id}")
                                    ) {
                                        Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Email Nudge", style = XMaxType.caption1Semibold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                        if (canRecord && proj.outstandingPaise > 0L) {
                            Button(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    prefillAmountPaise = null
                                    prefillNote = ""
                                    recordTarget = proj
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg),
                                modifier = Modifier.fillMaxWidth().height(44.dp).testTag("record_payment_${proj.id}")
                            ) { Text("Record Payment", style = XMaxType.buttonCompact) }
                        }
                    }
                }
            }
        }

        item { Text("Payment Ledger (${state.payments.size})", style = XMaxType.sectionHeading.tabular(), color = palette.textPrimary) }
        if (state.payments.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Filled.Receipt,
                    "No Receipts Logged Yet",
                    "Recorded client payments appear here with payment method and reference details."
                )
            }
        } else {
            items(state.payments, key = { it.id }) { p ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(MoneyFormatter.formatInr(p.amountPaise), style = XMaxType.numericHeadline, color = palette.mintInk)
                            StatusPill(p.methodEnum().label, palette.lavenderBg, palette.lavenderInk)
                        }
                        Text(formatTitleCase(p.projectTitle), style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                        Text(
                            "${formatTitleCase(p.clientCompanyName)} • Ref: ${p.reference.ifBlank { "N/A" }}",
                            style = XMaxType.footnote.tabular(),
                            color = palette.textSecondary
                        )
                        Text(
                            "Recorded by ${formatTitleCase(p.recordedByName)} on ${MoneyFormatter.formatDate(p.paymentDateMillis)}",
                            style = XMaxType.caption1.tabular(),
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }

        if (state.nudgeLogs.isNotEmpty()) {
            item { Text("Automated Payment Nudge Log (${state.nudgeLogs.size})", style = XMaxType.sectionHeading.tabular(), color = palette.textPrimary) }
            items(state.nudgeLogs.take(20), key = { it.id }) { log ->
                val isSent = log.status == "SENT"
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier.fillMaxWidth().testTag("nudge_log_${log.id}")
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                label = log.channel,
                                containerColor = if (log.channel == "WHATSAPP") palette.mintBg else palette.lavenderBg,
                                contentColor = if (log.channel == "WHATSAPP") palette.mintInk else palette.lavenderInk
                            )
                            StatusPill(
                                label = if (isSent) "Delivered via API" else log.status,
                                containerColor = if (isSent) palette.mintBg else palette.butterBg,
                                contentColor = if (isSent) palette.mintInk else palette.butterInk
                            )
                        }
                        Text("${formatTitleCase(log.projectTitle)} • ${formatTitleCase(log.clientCompanyName)}", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                        Text(
                            "Due ${MoneyFormatter.formatInr(log.amountDuePaise)} • To: ${log.recipient.ifBlank { "Client" }}",
                            style = XMaxType.footnote.tabular(),
                            color = palette.textSecondary
                        )
                        if (log.summary.isNotBlank()) {
                            Text(log.summary, style = XMaxType.caption1, color = palette.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    recordTarget?.let { proj ->
        val defaultPaise = prefillAmountPaise
            ?: proj.advanceDuePaise.takeIf { proj.paidPaise == 0L && it > 0L }
            ?: proj.outstandingPaise
        var amountRupees by remember(proj.id, prefillAmountPaise) {
            mutableStateOf(MoneyFormatter.paiseToEditableRupees(defaultPaise))
        }
        var method by remember { mutableStateOf(PaymentMethod.UPI) }
        var ref by remember { mutableStateOf("") }
        var note by remember(proj.id, prefillNote) { mutableStateOf(prefillNote) }

        AlertDialog(
            onDismissRequest = { recordTarget = null },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Record Payment — ${formatTitleCase(proj.title)}", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remaining balance: ${MoneyFormatter.formatInr(proj.outstandingPaise)}", style = XMaxType.footnoteMedium.tabular(), color = palette.textSecondary)
                    OutlinedTextField(
                        value = amountRupees,
                        onValueChange = { amountRupees = it },
                        textStyle = XMaxType.body.tabular(),
                        label = { Text("Amount Paid (₹) *", style = XMaxType.inputLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaymentMethod.entries.forEach { m ->
                            FilterChip(
                                selected = method == m,
                                onClick = { method = m },
                                shape = RoundedCornerShape(999.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.buttonPrimaryBg,
                                    selectedLabelColor = palette.buttonPrimaryFg,
                                    containerColor = palette.surfaceMuted,
                                    labelColor = palette.textPrimary
                                ),
                                border = null,
                                label = { Text(m.label, style = XMaxType.footnoteMedium) }
                            )
                        }
                    }
                    OutlinedTextField(value = ref, onValueChange = { ref = it }, textStyle = XMaxType.body, label = { Text("Transaction Ref / UTR", style = XMaxType.inputLabel) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = note, onValueChange = { note = it }, textStyle = XMaxType.body, label = { Text("Note", style = XMaxType.inputLabel) }, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                val paise = MoneyFormatter.rupeesToPaise(amountRupees) ?: 0L
                Button(
                    onClick = {
                        AppHapticsAndNotifier.triggerCompletionVibration(context)
                        onRecordPayment(proj, paise, System.currentTimeMillis(), method, ref, note)
                        recordTarget = null
                    },
                    enabled = paise > 0L,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.buttonPrimaryBg, contentColor = palette.buttonPrimaryFg)
                ) { Text("Save Payment", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { recordTarget = null }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }
}

@Composable
fun ActivityLogScreen(
    state: AgencyUiState,
    onDeleteLog: (String) -> Unit = {},
    onClearAllLogs: () -> Unit = {}
) {
    val palette = XMaxTheme.colors
    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Internal Activity Log",
                    style = XMaxType.sectionHeading,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (state.isSuperAdmin && state.activityLog.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = palette.blushBg, contentColor = CrimsonBlocked),
                        modifier = Modifier.testTag("clear_all_logs_button")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear All Logs", style = XMaxType.buttonCompact)
                    }
                }
            }
        }
        if (state.activityLog.isEmpty()) {
            item { EmptyStateCard(Icons.Filled.History, "No Activity Entries Yet", "Key agency actions appear here automatically.") }
        } else {
            items(state.activityLog, key = { it.id }) { act ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(palette.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(16.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(formatTitleCase(act.title), style = XMaxType.headline, color = palette.textPrimary)
                            Text(act.detail, style = XMaxType.footnote, color = palette.textSecondary)
                            Text("By ${formatTitleCase(act.actorName)} • ${act.categoryEnum().label}", style = XMaxType.caption1Medium, color = palette.textSecondary)
                        }
                        if (state.isSuperAdmin) {
                            IconButton(
                                onClick = { onDeleteLog(act.id) },
                                modifier = Modifier.size(48.dp).testTag("delete_log_${act.id}")
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete log entry", tint = CrimsonBlocked)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = palette.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Clear All Activity Logs?", style = XMaxType.secondaryTitle, color = palette.textPrimary) },
            text = { Text("Permanently delete all ${state.activityLog.size} activity log entries? This cannot be undone.", style = XMaxType.subheadline.tabular(), color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllLogs()
                        showClearConfirm = false
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                    modifier = Modifier.testTag("confirm_clear_all_logs_button")
                ) { Text("Clear Logs", style = XMaxType.buttonCompact) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel", style = XMaxType.buttonCompact, color = palette.textSecondary) } }
        )
    }
}

