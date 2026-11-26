package com.docready.aamunim.imaging

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.text.TextRecognizer
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** The text model is not on the phone yet (Google Play services is still fetching it) */
class OcrNotReady : IllegalStateException(Ocr.NOT_READY)

/**
 * English (Latin script) text recognition with ML Kit through Google Play services: Play
 * fetches the model itself when the app is installed (manifest DEPENDENCIES "ocr"), so the app
 * stays small and has no network permission. Until the model is there, reading throws
 * [OcrNotReady] (and an urgent download is asked for).
 * Work per page is capped: the page is read at most [MAX_SIDE] px on its longer side. Suspends;
 * cancelling the caller (leaving the screen) stops waiting for it.
 */
object Ocr {
  /** About 240 dpi on A4: small print still reads, a page takes a fraction of a second */
  const val MAX_SIDE = 2000

  /** For suggestions: smaller and faster (headings and labels read well) */
  const val QUICK_SIDE = 1000

  const val NOT_READY = "Text reading is getting ready (Google Play is downloading it). Try again in a minute."

  /** The app, to ask Play services for the model when it is missing (set when the app starts) */
  @Volatile
  var appContext: Context? = null

  @Volatile
  private var installAsked = false

  suspend fun read(src: Bitmap, maxSide: Int = MAX_SIDE): OcrPage {
    val long = max(src.width, src.height)
    val b = if (long > maxSide) {
      val k = maxSide.toFloat() / long
      Bitmap.createScaledBitmap(src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true)
    } else {
      src
    }
    val w = b.width.toFloat()
    val h = b.height.toFloat()
    val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
      val text: Text = try {
        client.process(InputImage.fromBitmap(b, 0)).awaitCancellable()
      } catch (e: MlKitException) {
        if (e.errorCode == MlKitException.UNAVAILABLE) {
          askForModel(client)
          throw OcrNotReady()
        }
        throw e
      }
      val lines = text.textBlocks.flatMap { block ->
        block.lines.mapNotNull { line ->
          val words = line.elements.mapNotNull { e ->
            val r = e.boundingBox ?: return@mapNotNull null
            val t = e.text.trim()
            if (t.isEmpty()) null else OcrWord(t, r.left / w, r.top / h, r.right / w, r.bottom / h)
          }
          words.ifEmpty { null }
        }
      }
      return OcrPage(lines)
    } finally {
      // (the scaled copy is left to the garbage collector: a cancelled task may still be reading it)
      client.close()
    }
  }

  /**
   * The model fetched now rather than later (once per app run): Play services downloads it
   * itself, the app needs no network permission for it. Errors are ignored (it comes anyway).
   */
  private fun askForModel(client: TextRecognizer) {
    val ctx = appContext ?: return
    if (installAsked) return
    installAsked = true
    try {
      ModuleInstall.getClient(ctx).installModules(ModuleInstallRequest.newBuilder().addApi(client).build())
    } catch (_: Exception) {
    }
  }

  /**
   * What a made document says about its applicant: from text already read ([texts]), else from
   * its first page read now. A failure is no facts (suggestions are only a help), except
   * [OcrNotReady], which is passed on so the screen can say so.
   */
  suspend fun facts(texts: List<String>, firstPage: () -> Bitmap): List<Fact> = try {
    val text = texts.take(3).joinToString("\n").ifEmpty {
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { read(firstPage()).text }
    }
    PageFacts.find(text)
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (e: OcrNotReady) {
    throw e
  } catch (_: Throwable) {
    emptyList()
  }

  /** A Play services task awaited without blocking a thread; cancelling stops the wait */
  private suspend fun <T> Task<T>.awaitCancellable(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { r -> if (cont.isActive) cont.resume(r) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
  }
}
