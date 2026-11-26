package com.docready.aamunim.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One page being cleaned, for the scanning animation: the raw page, the cleaned result once it is
 * ready, what is happening, and whether it is done. [key] tells pages apart (a new page restarts).
 */
data class ScanAnim(
  val key: Int,
  val before: ImageBitmap,
  val after: ImageBitmap?,
  val label: String,
  val done: Boolean = false,
)

/** The phone's "Remove animations" / animator scale 0 turns the sweep into a plain progress bar */
fun animationsEnabled(context: Context): Boolean = try {
  Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
} catch (_: Exception) {
  true
}

private val Glow = Color(0xFF8FD19E)
private val Line = Color(0xFF1B6B3A)

/** Where [image] sits inside a [w] × [h] area (fitted, centred) */
private fun fit(image: ImageBitmap, w: Float, h: Float): Rect {
  val s = min(w / image.width, h / image.height)
  val iw = image.width * s
  val ih = image.height * s
  return Rect((w - iw) / 2f, (h - ih) / 2f, (w + iw) / 2f, (h + ih) / 2f)
}

private fun DrawScope.drawFitted(image: ImageBitmap, r: Rect) {
  drawImage(
    image,
    srcOffset = IntOffset.Zero,
    srcSize = IntSize(image.width, image.height),
    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
    dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
  )
}

/**
 * The scanning animation (our part: Google's scanner owns the camera screen): a green line with a
 * soft glow sweeps down the page and keeps sweeping while the page is worked on (off the main
 * thread); once the cleaned result is ready it completes the sweep, revealing the result behind
 * the line over the raw photo; the corners pulse while it works; a tick pops and the phone gives a
 * light haptic tick (as its settings allow) when done. With animations off: the page and a
 * progress bar.
 */
@Composable
fun ScanAnimationCard(anim: ScanAnim, modifier: Modifier = Modifier, height: Int = 260, textColor: Color = Color.Unspecified) {
  val context = LocalContext.current
  val on = remember { animationsEnabled(context) }
  val haptic = LocalHapticFeedback.current
  val pos = remember(anim.key) { Animatable(0f) }
  var revealed by remember(anim.key) { mutableStateOf(false) }
  val tick = remember(anim.key) { Animatable(0f) }
  val ready = anim.after != null

  LaunchedEffect(anim.key, ready, on) {
    if (!on) {
      revealed = ready
      return@LaunchedEffect
    }
    if (!ready) {
      // loops until the result is ready
      while (true) {
        pos.snapTo(0f)
        pos.animateTo(1f, tween(1300, easing = LinearEasing))
      }
    } else {
      // the sweep finishes from where it is, revealing the cleaned page
      val left = 1f - pos.value
      pos.animateTo(1f, tween((left * 900).roundToInt().coerceAtLeast(160), easing = LinearEasing))
      revealed = true
    }
  }
  LaunchedEffect(anim.key, anim.done) {
    if (anim.done) {
      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
      if (on) tick.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) else tick.snapTo(1f)
    }
  }
  val pulse by rememberInfiniteTransition(label = "corners").animateFloat(
    initialValue = 0.35f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    label = "cornerPulse",
  )

  Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) {
      Canvas(Modifier.fillMaxSize()) {
        val raw = fit(anim.before, size.width, size.height)
        drawFitted(anim.before, raw)
        val after = anim.after
        val y = raw.top + raw.height * (if (on && !revealed) pos.value else 1f)
        if (after != null) {
          val done = fit(after, size.width, size.height)
          // behind the line: the cleaned page; ahead of it: still the raw photo
          clipRect(0f, 0f, size.width, if (revealed || !on) size.height else y) { drawFitted(after, done) }
        }
        if (on && !revealed) {
          val trail = 70.dp.toPx()
          drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Glow.copy(alpha = 0.38f)), startY = y - trail, endY = y),
            topLeft = Offset(raw.left, y - trail),
            size = Size(raw.width, trail),
          )
          drawLine(Glow.copy(alpha = 0.45f), Offset(raw.left, y), Offset(raw.right, y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
          drawLine(Line, Offset(raw.left, y), Offset(raw.right, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
          // the page's corners, pulsing while it is straightened and cleaned
          val arm = min(raw.width, raw.height) * 0.12f
          val c = Glow.copy(alpha = pulse)
          val sw = 3.dp.toPx()
          for ((cx, cy, dx, dy) in listOf(
            listOf(raw.left, raw.top, 1f, 1f),
            listOf(raw.right, raw.top, -1f, 1f),
            listOf(raw.right, raw.bottom, -1f, -1f),
            listOf(raw.left, raw.bottom, 1f, -1f),
          )) {
            drawLine(c, Offset(cx, cy), Offset(cx + dx * arm, cy), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, Offset(cx, cy), Offset(cx, cy + dy * arm), strokeWidth = sw, cap = StrokeCap.Round)
          }
        }
      }
      if (anim.done) {
        Box(
          Modifier
            .graphicsLayer {
              scaleX = tick.value
              scaleY = tick.value
            }
            .size(56.dp)
            .background(Line, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(AppIcons.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(30.dp))
        }
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!on && !anim.done) {
        LinearProgressIndicator(modifier = Modifier.width(80.dp))
        Box(Modifier.width(10.dp))
      }
      Text(
        anim.label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (textColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else textColor,
      )
    }
  }
}
