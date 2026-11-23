package com.docready.aamunim.imaging.beauty

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The "scanner app" looks, pure Kotlin on a [Px] (changed in place):
 *  · [magic]: crisp dark text on clean white paper, colours (stamps, photos) natural
 *  · [magicBw]: smooth black & white with soft (anti-aliased) edges, never jagged 1-bit
 *  · [lighten]: brighter, even paper with natural colours
 *
 * All three first measure the paper's own light all over the page ([PaperGrid]: the lightest
 * part of each large cell, spread over its neighbours, smoothed — a "closing" then a blur, so
 * writing and even bold headings are left out of it and no halo forms around them), then divide
 * by it: shadows and uneven light go, and the paper's tint is balanced away.
 */
object MagicLooks {
  /**
   * Near-colourless light shading reaches paper white from here to here, judged by the pixel's
   * weakest channel against the paper: a crease or a shadow's remains (≈ 0.85-0.95 in every
   * channel) goes white; pencil, light ruled lines (≤ 0.8) stay; a pale colour (yellow
   * highlighter, a pale red stamp) has one weak channel, so it keeps its hue.
   */
  private const val KNEE_FROM = 0.80f
  private const val KNEE_TO = 0.92f

  /** Ink deepened: the brightness (1 = paper) through a gentle power curve */
  fun inkCurve(q: Float): Float = (q / KNEE_TO).coerceIn(0f, 1f).pow(1.6f)

