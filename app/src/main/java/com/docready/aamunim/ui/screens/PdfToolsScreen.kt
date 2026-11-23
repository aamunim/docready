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
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.PdfTools
import com.docready.aamunim.imaging.Scanner
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.intake.IntakeTarget
import com.docready.aamunim.intake.IncomingFile
import com.docready.aamunim.save.ReadyFile
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
 * One page in the list: a PDF's page or a picture, kept as a JPEG work file.
 * [sourceBytes]: its share of the added file's weight (for "was ... → now ...").
 */
class PdfPage(
  val file: File,
  val thumb: ImageBitmap?,
  val from: String,
  val sourceBytes: Long = 0L,
  /** The file it came from (a PDF, or the photo itself), to render a part again in more detail */
  val source: File? = null,
  /** Its page number in [source] when that is a PDF; -1 = a photo */
  val pdfPage: Int = -1,
)

class PdfToolsViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()

  var mode by mutableStateOf(PdfMode.MERGE)
  val pages = mutableStateListOf<PdfPage>()
  var maxKbText by mutableStateOf("")
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
  val split = SplitState(app, viewModelScope)

  /** Several pages came in by Share / Open with: offer to split them by preset */
  var suggestSplit by mutableStateOf(false)

  fun addIncoming(files: List<IncomingFile>) {
    add(files.map { f -> { f.file } to (f.mime to f.name) }, fromShare = true)
  }

  /** Each: how to get the file, (its kind, its name) */
  private fun add(items: List<Pair<() -> File, Pair<String, String>>>, fromShare: Boolean = false) {
    if (items.isEmpty()) return
    viewModelScope.launch {
      busy = "Opening the files…"
      error = null
      result = null
      // a file that cannot be opened must not count: restored on failure
      val filesBefore = filesAdded
      val singleBefore = singlePdf
      try {
        val added = withContext(Dispatchers.IO) {
          val out = mutableListOf<PdfPage>()
          for ((fileOf, info) in items) {
            val (mime, name) = info
            val f = fileOf()
            if (firstName == null) firstName = name.substringBeforeLast('.')
            filesAdded++
            if (mime == "application/pdf") {
              val r = PdfTools.render(ctx, f)
              if (r.total > r.pages.size) {
                withContext(Dispatchers.Main) { notes.add("$name: only the first ${r.pages.size} of ${r.total} pages were added.") }
              }
              singlePdf = if (filesAdded == 1 && r.total == r.pages.size) f else null
              val share = f.length() / maxOf(1, r.total)
              r.pages.forEachIndexed { n, p -> out.add(PdfPage(p, DocTools.thumbnail(p, 240)?.asImageBitmap(), name, share, f, n)) }
            } else {
              singlePdf = null
              val b = DocTools.load(ctx, Uri.fromFile(f), PdfTools.PAGE_LONG_SIDE)
              val p = DocTools.keepPage(ctx, b)
              out.add(PdfPage(p, DocTools.thumbnail(p, 240)?.asImageBitmap(), name, f.length(), f, -1))
            }
          }
          out
        }
        pages.addAll(added)
        if (fromShare && added.size > 1 && mode != PdfMode.SPLIT) suggestSplit = true
        if (mode == PdfMode.ID_CARD) {
          // pictures handed to the NID tool fill the empty sides
          for (p in added) {
            if (front == null) front = p else if (back == null) back = p
          }
        }
      } catch (e: Throwable) {
        filesAdded = filesBefore
        singlePdf = singleBefore
        error = messageOf(e, "The file could not be opened.")
      } finally {
        busy = null
      }
    }
  }

  fun move(index: Int, by: Int) {
    val to = index + by
    if (index !in pages.indices || to !in pages.indices) return
    val p = pages.removeAt(index)
    pages.add(to, p)
    pagesChanged = true
    result = null
    split.onPagesChanged(pages)
  }

  fun remove(index: Int) {
    if (index in pages.indices) pages.removeAt(index)
    pagesChanged = true
    result = null
    split.onPagesChanged(pages)
  }

  fun clear() {
    pages.clear()
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
            PdfPage(f, DocTools.thumbnail(f, 240)?.asImageBitmap(), "scan")
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
          PdfPage(p, DocTools.thumbnail(p, 240)?.asImageBitmap(), "picture")
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
    val files = pages.map { it.file }
    val f = front
    val b = back
    if (m == PdfMode.ID_CARD && (f == null || b == null)) {
      error = "Add both sides of the card first."
      return
    }
    if (m != PdfMode.ID_CARD && files.isEmpty()) return
    val actual = actualSize
    val before = pages.sumOf { it.sourceBytes }.takeIf { m == PdfMode.COMPRESS && it > 0 }
    // the PDF exactly as it was added (no page moved or removed): never made heavier
    val original = singlePdf?.takeIf { m == PdfMode.COMPRESS && !pagesChanged }
    val base = FileNames.safeBase(firstName ?: "document")
    viewModelScope.launch {
      busy = "Making the PDF…"
      error = null
      try {
        result = withContext(Dispatchers.Default) {
          val maxBytes = kb?.let { it * 1024L }
          val sourcePages: List<File>
          val name: String
          if (m == PdfMode.ID_CARD && f != null && b != null) {
            val fb = DocTools.load(ctx, Uri.fromFile(f.file), 1600)
            val bb = DocTools.load(ctx, Uri.fromFile(b.file), 1600)
            val page: Bitmap = PdfTools.idCardPage(fb, bb, actual)
            fb.recycle()
            bb.recycle()
            sourcePages = listOf(DocTools.keepPage(ctx, page))
            name = "nid_both_sides.pdf"
          } else {
            sourcePages = files
            name = if (m == PdfMode.COMPRESS) "${base}_compressed.pdf" else "merged_${files.size}_pages.pdf"
          }
          val out = FileNames.outputFile(ctx, name)
          var notesOut: String? = null
          val size: Long
          val fits: Boolean
          if (original != null && maxBytes != null && original.length() <= maxBytes) {
            // already small enough: kept as it is (text stays text)
            original.copyTo(out, overwrite = true)
            size = out.length()
            fits = true
            notesOut = "This PDF is already under ${kb} KB: it is kept as it was."
          } else {
            val (written, _, ok) = DocTools.fitPdf(sourcePages, maxBytes, out)
            if (original != null && written >= original.length()) {
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
          withContext(Dispatchers.Main) {
            // only the latest run's "kept as it was" note is shown
            lastMakeNote?.let { notes.remove(it) }
            lastMakeNote = notesOut
            notesOut?.let { n -> notes.add(n) }
          }
          FilesResult(
            listOf(ReadyFile(out, name, "application/pdf")),
            DocTools.thumbnail(sourcePages[0], 600)?.asImageBitmap(),
            size, size, kb, fits, sourcePages.size, OutputFormat.PDF, before,
          )
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The PDF could not be made.")
      } finally {
        busy = null
      }
    }
  }
}

@Composable
fun PdfToolsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val vm: PdfToolsViewModel = viewModel { PdfToolsViewModel(app) }
  var saving by rememberSaveable { mutableStateOf(false) }
  var savingSplit by rememberSaveable { mutableStateOf(false) }
  val intake by Intake.request.collectAsState()
  LaunchedEffect(intake) {
    if (intake?.target == IntakeTarget.PDF_TOOLS) vm.addIncoming(Intake.take(IntakeTarget.PDF_TOOLS))
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

  Scaffold(
    topBar = { DocTopBar("PDF tools", onBack, subtitle = "Merge, compress, NID") },
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
            Button(onClick = { saving = true }, enabled = vm.busy == null, modifier = Modifier.weight(1f).height(52.dp)) { Text("Save or share") }
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
      } else if (vm.mode == PdfMode.SPLIT) {
        SplitTool(vm.split, vm.pages, addFiles, { savingSplit = true }, outerBusy = vm.busy != null)
      } else if (vm.mode == PdfMode.ID_CARD) {
        IdCardTool(vm, startCardScan, { pickFront.launch("image/*") }, { pickBack.launch("image/*") })
      } else {
        PagesTool(vm, addFiles)
      }
      if (vm.busy != null) BusyRow(vm.busy ?: "")
      NotesList(vm.notes)
      ErrorText(vm.error)
    }
  }

  val r = vm.result
  if (saving && r != null) {
    SaveSheet(r.files, defaultFolder = "DocReady", onDismiss = { saving = false })
  }
  val splitFiles = vm.split.allFiles()
  if (savingSplit && splitFiles.isNotEmpty()) {
    // every item's file in one folder, named after the preset
    SaveSheet(splitFiles, defaultFolder = vm.split.preset?.name ?: "DocReady", onDismiss = { savingSplit = false })
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
        "Pages are saved as pictures (text in them can't be selected)."
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
    if (vm.pages.isNotEmpty()) OutlinedButton(onClick = { vm.clear() }, enabled = vm.busy == null) { Text("Clear") }
  }
  if (vm.pages.isNotEmpty()) {
    SectionLabel("${vm.pages.size} page${if (vm.pages.size == 1) "" else "s"}")
    vm.pages.forEachIndexed { i, p ->
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
      ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(width = 48.dp, height = 64.dp), contentAlignment = Alignment.Center) {
            p.thumb?.let { Image(it, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
          }
          Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Page ${i + 1}", style = MaterialTheme.typography.bodyLarge)
            Text(p.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(AppIcons.Up, contentDescription = "Move up") }
          IconButton(onClick = { vm.move(i, 1) }, enabled = i < vm.pages.size - 1) { Icon(AppIcons.Down, contentDescription = "Move down") }
          IconButton(onClick = { vm.remove(i) }) { Icon(AppIcons.Delete, contentDescription = "Remove page") }
        }
      }
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
