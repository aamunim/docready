package com.docready.aamunim.ui.screens

import android.app.Activity
import android.app.Application
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

class Page(val file: File, val thumb: ImageBitmap?)

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
  fun addScanned(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      busy = "Reading the pages…"
      error = null
      result = null
      try {
        val added = withContext(Dispatchers.IO) {
          uris.map { u ->
            val f = FileNames.copyIn(ctx, u, "jpg")
            Page(f, DocTools.thumbnail(f, 240)?.asImageBitmap())
          }
        }
        pages.addAll(added)
      } catch (e: Throwable) {
        error = messageOf(e, "The scanned pages could not be read.")
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
    val files = pages.map { it.file }
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
              DocTools.keepPage(ctx, DocTools.clean(DocTools.load(ctx, Uri.fromFile(f), PdfTools.PAGE_LONG_SIDE), color, enh, true))
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
              val b = DocTools.clean(DocTools.load(ctx, Uri.fromFile(f), PdfTools.PAGE_LONG_SIDE), color, enh, true)
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
      if (vm.pages.isNotEmpty()) {
        BottomActions {
          if (vm.result == null) {
            OutlinedButton(onClick = startScan, enabled = vm.busy == null, modifier = Modifier.height(52.dp)) { Text("Add pages") }
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
        SectionLabel("${vm.pages.size} page${if (vm.pages.size == 1) "" else "s"}")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          vm.pages.forEachIndexed { i, p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(width = 90.dp, height = 120.dp),
              ) {
                p.thumb?.let { Image(it, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
              }
              Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
              OutlinedButton(onClick = { vm.remove(p) }, modifier = Modifier.height(32.dp)) {
                Text("Remove", style = MaterialTheme.typography.labelSmall)
              }
            }
          }
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
