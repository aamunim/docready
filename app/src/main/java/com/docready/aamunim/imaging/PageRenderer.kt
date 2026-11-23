package com.docready.aamunim.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.docready.aamunim.data.model.CropRect
import com.docready.aamunim.data.model.Look
import com.docready.aamunim.data.model.PageRecipe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A page made from its source and its [PageRecipe] (non-destructive editing): the same code
 * makes the editor's preview (a small source) and the full-quality page (the full source).
 * Every function blocks and uses up the picture it is given: call from Dispatchers.Default.
 */
object PageRenderer {
  /** The whole recipe */
  fun render(src: Bitmap, r: PageRecipe): Bitmap {
    if (r.isPlain) return src
    return finish(geometry(src, r), r)
  }

  /** Corners, quarter turns and fine rotation (what the crop box is drawn on) */
  fun geometry(src: Bitmap, r: PageRecipe): Bitmap {
    var b = DocTools.straighten(src, r.quad?.toFloatArray(), r.turn)
    if (abs(r.rotate) >= 0.05f) b = rotateFine(b, r.rotate)
    return b
  }

  /** After the geometry: the crop box, then shadows / look / light, then erasing */
  fun finish(geo: Bitmap, r: PageRecipe): Bitmap = erase(look(cropped(geo, r.crop), r), r)

  /** A few degrees of rotation around the middle, the same size, white in the corners */
  fun rotateFine(src: Bitmap, degrees: Float): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.WHITE)
    val m = Matrix().apply { postRotate(degrees, src.width / 2f, src.height / 2f) }
    c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    src.recycle()
    return out
  }

  fun cropped(src: Bitmap, crop: CropRect?): Bitmap {
    if (crop == null) return src
    val x = (crop.left.coerceIn(0f, 1f) * src.width).roundToInt().coerceIn(0, src.width - 1)
    val y = (crop.top.coerceIn(0f, 1f) * src.height).roundToInt().coerceIn(0, src.height - 1)
    val w = max(1, min(src.width - x, (crop.width * src.width).roundToInt()))
    val h = max(1, min(src.height - y, (crop.height * src.height).roundToInt()))
    if (x == 0 && y == 0 && w == src.width && h == src.height) return src
    val out = Bitmap.createBitmap(src, x, y, w, h)
    if (out !== src) src.recycle()
    return out
  }

  /** Shadows out (the paper's own light measured and evened), the look, then brightness / contrast */
  fun look(src: Bitmap, r: PageRecipe): Bitmap {
    var b = DocTools.mutable(src)
    if (r.removeShadows) b = DocTools.flattenLight(b)
    b = when (r.look) {
      Look.ORIGINAL -> b
      Look.AUTO -> DocTools.autoLevels(b)
      Look.GREY -> DocTools.toGray(DocTools.autoLevels(b))
      Look.BW -> DocTools.toBlackWhite(DocTools.autoLevels(b), 15)
      Look.WHITEN -> DocTools.autoLevels(b).also { DocTools.liftFolds(it, false) }
    }
    if (abs(r.brightness) >= 0.01f || abs(r.contrast) >= 0.01f) b = light(b, r.brightness, r.contrast)
    return b
  }

  /** Brightness (-1 to 1) and contrast (-0.5 to 1) through a colour matrix */
  fun light(src: Bitmap, brightness: Float, contrast: Float): Bitmap {
    val s = 1f + contrast.coerceIn(-0.5f, 1f)
    val t = 128f * (1f - s) + brightness.coerceIn(-1f, 1f) * 100f
    val cm = ColorMatrix(
      floatArrayOf(
        s, 0f, 0f, 0f, t,
        0f, s, 0f, 0f, t,
        0f, 0f, s, 0f, t,
        0f, 0f, 0f, 1f, 0f,
      ),
    )
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    src.recycle()
    return out
  }

  /** The paper's colour: the light part of the page (its brightest fifth), averaged */
  fun paperColour(b: Bitmap): Int {
    val stepX = max(1, b.width / 64)
    val stepY = max(1, b.height / 64)
    val samples = ArrayList<Int>()
    var y = stepY / 2
    while (y < b.height) {
      var x = stepX / 2
      while (x < b.width) {
        samples.add(b.getPixel(x, y))
        x += stepX
      }
      y += stepY
    }
    if (samples.isEmpty()) return Color.WHITE
    fun lum(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
    val light = samples.sortedByDescending { lum(it) }.take(max(1, samples.size / 5))
    val r = light.sumOf { Color.red(it) } / light.size
    val g = light.sumOf { Color.green(it) } / light.size
    val bl = light.sumOf { Color.blue(it) } / light.size
    return Color.rgb(r, g, bl)
  }

  /** The eraser's strokes painted on the finished page (in place) */
  fun erase(src: Bitmap, r: PageRecipe): Bitmap {
    if (r.erase.isEmpty()) return src
    val b = DocTools.mutable(src)
    val crop = r.crop ?: CropRect(0f, 0f, 1f, 1f)
    val cw = max(0.0001f, crop.width)
    val ch = max(0.0001f, crop.height)
    val paper = paperColour(b)
    val canvas = Canvas(b)
    for (s in r.erase) {
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (s.white) Color.WHITE else paper
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = max(1f, s.radius / cw * b.width * 2f)
      }
      val n = s.points.size / 2
      if (n == 0) continue
      fun px(i: Int) = (s.points[i * 2] - crop.left) / cw * b.width
      fun py(i: Int) = (s.points[i * 2 + 1] - crop.top) / ch * b.height
      if (n == 1) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(px(0), py(0), paint.strokeWidth / 2f, paint)
      } else {
        val path = Path().apply {
          moveTo(px(0), py(0))
          for (i in 1 until n) lineTo(px(i), py(i))
        }
        canvas.drawPath(path, paint)
      }
    }
    return b
  }
}
