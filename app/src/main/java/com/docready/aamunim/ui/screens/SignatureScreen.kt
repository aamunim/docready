package com.docready.aamunim.ui.screens

import com.docready.aamunim.intake.Handoff
import com.docready.aamunim.data.DraftRepository
import android.app.Application
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
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
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.imaging.PageRenderer
import com.docready.aamunim.ui.editor.EditorMode
import com.docready.aamunim.ui.editor.EditorPage
import com.docready.aamunim.ui.editor.PageEditorScreen
import com.docready.aamunim.ui.editor.PageEditorState
import com.docready.aamunim.data.model.PresetItem
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.PdfTools
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

class SignatureViewModel(app: Application, presetId: String?, itemIndex: Int, studentId: String? = null) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()
  val preset = app.docReady.presets.get(presetId)
  private val item = preset?.items?.getOrNull(itemIndex)?.takeIf { it.kind == ItemKind.SIGNATURE }
    ?: preset?.items?.firstOrNull { it.kind == ItemKind.SIGNATURE }

  /** The item's place in its preset (a student's checklist), or null */
  val itemPos: Int? = item?.let { preset?.items?.indexOf(it) }?.takeIf { it >= 0 }

  private var widthState by mutableStateOf((item?.widthPx ?: 300).toString())
  private var heightState by mutableStateOf((item?.heightPx ?: 80).toString())
  private var maxKbState by mutableStateOf(if (item != null) item.maxKb?.toString() ?: "" else "60")
  private var transparentState by mutableStateOf(item?.format == OutputFormat.PNG)
  private var boxState by mutableStateOf(NormBox.Default)

  // any change makes the shown result out of date: Save waits until it is made again
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
  /** The sizes of a preset's signature item, picked on this screen */
  fun applyItem(it: PresetItem) {
    it.widthPx?.let { w -> widthText = w.toString() }
    it.heightPx?.let { h -> heightText = h.toString() }
    maxKbText = it.maxKb?.toString() ?: ""
    transparent = it.format == OutputFormat.PNG
  }

  var transparent: Boolean
    get() = transparentState
    set(v) {
      transparentState = v
      edited()
    }
  var box: NormBox
    get() = boxState
    set(v) {
      boxState = v
      edited()
    }

  /** The result no longer matches the settings */
  var stale by mutableStateOf(false)
    private set
  private var edits = 0

  private fun edited() {
    edits++
    stale = true
  }
  var picture by mutableStateOf<ImageBitmap?>(null)
  var busy by mutableStateOf<String?>(null)
  var result by mutableStateOf<ImageResult?>(null)
  var error by mutableStateOf<String?>(null)

  private val lock = Mutex()
  private var src: Bitmap? = null

  fun setSource(uri: Uri) = load({ FileNames.copyIn(ctx, uri, "img") }, PageRecipe.Plain)

  fun setSourceFile(file: File) = load({ file }, PageRecipe.Plain)

  // ---- the signature photo's own edits (crop, rotate, erase, contrast) in the shared editor
  private var sourceFile: File? = null
  private var sourceRecipe = PageRecipe.Plain
  var editor by mutableStateOf<PageEditorState?>(null)
    private set

  fun openEditor() {
    val f = sourceFile ?: return
    editor = PageEditorState(
      viewModelScope,
      listOf(EditorPage("Signature photo", sourceRecipe) { side -> DocTools.load(ctx, Uri.fromFile(f), side) }),
      0,
      EditorMode.SIGNATURE,
      onDone = { done ->
        editor = null
        if (done[0] != sourceRecipe) load({ f }, done[0])
      },
      onClose = { editor = null },
    )
  }

  private fun load(fileOf: () -> File, recipe: PageRecipe) {
    viewModelScope.launch {
      busy = "Opening the photo…"
      error = null
      result = null
      try {
        val shown = lock.withLock {
          withContext(Dispatchers.Default) {
            val f = fileOf()
            val b = DocTools.mutable(PageRenderer.render(DocTools.load(ctx, Uri.fromFile(f), 2000), recipe))
            sourceFile = f
            sourceRecipe = recipe
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
  /** A quarter turn clockwise (a photo taken sideways): part of the photo's edits */
  fun rotate() {
    val f = sourceFile ?: return
    load({ f }, sourceRecipe.copy(turn = (sourceRecipe.turn + 90) % 360))
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
    val editsNow = edits
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
            // the cleaned ink is kept, to be placed on a form's page
            val shownInk = DocTools.scaleToMax(ink.copy(Bitmap.Config.ARGB_8888, false), 600).asImageBitmap()
            withContext(Dispatchers.Main) { setInk(ink, shownInk) }
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
              ReadyFile(file, name, if (png) "image/png" else "image/jpeg", kb, exactSize = true, itemIndex = itemPos),
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap(),
              w, h, bytes.size.toLong(), kb, fits, if (png) "PNG" else "JPG", emptyList(),
            )
          }
        }
        result = r
        stale = edits != editsNow
      } catch (e: Throwable) {
        error = messageOf(e, "The signature could not be made.")
      } finally {
        busy = null
      }
    }
  }

  // ---- the signature placed on a form's page (the ported placeSignature)
  private var ink: Bitmap? = null
  var inkPreview by mutableStateOf<ImageBitmap?>(null)
    private set
  private var inkRatio = 0.3f

  /** The form: a photo, or a PDF whose pages are rendered one at a time when shown */
  private var formSource: File? = null
  private var formIsPdf = false

  /** The form's pages (a photo: 1; a PDF: up to [PdfTools.PAGE_LIMIT]) */
  var formCount by mutableStateOf(0)
    private set
  var formIndex by mutableStateOf(0)
    private set

  /** The page shown, as a picture file (the signature is drawn on it) */
  private var formPageFile: File? = null
  var formImage by mutableStateOf<ImageBitmap?>(null)
    private set
  private var formW = 1
  private var formH = 1
  private var placeBoxState by mutableStateOf(NormBox.Default)
  var placeBox: NormBox
    get() = placeBoxState
    set(v) {
      placeBoxState = v
      placeResult = null
    }
  var placeFormat by mutableStateOf(OutputFormat.PDF)
  var placeMaxKbText by mutableStateOf("")
  var placeResult by mutableStateOf<FilesResult?>(null)

  private fun setInk(b: Bitmap, shown: ImageBitmap) {
    ink = b // (the old one may still be drawn: Android frees it)
    inkPreview = shown
    inkRatio = b.height.toFloat() / maxOf(1, b.width)
    placeResult = null
    if (formImage != null) placeBoxState = startBox()
  }

  /** The signature box's height / width in parts of the page */
  fun placeRatio(): Float = inkRatio * formW / maxOf(1, formH)

  private fun startBox(): NormBox {
    val r = placeRatio()
    var w = 0.35f
    var h = w * r
    if (h > 0.2f) {
      h = 0.2f
      w = h / r
    }
    val l = 0.6f.coerceAtMost(1f - w)
    val t = 0.75f.coerceAtMost(1f - h)
    return NormBox(l, t, l + w, t + h)
  }

  /** A form page chosen: a photo, or a PDF (its pages rendered, the first shown) */
  fun openForm(uri: Uri) {
    viewModelScope.launch {
      busy = "Opening the page…"
      error = null
      try {
        val (file, pdf, count) = withContext(Dispatchers.IO) {
          val type = ctx.contentResolver.getType(uri) ?: ""
          val name = FileNames.displayName(ctx, uri)?.lowercase() ?: ""
          if (type == "application/pdf" || name.endsWith(".pdf")) {
            val f = FileNames.copyIn(ctx, uri, "pdf")
            Triple(f, true, PdfTools.pageCount(f))
          } else {
            Triple(FileNames.copyIn(ctx, uri, "img"), false, 1)
          }
        }
        if (count == 0) throw IllegalStateException("This PDF has no pages.")
        if (count > PdfTools.PAGE_LIMIT) error = "This PDF has $count pages: only the first ${PdfTools.PAGE_LIMIT} can be used."
        // the form before: its page file and edits must not carry over to this one
        formRequest++
        formPageFile?.let { old -> if (formIsPdf && old.exists()) old.delete() }
        formPageFile = null
        formIndex = 0
        formRecipe = PageRecipe.Plain
        formSource = file
        formIsPdf = pdf
        formCount = minOf(count, PdfTools.PAGE_LIMIT)
        // (in this coroutine: busy stays on until the first page is shown)
        loadFormPage(0)
      } catch (e: Throwable) {
        error = messageOf(e, "The page could not be opened.")
      } finally {
        busy = null
      }
    }
  }

  /** The latest page asked for: an older render that finishes later is thrown away */
  private var formRequest = 0

  fun showFormPage(index: Int) {
    viewModelScope.launch { loadFormPage(index) }
  }

  /** The form page's own edits (the shared page editor); a new page starts as it is */
  private var formRecipe = PageRecipe.Plain

  fun openFormEditor() {
    val f = formPageFile ?: return
    editor = PageEditorState(
      viewModelScope,
      listOf(EditorPage("Form page ${formIndex + 1}", formRecipe) { side -> DocTools.load(ctx, Uri.fromFile(f), side) }),
      0,
      EditorMode.DOCUMENT,
      onDone = { done ->
        editor = null
        if (done[0] != formRecipe) viewModelScope.launch { loadFormPage(formIndex, done[0]) }
      },
      onClose = { editor = null },
    )
  }

  private suspend fun loadFormPage(index: Int, recipe: PageRecipe = PageRecipe.Plain) {
    val src = formSource ?: return
    if (index !in 0 until formCount) return
    val pdf = formIsPdf
    // the same page again (its edits changed): its page file is used as it is
    val reuse = formPageFile?.takeIf { index == formIndex && it.exists() }
    val my = ++formRequest
    busy = "Opening page ${index + 1}…"
    try {
      val (file, b) = withContext(Dispatchers.Default) {
        // a PDF page is made only when it is shown
        val f = if (reuse != null) {
          reuse
        } else if (pdf) {
          val page = PdfTools.renderPage(src, index, 2000)
          try {
            DocTools.save(ctx, DocTools.jpeg(page, 92), "jpg")
          } finally {
            page.recycle()
          }
        } else {
          src
        }
        f to PageRenderer.render(DocTools.load(ctx, Uri.fromFile(f), 1400), recipe)
      }
      if (my != formRequest || formSource !== src) {
        if (file !== src) file.delete() // a newer page was asked for meanwhile
        return
      }
      // the page shown before is no longer needed (one page file at a time)
      formPageFile?.let { old -> if (old != src && old != file) old.delete() }
      formPageFile = file
      formRecipe = recipe
      formW = b.width
      formH = b.height
      formIndex = index
      formImage = b.asImageBitmap()
      placeBoxState = startBox()
      placeResult = null
    } catch (e: Throwable) {
      if (my == formRequest) error = messageOf(e, "The page could not be opened.")
    } finally {
      if (my == formRequest) busy = null
    }
  }

  fun closeForm() {
    formRequest++
    formPageFile?.let { if (formIsPdf) it.delete() }
    formSource = null
    formPageFile = null
    formCount = 0
    formImage = null
    placeResult = null
  }

  /** The page with the signature drawn into its box, as a JPG or a one-page PDF */
  fun makePage() {
    val signature = ink ?: return
    val f = formPageFile ?: return
    val kb = placeMaxKbText.toIntOrNull()
    if (kb != null && kb !in 10..50_000) {
      error = "Max KB: 10 to 50000 (or leave it empty for no limit)."
      return
    }
    val box = placeBox
    val asPdf = placeFormat == OutputFormat.PDF
    viewModelScope.launch {
      busy = "Placing the signature…"
      error = null
      try {
        placeResult = withContext(Dispatchers.Default) {
          val edits = formRecipe
          val page = DocTools.mutable(
            DocTools.scaleToMax(PageRenderer.render(DocTools.load(ctx, Uri.fromFile(f), if (edits.quad != null) 2600 else 2400), edits, 2400), 2400),
          )
          Canvas(page).drawBitmap(
            signature,
            null,
            RectF(box.left * page.width, box.top * page.height, box.right * page.width, box.bottom * page.height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
          )
          val maxBytes = kb?.let { it * 1024L }
          if (asPdf) {
            val kept = DocTools.keepPage(ctx, page)
            val name = "signed_page.pdf"
            val out = FileNames.outputFile(ctx, name)
            val previewImg = DocTools.thumbnail(kept, 600)?.asImageBitmap()
            val (size, _, fits) = try {
              DocTools.fitPdf(listOf(kept), maxBytes, out)
            } finally {
              kept.delete() // (in the PDF now)
            }
            FilesResult(
              listOf(ReadyFile(out, name, "application/pdf", kb)),
              previewImg,
              size, size, kb, fits, 1, OutputFormat.PDF,
            )
          } else {
            val fit = DocTools.fitPicture(page, maxBytes, false)
            val name = "signed_page.jpg"
            val out = FileNames.outputFile(ctx, name)
            out.writeBytes(fit.bytes)
            FilesResult(
              listOf(ReadyFile(out, name, "image/jpeg", kb)),
              DocTools.thumbnail(out, 600)?.asImageBitmap(),
              fit.bytes.size.toLong(), fit.bytes.size.toLong(), kb, fit.fits, 1, OutputFormat.JPG,
            )
          }
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The page could not be made.")
      } finally {
        busy = null
      }
    }
  }

  init {
    // a page chosen for this item in the page picker
    Handoff.take(DraftRepository.keyOf("signature", presetId, itemIndex, studentId))?.firstOrNull()?.let { setSourceFile(it.file) }
  }
}

@Composable
fun SignatureScreen(presetId: String?, itemIndex: Int, onBack: () -> Unit, onNewPreset: () -> Unit = {}, studentId: String? = null) {
  val app = LocalContext.current.applicationContext as Application
  val vm: SignatureViewModel = viewModel { SignatureViewModel(app, presetId, itemIndex, studentId) }
  // the shared page editor takes the whole screen while it is open
  vm.editor?.let {
    PageEditorScreen(it)
    return
  }
  val source = rememberImageSource { vm.setSource(it) }
  var saving by rememberSaveable { mutableStateOf(false) }
  var savingPage by rememberSaveable { mutableStateOf(false) }
  val context = LocalContext.current
  val openForm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openForm(uri) }
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
          if (studentId != null) {
            AddToStudentButton(studentId, vm.result?.ready?.takeIf { !vm.stale && vm.busy == null }?.let { listOf(it) }, vm.preset, Modifier.weight(1f), onBack)
          } else {
            Button(
              onClick = { saving = true },
              enabled = vm.result != null && !vm.stale && vm.busy == null,
              modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Save or share") }
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
        if (vm.preset == null) PresetItemShortcut(ItemKind.SIGNATURE, { _, item -> vm.applyItem(item) }, onNewPreset)
      } else {
        Text(
          "Drag the corners so the box fits closely around the signature.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(10)
        CropBox(picture, vm.box, { vm.box = it })
        VSpace(8)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { vm.openEditor() }, enabled = vm.busy == null) { Text("Edit photo") }
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

        if (vm.preset == null) PresetItemShortcut(ItemKind.SIGNATURE, { _, item -> vm.applyItem(item) }, onNewPreset)
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
          if (vm.stale && vm.busy == null) StaleNote("Something changed: tap “Make again” to update the signature.")
          if (!vm.stale) PlaceOnPage(vm, {
            try {
              openForm.launch(arrayOf("image/*", "application/pdf"))
            } catch (_: ActivityNotFoundException) {
              Toast.makeText(context, "No file picker found on this phone.", Toast.LENGTH_LONG).show()
            }
          }, { savingPage = true })
        }
      }
      if (vm.busy != null) BusyRow(vm.busy ?: "")
      ErrorText(vm.error)
    }
  }

  val ready = vm.result?.ready
  if (saving && ready != null) {
    SaveSheet(listOf(ready), defaultFolder = vm.preset?.name ?: "DocReady", onDismiss = { saving = false }, naming = presetNaming(vm.preset))
  }
  val page = vm.placeResult
  if (savingPage && page != null) {
    SaveSheet(page.files, defaultFolder = vm.preset?.name ?: "DocReady", onDismiss = { savingPage = false }, naming = presetNaming(vm.preset))
  }
}

