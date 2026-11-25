package com.example.ui.theme

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val label: String) {
    SYSTEM("Auto"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class AppAccentColor(
    val label: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
    val lightSoftBg: Color,
    val darkSoftBg: Color,
    val lightInk: Color,
    val darkInk: Color
) {
    VIOLET(
        label = "Electric Violet",
        lightPrimary = Color(0xFF6E56CF),
        darkPrimary = Color(0xFF9E86FF),
        lightSoftBg = Color(0xFFF3F0FF),
        darkSoftBg = Color(0xFF231E3E),
        lightInk = Color(0xFF5B34DA),
        darkInk = Color(0xFFB4A3FF)
    ),
    EMERALD(
        label = "Emerald Mint",
        lightPrimary = Color(0xFF059669),
        darkPrimary = Color(0xFF34D399),
        lightSoftBg = Color(0xFFECFDF5),
        darkSoftBg = Color(0xFF132D22),
        lightInk = Color(0xFF047857),
        darkInk = Color(0xFF6EE7B7)
    ),
    OCEAN(
        label = "Ocean Blue",
        lightPrimary = Color(0xFF0284C7),
        darkPrimary = Color(0xFF38BDF8),
        lightSoftBg = Color(0xFFF0F9FF),
        darkSoftBg = Color(0xFF162432),
        lightInk = Color(0xFF0369A1),
        darkInk = Color(0xFF7DD3FC)
    ),
    CORAL(
        label = "Sunset Coral",
        lightPrimary = Color(0xFFEA580C),
        darkPrimary = Color(0xFFFB923C),
        lightSoftBg = Color(0xFFFFF7ED),
        darkSoftBg = Color(0xFF2B1D19),
        lightInk = Color(0xFFC2410C),
        darkInk = Color(0xFFFDBA74)
    ),
    AMBER(
        label = "Solar Amber",
        lightPrimary = Color(0xFFD97706),
        darkPrimary = Color(0xFFFBBF24),
        lightSoftBg = Color(0xFFFFFBEB),
        darkSoftBg = Color(0xFF312412),
        lightInk = Color(0xFFB45309),
        darkInk = Color(0xFFFCD34D)
    ),
    ROSE(
        label = "Neon Rose",
        lightPrimary = Color(0xFFE11D48),
        darkPrimary = Color(0xFFFB7185),
        lightSoftBg = Color(0xFFFFF1F2),
        darkSoftBg = Color(0xFF33181C),
        lightInk = Color(0xFFBE123C),
        darkInk = Color(0xFFFDA4AF)
    )
}

enum class AppButtonTheme(val label: String) {
    ACCENT("Brand Accent"),
    OBSIDIAN("Classic Monolith"),
    VIBRANT("Neon Contrast")
}

enum class AppNavHighlight(val label: String, val lightColor: Color, val darkColor: Color) {
    MATCH_ACCENT("Match Accent", Color.Unspecified, Color.Unspecified),
    EMERALD_PULSE("Emerald Pulse", Color(0xFF10B981), Color(0xFF34D399)),
    VIOLET_ORB("Royal Violet", Color(0xFF7C3AED), Color(0xFF9E86FF)),
    CYAN_ELECTRIC("Electric Cyan", Color(0xFF06B6D4), Color(0xFF38BDF8))
}

enum class AppDashboardTint(val label: String) {
    DYNAMIC_ACCENT("Dynamic Accent"),
    PASTEL_STUDIO("Classic Studio"),
    SLEEK_SLATE("Deep Slate")
}

enum class AppIconTheme(val label: String, val badgeColor: Color, val description: String) {
    CLASSIC_VIOLET("Classic Violet", Color(0xFF6E56CF), "Original studio violet monogram"),
    EMERALD_AGENCY("Emerald Glow", Color(0xFF059669), "Modern WhatsApp-inspired emerald"),
    OBSIDIAN_DARK("Dark Obsidian", Color(0xFF111827), "Minimalist high-contrast obsidian"),
    PASTEL_AURA("Pastel Mesh", Color(0xFFFB923C), "Multi-gradient sunset vibe")
}

object AppAppearanceStore {
    private const val PREF_NAME = "xmax_appearance_v1"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_BUTTON_THEME = "button_theme"
    private const val KEY_NAV_HIGHLIGHT = "nav_highlight"
    private const val KEY_DASHBOARD_TINT = "dashboard_tint"
    private const val KEY_APP_ICON_THEME = "app_icon_theme"

    @Volatile
    private var loaded = false

    private val _themeModeFlow = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeModeFlow: StateFlow<AppThemeMode> = _themeModeFlow.asStateFlow()

    private val _accentColorFlow = MutableStateFlow(AppAccentColor.VIOLET)
    val accentColorFlow: StateFlow<AppAccentColor> = _accentColorFlow.asStateFlow()

    private val _buttonThemeFlow = MutableStateFlow(AppButtonTheme.ACCENT)
    val buttonThemeFlow: StateFlow<AppButtonTheme> = _buttonThemeFlow.asStateFlow()

    private val _navHighlightFlow = MutableStateFlow(AppNavHighlight.MATCH_ACCENT)
    val navHighlightFlow: StateFlow<AppNavHighlight> = _navHighlightFlow.asStateFlow()

    private val _dashboardTintFlow = MutableStateFlow(AppDashboardTint.DYNAMIC_ACCENT)
    val dashboardTintFlow: StateFlow<AppDashboardTint> = _dashboardTintFlow.asStateFlow()

    private val _appIconThemeFlow = MutableStateFlow(AppIconTheme.CLASSIC_VIOLET)
    val appIconThemeFlow: StateFlow<AppIconTheme> = _appIconThemeFlow.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val rawMode = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
        _themeModeFlow.value = runCatching { AppThemeMode.valueOf(rawMode ?: AppThemeMode.SYSTEM.name) }
            .getOrDefault(AppThemeMode.SYSTEM)

        val rawAccent = prefs.getString(KEY_ACCENT, AppAccentColor.VIOLET.name)
        _accentColorFlow.value = runCatching { AppAccentColor.valueOf(rawAccent ?: AppAccentColor.VIOLET.name) }
            .getOrDefault(AppAccentColor.VIOLET)

        val rawButton = prefs.getString(KEY_BUTTON_THEME, AppButtonTheme.ACCENT.name)
        _buttonThemeFlow.value = runCatching { AppButtonTheme.valueOf(rawButton ?: AppButtonTheme.ACCENT.name) }
            .getOrDefault(AppButtonTheme.ACCENT)

        val rawNav = prefs.getString(KEY_NAV_HIGHLIGHT, AppNavHighlight.MATCH_ACCENT.name)
        _navHighlightFlow.value = runCatching { AppNavHighlight.valueOf(rawNav ?: AppNavHighlight.MATCH_ACCENT.name) }
            .getOrDefault(AppNavHighlight.MATCH_ACCENT)

        val rawDash = prefs.getString(KEY_DASHBOARD_TINT, AppDashboardTint.DYNAMIC_ACCENT.name)
        _dashboardTintFlow.value = runCatching { AppDashboardTint.valueOf(rawDash ?: AppDashboardTint.DYNAMIC_ACCENT.name) }
            .getOrDefault(AppDashboardTint.DYNAMIC_ACCENT)

        val rawIcon = prefs.getString(KEY_APP_ICON_THEME, AppIconTheme.CLASSIC_VIOLET.name)
        _appIconThemeFlow.value = runCatching { AppIconTheme.valueOf(rawIcon ?: AppIconTheme.CLASSIC_VIOLET.name) }
            .getOrDefault(AppIconTheme.CLASSIC_VIOLET)

        loaded = true
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        loaded = true
        _themeModeFlow.value = mode
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }

    fun setAccentColor(context: Context, accent: AppAccentColor) {
        loaded = true
        _accentColorFlow.value = accent
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT, accent.name)
            .apply()
    }

    fun setButtonTheme(context: Context, theme: AppButtonTheme) {
        loaded = true
        _buttonThemeFlow.value = theme
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUTTON_THEME, theme.name)
            .apply()
    }

    fun setNavHighlight(context: Context, highlight: AppNavHighlight) {
        loaded = true
        _navHighlightFlow.value = highlight
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAV_HIGHLIGHT, highlight.name)
            .apply()
    }

    fun setDashboardTint(context: Context, tint: AppDashboardTint) {
        loaded = true
        _dashboardTintFlow.value = tint
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DASHBOARD_TINT, tint.name)
            .apply()
    }

    fun setAppIconTheme(context: Context, iconTheme: AppIconTheme) {
        loaded = true
        _appIconThemeFlow.value = iconTheme
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_ICON_THEME, iconTheme.name)
            .apply()
    }

    internal fun resetForTesting(mode: AppThemeMode = AppThemeMode.SYSTEM) {
        loaded = false
        _themeModeFlow.value = mode
        _accentColorFlow.value = AppAccentColor.VIOLET
        _buttonThemeFlow.value = AppButtonTheme.ACCENT
        _navHighlightFlow.value = AppNavHighlight.MATCH_ACCENT
        _dashboardTintFlow.value = AppDashboardTint.DYNAMIC_ACCENT
        _appIconThemeFlow.value = AppIconTheme.CLASSIC_VIOLET
    }
}

