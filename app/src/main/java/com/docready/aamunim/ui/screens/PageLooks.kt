package com.docready.aamunim.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.title
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocPhoto
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.InkShape
import com.docready.aamunim.imaging.Keywords
import com.docready.aamunim.imaging.Ocr
import com.docready.aamunim.imaging.OcrNotReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a page shows: its words (OCR on a page about 1000 px), a face and how big, and the
 * shape of its ink
 */
class PageLook(val tokens: Set<String>, val top: List<String>, val words: Int, val faceShare: Float, val ink: InkShape, val textRead: Boolean = true)

/**
 * Which preset item a page looks like (Split by preset's suggestions, and the page picker's
 * "Looks like <item>?"): only ever a suggestion, never given without the user.
 */
class PageLooker(private val ctx: Application) {
  private val learned = ctx.docReady.learned

  /** Text reading is not ready yet (its model downloading): suggestions use the pictures only */
  var textNotReady by mutableStateOf(false)
    private set

  suspend fun look(page: PdfPage, recipe: PageRecipe): PageLook? = try {
    val b = withContext(Dispatchers.Default) { page.rendered(ctx, recipe, Ocr.QUICK_SIDE) }
    // the text model still downloading: the face, signature and ink checks still run
    val text = try {
      Ocr.read(b, Ocr.QUICK_SIDE).also { textNotReady = false }
    } catch (_: OcrNotReady) {
      textNotReady = true
      null
    }
    withContext(Dispatchers.Default) {
      val faceShare = try {
        DocPhoto.faces(b).firstOrNull()?.let { it.box.width() * it.box.height() / (b.width.toFloat() * b.height) } ?: 0f
      } catch (_: Exception) {
        0f // (the face finder not ready: no photo suggestion)
      }
      val small = DocTools.scaleToMax(b, 300)
      val px = IntArray(small.width * small.height)
      small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
      val all = text?.text ?: ""
      PageLook(Keywords.tokens(all).toSet(), Keywords.top(all), text?.words?.size ?: 0, faceShare, InkShape.of(px, small.width, small.height), textRead = text != null)
    }
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (_: Throwable) {
    null
  }

  /**
   * The item a page looks like: a face taking some of the page with little text → a photo item;
   * mostly white with one small cluster of ink → a signature item; else the document item whose
   * title, keywords and learned words match best (no suggestion on a tie or a weak match).
   */
  fun choose(look: PageLook, p: Preset): Int? {
    val items = p.items
    // a face alone is not enough (an NID card or a form has one): a large face on a page with
    // little ink of text (Bangla text is not read, so the ink tells), or a page that is mostly photo
    val bigFace = look.faceShare >= 0.10f && look.words < 30 && look.ink.darkShare < 0.06f
    val mostlyPhoto = look.faceShare >= 0.05f && look.ink.midShare >= 0.5f && look.words < 15
    if (bigFace || mostlyPhoto) {
      items.indexOfFirst { it.kind == ItemKind.PHOTO }.takeIf { it >= 0 }?.let { return it }
    }
    if (look.faceShare == 0f && look.ink.looksLikeSignature(look.words)) {
      items.indexOfFirst { it.kind == ItemKind.SIGNATURE }.takeIf { it >= 0 }?.let { return it }
    }
    val scores = items.mapIndexedNotNull { i, it ->
      if (it.kind != ItemKind.DOCUMENT) null else i to Keywords.score(look.tokens, it.title, it.keywords, learned.words(it.id))
    }.sortedByDescending { it.second }
    val best = scores.firstOrNull() ?: return null
    if (best.second < 2) return null
    if (scores.size > 1 && scores[1].second == best.second) return null
    return best.first
  }
}
