package com.docready.aamunim.imaging.beauty

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fingers holding the paper, removed (as scanner apps do). Found on a copy of the page at most
 * [SIDE] px: skin-coloured parts (YCbCr) clearly darker or more colourful than the paper around
 * them, touching the page's edge, inside a band of [BAND] of the side from that edge, neither
 * tiny nor huge, and not over a photo / face / protected area ([avoid]). Grown a little for the
 * finger's shadow and the nail's edge, then feathered. Filled with the paper's own colour there,
 * estimated from the paper around (the finger itself left out), so no grey patch: whatever the
 * finger covered (text too) is lost; the fill is paper.
 *
 * The mask is worked out on the small copy and scaled to the page, so the full page and the
 * preview get the same result. Pure Kotlin: tested on the JVM.
 */
object Fingers {
  const val SIDE = 1000

  /** How far from an edge a finger may reach, as a part of that side */
  const val BAND = 0.18f

  /** A finding: the feathered weight (0-1) on the small copy, its size, and each finger's box */
  class Found(val w: Int, val h: Int, val weight: FloatArray, val boxes: List<FBox>, private val paper: Grid) {
    /** The paper colour at a point of the small copy (bilinear) */
    fun paperAt(x: Float, y: Float, out: FloatArray) = paper.at(x, y, out)
  }

  /** The fingers on [p], or null when there are none. [avoid]: boxes never touched (parts 0-1) */
  fun find(p: Px, avoid: List<FBox> = emptyList()): Found? {
    val s = shrink(p, SIDE)
    val w = s.w
    val h = s.h
    val n = w * h
    val paper = paperGrid(s, null)
    val bandX = (w * BAND).roundToInt()
    val bandY = (h * BAND).roundToInt()
    val c3 = FloatArray(3)
    val skin = BooleanArray(n)
    for (y in 0 until h) for (x in 0 until w) {
      if (x >= bandX && x < w - bandX && y >= bandY && y < h - bandY) continue
      skin[y * w + x] = isSkin(s.data[y * w + x], paper, x, y, c3, strict = true)
    }
    val labels = IntArray(n)
    val minArea = max(30, n / 700)
    val maxArea = n / 10
    val avoidPx = avoid.map { it.toPx(w, h) }
    val kept = ArrayList<IntArray>()
    val boxes = ArrayList<FBox>()
    var next = 1
    val stack = IntArray(n)
    for (start in 0 until n) {
      if (!skin[start] || labels[start] != 0) continue
      val label = next++
      var top = 0
      stack[top++] = start
      labels[start] = label
      val members = ArrayList<Int>()
      var x0 = w
      var y0 = h
      var x1 = -1
      var y1 = -1
      while (top > 0) {
        val i = stack[--top]
        members.add(i)
        val x = i % w
        val y = i / w
        if (x < x0) x0 = x
        if (y < y0) y0 = y
        if (x > x1) x1 = x
        if (y > y1) y1 = y
        if (x > 0 && skin[i - 1] && labels[i - 1] == 0) { labels[i - 1] = label; stack[top++] = i - 1 }
        if (x < w - 1 && skin[i + 1] && labels[i + 1] == 0) { labels[i + 1] = label; stack[top++] = i + 1 }
        if (y > 0 && skin[i - w] && labels[i - w] == 0) { labels[i - w] = label; stack[top++] = i - w }
        if (y < h - 1 && skin[i + w] && labels[i + w] == 0) { labels[i + w] = label; stack[top++] = i + w }
      }
      val count = members.size
      if (count < minArea || count > maxArea) continue
      // it comes in from the edge (a couple of pixels of slack for a soft page edge)
      val touches = x0 <= 2 || y0 <= 2 || x1 >= w - 3 || y1 >= h - 3
      if (!touches) continue
      val box = IBox(x0, y0, x1 + 1, y1 + 1)
      if (avoidPx.any { overlaps(it, box) }) continue
      // a filled, chunky shape (a finger), not a thin skin-coloured line of print
      if (count < box.width * box.height * 0.25f && min(box.width, box.height) < max(6, min(w, h) / 80)) continue
      kept.add(members.toIntArray())
      boxes.add(FBox(x0.toFloat() / w, y0.toFloat() / h, (x1 + 1f) / w, (y1 + 1f) / h))
    }
    if (kept.isEmpty()) return null
    val mask = BooleanArray(n)
    for (m in kept) for (i in m) mask[i] = true
    // grown for the shadow beside the finger and the nail's edge, then feathered; the paper
    // under the fingers measured from the paper around them (the grown mask left out)
    return finish(s, mask, boxes)
  }

