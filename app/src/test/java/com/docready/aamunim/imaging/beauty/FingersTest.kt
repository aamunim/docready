package com.docready.aamunim.imaging.beauty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * A page held by two fingers (from the left over a text line, from the bottom over the margin),
 * with a portrait photo near the right edge: the fingers become paper, the photo is untouched.
 */
class FingersTest {
  private val w = 1240
  private val h = 1754
  private val photo = IBox(1020, 60, 1200, 290)
  private val left = floatArrayOf(-20f, 700f, 190f, 712f, 34f)
  private val bottom = floatArrayOf(620f, 1774f, 640f, 1530f, 38f)

  private fun page(fingers: Boolean): IntArray {
    val d = IntArray(w * h)
    val rnd = java.util.Random(3)
    for (y in 0 until h) for (x in 0 until w) {
      val base = 238 + (x * 10 / w) - (y * 6 / h)
      val n = rnd.nextInt(5) - 2
      d[y * w + x] = Px.rgb(base + n, base - 2 + n, base - 8 + n)
    }
    for (line in 0 until 40) {
      val y0 = 120 + line * 38
      var x = 60
      val r = java.util.Random(line.toLong())
      while (x < w - 60) {
        val ww = 15 + r.nextInt(55)
        for (xx in x until min(w - 60, x + ww)) for (k in 0 until 14) d[(y0 + k) * w + xx] = Px.rgb(35, 35, 40)
        x += ww + 12
      }
    }
    // a portrait: grey background, dark hair, a skin-coloured face
    for (y in photo.top until photo.bottom) for (x in photo.left until photo.right) {
      val cx = (photo.left + photo.right) / 2f
      val dx = (x - cx) / 55f
      val dy = (y - 190f) / 72f
      d[y * w + x] = when {
        dx * dx + dy * dy < 1f -> Px.rgb(205 - (abs(dx) * 40).toInt(), 150 - (abs(dx) * 30).toInt(), 125 - (abs(dx) * 25).toInt())
        ((x - cx) / 75f).pow(2) + ((y - 150f) / 80f).pow(2) < 1f -> Px.rgb(40, 30, 25)
        else -> Px.rgb(150, 160, 170)
      }
    }
    if (fingers) {
      finger(d, left)
      finger(d, bottom)
    }
    return d
  }

  /** A capsule from the edge, shaded across, a lighter nail at the tip, a soft shadow beside it */
  private fun finger(d: IntArray, f: FloatArray) {
    val (ax, ay, bx, by, rad) = f.toList()
    val vx = bx - ax
    val vy = by - ay
    val len2 = vx * vx + vy * vy
    for (y in 0 until h) for (x in 0 until w) {
      val t = (((x - ax) * vx + (y - ay) * vy) / len2).coerceIn(0f, 1f)
      val dist = hypot(x - (ax + vx * t), y - (ay + vy * t))
      if (dist < rad) {
        val shade = 1f - 0.35f * (dist / rad).pow(2)
        val nail = t > 0.82f && dist < rad * 0.6f
        d[y * w + x] = if (nail) {
          Px.rgb((235 * shade).toInt(), (190 * shade).toInt(), (180 * shade).toInt())
        } else {
          Px.rgb((215 * shade).toInt(), (155 * shade).toInt(), (125 * shade).toInt())
        }
      } else if (dist < rad + 6 && t > 0f && t < 1f) {
        val c = d[y * w + x]
        d[y * w + x] = Px.rgb(Px.r(c) - 30, Px.g(c) - 30, Px.b(c) - 30)
      }
    }
  }

  private fun inFinger(x: Int, y: Int, f: FloatArray): Boolean {
    val (ax, ay, bx, by, rad) = f.toList()
    val vx = bx - ax
    val vy = by - ay
    val t = (((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy)).coerceIn(0f, 1f)
    return hypot(x - (ax + vx * t), y - (ay + vy * t)) < rad
  }

  @Test
  fun fingersBecomePaperAndThePhotoIsUntouched() {
    val d = page(fingers = true)
    val before = d.copyOf()
    val p = Px(w, h, d)
    val found = Fingers.find(p)
    assertEquals(2, found?.boxes?.size)
    assertTrue(Fingers.remove(p))
    val lums = ArrayList<Int>()
    for (y in 0 until h) for (x in 0 until w) if (inFinger(x, y, left) || inFinger(x, y, bottom)) lums.add(Px.lum(d[y * w + x]))
    val mean = lums.average()
    // paper there is about 236: no grey patch, no skin left
    assertTrue("fill mean $mean", mean >= 228)
    assertTrue("fill min ${lums.min()}", lums.min() >= 220)
    for (y in photo.top until photo.bottom) for (x in photo.left until photo.right) assertEquals(before[y * w + x], d[y * w + x])
  }

  @Test
  fun onlyTheStoredBoxesArePaintedAtAnySize() {
    val d = page(fingers = true)
    val before = d.copyOf()
    val boxes = Fingers.find(Px(w, h, d))!!.boxes
    // the full page
    Fingers.removeIn(Px(w, h, d), boxes)
    val lums = ArrayList<Int>()
    for (y in 0 until h) for (x in 0 until w) if (inFinger(x, y, left) || inFinger(x, y, bottom)) lums.add(Px.lum(d[y * w + x]))
    assertTrue("fill mean ${lums.average()}", lums.average() >= 228)
    assertTrue("fill min ${lums.min()}", lums.min() >= 220)
    for (y in photo.top until photo.bottom) for (x in photo.left until photo.right) assertEquals(before[y * w + x], d[y * w + x])
    // only the left finger's box: the bottom finger stays
    val one = page(fingers = true)
    Fingers.removeIn(Px(w, h, one), boxes.filter { it.left == 0f })
    assertTrue(Px.lum(one[1700 * w + 630]) < 200)
    assertTrue(Px.lum(one[706 * w + 40]) >= 220)
  }

  @Test
  fun aPageWithoutFingersIsLeftAlone() {
    val d = page(fingers = false)
    val before = d.copyOf()
    assertNull(Fingers.find(Px(w, h, d)))
    assertTrue(!Fingers.remove(Px(w, h, d)))
    assertTrue(before.contentEquals(d))
  }
}
