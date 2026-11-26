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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.docready.aamunim.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand: deep green on warm, neutral greys. No blue, indigo, cyan, teal, sky or navy anywhere,
// and no blue-tinted greys (every Material slot is set, so nothing falls back to a default).
val Primary = Color(0xFF1B6B3A)
val PrimaryDark = Color(0xFF14532D)
val Background = Color(0xFFF7F6F3)
val Success = Primary
val Amber = Color(0xFFB45309)

private val LightColors = lightColorScheme(
  primary = Primary,
  onPrimary = Color.White,
  // primary soft / on-soft
  primaryContainer = Color(0xFFE3F0E7),
  onPrimaryContainer = PrimaryDark,
  inversePrimary = Color(0xFF8FD19E),
  // secondary text, neutral chip
  secondary = Color(0xFF3D3A35),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFEFEBE4),
  onSecondaryContainer = Color(0xFF3D3A35),
  // amber, amber soft, amber text
  tertiary = Amber,
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFFBEFD9),
  onTertiaryContainer = Color(0xFF92400E),
  background = Background,
  onBackground = Color(0xFF1C1A17),
  surface = Color.White,
  onSurface = Color(0xFF1C1A17),
  surfaceVariant = Color(0xFFEFEBE4),
  // muted text
  onSurfaceVariant = Color(0xFF5F5A52),
  surfaceTint = Primary,
  inverseSurface = Color(0xFF32302C),
  inverseOnSurface = Color(0xFFF5F3EF),
  error = Color(0xFFB42318),
  onError = Color.White,
  errorContainer = Color(0xFFFDE7E4),
  onErrorContainer = Color(0xFF8A1C12),
  // outline, divider
  outline = Color(0xFFE4DFD5),
  outlineVariant = Color(0xFFEFEBE4),
  scrim = Color.Black,
  surfaceBright = Color.White,
  surfaceDim = Color(0xFFE5E2DC),
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color.White,
  surfaceContainer = Color.White,
  surfaceContainerHigh = Color.White,
  surfaceContainerHighest = Color(0xFFEFEBE4),
)

// The same hues on the editor's warm near-black (no blue)
private val DarkColors = darkColorScheme(
  primary = Color(0xFF8FD19E),
  onPrimary = Color(0xFF10331D),
  primaryContainer = Color(0xFF2E3B31),
  onPrimaryContainer = Color(0xFFD9F0DF),
  inversePrimary = Primary,
  secondary = Color(0xFFCFC8BC),
  onSecondary = Color(0xFF1F1D1B),
  secondaryContainer = Color(0xFF2C2926),
  onSecondaryContainer = Color(0xFFE4DED4),
  tertiary = Color(0xFFF0A64B),
  onTertiary = Color(0xFF3A2306),
  tertiaryContainer = Color(0xFF2A2116),
  onTertiaryContainer = Color(0xFFF6D9A8),
  background = Color(0xFF141312),
  onBackground = Color(0xFFF5F3EF),
  surface = Color(0xFF1F1D1B),
  onSurface = Color(0xFFF5F3EF),
  surfaceVariant = Color(0xFF2C2926),
  onSurfaceVariant = Color(0xFFA8A196),
  surfaceTint = Color(0xFF8FD19E),
  inverseSurface = Color(0xFFF5F3EF),
  inverseOnSurface = Color(0xFF1F1D1B),
  error = Color(0xFFFDA29B),
  onError = Color(0xFF55160C),
  errorContainer = Color(0xFF3B1A16),
  onErrorContainer = Color(0xFFFECDCA),
  outline = Color(0xFF3A3632),
  outlineVariant = Color(0xFF2C2926),
  scrim = Color.Black,
  surfaceBright = Color(0xFF2E2B28),
  surfaceDim = Color(0xFF141312),
  surfaceContainerLowest = Color(0xFF141312),
  surfaceContainerLow = Color(0xFF1F1D1B),
  surfaceContainer = Color(0xFF1F1D1B),
  surfaceContainerHigh = Color(0xFF262421),
  surfaceContainerHighest = Color(0xFF2C2926),
)

