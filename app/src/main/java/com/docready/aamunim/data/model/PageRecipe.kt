package com.docready.aamunim.data.model

import kotlinx.serialization.Serializable

/** A page's look: as it is, evened colour, grey, black & white, or light greys made white */
@Serializable
enum class Look(val title: String) {
  ORIGINAL("Original"),
  AUTO("Auto color"),
  GREY("Grey"),
  BW("B&W"),
  WHITEN("Whiten"),
}

/** A box as parts (0-1) of the picture it is on */
@Serializable
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top
}

/**
 * One brush stroke of the eraser: points (x, y, x, y…) and radius as parts of the page after
 * straightening and turning but before the crop box, so changing the crop never moves them.
 * [white]: painted white; else the paper's own colour.
 */
@Serializable
data class EraseStroke(val points: List<Float>, val radius: Float, val white: Boolean)

/**
 * What was done to a page, kept apart from the page itself (the source is never changed):
 * corners → quarter turns → fine rotation → crop box → shadows / look / light → erasing.
 * The preview and the full-quality page are both made from the source with it.
 */
@Serializable
data class PageRecipe(
  /** The paper's four corners on the source (x, y × 4, parts 0-1); null = the whole picture */
  val quad: List<Float>? = null,
  val turn: Int = 0,
  /** Fine rotation in degrees, -15 to 15 */
  val rotate: Float = 0f,
  val crop: CropRect? = null,
  val look: Look = Look.ORIGINAL,
  val removeShadows: Boolean = false,
  /** -1 to 1 */
  val brightness: Float = 0f,
  /** -0.5 to 1 */
  val contrast: Float = 0f,
  val erase: List<EraseStroke> = emptyList(),
) {
  val isPlain: Boolean get() = this == Plain

  /** This page with [other]'s look (shadows, look, light); its own corners, crop and erasing kept */
  fun withLookOf(other: PageRecipe): PageRecipe =
    copy(look = other.look, removeShadows = other.removeShadows, brightness = other.brightness, contrast = other.contrast)

  companion object {
    val Plain = PageRecipe()

    /** A new document page: shadows removed, colour evened */
    val Document = PageRecipe(look = Look.AUTO, removeShadows = true)
  }
}
