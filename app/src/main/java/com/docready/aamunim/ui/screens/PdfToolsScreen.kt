package com.docready.aamunim.ui.screens

import com.docready.aamunim.data.Draft
import com.docready.aamunim.data.DraftCut
import com.docready.aamunim.data.DraftHost
import com.docready.aamunim.data.DraftPage
import com.docready.aamunim.data.DraftRepository
import com.docready.aamunim.data.model.title
import com.docready.aamunim.ui.components.DraftOffer
import com.docready.aamunim.ui.components.DuplicateBadge
import com.docready.aamunim.ui.components.Duplicates
import com.docready.aamunim.ui.components.DuplicatesBar
import com.docready.aamunim.ui.components.FactChips
import com.docready.aamunim.ui.components.SearchableSwitch
import com.docready.aamunim.imaging.Fact
import com.docready.aamunim.imaging.Ocr
import com.docready.aamunim.imaging.OcrWord
import com.docready.aamunim.ui.components.NormBox
import com.docready.aamunim.docReady
import kotlinx.coroutines.delay
import com.docready.aamunim.ui.components.ScanAnimationCard
import com.docready.aamunim.ui.components.ScanAnim
import com.docready.aamunim.data.model.beautyBadge
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.imaging.PageRenderer
import com.docready.aamunim.ui.editor.EditorMode
import com.docready.aamunim.ui.editor.EditorPage
import com.docready.aamunim.ui.editor.PageEditorScreen
import com.docready.aamunim.ui.editor.PageEditorState
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.PdfTools
import com.docready.aamunim.imaging.Scanner
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.intake.IntakeTarget
import com.docready.aamunim.intake.IncomingFile
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.ui.components.animationsEnabled
import com.docready.aamunim.ui.components.BeautyBadge
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.NotesList
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.components.SaveSheet
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import com.docready.aamunim.ui.components.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PdfMode(val title: String) {
  MERGE("Merge & reorder"),
  COMPRESS("Compress"),
  ID_CARD("NID on one A4"),
  SPLIT("Split by preset"),
}

/**
 * One page in the list: a PDF's page or a picture. Only its small picture is made when it is
 * added; the full page (A4 at 150 dpi, a JPEG work file) is made from [source] when needed, so a
 * 150-page PDF opens quickly and uses little memory and disk.
 * [sourceBytes]: its share of the added file's weight (for "was ... → now ...").
 */
class PdfPage(
  val thumb: ImageBitmap?,
  val from: String,
  val sourceBytes: Long = 0L,
  /** The file it came from (a PDF, or the photo itself) */
  val source: File? = null,
  /** Its page number in [source] when that is a PDF; -1 = a photo */
  val pdfPage: Int = -1,
  kept: File? = null,
) {
  @Volatile
  private var keptFile: File? = kept

  /** The full page as a picture file, made the first time it is asked for (blocking) */
  fun file(context: Context): File {
    keptFile?.let { if (it.exists()) return it }
    synchronized(this) {
      keptFile?.let { if (it.exists()) return it }
      val src = source?.takeIf { it.exists() }
        ?: throw IllegalStateException("A page is no longer on the phone (files are kept for a day): add it again.")
      val f = if (pdfPage >= 0) {
        PdfTools.renderPageFile(context, src, pdfPage)
      } else {
        DocTools.keepPage(context, DocTools.load(context, Uri.fromFile(src), PdfTools.PAGE_LONG_SIDE))
      }
      keptFile = f
      return f
    }
  }

  /**
   * The page's source at [maxSide] (blocking): a PDF page rendered from its PDF, a photo loaded
   * from itself, else the kept page. Never changed: edits are applied to a copy.
   */
  fun loadSource(context: Context, maxSide: Int): Bitmap {
    val src = source?.takeIf { it.exists() }
    return when {
      src != null && pdfPage >= 0 -> PdfTools.renderPage(src, pdfPage, maxSide)
      src != null -> DocTools.load(context, Uri.fromFile(src), maxSide)
      else -> DocTools.load(context, Uri.fromFile(file(context)), maxSide)
    }
  }

  /** The page made from its source with [recipe], its longer side at most [maxSide] (blocking) */
  fun rendered(context: Context, recipe: PageRecipe, maxSide: Int): Bitmap {
    // straightening from corners wants more detail than the page it gives
    val side = if (recipe.quad != null) maxOf(maxSide, 2600) else maxSide
    return DocTools.scaleToMax(PageRenderer.render(loadSource(context, side), recipe, maxSide), maxSide)
  }
}