  /**
   * Magic: the page divided by its paper ([Background]: shadows, folds, tint gone); ink deepened
   * with its colour kept (each channel scaled alike, then a little richer); only near-colourless
   * light shading taken to white; a light denoise on the paper and gentle sharpening of the text
   * edges. [protect] (photos, logos, stamps) and smooth mid-tone areas (greyscale photos) only get
   * the even light and the tint balance, feathered at their edges.
   */
  fun magic(p: Px, protect: List<IBox> = emptyList()) {
    val bg = Background.of(p)
    val local = localPaper(p)
    val boxes = protect + photoAreas(p, bg)
    val feather = max(8, max(p.w, p.h) / 60)
    val c3 = FloatArray(3)
    // a protected area keeps its own tones: the paper's tint balanced, and lifted by how much
    // darker the paper just around it is than the page's paper (a photo under a shadow does not sit
    // on a grey island); a photo's own paper estimate would be the photo itself
    val paper = bg.paper
    val gains = boxGains(p, bg, local, boxes, feather)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      val i = y * p.w + x
      val c = p.data[i]
      bg.at(x, y, local[i].toInt() and 255, c3)
      val nr = Px.r(c) / max(1f, c3[0])
      val ng = Px.g(c) / max(1f, c3[1])
      val nb = Px.b(c) / max(1f, c3[2])
      val (wp, box) = protectWeight(boxes, x, y, feather)
      // what a protected area gets: the colours with the paper's tint balanced and its light evened
      val gain = if (box >= 0) gains[box] else 1f
      val er = Px.r(c) / max(1f, paper[0]) * 255f * gain
      val eg = Px.g(c) / max(1f, paper[1]) * 255f * gain
      val eb = Px.b(c) / max(1f, paper[2]) * 255f * gain
      var mr = er
      var mg = eg
      var mb = eb
      if (wp < 1f) {
        val q = (nr * 299 + ng * 587 + nb * 114) / 1000f
        // only soft shading goes white: a crisp mark (a printed line, a letter's edge) is much
        // darker than the paper right beside it; a crease or a shadow's remains is not
        val loc = local[i].toInt() and 255
        val crisp = (loc - Px.lum(c)).toFloat() / max(1, loc) > 0.05f
        val white = if (crisp) 0f else Px.smoothstep(KNEE_FROM, KNEE_TO, kotlin.math.min(nr, kotlin.math.min(ng, nb)))
        if (white >= 0.99f && q >= KNEE_TO) {
          mr = 255f
          mg = 255f
          mb = 255f
        } else {
          val ink = inkCurve(q)
          val q2 = ink + (1f - ink) * white
          val k = q2 / max(0.001f, q)
          val l = q2 * 255f
          // the colour kept, a little richer (blue / red ink, stamps, highlighter)
          mr = l + (nr * k * 255f - l) * 1.15f
          mg = l + (ng * k * 255f - l) * 1.15f
          mb = l + (nb * k * 255f - l) * 1.15f
        }
      }
      p.data[i] = Px.rgb(
        (mr + (er - mr) * wp).roundToInt(),
        (mg + (eg - mg) * wp).roundToInt(),
        (mb + (eb - mb) * wp).roundToInt(),
      )
    }
    // (a faint printed line is not "flat": only very even paper is smoothed)
    BeautySteps.clarity(p, 0.45f, boxes, flatRange = 10)
  }

  /** 1 inside a protected box, fading to 0 over [feather] pixels outside it; and which box (-1: none) */
  private fun protectWeight(boxes: List<IBox>, x: Int, y: Int, feather: Int): Pair<Float, Int> {
    var w = 0f
    var which = -1
    for ((k, b) in boxes.withIndex()) {
      val dx = max(0, max(b.left - x, x - (b.right - 1)))
      val dy = max(0, max(b.top - y, y - (b.bottom - 1)))
      val d = max(dx, dy)
      if (d >= feather) continue
      val v = 1f - Px.smoothstep(0f, feather.toFloat(), d.toFloat())
      if (v > w) {
        w = v
        which = k
      }
      if (w >= 1f) break
    }
    return w to which
  }

  /**
   * Each protected box's light gain: the page's paper over the paper just outside the box (sampled
   * on a ring a feather away), clamped to 1-2.5 (only ever lifted, never by much).
   */
  private fun boxGains(p: Px, bg: Background, local: ByteArray, boxes: List<IBox>, feather: Int): FloatArray {
    val pl = max(1f, (bg.paper[0] * 299 + bg.paper[1] * 587 + bg.paper[2] * 114) / 1000f)
    val c3 = FloatArray(3)
    return FloatArray(boxes.size) { k ->
      val b = boxes[k]
      var sum = 0f
      var n = 0
      fun take(x: Int, y: Int) {
        if (x !in 0 until p.w || y !in 0 until p.h) return
        val i = y * p.w + x
        bg.at(x, y, local[i].toInt() and 255, c3)
        sum += (c3[0] * 299 + c3[1] * 587 + c3[2] * 114) / 1000f
        n++
      }
      val steps = 24
      for (s in 0..steps) {
        val x = b.left + (b.width * s) / steps
        val y = b.top + (b.height * s) / steps
        take(x, b.top - feather)
        take(x, b.bottom - 1 + feather)
        take(b.left - feather, y)
        take(b.right - 1 + feather, y)
      }
      if (n == 0) 1f else (pl / max(1f, sum / n)).coerceIn(1f, 2.5f)
    }
  }

  /**
   * Greyscale photos: areas whose cells have a wide tonal spread (the brightest tenth far above the
   * darkest, p90 − p10 > 35) with much of it in between (a continuous range of tones). Text is wide
   * but two-toned (paper and ink, little between); shadowed or grainy paper is narrow — both are
   * still cleaned.
   */
  fun photoAreas(p: Px, @Suppress("UNUSED_PARAMETER") bg: Background): List<IBox> {
    val cell = max(8, max(p.w, p.h) / 32)
    val gw = (p.w + cell - 1) / cell
    val gh = (p.h + cell - 1) / cell
    val step = max(1, cell / 12)
    val mask = BooleanArray(gw * gh)
    val hist = IntArray(256)
    for (gy in 0 until gh) for (gx in 0 until gw) {
      hist.fill(0)
      var n = 0
      var y = gy * cell
      while (y < kotlin.math.min(p.h, (gy + 1) * cell)) {
        var x = gx * cell
        while (x < kotlin.math.min(p.w, (gx + 1) * cell)) {
          hist[Px.lum(p.data[y * p.w + x])]++
          n++
          x += step
        }
        y += step
      }
      if (n < 16) continue
      fun level(part: Float): Int {
        var acc = 0
        for (v in 0 until 256) {
          acc += hist[v]
          if (acc >= n * part) return v
        }
        return 255
      }
      val p10 = level(0.1f)
      val p90 = level(0.9f)
      val spread = p90 - p10
      if (spread <= 35) continue
      val lo = p10 + spread * 0.2f
      val hi = p90 - spread * 0.2f
      var between = 0
      for (v in 0 until 256) if (v > lo && v < hi) between += hist[v]
      mask[gy * gw + gx] = between > n * 0.35f
    }
    return blobs(mask, gw, gh)
      .filter { it.box.width >= 2 && it.box.height >= 2 }
      .map { IBox(it.box.left * cell, it.box.top * cell, kotlin.math.min(p.w, it.box.right * cell), kotlin.math.min(p.h, it.box.bottom * cell)) }
  }

  /** Smooth black & white: ink → black, paper → white, a soft ramp between (anti-aliased edges) */
  fun magicBw(p: Px) {
    val bg = Background.of(p)
    val local = localPaper(p)
    val c3 = FloatArray(3)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      val i = y * p.w + x
      val own = Px.lum(p.data[i])
      bg.at(x, y, local[i].toInt() and 255, c3)
      val bl = max(1f, (c3[0] * 299 + c3[1] * 587 + c3[2] * 114) / 1000f)
      val v = (Px.smoothstep(0.5f, 0.8f, own / bl) * 255f).roundToInt()
      p.data[i] = Px.rgb(v, v, v)
    }
  }

  /** Brighter, even paper; colours natural (a mild curve, no clipping of ink) */
  fun lighten(p: Px) {
    val bg = Background.of(p)
    val local = localPaper(p)
    val c3 = FloatArray(3)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      val i = y * p.w + x
      val c = p.data[i]
      bg.at(x, y, local[i].toInt() and 255, c3)
      fun ch(v: Int, b: Float): Int {
        val t = (v / max(1f, b)).coerceIn(0f, 1.2f)
        val lifted = t.pow(0.85f)
        val w = Px.smoothstep(0.85f, 0.97f, t)
        return ((lifted + (1f - lifted) * w) * 255f).roundToInt()
      }
      p.data[i] = Px.rgb(ch(Px.r(c), c3[0]), ch(Px.g(c), c3[1]), ch(Px.b(c), c3[2]))
    }
  }

  /**
   * Shadows out, the paper's tint kept (the Clean tab's "Remove shadows"): each pixel lifted by the
   * paper's brightness only.
   */
  fun flatten(p: Px) {
    val bg = Background.of(p)
    val local = localPaper(p)
    val c3 = FloatArray(3)
    val target = 245f
    for (y in 0 until p.h) for (x in 0 until p.w) {
      val i = y * p.w + x
      val c = p.data[i]
      bg.at(x, y, local[i].toInt() and 255, c3)
      val bl = max(1f, (c3[0] * 299 + c3[1] * 587 + c3[2] * 114) / 1000f)
      val k = (target / bl).coerceIn(0.8f, 3f)
      p.data[i] = Px.rgb((Px.r(c) * k).roundToInt(), (Px.g(c) * k).roundToInt(), (Px.b(c) * k).roundToInt())
    }
  }

  private val WHITE = Px.rgb(255, 255, 255)

  /**
   * The paper level right around each pixel (the lightest of its 3 × 3), measured before any
   * pixel is changed: a grey line or a letter on lit paper is then judged by the lit paper beside
   * it, never mistaken for shadowed paper. One byte a pixel.
   */
  private fun localPaper(p: Px): ByteArray {
    val w = p.w
    val h = p.h
    val rowMax = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      var m = 0
      for (xx in max(0, x - 1)..kotlin.math.min(w - 1, x + 1)) {
        val v = Px.lum(p.data[y * w + xx])
        if (v > m) m = v
      }
      rowMax[y * w + x] = m.toByte()
    }
    val out = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      var m = 0
      for (yy in max(0, y - 1)..kotlin.math.min(h - 1, y + 1)) {
        val v = rowMax[yy * w + x].toInt() and 255
        if (v > m) m = v
      }
      out[y * w + x] = m.toByte()
    }
    return out
  }
}
