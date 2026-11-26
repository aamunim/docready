package com.docready.aamunim.ui.screens

import android.app.Application
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.PresetQr
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.summary
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.QrCodes
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.save.SaveOutcome
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.ResultCard
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.common.MlKitException
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- showing a preset's codes

/** A preset as QR codes: one for most presets; a big one in parts ("1 of 3"), swiped or played */
@Composable
fun PresetQrScreen(id: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val app = context.docReady
  val preset = remember(id) { app.presets.get(id) }
  var codes by remember { mutableStateOf<List<Pair<String, Bitmap>>?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var saving by remember { mutableStateOf(false) }
  var auto by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(preset) {
    val p = preset ?: return@LaunchedEffect
    try {
      codes = withContext(Dispatchers.Default) { PresetQr.encode(p).map { it to QrCodes.bitmap(it) } }
    } catch (e: Throwable) {
      error = messageOf(e, "The QR code could not be made.")
    }
  }
  val list = codes
  val pager = rememberPagerState { list?.size ?: 0 }
  // auto-advance: the next part every 2.5 s (a phone scanning them all in a row)
  LaunchedEffect(auto, list?.size) {
    val n = list?.size ?: 0
    while (auto && n > 1) {
      delay(2500)
      pager.animateScrollToPage((pager.currentPage + 1) % n)
    }
  }
  Scaffold(
    topBar = { DocTopBar("Preset QR code", onBack, subtitle = preset?.name) },
  ) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      when {
        preset == null -> Text("This preset is no longer here.")
        error != null -> ErrorText(error)
        list == null -> BusyRow("Making the code…")
        else -> {
          Text(
            if (list.size == 1) {
              "On the other phone: Presets → Scan QR."
            } else {
              "This preset needs ${list.size} codes. On the other phone: Presets → Scan QR, then scan each one (any order)."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          VSpace(12)
          HorizontalPager(pager, modifier = Modifier.fillMaxWidth()) { i ->
            // always black on white, whatever the theme: a code scanners read
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
              Image(
                list[i].second.asImageBitmap(),
                contentDescription = "QR code ${i + 1} of ${list.size}",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
              )
            }
          }
          if (list.size > 1) {
            Text(
              "${pager.currentPage + 1} of ${list.size} · swipe for the next",
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              Text("Show each part in turn", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
              Switch(checked = auto, onCheckedChange = { auto = it })
            }
          }
          VSpace(16)
          OutlinedButton(
            onClick = {
              saving = true
              scope.launch {
                val r = try {
                  saveQrImages(context.applicationContext as Application, preset, list)
                } catch (e: Throwable) {
                  SaveOutcome.Failed(messageOf(e, "The QR image could not be saved."))
                }
                saving = false
                Toast.makeText(
                  context,
                  when (r) {
                    is SaveOutcome.Saved -> "Saved in ${r.where}"
                    is SaveOutcome.Failed -> r.message
                    else -> "Saved"
                  },
                  Toast.LENGTH_LONG,
                ).show()
              }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
          ) { Text(if (saving) "Saving…" else if (list.size == 1) "Save QR image" else "Save QR images (${list.size})") }
          Text(
            "Saved in Downloads / DocReady / ${preset.name}, with the preset's name under each code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
      }
    }
  }
}

/** The codes as PNG pictures (with a caption) saved to Downloads / DocReady / <preset> */
private suspend fun saveQrImages(context: Application, preset: Preset, codes: List<Pair<String, Bitmap>>): SaveOutcome {
  val files = withContext(Dispatchers.Default) {
    codes.mapIndexed { i, (_, code) ->
      val part = if (codes.size == 1) "" else " · ${i + 1} of ${codes.size}"
      val picture = QrCodes.withCaption(code, "DocReady preset: ${preset.name}$part")
      val base = FileNames.safeBase(preset.name) + "_QR" + (if (codes.size == 1) "" else "_${i + 1}_of_${codes.size}")
      val out = FileNames.outputFile(context, "$base.png")
      out.writeBytes(DocTools.png(picture))
      picture.recycle()
      ReadyFile(out, out.name, "image/png")
    }
  }
  return context.docReady.saveTargets.phone.save(context, files, preset.name)
}

// ---------------------------------------------------------------- reading codes

class PresetQrImportViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()
  private val collector = PresetQr.Collector()

  var scanned by mutableStateOf(0)
    private set
  var total by mutableStateOf(0)
    private set
  var note by mutableStateOf<String?>(null)
  var error by mutableStateOf<String?>(null)
  var busy by mutableStateOf<String?>(null)
    private set

  /** The preset read from the codes, waiting for Import / Cancel */
  var preview by mutableStateOf<Preset?>(null)
    private set

  val complete: Boolean get() = preview != null

  /** Codes read (from the camera or a picture): each added; when all are in, the preset is read */
  fun addAll(texts: List<String>) {
    if (preview != null) return
    var new = 0
    var other = 0
    var notOurs = 0
    for (t in texts) {
      when (collector.add(t)) {
        PresetQr.Added.NEW -> new++
        PresetQr.Added.ALREADY -> Unit
        PresetQr.Added.OTHER_PRESET -> other++
        PresetQr.Added.NOT_OURS -> notOurs++
      }
    }
    scanned = collector.count
    total = collector.total
    error = when {
      other > 0 -> "That code is part of another preset: scan the parts of one preset at a time (or Start again)."
      new == 0 && notOurs > 0 -> "That is not a DocReady preset code."
      else -> null
    }
    note = if (new == 0 && other == 0 && notOurs == 0 && texts.isNotEmpty()) "That part was already scanned." else null
    if (collector.complete) {
      try {
        preview = collector.preset()
      } catch (e: IllegalArgumentException) {
        error = e.message ?: "This is not a DocReady preset."
        collector.clear()
        scanned = 0
        total = 0
      } catch (_: Throwable) {
        // (never a crash on a strange code)
        error = "This is not a DocReady preset."
        collector.clear()
        scanned = 0
        total = 0
      }
    }
  }

  /** Codes in a picture (a photo or a screenshot of the codes) */
  fun readImage(uri: Uri) {
    viewModelScope.launch {
      busy = "Looking for QR codes in the picture…"
      error = null
      try {
        val found = withContext(Dispatchers.Default) {
          val b = DocTools.load(ctx, uri, 2400)
          try {
            QrCodes.decode(b)
          } finally {
            b.recycle()
          }
        }
        if (found.isEmpty()) error = "No QR code found in this picture. Try a sharper picture with the whole code in it." else addAll(found)
      } catch (e: Throwable) {
        error = messageOf(e, "The picture could not be read.")
      } finally {
        busy = null
      }
    }
  }

  fun startAgain() {
    collector.clear()
    scanned = 0
    total = 0
    preview = null
    error = null
    note = null
  }
}

/** "Scan QR": the camera (Google's code scanner) or a picture; parts in any order; a preview first */
@Composable
fun PresetQrImportScreen(onBack: () -> Unit, onImported: (String) -> Unit) {
  val context = LocalContext.current
  val app = context.docReady
  val appContext = context.applicationContext as Application
  val vm: PresetQrImportViewModel = viewModel { PresetQrImportViewModel(appContext) }
  var clash by remember { mutableStateOf<Preset?>(null) }

  fun askForScanner(options: GmsBarcodeScannerOptions) {
    try {
      ModuleInstall.getClient(context).installModules(
        ModuleInstallRequest.newBuilder().addApi(GmsBarcodeScanning.getClient(context, options)).build(),
      )
    } catch (_: Exception) {
    }
  }

  fun scanWithCamera() {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    try {
      GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code ->
          vm.addAll(listOfNotNull(code.rawValue))
          // more parts to go: the scanner opens again for the next one (Back stops it)
          if (!vm.complete && vm.total > 1 && vm.error == null) scanWithCamera()
        }
        .addOnFailureListener { e ->
          if ((e as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE) {
            // its module not downloaded yet (a fresh install): asked for now, by Play services
            // itself (the app has no network permission)
            askForScanner(options)
            vm.error = "The scanner is getting ready (Google Play is downloading it). Try again in a minute, or use “From a picture”."
          } else {
            vm.error = "The scanner could not start: ${e.message ?: "unknown problem"}. Try “From a picture”."
          }
        }
    } catch (e: Exception) {
      vm.error = "The code scanner is not available on this phone. Use “From a picture”."
    }
  }

  val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.readImage(uri) }

  fun save(p: Preset, replace: Preset?) {
    val saved = if (replace != null) app.presets.replace(replace.id, p) else app.presets.addNew(p)
    Toast.makeText(context, "Preset “${saved.name}” imported.", Toast.LENGTH_SHORT).show()
    onImported(saved.id)
  }

  Scaffold(topBar = { DocTopBar("Scan preset QR", onBack) }) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
      val preview = vm.preview
      if (preview == null) {
        Text(
          "On the phone that has the preset: Presets → the preset → Share → Show QR. A big preset has several codes: scan them all, in any order.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(16)
        Button(onClick = { scanWithCamera() }, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Scan with the camera") }
        VSpace(8)
        OutlinedButton(
          onClick = {
            try {
              pickImage.launch("image/*")
            } catch (_: ActivityNotFoundException) {
              Toast.makeText(context, "No photo picker found on this phone.", Toast.LENGTH_LONG).show()
            }
          },
          enabled = vm.busy == null,
          modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("From a picture (photo or screenshot)") }
        if (vm.total > 1) {
          VSpace(16)
          Text("${vm.scanned} of ${vm.total} scanned", fontWeight = FontWeight.SemiBold)
          LinearProgressIndicator(progress = { vm.scanned.toFloat() / vm.total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
          TextButton(onClick = { vm.startAgain() }) { Text("Start again") }
        }
        vm.busy?.let { BusyRow(it) }
        vm.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        ErrorText(vm.error)
      } else {
        ResultCard(true, "Preset found: ${preview.name}", "${preview.items.size} item${if (preview.items.size == 1) "" else "s"}. Check it, then import it.")
        SectionLabel("Items")
        for (item in preview.items) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
          ) {
            Column(Modifier.padding(12.dp)) {
              Text(item.summary(), style = MaterialTheme.typography.bodyMedium)
              if (item.keywords.isNotEmpty()) {
                Text(
                  "Keywords: " + item.keywords.joinToString(", "),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = { vm.startAgain() }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Cancel") }
          Button(
            onClick = {
              val same = app.presets.named(preview.name)
              if (same != null) clash = same else save(preview, null)
            },
            modifier = Modifier.weight(1f).height(52.dp),
          ) { Text("Import") }
        }
      }
    }
  }
  val existing = clash
  val p = vm.preview
  if (existing != null && p != null) {
    AlertDialog(
      onDismissRequest = { clash = null },
      title = { Text("“${existing.name}” is already here") },
      text = {
        // (items are matched by kind and name, then by place: students' ticks stay with them)
        val plan = remember(existing.id, p) { app.presets.replacePlan(existing.id, p) }
        val lost = plan.unmatched
        Text(
          "Replace your preset with this one, or keep both (this one gets a new name)?" +
            (if (plan.renamed > 0) " ${plan.renamed} item renamed: its ticks are kept." else "") +
            (if (lost > 0) " $lost of your preset's items have no match in this one: students' ticks for them will be lost." else ""),
        )
      },
      confirmButton = {
        TextButton(onClick = {
          clash = null
          save(p, existing)
        }) { Text("Replace") }
      },
      dismissButton = {
        TextButton(onClick = {
          clash = null
          save(p.copy(name = app.presets.freeName(p.name)), null)
        }) { Text("Keep both") }
      },
    )
  }
}
