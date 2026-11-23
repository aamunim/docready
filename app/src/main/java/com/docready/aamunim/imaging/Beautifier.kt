package com.docready.aamunim.imaging

import android.graphics.Bitmap
import com.docready.aamunim.data.model.BeautifyRecipe
import com.docready.aamunim.data.model.BeautyStrength
import com.docready.aamunim.data.model.CropRect
import com.docready.aamunim.data.model.EdgeKind
import com.docready.aamunim.data.model.EdgeMark
import com.docready.aamunim.data.model.FoldLine
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.imaging.beauty.BeautyAnalyzer
import com.docready.aamunim.imaging.beauty.BeautySteps
import com.docready.aamunim.imaging.beauty.FBox
import com.docready.aamunim.imaging.beauty.FoldPx
import com.docready.aamunim.imaging.beauty.IBox
import com.docready.aamunim.imaging.beauty.MarkKind
import com.docready.aamunim.imaging.beauty.MarkShape
import com.docready.aamunim.imaging.beauty.Px
import kotlin.math.max
import kotlin.math.roundToInt

/** What Beautify found on a page, for the editor: the steps to use, and what to tell the user */
class BeautyResult(
  val recipe: BeautifyRecipe,
  /** Uneven light: the Clean tab's "Remove shadows" is turned on too */
  val shadow: Boolean,
  /** A large tilt, offered only ("Straighten by 20°?") */
  val askAngle: Float?,
  val notes: List<String>,
)

/**
 * ✨ Beautify between Android pictures and the pure steps ([BeautyAnalyzer], [BeautySteps]).
 * Blocking: call from Dispatchers.Default.
 */
object Beautifier {
  /** Analysis is done on a copy of at most this size */
  const val ANALYSIS_SIDE = 1000

  fun toPx(b: Bitmap): Px {
    val data = IntArray(b.width * b.height)
    b.getPixels(data, 0, b.width, 0, 0, b.width, b.height)
    return Px(b.width, b.height, data)
  }

  /**
   * The page analysed ([page]: framed as it will be, before Beautify's own straightening; not
   * changed) and the steps it needs at [strength]. A signature only gets faded-ink and clarity.
   */
  fun analyze(page: Bitmap, strength: BeautyStrength, signature: Boolean): BeautyResult {
    val k = minOf(1f, ANALYSIS_SIDE.toFloat() / maxOf(page.width, page.height))
    val small = if (k < 1f) {
      Bitmap.createScaledBitmap(page, max(1, (page.width * k).roundToInt()), max(1, (page.height * k).roundToInt()), true)
    } else {
      page
    }
    val px = toPx(small)
    if (small !== page) small.recycle()
    val f = BeautyAnalyzer.analyze(px, signature)
    val level = strength.level
    fun box(b: FBox) = CropRect(b.left, b.top, b.right, b.bottom)
    val recipe = BeautifyRecipe(
      strength = strength,
      deskew = f.deskew,
      flip = f.flip,
      trim = f.trim?.let(::box),
      glare = if (f.glare.isNotEmpty()) level else 0f,
      glareAreas = f.glare.map(::box),
      stains = if (f.stains) level else 0f,
      whiten = if (f.yellow) level else 0f,
      folds = if (f.folds.isNotEmpty()) level else 0f,
      foldLines = f.folds.map { FoldLine(it.first, it.second, it.third) },
      // painted over only once the user has seen them outlined
      edges = false,
      edgeMarks = f.edges.map { (kind, b) ->
        EdgeMark(
          when (kind) {
            MarkKind.FINGER -> EdgeKind.FINGER
            MarkKind.HOLE -> EdgeKind.HOLE
            MarkKind.STAPLE -> EdgeKind.STAPLE
          },
          box(b),
        )
      },
      clarity = if (f.noisy || f.blurry) level * 0.8f else 0f,
      ink = if (f.faded) level else 0f,
      protect = f.protect.map(::box),
    )
    val notes = buildList {
      if (f.blurry) add("This page is blurry: rescan it for better quality.")
      if (f.glareLoss) add("Glare: some of what is under the shine can't be brought back.")
      if (f.edges.isNotEmpty()) {
        val what = f.edges.map { it.first }.distinct().joinToString(", ") {
          when (it) {
            MarkKind.FINGER -> "finger"
            MarkKind.HOLE -> "punch hole"
            MarkKind.STAPLE -> "staple"
          }
        }
        add("${f.edges.size} mark(s) at the edges ($what) are outlined: turn on “Edge marks” to paint them over.")
      }
    }
    return BeautyResult(recipe, !signature && f.shadow, f.askAngle, notes)
  }

  /**
   * The clean-up steps on a finished-geometry page (after the user's crop box [crop]); boxes and
   * lines are mapped from the uncropped frame. [src] may be used up.
   */
  fun apply(src: Bitmap, r: PageRecipe): Bitmap {
    val bt = r.beautify ?: return src
    if (!bt.hasPixels) return src
    val b = DocTools.mutable(src)
    val p = toPx(b)
    val crop = r.crop ?: CropRect(0f, 0f, 1f, 1f)
    val cw = max(0.0001f, crop.width)
    val ch = max(0.0001f, crop.height)
    fun mapBox(c: CropRect): IBox = FBox(
      (c.left - crop.left) / cw,
      (c.top - crop.top) / ch,
      (c.right - crop.left) / cw,
      (c.bottom - crop.top) / ch,
    ).toPx(p.w, p.h)
    val protect = bt.protect.map(::mapBox)
    BeautySteps.glare(p, bt.glareAreas.map(::mapBox), bt.glare)
    BeautySteps.stains(p, bt.stains, protect)
    BeautySteps.whiten(p, bt.whiten, protect)
    if (bt.folds > 0f) {
      val lines = bt.foldLines.map { f ->
        if (f.horizontal) {
          FoldPx(true, (((f.at - crop.top) / ch) * p.h).roundToInt(), max(1, (f.half / ch * p.h).roundToInt()))
        } else {
          FoldPx(false, (((f.at - crop.left) / cw) * p.w).roundToInt(), max(1, (f.half / cw * p.w).roundToInt()))
        }
      }
      BeautySteps.folds(p, lines, bt.folds)
    }
    if (bt.edges) {
      BeautySteps.edges(p, bt.edgeMarks.map { (if (it.kind == EdgeKind.HOLE) MarkShape.ELLIPSE else MarkShape.BOX) to mapBox(it.box) })
    }
    BeautySteps.clarity(p, bt.clarity)
    BeautySteps.ink(p, bt.ink)
    b.setPixels(p.data, 0, p.w, 0, 0, p.w, p.h)
    return b
  }
}
