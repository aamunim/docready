package com.docready.aamunim.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.PhotoBackground
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.data.model.label
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocPhoto
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.intake.IntakeTarget
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.ImageSourceButtons
import com.docready.aamunim.ui.components.NotesList
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.components.ResultCard
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

/** A made photo or signature: the file, and how it compares with the size asked */
class ImageResult(
  val ready: ReadyFile,
  val preview: ImageBitmap?,
  val width: Int,
  val height: Int,
  val bytes: Long,
  val maxKb: Int?,
  val fits: Boolean,
  val format: String,
  val notes: List<String>,
)

/** "Meets the preset" / "Can't reach 100 KB" card for an [ImageResult] */
@Composable
fun ImageResultCard(r: ImageResult, fromPreset: Boolean) {
  val size = "${r.width} × ${r.height} px"
  if (r.fits) {
    val limit = r.maxKb?.let { " (max $it)" } ?: ""
    ResultCard(
      ok = true,
      title = if (fromPreset) "Meets the preset" else "Ready",
      detail = "$size · ${bytesText(r.bytes)}$limit · ${r.format}",
    )
  } else {
    ResultCard(
      ok = false,
      title = "Can't get under ${r.maxKb} KB at this size",
      detail = "$size · ${bytesText(r.bytes)} even at the lowest quality. Try a plain background, a smaller size, or check the form's limit.",
    )
  }
}

