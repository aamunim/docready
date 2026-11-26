package com.docready.aamunim.imaging.beauty

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A fold in pixels: a band across the page */
data class FoldPx(val horizontal: Boolean, val at: Int, val half: Int)

enum class MarkShape { ELLIPSE, BOX }

/**
 * Beautify's clean-up steps, each on its own, all pure Kotlin on a [Px] (changed in place).
 * [amount] 0-1 is the step's strength; [protect] boxes (photos, logos, stamps) are left as they
 * are by the colour steps. Order used by the renderer: glare → stains → whiten → folds → edges →
 * clarity (denoise, then sharpen) → ink.
 */
object BeautySteps {
  private fun inAny(boxes: List<IBox>, x: Int, y: Int): Boolean {
    for (b in boxes) if (b.contains(x, y)) return true
    return false
  }

  /** Blown-out glare (a laminated card) brought down towards the colour around it */
  fun glare(p: Px, areas: List<IBox>, amount: Float) {
    if (amount <= 0f) return
    for (a in areas) {
      // the colour just around the patch, without its own glare
      val ring = max(2, max(a.width, a.height) / 8)
      var sr = 0L
      var sg = 0L
      var sb = 0L
      var n = 0
      val x0 = max(0, a.left - ring)
      val x1 = min(p.w, a.right + ring)
      val y0 = max(0, a.top - ring)
      val y1 = min(p.h, a.bottom + ring)
      for (y in y0 until y1) for (x in x0 until x1) {
        if (a.contains(x, y)) continue
        val c = p.data[y * p.w + x]
        if (Px.lum(c) < 240) {
          sr += Px.r(c)
          sg += Px.g(c)
          sb += Px.b(c)
          n++
        }
      }
      if (n == 0) continue
      val rr = (sr / n).toInt()
      val rg = (sg / n).toInt()
      val rb = (sb / n).toInt()
      for (y in a.top until a.bottom) for (x in a.left until a.right) {
        val i = y * p.w + x
        val c = p.data[i]
        val wgt = Px.smoothstep(225f, 255f, Px.lum(c).toFloat()) * amount * 0.85f
        if (wgt <= 0f) continue
        p.data[i] = Px.rgb(
          (Px.r(c) + (rr - Px.r(c)) * wgt).roundToInt(),
          (Px.g(c) + (rg - Px.g(c)) * wgt).roundToInt(),
          (Px.b(c) + (rb - Px.b(c)) * wgt).roundToInt(),
        )
      }
    }
  }