/** A small picture for the page lists (16-bit colour: 150 of them stay small) */
private fun listThumb(b: Bitmap): ImageBitmap {
  val small = DocTools.scaleToMax(b, 200)
  val lean = small.copy(Bitmap.Config.RGB_565, false)
  small.recycle()
  return lean.asImageBitmap()
}

class PdfToolsViewModel(
  app: Application,
  private val startMode: PdfMode = PdfMode.MERGE,
  private val presetId: String? = null,
  private val itemIndex: Int = -1,
  private val studentId: String? = null,
  resume: Boolean = false,
) : AndroidViewModel(app) {
  /** A preset's document item this PDF is made for (from a student's checklist), or null */
  val preset = app.docReady.presets.get(presetId)
  private val forItem = preset?.items?.getOrNull(itemIndex)?.takeIf { it.kind == com.docready.aamunim.data.model.ItemKind.DOCUMENT }
  val itemPos: Int? = if (forItem != null) itemIndex else null

  private val ctx: Application get() = getApplication()

  var mode by mutableStateOf(startMode)
  val pages = mutableStateListOf<PdfPage>()
  var maxKbText by mutableStateOf(forItem?.maxKb?.toString() ?: "")

  /** A searchable PDF: the pages' English text (OCR) as an invisible layer (merge / compress) */
  var searchable by mutableStateOf(false)

  /** What the pages say about the student (name, roll…), offered as chips after making */
  var facts by mutableStateOf<List<Fact>>(emptyList())
    private set
  var busy by mutableStateOf<String?>(null)
  var result by mutableStateOf<FilesResult?>(null)
  var error by mutableStateOf<String?>(null)
  val notes = mutableStateListOf<String>()

  private var firstName: String? = null

  /** The one PDF added, while its pages are as they came (compress may then keep it as it is) */
  private var singlePdf: File? = null
  private var filesAdded = 0
  private var lastMakeNote: String? = null
  private var pagesChanged = false

  // NID: both sides of the card
  var front by mutableStateOf<PdfPage?>(null)
  var back by mutableStateOf<PdfPage?>(null)
  var actualSize by mutableStateOf(true)

  fun addUris(uris: List<Uri>) {
    val items = uris.map { u ->
      val type = ctx.contentResolver.getType(u) ?: ""
      val name = FileNames.displayName(ctx, u) ?: "file"
      Triple(u, if (type == "application/pdf" || name.lowercase().endsWith(".pdf")) "application/pdf" else type, name)
    }
    add(items.map { (u, mime, name) -> { FileNames.copyIn(ctx, u, if (mime == "application/pdf") "pdf" else "img") } to (mime to name) })
  }

  /** Split by preset: the pages given out to a preset's items */
  /** A photo being cleaned as it is added (the scanning animation) */
  var anim by mutableStateOf<ScanAnim?>(null)
    private set
  private var animKey = 0

  /** Each page's edits (the page editor), shared with Split by preset */
  val recipes = mutableStateMapOf<PdfPage, PageRecipe>()

  /** Small pictures of edited pages */
  val thumbs = mutableStateMapOf<PdfPage, ImageBitmap>()

  /** A page was edited by hand (compress then never hands back the untouched original) */
  private var userEdited = false

  val split = SplitState(app, viewModelScope, recipes, thumbs) { pages.toList() }

  // ---- the shared page editor
  var editor by mutableStateOf<PageEditorState?>(null)
    private set

  fun openEditor(start: Int) {
    val list = pages.toList()
    if (list.isEmpty()) return
    editor = PageEditorState(
      viewModelScope,
      list.mapIndexed { i, p ->
        EditorPage("Page ${i + 1} · ${p.from}", recipes[p] ?: PageRecipe.Plain) { side -> p.loadSource(ctx, side) }
      },
      start,
      EditorMode.DOCUMENT,
      onDone = { done -> applyRecipes(list, done) },
      onClose = { editor = null },
      animate = animationsEnabled(ctx),
    )
  }

  private fun applyRecipes(list: List<PdfPage>, done: List<PageRecipe>) {
    editor = null
    val changed = list.indices.filter { (recipes[list[it]] ?: PageRecipe.Plain) != done[it] }.map { list[it] }
    if (changed.isEmpty()) return
    for (p in changed) recipes[p] = done[list.indexOf(p)]
    userEdited = true
    result = null
    split.onRecipesChanged(changed)
    viewModelScope.launch {
      busy = "Updating the pages…"
      try {
        for (p in changed) {
          val r = recipes[p] ?: continue
          thumbs[p] = withContext(Dispatchers.Default) { listThumb(p.rendered(ctx, r, 400)) }
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The pages could not be updated.")
      } finally {
        busy = null
      }
    }
  }

  /** Several pages came in by Share / Open with: offer to split them by preset */
  var suggestSplit by mutableStateOf(false)

  fun addIncoming(files: List<IncomingFile>) {
    add(files.map { f -> { f.file } to (f.mime to f.name) }, fromShare = true)
  }

  /**
   * Each: how to get the file, (its kind, its name). Pages appear one by one as their small
   * pictures are made ("Reading page X of N"); a file that can't be opened is named on screen
   * and the others are still added.
   */
  private fun add(items: List<Pair<() -> File, Pair<String, String>>>, fromShare: Boolean = false) {
    if (items.isEmpty()) return
    viewModelScope.launch {
      busy = "Opening the files…"
      error = null
      result = null
      val before = pages.size
      val problems = mutableListOf<String>()
      try {
        for ((index, item) in items.withIndex()) {
          val (fileOf, info) = item
          val (mime, name) = info
          val pagesBefore = pages.size
          try {
            val f = withContext(Dispatchers.IO) { fileOf() }
            if (mime == "application/pdf") {
              val total = withContext(Dispatchers.Default) {
                PdfTools.renderThumbs(f, PdfTools.PAGE_LIMIT, 200) { n, total, thumb ->
                  val shown = thumb.asImageBitmap()
                  withContext(Dispatchers.Main) {
                    val page = PdfPage(shown, name, f.length() / maxOf(1, total), f, n)
                    // a PDF's page stays as it was made (it is not a photo of paper)
                    recipes[page] = PageRecipe.Plain
                    pages.add(page)
                    busy = "Reading page ${n + 1} of ${minOf(total, PdfTools.PAGE_LIMIT)}…"
                  }
                }
              }
              if (total == 0) {
                problems.add("$name has no pages.")
              } else {
                if (total > PdfTools.PAGE_LIMIT) {
                  notes.add("$name has $total pages: only the first ${PdfTools.PAGE_LIMIT} are used.")
                }
                filesAdded++
                singlePdf = if (filesAdded == 1 && total <= PdfTools.PAGE_LIMIT) f else null
              }
            } else {
              // the tile shows the photo as it will come out (shadows removed, colour evened)
              // the scanning animation sweeps over the photo while it is cleaned (Magic)
              val raw = withContext(Dispatchers.Default) { DocTools.load(ctx, Uri.fromFile(f), 900).asImageBitmap() }
              anim = ScanAnim(animKey++, raw, null, "Cleaning $name…")
              // fingers holding the paper: painted out by default where found (never over a face)
              val start = withContext(Dispatchers.Default) {
                val found = try {
                  PageRenderer.findFingers(DocTools.load(ctx, Uri.fromFile(f), 1600), PageRecipe.Document).isNotEmpty()
                } catch (_: Throwable) {
                  false
                }
                if (found) PageRecipe.Document.copy(fingers = true) else PageRecipe.Document
              }
              val (shown, cleaned) = withContext(Dispatchers.Default) {
                val c = PageRenderer.render(DocTools.load(ctx, Uri.fromFile(f), 900), start)
                listThumb(c.copy(Bitmap.Config.ARGB_8888, false)) to c.asImageBitmap()
              }
              anim = anim?.copy(after = cleaned, done = true, label = "$name ready")
              // (only a few photos, or the last one, pause to show the result)
              if (animationsEnabled(ctx) && (items.size <= 3 || index == items.size - 1)) delay(600)
              val page = PdfPage(shown, name, f.length(), f, -1)
              // a photo of paper: shadows removed, colour evened (changeable in the editor)
              recipes[page] = start
              pages.add(page)
              filesAdded++
              singlePdf = null
            }
            if (firstName == null) firstName = name.substringBeforeLast('.')
          } catch (e: Throwable) {
            // (pages of a PDF that failed half way stay out)
            while (pages.size > pagesBefore) pages.removeAt(pages.size - 1)
            problems.add("$name: ${messageOf(e, "it could not be opened.")}")
          }
        }
      } finally {
        busy = null
        anim = null
      }
      val added = if (pages.size > before) pages.subList(before, pages.size).toList() else emptyList()
      if (problems.isNotEmpty()) error = problems.joinToString("\n")
      if (added.isEmpty() && problems.isEmpty()) error = "Nothing was added: choose a PDF or photos."
      split.onPagesChanged(pages)
      if (fromShare && added.size > 1 && mode != PdfMode.SPLIT) suggestSplit = true
      if (mode == PdfMode.ID_CARD) {
        // pictures handed to the NID tool fill the empty sides
        for (p in added) {
          if (front == null) front = p else if (back == null) back = p
        }
      }
    }
  }

  /** Pages that look like an earlier page (the same paper added twice) */
  val duplicates = Duplicates(
    viewModelScope,
    pages,
    { it.thumb },
    changed = {
      pagesChanged = true
      result = null
      split.onPagesChanged(pages)
    },
    // Undo gives the pages back their items and cuts in Split by preset
    keep = { gone -> split.keepFor(gone) },
  )

  fun move(index: Int, by: Int) {
    val to = index + by
    if (index !in pages.indices || to !in pages.indices) return
    val p = pages.removeAt(index)
    pages.add(to, p)
    duplicates.forgetUndo()
    pagesChanged = true
    result = null
    split.onPagesChanged(pages)
  }

  fun remove(index: Int) {
    if (index in pages.indices) pages.removeAt(index)
    duplicates.forgetUndo()
    pagesChanged = true
    result = null
    split.onPagesChanged(pages)
  }

  fun clear() {
    pages.clear()
    duplicates.forgetUndo()
    recipes.clear()
    thumbs.clear()
    userEdited = false
    notes.clear()
    firstName = null
    singlePdf = null
    filesAdded = 0
    lastMakeNote = null
    pagesChanged = false
    front = null
    back = null
    result = null
    error = null
    split.onPagesChanged(pages)
  }

  /** The scanner's two pictures: front, then back */
  fun setIdFromScan(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      busy = "Reading the card…"
      try {
        val sides = withContext(Dispatchers.IO) {
          uris.take(2).map { u ->
            val f = FileNames.copyIn(ctx, u, "jpg")
            PdfPage(listThumb(DocTools.load(ctx, Uri.fromFile(f), 400)), "scan", kept = f)
          }
        }
        front = sides.getOrNull(0)
        back = sides.getOrNull(1) ?: back
        result = null
      } catch (e: Throwable) {
        error = messageOf(e, "The card could not be read.")
      } finally {
        busy = null
      }
    }
  }

  fun setSide(isFront: Boolean, uri: Uri) {
    viewModelScope.launch {
      try {
        val side = withContext(Dispatchers.IO) {
          val f = FileNames.copyIn(ctx, uri, "img")
          val b = DocTools.load(ctx, Uri.fromFile(f), 1600)
          val p = DocTools.keepPage(ctx, b)
          PdfPage(listThumb(DocTools.load(ctx, Uri.fromFile(p), 400)), "picture", kept = p)
        }
        if (isFront) front = side else back = side
        result = null
      } catch (e: Throwable) {
        error = messageOf(e, "The picture could not be opened.")
      }
    }
  }

  fun make() {
    val kb = maxKbText.toIntOrNull()
    if (mode == PdfMode.COMPRESS && kb == null) {
      error = "Write the size the PDF must be under (in KB)."
      return
    }
    if (kb != null && kb !in 10..50_000) {
      error = "Max KB: 10 to 50000."
      return
    }
    val m = mode
    val chosen = pages.toList()
    val f = front
    val b = back
    if (m == PdfMode.ID_CARD && (f == null || b == null)) {
      error = "Add both sides of the card first."
      return
    }
    if (m != PdfMode.ID_CARD && chosen.isEmpty()) {
      error = "Add a PDF or photos first."
      return
    }
    val actual = actualSize
    val before = pages.sumOf { it.sourceBytes }.takeIf { m == PdfMode.COMPRESS && it > 0 }
    // the PDF exactly as it was added (no page moved or removed): never made heavier
    val original = singlePdf?.takeIf { m == PdfMode.COMPRESS && !pagesChanged && !userEdited }
    val edits = chosen.map { recipes[it] ?: PageRecipe.Plain }
    val base = FileNames.safeBase(firstName ?: "document")
    val ocr = searchable && m != PdfMode.ID_CARD
    val texts = ArrayList<String>()
    viewModelScope.launch {
      busy = "Making the PDF…"
      error = null
      facts = emptyList()
      try {
        result = withContext(Dispatchers.Default) {
          val maxBytes = kb?.let { it * 1024L }
          val sourcePages: List<File>
          val name: String
          val words = if (ocr) ArrayList<List<OcrWord>>() else null
          if (m == PdfMode.ID_CARD && f != null && b != null) {
            val fb = DocTools.load(ctx, Uri.fromFile(f.file(ctx)), 1600)
            val bb = DocTools.load(ctx, Uri.fromFile(b.file(ctx)), 1600)
            val page: Bitmap = PdfTools.idCardPage(fb, bb, actual)
            fb.recycle()
            bb.recycle()
            sourcePages = listOf(DocTools.keepPage(ctx, page))
            name = "nid_both_sides.pdf"
          } else {
            // (a searchable PDF is always made again: the original has no text layer of this app's)
            val keepAsIs = original != null && maxBytes != null && original.length() <= maxBytes && !ocr
            // the full pages are made now, one at a time (not when the PDF was opened)
            sourcePages = if (keepAsIs) {
              emptyList()
            } else {
              chosen.mapIndexed { i, p ->
                withContext(Dispatchers.Main) { busy = "Preparing page ${i + 1} of ${chosen.size}…" }
                // the page made from its source with its edits, kept as a work file
                // full detail (smaller only if a KB limit needs it; long documents a little smaller)
                val sides = if (chosen.size <= 30) {
                  listOf(PdfTools.DOC_LONG_SIDE, 2400, PdfTools.PAGE_LONG_SIDE)
                } else {
                  listOf(PdfTools.LONG_DOC_SIDE, PdfTools.PAGE_LONG_SIDE)
                }
                val b = PdfTools.atBestSize(sides) { side -> p.rendered(ctx, edits[i], side) }
                if (words != null) {
                  withContext(Dispatchers.Main) { busy = "Reading the text of page ${i + 1} of ${chosen.size}…" }
                  val read = Ocr.read(b)
                  words.add(read.words)
                  if (texts.size < 3) texts.add(read.text)
                }
                DocTools.keepPage(ctx, b)
              }
            }
            withContext(Dispatchers.Main) { busy = "Making the PDF…" }
            name = if (m == PdfMode.COMPRESS) "${base}_compressed.pdf" else "merged_${chosen.size}_pages.pdf"
          }
          val out = FileNames.outputFile(ctx, name)
          var notesOut: String? = null
          val size: Long
          val fits: Boolean
          val previewImg = sourcePages.firstOrNull()?.let { DocTools.thumbnail(it, 600)?.asImageBitmap() } ?: chosen.firstOrNull()?.thumb
          try {
            if (original != null && maxBytes != null && original.length() <= maxBytes && !ocr) {
              // already small enough: kept as it is (text stays text)
              original.copyTo(out, overwrite = true)
              size = out.length()
              fits = true
              notesOut = "This PDF is already under ${kb} KB: it is kept as it was."
            } else {
              val (written, _, ok) = DocTools.fitPdf(sourcePages, maxBytes, out, words)
              if (original != null && written >= original.length() && !ocr) {
                // pictures would only make it heavier: the original is kept
                original.copyTo(out, overwrite = true)
                size = out.length()
                fits = maxBytes == null || size <= maxBytes
                notesOut = "Saving the pages again would not make this PDF smaller: it is kept as it was."
              } else {
                size = written
                fits = ok
              }
            }
          } finally {
            // the full-quality page pictures are in the PDF now: not kept on the phone
            sourcePages.forEach { it.delete() }
          }
          withContext(Dispatchers.Main) {
            // only the latest run's "kept as it was" note is shown
            lastMakeNote?.let { notes.remove(it) }
            lastMakeNote = notesOut
            notesOut?.let { n -> notes.add(n) }
          }
          FilesResult(
            listOf(ReadyFile(out, name, "application/pdf", kb, itemIndex = itemPos)),
            previewImg,
            size, size, kb, fits, if (m == PdfMode.ID_CARD) 1 else chosen.size, OutputFormat.PDF, before,
          )
        }
        // made for a student: what the first page says about them (offered, never filled in)
        if (studentId != null && m != PdfMode.ID_CARD && chosen.isNotEmpty()) {
          if (texts.isEmpty()) busy = "Looking for the name and numbers…"
          facts = Ocr.facts(texts) { chosen[0].rendered(ctx, edits[0], Ocr.MAX_SIDE) }
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The PDF could not be made.")
      } finally {
        busy = null
      }
    }
  }

  // ---- autosave: "Continue where you left off?" (PDF tools and Split by preset each keep one)
  private val draftTool = if (startMode == PdfMode.SPLIT) "split" else "pdf"
  private val draftTitle = listOfNotNull(
    if (startMode == PdfMode.SPLIT) "Split by preset" else "PDF tools",
    forItem?.title,
    app.docReady.students.get(studentId)?.name?.let { "for $it" },
  ).joinToString(" · ")

  val draft = DraftHost(viewModelScope, app.docReady.drafts, DraftRepository.keyOf(draftTool, presetId, itemIndex, studentId), { snapshot() }) { d -> restore(d) }

  private fun snapshot(): Draft? {
    val list = pages.filter { it.source != null }
    if (list.isEmpty()) return null
    val splitPreset = split.preset
    val title = if (draftTool == "split" && splitPreset != null) "$draftTitle · ${splitPreset.name}" else draftTitle
    return Draft(
      draft.key, draftTool, title, presetId, itemIndex, studentId,
      list.map { p ->
        DraftPage(
          p.source!!.path,
          pdfPage = p.pdfPage,
          from = p.from,
          sourceBytes = p.sourceBytes,
          recipe = recipes[p] ?: PageRecipe.Plain,
          item = split.assigned[p],
        )
      },
      listOfNotNull(
        "mode" to (if (mode == PdfMode.ID_CARD) PdfMode.MERGE else mode).name,
        "maxKb" to maxKbText,
        "searchable" to searchable.toString(),
        splitPreset?.let { "splitPreset" to it.id },
        firstName?.let { "firstName" to it },
      ).toMap(),
      split.crops.map { (i, b) -> DraftCut(i, listOf(b.left, b.top, b.right, b.bottom)) },
    )
  }

  private suspend fun restore(d: Draft): Boolean {
    val v = d.values
    v["mode"]?.let { m -> PdfMode.entries.firstOrNull { it.name == m }?.let { mode = it } }
    v["maxKb"]?.let { maxKbText = it }
    v["searchable"]?.toBooleanStrictOrNull()?.let { searchable = it }
    v["firstName"]?.let { if (firstName == null) firstName = it }
    busy = "Opening your pages…"
    try {
      val back = mutableListOf<Pair<PdfPage, DraftPage>>()
      for ((n, dp) in d.pages.withIndex()) {
        if (d.pages.size > 3) busy = "Opening your pages (${n + 1} of ${d.pages.size})…"
        val src = File(dp.file)
        val page = withContext(Dispatchers.Default) {
          val bare = PdfPage(null, dp.from, dp.sourceBytes, src, dp.pdfPage)
          PdfPage(listThumb(bare.rendered(ctx, dp.recipe, 400)), dp.from, dp.sourceBytes, src, dp.pdfPage)
        }
        recipes[page] = dp.recipe
        back.add(page to dp)
      }
      pages.addAll(0, back.map { it.first })
      filesAdded += back.map { it.second.file }.distinct().size
      singlePdf = null
      result = null
      val splitPreset = app().docReady.presets.get(v["splitPreset"])
      split.restore(
        splitPreset,
        back.mapNotNull { (p, dp) -> dp.item?.let { p to it } }.toMap(),
        d.cuts.filter { it.box.size == 4 }.associate { it.item to NormBox(it.box[0], it.box[1], it.box[2], it.box[3]) },
        pages,
      )
      split.onPagesChanged(pages)
      return true
    } catch (e: Throwable) {
      error = messageOf(e, "The unfinished pages could not be opened.") + " The draft is kept as it was."
      return false
    } finally {
      busy = null
    }
  }

  override fun onCleared() {
    draft.flush()
    super.onCleared()
  }

  private fun app(): Application = getApplication()

  init {
    split.onSaved = { draft.done() }
    draft.begin(resume)
  }
}

@Composable
fun PdfToolsScreen(
  onBack: () -> Unit,
  startMode: PdfMode = PdfMode.MERGE,
  onNewPreset: () -> Unit = {},
  presetId: String? = null,
  itemIndex: Int = -1,
  studentId: String? = null,
  resume: Boolean = false,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val vm: PdfToolsViewModel = viewModel { PdfToolsViewModel(app, startMode, presetId, itemIndex, studentId, resume) }
  // the shared page editor takes the whole screen while it is open
  vm.editor?.let {
    PageEditorScreen(it)
    return
  }
  var saving by rememberSaveable { mutableStateOf(false) }
  var savingSplit by rememberSaveable { mutableStateOf(false) }
  val intake by Intake.request.collectAsState()
  LaunchedEffect(intake) {
    // (the split screen and the PDF tools screen are separate entries: each takes its own)
    val mine = if (startMode == PdfMode.SPLIT) IntakeTarget.SPLIT else IntakeTarget.PDF_TOOLS
    if (intake?.target == mine) vm.addIncoming(Intake.take(mine))
  }
  val openDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    vm.addUris(uris)
  }
  val pickFront = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.setSide(true, uri) }
  val pickBack = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.setSide(false, uri) }
  val scanCard = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
    if (res.resultCode == Activity.RESULT_OK) {
      vm.setIdFromScan(
        try {
          Scanner.pageUris(res.data)
        } catch (_: Exception) {
          emptyList()
        },
      )
    }
  }
  val addFiles = {
    try {
      openDocs.launch(arrayOf("application/pdf", "image/*"))
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, "No file picker found on this phone.", Toast.LENGTH_LONG).show()
    }
  }
  val startCardScan = {
    val activity = context.findActivity()
    if (activity != null) {
      Scanner.startIntent(activity, 2, gallery = true)
        .addOnSuccessListener { sender -> scanCard.launch(IntentSenderRequest.Builder(sender).build()) }
        .addOnFailureListener { e ->
          Toast.makeText(context, e.message ?: "The scanner is not available on this phone (Google Play services).", Toast.LENGTH_LONG).show()
        }
    }
  }

  DraftOffer(vm.draft)
  Scaffold(
    topBar = {
      if (vm.mode == PdfMode.SPLIT) {
        DocTopBar("Split by preset", onBack, subtitle = "One PDF → each paper the form asks for")
      } else {
        DocTopBar("PDF tools", onBack, subtitle = "Merge, compress, NID")
      }
    },
    bottomBar = {
      val canMake = when (vm.mode) {
        PdfMode.ID_CARD -> vm.front != null && vm.back != null
        PdfMode.SPLIT -> false // (its own "Make all")
        else -> vm.pages.isNotEmpty()
      }
      if (canMake) {
        BottomActions {
          if (vm.result == null) {
            Button(onClick = { vm.make() }, enabled = vm.busy == null, modifier = Modifier.weight(1f).height(52.dp)) {
              Text(if (vm.mode == PdfMode.COMPRESS) "Compress" else "Make PDF")
            }
          } else {
            OutlinedButton(onClick = { vm.result = null }, enabled = vm.busy == null, modifier = Modifier.height(52.dp)) { Text("Change") }
            if (studentId != null) {
              AddToStudentButton(studentId, vm.result?.files?.takeIf { vm.busy == null }, vm.preset, Modifier.weight(1f)) {
                vm.draft.done()
                onBack()
              }
            } else {
              Button(onClick = { saving = true }, enabled = vm.busy == null, modifier = Modifier.weight(1f).height(52.dp)) { Text("Save or share") }
            }
          }
        }
      }
    },
  ) { padding ->
    Column(
      Modifier
        .padding(padding)
        .consumeWindowInsets(padding)
        .imePadding()
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      ChoiceChips(PdfMode.entries.toList(), vm.mode, { it.title }, {
        vm.mode = it
        vm.result = null
        vm.error = null
        if (it == PdfMode.SPLIT) vm.suggestSplit = false
      })
      if (vm.suggestSplit && vm.mode != PdfMode.SPLIT && vm.result == null) {
        VSpace(10)
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(12.dp)) {
            Text(
              "${vm.pages.size} pages came in. Are they different papers for one form?",
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(onClick = {
                vm.mode = PdfMode.SPLIT
                vm.suggestSplit = false
              }) { Text("Split by preset") }
              TextButton(onClick = { vm.suggestSplit = false }) { Text("No, one PDF") }
            }
          }
        }
      }
      vm.anim?.let { ScanAnimationCard(it, Modifier.padding(top = 12.dp)) }
      // what is happening / what went wrong: near the top, where it is seen
      if (vm.busy != null) BusyRow(vm.busy ?: "")
      ErrorText(vm.error)
      NotesList(vm.notes)
      val result = vm.result
      if (result != null) {
        VSpace(12)
        result.preview?.let {
          Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().height(300.dp),
          ) { Image(it, contentDescription = "First page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp)) }
        }
        VSpace(12)
        FilesResultCard(result)
        Text(result.files[0].name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (studentId != null) FactChips(studentId, vm.facts)
      } else if (vm.mode == PdfMode.SPLIT) {
        SplitTool(
          vm.split,
          vm.pages,
          addFiles,
          { savingSplit = true },
          outerBusy = vm.busy != null,
          onNewPreset = onNewPreset,
          onStartAgain = { vm.clear() },
          onEdit = { vm.openEditor(it) },
          duplicates = vm.duplicates,
        )
      } else if (vm.mode == PdfMode.ID_CARD) {
        IdCardTool(vm, startCardScan, { pickFront.launch("image/*") }, { pickBack.launch("image/*") })
      } else {
        PagesTool(vm, addFiles)
      }
    }
  }

  val r = vm.result
  if (saving && r != null) {
    SaveSheet(r.files, defaultFolder = "DocReady", onDismiss = { saving = false }, onSaved = { vm.draft.done() })
  }
  val splitFiles = vm.split.allFiles()
  if (savingSplit && splitFiles.isNotEmpty()) {
    // every item's file in one folder, named after the preset
    SaveSheet(splitFiles, defaultFolder = vm.split.preset?.name ?: "DocReady", onDismiss = { savingSplit = false }, naming = presetNaming(vm.split.preset), onSaved = { vm.draft.done() })
  }
}

