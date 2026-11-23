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
    // a protected area keeps its own light: only the paper's tint is balanced (a photo's paper
    // estimate would be the photo itself)
    val paper = bg.paper
    for (y in 0 until p.h) for (x in 0 until p.w) {
      val i = y * p.w + x
      val c = p.data[i]
      bg.at(x, y, local[i].toInt() and 255, c3)
      val nr = Px.r(c) / max(1f, c3[0])
      val ng = Px.g(c) / max(1f, c3[1])
      val nb = Px.b(c) / max(1f, c3[2])
      val wp = protectWeight(boxes, x, y, feather)
      // what a protected area gets: the colours with the paper's tint balanced
      val er = Px.r(c) / max(1f, paper[0]) * 255f
      val eg = Px.g(c) / max(1f, paper[1]) * 255f
      val eb = Px.b(c) / max(1f, paper[2]) * 255f
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

  /** 1 inside a protected box, fading to 0 over [feather] pixels outside it */
  private fun protectWeight(boxes: List<IBox>, x: Int, y: Int, feather: Int): Float {
    var w = 0f
    for (b in boxes) {
      val dx = max(0, max(b.left - x, x - (b.right - 1)))
      val dy = max(0, max(b.top - y, y - (b.bottom - 1)))
      val d = max(dx, dy)
      if (d >= feather) continue
      val v = 1f - Px.smoothstep(0f, feather.toFloat(), d.toFloat())
      if (v > w) w = v
      if (w >= 1f) break
    }
    return w
  }

  /**
   * Greyscale photos: on the page as it is, areas where most of it is mid-tone against the page's
   * paper (neither paper nor ink) and textured (a photo's detail; a shadow or a fold is smooth, so
   * it is still evened away). Their highlights and shades are kept.
   */
  fun photoAreas(p: Px, bg: Background): List<IBox> {
    val cell = max(8, max(p.w, p.h) / 32)
    val gw = (p.w + cell - 1) / cell
    val gh = (p.h + cell - 1) / cell
    val step = max(1, cell / 10)
    val pl = max(1f, (bg.paper[0] * 299 + bg.paper[1] * 587 + bg.paper[2] * 114) / 1000f)
    val mask = BooleanArray(gw * gh)
    for (gy in 0 until gh) for (gx in 0 until gw) {
      var mid = 0
      var n = 0
      var grad = 0L
      var pairs = 0
      fun isMid(l: Int) = l / pl in 0.2f..0.85f
      var y = gy * cell
      while (y < kotlin.math.min(p.h - 1, (gy + 1) * cell)) {
        var x = gx * cell
        while (x < kotlin.math.min(p.w - 1, (gx + 1) * cell)) {
          val i = y * p.w + x
          val l = Px.lum(p.data[i])
          n++
          if (isMid(l)) {
            mid++
            // texture between mid-tones only (a letter's edge against shadowed paper is not texture)
            val r = Px.lum(p.data[i + 1])
            val d = Px.lum(p.data[i + p.w])
            if (isMid(r)) {
              grad += kotlin.math.abs(r - l)
              pairs++
            }
            if (isMid(d)) {
              grad += kotlin.math.abs(d - l)
              pairs++
            }
          }
          x += step
        }
        y += step
      }
      mask[gy * gw + gx] = n > 0 && mid > n * 0.5f && pairs > n * 0.6f && grad >= pairs * 5L
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
