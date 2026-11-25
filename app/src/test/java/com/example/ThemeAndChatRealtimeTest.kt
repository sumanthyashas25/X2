package com.example

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.chat.ChatSettingsStore
import com.example.model.CompanyProfile
import com.example.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeAndChatRealtimeTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AppAppearanceStore.resetForTesting()
        ChatSettingsStore.clearSecretCode(context)
        ChatSettingsStore.setWallpaper(context, "studio_white")
        ChatSettingsStore.clearCustomWallpaper(context)
    }

    @Test
    fun `appearance store persists and restores all theme attributes`() {
        AppAppearanceStore.ensureLoaded(context)

        AppAppearanceStore.setThemeMode(context, AppThemeMode.DARK)
        assertEquals(AppThemeMode.DARK, AppAppearanceStore.themeModeFlow.value)

        AppAppearanceStore.setAccentColor(context, AppAccentColor.EMERALD)
        assertEquals(AppAccentColor.EMERALD, AppAppearanceStore.accentColorFlow.value)

        AppAppearanceStore.setButtonTheme(context, AppButtonTheme.OBSIDIAN)
        assertEquals(AppButtonTheme.OBSIDIAN, AppAppearanceStore.buttonThemeFlow.value)

        AppAppearanceStore.setNavHighlight(context, AppNavHighlight.EMERALD_PULSE)
        assertEquals(AppNavHighlight.EMERALD_PULSE, AppAppearanceStore.navHighlightFlow.value)

        AppAppearanceStore.setDashboardTint(context, AppDashboardTint.SLEEK_SLATE)
        assertEquals(AppDashboardTint.SLEEK_SLATE, AppAppearanceStore.dashboardTintFlow.value)

        AppAppearanceStore.setAppIconTheme(context, AppIconTheme.EMERALD_AGENCY)
        assertEquals(AppIconTheme.EMERALD_AGENCY, AppAppearanceStore.appIconThemeFlow.value)
    }

    @Test
    fun `buildStudioPalette applies custom accents buttons and nav highlights`() {
        val darkPalette = buildStudioPalette(
            isDark = true,
            accent = AppAccentColor.EMERALD,
            buttonTheme = AppButtonTheme.ACCENT,
            navHighlight = AppNavHighlight.MATCH_ACCENT,
            dashboardTint = AppDashboardTint.DYNAMIC_ACCENT
        )

        assertEquals(AppAccentColor.EMERALD.darkPrimary, darkPalette.accentViolet)
        assertEquals(AppAccentColor.EMERALD.darkPrimary, darkPalette.buttonPrimaryBg)
        assertEquals(AppAccentColor.EMERALD.darkPrimary, darkPalette.navHighlightColor)
        assertEquals(AppAccentColor.EMERALD.darkSoftBg, darkPalette.kpiLavenderBg)

        val lightPalette = buildStudioPalette(
            isDark = false,
            accent = AppAccentColor.OCEAN,
            buttonTheme = AppButtonTheme.OBSIDIAN,
            navHighlight = AppNavHighlight.EMERALD_PULSE,
            dashboardTint = AppDashboardTint.SLEEK_SLATE
        )

        assertEquals(AppAccentColor.OCEAN.lightPrimary, lightPalette.accentViolet)
        assertEquals(ObsidianCharcoal, lightPalette.buttonPrimaryBg)
        assertEquals(AppNavHighlight.EMERALD_PULSE.lightColor, lightPalette.navHighlightColor)
    }

    @Test
    fun `company profile includes default chat background fields`() {
        val company = CompanyProfile(
            name = "X.MAX Studio",
            defaultChatWallpaperId = "dark_doodle",
            defaultChatWallpaperUrl = "https://images.unsplash.com/wallpaper.jpg"
        )
        assertEquals("dark_doodle", company.defaultChatWallpaperId)
        assertEquals("https://images.unsplash.com/wallpaper.jpg", company.defaultChatWallpaperUrl)
    }

    @Test
    fun `chat settings store resolves super admin default wallpaper when user has no custom override`() {
        val prefs = ChatSettingsStore.ensureLoaded(context)

        // Baseline: fallback to company default wallpaper preset
        val companyDefault = ChatSettingsStore.resolveThreadWallpaper(
            prefs = prefs,
            channelId = "ch_general",
            companyDefaultWallpaperId = "dark_doodle",
            companyDefaultWallpaperUrl = ""
        )
        assertEquals("dark_doodle", companyDefault.presetId)
        assertNull(companyDefault.customImagePath)

        // Company custom image URL
        val companyCustom = ChatSettingsStore.resolveThreadWallpaper(
            prefs = prefs,
            channelId = "ch_general",
            companyDefaultWallpaperId = "custom",
            companyDefaultWallpaperUrl = "https://assets.xmax.agency/bg.jpg"
        )
        assertEquals("https://assets.xmax.agency/bg.jpg", companyCustom.customImagePath)

        // User per-chat custom override takes precedence over company default
        ChatSettingsStore.setCustomWallpaperPath(context, "/data/user/0/custom.jpg", "ch_vip")
        val vipResolved = ChatSettingsStore.resolveThreadWallpaper(
            prefs = ChatSettingsStore.prefsFlow.value,
            channelId = "ch_vip",
            companyDefaultWallpaperId = "dark_doodle",
            companyDefaultWallpaperUrl = "https://assets.xmax.agency/bg.jpg"
        )
        assertEquals("/data/user/0/custom.jpg", vipResolved.customImagePath)
    }
}