class PhotoViewModel(app: Application, presetId: String?, itemIndex: Int) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()
  val preset = app.docReady.presets.get(presetId)
  private val item = preset?.items?.getOrNull(itemIndex)?.takeIf { it.kind == ItemKind.PHOTO }
    ?: preset?.items?.firstOrNull { it.kind == ItemKind.PHOTO }

  private var widthState by mutableStateOf((item?.widthPx ?: 300).toString())
  private var heightState by mutableStateOf((item?.heightPx ?: 300).toString())
  private var maxKbState by mutableStateOf(if (item != null) item.maxKb?.toString() ?: "" else "100")

  // a typed size / KB changes what Save would give: the shown result is out of date until "Apply"
  var widthText: String
    get() = widthState
    set(v) {
      widthState = v
      edited()
    }
  var heightText: String
    get() = heightState
    set(v) {
      heightState = v
      edited()
    }
  var maxKbText: String
    get() = maxKbState
    set(v) {
      maxKbState = v
      edited()
    }
  var background by mutableStateOf(item?.background ?: PhotoBackground.WHITE)
  var headSize by mutableFloatStateOf(70f)
  var enhance by mutableStateOf(true)
  var busy by mutableStateOf<String?>(null)
  var hasSource by mutableStateOf(false)
  var result by mutableStateOf<ImageResult?>(null)
  var error by mutableStateOf<String?>(null)

  /** The result no longer matches the settings (Save waits for the photo to be made again) */
  var stale by mutableStateOf(false)
    private set
  private var edits = 0

  private fun edited() {
    edits++
    stale = true
  }

  private val lock = Mutex()
  private var src: Bitmap? = null

  /** The photo with light and contrast evened, made once (levels before the background goes in) */
  private var levelled: Bitmap? = null
  private var faces: List<DocPhoto.FaceInfo> = emptyList()
  private var facesFound = false
  private var qualityNotes: List<String> = emptyList()
  private var faceNotes: List<String> = emptyList()
  private var mask: FloatArray? = null
  private var maskFound = false
  private var version = 0

  fun setSource(uri: Uri) = load { FileNames.copyIn(ctx, uri, "img") }

  fun setSourceFile(file: File) = load { file }

  private fun load(fileOf: () -> File) {
    viewModelScope.launch {
      busy = "Finding the face…"
      error = null
      result = null
      try {
        lock.withLock {
          withContext(Dispatchers.Default) {
            val f = fileOf()
            val b = DocTools.load(ctx, Uri.fromFile(f), 2000)
            val notes = mutableListOf<String>()
            val (sharpness, brightness) = DocTools.quality(ctx, Uri.fromFile(f))
            if (sharpness < 60) notes.add("The photo looks blurry. A sharper photo gives a better result.")
            if (brightness < 60) notes.add("The photo is dark. Take it in good, even light.")
            qualityNotes = notes
            src = b
            levelled = null
            faces = emptyList()
            facesFound = false
            faceNotes = emptyList()
            mask = null
            maskFound = false
            findFacesIfNeeded(b)
          }
        }
        hasSource = true
        busy = null
        process()
      } catch (e: Throwable) {
        error = messageOf(e, "The photo could not be opened.")
        busy = null
      }
    }
  }

  /** Faces: found once; when the finder could not run (still downloading), tried again next time */
  private fun findFacesIfNeeded(s: Bitmap) {
    if (facesFound) return
    val notes = mutableListOf<String>()
    val found = DocPhoto.findFaces(s, notes)
    faceNotes = notes
    if (found != null) {
      faces = found
      facesFound = true
    }
  }

  /** The photo made again with the current settings */
  fun process() {
    val w = widthText.toIntOrNull()
    val h = heightText.toIntOrNull()
    val kb = maxKbText.toIntOrNull()
    if (w == null || h == null || w !in 50..4000 || h !in 50..4000) {
      error = "Width and height: 50 to 4000 px."
      return
    }
    if (kb != null && kb !in 5..50_000) {
      error = "Max KB: 5 to 50000 (or leave it empty for no limit)."
      return
    }
    if (src == null) return
    val bg = background
    val pct = headSize
    val enh = enhance
    val my = ++version
    val editsNow = edits
    stale = true
    viewModelScope.launch {
      busy = if (bg.argb != null && !maskFound) "Changing the background…" else "Making the photo…"
      error = null
      try {
        val r = lock.withLock {
          withContext(Dispatchers.Default) {
            val s = src ?: return@withContext null
            findFacesIfNeeded(s)
            if (bg.argb != null && !maskFound) {
              // a failure (the model still downloading) is tried again on the next change
              try {
                mask = DocPhoto.personMask(s)
                maskFound = true
              } catch (_: Exception) {
                mask = null
              }
            }
            val notes = (qualityNotes + faceNotes).toMutableList()
            if (bg.argb != null && mask == null) {
              notes.add(
                if (maskFound) "No person found to separate from the background: kept as it is."
                else "The background could not be changed now (Google's model may still be downloading): kept as it is. Try again in a minute.",
              )
            }
            // light and contrast are evened on the photo itself, before the new background goes
            // in (evening it after would stretch a light blue / grey background to white)
            val base = if (enh) levelled ?: DocTools.autoLevels(s.copy(Bitmap.Config.ARGB_8888, true)).also { levelled = it } else s
            val made = DocPhoto.make(base, w, h, 2000, pct, bg.argb, faces.firstOrNull(), mask, notes)
            val fit = DocTools.fitPicture(made.bitmap, kb?.let { it * 1024L }, true)
            val name = "photo_${w}x$h.jpg"
            val file = FileNames.outputFile(ctx, name)
            file.writeBytes(fit.bytes)
            val preview = BitmapFactory.decodeByteArray(fit.bytes, 0, fit.bytes.size)?.asImageBitmap()
            ImageResult(
              ReadyFile(file, name, "image/jpeg"), preview, fit.width, fit.height,
              fit.bytes.size.toLong(), kb, fit.fits, "JPG", notes,
            )
          }
        }
        if (my == version && r != null) {
          result = r
          stale = edits != editsNow
        }
      } catch (e: Throwable) {
        if (my == version) error = messageOf(e, "The photo could not be made.")
      } finally {
        if (my == version) busy = null
      }
    }
  }
}

/** Shown while the result on screen no longer matches what was changed */
@Composable
fun StaleNote(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 8.dp),
  )
}

fun messageOf(e: Throwable, fallback: String): String =
  if (e is OutOfMemoryError) "The picture is too big for this phone's memory." else e.message ?: fallback

