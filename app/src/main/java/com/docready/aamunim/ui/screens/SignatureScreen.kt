package com.docready.aamunim.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.SignatureTools
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.intake.IntakeTarget
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.CropBox
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.ImageSourceButtons
import com.docready.aamunim.ui.components.NormBox
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.components.SaveSheet
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import com.docready.aamunim.ui.components.rememberImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SignatureViewModel(app: Application, presetId: String?, itemIndex: Int) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()
  val preset = app.docReady.presets.get(presetId)
  private val item = preset?.items?.getOrNull(itemIndex)?.takeIf { it.kind == ItemKind.SIGNATURE }
    ?: preset?.items?.firstOrNull { it.kind == ItemKind.SIGNATURE }

  var widthText by mutableStateOf((item?.widthPx ?: 300).toString())
  var heightText by mutableStateOf((item?.heightPx ?: 80).toString())
  var maxKbText by mutableStateOf(if (item != null) item.maxKb?.toString() ?: "" else "60")
  var transparent by mutableStateOf(item?.format == OutputFormat.PNG)
  var box by mutableStateOf(NormBox.Default)
  var picture by mutableStateOf<ImageBitmap?>(null)
  var busy by mutableStateOf<String?>(null)
  var result by mutableStateOf<ImageResult?>(null)
  var error by mutableStateOf<String?>(null)

  private val lock = Mutex()
  private var src: Bitmap? = null

  fun setSource(uri: Uri) = load { FileNames.copyIn(ctx, uri, "img") }

  fun setSourceFile(file: File) = load { file }

  private fun load(fileOf: () -> File) {
    viewModelScope.launch {
      busy = "Opening the photo…"
      error = null
      result = null
      try {
        val shown = lock.withLock {
          withContext(Dispatchers.Default) {
            val b = DocTools.load(ctx, Uri.fromFile(fileOf()), 2000)
            src = b
            showable(b)
          }
        }
        box = NormBox.Default
        picture = shown
      } catch (e: Throwable) {
        error = messageOf(e, "The photo could not be opened.")
      } finally {
        busy = null
      }
    }
  }

  /** A copy small enough to show while cropping */
  private fun showable(b: Bitmap): ImageBitmap {
    val k = minOf(1f, 1200f / maxOf(b.width, b.height))
    return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true).asImageBitmap()
  }

  /** A quarter turn clockwise (a photo taken sideways) */
  fun rotate() {
    viewModelScope.launch {
      try {
        val shown = lock.withLock {
          withContext(Dispatchers.Default) {
            val b = src ?: return@withContext null
            val m = Matrix().apply { postRotate(90f) }
            val turned = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            src = turned
            showable(turned)
          }
        }
        if (shown != null) {
          picture = shown
          box = NormBox.Default
          result = null
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The photo could not be turned.")
      }
    }
  }

  fun process() {
    val w = widthText.toIntOrNull()
    val h = heightText.toIntOrNull()
    val kb = maxKbText.toIntOrNull()
    if (w == null || h == null || w !in 20..4000 || h !in 20..4000) {
      error = "Width and height: 20 to 4000 px."
      return
    }
    if (kb != null && kb !in 2..50_000) {
      error = "Max KB: 2 to 50000 (or leave it empty for no limit)."
      return
    }
    val cut = box
    val png = transparent
    viewModelScope.launch {
      busy = "Cleaning the signature…"
      error = null
      try {
        val r = lock.withLock {
          withContext(Dispatchers.Default) {
            val s = src ?: throw IllegalStateException("Take or choose a photo of the signature first.")
            var piece = DocTools.cut(s, cut.left, cut.top, cut.width, cut.height)
            if (piece === s) piece = s.copy(Bitmap.Config.ARGB_8888, true)
            val ink = SignatureTools.inkOf(piece)
              ?: throw IllegalStateException("No signature found: put the box closely around the signature, on plain paper.")
            val placed = SignatureTools.place(ink, w, h, png)
            ink.recycle()
            val maxBytes = kb?.let { it * 1024L }
            val bytes: ByteArray
            val fits: Boolean
            val name: String
            if (png) {
              bytes = DocTools.png(placed)
              placed.recycle()
              fits = maxBytes == null || bytes.size <= maxBytes
              name = "signature_${w}x$h.png"
            } else {
              val fit = DocTools.fitPicture(placed, maxBytes, true)
              bytes = fit.bytes
              fits = fit.fits
              name = "signature_${w}x$h.jpg"
            }
            val file = FileNames.outputFile(ctx, name)
            file.writeBytes(bytes)
            ImageResult(
              ReadyFile(file, name, if (png) "image/png" else "image/jpeg"),
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap(),
              w, h, bytes.size.toLong(), kb, fits, if (png) "PNG" else "JPG", emptyList(),
            )
          }
        }
        result = r
      } catch (e: Throwable) {
        error = messageOf(e, "The signature could not be made.")
      } finally {
        busy = null
      }
    }
  }
}

