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
  /** Paper reaches pure white from here (a soft knee, so there is no hard clipping edge) */
  private const val KNEE_FROM = 0.70f
  private const val KNEE_TO = 0.86f

  /** The brightness (1 = paper) through the Magic curve: ink deepened, faint shading and paper to white */
  fun magicCurve(t: Float): Float {
    val x = t.coerceIn(0f, 1.2f)
    val ink = (x / KNEE_TO).coerceAtMost(1f).pow(1.6f)
    val w = Px.smoothstep(KNEE_FROM, KNEE_TO, x)
    return ink + (1f - ink) * w
  }

  /**
   * Magic: the page divided by its paper ([Background]: shadows, folds, tint gone), the brightness
   * through [magicCurve] with the colour kept (each channel scaled alike, then a little more
   * saturated), then a light denoise on the paper and gentle sharpening of the text edges.
   * [protect] (photos, logos, stamps) stays as it was.
   */
  fun magic(p: Px, protect: List<IBox> = emptyList()) {
    val bg = Background.of(p)
    val local = localPaper(p)
    val c3 = FloatArray(3)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      if (protect.any { it.contains(x, y) }) continue
      val i = y * p.w + x
      val c = p.data[i]
      bg.at(x, y, local[i].toInt() and 255, c3)
      val nr = Px.r(c) / max(1f, c3[0])
      val ng = Px.g(c) / max(1f, c3[1])
      val nb = Px.b(c) / max(1f, c3[2])
      val q = (nr * 299 + ng * 587 + nb * 114) / 1000f
      val q2 = magicCurve(q)
      if (q2 >= 0.995f) {
        p.data[i] = WHITE
        continue
      }
      val k = q2 / max(0.001f, q)
      // the colour kept, a little richer (blue / red ink, stamps)
      val l = q2 * 255f
      fun ch(n: Float): Int {
        val v = n * k * 255f
        return (l + (v - l) * 1.15f).roundToInt()
      }
      p.data[i] = Px.rgb(ch(nr), ch(ng), ch(nb))
    }
    BeautySteps.clarity(p, 0.45f, protect)
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