@Composable
fun PhotoScreen(presetId: String?, itemIndex: Int, onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as Application
  val vm: PhotoViewModel = viewModel { PhotoViewModel(app, presetId, itemIndex) }
  val source = rememberImageSource { vm.setSource(it) }
  var saving by rememberSaveable { mutableStateOf(false) }
  val intake by Intake.request.collectAsState()
  LaunchedEffect(intake) {
    if (intake?.target == IntakeTarget.PHOTO) Intake.take(IntakeTarget.PHOTO).firstOrNull()?.let { vm.setSourceFile(it.file) }
  }

  Scaffold(
    topBar = {
      DocTopBar("Photo", onBack, subtitle = vm.preset?.let { "Preset: ${it.name}" } ?: "Face crop, background, exact size")
    },
    bottomBar = {
      if (vm.hasSource) {
        BottomActions {
          OutlinedButton(onClick = source.takePhoto, modifier = Modifier.height(52.dp)) { Text("Retake") }
          Button(
            onClick = { saving = true },
            enabled = vm.result != null && !vm.stale && vm.busy == null,
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
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      if (!vm.hasSource) {
        Text(
          "Take a photo against a plain wall in good light, or choose one. DocReady finds the face, " +
            "centres it, changes the background and makes the exact size.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(16)
        ImageSourceButtons(source)
        if (vm.busy != null) BusyRow(vm.busy ?: "")
        ErrorText(vm.error)
      } else {
        PhotoEditor(vm)
      }
    }
  }

  val ready = vm.result?.ready
  if (saving && ready != null) {
    SaveSheet(listOf(ready), defaultFolder = vm.preset?.name ?: "DocReady", onDismiss = { saving = false })
  }
}

@Composable
private fun PhotoEditor(vm: PhotoViewModel) {
  Column {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.size(220.dp),
        ) {
          Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            val preview = vm.result?.preview
            if (preview != null) {
              Image(preview, contentDescription = "The ready photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
          }
        }
      }
      if (vm.busy != null) BusyRow(vm.busy ?: "")
      vm.result?.let { r ->
        VSpace(12)
        ImageResultCard(r, fromPreset = vm.preset != null)
        NotesList(r.notes)
        if (vm.stale && vm.busy == null) StaleNote("The size changed: tap “Apply size” to make the photo again.")
      }
      ErrorText(vm.error)

      SectionLabel("Background")
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (bg in PhotoBackground.entries) {
          val selected = vm.background == bg
          val argb = bg.argb
          if (argb != null) {
            Box(
              Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(
                  if (selected) 3.dp else 1.dp,
                  if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                  CircleShape,
                )
                .clickable {
                  vm.background = bg
                  vm.process()
                }
                .semantics { contentDescription = bg.label + if (selected) ", selected" else "" },
            )
          } else {
            OutlinedButton(
              onClick = {
                vm.background = bg
                vm.process()
              },
              border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
              modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text(bg.label) }
          }
        }
      }

      SectionLabel("Size for this time")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Width px", vm.widthText, { vm.widthText = it }, Modifier.weight(1f))
        NumberField("Height px", vm.heightText, { vm.heightText = it }, Modifier.weight(1f))
        NumberField("Max KB", vm.maxKbText, { vm.maxKbText = it }, Modifier.weight(1f))
      }

      SectionLabel("Head size: ${vm.headSize.toInt()}% of the height")
      Slider(
        value = vm.headSize,
        onValueChange = { vm.headSize = it },
        onValueChangeFinished = { vm.process() },
        valueRange = 50f..85f,
      )
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Even out light and contrast", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = vm.enhance, onCheckedChange = {
          vm.enhance = it
          vm.process()
        })
      }
      VSpace(8)
      OutlinedButton(onClick = { vm.process() }, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth()) {
        Text("Apply size", fontWeight = FontWeight.SemiBold)
      }
      VSpace(8)
      Text(
        "Check the result against the form's own instructions before you submit.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
