package app.stillwave.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.stillwave.data.ThemeMode

/** Stillwave's named colors, resolved for the active light or night theme. */
@Immutable data class StillwavePalette(
    val accent: Color, val onAccent: Color, val accentContainer: Color, val onAccentContainer: Color,
    val background: Color, val onBackground: Color, val panel: Color, val tonal: Color,
    val muted: Color, val alert: Color, val outline: Color, val dark: Boolean)

val NightPalette = StillwavePalette(
    accent = Color(0xFFC5F277), onAccent = Color(0xFF10120F), accentContainer = Color(0xFF2E401D), onAccentContainer = Color(0xFFC5F277),
    background = Color(0xFF10120F), onBackground = Color(0xFFF0F3E9), panel = Color(0xFF1C2019), tonal = Color(0xFF2D3526),
    muted = Color(0xFFA4AD9A), alert = Color(0xFFFFA18B), outline = Color(0xFF414839), dark = true)

val LightPalette = StillwavePalette(
    accent = Color(0xFF466810), onAccent = Color(0xFFFFFFFF), accentContainer = Color(0xFFD6EDB0), onAccentContainer = Color(0xFF15210A),
    background = Color(0xFFF8F9F3), onBackground = Color(0xFF181C14), panel = Color(0xFFECEFE4), tonal = Color(0xFFDDE5CF),
    muted = Color(0xFF5B6353), alert = Color(0xFFB3381F), outline = Color(0xFFC2C8B7), dark = false)

val LocalPalette = staticCompositionLocalOf { NightPalette }

// Historic names kept for the call sites; they follow the active theme.
/** Accent: lime at night, deep olive by day. */
val Lime: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
/** Screen background. */
val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
/** Cards, sheets and bars. */
val Panel: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panel
/** Secondary text and inactive marks. */
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
/** Recording, destructive actions and playheads. */
val Coral: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.alert
/** Filled tonal controls on panels. */
val Tonal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.tonal

@Composable fun isNightTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable fun StillwaveTheme(night: Boolean = true, content: @Composable () -> Unit) {
    val p = if (night) NightPalette else LightPalette
    val shared = { base: ColorScheme -> base.copy(primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentContainer, onPrimaryContainer = p.onAccentContainer,
        background = p.background, onBackground = p.onBackground, surface = p.background, surfaceContainer = p.panel, surfaceVariant = p.panel,
        onSurface = p.onBackground, onSurfaceVariant = p.muted, outline = p.outline, error = p.alert) }
    // Night keeps the original scheme. Light also replaces Material's lavender defaults for
    // dialogs, menus, chips and tonal buttons so every surface stays in the same green family.
    val scheme = if (night) shared(darkColorScheme()) else shared(lightColorScheme()).copy(
        secondaryContainer = p.tonal, onSecondaryContainer = p.onBackground, secondary = p.accent,
        surfaceContainerLowest = Color.White, surfaceContainerLow = p.panel, surfaceContainerHigh = Color(0xFFE4E8DA),
        surfaceContainerHighest = Color(0xFFDDE2D2), surfaceBright = p.background, surfaceDim = Color(0xFFD8DCCF),
        outlineVariant = p.outline, inverseSurface = Color(0xFF2D3228), inverseOnSurface = Color(0xFFEEF1E6), inversePrimary = NightPalette.accent)
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme,
            typography = Typography(headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-1).sp),
                headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 28.sp, letterSpacing = (-0.5).sp)), content = content)
    }
}