  /**
   * Skin, clearly not the paper: skin colours (YCbCr), and darker than the paper there or much
   * more colourful (aged paper is skin-ish). [strict] for finding; a little looser where a
   * finger was already found and approved (its shape inside its box).
   */
  private fun isSkin(c: Int, paper: Grid, x: Int, y: Int, c3: FloatArray, strict: Boolean): Boolean {
    val r = Px.r(c)
    val g = Px.g(c)
    val b = Px.b(c)
    val yy = 0.299f * r + 0.587f * g + 0.114f * b
    val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
    val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b
    if (strict) {
      if (yy < 45f || cr !in 136f..180f || cb !in 75f..128f) return false
    } else {
      if (yy < 30f || cr !in 132f..185f || cb !in 70f..132f) return false
    }
    paper.at(x.toFloat(), y.toFloat(), c3)
    val pl = 0.299f * c3[0] + 0.587f * c3[1] + 0.114f * c3[2]
    val pcr = 128 + 0.5f * c3[0] - 0.418688f * c3[1] - 0.081312f * c3[2]
    return if (strict) pl - yy >= 25f || cr - pcr >= 12f else pl - yy >= 15f || cr - pcr >= 8f
  }

  /**
   * The approved fingers ([boxes]: parts of the page, as found and stored in the page's
   * recipe) painted out of [p] in place, and nothing else: the skin inside each box (a little
   * larger) gives the finger's shape; a box where that is not found any more is filled whole.
   * The same boxes give the same result at any size (preview and full page).
   */
  fun removeIn(p: Px, boxes: List<FBox>) {
    if (boxes.isEmpty()) return
    val s = shrink(p, SIDE)
    val w = s.w
    val h = s.h
    val paper = paperGrid(s, null)
    val c3 = FloatArray(3)
    val mask = BooleanArray(w * h)
    val pad = max(2, min(w, h) / 40)
    for (fb in boxes) {
      val b0 = fb.toPx(w, h)
      val b = IBox(max(0, b0.left - pad), max(0, b0.top - pad), min(w, b0.right + pad), min(h, b0.bottom + pad))
      if (b.width <= 0 || b.height <= 0) continue
      var count = 0
      for (y in b.top until b.bottom) for (x in b.left until b.right) {
        if (isSkin(s.data[y * w + x], paper, x, y, c3, strict = false)) {
          mask[y * w + x] = true
          count++
        }
      }
      // (the finger no longer shows as skin here, e.g. a different light: its found box instead)
      if (count < b0.width * b0.height / 12) {
        for (y in b0.top until b0.bottom) for (x in b0.left until b0.right) mask[y * w + x] = true
      }
    }
    if (mask.none { it }) return
    apply(p, finish(s, mask, boxes))
  }

  /** The mask grown (shadow, nail edge), feathered, and the paper around it measured */
  private fun finish(s: Px, mask: BooleanArray, boxes: List<FBox>): Found {
    val w = s.w
    val h = s.h
    val grow = max(3, min(w, h) / 90)
    val grown = dilate(mask, w, h, grow)
    val feather = max(2, min(w, h) / 200)
    return Found(w, h, blur(grown, w, h, feather), boxes, paperGrid(s, grown))
  }

  /**
   * The fingers painted out of [p] (in place), the mask scaled from the small copy. True when
   * something was removed.
   */
  fun remove(p: Px, avoid: List<FBox> = emptyList()): Boolean {
    val f = find(p, avoid) ?: return false
    apply(p, f)
    return true
  }

  fun apply(p: Px, f: Found) {
    val kx = f.w.toFloat() / p.w
    val ky = f.h.toFloat() / p.h
    val c3 = FloatArray(3)
    for (y in 0 until p.h) {
      val sy = (y + 0.5f) * ky - 0.5f
      for (x in 0 until p.w) {
        val sx = (x + 0.5f) * kx - 0.5f
        val a = weightAt(f, sx, sy)
        if (a <= 0.003f) continue
        f.paperAt(sx, sy, c3)
        val i = y * p.w + x
        val c = p.data[i]
        p.data[i] = Px.rgb(
          (Px.r(c) + (c3[0] - Px.r(c)) * a).roundToInt(),
          (Px.g(c) + (c3[1] - Px.g(c)) * a).roundToInt(),
          (Px.b(c) + (c3[2] - Px.b(c)) * a).roundToInt(),
        )
      }
    }
  }

