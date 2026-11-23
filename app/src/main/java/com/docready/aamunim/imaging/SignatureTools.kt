package com.docready.aamunim.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/** A signature from its photo: only firm ink kept, the paper made white or see-through. Blocking. */
object SignatureTools {
  /**
   * The cut piece of the photo cleaned (shadows out, light evened so a faint pen stays, only firm
   * ink kept) and trimmed to the ink, on a see-through background; null = no signature found.
   * [piece] is used up (recycled).
   */
  fun inkOf(piece: Bitmap): Bitmap? {
    val bw = DocTools.toBlackWhite(DocTools.autoLevels(DocTools.flattenLight(DocTools.mutable(piece))), 25)
    return try {
      DocTools.inkOnly(bw)
    } finally {
      bw.recycle()
    }
  }

  /** The ink placed in the middle of an exact [width] x [height] picture, white or see-through behind */
  fun place(ink: Bitmap, width: Int, height: Int, transparent: Boolean): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    if (!transparent) c.drawColor(Color.WHITE)
    // a small margin all round, the ink as big as fits (never cut, never stretched)
    val boxW = width * 0.92f
    val boxH = height * 0.88f
    val k = min(boxW / ink.width, boxH / ink.height)
    val dw = ink.width * k
    val dh = ink.height * k
    val dx = (width - dw) / 2f
    val dy = (height - dh) / 2f
    c.drawBitmap(ink, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
  }
}
