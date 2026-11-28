package com.example.ui.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.CatalogueSeedData
import com.example.domain.MoneyFormatter
import com.example.domain.ProjectRequirementInput
import com.example.domain.ProjectRequirementsQuoteEngine
import com.example.domain.RequirementServiceSelection
import com.example.domain.StructuredQuotationSummary
import com.example.model.AppPermission
import com.example.model.CatalogueItem
import com.example.model.CatalogueKind
import com.example.model.Client
import com.example.model.Member
import com.example.model.PricingMode
import com.example.model.ProjectRequest
import com.example.model.Quotation
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.theme.EmeraldApproved
import com.example.ui.theme.VioletPrimary
import com.example.ui.theme.XMaxTheme
import com.example.ui.theme.XMaxType
import com.example.ui.theme.tabular

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectRequirementsQuoteSheet(
    state: AgencyUiState,
    onDismiss: () -> Unit,
    onSaveClient: (Client, (Client) -> Unit) -> Unit,
    onCreateRequest: (Client, String, String, List<String>, Long, Member, (ProjectRequest) -> Unit) -> Unit,
    onSaveQuoteDraft: (Quotation, Boolean, (Quotation) -> Unit) -> Unit,
    onOpenInFullQuoteBuilder: (ProjectRequest?, Quotation) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val palette = XMaxTheme.colors
    val actor = state.myMember ?: return
    val company = state.company
    val canApplyDiscount = state.isSuperAdmin || state.hasPerm(AppPermission.APPLY_DISCOUNTS)

    val catalogueItems = remember(state.catalogue) {
        val live = state.catalogue.filter { !it.archived }
        if (live.isNotEmpty()) live else CatalogueSeedData.defaultCatalogue().filter { !it.archived }
    }

    var activeSection by remember { mutableIntStateOf(0) } // 0 = Requirements Form, 1 = Structured Summary
    var useInlineNewClient by remember { mutableStateOf(state.clients.isEmpty()) }
    var selectedClientId by remember { mutableStateOf(state.clients.firstOrNull()?.id.orEmpty()) }
    var newClientCompany by remember { mutableStateOf("") }
    var newClientContact by remember { mutableStateOf("") }
    var newClientEmail by remember { mutableStateOf("") }
    var newClientPhone by remember { mutableStateOf("") }

    var projectTitle by remember { mutableStateOf("") }
    var requirementsBrief by remember { mutableStateOf("") }
    var deliverablesText by remember { mutableStateOf("") }

    var serviceSearch by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedQuantities by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    var customLineTitle by remember { mutableStateOf("") }
    var customLineRupees by remember { mutableStateOf("") }

    var urgentDelivery by remember { mutableStateOf(false) }
    var includeGst by remember { mutableStateOf(company?.gstEnabled ?: false) }
    var discountRupees by remember { mutableStateOf("") }
    var advancePercentText by remember { mutableStateOf((company?.defaultAdvancePercent ?: 50).toString()) }
    var revisionRounds by remember { mutableIntStateOf(company?.defaultRevisionRounds ?: 2) }
    var timelineNote by remember { mutableStateOf("10-14 business days from advance & asset handover") }
    var validityDaysText by remember { mutableStateOf((company?.defaultValidityDays ?: 15).toString()) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val selectedExistingClient = remember(state.clients, selectedClientId) {
        state.clients.firstOrNull { it.id == selectedClientId }
    }

    val selections = remember(catalogueItems, selectedQuantities) {
        catalogueItems.mapNotNull { item ->
            val qty = selectedQuantities[item.id] ?: 0
            if (qty > 0) RequirementServiceSelection(item = item, quantity = qty) else null
        }
    }

    val requirementInput = remember(
        useInlineNewClient,
        selectedExistingClient,
        newClientCompany,
        newClientContact,
        newClientEmail,
        newClientPhone,
        projectTitle,
        requirementsBrief,
        deliverablesText,
        selections,
        customLineTitle,
        customLineRupees,
        urgentDelivery,
        includeGst,
        company?.gstRatePercent,
        discountRupees,
        advancePercentText,
        timelineNote,
        validityDaysText,
        revisionRounds,
        canApplyDiscount
    ) {
        val resolvedCompany = if (useInlineNewClient) newClientCompany else selectedExistingClient?.companyName.orEmpty()
        val resolvedContact = if (useInlineNewClient) newClientContact else selectedExistingClient?.contactPerson.orEmpty()
        val resolvedEmail = if (useInlineNewClient) newClientEmail else selectedExistingClient?.email.orEmpty()
        val resolvedPhone = if (useInlineNewClient) newClientPhone else selectedExistingClient?.phone.orEmpty()

        ProjectRequirementInput(
            selectedClientId = if (useInlineNewClient) "" else selectedExistingClient?.id.orEmpty(),
            clientCompanyName = resolvedCompany,
            clientContactPerson = resolvedContact,
            clientEmail = resolvedEmail,
            clientPhone = resolvedPhone,
            projectTitle = projectTitle,
            requirementsBrief = requirementsBrief,
            deliverablesText = deliverablesText,
            selectedServices = selections,
            customLineTitle = customLineTitle,
            customLinePricePaise = MoneyFormatter.rupeesToPaise(customLineRupees) ?: 0L,
            urgentDelivery = urgentDelivery,
            includeGst = includeGst,
            gstRatePercent = company?.gstRatePercent ?: 18,
            discountPaise = if (canApplyDiscount) (MoneyFormatter.rupeesToPaise(discountRupees) ?: 0L) else 0L,
            advancePercent = advancePercentText.toIntOrNull()?.coerceIn(0, 100) ?: 50,
            timelineNote = timelineNote,
            validityDays = validityDaysText.toIntOrNull()?.coerceIn(1, 90) ?: 15,
            revisionRounds = revisionRounds
        )
    }

    val summary = remember(requirementInput, company?.name) {
        ProjectRequirementsQuoteEngine.generateSummary(
            input = requirementInput,
            companyName = company?.name ?: "X.MAX"
        )
    }

    val categories = remember(catalogueItems) {
        listOf("All", "Packages") + catalogueItems
            .filter { it.kindEnum() != CatalogueKind.PACKAGE }
            .map { it.category }
            .distinct()
    }

    val filteredCatalogue = remember(catalogueItems, serviceSearch, selectedCategory) {
        catalogueItems.filter { item ->
            val catMatch = when (selectedCategory) {
                "All" -> true
                "Packages" -> item.kindEnum() == CatalogueKind.PACKAGE
                else -> item.category == selectedCategory
            }
            val searchMatch = serviceSearch.isBlank() ||
                item.name.contains(serviceSearch, ignoreCase = true) ||
                item.category.contains(serviceSearch, ignoreCase = true) ||
                item.description.contains(serviceSearch, ignoreCase = true)
            catMatch && searchMatch
        }
    }

    fun persistDraftAndOptionallyOpenBuilder(openBuilderAfter: Boolean) {
        if (!summary.isReady || isSaving) return
        isSaving = true

        fun createRequestAndQuote(client: Client) {
            val desiredTitles = summary.calculation.computedLines.map { it.title }
            val draftQuote = ProjectRequirementsQuoteEngine.toDraftQuotation(
                input = requirementInput,
                summary = summary,
                client = client,
                company = company
            )
            val managers = state.members.filter {
                it.uid == actor.uid || it.accessLevel == "SUPER_ADMIN" || it.accessLevel == "ADMIN"
            }
            val leadManager = managers.firstOrNull { it.uid == actor.uid } ?: actor
            val targetMillis = System.currentTimeMillis() + 14L * 86_400_000L

            onCreateRequest(
                client,
                summary.projectTitle,
                draftQuote.scopeSummary.ifBlank { summary.timelineNote },
                desiredTitles,
                targetMillis,
                leadManager
            ) { savedRequest ->
                val linkedDraft = draftQuote.copy(requestId = savedRequest.id)
                onSaveQuoteDraft(linkedDraft, false) { savedQuote ->
                    isSaving = false
                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                    if (openBuilderAfter) {
                        onOpenInFullQuoteBuilder(savedRequest, savedQuote)
                    } else {
                        actionFeedback = "Saved draft quote & client request for ${client.companyName}"
                    }
                }
            }
        }

        val existing = if (!useInlineNewClient) selectedExistingClient else null
        if (existing != null) {
            createRequestAndQuote(existing)
        } else {
            val newClient = Client(
                companyName = requirementInput.clientCompanyName.trim(),
                contactPerson = requirementInput.clientContactPerson.trim().ifBlank { requirementInput.clientCompanyName.trim() },
                phone = requirementInput.clientPhone.trim(),
                email = requirementInput.clientEmail.trim(),
                meetingNotes = requirementInput.requirementsBrief.trim()
            )
            onSaveClient(newClient) { savedClient ->
                createRequestAndQuote(savedClient)
            }
        }
    }

    FullScreenAgencyDialog(onDismissRequest = onDismiss) {
        val tfColors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = palette.surface,
            unfocusedContainerColor = palette.surface
        )
        Scaffold(
            containerColor = palette.canvas,
            contentColor = palette.textPrimary,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.canvas,
                        titleContentColor = palette.textPrimary
                    ),
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("req_quote_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = palette.textPrimary)
                        }
                    },
                    title = {
                        Column {
                            Text("Requirements & Quote Summary", style = XMaxType.headline, color = palette.textPrimary)
                            Text("Structured client proposal generator", style = XMaxType.caption1, color = palette.textSecondary)
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.outline),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${summary.calculation.computedLines.size} selected item(s)",
                                    style = XMaxType.caption1.tabular(),
                                    color = palette.textSecondary
                                )
                                Text(
                                    text = MoneyFormatter.formatInr(summary.calculation.totalPaise),
                                    style = XMaxType.numericSection,
                                    color = palette.textPrimary
                                )
                            }
                            if (activeSection == 0) {
                                Button(
                                    onClick = { activeSection = 1 },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.buttonPrimaryBg,
                                        contentColor = palette.buttonPrimaryFg
                                    ),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("view_structured_summary_button")
                                ) {
                                    Text("Generate Summary", style = XMaxType.buttonCompact)
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            } else {
                                Button(
                                    onClick = { persistDraftAndOptionallyOpenBuilder(openBuilderAfter = true) },
                                    enabled = summary.isReady && !isSaving,
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.buttonPrimaryBg,
                                        contentColor = palette.buttonPrimaryFg
                                    ),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("save_quote_from_req_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isSaving) "Saving…" else "Open in Quote Builder", style = XMaxType.buttonCompact)
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SegmentedPillTabs(
                    options = listOf("1. Requirements", "2. Client Summary"),
                    selectedIndex = activeSection,
                    onSelect = { activeSection = it },
                    modifier = Modifier.testTag("req_quote_mode_tabs")
                )

                AnimatedVisibility(visible = !actionFeedback.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = palette.mintBg,
                        border = BorderStroke(1.dp, palette.mintInk.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("req_quote_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = palette.mintInk, modifier = Modifier.size(18.dp))
                            Text(
                                text = actionFeedback.orEmpty(),
                                style = XMaxType.footnoteSemibold,
                                color = palette.mintInk,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (activeSection == 0) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("req_quote_form_list"),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // A. Client & Brief Card
                        item {
                            Card(
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Outlined.Business, contentDescription = null, tint = palette.accentViolet, modifier = Modifier.size(18.dp))
                                            Text("Client & Project Requirements", style = XMaxType.headline, color = palette.textPrimary)
                                        }
                                        if (state.clients.isNotEmpty()) {
                                            FilterChip(
                                                selected = useInlineNewClient,
                                                onClick = { useInlineNewClient = !useInlineNewClient },
                                                label = { Text(if (useInlineNewClient) "New Client" else "+ New Client", style = XMaxType.caption1Semibold) },
                                                shape = RoundedCornerShape(999.dp),
                                                modifier = Modifier.testTag("req_toggle_new_client_chip")
                                            )
                                        }
                                    }

                                    if (!useInlineNewClient && state.clients.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            state.clients.forEach { cli ->
                                                FilterChip(
                                                    selected = cli.id == selectedClientId,
                                                    onClick = { selectedClientId = cli.id },
                                                    shape = RoundedCornerShape(999.dp),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = palette.buttonPrimaryBg,
                                                        selectedLabelColor = palette.buttonPrimaryFg,
                                                        containerColor = palette.surface,
                                                        labelColor = palette.textPrimary
                                                    ),
                                                    border = null,
                                                    label = { Text(cli.companyName, style = XMaxType.footnoteMedium) }
                                                )
                                            }
                                        }
                                    } else {
                                        OutlinedTextField(
                                            value = newClientCompany,
                                            onValueChange = { newClientCompany = it },
                                            label = { Text("Client / Company Name *", style = XMaxType.inputLabel) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = tfColors,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("req_form_client_company")
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = newClientContact,
                                                onValueChange = { newClientContact = it },
                                                label = { Text("Contact Name", style = XMaxType.inputLabel) },
                                                singleLine = true,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = tfColors,
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = newClientPhone,
                                                onValueChange = { newClientPhone = it },
                                                label = { Text("Phone", style = XMaxType.inputLabel) },
                                                singleLine = true,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = tfColors,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        OutlinedTextField(
                                            value = newClientEmail,
                                            onValueChange = { newClientEmail = it },
                                            label = { Text("Client Email", style = XMaxType.inputLabel) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = tfColors,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    OutlinedTextField(
                                        value = projectTitle,
                                        onValueChange = { projectTitle = it },
                                        label = { Text("Project Title *", style = XMaxType.inputLabel) },
                                        placeholder = { Text("e.g. Brand Identity & Website Launch", style = XMaxType.body) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = tfColors,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("req_form_project_title")
                                    )

                                    OutlinedTextField(
                                        value = requirementsBrief,
                                        onValueChange = { requirementsBrief = it },
                                        label = { Text("Requirements & Business Goals", style = XMaxType.inputLabel) },
                                        placeholder = { Text("Describe client objectives, target audience, brand tone, or technical needs…", style = XMaxType.body) },
                                        minLines = 3,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = tfColors,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("req_form_brief")
                                    )

                                    OutlinedTextField(
                                        value = deliverablesText,
                                        onValueChange = { deliverablesText = it },
                                        label = { Text("Expected Deliverables (one per line)", style = XMaxType.inputLabel) },
                                        placeholder = { Text("Primary logo & brand book\n5-page responsive website\nLaunch reel kit", style = XMaxType.body) },
                                        minLines = 2,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = tfColors,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("req_form_deliverables")
                                    )
                                }
                            }
                        }

                        // B. Service & Package Picker Card
                        item {
                            Card(
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("Select Services & Packages", style = XMaxType.headline, color = palette.textPrimary)
                                    MonochromeSearchBar(
                                        query = serviceSearch,
                                        onQueryChange = { serviceSearch = it },
                                        placeholder = "Search services, packages, or categories…"
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        categories.forEach { cat ->
                                            val sel = selectedCategory == cat
                                            FilterChip(
                                                selected = sel,
                                                onClick = { selectedCategory = cat },
                                                shape = RoundedCornerShape(999.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = palette.buttonPrimaryBg,
                                                    selectedLabelColor = palette.buttonPrimaryFg,
                                                    containerColor = palette.surface,
                                                    labelColor = palette.textPrimary
                                                ),
                                                border = null,
                                                label = { Text(cat, style = XMaxType.caption1Semibold) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        items(filteredCatalogue.take(24), key = { it.id }) { item ->
                            val qty = selectedQuantities[item.id] ?: 0
                            RequirementServiceRow(
                                item = item,
                                quantity = qty,
                                onIncrement = {
                                    if (item.pricingModeEnum() == PricingMode.PERCENTAGE_SURCHARGE) {
                                        urgentDelivery = true
                                    } else {
                                        selectedQuantities = selectedQuantities + (item.id to (qty + 1))
                                    }
                                },
                                onDecrement = {
                                    if (qty <= 1) {
                                        selectedQuantities = selectedQuantities - item.id
                                    } else {
                                        selectedQuantities = selectedQuantities + (item.id to (qty - 1))
                                    }
                                }
                            )
                        }

                        // C. Custom Requirement Line & Commercial Settings
                        item {
                            Card(
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("Custom Scope & Commercial Terms", style = XMaxType.headline, color = palette.textPrimary)

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = customLineTitle,
                                            onValueChange = { customLineTitle = it },
                                            label = { Text("Custom Line Item", style = XMaxType.inputLabel) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = tfColors,
                                            modifier = Modifier
                                                .weight(1.3f)
                                                .testTag("req_custom_item_title")
                                        )
                                        OutlinedTextField(
                                            value = customLineRupees,
                                            onValueChange = { customLineRupees = it },
                                            label = { Text("Amount (₹)", style = XMaxType.inputLabel) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = tfColors,
                                            modifier = Modifier
                                                .weight(0.9f)
                                                .testTag("req_custom_item_amount")
                                        )
                                    }

                                    Surface(shape = RoundedCornerShape(14.dp), color = palette.surface, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text("Urgent Delivery (+25%)", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                                                    Text("Rush turnaround surcharge on eligible lines", style = XMaxType.caption1, color = palette.textSecondary)
                                                }
                                                Switch(
                                                    checked = urgentDelivery,
                                                    onCheckedChange = { urgentDelivery = it },
                                                    modifier = Modifier.testTag("req_urgent_switch")
                                                )
                                            }
                                            HorizontalDivider(color = palette.outline)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Include GST (${company?.gstRatePercent ?: 18}%)",
                                                    style = XMaxType.subheadlineMedium,
                                                    color = palette.textPrimary
                                                )
                                                Switch(
                                                    checked = includeGst,
                                                    onCheckedChange = { includeGst = it },
                                                    modifier = Modifier.testTag("req_gst_switch")
                                                )
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = advancePercentText,
                                            onValueChange = { advancePercentText = it.filter(Char::isDigit).take(3) },
                                            label = { Text("Advance %", style = XMaxType.inputLabel) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = tfColors,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = validityDaysText,
                                            onValueChange = { validityDaysText = it.filter(Char::isDigit).take(2) },
                                            label = { Text("Valid (Days)", style = XMaxType.inputLabel) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = tfColors,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (canApplyDiscount) {
                                            OutlinedTextField(
                                                value = discountRupees,
                                                onValueChange = { discountRupees = it },
                                                label = { Text("Discount ₹", style = XMaxType.inputLabel) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = tfColors,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = timelineNote,
                                        onValueChange = { timelineNote = it },
                                        label = { Text("Estimated Delivery Timeline", style = XMaxType.inputLabel) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = tfColors,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            "5-7 business days (Rush)",
                                            "10-14 business days",
                                            "3-4 weeks milestone delivery"
                                        ).forEach { preset ->
                                            FilterChip(
                                                selected = timelineNote == preset,
                                                onClick = { timelineNote = preset },
                                                label = { Text(preset, style = XMaxType.caption1Medium) },
                                                shape = RoundedCornerShape(999.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Structured Quotation Summary View
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("structured_quote_summary_list"),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (summary.validationHints.isNotEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = palette.butterBg,
                                    border = BorderStroke(1.dp, palette.butterInk.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = palette.butterInk, modifier = Modifier.size(18.dp))
                                            Text("Complete Before Saving", style = XMaxType.subheadlineSemibold, color = palette.butterInk)
                                        }
                                        summary.validationHints.forEach { hint ->
                                            Text("• $hint", style = XMaxType.footnote, color = palette.textPrimary)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            StructuredQuotationSummaryCard(
                                summary = summary,
                                companyName = company?.name ?: "X.MAX",
                                includeGst = includeGst,
                                gstRatePercent = company?.gstRatePercent ?: 18,
                                advancePercent = requirementInput.advancePercent
                            )
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(summary.formattedClientMessage))
                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                        actionFeedback = "Copied client quotation summary to clipboard."
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("copy_quote_summary_button")
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy", style = XMaxType.buttonCompact)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "${summary.projectTitle} — Quotation Summary")
                                            putExtra(Intent.EXTRA_TEXT, summary.formattedClientMessage)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Quotation Summary"))
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, palette.outline),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("share_quote_summary_button")
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Share", style = XMaxType.buttonCompact)
                                }
                            }
                        }

                        item {
                            OutlinedButton(
                                onClick = { persistDraftAndOptionallyOpenBuilder(openBuilderAfter = false) },
                                enabled = summary.isReady && !isSaving,
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, palette.accentViolet.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("save_draft_summary_button")
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, tint = palette.accentViolet, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save Draft Request & Quotation", style = XMaxType.buttonCompact, color = palette.textPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequirementServiceRow(
    item: CatalogueItem,
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    val palette = XMaxTheme.colors
    val selected = quantity > 0
    val priceLabel = when (item.pricingModeEnum()) {
        PricingMode.PERCENTAGE_SURCHARGE -> "+${item.surchargePercent}% Surcharge"
        PricingMode.CUSTOM_QUOTE -> "Custom Quote"
        PricingMode.ACTUAL_COST -> "Actual Cost"
        else -> MoneyFormatter.formatInr(item.pricePaise)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) palette.softVioletBg else palette.surface,
        border = BorderStroke(1.dp, if (selected) VioletPrimary.copy(alpha = 0.45f) else palette.outline),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("req_service_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                Text("${item.category} • $priceLabel", style = XMaxType.caption1.tabular(), color = palette.textSecondary)
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = XMaxType.caption1,
                        color = palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (item.pricingModeEnum() == PricingMode.PERCENTAGE_SURCHARGE) {
                OutlinedButton(
                    onClick = onIncrement,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.height(48.dp)
                ) { Text("+25% Rush", style = XMaxType.caption1Semibold) }
            } else if (!selected) {
                Button(
                    onClick = onIncrement,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.buttonPrimaryBg,
                        contentColor = palette.buttonPrimaryFg
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("req_add_service_${item.id}")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add ${item.name}", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", style = XMaxType.caption1Semibold)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onDecrement,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(palette.surface)
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity", tint = palette.textPrimary)
                    }
                    Text(
                        text = "$quantity",
                        style = XMaxType.numericHeadline,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = onIncrement,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(palette.buttonPrimaryBg)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase quantity", tint = palette.buttonPrimaryFg)
                    }
                }
            }
        }
    }
}

@Composable
fun StructuredQuotationSummaryCard(
    summary: StructuredQuotationSummary,
    companyName: String,
    includeGst: Boolean,
    gstRatePercent: Int,
    advancePercent: Int,
    modifier: Modifier = Modifier
) {
    val palette = XMaxTheme.colors
    val calc = summary.calculation

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
        shadowElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("structured_quote_summary_card")
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                        Text(
                            text = "${companyName.ifBlank { "X.MAX" }} • CLIENT QUOTATION",
                            style = XMaxType.caption1Semibold,
                            color = VioletPrimary
                        )
                    }
                    Text(
                        text = summary.projectTitle,
                        style = XMaxType.secondaryTitle,
                        color = palette.textPrimary
                    )
                    Text(
                        text = buildString {
                            append("Prepared for ${summary.clientDisplayName}")
                            if (summary.contactDisplayName.isNotBlank()) append(" (${summary.contactDisplayName})")
                        },
                        style = XMaxType.footnote,
                        color = palette.textSecondary
                    )
                }
                StatusPill(
                    label = if (summary.isReady) "Ready" else "Draft",
                    containerColor = if (summary.isReady) palette.mintBg else palette.butterBg,
                    contentColor = if (summary.isReady) palette.mintInk else palette.butterInk
                )
            }

            HorizontalDivider(color = palette.outline)

            // Scope & Deliverables
            if (summary.scopeHighlights.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                        Text("Project Requirements & Scope", style = XMaxType.subheadlineSemibold, color = palette.textPrimary)
                    }
                    summary.scopeHighlights.forEach { line ->
                        Text("• $line", style = XMaxType.footnote, color = palette.textSecondary)
                    }
                }
                HorizontalDivider(color = palette.outline)
            }

            // Itemized Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Itemized Schedule (${calc.computedLines.size})", style = XMaxType.subheadlineSemibold.tabular(), color = palette.textPrimary)
                if (calc.computedLines.isEmpty()) {
                    Text("No services added yet. Select items in the Requirements tab.", style = XMaxType.footnote, color = palette.textSecondary)
                } else {
                    calc.computedLines.forEachIndexed { idx, line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                val qtyTag = if (line.quantity > 1) " ×${line.quantity}" else ""
                                Text(
                                    text = "${idx + 1}. ${line.title}$qtyTag",
                                    style = XMaxType.subheadlineMedium,
                                    color = palette.textPrimary
                                )
                                Text(line.category, style = XMaxType.caption1, color = palette.textSecondary)
                            }
                            Text(
                                text = if (line.separatelyBilled) "At Actuals" else MoneyFormatter.formatInr(line.lineTotalPaise),
                                style = XMaxType.subheadlineSemibold.tabular(),
                                color = palette.textPrimary
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = palette.outline)

            // Commercial Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryMoneyRow("Subtotal", MoneyFormatter.formatInr(calc.subtotalPaise))
                if (calc.surchargePaise > 0L) {
                    SummaryMoneyRow("Urgent Delivery (+25%)", MoneyFormatter.formatInr(calc.surchargePaise))
                }
                if (calc.discountPaise > 0L) {
                    SummaryMoneyRow("Commercial Discount", "-${MoneyFormatter.formatInr(calc.discountPaise)}")
                }
                if (includeGst && calc.gstAmountPaise > 0L) {
                    SummaryMoneyRow("GST ($gstRatePercent%)", MoneyFormatter.formatInr(calc.gstAmountPaise))
                }
                HorizontalDivider(color = palette.outline.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Grand Total", style = XMaxType.headline, color = palette.textPrimary)
                    Text(
                        text = MoneyFormatter.formatInr(calc.totalPaise),
                        style = XMaxType.numericSection,
                        color = palette.textPrimary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surfaceMuted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Advance ($advancePercent%)", style = XMaxType.caption1, color = palette.textSecondary)
                            Text(MoneyFormatter.formatInr(calc.advancePaise), style = XMaxType.subheadlineSemibold.tabular(), color = EmeraldApproved)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Balance on Delivery", style = XMaxType.caption1, color = palette.textSecondary)
                            Text(MoneyFormatter.formatInr(calc.balancePaise), style = XMaxType.subheadlineSemibold.tabular(), color = palette.textPrimary)
                        }
                    }
                }
            }

            // Timeline & Terms
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.kpiLavenderBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                        Text("${summary.validityLabel} • Delivery & Terms", style = XMaxType.caption1Semibold, color = palette.textPrimary)
                    }
                    Text("Timeline: ${summary.timelineNote}", style = XMaxType.caption1, color = palette.textSecondary)
                    Text(summary.revisionPolicySummary, style = XMaxType.caption1, color = palette.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun SummaryMoneyRow(label: String, value: String) {
    val palette = XMaxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = XMaxType.footnote, color = palette.textSecondary)
        Text(value, style = XMaxType.footnoteSemibold.tabular(), color = palette.textPrimary)
    }
}