@Immutable
data class StudioColorPalette(
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceMuted: Color,
    val surfaceSoft: Color,
    val dockBg: Color,
    val outline: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val iconInactive: Color,
    val buttonPrimaryBg: Color,
    val buttonPrimaryFg: Color,
    val accentViolet: Color,
    val softVioletBg: Color,
    val kpiLavenderBg: Color,
    val kpiLavenderStroke: Color,
    val lavenderBg: Color,
    val lavenderInk: Color,
    val kpiPeachBg: Color,
    val kpiPeachStroke: Color,
    val peachBg: Color,
    val peachStroke: Color,
    val kpiSkyBg: Color,
    val kpiSkyStroke: Color,
    val skyBg: Color,
    val skyInk: Color,
    val skyStroke: Color,
    val kpiMintBg: Color,
    val kpiMintStroke: Color,
    val butterBg: Color,
    val butterInk: Color,
    val mintBg: Color,
    val mintInk: Color,
    val blushBg: Color,
    val blushInk: Color,
    val amberBg: Color,
    val amberInk: Color,
    val infoBg: Color,
    val infoInk: Color,
    val ambientOrb1: Color,
    val ambientOrb2: Color,
    val ambientOrb3: Color,
    val glowRim: Color,
    val navHighlightColor: Color = Color(0xFF6E56CF)
)

