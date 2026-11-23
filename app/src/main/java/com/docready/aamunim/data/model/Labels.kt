package com.docready.aamunim.data.model

val ItemKind.label: String
  get() = when (this) {
    ItemKind.PHOTO -> "Photo"
    ItemKind.SIGNATURE -> "Signature"
    ItemKind.DOCUMENT -> "Document"
  }

val PhotoBackground.label: String
  get() = when (this) {
    PhotoBackground.WHITE -> "White"
    PhotoBackground.LIGHT_BLUE -> "Light blue"
    PhotoBackground.GREY -> "Grey"
    PhotoBackground.KEEP -> "Keep original"
  }

/** The item's own name, else its kind ("SSC certificate", "Photo") */
val PresetItem.title: String
  get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: kind.label

/** "photo_300x300", "ssc_certificate": the ready file's name, without its extension */
fun PresetItem.fileBase(): String {
  val own = name?.trim()?.takeIf { it.isNotEmpty() }
  if (own != null) {
    // only marks a file name can't hold become "_": letters of any script (e.g. Bangla) stay
    return own.lowercase()
      .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\s]+"), "_")
      .trim('_', '.')
      .take(50)
      .ifEmpty { kind.name.lowercase() }
  }
  return when (kind) {
    ItemKind.PHOTO -> "photo_${widthPx ?: 300}x${heightPx ?: 300}"
    ItemKind.SIGNATURE -> "signature_${widthPx ?: 300}x${heightPx ?: 80}"
    ItemKind.DOCUMENT -> "document"
  }
}

/** "Photo · 300×300 px · max 100 KB · JPG" */
fun PresetItem.summary(): String {
  val parts = mutableListOf(title)
  if (title != kind.label) parts.add(kind.label.lowercase())
  if (widthPx != null && heightPx != null) parts.add("$widthPx×$heightPx px") else if (kind == ItemKind.DOCUMENT) parts.add("A4")
  parts.add(maxKb?.let { "max ${kbText(it)}" } ?: "no size limit")
  parts.add(format.name)
  if (kind == ItemKind.PHOTO && background != null) parts.add(background.label.lowercase() + " background")
  return parts.joinToString(" · ")
}

/** 100 → "100 KB", 1024 → "1 MB", 1536 → "1.5 MB" */
fun kbText(kb: Int): String =
  if (kb >= 1024 && kb % 512 == 0) {
    val mb = kb / 1024.0
    if (mb == mb.toLong().toDouble()) "${mb.toLong()} MB" else "$mb MB"
  } else {
    "$kb KB"
  }

/** A file's weight as people read it: whole KB rounded up (so "100 KB" never hides 100.4) */
fun bytesText(bytes: Long): String {
  val kb = (bytes + 1023) / 1024
  return if (kb >= 1024) String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0) else "$kb KB"
}