  private fun weightAt(f: Found, x: Float, y: Float): Float {
    val cx = x.coerceIn(0f, f.w - 1f)
    val cy = y.coerceIn(0f, f.h - 1f)
    val x0 = cx.toInt()
    val y0 = cy.toInt()
    val x1 = min(f.w - 1, x0 + 1)
    val y1 = min(f.h - 1, y0 + 1)
    val tx = cx - x0
    val ty = cy - y0
    val a = f.weight[y0 * f.w + x0] * (1 - tx) + f.weight[y0 * f.w + x1] * tx
    val b = f.weight[y1 * f.w + x0] * (1 - tx) + f.weight[y1 * f.w + x1] * tx
    return a * (1 - ty) + b * ty
  }

  private fun overlaps(a: IBox, b: IBox) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

  /** A copy at most [side] px (each small pixel the mean of the pixels it covers) */
  fun shrink(p: Px, side: Int): Px {
    val k = max(p.w, p.h).toFloat() / side
    if (k <= 1f) return p
    val w = max(1, (p.w / k).roundToInt())
    val h = max(1, (p.h / k).roundToInt())
    val out = IntArray(w * h)
    for (y in 0 until h) {
      val ya = y * p.h / h
      val yb = max(ya + 1, (y + 1) * p.h / h)
      for (x in 0 until w) {
        val xa = x * p.w / w
        val xb = max(xa + 1, (x + 1) * p.w / w)
        var r = 0
        var g = 0
        var b = 0
        var m = 0
        for (yy in ya until yb) for (xx in xa until xb) {
          val c = p.data[yy * p.w + xx]
          r += Px.r(c)
          g += Px.g(c)
          b += Px.b(c)
          m++
        }
        out[y * w + x] = Px.rgb(r / m, g / m, b / m)
      }
    }
    return Px(w, h, out)
  }

