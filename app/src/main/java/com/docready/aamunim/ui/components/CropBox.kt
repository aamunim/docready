package com.docready.aamunim.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A box on a picture, as parts of it (0-1): left, top, right, bottom */
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top

  companion object {
    val Default = NormBox(0.05f, 0.05f, 0.95f, 0.95f)

    /**
     * The biggest box of [ratio] (its height / width, in parts of the picture) that fits,
     * centred, [fill] of the picture's width or height
     */
    fun centered(ratio: Float, fill: Float = 0.9f): NormBox {
      var w = fill
      var h = w * ratio
      if (h > fill) {
        h = fill
        w = h / ratio
      }
      return NormBox((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
    }
  }
}

/**
 * A corner dragged while the box keeps its shape: the opposite corner stays, the size follows
 * the drag, never past the picture's edges. [ratio]: height / width in parts of the picture.
 */
private fun resizeFixed(b: NormBox, corner: Int, dx: Float, dy: Float, ratio: Float, minPart: Float): NormBox {
  val left = corner == 0 || corner == 3
  val top = corner == 0 || corner == 1
  val ax = if (left) b.right else b.left
  val ay = if (top) b.bottom else b.top
  val sx = if (left) -1f else 1f
  val sy = if (top) -1f else 1f
  // the drag along both directions, as a change of width
  var w = b.width + (sx * dx + sy * dy / ratio) / 2f
  val maxW = min(if (left) ax else 1f - ax, (if (top) ay else 1f - ay) / ratio)
  w = w.coerceIn(min(minPart, maxW), maxW)
  val h = w * ratio
  val l = if (left) ax - w else ax
  val t = if (top) ay - h else ay
  return NormBox(l, t, l + w, t + h)
}

/**
 * The picture with a box to drag: a corner resizes, inside moves the whole box.
 * [fixedRatio]: the box keeps this shape (height / width, in parts of the picture).
 * [overlay]: drawn inside the box (e.g. a signature placed on a page); then the outside
 * is not darkened.
 */
@Composable
fun CropBox(
  image: ImageBitmap,
  box: NormBox,
  onBoxChange: (NormBox) -> Unit,
  modifier: Modifier = Modifier,
  fixedRatio: Float? = null,
  overlay: ImageBitmap? = null,
  maxHeight: Int = 380,
) {
  val ratio = image.width.toFloat() / image.height.toFloat()
  val current by rememberUpdatedState(box)
  val change by rememberUpdatedState(onBoxChange)
  val keep by rememberUpdatedState(fixedRatio)
  val lineColor = MaterialTheme.colorScheme.primary
  // 0-3: a corner (top-left, top-right, bottom-right, bottom-left), 4: the whole box, -1: nothing
  var mode by remember { mutableIntStateOf(-1) }
  Box(modifier.fillMaxWidth().heightIn(max = maxHeight.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.aspectRatio(ratio)) {
      Image(image, contentDescription = "Picture", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
      Canvas(
        Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            val minPart = 0.06f
            detectDragGestures(
              onDragStart = { start ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val b = current
                val corners = listOf(
                  Offset(b.left * w, b.top * h),
                  Offset(b.right * w, b.top * h),
                  Offset(b.right * w, b.bottom * h),
                  Offset(b.left * w, b.bottom * h),
                )
                val near = corners.indices.minByOrNull { hypot(corners[it].x - start.x, corners[it].y - start.y) } ?: 0
                val dist = hypot(corners[near].x - start.x, corners[near].y - start.y)
                val reach = 48.dp.toPx()
                mode = when {
                  dist <= reach -> near
                  start.x in b.left * w..b.right * w && start.y in b.top * h..b.bottom * h -> 4
                  else -> -1
                }
              },
              onDragEnd = { mode = -1 },
              onDragCancel = { mode = -1 },
              onDrag = { pointer, drag ->
                pointer.consume()
                val dx = drag.x / size.width
                val dy = drag.y / size.height
                val b = current
                val fixed = keep
                val next = when {
                  mode in 0..3 && fixed != null -> resizeFixed(b, mode, dx, dy, fixed, minPart)
                  mode == 0 -> b.copy(left = (b.left + dx).coerceIn(0f, b.right - minPart), top = (b.top + dy).coerceIn(0f, b.bottom - minPart))
                  mode == 1 -> b.copy(right = (b.right + dx).coerceIn(b.left + minPart, 1f), top = (b.top + dy).coerceIn(0f, b.bottom - minPart))
                  mode == 2 -> b.copy(right = (b.right + dx).coerceIn(b.left + minPart, 1f), bottom = (b.bottom + dy).coerceIn(b.top + minPart, 1f))
                  mode == 3 -> b.copy(left = (b.left + dx).coerceIn(0f, b.right - minPart), bottom = (b.bottom + dy).coerceIn(b.top + minPart, 1f))
                  mode == 4 -> {
                    val mx = dx.coerceIn(-b.left, 1f - b.right)
                    val my = dy.coerceIn(-b.top, 1f - b.bottom)
                    NormBox(b.left + mx, b.top + my, b.right + mx, b.bottom + my)
                  }
                  else -> null
                }
                if (next != null) change(next)
              },
            )
          },
      ) {
        val w = size.width
        val h = size.height
        val l = box.left * w
        val t = box.top * h
        val r = box.right * w
        val btm = box.bottom * h
        if (overlay != null) {
          drawImage(
            overlay,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(overlay.width, overlay.height),
            dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
            dstSize = IntSize(max(1, (r - l).roundToInt()), max(1, (btm - t).roundToInt())),
          )
        } else {
          val shade = Color.Black.copy(alpha = 0.45f)
          // outside the box: darker
          drawRect(shade, Offset(0f, 0f), Size(w, t))
          drawRect(shade, Offset(0f, btm), Size(w, h - btm))
          drawRect(shade, Offset(0f, t), Size(l, btm - t))
          drawRect(shade, Offset(r, t), Size(w - r, btm - t))
        }
        drawRect(lineColor, Offset(l, t), Size(r - l, btm - t), style = Stroke(width = 2.dp.toPx()))
        val handle = 7.dp.toPx()
        for (p in listOf(Offset(l, t), Offset(r, t), Offset(r, btm), Offset(l, btm))) {
          drawCircle(Color.White, radius = handle + 1.5.dp.toPx(), center = p)
          drawCircle(lineColor, radius = handle, center = p)
        }
      }
    }
  }
}

/**
 * A paper's four corners on its picture (x, y × 4, parts 0-1, in order top-left, top-right,
 * bottom-right, bottom-left): each corner is dragged on its own.
 */
@Composable
fun QuadBox(image: ImageBitmap, quad: FloatArray, onQuadChange: (FloatArray) -> Unit, modifier: Modifier = Modifier) {
  val ratio = image.width.toFloat() / image.height.toFloat()
  val current by rememberUpdatedState(quad)
  val change by rememberUpdatedState(onQuadChange)
  val lineColor = MaterialTheme.colorScheme.primary
  var corner by remember { mutableIntStateOf(-1) }
  Box(modifier.fillMaxWidth().heightIn(max = 460.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.aspectRatio(ratio)) {
      Image(image, contentDescription = "Page", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
      Canvas(
        Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectDragGestures(
              onDragStart = { start ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val q = current
                val near = (0 until 4).minByOrNull { hypot(q[it * 2] * w - start.x, q[it * 2 + 1] * h - start.y) } ?: 0
                val dist = hypot(q[near * 2] * w - start.x, q[near * 2 + 1] * h - start.y)
                corner = if (dist <= 56.dp.toPx()) near else -1
              },
              onDragEnd = { corner = -1 },
              onDragCancel = { corner = -1 },
              onDrag = { pointer, drag ->
                val c = corner
                if (c >= 0) {
                  pointer.consume()
                  val q = current.copyOf()
                  q[c * 2] = (q[c * 2] + drag.x / size.width).coerceIn(0f, 1f)
                  q[c * 2 + 1] = (q[c * 2 + 1] + drag.y / size.height).coerceIn(0f, 1f)
                  change(q)
                }
              },
            )
          },
      ) {
        val w = size.width
        val h = size.height
        val pts = (0 until 4).map { Offset(quad[it * 2] * w, quad[it * 2 + 1] * h) }
        val outline = Path().apply {
          moveTo(pts[0].x, pts[0].y)
          for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
          close()
        }
        drawPath(outline, Color.Black.copy(alpha = 0.35f), style = Stroke(width = 5.dp.toPx()))
        drawPath(outline, lineColor, style = Stroke(width = 2.dp.toPx()))
        val handle = 9.dp.toPx()
        for (p in pts) {
          drawCircle(Color.White, radius = handle + 2.dp.toPx(), center = p)
          drawCircle(lineColor, radius = handle, center = p)
        }
      }
    }
  }
}

/** The whole picture as four corners */
fun fullQuad(): FloatArray = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

/** True when the corners are (nearly) the whole picture: nothing to straighten */
fun isFullQuad(q: FloatArray): Boolean = fullQuad().indices.all { abs(q[it] - fullQuad()[it]) < 0.005f }
