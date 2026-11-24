package com.docready.aamunim.ui.screens

import com.docready.aamunim.data.model.beautyBadge
import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PhotoBackground
import com.docready.aamunim.data.model.Look
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.PresetItem
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.data.model.fileBase
import com.docready.aamunim.data.model.summary
import com.docready.aamunim.data.model.title
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocPhoto
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.InkShape
import com.docready.aamunim.imaging.Keywords
import com.docready.aamunim.imaging.Ocr
import kotlinx.coroutines.Job
import com.docready.aamunim.imaging.PdfTools
import com.docready.aamunim.imaging.SignatureTools
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.ui.components.BeautyBadge
import com.docready.aamunim.ui.components.DuplicateBadge
import com.docready.aamunim.ui.components.Duplicates
import com.docready.aamunim.ui.components.DuplicatesBar
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.CropBox
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.NormBox
import com.docready.aamunim.ui.components.ResultCard
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import com.docready.aamunim.ui.theme.DocReadyColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one preset item became after "Make all" */
class SplitItemResult(
  val item: PresetItem,
  val files: List<ReadyFile>,
  val bytes: Long,
  val fits: Boolean,
  val missing: Boolean,
  val note: String?,
)


/**
 * "Split by preset": the pages of a merged PDF (and / or photos) given out to a preset's items
 * (photo, signature, SSC certificate, NID...), then each item made as the preset says:
 * documents → one PDF (or JPGs) under the item's KB; a photo → cut, face / background,
 * exact W × H, under its KB; a signature → cut, ink only, exact W × H, under its KB.
 * Held by [PdfToolsViewModel]; the pages are its page list.
 */
