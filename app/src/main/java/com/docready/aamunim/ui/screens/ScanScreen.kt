package com.docready.aamunim.ui.screens

import kotlinx.coroutines.delay
import com.docready.aamunim.ui.components.animationsEnabled
import com.docready.aamunim.ui.components.ScanAnimationCard
import com.docready.aamunim.ui.components.ScanAnim
import com.docready.aamunim.data.model.beautyBadge
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.Draft
import com.docready.aamunim.data.DraftHost
import com.docready.aamunim.data.DraftPage
import com.docready.aamunim.data.DraftRepository
import com.docready.aamunim.data.model.title
import com.docready.aamunim.ui.components.DraftOffer
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.imaging.PageRenderer
import com.docready.aamunim.ui.editor.EditorMode
import com.docready.aamunim.ui.editor.EditorPage
import com.docready.aamunim.ui.editor.PageEditorScreen
import com.docready.aamunim.ui.editor.PageEditorState
import com.docready.aamunim.data.model.PresetItem
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.PdfTools
import com.docready.aamunim.imaging.Scanner
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.ui.components.BeautyBadge
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.theme.DocReadyColors
import com.docready.aamunim.ui.components.ResultCard
import com.docready.aamunim.ui.components.SaveSheet
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import com.docready.aamunim.ui.components.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pages (or one PDF) made ready */
class FilesResult(
  val files: List<ReadyFile>,
  val preview: ImageBitmap?,
  val totalBytes: Long,
  val largestBytes: Long,
  val maxKb: Int?,
  val fits: Boolean,
  val pages: Int,
  val format: OutputFormat,
  val beforeBytes: Long? = null,
)

/**
 * A page to make ready: its picture file (never changed), its edits ([PageRecipe]: corners,
 * crop, look, erasing...), what to look at (blurry / dark), and whether its corners were only
 * guessed (a photo added as a page).
 */
class Page(
  val file: File,
  val thumb: ImageBitmap?,
  val recipe: PageRecipe = PageRecipe.Document,
  val warning: String? = null,
  val checkCorners: Boolean = false,
)