val LightStudioColors = StudioColorPalette(
    canvas = CrispCanvas,
    surface = PureWhite,
    surfaceElevated = PureWhite,
    surfaceMuted = MutedSurface,
    surfaceSoft = SoftCardSurface,
    dockBg = PureWhite.copy(alpha = 0.96f),
    outline = SubtleOutline,
    divider = SubtleOutline,
    textPrimary = ObsidianCharcoal,
    textSecondary = StudioTextSecondary,
    iconInactive = DockIconInactive,
    buttonPrimaryBg = ObsidianCharcoal,
    buttonPrimaryFg = PureWhite,
    accentViolet = VioletPrimary,
    softVioletBg = VioletSoftBg,
    kpiLavenderBg = KpiLavenderBg,
    kpiLavenderStroke = KpiLavenderStroke,
    lavenderBg = KpiLavenderBg,
    lavenderInk = VioletPrimary,
    kpiPeachBg = KpiPeachBg,
    kpiPeachStroke = KpiPeachStroke,
    peachBg = KpiPeachBg,
    peachStroke = KpiPeachStroke,
    kpiSkyBg = KpiSkyBg,
    kpiSkyStroke = KpiSkyStroke,
    skyBg = KpiSkyBg,
    skyInk = DeepPeriwinkleInk,
    skyStroke = KpiSkyStroke,
    kpiMintBg = KpiMintBg,
    kpiMintStroke = KpiMintStroke,
    butterBg = ButterYellow,
    butterInk = DeepAmberInk,
    mintBg = SoftMint,
    mintInk = DeepMintInk,
    blushBg = SoftBlush,
    blushInk = DeepBlushInk,
    amberBg = ButterYellow,
    amberInk = DeepAmberInk,
    infoBg = InfoPillBg,
    infoInk = InfoPillInk,
    ambientOrb1 = Color(0xFFE9E6FF).copy(alpha = 0.52f),
    ambientOrb2 = Color(0xFFFFEDE4).copy(alpha = 0.56f),
    ambientOrb3 = Color(0xFFE5F7EE).copy(alpha = 0.40f),
    glowRim = PureWhite,
    navHighlightColor = VioletPrimary
)

