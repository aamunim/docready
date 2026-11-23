package com.docready.aamunim.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * DocReady's picture and PDF work, all on the phone (no internet).
 * Ported from the DocReady Expo module (pure Android: no React Native APIs);
 * every function here is blocking: call it from Dispatchers.Default / IO.
 *
 *  · load a picture the right way up, at a size that fits in memory
 *  · clean it: even light and contrast, Gray, or sharp Black & White
 *  · make it as small as asked (JPEG quality first, then a smaller size)
 *  · put pages into a PDF (each page is the JPEG itself, so its weight is known)
 *  · tell if a picture is blurry or too dark
 */
object DocTools {
  const val A4_W_PT = 595
  const val A4_H_PT = 842
  private const val MIN_QUALITY = 35 // below this, small writing becomes hard to read
  private const val MAX_QUALITY = 92

  data class Fit(val bytes: ByteArray, val width: Int, val height: Int, val quality: Int, val fits: Boolean)

  // ------------------------------------------------------------------ loading

  /** The picture, the right way up, its longer side at most [maxSide] */
  fun load(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Not a picture.")

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inMutable = true // cleaned in place
    }
    var bmp = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
      ?: throw IllegalArgumentException("Could not read the picture.")

    // the camera's "which way up" note (a gallery photo often has one)
    val orientation = try {
      resolver.openInputStream(uri).use { s ->
        if (s == null) ExifInterface.ORIENTATION_NORMAL
        else ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      }
    } catch (_: Exception) {
      ExifInterface.ORIENTATION_NORMAL
    }
    bmp = orient(bmp, orientation)
    return mutable(scaleToMax(bmp, maxSide))
  }

  /** The camera's "which way up" note, mirrored ones too */
  fun orient(src: Bitmap, o: Int): Bitmap {
    val m = Matrix()
    when (o) {
      ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
      ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
      else -> return src
    }
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (out !== src) src.recycle()
    return out
  }

  /** A picture that can be changed in place */
  fun mutable(src: Bitmap): Bitmap {
    if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) return src
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    src.recycle()
    return out
  }

  /** [src] at most [maxSide] on its longer side; [src] is used up (recycled) when a smaller copy is made */
  fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
    val side = max(src.width, src.height)
    if (side <= maxSide) return src
    val k = maxSide.toFloat() / side
    return scale(src, (src.width * k).roundToInt(), (src.height * k).roundToInt())
  }

  fun scale(src: Bitmap, w: Int, h: Int): Bitmap {
    if (src.width == w && src.height == h) return src
    val out = Bitmap.createScaledBitmap(src, max(1, w), max(1, h), true)
    if (out !== src) src.recycle()
    return out
  }

  /** Cut the middle to the asked shape (e.g. 1:1 for a photo), then make it exactly w × h */
  fun cropTo(src: Bitmap, w: Int, h: Int): Bitmap {
    val want = w.toFloat() / h
    val have = src.width.toFloat() / src.height
    val rect = if (have > want) {
      val cw = (src.height * want).roundToInt()
      Rect((src.width - cw) / 2, 0, (src.width - cw) / 2 + cw, src.height)
    } else {
      val ch = (src.width / want).roundToInt()
      Rect(0, (src.height - ch) / 2, src.width, (src.height - ch) / 2 + ch)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, rect, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    src.recycle()
    return out
  }

  // ------------------------------------------------------------------ cleaning

  private fun lum(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

  /** Even out light and contrast: the darkest 1% → black, the lightest 1% → white */
  fun autoLevels(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val hist = IntArray(256)
    for (c in px) hist[lum(c)]++
    val cut = px.size / 100
    var lo = 0
    var acc = 0
    while (lo < 255 && acc + hist[lo] <= cut) acc += hist[lo++]
    var hi = 255
    acc = 0
    while (hi > 0 && acc + hist[hi] <= cut) acc += hist[hi--]
    if (hi - lo < 40) return src // already even (or almost blank)
    val map = IntArray(256) { v -> ((v - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
    for (i in px.indices) {
      val c = px[i]
      px[i] = Color.argb(255, map[Color.red(c)], map[Color.green(c)], map[Color.blue(c)])
    }
    src.setPixels(px, 0, w, 0, 0, w, h)
    return src
  }

  fun toGray(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
      val l = lum(px[i])
      px[i] = Color.argb(255, l, l, l)
    }
    src.setPixels(px, 0, w, 0, 0, w, h)
    return src
  }

  /**
   * Sharp Black & White for papers: each dot is compared with the light
   * around it (Bradley's method), so shadows and uneven light don't turn
   * into black patches. [strength] 15 = papers; higher = only firm ink stays
   * (a signature).
   */
  fun toBlackWhite(src: Bitmap, strength: Int = 15): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val gray = IntArray(w * h) { lum(px[it]) }
    // An Int table on purpose (half the memory of a Long one on a 3500 px page): entries may wrap
    // past Int.MAX_VALUE on pictures over ~8.4 million dots, but each window's sum below is a
    // difference of four entries, and wrapping (two's complement) arithmetic gives that
    // difference exactly as long as the window's own sum fits an Int — at most 255 × (w/16+1)²,
    // about 12 million for a 3500 px page.
    val integral = IntArray((w + 1) * (h + 1))
    for (y in 0 until h) {
      var row = 0
      for (x in 0 until w) {
        row += gray[y * w + x]
        integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + row
      }
    }
    val s = max(8, w / 16)
    val half = s / 2
    for (y in 0 until h) {
      val y1 = max(0, y - half)
      val y2 = min(h - 1, y + half)
      for (x in 0 until w) {
        val x1 = max(0, x - half)
        val x2 = min(w - 1, x + half)
        val count = (x2 - x1 + 1).toLong() * (y2 - y1 + 1)
        val sum = (integral[(y2 + 1) * (w + 1) + (x2 + 1)] - integral[y1 * (w + 1) + (x2 + 1)] -
          integral[(y2 + 1) * (w + 1) + x1] + integral[y1 * (w + 1) + x1]).toLong()
        val v = if (gray[y * w + x].toLong() * count * 100 <= sum * (100 - strength)) 0 else 255
        px[y * w + x] = Color.argb(255, v, v, v)
      }
    }
    src.setPixels(px, 0, w, 0, 0, w, h)
    return src
  }

  /**
   * Shadows and uneven light off a paper: the paper's own light is measured
   * all over (the brightest part of each small square, so the writing is
   * left out), smoothed, and every dot is lifted by it — the paper comes out
   * evenly white, the writing as it was. Only for papers (never a face).
   */
  fun flattenLight(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val cell = max(8, Math.round(max(w, h) / 80f))
    val gw = (w + cell - 1) / cell
    val gh = (h + cell - 1) / cell
    val grid = FloatArray(gw * gh)
    for (gy in 0 until gh) {
      for (gx in 0 until gw) {
        var top = 0
        val y2 = min(h, (gy + 1) * cell)
        val x2 = min(w, (gx + 1) * cell)
        var y = gy * cell
        while (y < y2) {
          var x = gx * cell
          while (x < x2) {
            val l = lum(px[y * w + x])
            if (l > top) top = l
            x += 2
          }
          y += 2
        }
        grid[gy * gw + gx] = top.toFloat()
      }
    }
    // squares full of writing: the paper around them; then smooth
    fun pass(input: FloatArray, maxMode: Boolean): FloatArray {
      val out = FloatArray(input.size)
      for (y in 0 until gh) {
        for (x in 0 until gw) {
          var acc = 0f
          var n = 0
          for (dy in -1..1) for (dx in -1..1) {
            val yy = y + dy
            val xx = x + dx
            if (yy in 0 until gh && xx in 0 until gw) {
              val v = input[yy * gw + xx]
              if (maxMode) { if (v > acc) acc = v } else acc += v
              n++
            }
          }
          out[y * gw + x] = if (maxMode) acc else acc / n
        }
      }
      return out
    }
    var bg = pass(grid, true)
    bg = pass(pass(bg, false), false)
    for (y in 0 until h) {
      val fy = min((gh - 1).toFloat(), max(0f, y.toFloat() / cell - 0.5f))
      val y0 = fy.toInt()
      val y1 = min(gh - 1, y0 + 1)
      val ty = fy - y0
      for (x in 0 until w) {
        val fx = min((gw - 1).toFloat(), max(0f, x.toFloat() / cell - 0.5f))
        val x0 = fx.toInt()
        val x1 = min(gw - 1, x0 + 1)
        val tx = fx - x0
        val light = (bg[y0 * gw + x0] * (1 - tx) + bg[y0 * gw + x1] * tx) * (1 - ty) +
          (bg[y1 * gw + x0] * (1 - tx) + bg[y1 * gw + x1] * tx) * ty
        // only paper is lifted: a dark area (a printed photo, a dark band, the table) is left
        // as it is, and the lift comes in slowly between 60 and 100 (no halo, no washed photo)
        val full = min(3f, 245f / max(light, 1f))
        val k = if (light <= 60f) 1f else if (light >= 100f) full else 1f + (full - 1f) * ((light - 60f) / 40f)
        val c = px[y * w + x]
        px[y * w + x] = Color.argb(
          255,
          min(255, (Color.red(c) * k + 0.5f).toInt()),
          min(255, (Color.green(c) * k + 0.5f).toInt()),
          min(255, (Color.blue(c) * k + 0.5f).toInt()),
        )
      }
    }
    src.setPixels(px, 0, w, 0, 0, w, h)
    return src
  }

  /**
   * color: "color" | "gray" | "bw" | "signature"; [paper]: shadows out first (papers, signatures);
   * [shadow] / [fold] as chosen in the cut sheet
   */
  fun clean(
    src: Bitmap,
    color: String,
    enhance: Boolean,
    paper: Boolean = false,
    shadow: Boolean = true,
    fold: Boolean? = null,
  ): Bitmap {
    var start = mutable(src)
    // (only when making it look better is on: a page left as it is stays as it is)
    if (enhance && shadow && (paper || color == "signature")) start = flattenLight(start)
    // folds: on by itself for Gray / B&W papers; never a signature's faint pen
    if (enhance && paper && color != "signature" && (fold ?: (color == "gray" || color == "bw"))) {
      liftFolds(start, color != "color")
    }
    var b = if (enhance) autoLevels(start) else start
    b = when (color) {
      "gray" -> toGray(b)
      "bw" -> toBlackWhite(b, 15)
      "signature" -> toBlackWhite(b, 25)
      else -> b
    }
    return b
  }

  /**
   * Folds and creases: once the light is even, a fold is a soft grey line on
   * white paper, far lighter than any writing. The light greys are made white,
   * the writing kept as it was; colours keep their hue.
   */
  fun liftFolds(b: Bitmap, strong: Boolean) {
    val lo = if (strong) 150f else 185f
    val hi = if (strong) 222f else 232f
    val w = b.width
    val h = b.height
    val row = IntArray(w)
    for (y in 0 until h) {
      b.getPixels(row, 0, w, 0, y, w, 1)
      for (x in 0 until w) {
        val c = row[x]
        val r = Color.red(c)
        val g = Color.green(c)
        val bl = Color.blue(c)
        val l = (r * 299 + g * 587 + bl * 114) / 1000f
        if (l <= lo) continue
        val t = if (l >= hi) 1f else (l - lo) / (hi - lo)
        val s2 = t * t * (3 - 2 * t)
        row[x] = Color.argb(
          Color.alpha(c),
          (r + (255 - r) * s2).roundToInt(),
          (g + (255 - g) * s2).roundToInt(),
          (bl + (255 - bl) * s2).roundToInt(),
        )
      }
      b.setPixels(row, 0, w, 0, y, w, 1)
    }
  }

  /**
   * A paper photographed at an angle, straightened from its four corners
   * (top-left, top-right, bottom-right, bottom-left; parts 0-1): Android's own drawing maps them onto an upright
   * page, the longer of each pair of sides, at most 2400 on its long side.
   */
  /** Four corners (x, y × 4, in order around) making a real, unfolded shape */
  fun isConvex(q: FloatArray): Boolean {
    if (q.size < 8 || q.any { it.isNaN() }) return false
    var sign = 0
    for (i in 0 until 4) {
      val ax = q[i * 2]; val ay = q[i * 2 + 1]
      val bx = q[((i + 1) % 4) * 2]; val by = q[((i + 1) % 4) * 2 + 1]
      val cx = q[((i + 2) % 4) * 2]; val cy = q[((i + 2) % 4) * 2 + 1]
      val z = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
      if (abs(z) < 1e-6f) return false
      val s = if (z > 0) 1 else -1
      if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
  }

  fun warp(src: Bitmap, q: FloatArray, maxSide: Int = 2400): Bitmap {
    val W = src.width.toFloat()
    val H = src.height.toFloat()
    val px = FloatArray(8) { i -> q[i].coerceIn(0f, 1f) * if (i % 2 == 0) W else H }
    fun d(a: Int, b: Int) = sqrt(((px[a * 2] - px[b * 2]) * (px[a * 2] - px[b * 2]) + (px[a * 2 + 1] - px[b * 2 + 1]) * (px[a * 2 + 1] - px[b * 2 + 1])).toDouble()).toFloat()
    var ow = max(d(0, 1), d(3, 2))
    var oh = max(d(0, 3), d(1, 2))
    val k = min(1f, maxSide.toFloat() / max(ow, oh))
    ow = max(1f, ow * k)
    oh = max(1f, oh * k)
    val dst = floatArrayOf(0f, 0f, ow, 0f, ow, oh, 0f, oh)
    val m = Matrix()
    if (!m.setPolyToPoly(px, 0, dst, 0, 4)) throw IllegalStateException("These corners can’t be straightened.")
    val out = Bitmap.createBitmap(ow.roundToInt(), oh.roundToInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.WHITE)
    c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
  }

  /**
   * The paper's four corners on a photo (top-left, top-right, bottom-right, bottom-left; parts 0-1), found on a
   * small copy: the light evened out first (a shadow across the paper is still
   * paper), the biggest light patch, its points furthest towards each corner.
   * null: nothing clear (a scan, a clean page, a light table).
   */
  fun findPaper(src: Bitmap): FloatArray? {
    // a small copy of its own (scaleToMax would recycle the caller's picture, which may be on screen)
    val k = 320f / max(src.width, src.height)
    val small = if (k < 1f) {
      Bitmap.createScaledBitmap(src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true)
    } else {
      src
    }
    val w = small.width
    val h = small.height
    val raw = IntArray(w * h)
    small.getPixels(raw, 0, w, 0, 0, w, h)
    val rawLum = FloatArray(w * h) { lum(raw[it]).toFloat() }
    // (its own copy: the caller's picture is never changed or let go here)
    val flat = flattenLight(small.copy(Bitmap.Config.ARGB_8888, true))
    val px = IntArray(w * h)
    flat.getPixels(px, 0, w, 0, 0, w, h)
    flat.recycle()
    if (small !== src) small.recycle()
    val lumv = IntArray(w * h) { min(255, lum(px[it])) }
    val hist = IntArray(256)
    for (v in lumv) hist[v]++
    // Otsu: paper (light) vs the rest
    var sum = 0.0
    for (t in 0 until 256) sum += t * hist[t].toDouble()
    var sumB = 0.0
    var wB = 0.0
    var best = 0.0
    var th = 128
    val n = (w * h).toDouble()
    for (t in 0 until 256) {
      wB += hist[t]
      if (wB == 0.0) continue
      val wF = n - wB
      if (wF == 0.0) break
      sumB += t * hist[t]
      val between = wB * wF * (sumB / wB - (sum - sumB) / wF) * (sumB / wB - (sum - sumB) / wF)
      if (between > best) {
        best = between
        th = t
      }
    }
    // the biggest light patch
    val label = IntArray(w * h) { -1 }
    var bestId = -1
    var bestSize = 0
    val stack = IntArray(w * h)
    for (start in 0 until w * h) {
      if (label[start] != -1 || lumv[start] <= th) continue
      var top = 0
      var size = 0
      label[start] = start
      stack[top++] = start
      while (top > 0) {
        val i = stack[--top]
        size++
        val x = i % w
        val y = i / w
        if (x > 0 && label[i - 1] == -1 && lumv[i - 1] > th) { label[i - 1] = start; stack[top++] = i - 1 }
        if (x < w - 1 && label[i + 1] == -1 && lumv[i + 1] > th) { label[i + 1] = start; stack[top++] = i + 1 }
        if (y > 0 && label[i - w] == -1 && lumv[i - w] > th) { label[i - w] = start; stack[top++] = i - w }
        if (y < h - 1 && label[i + w] == -1 && lumv[i + w] > th) { label[i + w] = start; stack[top++] = i + w }
      }
      if (size > bestSize) {
        bestSize = size
        bestId = start
      }
    }
    if (bestId < 0 || bestSize > w * h * 0.93 || bestSize < w * h * 0.15) return null
    var tl = Triple(Float.MAX_VALUE, 0, 0)
    var br = Triple(-Float.MAX_VALUE, 0, 0)
    var tr = Triple(-Float.MAX_VALUE, 0, 0)
    var bl = Triple(Float.MAX_VALUE, 0, 0)
    var inSum = 0.0
    var outSum = 0.0
    for (i in 0 until w * h) {
      if (label[i] != bestId) {
        outSum += rawLum[i]
        continue
      }
      inSum += rawLum[i]
      val x = i % w
      val y = i / w
      val s1 = (x + y).toFloat()
      val s2 = (x - y).toFloat()
      if (s1 < tl.first) tl = Triple(s1, x, y)
      if (s1 > br.first) br = Triple(s1, x, y)
      if (s2 > tr.first) tr = Triple(s2, x, y)
      if (s2 < bl.first) bl = Triple(s2, x, y)
    }
    val outN = w * h - bestSize
    // only when what is around is clearly darker than the paper (a table)
    if (outN == 0 || inSum / bestSize - outSum / outN < 45) return null
    val q = floatArrayOf(
      (tl.second + 0.5f) / w, (tl.third + 0.5f) / h,
      (tr.second + 0.5f) / w, (tr.third + 0.5f) / h,
      (br.second + 0.5f) / w, (br.third + 0.5f) / h,
      (bl.second + 0.5f) / w, (bl.third + 0.5f) / h,
    )
    // a little inside each corner (no dark border left)
    val cx = (q[0] + q[2] + q[4] + q[6]) / 4
    val cy = (q[1] + q[3] + q[5] + q[7]) / 4
    for (i in 0 until 4) {
      q[i * 2] += (cx - q[i * 2]) * 0.012f
      q[i * 2 + 1] += (cy - q[i * 2 + 1]) * 0.012f
    }
    return q
  }

  /**
   * Only the ink of a signature (after Black & White): the paper made
   * see-through, the empty edges cut away; null = nothing there.
   */
  fun inkOnly(bw: Bitmap): Bitmap? {
    val w = bw.width
    val h = bw.height
    val px = IntArray(w * h)
    bw.getPixels(px, 0, w, 0, 0, w, h)
    var x0 = w
    var y0 = h
    var x1 = -1
    var y1 = -1
    for (y in 0 until h) {
      for (x in 0 until w) {
        val i = y * w + x
        if (lum(px[i]) < 128) {
          px[i] = Color.argb(255, 20, 20, 20) // near-black ink
          if (x < x0) x0 = x
          if (y < y0) y0 = y
          if (x > x1) x1 = x
          if (y > y1) y1 = y
        } else {
          px[i] = Color.TRANSPARENT
        }
      }
    }
    if (x1 < 0 || x1 - x0 < 8 || y1 - y0 < 4) return null
    val all = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    all.setPixels(px, 0, w, 0, 0, w, h)
    val pad = 4
    val l = max(0, x0 - pad)
    val t = max(0, y0 - pad)
    val out = Bitmap.createBitmap(all, l, t, min(w, x1 + pad + 1) - l, min(h, y1 + pad + 1) - t)
    if (out !== all) all.recycle()
    return out
  }

  /**
   * A page straightened from its four corners (when they make a real shape) and turned by
   * 0 / 90 / 180 / 270 degrees. [src] is used up (recycled when a new picture is made).
   */
  fun straighten(src: Bitmap, quad: FloatArray?, turn: Int, maxSide: Int = 2400): Bitmap {
    var b = src
    if (quad != null && isConvex(quad)) {
      val flat = warp(b, quad, maxSide)
      b.recycle()
      b = flat
    }
    if (turn % 360 != 0) {
      val m = Matrix().apply { postRotate(turn.toFloat()) }
      val turned = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
      if (turned !== b) b.recycle()
      b = turned
    }
    return b
  }

  /** A part of a picture (as parts of it, 0–1), turned by 0 / 90 / 180 / 270 */
  fun cut(src: Bitmap, x: Float, y: Float, w: Float, h: Float, turn: Int = 0): Bitmap {
    val sx = (x.coerceIn(0f, 1f) * src.width).roundToInt().coerceIn(0, src.width - 1)
    val sy = (y.coerceIn(0f, 1f) * src.height).roundToInt().coerceIn(0, src.height - 1)
    val sw = max(1, min(src.width - sx, (w * src.width).roundToInt()))
    val sh = max(1, min(src.height - sy, (h * src.height).roundToInt()))
    val m = Matrix()
    if (turn % 360 != 0) m.postRotate(turn.toFloat())
    return Bitmap.createBitmap(src, sx, sy, sw, sh, m, true)
  }

  // ------------------------------------------------------------------ quality check

  /** sharpness: higher = sharper (under ~60 looks blurry); brightness 0–255 */
  fun quality(context: Context, uri: Uri): Pair<Double, Double> {
    val b = load(context, uri, 500)
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    b.recycle()
    val g = IntArray(w * h) { lum(px[it]) }
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    var bright = 0.0
    for (v in g) bright += v
    for (y in 1 until h - 1) {
      for (x in 1 until w - 1) {
        val i = y * w + x
        val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
        sum += lap
        sumSq += lap * lap
        n++
      }
    }
    val mean = if (n > 0) sum / n else 0.0
    val variance = if (n > 0) sumSq / n - mean * mean else 0.0
    return Pair(sqrt(max(0.0, variance)) * 4, bright / max(1, g.size))
  }

  // ------------------------------------------------------------------ weight

  fun jpeg(b: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
  }

  /** The best quality that is still under [maxBytes] (null = just good quality) */
  private fun bestQuality(maxBytes: Long?, minQuality: Int = MIN_QUALITY, size: (Int) -> Long): Pair<Int, Boolean> {
    if (maxBytes == null || size(MAX_QUALITY) <= maxBytes) return Pair(MAX_QUALITY, true)
    if (size(minQuality) > maxBytes) return Pair(minQuality, false)
    var lo = minQuality
    var hi = MAX_QUALITY
    while (hi - lo > 2) {
      val mid = (lo + hi) / 2
      if (size(mid) <= maxBytes) lo = mid else hi = mid
    }
    return Pair(lo, true)
  }

  /**
   * One picture under [maxBytes]: quality first; if even the lowest
   * readable quality is too heavy, a smaller size (unless [fixedSize]).
   */
  fun fitPicture(start: Bitmap, maxBytes: Long?, fixedSize: Boolean): Fit {
    var b = start
    while (true) {
      val cache = HashMap<Int, ByteArray>()
      // a fixed size can't shrink: it may go lower in quality instead
      val (q, ok) = bestQuality(maxBytes, if (fixedSize) 15 else MIN_QUALITY) { qq ->
        cache.getOrPut(qq) { jpeg(b, qq) }.size.toLong()
      }
      val bytes = cache.getOrPut(q) { jpeg(b, q) }
      if (ok || fixedSize || max(b.width, b.height) <= 600) {
        val fit = Fit(bytes, b.width, b.height, q, ok)
        b.recycle()
        return fit
      }
      b = scale(b, (b.width * 0.85f).roundToInt(), (b.height * 0.85f).roundToInt())
    }
  }

  // ------------------------------------------------------------------ PDF

  data class Page(val jpeg: ByteArray, val width: Int, val height: Int)


  /** Counts what is written, so the PDF's object offsets are known while it streams to a file */
  private class CountingStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
      private set

    override fun write(b: Int) {
      out.write(b)
      count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      out.write(b, off, len)
      count += len
    }

    override fun flush() = out.flush()
  }

  /**
   * A PDF whose pages are the JPEGs themselves (/DCTDecode): its weight is the pictures'
   * weight plus about half a KB a page. Each page is A4 (landscape for a wide picture), the
   * picture filling it. Pages are asked for one at a time ([pageAt]) and written straight to
   * [sink], so only one page's JPEG is in memory. Returns the bytes written.
   */
  fun writePdf(sink: OutputStream, pageCount: Int, pageAt: (Int) -> Page): Long {
    val out = CountingStream(sink)
    val offsets = LongArray(2 + 3 * pageCount)
    fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
    fun obj(n: Int, body: () -> Unit) {
      offsets[n - 1] = out.count
      w("$n 0 obj\n")
      body()
      w("\nendobj\n")
    }
    w("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
    val n = pageCount
    // 1 catalog, 2 pages, then for page i: page 3+3i, content 4+3i, image 5+3i
    obj(1) { w("<< /Type /Catalog /Pages 2 0 R >>") }
    obj(2) {
      val kids = (0 until n).joinToString(" ") { "${3 + 3 * it} 0 R" }
      w("<< /Type /Pages /Kids [$kids] /Count $n >>")
    }
    for (i in 0 until n) {
      val p = pageAt(i)
      val landscape = p.width > p.height
      val pw = if (landscape) A4_H_PT else A4_W_PT
      val ph = if (landscape) A4_W_PT else A4_H_PT
      // fit inside the page, centred
      val k = min(pw.toDouble() / p.width, ph.toDouble() / p.height)
      val dw = p.width * k
      val dh = p.height * k
      val dx = (pw - dw) / 2
      val dy = (ph - dh) / 2
      val pageNo = 3 + 3 * i
      obj(pageNo) {
        w(
          "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
            "/Resources << /XObject << /Im0 ${pageNo + 2} 0 R >> >> /Contents ${pageNo + 1} 0 R >>",
        )
      }
      val content = String.format(java.util.Locale.US, "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q", dw, dh, dx, dy)
      obj(pageNo + 1) {
        w("<< /Length ${content.length} >>\nstream\n$content\nendstream")
      }
      obj(pageNo + 2) {
        w(
          "<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n",
        )
        out.write(p.jpeg)
        w("\nendstream")
      }
    }
    val xref = out.count
    val total = 2 + 3 * n
    w("xref\n0 ${total + 1}\n0000000000 65535 f \n")
    for (i in 0 until total) w(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[i]))
    w("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    out.flush()
    return out.count
  }

  /** A page file as a JPEG page at [quality], made [k] times smaller (one page in memory) */
  private fun encodePage(f: File, quality: Int, k: Float): Page {
    var b = BitmapFactory.decodeFile(f.path) ?: throw IllegalStateException("A page could not be read.")
    if (k < 0.999f) b = scale(b, (b.width * k).roundToInt(), (b.height * k).roundToInt())
    val p = Page(jpeg(b, quality), b.width, b.height)
    b.recycle()
    return p
  }

  private fun longSideOf(f: File): Int {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    return max(o.outWidth, o.outHeight)
  }

  /**
   * Pages (already cleaned, kept as files) → a PDF under [maxBytes], written to [dest].
   * Only one page is in memory at a time: while trying, only each page's size is added up.
   * One quality for all pages; if the lowest readable quality is still too heavy, every page a
   * little smaller, down to about 100 dpi. Returns (bytes written, quality, fits).
   */
  fun fitPdf(pages: List<File>, maxBytes: Long?, dest: File): Triple<Long, Int, Boolean> {
    var k = 1.0f
    val overhead = 600L * pages.size + 400
    val longest = pages.maxOf { longSideOf(it) }
    while (true) {
      val factor = k
      val totals = HashMap<Int, Long>()
      val budget = maxBytes?.let { it - overhead }
      val (q, ok) = bestQuality(budget) { qq ->
        totals.getOrPut(qq) { pages.sumOf { f -> encodePage(f, qq, factor).jpeg.size.toLong() } }
      }
      if (ok || longest * factor <= 1170f) {
        val size = FileOutputStream(dest).buffered().use { s -> writePdf(s, pages.size) { i -> encodePage(pages[i], q, factor) } }
        return Triple(size, q, ok && (maxBytes == null || size <= maxBytes))
      }
      // too heavy even at the lowest quality: the first step jumps near the size that fits (the
      // weight goes with the area, so the scale with its square root), then 15 % steps as before;
      // never below about 100 dpi, so the fit guarantee is unchanged
      val atLowest = totals[MIN_QUALITY]
      val jump = if (k == 1f && atLowest != null && budget != null && budget > 0 && atLowest > 0) {
        (kotlin.math.sqrt(budget.toDouble() / atLowest) * 0.95).toFloat().coerceIn(0.3f, 0.85f)
      } else {
        0.85f
      }
      // (1169: the floor is always reached exactly, never a float hair above the exit check)
      k = max(k * jump, 1169f / longest)
    }
  }

  /** A cleaned page kept on the phone (near-lossless), so pages need not stay in memory */
  fun keepPage(context: Context, b: Bitmap): File {
    val f = workFile(context, "jpg")
    FileOutputStream(f).use { it.write(jpeg(b, 97)) }
    b.recycle()
    return f
  }

  /** A new private work file (cache/work); work files older than a day are tidied away */
  fun workFile(context: Context, ext: String): File {
    val dir = File(context.cacheDir, "work").apply { mkdirs() }
    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
    return File(dir, "w_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
  }

  fun save(context: Context, bytes: ByteArray, ext: String): File {
    val f = workFile(context, ext)
    FileOutputStream(f).use { it.write(bytes) }
    return f
  }

  fun png(b: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
  }

  /** A small picture for a list (its longer side about [side] px), or null */
  fun thumbnail(file: File, side: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
  }
}
