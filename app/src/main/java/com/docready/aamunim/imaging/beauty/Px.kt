package com.docready.aamunim.imaging.beauty

import kotlin.math.max
import kotlin.math.min

/**
 * A picture as plain ARGB ints (no Android types), so every Beautify step is pure Kotlin that
 * can be tested on the JVM. [data] is changed in place by the steps.
 */
class Px(val w: Int, val h: Int, val data: IntArray) {
  init {
    require(w > 0 && h > 0 && data.size >= w * h) { "Bad picture size" }
  }

  fun copy(): Px = Px(w, h, data.copyOf())

  companion object {
    fun r(c: Int) = (c shr 16) and 255
    fun g(c: Int) = (c shr 8) and 255
    fun b(c: Int) = c and 255

    fun rgb(r: Int, g: Int, b: Int): Int =
      (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Luminance 0-255 */
    fun lum(c: Int) = (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000

    /** Colourfulness 0-1 (max - min of the channels, over max) */
    fun sat(c: Int): Float {
      val mx = max(r(c), max(g(c), b(c)))
      val mn = min(r(c), min(g(c), b(c)))
      return if (mx == 0) 0f else (mx - mn).toFloat() / mx
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
      val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
      return t * t * (3 - 2 * t)
    }
  }
}

/** A box in pixels (inclusive left / top, exclusive right / bottom) */
data class IBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top
  fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}

/** A box as parts (0-1) of a picture: the stored form (any picture size) */
data class FBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  fun toPx(w: Int, h: Int) = IBox(
    (left * w).toInt().coerceIn(0, w),
    (top * h).toInt().coerceIn(0, h),
    (right * w + 0.999f).toInt().coerceIn(0, w),
    (bottom * h + 0.999f).toInt().coerceIn(0, h),
  )
}

/**
 * The paper's own colour, cell by cell: in each cell the colour of its lightest part (the writing
 * left out), then cells full of writing take their neighbours' paper, then smoothed. Read at
 * any point with [at] (bilinear).
 */
class PaperGrid(val gw: Int, val gh: Int, val cell: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
  fun lumAt(i: Int) = (r[i] * 299 + g[i] * 587 + b[i] * 114) / 1000f

  /** The paper colour at pixel x, y into [out] (r, g, b) */
  fun at(x: Int, y: Int, out: FloatArray) {
    val fx = min((gw - 1).toFloat(), max(0f, x.toFloat() / cell - 0.5f))
    val fy = min((gh - 1).toFloat(), max(0f, y.toFloat() / cell - 0.5f))
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = min(gw - 1, x0 + 1)
    val y1 = min(gh - 1, y0 + 1)
    val tx = fx - x0
    val ty = fy - y0
    fun mix(a: FloatArray): Float =
      (a[y0 * gw + x0] * (1 - tx) + a[y0 * gw + x1] * tx) * (1 - ty) + (a[y1 * gw + x0] * (1 - tx) + a[y1 * gw + x1] * tx) * ty
    out[0] = mix(r)
    out[1] = mix(g)
    out[2] = mix(b)
  }

  /** The page's usual paper colour: the middle of the lighter cells (stains and shadows left out) */
  fun global(): FloatArray {
    val idx = (0 until gw * gh).sortedBy { lumAt(it) }
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
    val n = (to - from).toFloat()
    return floatArrayOf(sr / n, sg / n, sb / n)
  }

  companion object {
    fun of(p: Px, cells: Int = 40): PaperGrid {
      val cell = max(8, max(p.w, p.h) / cells)
      val gw = (p.w + cell - 1) / cell
      val gh = (p.h + cell - 1) / cell
      val r = FloatArray(gw * gh)
      val g = FloatArray(gw * gh)
      val b = FloatArray(gw * gh)
      val step = max(1, cell / 12)
      for (gy in 0 until gh) for (gx in 0 until gw) {
        val x2 = min(p.w, (gx + 1) * cell)
        val y2 = min(p.h, (gy + 1) * cell)
        var top = 0
        var y = gy * cell
        while (y < y2) {
          var x = gx * cell
          while (x < x2) {
            val l = Px.lum(p.data[y * p.w + x])
            if (l > top) top = l
            x += step
          }
          y += step
        }
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        y = gy * cell
        while (y < y2) {
          var x = gx * cell
          while (x < x2) {
            val c = p.data[y * p.w + x]
            if (Px.lum(c) >= top - 14) {
              sr += Px.r(c)
              sg += Px.g(c)
              sb += Px.b(c)
              n++
            }
            x += step
          }
          y += step
        }
        val i = gy * gw + gx
        r[i] = sr.toFloat() / max(1, n)
        g[i] = sg.toFloat() / max(1, n)
        b[i] = sb.toFloat() / max(1, n)
      }
      // cells full of writing: the lightest neighbour's paper; then two smoothing passes
      val grid = PaperGrid(gw, gh, cell, r, g, b)
      grid.lightestNeighbour()
      repeat(2) { grid.smooth() }
      return grid
    }
  }

  private fun lightestNeighbour() {
    val nr = r.copyOf()
    val ng = g.copyOf()
    val nb = b.copyOf()
    for (y in 0 until gh) for (x in 0 until gw) {
      var best = y * gw + x
      for (dy in -1..1) for (dx in -1..1) {
        val yy = y + dy
        val xx = x + dx
        if (yy in 0 until gh && xx in 0 until gw && lumAt(yy * gw + xx) > lumAt(best)) best = yy * gw + xx
      }
      nr[y * gw + x] = r[best]
      ng[y * gw + x] = g[best]
      nb[y * gw + x] = b[best]
    }
    nr.copyInto(r)
    ng.copyInto(g)
    nb.copyInto(b)
  }

  private fun smooth() {
    for (a in listOf(r, g, b)) {
      val o = a.copyOf()
      for (y in 0 until gh) for (x in 0 until gw) {
        var s = 0f
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
          val yy = y + dy
          val xx = x + dx
          if (yy in 0 until gh && xx in 0 until gw) {
            s += o[yy * gw + xx]
            n++
          }
        }
        a[y * gw + x] = s / n
      }
    }
  }
}

/** Connected parts of a mask (4-neighbours), with their box and size */
class Blob(val box: IBox, val count: Int, val touchesBorder: Boolean)

fun blobs(mask: BooleanArray, w: Int, h: Int, minCount: Int = 1): List<Blob> {
  val seen = BooleanArray(w * h)
  val stack = IntArray(w * h)
  val out = ArrayList<Blob>()
  for (start in 0 until w * h) {
    if (!mask[start] || seen[start]) continue
    var top = 0
    stack[top++] = start
    seen[start] = true
    var n = 0
    var x0 = w
    var y0 = h
    var x1 = -1
    var y1 = -1
    while (top > 0) {
      val i = stack[--top]
      n++
      val x = i % w
      val y = i / w
      if (x < x0) x0 = x
      if (y < y0) y0 = y
      if (x > x1) x1 = x
      if (y > y1) y1 = y
      if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[top++] = i - 1 }
      if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[top++] = i + 1 }
      if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[top++] = i - w }
      if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[top++] = i + w }
    }
    if (n >= minCount) out.add(Blob(IBox(x0, y0, x1 + 1, y1 + 1), n, x0 == 0 || y0 == 0 || x1 == w - 1 || y1 == h - 1))
  }
  return out
}
