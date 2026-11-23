package com.docready.aamunim.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A form photo on the phone (ported from the DocReady Expo module): the face found
 * (ML Kit face detection), the person apart from the background (ML Kit subject
 * segmentation), the chosen colour behind (or the background kept, with no empty
 * edge), the head placed: hair to chin takes [make]'s facePct of the height, in the
 * middle, straight. Nothing is sent anywhere; the models come with Google Play services.
 *
 * All functions block (ML Kit tasks are awaited): call them from Dispatchers.Default.
 */
object DocPhoto {
  class FaceInfo(
    val box: RectF,
    val leftEye: PointF?,
    val rightEye: PointF?,
    val mouth: PointF?,
    val roll: Float,
  )

  class Head(val topX: Float, val topY: Float, val chinX: Float, val chinY: Float, val roll: Float)

  class Made(val bitmap: Bitmap, val fixedSize: Boolean)

  /** The faces, biggest first (px of the picture) */
  fun faces(b: Bitmap): List<FaceInfo> {
    val det = FaceDetection.getClient(
      FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.06f)
        .build(),
    )
    try {
      val list = Tasks.await(det.process(InputImage.fromBitmap(b, 0)), 30, TimeUnit.SECONDS)
      return list.map { f ->
        FaceInfo(
          RectF(f.boundingBox),
          f.getLandmark(FaceLandmark.LEFT_EYE)?.position,
          f.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
          f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position,
          f.headEulerAngleZ,
        )
      }.sortedByDescending { it.box.width() * it.box.height() }
    } finally {
      det.close()
    }
  }

  /** How sure each dot is the person (0-1, the picture's size), or null (the model isn't ready / nobody there) */
  fun personMask(b: Bitmap): FloatArray? {
    val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    try {
      val res = Tasks.await(seg.process(InputImage.fromBitmap(b, 0)), 40, TimeUnit.SECONDS)
      val buf = res.foregroundConfidenceMask ?: return null
      buf.rewind()
      if (buf.remaining() != b.width * b.height) return null
      val out = FloatArray(b.width * b.height)
      buf.get(out)
      var person = 0
      for (v in out) if (v > 0.5f) person++
      val share = person.toFloat() / out.size
      return if (share < 0.03f || share > 0.97f) null else out
    } finally {
      seg.close()
    }
  }

  /**
   * The faces on a photo with what to tell the user about them (found once per photo,
   * then reused while the background or the size changes). A failure is a note, not an error.
   */
  fun findFaces(src: Bitmap, notes: MutableList<String>): List<FaceInfo> {
    val list = try {
      faces(src)
    } catch (e: Exception) {
      notes.add("The face finder could not run (${e.message ?: "Google Play services"}): the photo is fitted as it is.")
      return emptyList()
    }
    if (list.isEmpty()) {
      notes.add("No face found: the photo is fitted as it is. Check it.")
    } else if (list.size > 1 && list[1].box.width() * list[1].box.height() > list[0].box.width() * list[0].box.height() * 0.35f) {
      notes.add("${list.size} faces in the photo: the biggest one is used.")
    }
    val face = list.firstOrNull()
    if (face != null && max(face.box.width() / src.width, face.box.height() / src.height) < 0.12f) {
      notes.add("The face is small in this photo: the result may look soft. A closer photo is better.")
    }
    return list
  }

  /** Where the head is: from the eyes and mouth (else the face's box); the hair's top from the mask */
  fun head(f: FaceInfo, mask: FloatArray?, w: Int, h: Int): Head {
    val le = f.leftEye
    val re = f.rightEye
    val mo = f.mouth
    if (le != null && re != null && mo != null) {
      val ex = (le.x + re.x) / 2
      val ey = (le.y + re.y) / 2
      val (a, b) = if (re.x <= le.x) re to le else le to re
      val roll = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
      val dm = hypot(mo.x - ex, mo.y - ey).takeIf { it > 1f } ?: (f.box.height() * 0.4f)
      val ux = -sin(Math.toRadians(roll.toDouble())).toFloat()
      val uy = cos(Math.toRadians(roll.toDouble())).toFloat()
      // the mouth's lower edge is closer to the chin than its middle
      val chinX = ex + ux * dm * 1.45f
      val chinY = ey + uy * dm * 1.45f
      var topX = ex - ux * dm * 1.8f
      var topY = ey - uy * dm * 1.8f
      if (mask != null) {
        hairTop(mask, w, h, ex, ey - dm * 2.8f, ey - dm * 1.1f)?.let { t ->
          topY = t
          topX = ex - ux * ((ey - t) / max(0.2f, uy))
        }
      }
      return Head(topX, topY, chinX, chinY, if (abs(roll) <= 20f) roll else 0f)
    }
    val cx = f.box.centerX()
    var top = f.box.top - f.box.height() * 0.42f
    if (mask != null) {
      hairTop(mask, w, h, cx, f.box.top - f.box.height(), f.box.top + f.box.height() * 0.2f)?.let { top = it }
    }
    return Head(cx, top, cx, f.box.bottom, 0f)
  }

  /** The highest row (between y0 and y1) where the person is, around x */
  private fun hairTop(mask: FloatArray, w: Int, h: Int, x: Float, y0: Float, y1: Float): Float? {
    val xi = x.roundToInt().coerceIn(0, w - 1)
    val from = max(0, y0.roundToInt())
    val to = min(h - 1, y1.roundToInt())
    for (y in from..to) {
      var hits = 0
      for (dx in -3..3) {
        val xx = (xi + dx * 2).coerceIn(0, w - 1)
        if (mask[y * w + xx] > 0.5f) hits++
      }
      if (hits >= 4) return y.toFloat()
    }
    return null
  }

  /**
   * The photo made: W x H, the head placed (or the whole photo filling the frame), the
   * background a colour ([bg] with a [mask]) or kept. [face]: the face to place (null = none).
   * [notes]: what the user should look at.
   */
  fun make(
    src: Bitmap,
    width: Int?,
    height: Int?,
    maxSide: Int,
    facePct: Float,
    bg: Int?,
    face: FaceInfo?,
    mask: FloatArray?,
    notes: MutableList<String>,
  ): Made {
    val useMask = if (bg != null) mask else null
    val head = face?.let { head(it, useMask, src.width, src.height) }?.takeIf { it.chinY > it.topY }
    if (face != null && head == null) notes.add("The head could not be placed (a turned photo?): fitted as it is. Check it.")
    if (head != null && abs(head.roll) > 12f) notes.add("The head leans a lot: straightened, but check it.")
    // the size asked; none: a passport's shape (35 x 45, 600 px wide) when the head is placed,
    // else the photo's own shape (nothing cut away)
    val bigW: Int
    val bigH: Int
    if (width != null && height != null) {
      bigW = width
      bigH = height
    } else if (head != null) {
      bigW = 600
      bigH = 600 * 45 / 35
    } else {
      val k = min(1f, maxSide.toFloat() / max(src.width, src.height))
      bigW = max(1, (src.width * k).roundToInt())
      bigH = max(1, (src.height * k).roundToInt())
    }

    val m = Matrix()
    if (head != null) {
      val headPx = max(1f, hypot(head.chinX - head.topX, head.chinY - head.topY))
      val scale = (facePct / 100f) * bigH / headPx
      val topPct = max(3f, min(18f, (100f - facePct) * 0.3f))
      val wantY = (topPct / 100f) * bigH + (facePct / 100f) * bigH / 2f
      m.postTranslate(-(head.topX + head.chinX) / 2f, -(head.topY + head.chinY) / 2f)
      m.postRotate(-head.roll)
      m.postScale(scale, scale)
      m.postTranslate(bigW / 2f, wantY)
    } else {
      val scale = max(bigW.toFloat() / src.width, bigH.toFloat() / src.height)
      m.postTranslate(-src.width / 2f, -src.height / 2f)
      m.postScale(scale, scale)
      m.postTranslate(bigW / 2f, bigH / 2f)
    }
    // the background kept: no empty edge
    if (useMask == null) cover(m, src.width, src.height, bigW, bigH)

    val out = Bitmap.createBitmap(bigW, bigH, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(if (useMask != null && bg != null) bg else Color.WHITE)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    if (useMask != null) {
      // the person only: each dot as sure as the mask says (a soft, firm edge)
      val w = src.width
      val h = src.height
      val person = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val row = IntArray(w)
      for (y in 0 until h) {
        src.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
          val v = useMask[y * w + x]
          val a = if (v <= 0.35f) 0f else if (v >= 0.65f) 1f else (v - 0.35f) / 0.3f
          row[x] = (row[x] and 0x00FFFFFF) or ((a * 255).roundToInt() shl 24)
        }
        person.setPixels(row, 0, w, 0, y, w, 1)
      }
      c.drawBitmap(person, m, paint)
      person.recycle()
    } else {
      c.drawBitmap(src, m, paint)
    }
    // its size is fixed when it was asked, or the head was placed in it; else it may shrink to fit the weight
    return Made(out, (width != null && height != null) || head != null)
  }

  /** The placed photo moved (then made a little bigger) until it fills the W x H frame */
  private fun cover(m: Matrix, sw: Int, sh: Int, frameW: Int, frameH: Int) {
    repeat(6) {
      val p = floatArrayOf(0f, 0f, sw.toFloat(), 0f, sw.toFloat(), sh.toFloat(), 0f, sh.toFloat())
      m.mapPoints(p)
      val left = max(p[0], p[6])
      val right = min(p[2], p[4])
      val top = max(p[1], p[3])
      val bottom = min(p[5], p[7])
      if (right - left < frameW || bottom - top < frameH) {
        val f = max(frameW / max(1f, right - left), frameH / max(1f, bottom - top)) * 1.01f
        m.postScale(f, f, frameW / 2f, frameH / 2f)
        return@repeat
      }
      var dx = 0f
      var dy = 0f
      if (left > 0) dx = -left else if (right < frameW) dx = frameW - right
      if (top > 0) dy = -top else if (bottom < frameH) dy = frameH - bottom
      if (dx == 0f && dy == 0f) return
      m.postTranslate(dx, dy)
    }
  }
}