  /** [mask] grown by [r] px (a square neighbourhood: two passes of a running count) */
  private fun dilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
    val rows = BooleanArray(w * h)
    for (y in 0 until h) {
      var count = 0
      for (x in -r until w + r) {
        val add = x + r
        if (add in 0 until w && mask[y * w + add]) count++
        val drop = x - r - 1
        if (drop in 0 until w && mask[y * w + drop]) count--
        if (x in 0 until w) rows[y * w + x] = count > 0
      }
    }
    val out = BooleanArray(w * h)
    for (x in 0 until w) {
      var count = 0
      for (y in -r until h + r) {
        val add = y + r
        if (add in 0 until h && rows[add * w + x]) count++
        val drop = y - r - 1
        if (drop in 0 until h && rows[drop * w + x]) count--
        if (y in 0 until h) out[y * w + x] = count > 0
      }
    }
    return out
  }

  /** A 0-1 weight: [mask] box-blurred by [r] px, so its edge fades in (never a hard seam) */
  private fun blur(mask: BooleanArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(w * h)
    val d = 2 * r + 1f
    for (y in 0 until h) {
      var s = 0f
      for (x in -r..r) if (mask[y * w + x.coerceIn(0, w - 1)]) s++
      for (x in 0 until w) {
        tmp[y * w + x] = s / d
        if (mask[y * w + (x + r + 1).coerceIn(0, w - 1)]) s++
        if (mask[y * w + (x - r).coerceIn(0, w - 1)]) s--
      }
    }
    val out = FloatArray(w * h)
    for (x in 0 until w) {
      var s = 0f
      for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
      for (y in 0 until h) {
        // fully inside the grown mask stays fully filled (only its rim fades)
        out[y * w + x] = if (mask[y * w + x]) max(s / d, 0.999f).coerceAtMost(1f) else s / d
        s += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
        s -= tmp[(y - r).coerceIn(0, h - 1) * w + x]
      }
    }
    return out
  }

  /**
   * The paper's colour in cells (about 30 across): each cell's light part (its upper fifth by
   * brightness, the ink left out) of the pixels not in [skip]; cells with too few such pixels
   * get their neighbours' colour, spreading in from around. Then smoothed once.
   */
  class Grid(val gw: Int, val gh: Int, val cw: Float, val ch: Float, val rgb: Array<FloatArray>) {
    fun at(x: Float, y: Float, out: FloatArray) {
      val gx = (x / cw - 0.5f).coerceIn(0f, gw - 1f)
      val gy = (y / ch - 0.5f).coerceIn(0f, gh - 1f)
      val x0 = gx.toInt()
      val y0 = gy.toInt()
      val x1 = min(gw - 1, x0 + 1)
      val y1 = min(gh - 1, y0 + 1)
      val tx = gx - x0
      val ty = gy - y0
      for (k in 0..2) {
        val c = rgb[k]
        val a = c[y0 * gw + x0] * (1 - tx) + c[y0 * gw + x1] * tx
        val b = c[y1 * gw + x0] * (1 - tx) + c[y1 * gw + x1] * tx
        out[k] = a * (1 - ty) + b * ty
      }
    }
  }

  private fun paperGrid(s: Px, skip: BooleanArray?): Grid {
    val cells = 30
    val cell = max(4f, max(s.w, s.h).toFloat() / cells)
    val gw = max(1, (s.w / cell).roundToInt())
    val gh = max(1, (s.h / cell).roundToInt())
    val cw = s.w.toFloat() / gw
    val ch = s.h.toFloat() / gh
    val rgb = Array(3) { FloatArray(gw * gh) }
    val known = BooleanArray(gw * gh)
    val lums = IntArray(((cw + 1) * (ch + 1)).toInt() + 4)
    val idx = IntArray(lums.size)
    for (gy in 0 until gh) for (gx in 0 until gw) {
      val xa = (gx * cw).toInt()
      val xb = min(s.w, ((gx + 1) * cw).toInt())
      val ya = (gy * ch).toInt()
      val yb = min(s.h, ((gy + 1) * ch).toInt())
      var m = 0
      for (y in ya until yb) for (x in xa until xb) {
        val i = y * s.w + x
        if (skip != null && skip[i]) continue
        if (m < lums.size) {
          lums[m] = Px.lum(s.data[i])
          idx[m] = i
          m++
        }
      }
      val total = (xb - xa) * (yb - ya)
      if (m < max(4, total / 5)) continue
      // the light part: pixels at or above the 80th percentile of brightness
      val sorted = lums.copyOf(m).sortedArray()
      val cut = sorted[(m * 0.8f).toInt().coerceAtMost(m - 1)]
      var r = 0f
      var g = 0f
      var b = 0f
      var k = 0
      for (j in 0 until m) if (lums[j] >= cut) {
        val c = s.data[idx[j]]
        r += Px.r(c)
        g += Px.g(c)
        b += Px.b(c)
        k++
      }
      val ci = gy * gw + gx
      rgb[0][ci] = r / k
      rgb[1][ci] = g / k
      rgb[2][ci] = b / k
      known[ci] = true
    }
    // cells under a finger: filled in from their known neighbours, ring by ring
    if (known.none { it }) {
      for (k in 0..2) rgb[k].fill(250f)
      return Grid(gw, gh, cw, ch, rgb)
    }
    var missing = known.count { !it }
    while (missing > 0) {
      val add = ArrayList<Int>()
      for (ci in 0 until gw * gh) {
        if (known[ci]) continue
        val gx = ci % gw
        val gy = ci / gw
        var r = 0f
        var g = 0f
        var b = 0f
        var k = 0
        for (dy in -1..1) for (dx in -1..1) {
          val nx = gx + dx
          val ny = gy + dy
          if (nx !in 0 until gw || ny !in 0 until gh) continue
          val ni = ny * gw + nx
          if (!known[ni]) continue
          r += rgb[0][ni]
          g += rgb[1][ni]
          b += rgb[2][ni]
          k++
        }
        if (k > 0) {
          rgb[0][ci] = r / k
          rgb[1][ci] = g / k
          rgb[2][ci] = b / k
          add.add(ci)
        }
      }
      if (add.isEmpty()) break
      for (ci in add) known[ci] = true
      missing -= add.size
    }
    // one light smoothing (a cell's odd one out — a stamp, a dark word — evened)
    val sm = Array(3) { FloatArray(gw * gh) }
    for (ci in 0 until gw * gh) {
      val gx = ci % gw
      val gy = ci / gw
      for (k in 0..2) {
        var sum = 0f
        var wsum = 0f
        for (dy in -1..1) for (dx in -1..1) {
          val nx = gx + dx
          val ny = gy + dy
          if (nx !in 0 until gw || ny !in 0 until gh) continue
          val wt = if (dx == 0 && dy == 0) 2f else 1f
          sum += rgb[k][ny * gw + nx] * wt
          wsum += wt
        }
        sm[k][ci] = sum / wsum
      }
    }
    return Grid(gw, gh, cw, ch, sm)
  }
}
