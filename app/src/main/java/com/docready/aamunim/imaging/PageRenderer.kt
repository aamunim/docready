package com.docready.aamunim.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.docready.aamunim.data.model.CropRect
import com.docready.aamunim.data.model.Look
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.imaging.beauty.FBox
import com.docready.aamunim.imaging.beauty.Fingers
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A page made from its source and its [PageRecipe] (non-destructive editing): the same code
 * makes the editor's preview (a small source) and the full-quality page (the full source).
 * Every function blocks and uses up the picture it is given: call from Dispatchers.Default.
 */
object PageRenderer {
  /** The whole recipe */
  /**
   * [maxSide]: the page's longer side once straightened from its corners (the source may be
   * decoded larger only as the warp's input; the looks then never run on more than the target).
   */
  fun render(src: Bitmap, r: PageRecipe, maxSide: Int? = null): Bitmap {
    if (r.isPlain) return src
    return finish(geometry(src, r, maxSide = maxSide), r)
  }

  /**
   * Corners, quarter turns, fine rotation; then Beautify's clean-up (in the frame it analysed,
   * unless [beautyPixels] is false: the "before" view), its border trim, upside-down turn and
   * text straightening. This is what the crop box is drawn on.
   */
  fun geometry(src: Bitmap, r: PageRecipe, beautyPixels: Boolean = true, maxSide: Int? = null): Bitmap {
    // (the straightened page keeps the source's detail: no 2400 px cap)
    var b = DocTools.straighten(src, r.quad?.toFloatArray(), r.turn, maxSide ?: maxOf(src.width, src.height))
    // (no corners: a page decoded larger than the target is brought down before the looks run)
    if (maxSide != null) b = DocTools.scaleToMax(b, maxSide)
    if (abs(r.rotate) >= 0.05f) b = rotateFine(b, r.rotate)
    val bt = r.beautify
    if (bt != null) {
      if (beautyPixels) b = Beautifier.apply(b, bt)
      b = cropped(b, bt.trim)
      if (bt.flip) b = DocTools.straighten(b, null, 180)
      if (abs(bt.deskew) >= 0.05f) b = rotateFine(b, bt.deskew)
    }
    return b
  }

  /** After the geometry: the crop box, fingers out, shadows / look / light, then erasing */
  fun finish(geo: Bitmap, r: PageRecipe): Bitmap = erase(look(fingers(cropped(geo, r.crop), r), r), r)

  /**
   * The approved fingers painted out (their stored boxes only, scaled to this page: the preview
   * and the full page get the same); a recipe from before the boxes were stored finds them again
   */
  private fun fingers(b: Bitmap, r: PageRecipe): Bitmap {
    if (!r.fingers) return b
    if (r.fingerBoxes.isEmpty()) return Beautifier.removeFingers(b)
    val boxes = r.fingerBoxes.mapNotNull { inCrop(it, r.crop) }
    return if (boxes.isEmpty()) b else Beautifier.removeFingersIn(b, boxes)
  }

  /** A box on the framed page as parts of the page inside the crop box (null: outside it) */
  fun inCrop(c: CropRect, crop: CropRect?): FBox? {
    val k = crop ?: CropRect(0f, 0f, 1f, 1f)
    val w = max(0.0001f, k.width)
    val h = max(0.0001f, k.height)
    val l = ((c.left - k.left) / w).coerceIn(0f, 1f)
    val t = ((c.top - k.top) / h).coerceIn(0f, 1f)
    val rr = ((c.right - k.left) / w).coerceIn(0f, 1f)
    val bb = ((c.bottom - k.top) / h).coerceIn(0f, 1f)
    return if (rr - l < 0.002f || bb - t < 0.002f) null else FBox(l, t, rr, bb)
  }

  /** Fingers found: their boxes (framed page, before the crop box), and whether faces were checked */
  class FingerFind(val boxes: List<CropRect>, val facesChecked: Boolean)

  /**
   * The fingers on a page as it is framed (corners, turns, Beautify's framing): any over a photo,
   * logo or stamp left out, and any touching a face dropped (a portrait photo is skin too; faces
   * from 2 % of the page). Those checks run only when skin was found. Uses up [src].
   * Blocking (the face finder is awaited).
   */
  fun findFingers(src: Bitmap, r: PageRecipe): FingerFind {
    val page = geometry(src, r.copy(fingers = false), maxSide = Fingers.SIDE)
    try {
      val pw = page.width.toFloat()
      val ph = page.height.toFloat()
      // the quick skin check first: most pages stop here (no photo / stamp search, no face scan)
      val skin = Fingers.find(Beautifier.toPx(page))?.boxes ?: return FingerFind(emptyList(), true)
      val avoid = Beautifier.protectBoxes(page).map { FBox(it.left / pw, it.top / ph, it.right / pw, it.bottom / ph) }
      fun overlaps(b: FBox, f: FBox) = b.left < f.right && f.left < b.right && b.top < f.bottom && f.top < b.bottom
      val found = skin.filter { b -> avoid.none { overlaps(b, it) } }
      if (found.isEmpty()) return FingerFind(emptyList(), true)
      val faces: List<FBox>? = try {
        DocPhoto.faces(page, minFaceSize = 0.02f).map { f -> FBox(f.box.left / pw, f.box.top / ph, f.box.right / pw, f.box.bottom / ph) }
      } catch (_: Exception) {
        null // (the face finder not ready: the fingers are only offered)
      }
      val kept = if (faces == null) {
        found
      } else {
        found.filter { b -> faces.none { overlaps(b, it) } }
      }
      return FingerFind(kept.map { CropRect(it.left, it.top, it.right, it.bottom) }, faces != null)
    } finally {
      page.recycle()
    }
  }

