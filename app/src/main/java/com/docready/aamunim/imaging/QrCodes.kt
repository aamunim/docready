package com.docready.aamunim.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import kotlin.math.max

/**
 * QR codes made and read on the phone with ZXing's core library (plain Java, no network, no
 * camera): black on white, each module a whole number of pixels so the code stays crisp.
 * Blocking: call off the main thread.
 */
object QrCodes {
  /** The code's modules (ECC M: a fifth of the code can be damaged; 4 modules of quiet edge) */
  fun matrix(text: String): BitMatrix =
    QRCodeWriter().encode(
      text,
      BarcodeFormat.QR_CODE,
      0,
      0,
      mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
    )

  /** The code as a picture about [side] px wide (whole pixels per module), black on white */
  fun bitmap(text: String, side: Int = 900): Bitmap {
    val m = matrix(text)
    val scale = max(1, side / m.width)
    val w = m.width * scale
    val h = m.height * scale
    val px = IntArray(w * h)
    for (y in 0 until h) {
      val my = y / scale
      for (x in 0 until w) px[y * w + x] = if (m.get(x / scale, my)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
  }

  /** The code with a line of text under it (for a saved image: what it is, and which part) */
  fun withCaption(code: Bitmap, caption: String): Bitmap {
    val pad = code.width / 14
    val out = Bitmap.createBitmap(code.width, code.height + pad * 2, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.WHITE)
    c.drawBitmap(code, 0f, 0f, null)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = pad * 0.8f
      textAlign = Paint.Align.CENTER
      typeface = Typeface.DEFAULT_BOLD
    }
    var text = caption
    while (text.length > 4 && paint.measureText(text) > code.width * 0.92f) text = text.dropLast(2) + "…"
    c.drawText(text, code.width / 2f, code.height + pad * 1.1f, paint)
    return out
  }

  /** Every QR code found in a picture's pixels (several codes in one photo too); empty if none */
  fun decode(argb: IntArray, w: Int, h: Int): List<String> {
    val hints = mapOf(
      DecodeHintType.TRY_HARDER to true,
      DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )
    val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, argb)))
    val found = try {
      QRCodeMultiReader().decodeMultiple(image, hints).mapNotNull { it.text }
    } catch (_: Exception) {
      emptyList()
    }
    if (found.isNotEmpty()) return found.distinct()
    return try {
      listOfNotNull(QRCodeReader().decode(image, hints).text)
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * The codes in a picture: read at the given size, then smaller (a big photo of a screen reads
   * better a little smaller). Never throws on a picture with no code.
   */
  fun decode(b: Bitmap): List<String> {
    for (side in listOf(1600, 1000, 700)) {
      val s = if (max(b.width, b.height) > side) DocTools.scaleToMax(b.copy(Bitmap.Config.ARGB_8888, false), side) else b
      val px = IntArray(s.width * s.height)
      s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
      val found = decode(px, s.width, s.height)
      if (found.isNotEmpty()) return found
      if (max(b.width, b.height) <= side) break
    }
    return emptyList()
  }
}
