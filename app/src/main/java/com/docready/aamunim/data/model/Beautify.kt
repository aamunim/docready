package com.docready.aamunim.data.model

import kotlinx.serialization.Serializable

/** How hard Beautify works: the level every step it turns on starts at */
@Serializable
enum class BeautyStrength(val title: String, val level: Float) {
  LIGHT("Light", 0.45f),
  MEDIUM("Medium", 0.7f),
  STRONG("Strong", 1f),
}

@Serializable
enum class EdgeKind(val title: String) {
  FINGER("finger"),
  HOLE("punch hole"),
  STAPLE("staple"),
}

/** Something at the page's edge to paint over with the paper's colour (a box, parts of the page) */
@Serializable
data class EdgeMark(val kind: EdgeKind, val box: CropRect)

/** A fold or crease: a straight band across the page ([at] its middle, [half] its half width, parts of the page) */
@Serializable
data class FoldLine(val horizontal: Boolean, val at: Float, val half: Float)

/**
 * ✨ Beautify for bad paper, part of a page's [PageRecipe] (never changes the source). Found by
 * analysing the page; each step is on when its value is above 0 (or its switch is on). Boxes and
 * lines are parts of the page after straightening, the upside-down turn and the border trim,
 * before the user's crop box — the same frame as erasing.
 */
@Serializable
data class BeautifyRecipe(
  val strength: BeautyStrength = BeautyStrength.MEDIUM,
  /** Text-line straightening, degrees (0 = off) */
  val deskew: Float = 0f,
  /** The page was upside down */
  val flip: Boolean = false,
  /** A black border cut away (parts of the page) */
  val trim: CropRect? = null,
  val glare: Float = 0f,
  val glareAreas: List<CropRect> = emptyList(),
  val stains: Float = 0f,
  /** Yellow / aged paper made white (ink colours kept) */
  val whiten: Float = 0f,
  val folds: Float = 0f,
  val foldLines: List<FoldLine> = emptyList(),
  /** Fingers, punch holes, staples at the edges painted over (only once the user agrees) */
  val edges: Boolean = false,
  val edgeMarks: List<EdgeMark> = emptyList(),
  /** Denoise, then sharpen */
  val clarity: Float = 0f,
  /** Faded ink made darker (paper stays white) */
  val ink: Float = 0f,
  /** Photos, logos, stamps: left as they are by the colour steps */
  val protect: List<CropRect> = emptyList(),
) {
  /** Steps that move the page (straighten, upside down, border) */
  val hasGeometry: Boolean get() = deskew != 0f || flip || trim != null

  fun sameGeometry(o: BeautifyRecipe?): Boolean =
    deskew == (o?.deskew ?: 0f) && flip == (o?.flip ?: false) && trim == o?.trim

  val hasPixels: Boolean
    get() = glare > 0f || stains > 0f || whiten > 0f || (folds > 0f && foldLines.isNotEmpty()) ||
      (edges && edgeMarks.isNotEmpty()) || clarity > 0f || ink > 0f

  /** The same page geometry, no colour / clean-up steps (a face photo, the "before" view) */
  fun pixelsOff(): BeautifyRecipe = copy(glare = 0f, stains = 0f, whiten = 0f, folds = 0f, edges = false, clarity = 0f, ink = 0f)

  /** What ran, in short words for the page's badge */
  fun labels(): List<String> = buildList {
    if (deskew != 0f) add("straight")
    if (flip) add("upright")
    if (trim != null) add("border")
    if (glare > 0f && glareAreas.isNotEmpty()) add("glare")
    if (stains > 0f) add("stain")
    if (whiten > 0f) add("yellow")
    if (folds > 0f && foldLines.isNotEmpty()) add("fold")
    if (edges && edgeMarks.isNotEmpty()) add("edges")
    if (clarity > 0f) add("sharp")
    if (ink > 0f) add("ink")
  }
}

/** "✨ shadow · yellow · fold": what Beautify did on this page, or null */
fun PageRecipe.beautyBadge(): String? {
  val b = beautify ?: return null
  val words = (if (removeShadows) listOf("shadow") else emptyList()) + b.labels()
  return if (words.isEmpty()) null else "✨ " + words.joinToString(" · ")
}
