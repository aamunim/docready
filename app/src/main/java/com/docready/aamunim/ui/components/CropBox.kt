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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** A box on a picture, as parts of it (0-1): left, top, right, bottom */
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top

  companion object {
    val Default = NormBox(0.05f, 0.05f, 0.95f, 0.95f)
  }
}

/**
 * The picture with a box to drag: a corner moves that corner, inside moves the whole box.
 */
@Composable
fun CropBox(image: ImageBitmap, box: NormBox, onBoxChange: (NormBox) -> Unit, modifier: Modifier = Modifier) {
  val ratio = image.width.toFloat() / image.height.toFloat()
  val current by rememberUpdatedState(box)
  val change by rememberUpdatedState(onBoxChange)
  val lineColor = MaterialTheme.colorScheme.primary
  // 0-3: a corner (top-left, top-right, bottom-right, bottom-left), 4: the whole box, -1: nothing
  var mode by remember { mutableIntStateOf(-1) }
  Box(modifier.fillMaxWidth().heightIn(max = 380.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.aspectRatio(ratio)) {
      Image(image, contentDescription = "Picture to crop", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
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
                val next = when (mode) {
                  0 -> b.copy(left = (b.left + dx).coerceIn(0f, b.right - minPart), top = (b.top + dy).coerceIn(0f, b.bottom - minPart))
                  1 -> b.copy(right = (b.right + dx).coerceIn(b.left + minPart, 1f), top = (b.top + dy).coerceIn(0f, b.bottom - minPart))
                  2 -> b.copy(right = (b.right + dx).coerceIn(b.left + minPart, 1f), bottom = (b.bottom + dy).coerceIn(b.top + minPart, 1f))
                  3 -> b.copy(left = (b.left + dx).coerceIn(0f, b.right - minPart), bottom = (b.bottom + dy).coerceIn(b.top + minPart, 1f))
                  4 -> {
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
        val shade = Color.Black.copy(alpha = 0.45f)
        // outside the box: darker
        drawRect(shade, Offset(0f, 0f), Size(w, t))
        drawRect(shade, Offset(0f, btm), Size(w, h - btm))
        drawRect(shade, Offset(0f, t), Size(l, btm - t))
        drawRect(shade, Offset(r, t), Size(w - r, btm - t))
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
