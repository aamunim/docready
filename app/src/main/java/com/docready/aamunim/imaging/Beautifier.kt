package com.docready.aamunim.imaging

import android.graphics.Bitmap
import com.docready.aamunim.data.model.BeautifyRecipe
import com.docready.aamunim.data.model.BeautyStrength
import com.docready.aamunim.data.model.CropRect
import com.docready.aamunim.data.model.EdgeKind
import com.docready.aamunim.data.model.EdgeMark
import com.docready.aamunim.data.model.FoldLine
import com.docready.aamunim.imaging.beauty.BeautyAnalyzer
import com.docready.aamunim.imaging.beauty.BeautySteps
import com.docready.aamunim.imaging.beauty.FBox
import com.docready.aamunim.imaging.beauty.Fingers
import com.docready.aamunim.imaging.beauty.FoldPx
import com.docready.aamunim.imaging.beauty.IBox
import com.docready.aamunim.imaging.beauty.MarkKind
import com.docready.aamunim.imaging.beauty.MagicLooks
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

  /** Runs a pure step on [b] (mutable copy if needed) and writes the result back */
  private inline fun onPx(src: Bitmap, step: (Px) -> Unit): Bitmap {
    val b = DocTools.mutable(src)
    val p = toPx(b)
    step(p)
    b.setPixels(p.data, 0, p.w, 0, 0, p.w, p.h)
    return b
  }

  /** Photos, logos and stamps on a page (found on a ≤ 1000 px copy), as boxes of [b] */
  fun protectBoxes(b: Bitmap): List<IBox> {
    val k = minOf(1f, ANALYSIS_SIDE.toFloat() / maxOf(b.width, b.height))
    val small = if (k < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()), true) else b
    val px = toPx(small)
    if (small !== b) small.recycle()
    return BeautyAnalyzer.protectedAreas(px).map { it.toPx(b.width, b.height) }
  }

  /** Magic: crisp dark text on clean white, colours natural, photos / logos / stamps left as they are */
  fun magic(src: Bitmap): Bitmap {
    val protect = protectBoxes(src)
    return onPx(src) { MagicLooks.magic(it, protect) }
  }

  fun magicBw(src: Bitmap): Bitmap = onPx(src) { MagicLooks.magicBw(it) }

  /**
   * Fingers holding the paper found again and painted out (recipes from before the boxes were
   * stored): photos, logos and stamps never touched
   */
  fun removeFingers(src: Bitmap): Bitmap {
    val avoid = protectBoxes(src).map { FBox(it.left.toFloat() / src.width, it.top.toFloat() / src.height, it.right.toFloat() / src.width, it.bottom.toFloat() / src.height) }
    return onPx(src) { Fingers.remove(it, avoid) }
  }

  /** The approved fingers ([boxes], parts of [src]) painted out with the paper's own colour */
  fun removeFingersIn(src: Bitmap, boxes: List<FBox>): Bitmap = onPx(src) { Fingers.removeIn(it, boxes) }

  fun lighten(src: Bitmap): Bitmap = onPx(src) { MagicLooks.lighten(it) }

  /** "Remove shadows": the paper's light evened by its estimate (hard shadow edges and folds too, no halos) */
  fun flatten(src: Bitmap): Bitmap = onPx(src) { MagicLooks.flatten(it) }

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
      foundDeskew = f.deskew,
      // turning the page over, cutting a border, flattening folds and painting over edge marks
      // change what is on the page: offered (outlined), never done by themselves
      flip = false,
      maybeUpsideDown = f.maybeUpsideDown,
      trim = null,
      foundTrim = f.trim?.let(::box),
      glare = if (f.glare.isNotEmpty()) level else 0f,
      glareAreas = f.glare.map(::box),
      stains = if (f.stains) level else 0f,
      whiten = if (f.yellow) level else 0f,
      folds = 0f,
      foldLines = f.folds.map { FoldLine(it.first, it.second, it.third) },
      // painted over only once the user has seen them outlined
      edges = false,
      // (fingers have their own step, PageRecipe.fingers: found with a mask, filled with the paper)
      edgeMarks = f.edges.filter { it.first != MarkKind.FINGER }.map { (kind, b) ->
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
      if (f.trim != null) add("A dark border is outlined: turn on “Trim black border” to cut it away.")
      if (f.folds.isNotEmpty()) add("${f.folds.size} fold line(s) are outlined: turn on “Folds” to flatten them.")
      if (f.glareLoss) add("Glare: some of what is under the shine can't be brought back.")
      val marks = f.edges.filter { it.first != MarkKind.FINGER }
      if (marks.isNotEmpty()) {
        val what = marks.map { it.first }.distinct().joinToString(", ") {
          when (it) {
            MarkKind.FINGER -> "finger"
            MarkKind.HOLE -> "punch hole"
            MarkKind.STAPLE -> "staple"
          }
        }
        add("${marks.size} mark(s) at the edges ($what) are outlined: turn on “Edge marks” to paint them over.")
      }
    }
    return BeautyResult(recipe, !signature && f.shadow, f.askAngle, notes)
  }

  /**
   * The clean-up steps on the page as Beautify analysed it (after the user's corners / turns /
   * angle, before Beautify's own trim / turn / straightening), so its boxes fit directly.
   * [src] may be used up.
   */
  fun apply(src: Bitmap, bt: BeautifyRecipe): Bitmap {
    if (!bt.hasPixels) return src
    val b = DocTools.mutable(src)
    val p = toPx(b)
    fun mapBox(c: CropRect): IBox = FBox(c.left, c.top, c.right, c.bottom).toPx(p.w, p.h)
    val protect = bt.protect.map(::mapBox)
    BeautySteps.glare(p, bt.glareAreas.map(::mapBox), bt.glare)
    BeautySteps.stains(p, bt.stains, protect)
    BeautySteps.whiten(p, bt.whiten, protect)
    if (bt.folds > 0f) {
      val lines = bt.foldLines.map { f ->
        if (f.horizontal) {
          FoldPx(true, (f.at * p.h).roundToInt(), max(1, (f.half * p.h).roundToInt()))
        } else {
          FoldPx(false, (f.at * p.w).roundToInt(), max(1, (f.half * p.w).roundToInt()))
        }
      }
      BeautySteps.folds(p, lines, bt.folds, protect)
    }
    if (bt.edges) {
      BeautySteps.edges(p, bt.edgeMarks.map { (if (it.kind == EdgeKind.HOLE) MarkShape.ELLIPSE else MarkShape.BOX) to mapBox(it.box) })
    }
    // (photos, logos and stamps are never sharpened or darkened)
    BeautySteps.clarity(p, bt.clarity, protect)
    BeautySteps.ink(p, bt.ink, protect)
    b.setPixels(p.data, 0, p.w, 0, 0, p.w, p.h)
    return b
  }
}
