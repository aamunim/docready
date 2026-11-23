package com.docready.aamunim.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.model.BeautifyRecipe
import com.docready.aamunim.data.model.BeautyStrength
import com.docready.aamunim.data.model.CropRect
import com.docready.aamunim.data.model.EdgeKind
import com.docready.aamunim.data.model.beautyBadge
import com.docready.aamunim.ui.theme.EditorColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Amber = androidx.compose.ui.graphics.Color(0xFFF2B36B)

/** ✨ Beautify's controls: one tap, strength, each step on / off with its strength, the loupe */
@Composable
internal fun BeautifyControls(state: PageEditorState) {
  val r = state.recipe
  val b = r.beautify
  val found = state.foundHere?.recipe
  val signature = state.mode == EditorMode.SIGNATURE
  Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
    val busy = state.beautifying
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      if (busy == null) {
        Button(onClick = { state.beautify() }) { Text(if (signature) "✨ Boost ink" else "✨ Beautify") }
        if (state.mode == EditorMode.DOCUMENT && state.pages.size > 1) {
          OutlinedButton(onClick = { state.beautifyAll() }) { Text("Beautify all pages") }
        }
      } else {
        Text(busy, color = EditorColors.Text)
        TextButton(onClick = { state.stopBeautify() }) { Text("Stop") }
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (s in BeautyStrength.entries) {
        FilterChip(selected = (b?.strength ?: BeautyStrength.MEDIUM) == s, onClick = { state.setStrength(s) }, label = { Text(s.title) })
      }
    }
    state.askAngle?.let { a ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("The page is tilted about ${a.roundToInt()}°.", color = Amber, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.acceptAskAngle() }) { Text("Straighten it") }
      }
    }
    for (n in state.notes) Text(n, color = Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
    r.beautyBadge()?.let { Text(it, color = EditorColors.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }

    if (b == null) {
      Text(
        if (signature) {
          "Tap ✨: faded ink made darker and grain cleaned; the paper stays white."
        } else {
          "Tap ✨ Beautify: the page is looked at and only what it needs is fixed (straight text, white paper, folds, stains…)."
        },
        color = EditorColors.TextDim,
        style = MaterialTheme.typography.bodySmall,
      )
    } else {
      val level = b.strength.level
      if (!signature) {
        Switchable("Straighten text", "${b.deskew}°", b.deskew != 0f, b.deskew != 0f || (found?.deskew ?: 0f) != 0f) { on ->
          state.setBeauty { it.copy(deskew = if (on) found?.deskew ?: 0f else 0f) }
        }
        Switchable("Upside down", null, b.flip, b.flip || found?.flip == true) { on -> state.setBeauty { it.copy(flip = on) } }
        Switchable("Trim black border", null, b.trim != null, b.trim != null || found?.trim != null) { on ->
          state.setBeauty { it.copy(trim = if (on) found?.trim else null) }
        }
        Graded("Glare (laminated card)", b.glare, b.glareAreas.isNotEmpty() || found?.glareAreas?.isNotEmpty() == true, level, state.index) { v ->
          state.setBeauty { it.copy(glare = v, glareAreas = it.glareAreas.ifEmpty { found?.glareAreas ?: emptyList() }) }
        }
        Graded("Stains / blotches", b.stains, true, level, state.index) { v -> state.setBeauty { it.copy(stains = v) } }
        Graded("Yellow paper → white", b.whiten, true, level, state.index) { v -> state.setBeauty { it.copy(whiten = v) } }
        Graded("Folds / creases", b.folds, b.foldLines.isNotEmpty() || found?.foldLines?.isNotEmpty() == true, level, state.index) { v ->
          state.setBeauty { it.copy(folds = v, foldLines = it.foldLines.ifEmpty { found?.foldLines ?: emptyList() }) }
        }
        if (b.foldLines.isNotEmpty()) {
          FilterChip(selected = state.showFolds, onClick = { state.showFolds = !state.showFolds }, label = { Text("Show folds") })
        }
        val marks = b.edgeMarks.size
        Switchable(
          "Edge marks",
          if (marks > 0) "$marks outlined (fingers, holes, staples)" else null,
          b.edges,
          marks > 0,
        ) { on -> state.setBeauty { it.copy(edges = on) } }
      }
      Graded("Denoise, then sharpen", b.clarity, true, level * 0.8f, state.index) { v -> state.setBeauty { it.copy(clarity = v) } }
      Graded("Faded ink", b.ink, true, level, state.index) { v -> state.setBeauty { it.copy(ink = v) } }
      Text(
        if (signature) "Only the ink is changed; the paper stays white." else "Photos, logos and stamps are left as they are by the colour steps.",
        color = EditorColors.TextDim,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Loupe(state)
  }
}

/** A step that is only on or off */
@Composable
private fun Switchable(title: String, detail: String?, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, color = if (enabled) EditorColors.Text else EditorColors.TextDim)
      if (detail != null && on) Text(detail, color = EditorColors.TextDim, style = MaterialTheme.typography.bodySmall)
      if (!enabled) Text("Not found on this page", color = EditorColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
  }
}

/** A step with a strength: on / off, and its slider while on */
@Composable
private fun Graded(title: String, value: Float, enabled: Boolean, level: Float, page: Int, onChange: (Float) -> Unit) {
  var v by remember(page, value) { mutableFloatStateOf(value) }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, color = if (enabled) EditorColors.Text else EditorColors.TextDim)
      if (!enabled) Text("Not found on this page", color = EditorColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = value > 0f, onCheckedChange = { onChange(if (it) level else 0f) }, enabled = enabled)
  }
  if (value > 0f) {
    Slider(
      value = v,
      onValueChange = { v = it },
      onValueChangeFinished = { onChange((v * 100).roundToInt() / 100f) },
      valueRange = 0.1f..1f,
    )
  }
}

