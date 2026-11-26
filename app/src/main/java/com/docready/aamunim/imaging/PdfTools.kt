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
  /** A4's long side at 150 dpi (a PDF's page made on demand, the smallest fallback) */
  const val PAGE_LONG_SIDE = 1754

  /**
   * A document page's long side when there is no size limit to meet (about 250 dpi on A4):
   * small print stays crisp. fitPdf / fitPicture only make it smaller when a KB limit needs it.
   */
  const val DOC_LONG_SIDE = 3000

  /** Long documents: a little smaller per page (memory and work files stay bounded) */
  const val LONG_DOC_SIDE = 2200

  /**
   * [make] at the largest of [sides] this phone's memory allows: an out-of-memory tries the next
   * size down (3000 → 2400 → 1754 px by default).
   */
  fun <T> atBestSize(sides: List<Int> = listOf(DOC_LONG_SIDE, 2400, PAGE_LONG_SIDE), make: (Int) -> T): T {
    var last: Throwable? = null
    for (side in sides) {
      try {
        return make(side)
      } catch (e: OutOfMemoryError) {
        last = e
        System.gc()
      }
    }
    throw IllegalStateException("This page is too big for this phone's memory.", last)
  }

  /** At most this many pages of one PDF are used (a note tells the user when there are more) */
  const val PAGE_LIMIT = 150

  /** The PDF opened (a password / damaged PDF gives a clear message) */
  private inline fun <T> withRenderer(pdf: File, block: (PdfRenderer) -> T): T {
    val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
      PdfRenderer(fd)
    } catch (e: Exception) {
      fd.close()
      throw IllegalStateException("This PDF can't be opened (locked with a password, or damaged).")
    }
    try {
      return block(renderer)
    } finally {
      renderer.close()
      fd.close()
    }
  }

  fun pageCount(pdf: File): Int = withRenderer(pdf) { it.pageCount }

  /**
   * Small pictures of the first [limit] pages (their longer side [side] px, 16-bit colour to keep
   * 150 of them small), one at a time: [onPage] gets each as soon as it is made. Fast: no full
   * page is made here (see [renderPageFile]). Returns the PDF's page count.
   */
  suspend fun renderThumbs(pdf: File, limit: Int, side: Int, onPage: suspend (index: Int, total: Int, thumb: Bitmap) -> Unit): Int =
    withRenderer(pdf) { renderer ->
      val total = renderer.pageCount
      for (n in 0 until min(total, limit)) {
        val page = renderer.openPage(n)
        val thumb = try {
          val k = side.toFloat() / maxOf(page.width, page.height)
          val bmp = Bitmap.createBitmap(
            maxOf(1, (page.width * k).roundToInt()),
            maxOf(1, (page.height * k).roundToInt()),
            Bitmap.Config.ARGB_8888,
          )
          bmp.eraseColor(Color.WHITE)
          page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          val small = bmp.copy(Bitmap.Config.RGB_565, false)
          bmp.recycle()
          small
        } finally {
          page.close()
        }
        onPage(n, total, thumb)
      }
      total
    }

  /** One page as a JPEG work file (A4 at 150 dpi), made when it is needed */
  fun renderPageFile(context: Context, pdf: File, index: Int): File {
    val b = renderPage(pdf, index, PAGE_LONG_SIDE)
    try {
      return DocTools.save(context, DocTools.jpeg(b, 92), "jpg")
    } finally {
      b.recycle()
    }
  }

  /**
   * One page of [pdf] as a picture, its longer side [maxSide] px (for cutting a small part of
   * it in detail); a phone short of memory gets it at 2400, then at A4 150 dpi.
   */
  fun renderPage(pdf: File, index: Int, maxSide: Int): Bitmap {
    val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
      val renderer = PdfRenderer(fd)
      try {
        val page = renderer.openPage(index)
        try {
          for (side in listOf(maxSide, 2400, PAGE_LONG_SIDE).distinct().filter { it <= maxSide }) {
            try {
              val k = side.toFloat() / maxOf(page.width, page.height)
              val bmp = Bitmap.createBitmap(
                maxOf(1, (page.width * k).roundToInt()),
                maxOf(1, (page.height * k).roundToInt()),
                Bitmap.Config.ARGB_8888,
              )
              bmp.eraseColor(Color.WHITE)
              page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              return bmp
            } catch (_: OutOfMemoryError) {
              // smaller next
            }
          }
          throw IllegalStateException("The page is too big for this phone's memory.")
        } finally {
          page.close()
        }
      } finally {
        renderer.close()
      }
    } finally {
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