  /**
   * Stains and blotches: where the paper's own colour drifts from the page's usual paper colour
   * (a large, low-contrast patch), it is brought back; writing on top keeps its darkness because
   * the same gain is used for the paper and for what is written on it.
   */
  fun stains(p: Px, amount: Float, protect: List<IBox>) {
    if (amount <= 0f) return
    val grid = PaperGrid.of(p)
    val gl = grid.global()
    val local = FloatArray(3)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      if (inAny(protect, x, y)) continue
      grid.at(x, y, local)
      val i = y * p.w + x
      val c = p.data[i]
      val gr = 1f + ((gl[0] / max(local[0], 1f)).coerceIn(0.75f, 1.7f) - 1f) * amount
      val gg = 1f + ((gl[1] / max(local[1], 1f)).coerceIn(0.75f, 1.7f) - 1f) * amount
      val gb = 1f + ((gl[2] / max(local[2], 1f)).coerceIn(0.75f, 1.7f) - 1f) * amount
      p.data[i] = Px.rgb((Px.r(c) * gr).roundToInt(), (Px.g(c) * gg).roundToInt(), (Px.b(c) * gb).roundToInt())
    }
  }

  /**
   * Yellow / aged paper to white: each colour channel is scaled so the paper becomes white
   * (a white balance), so black, blue and red ink keep their colours; near-white is made white.
   */
  fun whiten(p: Px, amount: Float, protect: List<IBox>) {
    if (amount <= 0f) return
    val gl = PaperGrid.of(p).global()
    val kr = (250f / max(gl[0], 1f)).coerceIn(1f, 2f)
    val kg = (250f / max(gl[1], 1f)).coerceIn(1f, 2f)
    val kb = (250f / max(gl[2], 1f)).coerceIn(1f, 2f)
    // (white paper is the point: Medium already goes nearly all the way)
    val k = min(1f, amount * 1.4f)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      if (inAny(protect, x, y)) continue
      val i = y * p.w + x
      val c = p.data[i]
      var r = min(255f, Px.r(c) * kr)
      var g = min(255f, Px.g(c) * kg)
      var b = min(255f, Px.b(c) * kb)
      val l = (r * 299 + g * 587 + b * 114) / 1000f
      val t = Px.smoothstep(232f, 250f, l)
      r += (255 - r) * t
      g += (255 - g) * t
      b += (255 - b) * t
      p.data[i] = Px.rgb(
        (Px.r(c) + (r - Px.r(c)) * k).roundToInt(),
        (Px.g(c) + (g - Px.g(c)) * k).roundToInt(),
        (Px.b(c) + (b - Px.b(c)) * k).roundToInt(),
      )
    }
  }

  /** Folds / creases: in each band, shading lighter than writing is brought to the paper's colour */
  fun folds(p: Px, lines: List<FoldPx>, amount: Float, protect: List<IBox> = emptyList()) {
    if (amount <= 0f || lines.isEmpty()) return
    val grid = PaperGrid.of(p)
    val paper = FloatArray(3)
    for (f in lines) {
      // (with the fold's soft sides)
      val from = max(0, f.at - 2 * f.half - 2)
      val to = min(if (f.horizontal) p.h else p.w, f.at + 2 * f.half + 3)
      for (a in from until to) {
        val along = if (f.horizontal) p.w else p.h
        for (b in 0 until along) {
          val x = if (f.horizontal) b else a
          val y = if (f.horizontal) a else b
          if (inAny(protect, x, y)) continue
          val i = y * p.w + x
          val c = p.data[i]
          grid.at(x, y, paper)
          val pl = (paper[0] * 299 + paper[1] * 587 + paper[2] * 114) / 1000f
          // writing crossing the fold is much darker than the paper: kept
          if (Px.lum(c) < pl - 55f) continue
          p.data[i] = Px.rgb(
            (Px.r(c) + (paper[0] - Px.r(c)) * amount).roundToInt(),
            (Px.g(c) + (paper[1] - Px.g(c)) * amount).roundToInt(),
            (Px.b(c) + (paper[2] - Px.b(c)) * amount).roundToInt(),
          )
        }
      }
    }
  }

  /** Fingers, punch holes and staples at the edges painted over with the paper's colour */
  fun edges(p: Px, marks: List<Pair<MarkShape, IBox>>) {
    if (marks.isEmpty()) return
    val gl = PaperGrid.of(p).global()
    val paper = Px.rgb(gl[0].roundToInt(), gl[1].roundToInt(), gl[2].roundToInt())
    for ((shape, b0) in marks) {
      // a little bigger than what was found (its soft edge too)
      val grow = max(2, max(b0.width, b0.height) / 5)
      val b = IBox(max(0, b0.left - grow), max(0, b0.top - grow), min(p.w, b0.right + grow), min(p.h, b0.bottom + grow))
      val cx = (b.left + b.right) / 2f
      val cy = (b.top + b.bottom) / 2f
      val rx = max(1f, b.width / 2f)
      val ry = max(1f, b.height / 2f)
      for (y in b.top until b.bottom) for (x in b.left until b.right) {
        if (shape == MarkShape.ELLIPSE) {
          val dx = (x + 0.5f - cx) / rx
          val dy = (y + 0.5f - cy) / ry
          if (dx * dx + dy * dy > 1f) continue
        }
        p.data[y * p.w + x] = paper
      }
    }
  }

  /** The 9 pixels around x (itself included) in three rows, edges repeated */
  private inline fun around(prev: IntArray, cur: IntArray, next: IntArray, x: Int, w: Int, f: (Int) -> Unit) {
    val a = if (x > 0) x - 1 else 0
    val b = if (x < w - 1) x + 1 else w - 1
    f(prev[a]); f(prev[x]); f(prev[b])
    f(cur[a]); f(cur[x]); f(cur[b])
    f(next[a]); f(next[x]); f(next[b])
  }

  /**
   * Each pixel worked out from its 3 × 3 neighbourhood as it was before this pass (three rows kept
   * aside, so no full copy of the picture is needed).
   */
  private inline fun pass3(p: Px, f: (x: Int, y: Int, prev: IntArray, cur: IntArray, next: IntArray) -> Int) {
    val w = p.w
    var prev = p.data.copyOfRange(0, w)
    var cur = p.data.copyOfRange(0, w)
    for (y in 0 until p.h) {
      val next = if (y + 1 < p.h) p.data.copyOfRange((y + 1) * w, (y + 2) * w) else cur
      for (x in 0 until w) p.data[y * w + x] = f(x, y, prev, cur, next)
      prev = cur
      cur = next
    }
  }

  /**
   * Denoise, then sharpen (in this order: sharpening noise would make it worse). Lone dark
   * specks on paper are removed, flat areas smoothed, then the edges of writing are sharpened.
   */
  fun clarity(p: Px, amount: Float, protect: List<IBox> = emptyList(), flatRange: Int = 24) {
    if (amount <= 0f) return
    val w = p.w
    // the page's own ink level: a dot as dark as the writing is a decimal point, an i-dot,
    // a Bangla dot — only fainter specks are dirt
    val inkLevel = inkLevel(p)
    // 1. specks and flat-area noise
    pass3(p) { x, y, prev, cur, next ->
      val c = cur[x]
      if (inAny(protect, x, y)) return@pass3 c
      val l = Px.lum(c)
      var sr = 0
      var sg = 0
      var sb = 0
      var mn = 255
      var mx = 0
      var lighter = 0
      around(prev, cur, next, x, w) { n ->
        sr += Px.r(n)
        sg += Px.g(n)
        sb += Px.b(n)
        val nl = Px.lum(n)
        if (nl < mn) mn = nl
        if (nl > mx) mx = nl
        if (nl > l + 50) lighter++
      }
      when {
        // a lone speck fainter than the ink: all 8 neighbours much lighter
        lighter == 8 && l > inkLevel + 40 -> Px.rgb((sr - Px.r(c)) / 8, (sg - Px.g(c)) / 8, (sb - Px.b(c)) / 8)
        // a flat area: its noise smoothed
        mx - mn < flatRange -> {
          val k = amount
          Px.rgb(
            (Px.r(c) + (sr / 9 - Px.r(c)) * k).roundToInt(),
            (Px.g(c) + (sg / 9 - Px.g(c)) * k).roundToInt(),
            (Px.b(c) + (sb / 9 - Px.b(c)) * k).roundToInt(),
          )
        }
        else -> c
      }
    }
    // 2. sharpen the edges of writing (unsharp mask, 3 × 3)
    val a = 0.8f * amount
    pass3(p) { x, y, prev, cur, next ->
      val c = cur[x]
      if (inAny(protect, x, y)) return@pass3 c
      var sr = 0
      var sg = 0
      var sb = 0
      var mn = 255
      var mx = 0
      around(prev, cur, next, x, w) { n ->
        sr += Px.r(n)
        sg += Px.g(n)
        sb += Px.b(n)
        val nl = Px.lum(n)
        if (nl < mn) mn = nl
        if (nl > mx) mx = nl
      }
      if (mx - mn <= 30) {
        c
      } else {
        Px.rgb(
          (Px.r(c) + (Px.r(c) - sr / 9f) * a).roundToInt(),
          (Px.g(c) + (Px.g(c) - sg / 9f) * a).roundToInt(),
          (Px.b(c) + (Px.b(c) - sb / 9f) * a).roundToInt(),
        )
      }
    }
  }

  /**
   * Faded ink made darker: only what is darker than the paper around it, more the darker it is
   * already (a soft start, so the thin, light edge of a stroke is darkened a little and the stroke
   * never breaks); never lightened, the paper never touched; ink keeps its colour.
   */
  fun ink(p: Px, amount: Float, protect: List<IBox> = emptyList()) {
    if (amount <= 0f) return
    val grid = PaperGrid.of(p)
    val bg = FloatArray(3)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      if (inAny(protect, x, y)) continue // (a portrait is never darkened)
      val i = y * p.w + x
      val c = p.data[i]
      grid.at(x, y, bg)
      val bl = (bg[0] * 299 + bg[1] * 587 + bg[2] * 114) / 1000f
      val d = bl - Px.lum(c)
      if (d <= 10f) continue
      val gain = 1f + amount * 1.3f * Px.smoothstep(10f, 70f, d)
      fun ch(v: Int, b: Float): Int = min(v, (b - (b - v) * gain).roundToInt())
      p.data[i] = Px.rgb(ch(Px.r(c), bg[0]), ch(Px.g(c), bg[1]), ch(Px.b(c), bg[2]))
    }
  }

  /** The picture turned by [degrees] around its middle, the same size, white in the corners */
  fun rotate(p: Px, degrees: Float): Px {
    if (abs(degrees) < 0.01f) return p
    val rad = Math.toRadians(degrees.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    val cx = p.w / 2f
    val cy = p.h / 2f
    val out = IntArray(p.w * p.h)
    val white = Px.rgb(255, 255, 255)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      // where this pixel comes from (the turn undone)
      val dx = x + 0.5f - cx
      val dy = y + 0.5f - cy
      val sx = (dx * cos + dy * sin + cx).toInt()
      val sy = (-dx * sin + dy * cos + cy).toInt()
      out[y * p.w + x] = if (sx in 0 until p.w && sy in 0 until p.h) p.data[sy * p.w + sx] else white
    }
    return Px(p.w, p.h, out)
  }

  fun rotate180(p: Px): Px {
    val out = IntArray(p.w * p.h)
    val n = p.w * p.h
    for (i in 0 until n) out[n - 1 - i] = p.data[i]
    return Px(p.w, p.h, out)
  }

  fun crop(p: Px, b: IBox): Px {
    val w = max(1, b.width)
    val h = max(1, b.height)
    val out = IntArray(w * h)
    for (y in 0 until h) System.arraycopy(p.data, (b.top + y) * p.w + b.left, out, y * w, w)
    return Px(w, h, out)
  }

  /** The usual darkness of the writing: the middle of the ink pixels (Otsu's dark class), on a sample */
  fun inkLevel(p: Px): Int {
    val step = max(1, (p.w * p.h) / 200_000)
    val hist = IntArray(256)
    var i = 0
    while (i < p.w * p.h) {
      hist[Px.lum(p.data[i])]++
      i += step
    }
    // Otsu on the sample
    var total = 0.0
    var sum = 0.0
    for (t in 0 until 256) {
      total += hist[t]
      sum += t * hist[t].toDouble()
    }
    var sumB = 0.0
    var wB = 0.0
    var best = 0.0
    var th = 128
    for (t in 0 until 256) {
      wB += hist[t]
      if (wB == 0.0) continue
      val wF = total - wB
      if (wF == 0.0) break
      sumB += t * hist[t]
      val d = sumB / wB - (sum - sumB) / wF
      val between = wB * wF * d * d
      if (between > best) {
        best = between
        th = t
      }
    }
    var n = 0
    for (t in 0..th) n += hist[t]
    if (n == 0) return 0
    var acc = 0
    for (t in 0..th) {
      acc += hist[t]
      if (acc * 2 >= n) return t
    }
    return th
  }
}
