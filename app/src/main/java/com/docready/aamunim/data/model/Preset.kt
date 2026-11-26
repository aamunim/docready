package com.docready.aamunim.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ItemKind { PHOTO, SIGNATURE, DOCUMENT }

@Serializable
enum class OutputFormat { JPG, PNG, PDF }

/** A photo's background: a colour behind the person, or the photo's own background kept */
@Serializable
enum class PhotoBackground(val argb: Int?) {
  WHITE(0xFFFFFFFF.toInt()),
  LIGHT_BLUE(0xFFDBEAFE.toInt()),
  GREY(0xFFE5E7EB.toInt()),
  KEEP(null),
}

/**
 * One file a form asks for. null width/height on a document = A4 pages; null maxKb = no limit.
 * [name]: what the form calls it ("SSC certificate"); also the file's name.
 */
@Serializable
data class PresetItem(
  val kind: ItemKind,
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val maxKb: Int? = null,
  val format: OutputFormat = OutputFormat.JPG,
  val background: PhotoBackground? = null,
  val name: String? = null,
  /** The form can't be sent without it (false = optional) */
  val required: Boolean = true,
  /**
   * (older versions: a document's pages as free text, "1-2"; read once into [minPages] /
   * [maxPages], and still written for older versions of the app)
   */
  val pagesHint: String? = null,
  /**
   * Stays the same when items are reordered, renamed or others deleted (a student's files are
   * matched to items by it). "" in files from older versions: given one when loaded / saved.
   */
  val id: String = "",
  /** Words on the paper that tell it apart (Split by preset suggests pages with them): optional */
  val keywords: List<String> = emptyList(),
  /** A document's fewest pages (null = 1) */
  val minPages: Int? = null,
  /** A document's most pages (null = 1; [PageLimits.ANY] = any number). Photos, signatures: 1 */
  val maxPages: Int? = null,
)

/** Every item with an id (new or older items get a fresh one) */
fun Preset.withItemIds(newId: () -> String): Preset =
  if (items.all { it.id.isNotEmpty() }) this else copy(items = items.map { if (it.id.isEmpty()) it.copy(id = newId()) else it })

@Serializable
data class Preset(
  val id: String,
  val name: String,
  val items: List<PresetItem>,
  /** This preset's own file-name pattern (Settings → File names tokens); null = the general one */
  val namePattern: String? = null,
  /** (older versions had read-only built-in presets; kept only so their saved copies still load) */
  val builtIn: Boolean = false,
  val updatedAt: Long = 0L,
)

/** A file saved on this phone, in one of the user's local folders */
@Serializable
data class SavedFile(
  val name: String,
  val uri: String,
  val mime: String,
  val bytes: Long,
  val savedAt: Long,
)

/** A "folder": a named set of ready files on the phone (Downloads/DocReady/<name>/) */
@Serializable
data class LocalFolder(
  val name: String,
  val files: List<SavedFile> = emptyList(),
  val updatedAt: Long = 0L,
)