val DarkStudioColors = StudioColorPalette(
    canvas = Color(0xFF0C0E13),
    surface = Color(0xFF151821),
    surfaceElevated = Color(0xFF1B202B),
    surfaceMuted = Color(0xFF1F2430),
    surfaceSoft = Color(0xFF181C26),
    dockBg = Color(0xFF151821).copy(alpha = 0.96f),
    outline = Color(0xFF2B3140),
    divider = Color(0xFF2B3140),
    textPrimary = Color(0xFFF3F5FA),
    textSecondary = Color(0xFF9BA4B8),
    iconInactive = Color(0xFF8C95A8),
    buttonPrimaryBg = Color(0xFFF3F5FA),
    buttonPrimaryFg = Color(0xFF0C0E13),
    accentViolet = Color(0xFF9178FF),
    softVioletBg = Color(0xFF231E3E),
    kpiLavenderBg = Color(0xFF211C38),
    kpiLavenderStroke = Color(0xFF9D87FF),
    lavenderBg = Color(0xFF211C38),
    lavenderInk = Color(0xFFB4A3FF),
    kpiPeachBg = Color(0xFF2B1D19),
    kpiPeachStroke = Color(0xFFFB923C),
    peachBg = Color(0xFF2B1D19),
    peachStroke = Color(0xFFFB923C),
    kpiSkyBg = Color(0xFF162432),
    kpiSkyStroke = Color(0xFF38BDF8),
    skyBg = Color(0xFF162432),
    skyInk = Color(0xFF7DD3FC),
    skyStroke = Color(0xFF38BDF8),
    kpiMintBg = Color(0xFF142821),
    kpiMintStroke = Color(0xFF34D399),
    butterBg = Color(0xFF312412),
    butterInk = Color(0xFFFCD34D),
    mintBg = Color(0xFF132D22),
    mintInk = Color(0xFF6EE7B7),
    blushBg = Color(0xFF33181C),
    blushInk = Color(0xFFFCA5A5),
    amberBg = Color(0xFF312412),
    amberInk = Color(0xFFFCD34D),
    infoBg = Color(0xFF17253B),
    infoInk = Color(0xFF93C5FD),
    ambientOrb1 = Color(0xFF4C35B5).copy(alpha = 0.16f),
    ambientOrb2 = Color(0xFF9A3E1F).copy(alpha = 0.12f),
    ambientOrb3 = Color(0xFF0E6B4F).copy(alpha = 0.14f),
    glowRim = Color(0xFF2C3344),
    navHighlightColor = Color(0xFF9178FF)
)

fun buildStudioPalette(
    isDark: Boolean,
    accent: AppAccentColor = AppAccentColor.VIOLET,
    buttonTheme: AppButtonTheme = AppButtonTheme.ACCENT,
    navHighlight: AppNavHighlight = AppNavHighlight.MATCH_ACCENT,
    dashboardTint: AppDashboardTint = AppDashboardTint.DYNAMIC_ACCENT
): StudioColorPalette {
    val base = if (isDark) DarkStudioColors else LightStudioColors
    val accentPrimary = if (isDark) accent.darkPrimary else accent.lightPrimary
    val accentSoftBg = if (isDark) accent.darkSoftBg else accent.lightSoftBg
    val accentInk = if (isDark) accent.darkInk else accent.lightInk

    val btnBg = when (buttonTheme) {
        AppButtonTheme.ACCENT -> accentPrimary
        AppButtonTheme.OBSIDIAN -> if (isDark) Color(0xFFF3F5FA) else ObsidianCharcoal
        AppButtonTheme.VIBRANT -> accentPrimary
    }
    val btnFg = when (buttonTheme) {
        AppButtonTheme.ACCENT -> PureWhite
        AppButtonTheme.OBSIDIAN -> if (isDark) Color(0xFF0C0E13) else PureWhite
        AppButtonTheme.VIBRANT -> PureWhite
    }

    val navColor = when (navHighlight) {
        AppNavHighlight.MATCH_ACCENT -> accentPrimary
        else -> if (isDark) navHighlight.darkColor else navHighlight.lightColor
    }

    val kpiLavender = when (dashboardTint) {
        AppDashboardTint.DYNAMIC_ACCENT -> accentSoftBg
        AppDashboardTint.SLEEK_SLATE -> if (isDark) Color(0xFF1B202B) else Color(0xFFF1F5F9)
        AppDashboardTint.PASTEL_STUDIO -> base.kpiLavenderBg
    }
    val kpiLavenderStroke = when (dashboardTint) {
        AppDashboardTint.DYNAMIC_ACCENT -> accentPrimary.copy(alpha = 0.5f)
        AppDashboardTint.SLEEK_SLATE -> if (isDark) Color(0xFF2B3140) else Color(0xFFE2E8F0)
        AppDashboardTint.PASTEL_STUDIO -> base.kpiLavenderStroke
    }
    val lavInk = when (dashboardTint) {
        AppDashboardTint.DYNAMIC_ACCENT -> accentInk
        AppDashboardTint.SLEEK_SLATE -> if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
        AppDashboardTint.PASTEL_STUDIO -> base.lavenderInk
    }

    return base.copy(
        buttonPrimaryBg = btnBg,
        buttonPrimaryFg = btnFg,
        accentViolet = accentPrimary,
        softVioletBg = accentSoftBg,
        kpiLavenderBg = kpiLavender,
        kpiLavenderStroke = kpiLavenderStroke,
        lavenderBg = kpiLavender,
        lavenderInk = lavInk,
        navHighlightColor = navColor
    )
}

