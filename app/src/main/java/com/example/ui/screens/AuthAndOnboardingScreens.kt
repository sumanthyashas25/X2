package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PhoneVerificationSession
import com.example.model.MembershipStatus
import com.example.ui.AgencyUiState
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
private fun Modifier.cleanLightBackdrop(): Modifier = this.background(XMaxTheme.colors.canvas)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    authInProgress: Boolean,
    errorMessage: String?,
    phoneSession: PhoneVerificationSession = PhoneVerificationSession(),
    onGoogleSignIn: () -> Unit,
    onDismissError: () -> Unit = {},
    onSendPhoneOtp: (String) -> Unit = {},
    onVerifyPhoneOtp: (String) -> Unit = {},
    onResetPhoneAuth: () -> Unit = {}
) {
    val palette = XMaxTheme.colors
    var showPhoneSheet by remember { mutableStateOf(false) }
    var activeLegalTopic by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxSize()
                .align(Alignment.Center)
                .systemBarsPadding()
        ) {
            val minScreenHeight = maxHeight
            val topSpacer = (minScreenHeight * 0.14f).coerceIn(24.dp, 88.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minScreenHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top subtle X.MAX wordmark badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XMaxLogoImage(tint = palette.textPrimary.copy(alpha = 0.78f), height = 14.dp)
                }

                // Main centered identity, headline, and auth actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topSpacer * 0.35f, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoginGreenUserEmblem(
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("login_hero_emblem")
                    )

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = "Sign in to continue",
                        style = XMaxType.primaryTitle,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Please try one of the following ways to\nregister or log in to your account.",
                        style = XMaxType.subheadline,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        XMaxToastBanner(
                            title = "Sign-in Issue",
                            message = errorMessage,
                            tone = ToastTone.ERROR,
                            onDismiss = onDismissError
                        )
                    }

                    Spacer(Modifier.height(34.dp))

                    // Primary Solid Google Button
                    Surface(
                        onClick = { if (!authInProgress) onGoogleSignIn() },
                        enabled = !authInProgress,
                        shape = RoundedCornerShape(999.dp),
                        color = palette.buttonPrimaryBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("continue_with_google_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (authInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.buttonPrimaryFg
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Signing in…",
                                    style = XMaxType.button,
                                    color = palette.buttonPrimaryFg
                                )
                            } else {
                                GoogleGLogo(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Continue with Google",
                                    style = XMaxType.button,
                                    color = palette.buttonPrimaryFg
                                )
                            }
                        }
                    }
                }

                // Bottom green-accent legal footer matching Login_ui_referance_f765934b.jpg
                val legalCaption = remember {
                    buildAnnotatedString {
                        append("By continuing, you confirm that you've read\nand agreed to the ")
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "terms",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = LoginGreenAccent,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.None
                                    )
                                ),
                                linkInteractionListener = { activeLegalTopic = "Terms of Service" }
                            )
                        ) {
                            append("Terms of Service")
                        }
                        append(" and\nconsent the ")
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "privacy",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = LoginGreenAccent,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.None
                                    )
                                ),
                                linkInteractionListener = { activeLegalTopic = "Privacy Policy" }
                            )
                        ) {
                            append("Privacy Policy")
                        }
                        append(".")
                    }
                }

                Text(
                    text = legalCaption,
                    style = XMaxType.caption1.copy(
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag("login_terms_caption")
                )
            }
        }
    }

    if (showPhoneSheet) {
        PhoneAuthBottomSheet(
            session = phoneSession,
            onDismiss = {
                showPhoneSheet = false
                onResetPhoneAuth()
            },
            onSendOtp = onSendPhoneOtp,
            onVerifyOtp = onVerifyPhoneOtp,
            onChangeNumber = onResetPhoneAuth
        )
    }

    if (activeLegalTopic != null) {
        ModalBottomSheet(
            onDismissRequest = { activeLegalTopic = null },
            containerColor = palette.surface,
            contentColor = palette.textPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = activeLegalTopic ?: "",
                    style = XMaxType.secondaryTitle,
                    color = palette.textPrimary
                )
                Text(
                    text = if (activeLegalTopic == "Terms of Service") {
                        "Access to this workspace is restricted to authorized team members and approved collaborators. Client quotations, deliverables, and pricing details shared inside the workspace remain confidential."
                    } else {
                        "Authentication identifiers (Google account email or verified phone number) and profile details submitted during onboarding are stored securely in Firebase Authentication and Cloud Firestore for team role access and activity auditing."
                    },
                    style = XMaxType.subheadline,
                    color = palette.textSecondary
                )
                Button(
                    onClick = { activeLegalTopic = null },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.buttonPrimaryBg,
                        contentColor = palette.buttonPrimaryFg
                    ),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Close", style = XMaxType.button)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LoginGreenUserEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 3.2.dp.toPx()
        val w = size.width
        val h = size.height
        // Head circle
        drawCircle(
            color = LoginGreenAccent,
            radius = w * 0.16f,
            center = Offset(w * 0.42f, h * 0.30f),
            style = Stroke(width = stroke)
        )
        // Rounded shoulder capsule
        drawRoundRect(
            color = LoginGreenAccent,
            topLeft = Offset(w * 0.14f, h * 0.56f),
            size = Size(w * 0.56f, h * 0.28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f, h * 0.14f),
            style = Stroke(width = stroke)
        )
        // Plus (+) badge on upper right
        val px = w * 0.78f
        val py = h * 0.30f
        val arm = w * 0.10f
        drawLine(
            color = LoginGreenAccent,
            start = Offset(px - arm, py),
            end = Offset(px + arm, py),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = LoginGreenAccent,
            start = Offset(px, py - arm),
            end = Offset(px, py + arm),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneAuthBottomSheet(
    session: PhoneVerificationSession,
    onDismiss: () -> Unit,
    onSendOtp: (String) -> Unit,
    onVerifyOtp: (String) -> Unit,
    onChangeNumber: () -> Unit
) {
    val palette = XMaxTheme.colors
    var phoneInput by remember(session.phoneNumber) { mutableStateOf(session.phoneNumber.ifBlank { "+91" }) }
    var otpInput by remember { mutableStateOf("") }
    val awaitingOtp = !session.verificationId.isNullOrBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (awaitingOtp) "Enter Verification Code" else "Continue with Phone",
                style = XMaxType.secondaryTitle,
                color = palette.textPrimary
            )
            Text(
                text = if (awaitingOtp) {
                    "Enter the SMS code sent to ${session.phoneNumber}."
                } else {
                    "Enter your mobile number with country code (for example +919876543210)."
                },
                style = XMaxType.subheadline,
                color = palette.textSecondary
            )

            if (!awaitingOtp) {
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("Phone Number (E.164)", style = XMaxType.inputLabel) },
                    textStyle = XMaxType.body.tabular(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.canvas,
                        unfocusedContainerColor = palette.canvas,
                        focusedBorderColor = palette.textPrimary,
                        unfocusedBorderColor = palette.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_number_input")
                )
            } else {
                OutlinedTextField(
                    value = otpInput,
                    onValueChange = { ch -> otpInput = ch.filter { it.isDigit() }.take(6) },
                    label = { Text("6-Digit SMS Code", style = XMaxType.inputLabel) },
                    textStyle = XMaxType.body.tabular(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.canvas,
                        unfocusedContainerColor = palette.canvas,
                        focusedBorderColor = palette.textPrimary,
                        unfocusedBorderColor = palette.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_otp_input")
                )
            }

            if (!session.errorMessage.isNullOrBlank()) {
                Text(
                    text = session.errorMessage,
                    style = XMaxType.footnote,
                    color = CrimsonBlocked
                )
            }

            Button(
                onClick = {
                    if (awaitingOtp) onVerifyOtp(otpInput) else onSendOtp(phoneInput)
                },
                enabled = !session.inProgress,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.buttonPrimaryBg,
                    contentColor = palette.buttonPrimaryFg
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("phone_auth_submit_button")
            ) {
                if (session.inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = palette.buttonPrimaryFg
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", style = XMaxType.button)
                } else {
                    Text(
                        text = if (awaitingOtp) "Verify & Continue" else "Send Verification Code",
                        style = XMaxType.button
                    )
                }
            }

            if (awaitingOtp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            otpInput = ""
                            onChangeNumber()
                        }
                    ) {
                        Text("Change Number", style = XMaxType.buttonCompact, color = palette.textSecondary)
                    }
                    TextButton(
                        onClick = { onSendOtp(session.phoneNumber) },
                        enabled = !session.inProgress
                    ) {
                        Text("Resend Code", style = XMaxType.buttonCompact, color = palette.textPrimary)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun GoogleGLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.20f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = Color(0xFFEA4335),
            startAngle = -155f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 145f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -5f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.52f, size.height * 0.5f),
            end = Offset(size.width * 0.92f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt
        )
    }
}

