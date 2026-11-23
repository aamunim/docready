package com.docready.aamunim.ui.screens

import android.app.Application
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.imaging.FileFacts
import com.docready.aamunim.imaging.SizeFixer
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.components.ResultCard
import com.docready.aamunim.ui.components.SaveSheet
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.SizeRow
import com.docready.aamunim.ui.components.VSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Check size: any photo or PDF (picked with the system picker) shown as KB, pages and pixels;
 * with a KB limit, "Fix to fit" brings it under it (the same fitting as the tools use).
 */
class CheckSizeViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()

  /** The file as picked (a copy in the share folder) */
  var original by mutableStateOf<ReadyFile?>(null)
    private set
  var originalFacts by mutableStateOf<FileFacts?>(null)
    private set

  /** The file after "Fix to fit", or null */
  var fixed by mutableStateOf<ReadyFile?>(null)
    private set
  var fixedFacts by mutableStateOf<FileFacts?>(null)
    private set

  var maxKbText by mutableStateOf("")
  var keepPixels by mutableStateOf(false)
  var busy by mutableStateOf<String?>(null)
  var error by mutableStateOf<String?>(null)

  /** What Save would give: the fixed file, else the original */
  val current: ReadyFile? get() = fixed ?: original

  fun pick(uri: Uri) {
    viewModelScope.launch {
      busy = "Reading the file…"
      error = null
      try {
        val (f, facts) = withContext(Dispatchers.IO) {
          val name = FileNames.displayName(ctx, uri) ?: "file"
          val type = ctx.contentResolver.getType(uri) ?: ""
          val mime = when {
            type == "application/pdf" || name.lowercase().endsWith(".pdf") -> "application/pdf"
            type.startsWith("image/") -> type
            name.lowercase().endsWith(".png") -> "image/png"
            else -> "image/jpeg"
          }
          // copied in (the picker's link may not last), into the share folder so it can be saved
          val tmp = FileNames.copyIn(ctx, uri, if (mime == "application/pdf") "pdf" else "img", MAX_BYTES)
          val out = FileNames.outputFile(ctx, name.replace('/', '_'))
          tmp.copyTo(out, overwrite = true)
          tmp.delete()
          val ready = ReadyFile(out, out.name, mime)
          ready to SizeFixer.facts(out, mime)
        }
        if (facts.pages == null && facts.width == null) {
          error = "This file could not be read as a photo or a PDF."
        }
        original = f
        originalFacts = facts
        fixed = null
        fixedFacts = null
      } catch (e: Throwable) {
        error = messageOf(e, "The file could not be read.")
      } finally {
        busy = null
      }
    }
  }

  fun fix() {
    val f = original ?: return
    val kb = maxKbText.toIntOrNull()
    if (kb == null || kb !in 5..50_000) {
      error = "Write the size it must be under: 5 to 50000 KB."
      return
    }
    val exact = keepPixels
    viewModelScope.launch {
      busy = "Making it fit…"
      error = null
      try {
        val (r, facts) = withContext(Dispatchers.Default) {
          val out = SizeFixer.fit(ctx, f.copy(maxKb = kb, exactSize = exact), kb)
          out to SizeFixer.facts(out.file, out.mime)
        }
        fixed = r
        fixedFacts = facts
        if (r.file == f.file) error = "This file cannot be made smaller without changing its pixel size."
      } catch (e: Throwable) {
        error = messageOf(e, "It could not be made smaller.")
      } finally {
        busy = null
      }
    }
  }

  /** A new limit: the old fix no longer answers it */
  fun limitChanged(text: String) {
    maxKbText = text.filter { it.isDigit() }.take(5)
    fixed = null
    fixedFacts = null
  }

  companion object {
    /** Larger files are not copied in */
    const val MAX_BYTES = 200L * 1024 * 1024
  }
}

@Composable
fun CheckSizeScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val vm: CheckSizeViewModel = viewModel { CheckSizeViewModel(app) }
  var saving by rememberSaveable { mutableStateOf(false) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pick(uri) }
  val choose = {
    try {
      picker.launch(arrayOf("application/pdf", "image/*"))
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, "No file picker found on this phone.", Toast.LENGTH_LONG).show()
    }
  }
  Scaffold(
    topBar = { DocTopBar("Check size", onBack, subtitle = "Any photo or PDF: KB, pages, pixels") },
    bottomBar = {
      if (vm.original != null) {
        BottomActions {
          OutlinedButton(onClick = choose, enabled = vm.busy == null, modifier = Modifier.height(52.dp)) { Text("Another file") }
          Button(
            onClick = { saving = true },
            enabled = vm.busy == null && vm.current != null,
            modifier = Modifier.weight(1f).height(52.dp),
          ) { Text("Save or share") }
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
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp),
    ) {
      val original = vm.original
      if (original == null) {
        VSpace(16)
        Text(
          "Pick a photo or a PDF to see its size in KB, its pages or its pixels. Give a limit (\"under 100 KB\") and it can be made to fit.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(16)
        Button(onClick = choose, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Choose a file") }
      } else {
        val kb = vm.maxKbText.toIntOrNull()
        SectionLabel("The file")
        SizeRow(original.name, original.copy(maxKb = kb), vm.originalFacts)
        SectionLabel("Limit")
        NumberField("Must be under (KB)", vm.maxKbText, { vm.limitChanged(it) }, modifier = Modifier.fillMaxWidth())
        if (original.mime.startsWith("image/")) {
          Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Keep the pixel size", style = MaterialTheme.typography.bodyMedium)
              Text(
                "For an exact size (a 300 × 300 photo): only the quality goes down",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(checked = vm.keepPixels, onCheckedChange = { vm.keepPixels = it })
          }
        }
        val tooBig = kb != null && original.file.length() > kb * 1024L
        if (kb != null && !tooBig && vm.fixed == null) {
          VSpace(10)
          ResultCard(true, "It fits", "${bytesText(original.file.length())} is under $kb KB: nothing to fix.")
        }
        if (tooBig) {
          VSpace(10)
          Button(onClick = { vm.fix() }, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Fix to fit") }
        }
        val fixed = vm.fixed
        if (fixed != null && fixed.file != original.file) {
          SectionLabel("After the fix")
          SizeRow(fixed.name, fixed, vm.fixedFacts)
          val fits = kb != null && fixed.file.length() <= kb * 1024L
          VSpace(6)
          ResultCard(
            fits,
            if (fits) "Fits now" else "Still too big",
            "Was ${bytesText(original.file.length())}, now ${bytesText(fixed.file.length())}." +
              if (fits) "" else " Try a higher limit, or a PDF with fewer pages.",
          )
        }
      }
      vm.busy?.let { BusyRow(it) }
      ErrorText(vm.error)
    }
  }
  val current = vm.current
  if (saving && current != null) {
    SaveSheet(listOf(current), defaultFolder = "DocReady", onDismiss = { saving = false })
  }
}