val LocalStudioColors = staticCompositionLocalOf { LightStudioColors }
val LocalIsDarkTheme = staticCompositionLocalOf { false }

object XMaxTheme {
    val colors: StudioColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioColors.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalIsDarkTheme.current
}

private val StudioLightColors = lightColorScheme(
    primary = ObsidianCharcoal,
    onPrimary = PureWhite,
    primaryContainer = VioletSoftBg,
    onPrimaryContainer = VioletPrimary,
    secondary = VioletPrimary,
    onSecondary = PureWhite,
    secondaryContainer = MutedSurface,
    onSecondaryContainer = ObsidianCharcoal,
    tertiary = EmeraldApproved,
    onTertiary = PureWhite,
    tertiaryContainer = SoftMint,
    onTertiaryContainer = DeepMintInk,
    error = CrimsonBlocked,
    onError = PureWhite,
    errorContainer = SoftBlush,
    onErrorContainer = DeepBlushInk,
    background = CrispCanvas,
    onBackground = ObsidianCharcoal,
    surface = PureWhite,
    onSurface = ObsidianCharcoal,
    surfaceVariant = MutedSurface,
    onSurfaceVariant = StudioTextSecondary,
    outline = SubtleOutline
)

private val StudioDarkColors = darkColorScheme(
    primary = Color(0xFFF3F5FA),
    onPrimary = Color(0xFF0C0E13),
    primaryContainer = Color(0xFF232536),
    onPrimaryContainer = Color(0xFFF3F5FA),
    secondary = Color(0xFF9178FF),
    onSecondary = Color(0xFF0C0E13),
    secondaryContainer = Color(0xFF1F2430),
    onSecondaryContainer = Color(0xFFF3F5FA),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF0B251D),
    tertiaryContainer = Color(0xFF132D22),
    onTertiaryContainer = Color(0xFF6EE7B7),
    error = Color(0xFFF97066),
    onError = Color(0xFF1C0C0E),
    errorContainer = Color(0xFF33181C),
    onErrorContainer = Color(0xFFFCA5A5),
    background = Color(0xFF0C0E13),
    onBackground = Color(0xFFF3F5FA),
    surface = Color(0xFF151821),
    onSurface = Color(0xFFF3F5FA),
    surfaceVariant = Color(0xFF1F2430),
    onSurfaceVariant = Color(0xFF9BA4B8),
    outline = Color(0xFF2B3140)
)

val XMaxShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun XMaxTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val accent by AppAppearanceStore.accentColorFlow.collectAsState()
    val buttonTheme by AppAppearanceStore.buttonThemeFlow.collectAsState()
    val navHighlight by AppAppearanceStore.navHighlightFlow.collectAsState()
    val dashboardTint by AppAppearanceStore.dashboardTintFlow.collectAsState()

    val studioColors = remember(darkTheme, accent, buttonTheme, navHighlight, dashboardTint) {
        buildStudioPalette(darkTheme, accent, buttonTheme, navHighlight, dashboardTint)
    }

    val baseM3 = if (darkTheme) StudioDarkColors else StudioLightColors
    val dynamicM3 = remember(baseM3, studioColors) {
        baseM3.copy(
            primary = studioColors.buttonPrimaryBg,
            onPrimary = studioColors.buttonPrimaryFg,
            secondary = studioColors.accentViolet,
            tertiary = studioColors.navHighlightColor
        )
    }

    CompositionLocalProvider(
        LocalXMaxTypography provides XMaxType,
        LocalStudioColors provides studioColors,
        LocalIsDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = dynamicM3,
            typography = XMaxTypography,
            shapes = XMaxShapes,
            content = content
        )
    }
}