@Composable
private fun PagesTool(vm: PdfToolsViewModel, addFiles: () -> Unit) {
  Text(
    if (vm.mode == PdfMode.COMPRESS) {
      "Add a PDF and write the size it must be under. Pages are saved again as pictures, " +
        "at lower quality and size until the PDF fits."
    } else {
      "Add PDFs and photos, put the pages in order, remove what is not needed, then make one PDF. " +
        "Pages are saved as pictures; turn on Searchable PDF to keep their English text selectable."
    },
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 12.dp),
  )
  VSpace(12)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = addFiles, enabled = vm.busy == null) {
      Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Add PDF or photos")
    }
    if (vm.pages.isNotEmpty()) {
      OutlinedButton(onClick = { vm.openEditor(0) }, enabled = vm.busy == null) { Text("Edit pages") }
      OutlinedButton(onClick = { vm.clear() }, enabled = vm.busy == null) { Text("Clear") }
    }
  }
  if (vm.pages.isNotEmpty()) {
    SectionLabel("${vm.pages.size} page${if (vm.pages.size == 1) "" else "s"}")
    DuplicatesBar(vm.duplicates, enabled = vm.busy == null)
    val sameAs = vm.duplicates.sameAs
    vm.pages.forEachIndexed { i, p ->
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
      ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(width = 48.dp, height = 64.dp), contentAlignment = Alignment.Center) {
            (vm.thumbs[p] ?: p.thumb)?.let { Image(it, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
          }
          Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Page ${i + 1}", style = MaterialTheme.typography.bodyLarge)
            Text(p.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            vm.recipes[p]?.beautyBadge()?.let { BeautyBadge(it) }
            sameAs.getOrNull(i)?.let { DuplicateBadge(it) }
          }
          // (pages being added rely on the list's positions: no change meanwhile)
          IconButton(onClick = { vm.move(i, -1) }, enabled = vm.busy == null && i > 0) { Icon(AppIcons.Up, contentDescription = "Move up") }
          IconButton(onClick = { vm.move(i, 1) }, enabled = vm.busy == null && i < vm.pages.size - 1) { Icon(AppIcons.Down, contentDescription = "Move down") }
          IconButton(onClick = { vm.openEditor(i) }, enabled = vm.busy == null) { Icon(AppIcons.Edit, contentDescription = "Edit page") }
          IconButton(onClick = { vm.remove(i) }, enabled = vm.busy == null) { Icon(AppIcons.Delete, contentDescription = "Remove page") }
        }
      }
    }
    SearchableSwitch(vm.searchable) {
      vm.searchable = it
      vm.result = null
    }
    SectionLabel("Size limit")
    NumberField(
      if (vm.mode == PdfMode.COMPRESS) "Must be under (KB)" else "Max KB (empty = no limit)",
      vm.maxKbText,
      { vm.maxKbText = it },
      Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun IdCardTool(vm: PdfToolsViewModel, scan: () -> Unit, pickFront: () -> Unit, pickBack: () -> Unit) {
  Text(
    "Both sides of the National ID card on one A4 page, ready to print or attach. " +
      "Scan the front, then the back, or choose two photos.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 12.dp),
  )
  VSpace(12)
  Button(onClick = scan, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
    Icon(AppIcons.Scan, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text("Scan both sides")
  }
  VSpace(12)
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    CardSide("Front", vm.front, pickFront, Modifier.weight(1f))
    CardSide("Back", vm.back, pickBack, Modifier.weight(1f))
  }
  Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Real card size", style = MaterialTheme.typography.bodyLarge)
      Text("85.6 × 54 mm when printed on A4", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = vm.actualSize, onCheckedChange = {
      vm.actualSize = it
      vm.result = null
    })
  }
  SectionLabel("Size limit")
  NumberField("Max KB (empty = no limit)", vm.maxKbText, { vm.maxKbText = it }, Modifier.fillMaxWidth())
}

@Composable
private fun CardSide(title: String, page: PdfPage?, pick: () -> Unit, modifier: Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth().height(110.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (page?.thumb != null) {
          Image(page.thumb, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
        } else {
          Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
    OutlinedButton(onClick = pick, modifier = Modifier.padding(top = 6.dp)) { Text("Choose $title".lowercase().replaceFirstChar { it.uppercase() }) }
  }
}