class ScanViewModel(app: Application, private val presetId: String?, private val itemIndex: Int, private val studentId: String? = null, resume: Boolean = false) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()
  val preset = app.docReady.presets.get(presetId)
  private val item = preset?.items?.getOrNull(itemIndex)?.takeIf { it.kind == ItemKind.DOCUMENT }
    ?: preset?.items?.firstOrNull { it.kind == ItemKind.DOCUMENT }

  /** The item's place in its preset (a student's checklist), or null */
  val itemPos: Int? = item?.let { preset?.items?.indexOf(it) }?.takeIf { it >= 0 }

  val pages = mutableStateListOf<Page>()
  var format by mutableStateOf(if (item?.format == OutputFormat.JPG) OutputFormat.JPG else OutputFormat.PDF)
  var maxKbText by mutableStateOf(item?.maxKb?.toString() ?: "")

  /** The limits of a preset's document item, picked on this screen */
  fun applyItem(it: PresetItem) {
    format = if (it.format == OutputFormat.JPG) OutputFormat.JPG else OutputFormat.PDF
    maxKbText = it.maxKb?.toString() ?: ""
    result = null
  }
  var busy by mutableStateOf<String?>(null)
  var result by mutableStateOf<FilesResult?>(null)
  var error by mutableStateOf<String?>(null)

  /** The page being cleaned right now, for the scanning animation (null = none) */
  var anim by mutableStateOf<ScanAnim?>(null)
    private set
  private var animKey = 0

  /** The scanner's pages, copied in (they are added after the pages already there) */
  fun addScanned(uris: List<Uri>) = addPages(uris, photos = false)

  /** Photos of papers added as pages: their corners are looked for, to be checked by the user */
  fun addPhotos(uris: List<Uri>) = addPages(uris, photos = true)

  private fun addPages(uris: List<Uri>, photos: Boolean) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      busy = "Reading the pages…"
      error = null
      result = null
      val list = uris.take(30)
      val animated = animationsEnabled(ctx)
      try {
        for ((n, u) in list.withIndex()) {
          // the raw page first: the scanning animation sweeps over it while it is cleaned
          val (f, raw) = withContext(Dispatchers.Default) {
            val file = FileNames.copyIn(ctx, u, if (photos) "img" else "jpg")
            file to DocTools.load(ctx, Uri.fromFile(file), PREVIEW).asImageBitmap()
          }
          val label = if (list.size == 1) "Cleaning the page…" else "Cleaning page ${n + 1} of ${list.size}…"
          anim = ScanAnim(animKey++, raw, null, label)
          val (page, cleaned) = withContext(Dispatchers.Default) {
            val quad = if (photos) {
              val b = DocTools.load(ctx, Uri.fromFile(f), 1600)
              try {
                DocTools.findPaper(b)
              } finally {
                b.recycle()
              }
            } else {
              null
            }
            // new document pages: Magic (and the paper's corners on a photo)
            val recipe = PageRecipe.Document.copy(quad = quad?.toList())
            // the real result, small: the animation's reveal and the page's tile
            val shown = PageRenderer.render(DocTools.load(ctx, Uri.fromFile(f), PREVIEW), recipe)
            val tile = DocTools.scaleToMax(shown.copy(Bitmap.Config.ARGB_8888, false), 240).asImageBitmap()
            Page(f, tile, recipe, qualityWarning(f), checkCorners = photos) to shown.asImageBitmap()
          }
          pages.add(page)
          anim = anim?.copy(after = cleaned, done = true, label = if (list.size == 1) "Page ready" else "Page ${n + 1} of ${list.size} ready")
          // (a moment to see the reveal and the tick; none when animations are off)
          // (only short batches and the last page pause: never slowing a long scan down)
          if (animated && (list.size <= 3 || n == list.size - 1)) delay(650)
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The pages could not be read.")
      } finally {
        busy = null
        anim = null
      }
    }
  }

  /** "Looks blurry" / "Too dark" for a page's picture (the same check as for photos), or null */
  private fun qualityWarning(f: File): String? = try {
    val (sharpness, brightness) = DocTools.quality(ctx, Uri.fromFile(f))
    when {
      sharpness < 60 && brightness < 60 -> "Blurry and dark: scan it again?"
      sharpness < 60 -> "Looks blurry: scan it again?"
      brightness < 60 -> "Too dark: scan it in better light?"
      else -> null
    }
  } catch (_: Exception) {
    null
  }

  /** The page as it will come out (its edits applied), small, for the list */
  private fun pageThumb(f: File, recipe: PageRecipe): ImageBitmap? {
    val b = PageRenderer.render(DocTools.load(ctx, Uri.fromFile(f), 800), recipe)
    return DocTools.scaleToMax(b, 240).asImageBitmap()
  }

  /**
   * A page made from its picture and its edits at full detail (up to 3000 px, about 250 dpi on
   * A4; a phone short of memory gets 2400, then 1754). A KB limit makes it smaller later, only
   * as far as needed.
   */
  private fun prepared(p: Page): Bitmap = PdfTools.atBestSize { side ->
    // (straightening from corners takes a little more of the photo than the page it gives)
    val b = DocTools.load(ctx, Uri.fromFile(p.file), if (p.recipe.quad != null) side + 600 else side)
    DocTools.scaleToMax(PageRenderer.render(b, p.recipe, side), side)
  }

  // ---- the shared page editor (all pages, starting at one)
  var editor by mutableStateOf<PageEditorState?>(null)
    private set

  fun openEditor(start: Int) {
    val list = pages.toList()
    if (list.isEmpty()) return
    editor = PageEditorState(
      viewModelScope,
      list.mapIndexed { i, p -> EditorPage("Page ${i + 1}", p.recipe) { side -> DocTools.load(ctx, Uri.fromFile(p.file), side) } },
      start,
      EditorMode.DOCUMENT,
      onDone = { recipes -> applyRecipes(list, recipes) },
      onClose = { editor = null },
      animate = animationsEnabled(ctx),
    )
  }

  private fun applyRecipes(list: List<Page>, recipes: List<PageRecipe>) {
    editor = null
    val changed = list.indices.filter { list[it].recipe != recipes[it] }
    if (changed.isEmpty()) return
    result = null
    viewModelScope.launch {
      busy = "Updating the pages…"
      try {
        for (i in changed) {
          val p = list[i]
          val thumb = withContext(Dispatchers.Default) { pageThumb(p.file, recipes[i]) }
          val at = pages.indexOf(p)
          if (at >= 0) pages[at] = Page(p.file, thumb, recipes[i], p.warning, checkCorners = false)
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The pages could not be updated.")
      } finally {
        busy = null
      }
    }
  }

  fun remove(page: Page) {
    pages.remove(page)
    result = null
  }

  fun make() {
    val kb = maxKbText.toIntOrNull()
    if (kb != null && kb !in 10..50_000) {
      error = "Max KB: 10 to 50000 (or leave it empty for no limit)."
      return
    }
    if (pages.isEmpty()) return
    val files = pages.toList()
    val asPdf = format == OutputFormat.PDF
    viewModelScope.launch {
      busy = if (asPdf) "Making the PDF…" else "Making the pictures…"
      error = null
      try {
        result = withContext(Dispatchers.Default) {
          val maxBytes = kb?.let { it * 1024L }
          if (asPdf) {
            // one page in memory at a time: cleaned, then kept as a file
            val cleaned = files.map { f ->
              DocTools.keepPage(ctx, prepared(f))
            }
            val name = if (files.size == 1) "scan_1_page.pdf" else "scan_${files.size}_pages.pdf"
            val out = FileNames.outputFile(ctx, name)
            val previewImg = DocTools.thumbnail(cleaned[0], 600)?.asImageBitmap()
            val (size, _, fits) = try {
              DocTools.fitPdf(cleaned, maxBytes, out)
            } finally {
              cleaned.forEach { it.delete() } // (in the PDF now)
            }
            FilesResult(
              listOf(ReadyFile(out, name, "application/pdf", kb, itemIndex = itemPos)),
              previewImg,
              size, size, kb, fits, files.size, OutputFormat.PDF,
            )
          } else {
            val ready = mutableListOf<ReadyFile>()
            var allFit = true
            var total = 0L
            var largest = 0L
            files.forEachIndexed { i, f ->
              val b = prepared(f)
              val fit = DocTools.fitPicture(b, maxBytes, false)
              val name = if (files.size == 1) "scan.jpg" else "scan_page-${i + 1}.jpg"
              val out = FileNames.outputFile(ctx, name)
              out.writeBytes(fit.bytes)
              ready.add(ReadyFile(out, name, "image/jpeg", kb, itemIndex = itemPos))
              allFit = allFit && fit.fits
              total += fit.bytes.size
              largest = maxOf(largest, fit.bytes.size.toLong())
            }
            FilesResult(
              ready, DocTools.thumbnail(ready[0].file, 600)?.asImageBitmap(),
              total, largest, kb, allFit, files.size, OutputFormat.JPG,
            )
          }
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The document could not be made.")
      } finally {
        busy = null
      }
    }
  }

  fun clearResult() {
    result = null
  }

  // ---- autosave: "Continue where you left off?"
  private val draftTitle = listOfNotNull(
    "Scan",
    item?.title,
    app.docReady.students.get(studentId)?.name?.let { "for $it" },
  ).joinToString(" · ")

  val draft = DraftHost(viewModelScope, app.docReady.drafts, DraftRepository.keyOf("scan", presetId, itemIndex, studentId), { snapshot() }) { d -> restore(d) }

  private fun snapshot(): Draft? {
    val list = pages.toList()
    if (list.isEmpty()) return null
    return Draft(
      draft.key, "scan", draftTitle, presetId, itemIndex, studentId,
      list.map { DraftPage(it.file.path, recipe = it.recipe, warning = it.warning, checkCorners = it.checkCorners) },
      mapOf("format" to format.name, "maxKb" to maxKbText),
    )
  }

  private suspend fun restore(d: Draft): Boolean {
    d.values["format"]?.let { f -> OutputFormat.entries.firstOrNull { it.name == f }?.let { format = it } }
    d.values["maxKb"]?.let { maxKbText = it }
    busy = "Opening your pages…"
    try {
      val back = withContext(Dispatchers.Default) {
        d.pages.map { p ->
          val f = File(p.file)
          Page(f, pageThumb(f, p.recipe), p.recipe, p.warning, p.checkCorners)
        }
      }
      // (before any pages added meanwhile)
      pages.addAll(0, back)
      result = null
      return true
    } catch (e: Throwable) {
      error = messageOf(e, "The unfinished pages could not be opened.") + " The draft is kept as it was."
      return false
    } finally {
      busy = null
    }
  }

  init {
    draft.begin(resume)
  }

  override fun onCleared() {
    draft.flush()
    super.onCleared()
  }
}

/** The ready files' card: what was made and whether it is under the limit */
@Composable
fun FilesResultCard(r: FilesResult) {
  val what = if (r.format == OutputFormat.PDF) {
    "PDF · ${r.pages} page${if (r.pages == 1) "" else "s"}"
  } else {
    "${r.files.size} JPG${if (r.files.size == 1) "" else "s"}"
  }
  val weight = if (r.format == OutputFormat.PDF || r.files.size == 1) bytesText(r.totalBytes) else "largest ${bytesText(r.largestBytes)}"
  val before = r.beforeBytes?.let { "was ${bytesText(it)} → " } ?: ""
  if (r.fits) {
    ResultCard(true, if (r.maxKb != null) "Meets the size limit" else "Ready", "$what · $before$weight${r.maxKb?.let { " (max $it KB)" } ?: ""}")
  } else {
    ResultCard(
      false,
      "Can't get under ${r.maxKb} KB",
      "$what · $before$weight at the lowest readable quality. Try fewer pages, Grey or Black & white, or check the form's limit.",
    )
  }
}

@Composable
fun ScanScreen(presetId: String?, itemIndex: Int, onBack: () -> Unit, onNewPreset: () -> Unit = {}, studentId: String? = null, resume: Boolean = false) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val vm: ScanViewModel = viewModel { ScanViewModel(app, presetId, itemIndex, studentId, resume) }
  var saving by rememberSaveable { mutableStateOf(false) }
  // the shared page editor takes the whole screen while it is open
  vm.editor?.let {
    PageEditorScreen(it)
    return
  }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
    if (res.resultCode == Activity.RESULT_OK) {
      val uris = try {
        Scanner.pageUris(res.data)
      } catch (_: Exception) {
        emptyList()
      }
      vm.addScanned(uris)
    }
  }
  val addPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> vm.addPhotos(uris) }
  val pickPhotos = {
    try {
      addPhotos.launch("image/*")
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, "No photo picker found on this phone.", Toast.LENGTH_LONG).show()
    }
  }
  val startScan = {
    val activity = context.findActivity()
    if (activity != null) {
      Scanner.startIntent(activity, 30)
        .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
        .addOnFailureListener { e ->
          Toast.makeText(context, e.message ?: "The scanner is not available on this phone (Google Play services).", Toast.LENGTH_LONG).show()
        }
    }
  }

  DraftOffer(vm.draft)
  Scaffold(
    topBar = { DocTopBar("Scan document", onBack, subtitle = vm.preset?.let { "Preset: ${it.name}" } ?: "Certificates, marksheets") },
    bottomBar = {
      if (vm.pages.isNotEmpty()) {
        BottomActions {
          if (vm.result == null) {
            Button(onClick = { vm.make() }, enabled = vm.busy == null, modifier = Modifier.weight(1f).height(52.dp)) {
              Text(if (vm.format == OutputFormat.PDF) "Make PDF" else "Make JPGs")
            }
          } else {
            OutlinedButton(onClick = { vm.clearResult() }, enabled = vm.busy == null, modifier = Modifier.height(52.dp)) { Text("Change") }
            if (studentId != null) {
              AddToStudentButton(studentId, vm.result?.files?.takeIf { vm.busy == null }, vm.preset, Modifier.weight(1f)) {
                vm.draft.done()
                onBack()
              }
            } else {
              Button(onClick = { saving = true }, enabled = vm.busy == null, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Save or share")
              }
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
      val result = vm.result
      vm.anim?.let {
        ScanAnimationCard(it, Modifier.padding(bottom = 12.dp))
      }
      if (vm.pages.isEmpty()) {
        Text(
          "Google's scanner finds the page edges, straightens them and removes shadows. " +
            "Scan one or more pages, then choose a PDF or pictures.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(16)
        Button(onClick = startScan, modifier = Modifier.fillMaxWidth().height(52.dp)) {
          Icon(AppIcons.Scan, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(8.dp))
          Text("Scan pages")
        }
        VSpace(10)
        OutlinedButton(onClick = pickPhotos, modifier = Modifier.fillMaxWidth().height(52.dp)) {
          Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(8.dp))
          Text("Use photos of papers")
        }
        if (vm.preset == null) PresetItemShortcut(ItemKind.DOCUMENT, { _, item -> vm.applyItem(item) }, onNewPreset)
      } else if (result != null) {
        result.preview?.let {
          Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().height(320.dp),
          ) {
            Image(it, contentDescription = "First page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
          }
        }
        VSpace(12)
        FilesResultCard(result)
        VSpace(8)
        for (f in result.files) {
          Text(f.name + " · " + bytesText(f.file.length()), style = MaterialTheme.typography.bodySmall)
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          SectionLabel("${vm.pages.size} page${if (vm.pages.size == 1) "" else "s"} · tap a page to edit it", Modifier.weight(1f))
          TextButton(onClick = { vm.openEditor(0) }, enabled = vm.busy == null) { Text("Edit all pages") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          vm.pages.forEachIndexed { i, p ->
            Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              Surface(
                onClick = { vm.openEditor(i) },
                enabled = vm.busy == null,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                  if (p.warning != null || p.checkCorners) 2.dp else 1.dp,
                  if (p.warning != null || p.checkCorners) DocReadyColors.extra.warningBorder else MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.size(width = 90.dp, height = 120.dp),
              ) {
                p.thumb?.let { Image(it, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
              }
              Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
              p.warning?.let { PageNote(it) }
              p.recipe.beautyBadge()?.let { BeautyBadge(it) }
              if (p.checkCorners) PageNote("Check the corners")
              OutlinedButton(onClick = { vm.remove(p) }, enabled = vm.busy == null, modifier = Modifier.height(32.dp)) {
                Text("Remove", style = MaterialTheme.typography.labelSmall)
              }
            }
          }
        }
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = startScan, enabled = vm.busy == null) { Text("Scan more") }
          OutlinedButton(onClick = pickPhotos, enabled = vm.busy == null) { Text("Add photos") }
        }
        SectionLabel("Make")
        ChoiceChips(
          listOf(OutputFormat.PDF, OutputFormat.JPG),
          vm.format,
          { if (it == OutputFormat.PDF) "One PDF" else "JPG pictures" },
          { vm.format = it },
        )
        Text(
          "Each page: shadows removed and colour evened. Tap a page to crop, straighten, change its look or erase marks.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
        if (vm.preset == null) PresetItemShortcut(ItemKind.DOCUMENT, { _, item -> vm.applyItem(item) }, onNewPreset)
        SectionLabel("Size limit")
        NumberField(
          if (vm.format == OutputFormat.PDF) "Max KB for the PDF (empty = no limit)" else "Max KB per picture (empty = no limit)",
          vm.maxKbText,
          { vm.maxKbText = it },
          Modifier.fillMaxWidth(),
        )
      }
      if (vm.busy != null) BusyRow(vm.busy ?: "")
      ErrorText(vm.error)
    }
  }

  val r = vm.result
  if (saving && r != null) {
    SaveSheet(r.files, defaultFolder = vm.preset?.name ?: "DocReady", onDismiss = { saving = false }, naming = presetNaming(vm.preset), onSaved = { vm.draft.done() })
  }
}

@Composable
private fun PageNote(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = DocReadyColors.extra.onWarningContainer,
    textAlign = TextAlign.Center,
    modifier = Modifier.padding(vertical = 2.dp),
  )
}

/** The animation's page pictures: about a phone screen's width */
private const val PREVIEW = 900
