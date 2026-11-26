package com.docready.aamunim.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.Path
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Amber = androidx.compose.ui.graphics.Color(0xFFF2B36B)

/** ✨ Beautify's controls: one tap, strength, each step on / off with its strength, the loupe */
@Composable
internal fun BeautifyControls(state: PageEditorState) {
  val r = state.recipe
  val b = r.beautify
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
    if (b != null && b.maybeUpsideDown && !b.flip) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("This page may be upside down.", color = Amber, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.setBeauty { it.copy(flip = true) } }) { Text("Turn it") }
      }
    }
    for (n in state.notes) Text(n, color = Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
    r.beautyBadge()?.let { Text(it, color = EditorColors.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }
    if (!signature && (state.fingerBoxes.isNotEmpty() || r.fingers)) {
      val n = state.fingerBoxes.size
      Switchable(
        "Fingers",
        if (n > 0) "$n outlined: painted over with the paper (what they cover is lost)" else "Painted over with the paper",
        r.fingers,
        true,
      ) { on -> state.setFingers(on) }
    }

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
        Switchable("Straighten text", "${b.deskew}°", b.deskew != 0f, b.deskew != 0f || b.foundDeskew != 0f) { on ->
          state.setBeauty { it.copy(deskew = if (on) it.foundDeskew else 0f) }
        }
        Switchable("Turn upside down", null, b.flip, b.flip || b.maybeUpsideDown) { on -> state.setBeauty { it.copy(flip = on) } }
        Switchable("Trim black border", if (b.trim == null) null else "Cut away", b.trim != null, b.trim != null || b.foundTrim != null) { on ->
          state.setBeauty { it.copy(trim = if (on) it.foundTrim else null) }
        }
        Graded("Glare (laminated card)", b.glare, b.glareAreas.isNotEmpty(), level, state.index) { v ->
          state.setBeauty { it.copy(glare = v) }
        }
        Graded("Stains / blotches", b.stains, true, level, state.index) { v -> state.setBeauty { it.copy(stains = v) } }
        Graded("Yellow paper → white", b.whiten, true, level, state.index) { v -> state.setBeauty { it.copy(whiten = v) } }
        // (outlined first; only flattened when turned on: a printed line must never vanish)
        Graded("Folds / creases", b.folds, b.foldLines.isNotEmpty(), level, state.index) { v ->
          state.setBeauty { it.copy(folds = v) }
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
  // a dot for each step: green when on, amber when found but off, grey when not found
  val dot = when {
    on -> EditorColors.Accent
    enabled -> EditorColors.Amber
    else -> EditorColors.Line
  }
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind {
    drawLine(EditorColors.Divider, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
  }, verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
    Spacer(Modifier.width(12.dp))
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

/**
 * A point of the page as Beautify analysed it, on the shown page: through Beautify's border trim,
 * upside-down turn and straightening, then the user's crop box. [aspect]: the framed page's
 * width / height (straightening turns it in pixels, not in parts).
 */
private fun toShown(x0: Float, y0: Float, b: BeautifyRecipe, crop: CropRect?, aspect: Float, f: Rect): Offset {
  var x = x0
  var y = y0
  b.trim?.let { t ->
    x = (x - t.left) / max(0.0001f, t.width)
    y = (y - t.top) / max(0.0001f, t.height)
  }
  if (b.flip) {
    x = 1f - x
    y = 1f - y
  }
  if (b.deskew != 0f) {
    val rad = Math.toRadians(b.deskew.toDouble())
    val c = kotlin.math.cos(rad).toFloat()
    val s = kotlin.math.sin(rad).toFloat()
    val dx = (x - 0.5f) * aspect
    val dy = y - 0.5f
    x = (dx * c - dy * s) / aspect + 0.5f
    y = dx * s + dy * c + 0.5f
  }
  val k = crop ?: CropRect(0f, 0f, 1f, 1f)
  val u = (x - k.left) / max(0.0001f, k.width)
  val v = (y - k.top) / max(0.0001f, k.height)
  return Offset(f.left + u * f.width, f.top + v * f.height)
}

/** A box of the analysed page as a shape on the shown page (straightening may tilt it) */
private fun shownBox(c: CropRect, b: BeautifyRecipe, crop: CropRect?, aspect: Float, f: Rect): Path = Path().apply {
  val p0 = toShown(c.left, c.top, b, crop, aspect, f)
  val p1 = toShown(c.right, c.top, b, crop, aspect, f)
  val p2 = toShown(c.right, c.bottom, b, crop, aspect, f)
  val p3 = toShown(c.left, c.bottom, b, crop, aspect, f)
  moveTo(p0.x, p0.y)
  lineTo(p1.x, p1.y)
  lineTo(p2.x, p2.y)
  lineTo(p3.x, p3.y)
  close()
}

/**
 * Outlined before anything is done: edge marks to paint over, fold lines to flatten, the border
 * to cut (amber while offered, green once on); fold lines on request too.
 */
internal fun beautyOverlay(state: PageEditorState): (DrawScope.(Rect) -> Unit)? {
  val r = state.recipe
  val fingers = state.fingerBoxes.mapNotNull { com.docready.aamunim.imaging.PageRenderer.inCrop(it, r.crop) }
  val b: BeautifyRecipe = r.beautify ?: return fingerOverlay(fingers, r.fingers)
  val folds = b.foldLines.isNotEmpty() && (b.folds <= 0f || state.showFolds)
  val trim = b.trim == null && b.foundTrim != null
  if (b.edgeMarks.isEmpty() && !folds && !trim) return fingerOverlay(fingers, r.fingers)
  val geo = state.geometryImage
  val aspect = if (geo != null) geo.width.toFloat() / max(1, geo.height) else 1f
  val withFingers = fingerOverlay(fingers, r.fingers)
  return { f ->
    withFingers?.invoke(this, f)
    val stroke = Stroke(width = 2.dp.toPx())
    for (m in b.edgeMarks) {
      val color = if (b.edges) EditorColors.Accent else Amber
      if (m.kind == EdgeKind.HOLE && b.deskew == 0f) {
        val a = toShown(m.box.left, m.box.top, b, r.crop, aspect, f)
        val z = toShown(m.box.right, m.box.bottom, b, r.crop, aspect, f)
        drawOval(color, topLeft = Offset(min(a.x, z.x), min(a.y, z.y)), size = Size(abs(z.x - a.x), abs(z.y - a.y)), style = stroke)
      } else {
        drawPath(shownBox(m.box, b, r.crop, aspect, f), color, style = stroke)
      }
    }
    if (folds) {
      for (l in b.foldLines) {
        val from = if (l.horizontal) toShown(0f, l.at, b, r.crop, aspect, f) else toShown(l.at, 0f, b, r.crop, aspect, f)
        val to = if (l.horizontal) toShown(1f, l.at, b, r.crop, aspect, f) else toShown(l.at, 1f, b, r.crop, aspect, f)
        drawLine(if (b.folds > 0f) EditorColors.Accent else Amber, from, to, strokeWidth = 2.dp.toPx())
      }
    }
    if (trim) {
      // what would be kept (the rest is the border)
      drawPath(shownBox(b.foundTrim, b, r.crop, aspect, f), Amber, style = stroke)
    }
  }
}

/**
 * The fingers found, outlined in amber on the framed page (their boxes are parts of it), whether
 * left in or painted over (the before view shows them)
 */
private fun fingerOverlay(boxes: List<com.docready.aamunim.imaging.beauty.FBox>, @Suppress("UNUSED_PARAMETER") on: Boolean): (DrawScope.(Rect) -> Unit)? {
  if (boxes.isEmpty()) return null
  return { f ->
    val stroke = Stroke(width = 2.dp.toPx())
    for (b in boxes) {
      drawRect(
        Amber,
        topLeft = Offset(f.left + b.left * f.width, f.top + b.top * f.height),
        size = Size((b.right - b.left) * f.width, (b.bottom - b.top) * f.height),
        style = stroke,
      )
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