/** A box / line on the uncropped page, as a point of the shown (cropped) page */
private fun toShown(x: Float, y: Float, crop: CropRect?, f: Rect): Offset {
  val c = crop ?: CropRect(0f, 0f, 1f, 1f)
  val u = (x - c.left) / max(0.0001f, c.width)
  val v = (y - c.top) / max(0.0001f, c.height)
  return Offset(f.left + u * f.width, f.top + v * f.height)
}

/** What Beautify will paint over (edge marks) and, on request, the folds it found */
internal fun beautyOverlay(state: PageEditorState): (DrawScope.(Rect) -> Unit)? {
  val r = state.recipe
  val b: BeautifyRecipe = r.beautify ?: return null
  val folds = state.showFolds
  if (b.edgeMarks.isEmpty() && !(folds && b.foldLines.isNotEmpty())) return null
  return { f ->
    for (m in b.edgeMarks) {
      val a = toShown(m.box.left, m.box.top, r.crop, f)
      val z = toShown(m.box.right, m.box.bottom, r.crop, f)
      val stroke = Stroke(width = 2.dp.toPx())
      val color = if (b.edges) EditorColors.Accent else Amber
      if (m.kind == EdgeKind.HOLE) {
        drawOval(color, topLeft = a, size = Size(z.x - a.x, z.y - a.y), style = stroke)
      } else {
        drawRect(color, topLeft = a, size = Size(z.x - a.x, z.y - a.y), style = stroke)
      }
    }
    if (folds) {
      for (l in b.foldLines) {
        val from = if (l.horizontal) toShown(0f, l.at, r.crop, f) else toShown(l.at, 0f, r.crop, f)
        val to = if (l.horizontal) toShown(1f, l.at, r.crop, f) else toShown(l.at, 1f, r.crop, f)
        drawLine(Amber, from, to, strokeWidth = 2.dp.toPx())
      }
    }
  }
}

/** Before and after side by side, zoomed in where the page was tapped (to judge the text) */
@Composable
private fun Loupe(state: PageEditorState) {
  val after = state.preview ?: return
  val before = state.beforeBeautify ?: return
  Text(
    "Before · after (tap the page to move the loupe)",
    color = EditorColors.TextDim,
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier.padding(top = 8.dp),
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
    LoupeView(before, state.loupe)
    LoupeView(after, state.loupe)
  }
}

@Composable
private fun LoupeView(image: ImageBitmap, at: Offset) {
  Canvas(Modifier.size(140.dp)) {
    val side = max(16, (min(image.width, image.height) * 0.18f).roundToInt())
    val x = ((at.x * image.width).roundToInt() - side / 2).coerceIn(0, max(0, image.width - side))
    val y = ((at.y * image.height).roundToInt() - side / 2).coerceIn(0, max(0, image.height - side))
    drawImage(
      image,
      srcOffset = IntOffset(x, y),
      srcSize = IntSize(min(side, image.width), min(side, image.height)),
      dstOffset = IntOffset.Zero,
      dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
    drawRect(EditorColors.Line, style = Stroke(width = 1.dp.toPx()))
  }
  Spacer(Modifier.width(0.dp))
}
