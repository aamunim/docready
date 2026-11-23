package com.docready.aamunim.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.json.Json
import java.io.File

/** Shared JSON settings for the app's small stores */
val AppJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

/** Writes a file safely: a temp file first, then renamed over the old one */
fun File.writeTextAtomic(text: String) {
  parentFile?.mkdirs()
  val tmp = File(parentFile, "$name.tmp")
  tmp.writeText(text)
  if (!tmp.renameTo(this)) {
    writeText(text)
    tmp.delete()
  }
}

object FileNames {
  /** "photo_300x300" + "jpg" → "photo_300x300.jpg", or "-2", "-3"… when the name is taken */
  fun unique(base: String, ext: String, taken: Set<String>): String {
    val lower = taken.map { it.lowercase() }.toSet()
    var n = 1
    while (true) {
      val name = if (n == 1) "$base.$ext" else "$base-$n.$ext"
      if (name.lowercase() !in lower) return name
      n++
    }
  }

  /** A folder name safe for Downloads/DocReady/<name> (no slashes or odd marks); empty → "DocReady" */
  fun safeFolder(name: String): String {
    val clean = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]+"), " ").trim().trim('.').take(60).trim()
    return clean.ifEmpty { "DocReady" }
  }

  /** A readable file name piece ("My form" → "My_form") */
  fun safeBase(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(50).ifEmpty { "file" }

  /**
   * A new ready file with exactly this [name] (cache/out/<own folder>/<name>): its own folder
   * keeps the readable name when it is shared. Ready files older than a day are tidied away.
   */
  fun outputFile(context: Context, name: String): File {
    val root = File(context.cacheDir, "out").apply { mkdirs() }
    root.listFiles()?.forEach {
      if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.deleteRecursively()
    }
    val dir = File(root, "${System.currentTimeMillis()}_${(1000..9999).random()}").apply { mkdirs() }
    return File(dir, name)
  }

  /** A content link's file name, if it says */
  fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0) else null
    }
  } catch (_: Exception) {
    null
  }

  /** A picked / shared file copied into the app (the sender's link may not last) */
  fun copyIn(context: Context, uri: Uri, ext: String): File {
    val dir = File(context.cacheDir, "inputs").apply { mkdirs() }
    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
    val f = File(dir, "in_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
    val copied = context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
    if (copied == null) throw IllegalStateException("The file could not be read.")
    return f
  }
}
