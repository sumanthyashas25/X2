package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Inter typography family bundled locally in res/font/ across all 8 discrete static weights (200-900).
 * Serves as an offline, high-legibility Android alternative to Apple San Francisco (SF Pro)
 * without artificial bolding or network font fetches.
 */
val InterFontFamily = FontFamily(
    Font(R.font.inter_extralight, FontWeight.ExtraLight),
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
    Font(R.font.inter_black, FontWeight.Black)
)

val DisplayFontFamily = InterFontFamily
val BodyFontFamily = InterFontFamily

val MonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

/** Enables tabular (monospaced) numerals for aligned currency, statistics, timers, and tables. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * Centralized Apple Human Interface Guidelines-inspired typography tokens for X.MAX.
 * Supports Android accessibility font scaling via sp units and balanced tracking/line heights.
 */
@Immutable
data class XMaxTextTokens(
    /** Large screen / hero title: 34sp, Bold 700. */
    val largeTitle: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.4).sp
    ),
    /** Primary screen title: 28sp, Bold 700. */
    val primaryTitle: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp
    ),
    /** Section heading (e.g., Overview, Projects, Current Tracking): 22sp, Bold 700. */
    val sectionHeading: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    /** Secondary title (sheets, dialogs, grouped panels): 20sp, Semibold 600. */
    val secondaryTitle: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.15).sp
    ),
    /** Headline, card headings, user names (lists), client names, project names: 17sp, Semibold 600. */
    val headline: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    /** Dashboard welcome header user name: 17sp, Bold 700. */
    val userNameDashboard: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    /** Body text & text input text: 17sp, Regular 400. */
    val body: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.05).sp
    ),
    /** Callout text: 16sp, Regular 400. */
    val callout: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.05).sp
    ),
    /** Callout emphasis where subtle prominence is needed: 16sp, Semibold 600. */
    val calloutSemibold: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.05).sp
    ),
    /** Subheadline / secondary descriptions: 15sp, Regular 400. */
    val subheadline: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    /** Input labels & medium subheadlines: 15sp, Medium 500. */
    val subheadlineMedium: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    /** Compact row titles / emphasized subheadlines: 15sp, Semibold 600. */
    val subheadlineSemibold: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    /** Input field label alias: 15sp, Medium 500. */
    val inputLabel: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    /** Primary button text: 17sp, Semibold 600. */
    val button: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    /** Compact / split button text where 17sp would wrap/clip: 15sp, Semibold 600. */
    val buttonCompact: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    /** Statistical / KPI card big number: 34sp, Bold 700 with tabular numerals. */
    val statValue: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum"
    ),
    /** Statistical card label: 13sp, Medium 500. */
    val statLabel: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    /** Footnote text (also Dashboard "Welcome"): 13sp, Regular 400. */
    val footnote: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    /** Emphasized footnote / chip label: 13sp, Medium 500. */
    val footnoteMedium: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    /** Status pill / small interactive action label: 13sp, Semibold 600. */
    val footnoteSemibold: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    /** Caption 1: 12sp, Regular 400. */
    val caption1: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    /** Caption 1 Medium & Navigation labels: 12sp, Medium 500. */
    val caption1Medium: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    /** Caption 1 Semibold (tiny badges / counters): 12sp, Semibold 600. */
    val caption1Semibold: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    /** Caption 2: 11sp, Regular 400. */
    val caption2: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.05.sp
    ),
    /** Caption 2 Medium / micro tags: 11sp, Medium 500. */
    val caption2Medium: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.05.sp
    ),
    /** Bottom bar / rail navigation label: 11.5sp, Medium 500. */
    val navLabel: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    ),
    // Tabular numeral presets for currency totals, counts, timers, and numerical tables
    val numericTitle: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum"
    ),
    val numericSection: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum"
    ),
    val numericHeadline: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
        fontFeatureSettings = "tnum"
    ),
    val numericBody: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.05).sp,
        fontFeatureSettings = "tnum"
    ),
    val numericSubheadline: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    ),
    val numericFootnote: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    ),
    val numericCaption1: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    ),
    /** Monospaced tabular style for IDs, invoice numbers, and live audio timers. */
    val monoTimer: TextStyle = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum"
    )
)

val XMaxType = XMaxTextTokens()

val LocalXMaxTypography = staticCompositionLocalOf { XMaxType }

val XMaxTypography = Typography(
    displayLarge = XMaxType.largeTitle,
    displayMedium = XMaxType.primaryTitle,
    displaySmall = XMaxType.statValue,
    headlineLarge = XMaxType.primaryTitle,
    headlineMedium = XMaxType.sectionHeading,
    headlineSmall = XMaxType.sectionHeading,
    titleLarge = XMaxType.secondaryTitle,
    titleMedium = XMaxType.headline,
    titleSmall = XMaxType.subheadlineSemibold,
    bodyLarge = XMaxType.body,
    bodyMedium = XMaxType.subheadline,
    bodySmall = XMaxType.footnote,
    labelLarge = XMaxType.button,
    labelMedium = XMaxType.navLabel,
    labelSmall = XMaxType.caption2
)
