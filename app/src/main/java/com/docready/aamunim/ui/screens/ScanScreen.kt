package com.docready.aamunim.ui.screens

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
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.PdfTools
import com.docready.aamunim.imaging.Scanner
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.components.QuadBox
import com.docready.aamunim.ui.components.fullQuad
import com.docready.aamunim.ui.components.isFullQuad
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
 * A page to make ready: its picture file, the paper's corners on it (null = the whole
 * picture), a quarter-turn count in degrees, what to look at (blurry / dark), and whether its
 * corners were only guessed (a photo added as a page).
 */
class Page(
  val file: File,
  val thumb: ImageBitmap?,
  val quad: FloatArray? = null,
  val turn: Int = 0,
  val warning: String? = null,
  val checkCorners: Boolean = false,
)

enum class ColorMode(val key: String, val title: String) {
  COLOR("color", "Colour"),
  GRAY("gray", "Grey"),
  BW("bw", "Black & white"),
}

class ScanViewModel(app: Application, presetId: String?, itemIndex: Int) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()
  val preset = app.docReady.presets.get(presetId)
  private val item = preset?.items?.getOrNull(itemIndex)?.takeIf { it.kind == ItemKind.DOCUMENT }
    ?: preset?.items?.firstOrNull { it.kind == ItemKind.DOCUMENT }

  val pages = mutableStateListOf<Page>()
  var format by mutableStateOf(if (item?.format == OutputFormat.JPG) OutputFormat.JPG else OutputFormat.PDF)
  var colorMode by mutableStateOf(ColorMode.COLOR)
  var enhance by mutableStateOf(true)
  var maxKbText by mutableStateOf(item?.maxKb?.toString() ?: "")
  var busy by mutableStateOf<String?>(null)
  var result by mutableStateOf<FilesResult?>(null)
  var error by mutableStateOf<String?>(null)

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
      try {
        for (u in uris.take(30)) {
          val page = withContext(Dispatchers.Default) {
            val f = FileNames.copyIn(ctx, u, if (photos) "img" else "jpg")
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
            Page(f, pageThumb(f, quad, 0), quad, 0, qualityWarning(f), checkCorners = photos)
          }
          pages.add(page)
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The pages could not be read.")
      } finally {
        busy = null
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

  /** The page as it will come out (straightened, turned), small, for the list */
  private fun pageThumb(f: File, quad: FloatArray?, turn: Int): ImageBitmap? {
    val b = DocTools.straighten(DocTools.load(ctx, Uri.fromFile(f), 800), quad, turn)
    val small = DocTools.scaleToMax(b, 240)
    return small.asImageBitmap()
  }

  /** A page ready to be cleaned: straightened from its corners, turned, A4 at 150 dpi at most */
  private fun prepared(p: Page): Bitmap {
    val q = p.quad?.takeIf { DocTools.isConvex(it) }
    val b = DocTools.load(ctx, Uri.fromFile(p.file), if (q != null) 2600 else PdfTools.PAGE_LONG_SIDE)
    return DocTools.scaleToMax(DocTools.straighten(b, q, p.turn), PdfTools.PAGE_LONG_SIDE)
  }

  // ---- the corners of one page, changed by hand
  var editing by mutableStateOf<Int?>(null)
    private set
  var editImage by mutableStateOf<ImageBitmap?>(null)
    private set
  var editQuad by mutableStateOf(fullQuad())
  var editTurn by mutableStateOf(0)
    private set
  private var editBitmap: Bitmap? = null

  fun startEdit(index: Int) {
    val p = pages.getOrNull(index) ?: return
    viewModelScope.launch {
      try {
        val b = withContext(Dispatchers.Default) { DocTools.load(ctx, Uri.fromFile(p.file), 1400) }
        editBitmap?.recycle()
        editBitmap = b
        editImage = b.asImageBitmap()
        editQuad = p.quad?.copyOf() ?: fullQuad()
        editTurn = p.turn
        editing = index
      } catch (e: Throwable) {
        error = messageOf(e, "The page could not be opened.")
      }
    }
  }

  fun wholePage() {
    editQuad = fullQuad()
  }

  /** The paper's edges looked for on the page (when clear) */
  fun findEdges() {
    val b = editBitmap ?: return
    viewModelScope.launch {
      try {
        val q = withContext(Dispatchers.Default) { DocTools.findPaper(b) }
        if (q != null) editQuad = q else error = "No clear paper edges found: drag the corners by hand."
      } catch (e: Throwable) {
        error = messageOf(e, "The paper's edges could not be found: drag the corners by hand.")
      }
    }
  }

  fun turnPage() {
    editTurn = (editTurn + 90) % 360
  }

  fun cancelEdit() {
    editing = null
    editImage = null
    editBitmap = null // (the picture may still be drawn: Android frees it)
  }

  fun applyEdit() {
    val i = editing ?: return
    val p = pages.getOrNull(i) ?: return cancelEdit()
    val q = editQuad.copyOf().takeIf { !isFullQuad(it) }
    if (q != null && !DocTools.isConvex(q)) {
      error = "The corners cross each other: put each one on its own corner of the paper."
      return
    }
    val turn = editTurn
    viewModelScope.launch {
      busy = "Straightening the page…"
      error = null
      try {
        val thumb = withContext(Dispatchers.Default) { pageThumb(p.file, q, turn) }
        if (i in pages.indices && pages[i] === p) pages[i] = Page(p.file, thumb, q, turn, p.warning, checkCorners = false)
        result = null
        cancelEdit()
      } catch (e: Throwable) {
        error = messageOf(e, "The page could not be straightened.")
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
    val color = colorMode.key
    val enh = enhance
    viewModelScope.launch {
      busy = if (asPdf) "Making the PDF…" else "Making the pictures…"
      error = null
      try {
        result = withContext(Dispatchers.Default) {
          val maxBytes = kb?.let { it * 1024L }
          if (asPdf) {
            // one page in memory at a time: cleaned, then kept as a file
            val cleaned = files.map { f ->
              DocTools.keepPage(ctx, DocTools.clean(prepared(f), color, enh, true))
            }
            val name = if (files.size == 1) "scan_1_page.pdf" else "scan_${files.size}_pages.pdf"
            val out = FileNames.outputFile(ctx, name)
            val (size, _, fits) = DocTools.fitPdf(cleaned, maxBytes, out)
            FilesResult(
              listOf(ReadyFile(out, name, "application/pdf")),
              DocTools.thumbnail(cleaned[0], 600)?.asImageBitmap(),
              size, size, kb, fits, files.size, OutputFormat.PDF,
            )
          } else {
            val ready = mutableListOf<ReadyFile>()
            var allFit = true
            var total = 0L
            var largest = 0L
            files.forEachIndexed { i, f ->
              val b = DocTools.clean(prepared(f), color, enh, true)
              val fit = DocTools.fitPicture(b, maxBytes, false)
              val name = if (files.size == 1) "scan.jpg" else "scan_page-${i + 1}.jpg"
              val out = FileNames.outputFile(ctx, name)
              out.writeBytes(fit.bytes)
              ready.add(ReadyFile(out, name, "image/jpeg"))
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
fun ScanScreen(presetId: String?, itemIndex: Int, onBack: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val vm: ScanViewModel = viewModel { ScanViewModel(app, presetId, itemIndex) }
  var saving by rememberSaveable { mutableStateOf(false) }
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

  Scaffold(
    topBar = { DocTopBar("Scan document", onBack, subtitle = vm.preset?.let { "Preset: ${it.name}" } ?: "Certificates, marksheets") },
    bottomBar = {
      if (vm.pages.isNotEmpty() && vm.editing == null) {
        BottomActions {
          if (vm.result == null) {
            Button(onClick = { vm.make() }, enabled = vm.busy == null, modifier = Modifier.weight(1f).height(52.dp)) {
              Text(if (vm.format == OutputFormat.PDF) "Make PDF" else "Make JPGs")
            }
          } else {
            OutlinedButton(onClick = { vm.clearResult() }, enabled = vm.busy == null, modifier = Modifier.height(52.dp)) { Text("Change") }
            Button(onClick = { saving = true }, enabled = vm.busy == null, modifier = Modifier.weight(1f).height(52.dp)) {
              Text("Save or share")
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
      } else if (vm.editing != null) {
        PageCornerEditor(vm)
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
        SectionLabel("${vm.pages.size} page${if (vm.pages.size == 1) "" else "s"} · tap a page to fix its corners or turn it")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          vm.pages.forEachIndexed { i, p ->
            Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              Surface(
                onClick = { vm.startEdit(i) },
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
        SectionLabel("Colour")
        ChoiceChips(ColorMode.entries.toList(), vm.colorMode, { it.title }, { vm.colorMode = it })
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Even out light and remove shadows", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
          Switch(checked = vm.enhance, onCheckedChange = { vm.enhance = it })
        }
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
    SaveSheet(r.files, defaultFolder = vm.preset?.name ?: "DocReady", onDismiss = { saving = false })
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

/** One page with its four corners to drag, a quarter turn, and the paper's edges found again */
@Composable
private fun PageCornerEditor(vm: ScanViewModel) {
  val image = vm.editImage ?: return
  Text(
    "Drag each corner onto a corner of the paper. The page is straightened to them.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  VSpace(8)
  QuadBox(image, vm.editQuad, { vm.editQuad = it })
  if (vm.editTurn != 0) {
    Text("Turned ${vm.editTurn}° to the right", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
  }
  VSpace(8)
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = { vm.turnPage() }) {
      Icon(AppIcons.Rotate, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Turn")
    }
    OutlinedButton(onClick = { vm.findEdges() }) { Text("Find edges") }
    OutlinedButton(onClick = { vm.wholePage() }) { Text("Whole page") }
  }
  VSpace(8)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { vm.applyEdit() }, enabled = vm.busy == null) { Text("Done") }
    OutlinedButton(onClick = { vm.cancelEdit() }) { Text("Cancel") }
  }
}
