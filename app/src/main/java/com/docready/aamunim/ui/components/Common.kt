package com.docready.aamunim.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docready.aamunim.ui.theme.DocReadyColors
import com.docready.aamunim.ui.theme.SectionLabelStyle

/** The Activity behind a Compose context (Compose may wrap it) */
fun Context.findActivity(): Activity? {
  var c: Context = this
  while (c is ContextWrapper) {
    if (c is Activity) return c
    c = c.baseContext
  }
  return null
}

/** The top bar on the page's own background: back, a Manrope title (and a line under it), actions */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocTopBar(
  title: String,
  onBack: (() -> Unit)?,
  subtitle: String? = null,
  actions: @Composable RowScope.() -> Unit = {},
) {
  TopAppBar(
    title = {
      Column {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
          Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    },
    navigationIcon = {
      if (onBack != null) {
        IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
      }
    },
    actions = actions,
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
  )
}

/** A big screen title (tab screens: "Students", "Presets") with actions on the right */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
  Row(
    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    actions()
  }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = SectionLabelStyle,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = modifier.padding(top = 18.dp, bottom = 10.dp),
  )
}

/** A white card: radius 18, a 1 dp outline (optional click) */
@Composable
fun AppCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  border: BorderStroke? = null,
  color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
  content: @Composable () -> Unit,
) {
  val b = border ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
  if (onClick != null) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = color, border = b, modifier = modifier, content = content)
  } else {
    Surface(shape = RoundedCornerShape(18.dp), color = color, border = b, modifier = modifier, content = content)
  }
}

/** A 44 dp round icon button with an outline (search, sort, QR…) */
@Composable
fun CircleIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
  Surface(
    onClick = onClick,
    shape = androidx.compose.foundation.shape.CircleShape,
    color = if (filled) DocReadyColors.extra.chip else MaterialTheme.colorScheme.surface,
    border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = modifier.size(44.dp),
  ) {
    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp)) }
  }
}

/** How a file stands against its limit */
enum class FitState { FITS, CLOSE, TOO_BIG, NONE }

/** "Fits" (green) / "Close" (amber) / "Too big" (red) / neutral, as a small pill */
@Composable
fun StatusBadge(text: String, state: FitState, modifier: Modifier = Modifier) {
  val extra = DocReadyColors.extra
  val (bg, fg) = when (state) {
    FitState.FITS -> extra.successContainer to extra.onSuccessContainer
    FitState.CLOSE -> extra.warningContainer to extra.onWarningContainer
    FitState.TOO_BIG -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    FitState.NONE -> extra.chip to extra.text2
  }
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    color = fg,
    maxLines = 1,
    modifier = modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
  )
}

/** A thin rounded progress bar (5 dp) */
@Composable
fun SlimProgress(fraction: Float, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
  Box(modifier.fillMaxWidth().height(5.dp).background(DocReadyColors.extra.chip, RoundedCornerShape(3.dp))) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp).background(color, RoundedCornerShape(3.dp)))
  }
}

/** "PDF" / "JPG" / "PNG" / "ZIP" in a small rounded square, coloured by kind */
@Composable
fun FileKindTile(name: String, size: Int = 36) {
  val ext = name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
  val extra = DocReadyColors.extra
  val (bg, fg) = when (ext) {
    "PDF" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    "JPG", "JPEG", "PNG" -> extra.successContainer to MaterialTheme.colorScheme.primary
    "ZIP" -> extra.warningContainer to extra.onWarningContainer
    else -> extra.chip to extra.text2
  }
  Box(Modifier.size(size.dp).background(bg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
    Text(ext, color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 10.5.sp)
  }
}

/** A whole-number field (px, KB); blank allowed */
@Composable
fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  OutlinedTextField(
    value = value,
    onValueChange = { v -> onValueChange(v.filter { it.isDigit() }.take(5)) },
    label = { Text(label) },
    singleLine = true,
    enabled = enabled,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = modifier,
  )
}

/** A row of choices (one selected), scrolling sideways when it is wide */
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (o in options) {
      FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
    }
  }
}

/**
 * "Meets the preset (tick) 300 × 300 px · 86 KB (max 100) · JPG" in green, or a clear
 * warning in amber when the asked size / weight could not be reached.
 */
@Composable
fun ResultCard(ok: Boolean, title: String, detail: String, modifier: Modifier = Modifier) {
  val extra = DocReadyColors.extra
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = if (ok) extra.successContainer else extra.warningContainer,
    border = BorderStroke(1.dp, if (ok) extra.successBorder else extra.warningBorder),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(
        if (ok) AppIcons.Check else AppIcons.Warning,
        contentDescription = null,
        tint = if (ok) extra.success else extra.onWarningContainer,
        modifier = Modifier.size(22.dp),
      )
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          title,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.bodyLarge,
          color = if (ok) extra.onSuccessContainer else extra.onWarningContainer,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (ok) extra.onSuccessContainer else extra.onWarningContainer)
      }
    }
  }
}

/** Things to look at (no face found, blurry photo...) */
@Composable
fun NotesList(notes: List<String>, modifier: Modifier = Modifier) {
  if (notes.isEmpty()) return
  Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for (n in notes) {
      Row(verticalAlignment = Alignment.Top) {
        Icon(
          AppIcons.Warning,
          contentDescription = null,
          tint = DocReadyColors.extra.onWarningContainer,
          modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
  if (message == null) return
  Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = modifier.padding(top = 8.dp))
}

/** A row with a spinner and what is happening */
@Composable
fun BusyRow(text: String, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
    Spacer(Modifier.width(12.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium)
  }
}

/** The white bar at the bottom of a tool screen with its main buttons (pills, 50–52 dp) */
@Composable
fun BottomActions(content: @Composable RowScope.() -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surface) {
    Column {
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
      Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
      )
    }
  }
}

/** A small rounded square with an icon (folders, account rows) */
@Composable
fun IconTile(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, size: Int = 40) {
  Box(
    Modifier.size(size.dp).background(background, RoundedCornerShape(13.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
  }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier = modifier
      .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
      .padding(horizontal = 8.dp, vertical = 3.dp),
  )
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

/** "✨ shadow · yellow · fold": what Beautify did on a page, under its tile */
@Composable
fun BeautyBadge(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier.padding(top = 2.dp),
  )
}
