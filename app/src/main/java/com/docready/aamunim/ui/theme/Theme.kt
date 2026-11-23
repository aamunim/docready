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

// Brand (from the mockups)
val Primary = Color(0xFF2457D6)
val PrimaryDark = Color(0xFF1A43A8)
val Background = Color(0xFFF5F7FB)
val Success = Color(0xFF15803D)

private val LightColors = lightColorScheme(
  primary = Primary,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE8EEFC),
  onPrimaryContainer = PrimaryDark,
  secondary = Color(0xFF4B5563),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFE8EEFC),
  onSecondaryContainer = PrimaryDark,
  background = Background,
  onBackground = Color(0xFF111827),
  surface = Color.White,
  onSurface = Color(0xFF111827),
  surfaceVariant = Color(0xFFEEF2F8),
  onSurfaceVariant = Color(0xFF4B5563),
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color.White,
  surfaceContainer = Color.White,
  surfaceContainerHigh = Color.White,
  surfaceContainerHighest = Color(0xFFEEF2F8),
  outline = Color(0xFFDDE3EE),
  outlineVariant = Color(0xFFE5E9F2),
  error = Color(0xFFB42318),
  onError = Color.White,
  errorContainer = Color(0xFFFEF3F2),
  onErrorContainer = Color(0xFF7A271A),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFF9DB6FF),
  onPrimary = Color(0xFF0B2A78),
  primaryContainer = Color(0xFF1C3570),
  onPrimaryContainer = Color(0xFFDCE5FF),
  secondary = Color(0xFFB4BCCB),
  onSecondary = Color(0xFF1B2230),
  secondaryContainer = Color(0xFF1C3570),
  onSecondaryContainer = Color(0xFFDCE5FF),
  background = Color(0xFF0E1320),
  onBackground = Color(0xFFE6EAF2),
  surface = Color(0xFF171D2B),
  onSurface = Color(0xFFE6EAF2),
  surfaceVariant = Color(0xFF222A3B),
  onSurfaceVariant = Color(0xFFB4BCCB),
  surfaceContainerLowest = Color(0xFF121826),
  surfaceContainerLow = Color(0xFF171D2B),
  surfaceContainer = Color(0xFF1A2130),
  surfaceContainerHigh = Color(0xFF1E2535),
  surfaceContainerHighest = Color(0xFF242C3D),
  outline = Color(0xFF334055),
  outlineVariant = Color(0xFF2A3446),
  error = Color(0xFFFDA29B),
  onError = Color(0xFF55160C),
  errorContainer = Color(0xFF3B1A16),
  onErrorContainer = Color(0xFFFECDCA),
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
  folderContainer = Color(0xFFFEF3C7),
  folderIcon = Color(0xFF92400E),
)

private val DarkExtra = ExtraColors(
  success = Color(0xFF4ADE80),
  successContainer = Color(0xFF0F2E1C),
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
