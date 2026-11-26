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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docready.aamunim.data.Draft
import com.docready.aamunim.data.DraftHost
import com.docready.aamunim.data.DraftPage
import com.docready.aamunim.data.DraftRepository
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.PageLimits
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.data.model.pageRange
import com.docready.aamunim.data.model.summary
import com.docready.aamunim.data.model.title
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.PdfTools
import com.docready.aamunim.imaging.Scanner
import com.docready.aamunim.intake.Handoff
import com.docready.aamunim.intake.HandoffPage
import com.docready.aamunim.intake.IncomingFile
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.intake.IntakeTarget
import com.docready.aamunim.intake.PageKind
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.DraftOffer
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import com.docready.aamunim.ui.components.findActivity
import com.docready.aamunim.ui.theme.DocReadyColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/** A page that came in for a preset item: a PDF's page or a picture, and where it came from */
class UploadPage(val page: PdfPage, val kind: PageKind)

/**
 * "Add" on a preset item (from the preset, or a student's checklist): any files in (the
 * scanner, gallery pictures, PDFs, shared files), every PDF page and picture one page. When
 * more pages came in than the item takes, the user picks them (in order); never merged beyond
 * the item's limit. The chosen pages go to the item's own tool (Photo, Signature, or Scan for a
 * document), which makes the file by the item's rules. Sources are never changed.
 */