  /**
   * A new page's recipe with its fingers: painted over by default when faces were checked;
   * only offered (boxes kept, switched off) when the face finder could not run
   */
  fun withFingers(r: PageRecipe, f: FingerFind?): PageRecipe = when {
    f == null || f.boxes.isEmpty() -> r
    f.facesChecked -> r.copy(fingers = true, fingerBoxes = f.boxes)
    else -> r.copy(fingers = false, fingerBoxes = f.boxes)
  }

  /** A few degrees of rotation around the middle, the same size, white in the corners */
  fun rotateFine(src: Bitmap, degrees: Float): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.WHITE)
    val m = Matrix().apply { postRotate(degrees, src.width / 2f, src.height / 2f) }
    c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    src.recycle()
    return out
  }

  fun cropped(src: Bitmap, crop: CropRect?): Bitmap {
    if (crop == null) return src
    val x = (crop.left.coerceIn(0f, 1f) * src.width).roundToInt().coerceIn(0, src.width - 1)
    val y = (crop.top.coerceIn(0f, 1f) * src.height).roundToInt().coerceIn(0, src.height - 1)
    val w = max(1, min(src.width - x, (crop.width * src.width).roundToInt()))
    val h = max(1, min(src.height - y, (crop.height * src.height).roundToInt()))
    if (x == 0 && y == 0 && w == src.width && h == src.height) return src
    val out = Bitmap.createBitmap(src, x, y, w, h)
    if (out !== src) src.recycle()
    return out
  }

  /** The looks that measure the paper themselves (shadows and folds are part of them) */
  private val SELF_FLATTENING = setOf(Look.MAGIC, Look.MAGIC_BW, Look.LIGHTEN)

  /** Shadows out (the paper's own light measured and evened), the look, then brightness / contrast */
  fun look(src: Bitmap, r: PageRecipe): Bitmap {
    var b = DocTools.mutable(src)
    if (r.removeShadows && r.look !in SELF_FLATTENING) b = Beautifier.flatten(b)
    b = when (r.look) {
      Look.ORIGINAL -> b
      Look.MAGIC -> Beautifier.magic(b)
      Look.MAGIC_BW -> Beautifier.magicBw(b)
      Look.LIGHTEN -> Beautifier.lighten(b)
      Look.AUTO -> DocTools.autoLevels(b)
      Look.GREY -> DocTools.toGray(DocTools.autoLevels(b))
      Look.BW -> DocTools.toBlackWhite(DocTools.autoLevels(b), 15)
      Look.WHITEN -> DocTools.autoLevels(b).also { DocTools.liftFolds(it, false) }
    }
    if (abs(r.brightness) >= 0.01f || abs(r.contrast) >= 0.01f) b = light(b, r.brightness, r.contrast)
    return b
  }

  /** Brightness (-1 to 1) and contrast (-0.5 to 1) through a colour matrix */
  fun light(src: Bitmap, brightness: Float, contrast: Float): Bitmap {
    val s = 1f + contrast.coerceIn(-0.5f, 1f)
    val t = 128f * (1f - s) + brightness.coerceIn(-1f, 1f) * 100f
    val cm = ColorMatrix(
      floatArrayOf(
        s, 0f, 0f, 0f, t,
        0f, s, 0f, 0f, t,
        0f, 0f, s, 0f, t,
        0f, 0f, 0f, 1f, 0f,
      ),
    )
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    src.recycle()
    return out
  }

  /** The paper's colour: the light part of the page (its brightest fifth), averaged */
  fun paperColour(b: Bitmap): Int {
    val stepX = max(1, b.width / 64)
    val stepY = max(1, b.height / 64)
    val samples = ArrayList<Int>()
    var y = stepY / 2
    while (y < b.height) {
      var x = stepX / 2
      while (x < b.width) {
        samples.add(b.getPixel(x, y))
        x += stepX
      }
      y += stepY
    }
    if (samples.isEmpty()) return Color.WHITE
    fun lum(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
    val light = samples.sortedByDescending { lum(it) }.take(max(1, samples.size / 5))
    val r = light.sumOf { Color.red(it) } / light.size
    val g = light.sumOf { Color.green(it) } / light.size
    val bl = light.sumOf { Color.blue(it) } / light.size
    return Color.rgb(r, g, bl)
  }

  /** The eraser's strokes painted on the finished page (in place) */
  fun erase(src: Bitmap, r: PageRecipe): Bitmap {
    if (r.erase.isEmpty()) return src
    val b = DocTools.mutable(src)
    val crop = r.crop ?: CropRect(0f, 0f, 1f, 1f)
    val cw = max(0.0001f, crop.width)
    val ch = max(0.0001f, crop.height)
    val paper = paperColour(b)
    val canvas = Canvas(b)
    for (s in r.erase) {
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (s.white) Color.WHITE else paper
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        // the page before the crop box, in pixels of this picture: its shorter side sizes the brush
        strokeWidth = max(1f, s.radius * min(b.width / cw, b.height / ch) * 2f)
      }
      val n = s.points.size / 2
      if (n == 0) continue
      fun px(i: Int) = (s.points[i * 2] - crop.left) / cw * b.width
      fun py(i: Int) = (s.points[i * 2 + 1] - crop.top) / ch * b.height
      if (n == 1) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(px(0), py(0), paint.strokeWidth / 2f, paint)
      } else {
        val path = Path().apply {
          moveTo(px(0), py(0))
          for (i in 1 until n) lineTo(px(i), py(i))
        }
        canvas.drawPath(path, paint)
      }
    }
    return b
  }
}