@Composable
fun OnboardingScreen(
    state: AgencyUiState,
    onSubmitOnboarding: (company: String, fullName: String, age: Int, dept: String, role: String, phone: String) -> Unit,
    onBootstrapSuperAdmin: (fullName: String, age: Int) -> Unit,
    onSignOut: () -> Unit
) {
    val palette = XMaxTheme.colors
    val isDark = XMaxTheme.isDark
    val user = state.firebaseUser
    var companyName by remember { mutableStateOf("X.MAX") }
    var fullName by remember(user?.uid) { mutableStateOf(user?.displayName ?: "") }
    var ageText by remember { mutableStateOf("") }
    var phone by remember(user?.uid) { mutableStateOf(user?.phoneNumber ?: "") }
    var requestedDept by remember { mutableStateOf("Creative") }
    var requestedRole by remember { mutableStateOf("Graphic Designer") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val deptSuggestions = listOf("Creative", "Web", "Video", "Marketing", "Operations")
    val roleSuggestions = listOf(
        "Graphic Designer", "Brand Strategist", "Web Developer",
        "UI/UX Designer", "Video Editor", "Performance Marketer",
        "SEO & Content Specialist", "Project Manager"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .cleanLightBackdrop()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                XMaxWordmarkBadge("X.MAX Onboarding")
                Surface(
                    onClick = onSignOut,
                    shape = RoundedCornerShape(999.dp),
                    color = palette.surfaceMuted,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("onboarding_sign_out_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = palette.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Sign Out",
                            color = palette.textPrimary,
                            style = XMaxType.buttonCompact
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!state.errorMessage.isNullOrBlank()) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = palette.blushBg)
                    ) {
                        Text(
                            text = state.errorMessage,
                            color = palette.blushInk,
                            style = XMaxType.footnote,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                if (state.isBootstrappedEmail) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (isDark) palette.surfaceElevated else ObsidianCharcoal,
                        border = if (isDark) BorderStroke(1.dp, palette.outline) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(shape = RoundedCornerShape(999.dp), color = palette.infoBg) {
                                    Text(
                                        text = "Super Admin Ready",
                                        style = XMaxType.caption1Semibold,
                                        color = palette.infoInk,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Text(
                                "Trusted Authority Setup",
                                style = XMaxType.secondaryTitle,
                                color = PureWhite
                            )
                            Text(
                                "Verified Google account (${user?.email}) is authorized to provision the X.MAX workspace, starting 40-service catalogue, 5 packages, 11 add-ons, and Super Admin access.",
                                style = XMaxType.subheadline,
                                color = if (isDark) palette.textSecondary else Color(0xFFB5B9C4)
                            )
                            Button(
                                onClick = {
                                    val parsedAge = ageText.toIntOrNull()?.coerceIn(16, 100) ?: 28
                                    onBootstrapSuperAdmin(fullName.ifBlank { "X.MAX Director" }, parsedAge)
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDark) palette.buttonPrimaryBg else PureWhite,
                                    contentColor = if (isDark) palette.buttonPrimaryFg else ObsidianCharcoal
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("bootstrap_super_admin_button")
                            ) {
                                Icon(Icons.Filled.Shield, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Provision X.MAX as Super Admin", style = XMaxType.button)
                            }
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = palette.surfaceMuted),
                    border = BorderStroke(1.dp, palette.outline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Employee Membership Request",
                            style = XMaxType.secondaryTitle,
                            color = palette.textPrimary
                        )
                        Text(
                            "Signed in as ${user?.email ?: user?.phoneNumber ?: "Unknown"}. Requests require Super Admin approval unless an invitation already matches your email.",
                            style = XMaxType.subheadline,
                            color = palette.textSecondary
                        )

                        OutlinedTextField(
                            value = companyName,
                            onValueChange = { companyName = it },
                            label = { Text("Company Name", style = XMaxType.inputLabel) },
                            textStyle = XMaxType.body,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surface,
                                unfocusedContainerColor = palette.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_company_input")
                        )

                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it },
                            label = { Text("Employee Full Name", style = XMaxType.inputLabel) },
                            textStyle = XMaxType.body,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surface,
                                unfocusedContainerColor = palette.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_name_input")
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = ageText,
                                onValueChange = { ageText = it.filter { ch -> ch.isDigit() }.take(3) },
                                label = { Text("Age (Private)", style = XMaxType.inputLabel) },
                                textStyle = XMaxType.body.tabular(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = palette.surface,
                                    unfocusedContainerColor = palette.surface
                                ),
                                modifier = Modifier
                                    .weight(0.42f)
                                    .testTag("onboarding_age_input")
                            )
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("Phone (Optional)", style = XMaxType.inputLabel) },
                                textStyle = XMaxType.body.tabular(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = palette.surface,
                                    unfocusedContainerColor = palette.surface
                                ),
                                modifier = Modifier.weight(0.58f)
                            )
                        }
                        Text(
                            "Privacy: Your age & contact details are stored separately and visible only to you and Super Admins.",
                            style = XMaxType.caption1,
                            color = palette.textSecondary
                        )

                        Text("Requested Department", style = XMaxType.inputLabel, color = palette.textPrimary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            deptSuggestions.take(3).forEach { dept ->
                                FilterChip(
                                    selected = requestedDept.equals(dept, ignoreCase = true),
                                    onClick = { requestedDept = dept },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.buttonPrimaryBg,
                                        selectedLabelColor = palette.buttonPrimaryFg,
                                        containerColor = palette.surface,
                                        labelColor = palette.textPrimary
                                    ),
                                    border = null,
                                    label = { Text(dept, style = XMaxType.footnoteSemibold) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = requestedDept,
                            onValueChange = { requestedDept = it },
                            label = { Text("Department", style = XMaxType.inputLabel) },
                            textStyle = XMaxType.body,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surface,
                                unfocusedContainerColor = palette.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_department_input")
                        )

                        Text("Requested Job Role", style = XMaxType.inputLabel, color = palette.textPrimary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            roleSuggestions.take(2).forEach { r ->
                                FilterChip(
                                    selected = requestedRole.equals(r, ignoreCase = true),
                                    onClick = { requestedRole = r },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.buttonPrimaryBg,
                                        selectedLabelColor = palette.buttonPrimaryFg,
                                        containerColor = palette.surface,
                                        labelColor = palette.textPrimary
                                    ),
                                    border = null,
                                    label = { Text(r, maxLines = 1, style = XMaxType.footnoteSemibold) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = requestedRole,
                            onValueChange = { requestedRole = it },
                            label = { Text("Job Role", style = XMaxType.inputLabel) },
                            textStyle = XMaxType.body,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surface,
                                unfocusedContainerColor = palette.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_job_role_input")
                        )

                        validationError?.let { err ->
                            Text(err, color = CrimsonBlocked, style = XMaxType.footnote)
                        }

                        Button(
                            onClick = {
                                val age = ageText.toIntOrNull()
                                when {
                                    companyName.isBlank() -> validationError = "Enter your company name."
                                    fullName.isBlank() -> validationError = "Enter your full name."
                                    age == null || age !in 16..100 -> validationError = "Enter a valid age between 16 and 100."
                                    requestedDept.isBlank() || requestedRole.isBlank() -> validationError = "Specify department and job role."
                                    else -> {
                                        validationError = null
                                        onSubmitOnboarding(companyName, fullName, age, requestedDept, requestedRole, phone)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.buttonPrimaryBg,
                                contentColor = palette.buttonPrimaryFg
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("submit_onboarding_button")
                        ) {
                            Text("Submit Membership Request", style = XMaxType.button)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PendingApprovalScreen(
    state: AgencyUiState,
    onBootstrapSuperAdmin: (fullName: String) -> Unit,
    onSignOut: () -> Unit
) {
    val palette = XMaxTheme.colors
    val isDark = XMaxTheme.isDark
    val member = state.myMember
    val status = member?.statusEnum() ?: MembershipStatus.PENDING

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.outline),
                shadowElevation = 12.dp
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Soft top halo inside the card behind the clock badge
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(176.dp)
                            .align(Alignment.TopCenter)
                    ) {
                        val w = size.width
                        val h = size.height
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = if (isDark) listOf(
                                    palette.blushBg.copy(alpha = 0.55f),
                                    palette.lavenderBg.copy(alpha = 0.35f),
                                    Color.Transparent
                                ) else listOf(
                                    Color(0xFFFCE7F3).copy(alpha = 0.72f),
                                    Color(0xFFEDE9FE).copy(alpha = 0.42f),
                                    Color.Transparent
                                ),
                                center = Offset(w * 0.36f, h * 0.16f),
                                radius = w * 0.56f
                            )
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = if (isDark) listOf(
                                    palette.skyBg.copy(alpha = 0.55f),
                                    palette.lavenderBg.copy(alpha = 0.30f),
                                    Color.Transparent
                                ) else listOf(
                                    Color(0xFFE0F2FE).copy(alpha = 0.70f),
                                    Color(0xFFEDE9FE).copy(alpha = 0.38f),
                                    Color.Transparent
                                ),
                                center = Offset(w * 0.66f, h * 0.18f),
                                radius = w * 0.54f
                            )
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 30.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(6.dp))

                        // Floating circular badge with clock icon
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            color = palette.surface,
                            border = BorderStroke(1.dp, palette.outline),
                            shadowElevation = 4.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (state.isBootstrappedEmail) Icons.Filled.Shield else Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    tint = if (state.isBootstrappedEmail) palette.textPrimary else palette.lavenderInk,
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = when {
                                state.isBootstrappedEmail -> "Super Admin ready..."
                                status == MembershipStatus.PENDING -> "Membership pending..."
                                status == MembershipStatus.DECLINED -> "Request declined..."
                                status == MembershipStatus.SUSPENDED -> "Account inactive..."
                                else -> "Workspace pending..."
                            },
                            style = XMaxType.sectionHeading,
                            color = palette.textPrimary,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = when {
                                state.isBootstrappedEmail ->
                                    "Signed in as default Super Admin (${state.firebaseUser?.email}). Tap below to activate workspace access."
                                status == MembershipStatus.PENDING ->
                                    "Your account is awaiting Super Admin approval.\nPlease wait."
                                status == MembershipStatus.DECLINED ->
                                    "Your access request was declined by an administrator."
                                status == MembershipStatus.SUSPENDED ->
                                    "Your membership is currently inactive. Contact your Super Admin."
                                else ->
                                    "Your account is awaiting workspace authorization.\nPlease wait."
                            },
                            style = XMaxType.subheadline,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center
                        )

                        val identity = state.firebaseUser?.email ?: state.firebaseUser?.phoneNumber ?: member?.fullName
                        if (!identity.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = palette.surfaceMuted
                            ) {
                                Text(
                                    text = identity,
                                    style = XMaxType.caption1,
                                    color = palette.textSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }

                        if (!state.errorMessage.isNullOrBlank()) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = palette.blushBg)
                            ) {
                                Text(
                                    text = state.errorMessage,
                                    color = palette.blushInk,
                                    style = XMaxType.footnote,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        if (state.isBootstrappedEmail) {
                            Button(
                                onClick = {
                                    onBootstrapSuperAdmin(
                                        member?.fullName?.ifBlank { null }
                                            ?: state.firebaseUser?.displayName
                                            ?: "X.MAX Director"
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.buttonPrimaryBg,
                                    contentColor = palette.buttonPrimaryFg
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("pending_bootstrap_super_admin_button")
                            ) {
                                Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Activate Super Admin Access", style = XMaxType.button)
                            }
                        }

                        // Soft-pill Sign Out Button
                        Surface(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(999.dp),
                            color = palette.surfaceMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("pending_sign_out_button")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = "Sign Out",
                                    style = XMaxType.button,
                                    color = palette.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
