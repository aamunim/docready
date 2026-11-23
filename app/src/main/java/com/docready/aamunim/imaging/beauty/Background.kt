package com.docready.aamunim.imaging.beauty

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The paper's own colour at every point of a page (what the page would look like with nothing
 * written on it), for dividing shadows, folds and tints away:
 *
 *  1. a small copy (about 240 px): each block's paper colour (the light part of the block);
 *  2. a closing — the lightest within a radius, then the darkest within it again: writing and
 *     printed lines (thin and dark) vanish, while a shadow's hard edge or a fold's brightness step
 *     stays exactly where it is (a plain blur or max filter would smear or move it);
 *  3. an edge-keeping smoothing (bilateral: only similar neighbours are averaged);
 *  4. read back at full size with an edge-aware upsample: a paper pixel takes the nearby sample
 *     that matches it (the lit or the shadowed side), so the shadow's border leaves no halo; ink
 *     takes the plain average.
 *
 * Soft shading lines of any angle (creases) and "tent" steps of a folded page are broad enough to
 * be part of this background, so dividing by it removes them; thin dark lines (ruling, text) are not.
 */
class Background private constructor(
  private val sw: Int,
  private val sh: Int,
  private val block: Float,
  private val r: FloatArray,
  private val g: FloatArray,
  private val b: FloatArray,
  private val l: FloatArray,
) {
  /** The page's usual paper colour (the middle of its lighter half) */
  val paper: FloatArray by lazy {
    val idx = l.indices.sortedBy { l[it] }
    val from = idx.size / 2
    val to = max(from + 1, idx.size * 9 / 10)
    var sr = 0f
    var sg = 0f
    var sb = 0f
    for (k in from until to) {
      sr += r[idx[k]]
      sg += g[idx[k]]
      sb += b[idx[k]]
    }
    val c = (to - from).toFloat()
    floatArrayOf(sr / c, sg / c, sb / c)
  }

  /**
   * The paper colour behind pixel x, y whose own luminance is [own], into [out] (r, g, b). A
   * large dark area (a filled box, a dark photo) is not paper: the page's usual paper is used
   * there, so it is never lifted to white.
   */
  fun at(x: Int, y: Int, own: Int, out: FloatArray) {
    sample(x, y, own, out)
    val bl = (out[0] * 299 + out[1] * 587 + out[2] * 114) / 1000f
    val pl = (paper[0] * 299 + paper[1] * 587 + paper[2] * 114) / 1000f
    if (bl < pl * 0.33f) {
      out[0] = paper[0]
      out[1] = paper[1]
      out[2] = paper[2]
    }
  }

  private fun sample(x: Int, y: Int, own: Int, out: FloatArray) {
    val fx = min((sw - 1).toFloat(), max(0f, (x + 0.5f) / block - 0.5f))
    val fy = min((sh - 1).toFloat(), max(0f, (y + 0.5f) / block - 0.5f))
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = min(sw - 1, x0 + 1)
    val y1 = min(sh - 1, y0 + 1)
    val tx = fx - x0
    val ty = fy - y0
    val i00 = y0 * sw + x0
    val i10 = y0 * sw + x1
    val i01 = y1 * sw + x0
    val i11 = y1 * sw + x1
    var w00 = (1 - tx) * (1 - ty)
    var w10 = tx * (1 - ty)
    var w01 = (1 - tx) * ty
    var w11 = tx * ty
    // the darkest paper sample around (4 × 4): is this pixel paper on either side of an edge?
    var lo = Float.MAX_VALUE
    for (yy in max(0, y0 - 1)..min(sh - 1, y0 + 2)) for (xx in max(0, x0 - 1)..min(sw - 1, x0 + 2)) lo = min(lo, l[yy * sw + xx])
    // paper (as light as the darker side or more): the samples around (4 × 4, a tent of two
    // blocks) weighted by how well each matches it, so a pixel just across a shadow's edge still
    // finds its own side even when the edge falls inside a block
    if (own >= lo * 0.75f) {
      var sr = 0f
      var sg = 0f
      var sb = 0f
      var ws = 0f
      for (yy in max(0, y0 - 1)..min(sh - 1, y0 + 2)) {
        val wy = max(0f, 1f - kotlin.math.abs(yy - fy) / 2f)
        if (wy <= 0f) continue
        for (xx in max(0, x0 - 1)..min(sw - 1, x0 + 2)) {
          val wx = max(0f, 1f - kotlin.math.abs(xx - fx) / 2f)
          if (wx <= 0f) continue
          val j = yy * sw + xx
          val w = wx * wy * RANGE[min(255, kotlin.math.abs(own - l[j].toInt()))]
          sr += r[j] * w
          sg += g[j] * w
          sb += b[j] * w
          ws += w
        }
      }
      if (ws > 1e-4f) {
        out[0] = sr / ws
        out[1] = sg / ws
        out[2] = sb / ws
        return
      }
    }
    out[0] = r[i00] * w00 + r[i10] * w10 + r[i01] * w01 + r[i11] * w11
    out[1] = g[i00] * w00 + g[i10] * w10 + g[i01] * w01 + g[i11] * w11
    out[2] = b[i00] * w00 + b[i10] * w10 + b[i01] * w01 + b[i11] * w11
  }

  companion object {
    /** How well a sample matches a pixel, by their brightness difference (a table: no exp per pixel) */
    private val RANGE = FloatArray(256) { d -> exp(-(d / 16f) * (d / 16f)) }

    /** The small copy's longer side */
    private const val SMALL = 240

    fun of(p: Px): Background {
      val block = max(1f, ceil(max(p.w, p.h).toFloat() / SMALL))
      val bs = block.toInt()
      val sw = (p.w + bs - 1) / bs
      val sh = (p.h + bs - 1) / bs
      val n = sw * sh
      var r = FloatArray(n)
      var g = FloatArray(n)
      var b = FloatArray(n)
      var l = FloatArray(n)
      // 1. each block's paper: the mean of its light pixels (within 20 of its lightest)
      val step = max(1, bs / 6)
      for (by in 0 until sh) for (bx in 0 until sw) {
        val x2 = min(p.w, (bx + 1) * bs)
        val y2 = min(p.h, (by + 1) * bs)
        var top = 0
        var y = by * bs
        while (y < y2) {
          var x = bx * bs
          while (x < x2) {
            val v = Px.lum(p.data[y * p.w + x])
            if (v > top) top = v
            x += step
          }
          y += step
        }
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var k = 0
        y = by * bs
        while (y < y2) {
          var x = bx * bs
          while (x < x2) {
            val c = p.data[y * p.w + x]
            if (Px.lum(c) >= top - 20) {
              sr += Px.r(c)
              sg += Px.g(c)
              sb += Px.b(c)
              k++
            }
            x += step
          }
          y += step
        }
        val i = by * sw + bx
        r[i] = sr / max(1, k)
        g[i] = sg / max(1, k)
        b[i] = sb / max(1, k)
        l[i] = (r[i] * 299 + g[i] * 587 + b[i] * 114) / 1000f
      }
      // 2. closing (lightest, then darkest, within about 1.3 % of the page)
      val rad = max(2, (SMALL * 0.013f + 0.5f).toInt())
      for (pass in 0 until 2) {
        val light = pass == 0
        for (horizontal in listOf(true, false)) {
          val nr = FloatArray(n)
          val ng = FloatArray(n)
          val nb = FloatArray(n)
          val nl = FloatArray(n)
          for (yy in 0 until sh) for (xx in 0 until sw) {
            var best = yy * sw + xx
            for (d in -rad..rad) {
              val x = if (horizontal) xx + d else xx
              val y = if (horizontal) yy else yy + d
              if (x !in 0 until sw || y !in 0 until sh) continue
              val j = y * sw + x
              if (if (light) l[j] > l[best] else l[j] < l[best]) best = j
            }
            val i = yy * sw + xx
            nr[i] = r[best]
            ng[i] = g[best]
            nb[i] = b[best]
            nl[i] = l[best]
          }
          r = nr
          g = ng
          b = nb
          l = nl
        }
      }
      // 3. edge-keeping smoothing: neighbours of similar brightness only
      repeat(2) {
        val nr = FloatArray(n)
        val ng = FloatArray(n)
        val nb = FloatArray(n)
        val nl = FloatArray(n)
        for (yy in 0 until sh) for (xx in 0 until sw) {
          val i = yy * sw + xx
          var sr = 0f
          var sg = 0f
          var sb = 0f
          var ws = 0f
          for (dy in -2..2) for (dx in -2..2) {
            val x = xx + dx
            val y = yy + dy
            if (x !in 0 until sw || y !in 0 until sh) continue
            val j = y * sw + x
            val d = (l[j] - l[i]) / 10f
            val w = exp(-d * d)
            sr += r[j] * w
            sg += g[j] * w
            sb += b[j] * w
            ws += w
          }
          nr[i] = sr / ws
          ng[i] = sg / ws
          nb[i] = sb / ws
          nl[i] = (nr[i] * 299 + ng[i] * 587 + nb[i] * 114) / 1000f
        }
        r = nr
        g = ng
        b = nb
        l = nl
      }
      return Background(sw, sh, block, r, g, b, l)
    }
  }
}