/** Optional: the cleaned signature dragged and sized on a form's page, saved as JPG or PDF */
@Composable
private fun PlaceOnPage(vm: SignatureViewModel, choosePage: () -> Unit, savePage: () -> Unit) {
  val ink = vm.inkPreview ?: return
  SectionLabel("Place on a form page (optional)")
  val form = vm.formImage
  if (form == null) {
    Text(
      "Sign a form page without printing it: choose a photo or PDF of the page, then drag the signature into place.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    VSpace(8)
    OutlinedButton(onClick = choosePage, enabled = vm.busy == null) { Text("Choose the page (photo or PDF)") }
    return
  }
  if (vm.formCount > 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { vm.showFormPage(vm.formIndex - 1) }, enabled = vm.busy == null && vm.formIndex > 0) { Text("‹ Previous") }
      Text("Page ${vm.formIndex + 1} of ${vm.formCount}", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
      TextButton(onClick = { vm.showFormPage(vm.formIndex + 1) }, enabled = vm.busy == null && vm.formIndex < vm.formCount - 1) { Text("Next ›") }
    }
    VSpace(8)
  }
  Text(
    "Drag the signature into place; drag a corner to make it bigger or smaller.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  VSpace(6)
  CropBox(form, vm.placeBox, { vm.placeBox = it }, fixedRatio = vm.placeRatio(), overlay = ink, maxHeight = 460)
  VSpace(8)
  ChoiceChips(listOf(OutputFormat.PDF, OutputFormat.JPG), vm.placeFormat, { if (it == OutputFormat.PDF) "PDF" else "JPG" }, {
    vm.placeFormat = it
    vm.placeResult = null
  })
  VSpace(8)
  NumberField("Max KB (empty = no limit)", vm.placeMaxKbText, {
    vm.placeMaxKbText = it
    vm.placeResult = null
  }, Modifier.fillMaxWidth())
  VSpace(8)
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { vm.makePage() }, enabled = vm.busy == null) { Text("Make the page") }
    OutlinedButton(onClick = choosePage, enabled = vm.busy == null) { Text("Other page") }
    OutlinedButton(onClick = { vm.openFormEditor() }, enabled = vm.busy == null) { Text("Edit page") }
    TextButton(onClick = { vm.closeForm() }) { Text("Close") }
  }
  vm.placeResult?.let { r ->
    VSpace(10)
    FilesResultCard(r)
    VSpace(8)
    Button(onClick = savePage, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Save or share the page") }
  }
}