class SplitState(
  private val ctx: Application,
  private val scope: CoroutineScope,
  /** Each page's edits (shared with PDF tools: one page editor for both) */
  private val recipes: Map<PdfPage, PageRecipe>,
  /** Small pictures of edited pages */
  val thumbs: Map<PdfPage, ImageBitmap>,
  /** The pages now (for suggestions in the background) */
  private val currentPages: () -> List<PdfPage> = { emptyList() },
) {
  var preset by mutableStateOf<Preset?>(null)
    private set

  /** The item the selected pages are given to */
  var target by mutableStateOf(0)

  /** Each page's item (absent = not used) */
  val assigned = mutableStateMapOf<PdfPage, Int>()
  val selected = mutableStateListOf<PdfPage>()

  /** The part of its first page a photo / signature item is cut from (parts of the page) */
  val crops = mutableStateMapOf<Int, NormBox>()

  /** The page each cut was drawn on: a cut is used only while that page is still the item's first */
  private val cropPages = mutableMapOf<Int, PdfPage>()
  var busy by mutableStateOf<String?>(null)
  var error by mutableStateOf<String?>(null)
  var results by mutableStateOf<List<SplitItemResult>?>(null)

  /** Called after the files were saved to a student (the draft is then finished) */
  var onSaved: () -> Unit = {}

  /** A draft brought back: its preset, each page's item and the cut parts */
  fun restore(p: Preset?, given: Map<PdfPage, Int>, cuts: Map<Int, NormBox>, pages: List<PdfPage>) {
    if (p == null) return
    preset = p
    target = 0
    for ((page, i) in given) if (i in p.items.indices) assigned[page] = i
    for ((i, box) in cuts) {
      val first = pagesOf(i, pages).firstOrNull() ?: continue
      crops[i] = box
      cropPages[i] = first
    }
    results = null
  }

  fun choosePreset(p: Preset) {
    if (preset?.id == p.id) return
    preset = p
    target = 0
    assigned.clear()
    crops.clear()
    cropPages.clear()
    results = null
    suggested.clear()
    rejected.clear()
    suggest()
  }

  fun toggle(page: PdfPage) {
    if (!selected.remove(page)) selected.add(page)
  }

  fun selectAll(pages: List<PdfPage>) {
    selected.clear()
    selected.addAll(pages)
  }

  fun assignSelected() {
    val p = preset ?: return
    if (target !in p.items.indices) return
    for (page in selected) {
      // the page's old item may have lost the page its cut was drawn on
      assigned[page]?.let { if (it != target) dropCrop(it) }
      assigned[page] = target
      suggested.remove(page)
    }
    selected.clear()
    dropCrop(target) // the item's first page may have changed
    results = null
  }

  fun unassignSelected() {
    for (page in selected) assigned.remove(page)?.let { dropCrop(it) }
    selected.clear()
    results = null
  }

  fun pagesOf(item: Int, pages: List<PdfPage>): List<PdfPage> = pages.filter { assigned[it] == item }

  private fun dropCrop(item: Int) {
    crops.remove(item)
    cropPages.remove(item)
  }

  /**
   * The page list changed (moved, removed, cleared): removed pages are forgotten, a cut whose
   * page is no longer its item's first page is dropped, and old results are out of date.
   */
  fun onPagesChanged(pages: List<PdfPage>) {
    val present = pages.toSet()
    assigned.keys.filter { it !in present }.forEach { assigned.remove(it) }
    selected.removeAll { it !in present }
    suggested.keys.filter { it !in present }.forEach { suggested.remove(it) }
    for (i in crops.keys.toList()) {
      if (cropPages[i] == null || pagesOf(i, pages).firstOrNull() !== cropPages[i]) dropCrop(i)
    }
    results = null
    // new pages: looked at too (pages already looked at are not looked at again)
    if (preset != null && pages.any { it !in looks && it !in assigned }) suggest()
  }

  // ---- suggestions: which item each page looks like (never given without a tap)
  /** Each page's suggested item */
  val suggested = mutableStateMapOf<PdfPage, Int>()

  /** Items the user said a page is not (per page, for this preset) */
  private val rejected = HashMap<PdfPage, MutableSet<Int>>()

  /** "Looking at page 12 of 150…" while suggestions are being made */
  var suggesting by mutableStateOf<String?>(null)
    private set

  /** What was seen on each page (read once, whatever the preset) */
  private val looks = HashMap<PdfPage, PageLook>()
  private var suggestJob: Job? = null

  /**
   * What a page shows: its words (OCR on a page about 1000 px), a face and how big, and the
   * shape of its ink
   */
  private class PageLook(val tokens: Set<String>, val top: List<String>, val words: Int, val faceShare: Float, val ink: InkShape)

  /**
   * Suggestions made again for the pages not given yet, one page at a time (each shows as soon
   * as it is ready); a new call replaces a running one; leaving the screen stops it.
   */
  fun suggest() {
    suggestJob?.cancel()
    val p = preset ?: return
    suggestJob = scope.launch {
      val pages = currentPages()
      try {
        for ((n, page) in pages.withIndex()) {
          if (page in assigned) continue
          val look = looks[page] ?: run {
            suggesting = "Looking at page ${n + 1} of ${pages.size}…"
            lookAt(page)?.also { looks[page] = it }
          } ?: continue
          val best = choose(look, p)
          if (best != null && best !in (rejected[page] ?: emptySet<Int>()) && page !in assigned) suggested[page] = best else suggested.remove(page)
        }
      } finally {
        suggesting = null
      }
    }
  }

  private suspend fun lookAt(page: PdfPage): PageLook? = try {
    val b = withContext(Dispatchers.Default) { page.rendered(ctx, recipes[page] ?: PageRecipe.Plain, Ocr.QUICK_SIDE) }
    val text = Ocr.read(b, Ocr.QUICK_SIDE)
    withContext(Dispatchers.Default) {
      val faceShare = try {
        DocPhoto.faces(b).firstOrNull()?.let { it.box.width() * it.box.height() / (b.width.toFloat() * b.height) } ?: 0f
      } catch (_: Exception) {
        0f // (the face finder not ready: no photo suggestion)
      }
      val small = DocTools.scaleToMax(b, 300)
      val px = IntArray(small.width * small.height)
      small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
      val all = text.text
      PageLook(Keywords.tokens(all).toSet(), Keywords.top(all), text.words.size, faceShare, InkShape.of(px, small.width, small.height))
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
  private fun choose(look: PageLook, p: Preset): Int? {
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

  fun accept(page: PdfPage) {
    val i = suggested.remove(page) ?: return
    val p = preset ?: return
    assigned[page]?.let { if (it != i) dropCrop(it) }
    assigned[page] = i
    selected.remove(page)
    dropCrop(i)
    results = null
  }

  fun reject(page: PdfPage) {
    val i = suggested.remove(page) ?: return
    rejected.getOrPut(page) { mutableSetOf() }.add(i)
  }

  fun acceptAll(pages: List<PdfPage>) {
    for (page in pages) if (page in suggested) accept(page)
  }

  private val learned = ctx.docReady.learned

  /** (page, item id) already learned in this split: making the files again teaches nothing new */
  private val taught = HashSet<Pair<PdfPage, String>>()

  /**
   * The pages' words counted for the item: only when its files are made (a page given by
   * mistake and moved before that teaches nothing). Pages not looked at yet teach nothing.
   */
  private fun learn(pages: List<PdfPage>, item: PresetItem) {
    // (a page not looked at yet is not marked as taught: it can still teach once it has been)
    val words = pages.filter { looks[it] != null && taught.add(it to item.id) }.mapNotNull { looks[it]?.top }
    if (words.isEmpty() || item.id.isEmpty()) return
    scope.launch(Dispatchers.IO) { for (w in words) learned.learn(item.id, w) }
  }

  /** Pages edited in the page editor: a cut drawn on the old look / geometry no longer fits */
  fun onRecipesChanged(changed: Collection<PdfPage>) {
    for (page in changed) assigned[page]?.let { dropCrop(it) }
    if (changed.isNotEmpty()) results = null
    // an edited page may look different now (turned, cropped): looked at again
    for (page in changed) {
      looks.remove(page)
      suggested.remove(page)
      taught.removeAll { it.first === page }
    }
    if (changed.isNotEmpty() && preset != null) suggest()
  }

  /**
   * Before [pages] are removed (Remove duplicates): how to give them back their items and cuts
   * (Undo), to be run once they are back in the list
   */
  fun keepFor(pages: List<PdfPage>): () -> Unit {
    val presetId = preset?.id
    val given = pages.mapNotNull { p -> assigned[p]?.let { p to it } }
    val cuts = crops.toMap()
    val cutPages = cropPages.toMap()
    val wasSuggested = pages.mapNotNull { p -> suggested[p]?.let { p to it } }
    return restore@{
      // (another preset chosen since: its items are not these)
      if (preset?.id != presetId) return@restore
      for ((p, i) in given) assigned[p] = i
      for ((i, box) in cuts) {
        val on = cutPages[i] ?: continue
        if (crops[i] == null && assigned[on] == i) {
          crops[i] = box
          cropPages[i] = on
        }
      }
      for ((p, i) in wasSuggested) if (p !in assigned) suggested[p] = i
      results = null
    }
  }

  /** A page as it will be used: made from its source with its edits, its longer side at most [maxSide] */
  private fun prepared(page: PdfPage, maxSide: Int, faceSafe: Boolean = false): Bitmap =
    page.rendered(ctx, recipeOf(page, faceSafe), maxSide)

  /**
   * The page's edits; [faceSafe] (a face photo): its geometry, light and erasing only — a face is
   * never flattened by "remove shadows" or turned grey / black & white.
   */
  /** A page's edits, for its tile (the Beautify badge) */
  fun recipeFor(page: PdfPage): PageRecipe? = recipes[page]

  private fun recipeOf(page: PdfPage, faceSafe: Boolean): PageRecipe {
    val r = recipes[page] ?: PageRecipe.Plain
    // (Beautify keeps its framing for a face, never its paper clean-up)
    return if (faceSafe) r.copy(look = Look.ORIGINAL, removeShadows = false, beautify = r.beautify?.pixelsOff(), fingers = false) else r
  }

  /**
   * The page in more detail, for cutting a small photo / signature out of it: made again from
   * its PDF / photo with its long side up to [CUT_SIDE], its edits applied the same way (they are
   * parts of the page). A phone short of memory gets the usual page.
   */
  private fun preparedForCut(page: PdfPage, faceSafe: Boolean = false): Bitmap = try {
    page.rendered(ctx, recipeOf(page, faceSafe), CUT_SIDE)
  } catch (_: OutOfMemoryError) {
    prepared(page, 2400, faceSafe)
  }

  // ---- the region of a photo / signature item, cut by hand
  var cropping by mutableStateOf<Int?>(null)
    private set
  var cropImage by mutableStateOf<ImageBitmap?>(null)
    private set
  var cropBox by mutableStateOf(NormBox.Default)
  private var cropW = 1
  private var cropH = 1

  /** A photo's box keeps the item's W:H (height / width in parts of the page); a signature's is free */
  fun cropRatio(): Float? {
    val i = cropping ?: return null
    val item = preset?.items?.getOrNull(i) ?: return null
    if (item.kind != ItemKind.PHOTO) return null
    val w = item.widthPx ?: 300
    val h = item.heightPx ?: 300
    return (h.toFloat() / w) * (cropW.toFloat() / cropH)
  }

  fun startCrop(item: Int, pages: List<PdfPage>) {
    val page = pagesOf(item, pages).firstOrNull() ?: return
    scope.launch {
      try {
        val b = withContext(Dispatchers.Default) { prepared(page, 1400) }
        cropW = b.width
        cropH = b.height
        cropImage = b.asImageBitmap()
        cropPage = page
        cropping = item
        val r = cropRatio()
        cropBox = crops[item] ?: if (r != null) NormBox.centered(r, 0.4f) else NormBox(0.1f, 0.6f, 0.9f, 0.85f)
      } catch (e: Throwable) {
        error = messageOf(e, "The page could not be opened.")
      }
    }
  }

  private var cropPage: PdfPage? = null

  fun applyCrop() {
    val i = cropping ?: return
    crops[i] = cropBox
    cropPage?.let { cropPages[i] = it }
    cropping = null
    cropImage = null
    results = null
  }

  fun cancelCrop() {
    cropping = null
    cropImage = null
  }

  fun clearCrop(item: Int) {
    dropCrop(item)
    results = null
  }

  // ---- making every item
  fun makeAll(pages: List<PdfPage>) {
    val p = preset
    if (p == null) {
      error = "Choose the form's preset first (step 1)."
      return
    }
    if (pages.none { assigned[it] != null }) {
      error = "No page is given to an item yet: select pages, choose an item, then tap “Give”."
      return
    }
    val plan = p.items.indices.map { i -> pagesOf(i, pages) }
    // a cut counts only on the page it was drawn on
    val cuts = crops.filterKeys { i -> cropPages[i] != null && plan.getOrNull(i)?.firstOrNull() === cropPages[i] }
    scope.launch {
      busy = "Making the files…"
      error = null
      try {
        results = withContext(Dispatchers.Default) {
          val taken = mutableSetOf<String>()
          fun nameFor(base: String, ext: String) = FileNames.unique(base, ext, taken).also { taken.add(it) }
          p.items.mapIndexed { i, item ->
            val its = plan[i]
            withContext(Dispatchers.Main) { busy = "Making ${item.title} (${i + 1} of ${p.items.size})…" }
            if (its.isEmpty()) {
              SplitItemResult(item, emptyList(), 0L, false, true, null)
            } else {
              try {
                makeItem(item, i, its, cuts[i], ::nameFor)
              } catch (e: Throwable) {
                SplitItemResult(item, emptyList(), 0L, false, false, messageOf(e, "This file could not be made."))
              }
            }
          }
        }
        // the files are made: what each item's pages say is learned (once per page and item)
        p.items.forEachIndexed { i, item -> learn(plan[i], item) }
      } catch (e: Throwable) {
        error = messageOf(e, "The files could not be made.")
      } finally {
        busy = null
      }
    }
  }

  /** One item from its pages (blocking; one big picture in memory at a time) */
  private fun makeItem(item: PresetItem, index: Int, its: List<PdfPage>, cut: NormBox?, nameFor: (String, String) -> String): SplitItemResult {
    val maxBytes = item.maxKb?.let { it * 1024L }
    val base = item.fileBase()
    val more = if (its.size > 1) "Only the first of its ${its.size} pages is used." else null
    return when (item.kind) {
      ItemKind.DOCUMENT -> if (item.format == OutputFormat.JPG) {
        val files = mutableListOf<ReadyFile>()
        var total = 0L
        var fits = true
        its.forEachIndexed { n, page ->
          val fit = DocTools.fitPicture(PdfTools.atBestSize { side -> prepared(page, side) }, maxBytes, false)
          val name = nameFor(if (its.size == 1) base else "${base}_page-${n + 1}", "jpg")
          val out = FileNames.outputFile(ctx, name)
          out.writeBytes(fit.bytes)
          files.add(ReadyFile(out, name, "image/jpeg", item.maxKb, itemIndex = index))
          total += fit.bytes.size
          fits = fits && fit.fits
        }
        SplitItemResult(item, files, total, fits, false, if (its.size > 1 && maxBytes != null) "The limit is for each picture." else null)
      } else {
        val kept = its.map { page -> DocTools.keepPage(ctx, PdfTools.atBestSize { side -> prepared(page, side) }) }
        val name = nameFor(base, "pdf")
        val out = FileNames.outputFile(ctx, name)
        val (size, _, fits) = try {
          DocTools.fitPdf(kept, maxBytes, out)
        } finally {
          kept.forEach { it.delete() } // (in the PDF now)
        }
        SplitItemResult(item, listOf(ReadyFile(out, name, "application/pdf", item.maxKb, itemIndex = index)), size, fits, false, null)
      }

      ItemKind.PHOTO -> {
        val w = item.widthPx ?: 300
        val h = item.heightPx ?: 300
        val notes = mutableListOf<String>()
        more?.let { notes.add(it) }
        val piece = if (cut != null) {
          DocTools.scaleToMax(cutOut(preparedForCut(its[0], faceSafe = true), cut), 2400)
        } else {
          prepared(its[0], 2400, faceSafe = true)
        }
        val bg = (item.background ?: PhotoBackground.WHITE).argb
        // cut by hand: that part is the photo; else the face is found on the page
        val faces = if (cut == null) DocPhoto.findFaces(piece, notes) ?: emptyList() else emptyList()
        val mask = if (bg != null) {
          try {
            DocPhoto.personMask(piece)
          } catch (_: Exception) {
            null
          }
        } else {
          null
        }
        if (bg != null && mask == null) notes.add("The background was kept as it is (Google's model may still be downloading).")
        val levelled = DocTools.autoLevels(piece.copy(Bitmap.Config.ARGB_8888, true))
        val manual = if (cut != null) RectF(0f, 0f, levelled.width.toFloat(), levelled.height.toFloat()) else null
        val made = DocPhoto.make(levelled, w, h, 2000, 70f, bg, faces.firstOrNull(), mask, notes, manual)
        levelled.recycle()
        piece.recycle()
        val fit = DocTools.fitPicture(made.bitmap, maxBytes, true)
        val name = nameFor(base, "jpg")
        val out = FileNames.outputFile(ctx, name)
        out.writeBytes(fit.bytes)
        SplitItemResult(item, listOf(ReadyFile(out, name, "image/jpeg", item.maxKb, exactSize = true, itemIndex = index)), fit.bytes.size.toLong(), fit.fits, false, notes.joinToString(" ").ifEmpty { null })
      }

      ItemKind.SIGNATURE -> {
        val w = item.widthPx ?: 300
        val h = item.heightPx ?: 80
        val png = item.format == OutputFormat.PNG
        val piece = if (cut != null) DocTools.scaleToMax(cutOut(preparedForCut(its[0]), cut), 2400) else prepared(its[0], 2400)
        val ink = SignatureTools.inkOf(piece)
          ?: throw IllegalStateException("No signature found: cut the region closely around it.")
        val placed = SignatureTools.place(ink, w, h, png)
        ink.recycle()
        val bytes: ByteArray
        val fits: Boolean
        if (png) {
          bytes = DocTools.png(placed)
          placed.recycle()
          fits = maxBytes == null || bytes.size <= maxBytes
        } else {
          val fit = DocTools.fitPicture(placed, maxBytes, true)
          bytes = fit.bytes
          fits = fit.fits
        }
        val name = nameFor(base, if (png) "png" else "jpg")
        val out = FileNames.outputFile(ctx, name)
        out.writeBytes(bytes)
        SplitItemResult(item, listOf(ReadyFile(out, name, if (png) "image/png" else "image/jpeg", item.maxKb, exactSize = true, itemIndex = index)), bytes.size.toLong(), fits, false, more)
      }
    }
  }

  /** The cut part of [page] as a picture of its own ([page] is used up); no cut = the whole page */
  private fun cutOut(page: Bitmap, cut: NormBox?): Bitmap {
    if (cut == null) return page
    val piece = DocTools.cut(page, cut.left, cut.top, cut.width, cut.height)
    if (piece === page) return page
    page.recycle()
    return DocTools.mutable(piece)
  }

  fun allFiles(): List<ReadyFile> = results?.flatMap { it.files } ?: emptyList()

  companion object {
    /** The long side a page is rendered at when a photo / signature is cut from it */
    const val CUT_SIDE = 3500
  }
}

/** The split tool's screen part (inside PDF tools) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitTool(
  split: SplitState,
  pages: List<PdfPage>,
  addFiles: () -> Unit,
  saveAll: () -> Unit,
  outerBusy: Boolean = false,
  onNewPreset: () -> Unit = {},
  onStartAgain: () -> Unit = {},
  onEdit: (Int) -> Unit = {},
  duplicates: Duplicates<PdfPage>? = null,
) {
  // nothing is started while pages are still being opened or made
  val idle = split.busy == null && !outerBusy
  val presets by LocalContext.current.docReady.presets.presets.collectAsState()
  val cropping = split.cropping
  val cropImage = split.cropImage
  if (cropping != null && cropImage != null) {
    val item = split.preset?.items?.getOrNull(cropping)
    Text(
      if (item?.kind == ItemKind.PHOTO) "Put the box on the photo (it keeps the photo's shape)." else "Put the box closely around the signature.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 12.dp),
    )
    VSpace(8)
    CropBox(cropImage, split.cropBox, { split.cropBox = it }, fixedRatio = split.cropRatio(), maxHeight = 460)
    VSpace(8)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { split.applyCrop() }, enabled = idle) { Text("Use this part") }
      OutlinedButton(onClick = { split.cancelCrop() }) { Text("Cancel") }
    }
    return
  }

  Text(
    "One PDF with all the papers? Give each page to what the form asks for (photo, signature, " +
      "certificates…), then make every file at once, each in its own size.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 12.dp),
  )
  VSpace(12)
  if (pages.isEmpty()) {
    Button(onClick = addFiles, enabled = idle, modifier = Modifier.fillMaxWidth().height(56.dp)) {
      Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text("Choose PDF or photos")
    }
    Text(
      "Up to ${PdfTools.PAGE_LIMIT} pages. You can add more files after.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp),
    )
    return
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    OutlinedButton(onClick = addFiles, enabled = idle) {
      Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Add more")
    }
    TextButton(onClick = onStartAgain, enabled = idle) { Text("Start again") }
  }

  SectionLabel("1 · Choose the form's preset")
  if (presets.isEmpty()) {
    NoPresetsCard(onNewPreset)
    SplitPages(split, pages, null, onEdit, idle, duplicates)
    return
  }
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    for (p in presets) FilterChip(selected = split.preset?.id == p.id, onClick = { split.choosePreset(p) }, label = { Text(p.name) })
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      "Each paper of the form (SSC certificate, NID…) is an item of a preset.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onNewPreset) { Text("+ New preset") }
  }
  val preset = split.preset
  if (preset == null) {
    SplitHint("Choose a preset above, then give the pages below to its items.")
    SplitPages(split, pages, null, onEdit, idle, duplicates)
    return
  }

  SectionLabel("2 · Select pages, then give them to")
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    preset.items.forEachIndexed { i, item ->
      val n = split.pagesOf(i, pages).size
      FilterChip(
        selected = split.target == i,
        onClick = { split.target = i },
        label = { Text(if (n == 0) "${item.title} · missing" else "${item.title} · $n") },
      )
    }
  }
  VSpace(8)
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { split.assignSelected() }, enabled = idle && split.selected.isNotEmpty()) {
      Text("Give ${split.selected.size} to ${preset.items.getOrNull(split.target)?.title ?: ""}")
    }
    OutlinedButton(onClick = { split.unassignSelected() }, enabled = idle && split.selected.isNotEmpty()) { Text("Not used") }
    TextButton(onClick = { if (split.selected.isEmpty()) split.selectAll(pages) else split.selected.clear() }) {
      Text(if (split.selected.isEmpty()) "Select all" else "Clear")
    }
  }

  if (split.selected.isEmpty()) SplitHint("Tap pages below to select them.")
  // suggestions: shown on the pages, given only with ✓ (or all at once here)
  split.suggesting?.let { BusyRow("Suggesting items · $it") }
  val suggestions = pages.count { it in split.suggested }
  if (suggestions > 0) {
    OutlinedButton(onClick = { split.acceptAll(pages) }, enabled = idle, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
      Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Accept all suggestions ($suggestions)")
    }
  }
  SplitPages(split, pages, preset, onEdit, idle, duplicates)

  // photos and signatures are often a small part of a scanned page: cut them out
  val cutItems = preset.items.withIndex().filter { (i, it) -> it.kind != ItemKind.DOCUMENT && split.pagesOf(i, pages).isNotEmpty() }
  if (cutItems.isNotEmpty()) {
    SectionLabel("Cut from the page")
    for ((i, item) in cutItems) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(item.title, fontWeight = FontWeight.SemiBold)
          Text(
            if (split.crops[i] != null) "A part of the page is cut" else if (item.kind == ItemKind.PHOTO) "Whole page (the face is found)" else "Whole page",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedButton(onClick = { split.startCrop(i, pages) }, enabled = idle) { Text(if (split.crops[i] == null) "Cut" else "Change") }
        if (split.crops[i] != null) TextButton(onClick = { split.clearCrop(i) }) { Text("Whole") }
      }
    }
  }

  VSpace(14)
  Button(onClick = { split.makeAll(pages) }, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Make all") }
  if (split.busy != null) BusyRow(split.busy ?: "")
  ErrorText(split.error)

  val results = split.results
  if (results != null) {
    SectionLabel("Files")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (r in results) SplitResultCard(r)
    }
    if (results.any { it.files.isNotEmpty() }) {
      VSpace(12)
      Button(onClick = saveAll, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Save or share all") }
      SaveToStudent(split)
    }
  }
}

@Composable
private fun SplitResultCard(r: SplitItemResult) {
  val title = r.item.title
  when {
    r.missing -> ResultCard(false, "$title: missing", "No page was given to it. " + r.item.summary())
    r.files.isEmpty() -> ResultCard(false, "$title: not made", r.note ?: "This file could not be made.")
    else -> {
      val names = r.files.joinToString(", ") { it.name }
      val limit = r.item.maxKb?.let { " (max $it KB)" } ?: ""
      ResultCard(
        r.fits,
        if (r.fits) "$title ✓" else "$title: over the limit",
        "$names · ${bytesText(r.bytes)}$limit" + (r.note?.let { "\n$it" } ?: ""),
      )
    }
  }
  if (!r.missing && r.files.isNotEmpty() && !r.fits) {
    Text(
      "Even the lowest readable quality is too heavy. Check the limit, or give it fewer pages.",
      style = MaterialTheme.typography.bodySmall,
      color = DocReadyColors.extra.onWarningContainer,
    )
  }
}

@Composable
private fun SplitHint(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 8.dp),
  )
}

/** Every page as a tile: tap to select; under it, the item it was given to */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitPages(split: SplitState, pages: List<PdfPage>, preset: Preset?, onEdit: (Int) -> Unit, idle: Boolean, duplicates: Duplicates<PdfPage>? = null) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    SectionLabel("Pages (${pages.size}) · tap to select", Modifier.weight(1f))
    TextButton(onClick = { onEdit(0) }, enabled = idle) { Text("Edit all pages") }
  }
  duplicates?.let { DuplicatesBar(it, enabled = idle) }
  val sameAs = duplicates?.sameAs ?: emptyList()
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    pages.forEachIndexed { n, page ->
      val isSelected = page in split.selected
      val item = split.assigned[page]?.let { preset?.items?.getOrNull(it) }
      Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
          onClick = { split.toggle(page) },
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(
            if (isSelected) 3.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
          ),
          modifier = Modifier.size(width = 84.dp, height = 112.dp),
        ) {
          (split.thumbs[page] ?: page.thumb)?.let {
            Image(it, contentDescription = "Page ${n + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("${n + 1}", style = MaterialTheme.typography.labelSmall)
          // the pencil: the page editor, before or after giving the page
          IconButton(onClick = { onEdit(n) }, enabled = idle, modifier = Modifier.size(32.dp)) {
            Icon(AppIcons.Edit, contentDescription = "Edit page ${n + 1}", modifier = Modifier.size(16.dp))
          }
        }
        Text(
          item?.title ?: "not used",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = if (item != null) FontWeight.SemiBold else FontWeight.Normal,
          color = if (item != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
        )
        split.recipeFor(page)?.beautyBadge()?.let { BeautyBadge(it) }
        sameAs.getOrNull(n)?.let { DuplicateBadge(it) }
        val hint = if (item == null) split.suggested[page]?.let { preset?.items?.getOrNull(it) } else null
        if (hint != null) SuggestionBadge(hint.title, onAccept = { split.accept(page) }, onReject = { split.reject(page) }, enabled = idle)
      }
    }
  }
}

/** Split's files kept for a student (each as its preset item, so the student's checklist fills in) */
@Composable
private fun SaveToStudent(split: SplitState) {
  val context = LocalContext.current
  val app = context.docReady
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var picking by androidx.compose.runtime.remember { mutableStateOf(false) }
  var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
  OutlinedButton(onClick = { picking = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Text(if (busy) "Saving…" else "Save to a student…")
  }
  if (picking) {
    StudentPickerDialog(
      onPick = { s ->
        picking = false
        busy = true
        scope.launch {
          val r = try {
            app.students.attach(s.id, split.allFiles(), split.preset)
          } catch (e: Throwable) {
            null.also {
              android.widget.Toast.makeText(context, "Could not save to ${s.name}: ${e.message ?: "a file is no longer there"}. Make all again.", android.widget.Toast.LENGTH_LONG).show()
            }
          }
          busy = false
          if (r == null) return@launch
          split.onSaved()
          android.widget.Toast.makeText(
            context,
            if (r is com.docready.aamunim.save.SaveOutcome.Failed) "Kept for ${s.name}; Downloads: ${r.message}" else "Saved to ${s.name}",
            android.widget.Toast.LENGTH_LONG,
          ).show()
        }
      },
      onDismiss = { picking = false },
    )
  }
}

/** A suggested item on a page: its name, ✓ to give the page to it, ✕ to say it is not */
@Composable
private fun SuggestionBadge(title: String, onAccept: () -> Unit, onReject: () -> Unit, enabled: Boolean) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = Modifier.padding(top = 2.dp),
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 3.dp)) {
      Text(
        "$title?",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
      Row {
        IconButton(onClick = onAccept, enabled = enabled, modifier = Modifier.size(34.dp)) {
          Icon(AppIcons.Check, contentDescription = "Give this page to $title", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onReject, enabled = enabled, modifier = Modifier.size(34.dp)) {
          Icon(AppIcons.Close, contentDescription = "Not $title", modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}