class ItemUploadViewModel(
  app: Application,
  private val presetId: String?,
  val itemIndex: Int,
  private val studentId: String?,
  resume: Boolean,
) : AndroidViewModel(app) {
  private val ctx: Application get() = getApplication()
  val preset = app.docReady.presets.get(presetId)
  val item = preset?.items?.getOrNull(itemIndex)
  val range: IntRange = item?.pageRange ?: 1..1

  /** The tool that makes this item's file */
  val tool: String = when (item?.kind) {
    ItemKind.PHOTO -> "photo"
    ItemKind.SIGNATURE -> "signature"
    else -> "scan"
  }

  val pages = mutableStateListOf<UploadPage>()

  /** The chosen pages, in the order they were chosen (the file's order) */
  val selected = mutableStateListOf<UploadPage>()

  /** Pages that look like this item (OCR / keywords, faces, ink): a highlight only */
  val looksLikeIt = mutableStateMapOf<UploadPage, Boolean>()

  var busy by mutableStateOf<String?>(null)
  var error by mutableStateOf<String?>(null)

  /** More pages than the item takes: the picker is shown */
  var picking by mutableStateOf(false)
    private set

  /** The pages were handed to this tool: the screen goes there */
  var handedTo by mutableStateOf<String?>(null)
    private set

  private val looker = PageLooker(app)
  private var suggestJob: Job? = null

  fun addScanned(uris: List<Uri>) = add(uris.map { u -> Source({ FileNames.copyIn(ctx, u, "jpg") }, "image/jpeg", "scan") }, PageKind.SCANNED)

  /** Pictures and PDFs picked in the gallery or the file picker */
  fun addUris(uris: List<Uri>) = add(
    uris.map { u ->
      val type = ctx.contentResolver.getType(u) ?: ""
      val name = FileNames.displayName(ctx, u) ?: "file"
      val pdf = type == "application/pdf" || name.lowercase().endsWith(".pdf")
      Source({ FileNames.copyIn(ctx, u, if (pdf) "pdf" else "img") }, if (pdf) "application/pdf" else "image/*", name)
    },
    PageKind.PHOTO,
  )

  fun addIncoming(files: List<IncomingFile>) = add(files.filter { !it.isPreset }.map { f -> Source({ f.file }, f.mime, f.name) }, PageKind.PHOTO)

  private class Source(val fileOf: () -> File, val mime: String, val name: String)

  private fun add(sources: List<Source>, imageKind: PageKind) {
    if (sources.isEmpty()) return
    viewModelScope.launch {
      busy = "Opening the files…"
      error = null
      try {
        // everything copied in and counted first: more than the app takes at once is refused whole
        val opened = withContext(Dispatchers.IO) {
          sources.map { s ->
            val f = s.fileOf()
            val pdf = s.mime == "application/pdf"
            Triple(s, f, if (pdf) PdfTools.pageCount(f) else 1)
          }
        }
        val total = pages.size + opened.sumOf { it.third }
        if (total > PdfTools.PAGE_LIMIT) {
          error = "That is $total pages: DocReady takes up to ${PdfTools.PAGE_LIMIT} at once. Choose fewer files or pages."
          return@launch
        }
        for ((s, f, count) in opened) {
          if (s.mime == "application/pdf") {
            withContext(Dispatchers.Default) {
              PdfTools.renderThumbs(f, PdfTools.PAGE_LIMIT, 240) { n, all, thumb ->
                val shown = thumb.asImageBitmap()
                withContext(Dispatchers.Main) {
                  pages.add(UploadPage(PdfPage(shown, s.name, f.length() / maxOf(1, all), f, n), PageKind.PDF))
                  busy = "Reading page ${n + 1} of $all…"
                }
              }
            }
            if (count == 0) error = "${s.name} has no pages."
          } else {
            val thumb = withContext(Dispatchers.Default) { smallThumb(DocTools.load(ctx, Uri.fromFile(f), 300)) }
            pages.add(UploadPage(PdfPage(thumb, s.name, f.length(), f, -1), imageKind))
          }
        }
      } catch (e: Throwable) {
        error = messageOf(e, "The files could not be opened.")
      } finally {
        busy = null
      }
      afterAdding()
    }
  }

  /** Within the item's limit: straight on (no picking); else the picker, with suggestions */
  private fun afterAdding() {
    if (pages.isEmpty()) return
    if (!PageLimits.needsPicking(pages.size, range)) {
      selected.clear()
      selected.addAll(pages)
      if (PageLimits.selectionOk(selected.size, range)) {
        useSelected()
        return
      }
    }
    picking = true
    suggest()
  }

  /** A tap on a page: chosen (next in order) or not; never more than the item takes */
  fun toggle(p: UploadPage): String? {
    if (selected.remove(p)) return null
    if (PageLimits.canSelectMore(selected.size, range)) {
      selected.add(p)
      return null
    }
    // (one page: the new one replaces the old)
    if (range.last == 1) {
      selected.clear()
      selected.add(p)
      return null
    }
    return "This item takes at most ${range.last} pages: unselect one first."
  }

  /** "Looks like <item>?": each page looked at in turn (shown as it comes; never chosen by itself) */
  private fun suggest() {
    val p = preset ?: return
    suggestJob?.cancel()
    suggestJob = viewModelScope.launch {
      for (page in pages.toList()) {
        if (page in looksLikeIt) continue
        val look = looker.look(page.page, if (page.kind == PageKind.PDF) PageRecipe.Plain else PageRecipe.Document) ?: continue
        looksLikeIt[page] = looker.choose(look, p) == itemIndex
      }
    }
  }

  val textNotReady: Boolean get() = looker.textNotReady

  /**
   * The chosen pages made into picture files (a PDF's page at full detail; a picture as it is)
   * and handed to the item's tool
   */
  fun useSelected() {
    val chosen = selected.toList()
    if (!PageLimits.selectionOk(chosen.size, range)) return
    suggestJob?.cancel()
    viewModelScope.launch {
      busy = "Preparing the pages…"
      error = null
      try {
        val handed = withContext(Dispatchers.Default) {
          chosen.map { u ->
            val src = u.page.source ?: throw IllegalStateException("A page is no longer on the phone: add it again.")
            if (u.page.pdfPage >= 0) {
              val b = PdfTools.atBestSize { side -> PdfTools.renderPage(src, u.page.pdfPage, side) }
              HandoffPage(DocTools.keepPage(ctx, b), PageKind.PDF)
            } else {
              HandoffPage(src, u.kind)
            }
          }
        }
        Handoff.put(DraftRepository.keyOf(tool, presetId, itemIndex, studentId), handed)
        draft.done()
        handedTo = tool
      } catch (e: Throwable) {
        error = messageOf(e, "The pages could not be prepared.")
      } finally {
        busy = null
      }
    }
  }

  fun startAgain() {
    suggestJob?.cancel()
    pages.clear()
    selected.clear()
    looksLikeIt.clear()
    picking = false
    error = null
  }

  /** A large view of a page (for choosing) */
  suspend fun large(p: UploadPage): ImageBitmap? = withContext(Dispatchers.Default) {
    try {
      p.page.loadSource(ctx, 1400).asImageBitmap()
    } catch (_: Throwable) {
      null
    }
  }

  private fun smallThumb(b: Bitmap): ImageBitmap {
    val s = DocTools.scaleToMax(b, 240)
    val lean = s.copy(Bitmap.Config.RGB_565, false)
    s.recycle()
    return lean.asImageBitmap()
  }

  // ---- draft: the pages and the choice survive the app being closed
  private val draftTitle = listOfNotNull(
    "Add to ${item?.title ?: "item"}",
    app.docReady.students.get(studentId)?.name?.let { "for $it" },
  ).joinToString(" · ")

  val draft = DraftHost(viewModelScope, app.docReady.drafts, DraftRepository.keyOf("pick", presetId, itemIndex, studentId), { snapshot() }) { d -> restore(d) }

  private fun snapshot(): Draft? {
    val list = pages.filter { it.page.source != null }
    if (list.isEmpty() || handedTo != null) return null
    return Draft(
      draft.key, "pick", draftTitle, presetId, itemIndex, studentId,
      list.map { u -> DraftPage(u.page.source!!.path, pdfPage = u.page.pdfPage, from = u.page.from, sourceBytes = u.page.sourceBytes, item = u.kind.ordinal) },
      mapOf("selected" to selected.map { list.indexOf(it) }.filter { it >= 0 }.joinToString(",")),
    )
  }

  private suspend fun restore(d: Draft): Boolean {
    busy = "Opening your pages…"
    try {
      val back = withContext(Dispatchers.Default) {
        d.pages.map { dp ->
          val f = File(dp.file)
          val thumb = if (dp.pdfPage >= 0) smallThumb(PdfTools.renderPage(f, dp.pdfPage, 300)) else smallThumb(DocTools.load(ctx, Uri.fromFile(f), 300))
          UploadPage(PdfPage(thumb, dp.from, dp.sourceBytes, f, dp.pdfPage), PageKind.entries.getOrElse(dp.item ?: 1) { PageKind.PHOTO })
        }
      }
      pages.clear()
      pages.addAll(back)
      selected.clear()
      d.values["selected"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.forEach { i -> back.getOrNull(i)?.let { if (it !in selected) selected.add(it) } }
      picking = PageLimits.needsPicking(pages.size, range)
      if (picking) suggest()
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemUploadScreen(
  presetId: String?,
  itemIndex: Int,
  studentId: String?,
  onBack: () -> Unit,
  onHandOff: (tool: String) -> Unit,
  resume: Boolean = false,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val vm: ItemUploadViewModel = viewModel { ItemUploadViewModel(app, presetId, itemIndex, studentId, resume) }
  val item = vm.item
  LaunchedEffect(vm.handedTo) { vm.handedTo?.let(onHandOff) }
  val intake by Intake.request.collectAsState()
  LaunchedEffect(intake) {
    if (intake?.target == IntakeTarget.ITEM) vm.addIncoming(Intake.take(IntakeTarget.ITEM))
  }
  val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
    if (res.resultCode == Activity.RESULT_OK) {
      vm.addScanned(
        try {
          Scanner.pageUris(res.data)
        } catch (_: Exception) {
          emptyList()
        },
      )
    }
  }
  val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> vm.addUris(uris) }
  val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addUris(uris) }
  fun launch(block: () -> Unit) {
    try {
      block()
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, "No app for this on the phone.", Toast.LENGTH_LONG).show()
    }
  }
  val startScan = {
    val activity = context.findActivity()
    if (activity != null) {
      Scanner.startIntent(activity, minOf(30, maxOf(1, vm.range.last)))
        .addOnSuccessListener { sender -> scan.launch(IntentSenderRequest.Builder(sender).build()) }
        .addOnFailureListener { e ->
          Toast.makeText(context, e.message ?: "The scanner is not available on this phone (Google Play services).", Toast.LENGTH_LONG).show()
        }
    }
  }
  var zoom by remember { mutableStateOf<UploadPage?>(null) }

  DraftOffer(vm.draft)
  Scaffold(
    topBar = {
      DocTopBar(
        "Add: ${item?.title ?: "item"}",
        onBack,
        subtitle = listOfNotNull(vm.preset?.name, context.docReady.students.get(studentId)?.name).joinToString(" · ").ifEmpty { null },
      )
    },
    bottomBar = {
      if (vm.picking) {
        BottomActions {
          OutlinedButton(onClick = { vm.startAgain() }, enabled = vm.busy == null, modifier = Modifier.height(52.dp)) { Text("Start again") }
          Button(
            onClick = { vm.useSelected() },
            enabled = vm.busy == null && PageLimits.selectionOk(vm.selected.size, vm.range),
            modifier = Modifier.weight(1f).height(52.dp),
          ) { Text("Use selected (${vm.selected.size})") }
        }
      }
    },
  ) { padding ->
    if (item == null) {
      Column(Modifier.padding(padding).padding(16.dp)) { Text("This item is no longer in its preset.") }
      return@Scaffold
    }
    if (!vm.picking) {
      Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(item.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          "Takes ${PageLimits.describe(vm.range)}. Add pages from anywhere: if there are more, you choose which.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
        SectionLabel("Add from")
        Button(onClick = startScan, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
          Icon(AppIcons.Scan, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(8.dp))
          Text("Scan")
        }
        VSpace(8)
        OutlinedButton(onClick = { launch { gallery.launch("image/*") } }, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
          Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(8.dp))
          Text("Gallery (photos)")
        }
        VSpace(8)
        OutlinedButton(onClick = { launch { files.launch(arrayOf("application/pdf", "image/*")) } }, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
          Icon(AppIcons.Pages, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(8.dp))
          Text("Files (PDF or pictures)")
        }
        vm.busy?.let { BusyRow(it) }
        ErrorText(vm.error)
      }
      return@Scaffold
    }
    Column(Modifier.padding(padding).fillMaxSize()) {
      Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(PageLimits.chooseTitle(vm.range), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          "${vm.pages.size} pages came in; ${item.title} takes ${PageLimits.describe(vm.range)}. Tap pages in the order you want them; hold one to see it large.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val short = vm.range.first - vm.pages.size
        if (short > 0) {
          Text(
            "${item.title} takes at least ${vm.range.first} pages: add $short more.",
            style = MaterialTheme.typography.bodySmall,
            color = DocReadyColors.extra.onWarningContainer,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Add more:", style = MaterialTheme.typography.bodySmall)
          TextButton(onClick = startScan, enabled = vm.busy == null) { Text("Scan") }
          TextButton(onClick = { launch { gallery.launch("image/*") } }, enabled = vm.busy == null) { Text("Gallery") }
          TextButton(onClick = { launch { files.launch(arrayOf("application/pdf", "image/*")) } }, enabled = vm.busy == null) { Text("Files") }
        }
        if (vm.textNotReady) {
          Text(com.docready.aamunim.imaging.Ocr.NOT_READY, style = MaterialTheme.typography.bodySmall, color = DocReadyColors.extra.onWarningContainer)
        }
        vm.busy?.let { BusyRow(it) }
        ErrorText(vm.error)
      }
      LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
      ) {
        itemsIndexed(vm.pages, key = { i, _ -> i }) { i, p ->
          val order = vm.selected.indexOf(p)
          PickTile(
            p, i, order, vm.looksLikeIt[p] == true, item.title,
            onTap = { vm.toggle(p)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
            onZoom = { zoom = p },
          )
        }
      }
    }
  }
  zoom?.let { p -> PageZoom(p, vm, onClose = { zoom = null }) }
}