/** Colours Material has no slot for: "meets the preset", warnings, folders, secondary text */
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
  /** Secondary text (darker than muted) */
  val text2: Color,
  /** Neutral chip / track */
  val chip: Color,
  /** Dashed "new" borders on green */
  val successDash: Color,
)

private val LightExtra = ExtraColors(
  success = Primary,
  successContainer = Color(0xFFE3F0E7),
  onSuccessContainer = PrimaryDark,
  successBorder = Color(0xFF9FC7AC),
  warningContainer = Color(0xFFFBEFD9),
  onWarningContainer = Color(0xFF92400E),
  warningBorder = Color(0xFFD9B98A),
  folderContainer = Color(0xFFFBEFD9),
  folderIcon = Amber,
  text2 = Color(0xFF3D3A35),
  chip = Color(0xFFEFEBE4),
  successDash = Color(0xFF9FC7AC),
)

private val DarkExtra = ExtraColors(
  success = Color(0xFF8FD19E),
  successContainer = Color(0xFF2E3B31),
  onSuccessContainer = Color(0xFFD9F0DF),
  successBorder = Color(0xFF3E6B4B),
  warningContainer = Color(0xFF2A2116),
  onWarningContainer = Color(0xFFF6D9A8),
  warningBorder = Color(0xFF5C4320),
  folderContainer = Color(0xFF2A2116),
  folderIcon = Color(0xFFF0A64B),
  text2 = Color(0xFFCFC8BC),
  chip = Color(0xFF2C2926),
  successDash = Color(0xFF3E6B4B),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** Cards and tiles 18; chips and buttons fully rounded (pills); fields 12; sheets 26 */
private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(12.dp),
  small = RoundedCornerShape(percent = 50),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(18.dp),
  extraLarge = RoundedCornerShape(26.dp),
)

/** Manrope for titles (700 / 800), Inter for the rest (400–700); both SIL Open Font License */
val Manrope = FontFamily(
  Font(R.font.manrope_bold, FontWeight.Bold),
  Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

val Inter = FontFamily(
  Font(R.font.inter_regular, FontWeight.Normal),
  Font(R.font.inter_medium, FontWeight.Medium),
  Font(R.font.inter_semibold, FontWeight.SemiBold),
  Font(R.font.inter_bold, FontWeight.Bold),
)

private val AppTypography = Typography().let { t ->
  fun TextStyle.inter() = copy(fontFamily = Inter)
  t.copy(
    displayLarge = t.displayLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
    displayMedium = t.displayMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
    displaySmall = t.displaySmall.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
    headlineLarge = t.headlineLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
    headlineMedium = t.headlineMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    headlineSmall = t.headlineSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, letterSpacing = (-0.3).sp),
    titleLarge = t.titleLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp),
    titleMedium = t.titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    titleSmall = t.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    bodyLarge = t.bodyLarge.inter(),
    bodyMedium = t.bodyMedium.copy(fontFamily = Inter, fontSize = 14.sp),
    bodySmall = t.bodySmall.copy(fontFamily = Inter, fontSize = 12.5.sp),
    labelLarge = t.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = t.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    labelSmall = t.labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
  )
}

/** Headings over each group ("Continue where you left off", "Recent files"...) */
val SectionLabelStyle = TextStyle(fontFamily = Manrope, fontSize = 15.sp, fontWeight = FontWeight.Bold)

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
  val Text = Color(0xFFF5F3EF)
  val TextDim = Color(0xFFA8A196)
  /** Unselected tool / look labels */
  val TextSoft = Color(0xFFCFC8BC)
  val Accent = Color(0xFF8FD19E)
  val OnAccent = Color(0xFF10331D)
  /** A chosen tool / look's background */
  val Selected = Color(0xFF2E3B31)
  val OnSelected = Color(0xFFD9F0DF)
  val Line = Color(0xFF3A3632)
  val Divider = Color(0xFF2C2926)
  /** Amber on the dark chrome (fingers, warnings) */
  val Amber = Color(0xFFF0A64B)
  val AmberSoft = Color(0xFF2A2116)
  val AmberText = Color(0xFFF6D9A8)
  val AmberLine = Color(0xFF5C4320)
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
