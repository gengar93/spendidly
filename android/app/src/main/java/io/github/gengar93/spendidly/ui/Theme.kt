package io.github.gengar93.spendidly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.gengar93.spendidly.R

/** Shorthand over the Material colour scheme, so screens read the same in light, dark and dynamic colour. */
@Immutable
data class Palette(
    val bg: Color, val surface: Color, val sunk: Color, val ink: Color, val sub: Color, val line: Color,
    val accent: Color, val accentInk: Color, val accentSoft: Color, val danger: Color, val scrim: Color, val dark: Boolean,
)

private fun ColorScheme.palette(dark: Boolean) = Palette(
    bg = surface, surface = surfaceContainerLowest, sunk = surfaceContainerHigh, ink = onSurface, sub = onSurfaceVariant,
    line = outlineVariant, accent = primary, accentInk = onPrimary, accentSoft = primaryContainer, danger = error,
    scrim = Color.Black.copy(alpha = 0.32f), dark = dark,
)

// Used before Android 12, where there is no wallpaper colour: a tonal spot from seed #3F5F91.
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3F5F91), onPrimary = Color.White, primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF254777),
    secondary = Color(0xFF565F71), secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFF705574), tertiaryContainer = Color(0xFFFAD8FD), onTertiaryContainer = Color(0xFF573E5C),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20), onSurfaceVariant = Color(0xFF44474E), outlineVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3FA), surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE), surfaceContainerHighest = Color(0xFFE2E2E9),
)
private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA8C8FF), onPrimary = Color(0xFF05305F), primaryContainer = Color(0xFF254777), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC), secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0), tertiaryContainer = Color(0xFF573E5C), onTertiaryContainer = Color(0xFFFAD8FD),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9), onSurfaceVariant = Color(0xFFC4C6D0), outlineVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20), surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F), surfaceContainerHighest = Color(0xFF33353A),
)

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int, width: Float = 100f) = Font(
    R.font.robotoflex, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** Text: Roboto Flex at normal width. */
val TextFont = FontFamily(flex(400), flex(500), flex(600), flex(700))

/** Amounts and titles: Roboto Flex, wide and semibold. */
val NumberFont = FontFamily(flex(500, 118f), flex(560, 118f), flex(620, 120f), flex(700, 112f))

object Type {
    val body = TextStyle(fontFamily = TextFont, fontSize = 16.sp, lineHeight = 22.sp)
    val small = TextStyle(fontFamily = TextFont, fontSize = 14.sp, lineHeight = 19.sp)
    val tiny = TextStyle(fontFamily = TextFont, fontSize = 12.5.sp, lineHeight = 16.sp)
    val label = TextStyle(fontFamily = TextFont, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontFamily = NumberFont, fontSize = 30.sp, fontWeight = FontWeight(700))
    val heading = TextStyle(fontFamily = TextFont, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    fun amount(size: Int, weight: FontWeight = FontWeight(620)) =
        TextStyle(fontFamily = NumberFont, fontSize = size.sp, fontWeight = weight, letterSpacing = (-size * 0.015).sp)
    val rowAmount = TextStyle(fontFamily = TextFont, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

val LocalPalette = staticCompositionLocalOf { FallbackLight.palette(false) }

enum class Role { Primary, Secondary, Tertiary }

/** Container and content colours for a tonal role. */
@Composable
fun roleColors(role: Role): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (role) {
        Role.Primary -> c.primaryContainer to c.onPrimaryContainer
        Role.Secondary -> c.secondaryContainer to c.onSecondaryContainer
        Role.Tertiary -> c.tertiaryContainer to c.onTertiaryContainer
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpendidlyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Applies live: changing the setting in Settings recomposes from here.
    val setting = colours
    val scheme = remember(setting, dark) { appColorScheme(context, setting, dark) }
    val base = Typography()
    val typography = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = TextFont), bodyMedium = base.bodyMedium.copy(fontFamily = TextFont),
        labelLarge = base.labelLarge.copy(fontFamily = TextFont), titleLarge = base.titleLarge.copy(fontFamily = TextFont),
        headlineSmall = base.headlineSmall.copy(fontFamily = TextFont),
    )
    CompositionLocalProvider(LocalPalette provides scheme.palette(dark)) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), typography = typography, content = content)
    }
}
