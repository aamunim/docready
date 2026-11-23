package com.docready.aamunim.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.save.ReadyFile
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** A ready file's size, pixels and pages, as the size checker shows them */
data class FileFacts(val bytes: Long, val width: Int? = null, val height: Int? = null, val pages: Int? = null)

/**
 * The size checker's "Fix to fit": an output brought under its limit with the existing fitting
 * (fitPdf for PDFs, the JPEG quality / size search for pictures). Blocking: call off the main thread.
 */
object SizeFixer {
  fun facts(f: File, mime: String): FileFacts {
    val bytes = f.length()
    return when {
      mime == "application/pdf" -> FileFacts(bytes, pages = try {
        PdfTools.pageCount(f)
      } catch (_: Exception) {
        null
      })
      mime.startsWith("image/") -> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        FileFacts(bytes, o.outWidth.takeIf { it > 0 }, o.outHeight.takeIf { it > 0 })
      }
      else -> FileFacts(bytes)
    }
  }

  /**
   * [f] under [maxKb] (or its own limit): a new ready file with the same name, or [f] itself when
   * it cannot be made smaller (a see-through PNG of an exact size).
   */
  fun fit(context: Context, f: ReadyFile, maxKb: Int? = f.maxKb): ReadyFile {
    val kb = maxKb ?: return f
    val maxBytes = kb * 1024L
    if (f.file.length() <= maxBytes) return f
    return when {
      f.mime == "application/pdf" -> fitPdf(context, f, kb, maxBytes)
      f.mime == "image/png" -> fitPng(context, f, kb, maxBytes)
      f.mime.startsWith("image/") -> {
        val b = BitmapFactory.decodeFile(f.file.path) ?: throw IllegalStateException("The picture could not be read.")
        // exact sizes (photo, signature) keep their pixels and go lower in quality; others may shrink
        val fit = DocTools.fitPicture(b, maxBytes, f.exactSize)
        val name = f.name.substringBeforeLast('.') + ".jpg"
        val out = FileNames.outputFile(context, name)
        out.writeBytes(fit.bytes)
        f.copy(file = out, name = name, mime = "image/jpeg", maxKb = kb)
      }
      else -> f
    }
  }

  /** The PDF's pages made again as pictures (one at a time), then fitted */
  private fun fitPdf(context: Context, f: ReadyFile, kb: Int, maxBytes: Long): ReadyFile {
    val total = PdfTools.pageCount(f.file)
    // never a shorter PDF than was given: a page left out would go unnoticed
    if (total > PdfTools.PAGE_LIMIT) {
      throw IllegalArgumentException("This PDF has $total pages: Fix to fit works on up to ${PdfTools.PAGE_LIMIT}. Split it first (PDF tools).")
    }
    val n = total
    val pages = ArrayList<File>()
    try {
      for (i in 0 until n) {
        val b = PdfTools.atBestSize(listOf(2400, PdfTools.PAGE_LONG_SIDE)) { side -> PdfTools.renderPage(f.file, i, side) }
        try {
          pages.add(DocTools.save(context, DocTools.jpeg(b, 95), "jpg"))
        } finally {
          b.recycle()
        }
      }
      val out = FileNames.outputFile(context, f.name)
      DocTools.fitPdf(pages, maxBytes, out)
      return f.copy(file = out, maxKb = kb)
    } finally {
      pages.forEach { it.delete() }
    }
  }

  /** A PNG can only get smaller by fewer pixels: never for an exact size */
  private fun fitPng(context: Context, f: ReadyFile, kb: Int, maxBytes: Long): ReadyFile {
    if (f.exactSize) return f
    var b: Bitmap = BitmapFactory.decodeFile(f.file.path) ?: return f
    while (true) {
      val bytes = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
      if (bytes.size <= maxBytes || maxOf(b.width, b.height) <= 300) {
        b.recycle()
        val out = FileNames.outputFile(context, f.name)
        out.writeBytes(bytes)
        return f.copy(file = out, maxKb = kb)
      }
      b = DocTools.scale(b, (b.width * 0.85f).roundToInt(), (b.height * 0.85f).roundToInt())
    }
  }
}
