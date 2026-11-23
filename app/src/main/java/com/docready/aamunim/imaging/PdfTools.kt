package com.docready.aamunim.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF tools on the phone: a PDF's pages as pictures (Android's own PdfRenderer), and
 * pictures back into a PDF under a weight ([DocTools.fitPdf]: JPEG pages, the quality
 * lowered first, then the pages made smaller). Merge, compress, reorder and delete all
 * go through the same pages-as-pictures path. All functions block: call them off the main thread.
 */
object PdfTools {
  /** A4's long side at 150 dpi */
  const val PAGE_LONG_SIDE = 1754

  class Rendered(val pages: List<File>, val total: Int)

  /** A PDF's first [maxPages] pages as JPEG work files (their longer side [maxSide] px) */
  fun render(context: Context, pdf: File, maxSide: Int = PAGE_LONG_SIDE, maxPages: Int = 60): Rendered {
    val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
      PdfRenderer(fd)
    } catch (e: Exception) {
      fd.close()
      throw IllegalStateException("This PDF can't be opened (locked with a password, or damaged).")
    }
    try {
      val out = ArrayList<File>()
      for (n in 0 until min(renderer.pageCount, maxPages)) {
        val page = renderer.openPage(n)
        try {
          val k = maxSide.toFloat() / maxOf(page.width, page.height)
          val bmp = Bitmap.createBitmap(
            maxOf(1, (page.width * k).roundToInt()),
            maxOf(1, (page.height * k).roundToInt()),
            Bitmap.Config.ARGB_8888,
          )
          bmp.eraseColor(Color.WHITE)
          page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          out.add(DocTools.save(context, DocTools.jpeg(bmp, 92), "jpg"))
          bmp.recycle()
        } finally {
          page.close()
        }
      }
      return Rendered(out, renderer.pageCount)
    } catch (e: OutOfMemoryError) {
      throw IllegalStateException("The PDF is too big for this phone's memory.")
    } finally {
      renderer.close()
      fd.close()
    }
  }

  /**
   * Both sides of an ID card (NID) on one A4 page (150 dpi, portrait): the front above,
   * the back below. [actualSize]: each side printed at a real card's size (85.6 x 54 mm);
   * else each side fills most of its half of the page.
   */
  fun idCardPage(front: Bitmap, back: Bitmap, actualSize: Boolean): Bitmap {
    val pageW = 1240
    val pageH = PAGE_LONG_SIDE
    val pxPerMm = pageW / 210f
    val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.WHITE)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val boxW = if (actualSize) 85.6f * pxPerMm else pageW * 0.80f
    val boxH = if (actualSize) 54f * pxPerMm else pageH * 0.40f
    val gap = if (actualSize) 15f * pxPerMm else pageH * 0.05f
    val top = if (actualSize) 30f * pxPerMm else (pageH - boxH * 2 - gap) / 2f
    listOf(front to top, back to top + boxH + gap).forEach { (b, y) ->
      val k = min(boxW / b.width, boxH / b.height)
      val dw = b.width * k
      val dh = b.height * k
      val dx = (pageW - dw) / 2f
      val dy = y + (boxH - dh) / 2f
      c.drawBitmap(b, null, RectF(dx, dy, dx + dw, dy + dh), paint)
    }
    return out
  }
}