@Composable
fun SignatureScreen(presetId: String?, itemIndex: Int, onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as Application
  val vm: SignatureViewModel = viewModel { SignatureViewModel(app, presetId, itemIndex) }
  val source = rememberImageSource { vm.setSource(it) }
  var saving by rememberSaveable { mutableStateOf(false) }
  val intake by Intake.request.collectAsState()
  LaunchedEffect(intake) {
    if (intake?.target == IntakeTarget.SIGNATURE) Intake.take(IntakeTarget.SIGNATURE).firstOrNull()?.let { vm.setSourceFile(it.file) }
  }

  Scaffold(
    topBar = { DocTopBar("Signature", onBack, subtitle = vm.preset?.let { "Preset: ${it.name}" } ?: "Cut and clean") },
    bottomBar = {
      if (vm.picture != null) {
        BottomActions {
          OutlinedButton(onClick = { vm.process() }, enabled = vm.busy == null, modifier = Modifier.height(52.dp)) {
            Text(if (vm.result == null) "Make it" else "Make again")
          }
          Button(
            onClick = { saving = true },
            enabled = vm.result != null && vm.busy == null,
            modifier = Modifier.weight(1f).height(52.dp),
          ) { Text("Save or share") }
        }
      }
    },
  ) { padding ->
    Column(
      Modifier
        .padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      val picture = vm.picture
      if (picture == null) {
        Text(
          "Sign in dark ink on plain white paper, then take a photo of it from straight above, " +
            "or choose one. Only the ink is kept.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(16)
        ImageSourceButtons(source)
      } else {
        Text(
          "Drag the corners so the box fits closely around the signature.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(10)
        CropBox(picture, vm.box, { vm.box = it })
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { vm.rotate() }, enabled = vm.busy == null) {
            Icon(AppIcons.Rotate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Turn")
          }
          OutlinedButton(onClick = source.pickPhoto) { Text("Other photo") }
          OutlinedButton(onClick = source.takePhoto) { Text("Retake") }
        }

        SectionLabel("Background")
        ChoiceChips(
          options = listOf(false, true),
          selected = vm.transparent,
          label = { if (it) "See-through (PNG)" else "White (JPG)" },
          onSelect = { vm.transparent = it },
        )

        SectionLabel("Size")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          NumberField("Width px", vm.widthText, { vm.widthText = it }, Modifier.weight(1f))
          NumberField("Height px", vm.heightText, { vm.heightText = it }, Modifier.weight(1f))
          NumberField("Max KB", vm.maxKbText, { vm.maxKbText = it }, Modifier.weight(1f))
        }

        vm.result?.let { r ->
          SectionLabel("Result")
          Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Box(Modifier.padding(12.dp).heightIn(max = 160.dp).background(Color.White), contentAlignment = Alignment.Center) {
              r.preview?.let { Image(it, contentDescription = "The ready signature", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) }
            }
          }
          VSpace(10)
          ImageResultCard(r, fromPreset = vm.preset != null)
        }
      }
      if (vm.busy != null) BusyRow(vm.busy ?: "")
      ErrorText(vm.error)
    }
  }

  val ready = vm.result?.ready
  if (saving && ready != null) {
    SaveSheet(listOf(ready), defaultFolder = vm.preset?.name ?: "DocReady", onDismiss = { saving = false })
  }
}
