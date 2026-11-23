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

/** One file a form asks for. null width/height on a document = A4 pages; null maxKb = no limit. */
@Serializable
data class PresetItem(
  val kind: ItemKind,
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val maxKb: Int? = null,
  val format: OutputFormat = OutputFormat.JPG,
  val background: PhotoBackground? = null,
)

@Serializable
data class Preset(
  val id: String,
  val name: String,
  val items: List<PresetItem>,
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
