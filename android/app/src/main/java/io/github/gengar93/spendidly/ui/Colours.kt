package io.github.gengar93.spendidly.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import io.github.gengar93.spendidly.backend.ColourSetting
import io.github.gengar93.spendidly.backend.currentColours

/** A curated palette. [secondary] and [tertiary], when set, replace the colours [style] would derive from [seed]. */
data class ColourPalette(
    val id: String, val name: String, val seed: Color,
    val secondary: Color? = null, val tertiary: Color? = null, val style: PaletteStyle = PaletteStyle.TonalSpot,
)

/** Palettes offered in Settings, in order. The id is what's stored, so keep it once shipped. */
val colourPalettes = listOf(
    ColourPalette("navy", "Navy", Color(0xFF14213D), tertiary = Color(0xFFFF7F5C)),
    ColourPalette("forest", "Forest", Color(0xFF1E4B3A)),
    ColourPalette("plum", "Plum", Color(0xFF3B1F56)),
    ColourPalette("terracotta", "Terracotta", Color(0xFFA0522D)),
    ColourPalette("graphite", "Graphite", Color(0xFF3C4048), style = PaletteStyle.Neutral),
)

/** The app's colour setting; reading it in a composable recomposes it when the setting changes. */
val colours: ColourSetting get() = currentColours

/** The chosen palette, or null for Auto (also when the stored id is no longer offered). */
val ColourSetting.curated: ColourPalette? get() = colourPalettes.find { it.id == palette }

/** The wallpaper's main colour on Android 12+, else the system accent, else Spendidly blue. */
private fun wallpaperSeed(context: Context): Color {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val colors = runCatching {
            android.app.WallpaperManager.getInstance(context).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        colors?.primaryColor?.let { return Color(it.toArgb()) }
        return dynamicLightColorScheme(context).primary
    }
    return Color(0xFF3F5F91)
}

/**
 * Turns the colour setting into a scheme, for the app and the widgets alike. Auto follows the wallpaper: vivid builds
 * Material's Vibrant style from its colour (deeper than the system's), otherwise it's the system's own dynamic scheme.
 */
fun appColorScheme(context: Context, setting: ColourSetting, dark: Boolean): ColorScheme {
    setting.curated?.let {
        return dynamicColorScheme(
            seedColor = it.seed, isDark = dark, isAmoled = false, secondary = it.secondary, tertiary = it.tertiary, style = it.style,
        )
    }
    if (!setting.vivid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    val style = if (setting.vivid) PaletteStyle.Vibrant else PaletteStyle.TonalSpot
    return dynamicColorScheme(seedColor = wallpaperSeed(context), isDark = dark, isAmoled = false, style = style)
}