/** One page in the picker: its picture, its place in the choice (a number), a suggestion */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickTile(p: UploadPage, index: Int, order: Int, suggested: Boolean, itemTitle: String, onTap: () -> Unit, onZoom: () -> Unit) {
  val chosen = order >= 0
  val extra = DocReadyColors.extra
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier
        .fillMaxWidth()
        .aspectRatio(0.72f)
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
        .combinedClickable(onClick = onTap, onLongClick = onZoom),
    ) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
          if (chosen) 3.dp else if (suggested) 2.dp else 1.dp,
          if (chosen) MaterialTheme.colorScheme.primary else if (suggested) extra.warningBorder else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxSize(),
      ) {
        p.page.thumb?.let { Image(it, contentDescription = "Page ${index + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp)) }
      }
      if (chosen) {
        Box(
          Modifier.padding(4.dp).size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
          contentAlignment = Alignment.Center,
        ) { Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
      }
      IconButton(onClick = onZoom, modifier = Modifier.align(Alignment.TopEnd).size(34.dp)) {
        Icon(AppIcons.Search, contentDescription = "See page ${index + 1} large", modifier = Modifier.size(18.dp))
      }
    }
    Text("${index + 1} · ${p.page.from}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (suggested) {
      Surface(shape = RoundedCornerShape(6.dp), color = extra.warningContainer) {
        Text(
          "Looks like $itemTitle?",
          color = extra.onWarningContainer,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }
    }
  }
}

/** A page large (made in the background), to choose by */
@Composable
private fun PageZoom(p: UploadPage, vm: ItemUploadViewModel, onClose: () -> Unit) {
  val image by produceState<ImageBitmap?>(p.page.thumb, p) { value = vm.large(p) ?: p.page.thumb }
  Dialog(onDismissRequest = onClose) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
      Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        image?.let { Image(it, contentDescription = "Page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
          val order = vm.selected.indexOf(p)
          TextButton(onClick = { vm.toggle(p) }) { Text(if (order >= 0) "Unselect (now ${order + 1})" else "Select") }
          TextButton(onClick = onClose) { Text("Close") }
        }
      }
    }
  }
}

/** "Use for a preset item…" (a share): the preset and its item, then the item's Add screen */
@Composable
fun ItemChooserScreen(onBack: () -> Unit, onChosen: (presetId: String, index: Int) -> Unit) {
  val presets by LocalContext.current.docReady.presets.presets.collectAsState()
  Scaffold(topBar = { DocTopBar("Use for a preset item", onBack, subtitle = "Choose the form's preset and the paper") }) { padding ->
    if (presets.isEmpty()) {
      Column(Modifier.padding(padding).padding(16.dp)) { Text("No presets yet: make one first (Home → Presets).") }
      return@Scaffold
    }
    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (p in presets) {
        item(key = "p-" + p.id) { Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp)) }
        items(p.items.indices.toList(), key = { i -> p.id + "-" + i }) { i ->
          val it = p.items[i]
          Surface(
            onClick = { onChosen(p.id, i) },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(Modifier.padding(12.dp)) {
              Text(it.title, fontWeight = FontWeight.SemiBold)
              Text(it.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }
}
