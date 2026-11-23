package com.docready.aamunim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand: deep green on warm, neutral greys. No blue, indigo, cyan, teal, sky or navy anywhere,
// and no blue-tinted greys (every Material slot is set, so nothing falls back to a default).
val Primary = Color(0xFF1B6B3A)
val PrimaryDark = Color(0xFF14512C)
val Background = Color(0xFFF7F6F3)
val Success = Color(0xFF15803D)
val Amber = Color(0xFFB45309)

private val LightColors = lightColorScheme(
  primary = Primary,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFDCEFE1),
  onPrimaryContainer = PrimaryDark,
  inversePrimary = Color(0xFF8FD19E),
  secondary = Color(0xFF5C5750),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFEDE9E2),
  onSecondaryContainer = Color(0xFF2B2722),
  tertiary = Amber,
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFFDEBD3),
  onTertiaryContainer = Color(0xFF5C2A05),
  background = Background,
  onBackground = Color(0xFF1C1A17),
  surface = Color.White,
  onSurface = Color(0xFF1C1A17),
  surfaceVariant = Color(0xFFF0EEE9),
  onSurfaceVariant = Color(0xFF5C5750),
  surfaceTint = Primary,
  inverseSurface = Color(0xFF32302C),
  inverseOnSurface = Color(0xFFF5F3EF),
  error = Color(0xFFB42318),
  onError = Color.White,
  errorContainer = Color(0xFFFEF3F2),
  onErrorContainer = Color(0xFF7A271A),
  outline = Color(0xFFDDD8CF),
  outlineVariant = Color(0xFFE9E5DE),
  scrim = Color.Black,
  surfaceBright = Color.White,
  surfaceDim = Color(0xFFE5E2DC),
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color.White,
  surfaceContainer = Color.White,
  surfaceContainerHigh = Color.White,
  surfaceContainerHighest = Color(0xFFF0EEE9),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFF8FD19E),
  onPrimary = Color(0xFF0B3A1C),
  primaryContainer = Color(0xFF1F4D2E),
  onPrimaryContainer = Color(0xFFC9EDD2),
  inversePrimary = Primary,
  secondary = Color(0xFFC9C3B9),
  onSecondary = Color(0xFF2B2722),
  secondaryContainer = Color(0xFF3A3631),
  onSecondaryContainer = Color(0xFFEDE9E2),
  tertiary = Color(0xFFF2B36B),
  onTertiary = Color(0xFF4A2303),
  tertiaryContainer = Color(0xFF5C2E08),
  onTertiaryContainer = Color(0xFFFDDDB5),
  background = Color(0xFF121110),
  onBackground = Color(0xFFEDEAE4),
  surface = Color(0xFF1C1A18),
  onSurface = Color(0xFFEDEAE4),
  surfaceVariant = Color(0xFF2A2724),
  onSurfaceVariant = Color(0xFFC9C3B9),
  surfaceTint = Color(0xFF8FD19E),
  inverseSurface = Color(0xFFEDEAE4),
  inverseOnSurface = Color(0xFF2B2722),
  error = Color(0xFFFDA29B),
  onError = Color(0xFF55160C),
  errorContainer = Color(0xFF3B1A16),
  onErrorContainer = Color(0xFFFECDCA),
  outline = Color(0xFF4A4540),
  outlineVariant = Color(0xFF36322E),
  scrim = Color.Black,
  surfaceBright = Color(0xFF2E2B28),
  surfaceDim = Color(0xFF121110),
  surfaceContainerLowest = Color(0xFF151412),
  surfaceContainerLow = Color(0xFF1C1A18),
  surfaceContainer = Color(0xFF201E1B),
  surfaceContainerHigh = Color(0xFF262421),
  surfaceContainerHighest = Color(0xFF2E2B28),
)

/** Colours Material has no slot for: "meets the preset", warnings, folders */
@Immutable
data class ExtraColors(
  val success: Color,
  val successContainer: Color,
  val onSuccessContainer: Color,
  val successBorder: Color,
  val warningContainer: Color,
  val onWarningContainer: Color,
  val warningBorder: Color,
  val folderContainer: Color,
  val folderIcon: Color,
)

private val LightExtra = ExtraColors(
  success = Success,
  successContainer = Color(0xFFECFDF3),
  onSuccessContainer = Color(0xFF14532D),
  successBorder = Color(0xFFA7E3BD),
  warningContainer = Color(0xFFFFFAEB),
  onWarningContainer = Color(0xFF7A2E0E),
  warningBorder = Color(0xFFFEDF89),
  folderContainer = Color(0xFFFDEBD3),
  folderIcon = Color(0xFF92400E),
)

private val DarkExtra = ExtraColors(
  success = Color(0xFF4ADE80),
  successContainer = Color(0xFF13291A),
  onSuccessContainer = Color(0xFFBBF7D0),
  successBorder = Color(0xFF1F5E3A),
  warningContainer = Color(0xFF3A2A0A),
  onWarningContainer = Color(0xFFFDE68A),
  warningBorder = Color(0xFF7A5A12),
  folderContainer = Color(0xFF3A2E10),
  folderIcon = Color(0xFFFCD34D),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(14.dp),
  large = RoundedCornerShape(18.dp),
  extraLarge = RoundedCornerShape(24.dp),
)

private val AppTypography = Typography().let { t ->
  t.copy(
    titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
  )
}

/** Small grey headings over each group ("Make it ready", "Presets"...) */
val SectionLabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)

@Composable
fun DocReadyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra) {
    MaterialTheme(
      colorScheme = if (darkTheme) DarkColors else LightColors,
      typography = AppTypography,
      shapes = AppShapes,
      content = content,
    )
  }
}

object DocReadyColors {
  val extra: ExtraColors
    @Composable get() = LocalExtraColors.current
}

/** The page editor's dark chrome: neutral near-black, green accents (no blue) */
object EditorColors {
  val Background = Color(0xFF141312)
  val Panel = Color(0xFF1F1D1B)
  val PanelHigh = Color(0xFF2A2825)
  val Text = Color(0xFFEDEAE4)
  val TextDim = Color(0xFFA8A29A)
  val Accent = Color(0xFF8FD19E)
  val OnAccent = Color(0xFF0B3A1C)
  val Line = Color(0xFF3A3632)
}

/** The page editor's own dark theme (whatever the phone's setting): near-black, green accents */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalExtraColors provides DarkExtra) {
    MaterialTheme(
      colorScheme = DarkColors.copy(
        primary = EditorColors.Accent,
        onPrimary = EditorColors.OnAccent,
        background = EditorColors.Background,
        onBackground = EditorColors.Text,
        surface = EditorColors.Panel,
        onSurface = EditorColors.Text,
        surfaceVariant = EditorColors.PanelHigh,
        onSurfaceVariant = EditorColors.TextDim,
        outline = EditorColors.Line,
        surfaceContainer = EditorColors.Panel,
        surfaceContainerLow = EditorColors.Panel,
        surfaceContainerHigh = EditorColors.PanelHigh,
        surfaceContainerHighest = EditorColors.PanelHigh,
      ),
      typography = AppTypography,
      shapes = AppShapes,
      content = content,
    )
  }
}
