package com.docready.aamunim.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.model.CropRect
import com.docready.aamunim.data.model.EraseStroke
import com.docready.aamunim.data.model.Look
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.imaging.DocTools
import com.docready.aamunim.imaging.PageRenderer
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.CropBox
import com.docready.aamunim.ui.components.NormBox
import com.docready.aamunim.ui.components.QuadBox
import com.docready.aamunim.ui.components.fullQuad
import com.docready.aamunim.ui.components.isFullQuad
import com.docready.aamunim.ui.theme.EditorColors
import com.docready.aamunim.ui.theme.EditorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the editor offers: a document page (all tools), a photo, or a signature */
enum class EditorMode { DOCUMENT, PHOTO, SIGNATURE }

enum class EditorTab { CROP, CLEAN, ERASE }

/**
 * One page for the editor: its name, its recipe, and how to read its source at a given size
 * (blocking; called off the main thread). The source itself is never changed.
 */
class EditorPage(val label: String, val recipe: PageRecipe, val load: (maxSide: Int) -> Bitmap)

/**
 * The shared page editor's state: a recipe per page (undo / redo per page), and the preview
 * made from a screen-sized copy of the source in the background. [onDone] gets every page's
 * recipe; nothing is made at full quality here.
 */
class PageEditorState(
  private val scope: CoroutineScope,
  val pages: List<EditorPage>,
  start: Int,
  val mode: EditorMode,
  private val onDone: (List<PageRecipe>) -> Unit,
  private val onClose: () -> Unit,
) {
  val recipes = mutableStateListOf<PageRecipe>().apply { addAll(pages.map { it.recipe }) }
  var index by mutableIntStateOf(start.coerceIn(0, max(0, pages.size - 1)))
    private set
  var tab by mutableStateOf(EditorTab.CROP)

  /** Crop tab: the free crop box (else the four corners, for documents) */
  var boxMode by mutableStateOf(mode != EditorMode.DOCUMENT)
  var brushWhite by mutableStateOf(false)

  /** Brush radius as a part of the shown page's width */
  var brushSize by mutableFloatStateOf(0.02f)

  var source by mutableStateOf<ImageBitmap?>(null)
    private set
  var geometryImage by mutableStateOf<ImageBitmap?>(null)
    private set
  var preview by mutableStateOf<ImageBitmap?>(null)
    private set
  var working by mutableStateOf(false)
    private set
  var error by mutableStateOf<String?>(null)

  /** A quiet note (e.g. erasing removed because the corners changed) */
  var notice by mutableStateOf<String?>(null)
  var canUndo by mutableStateOf(false)
    private set
  var canRedo by mutableStateOf(false)
    private set

  private var sourceBmp: Bitmap? = null
  private var loadVersion = 0

  /** Read on the background thread too: a newer request makes an older render stop early */
  @Volatile
  private var renderVersion = 0
  private var renderJob: Job? = null

  /** Corners / crop box being dragged: the preview is made again when the finger lifts */
  private var dragDirty = false
  private val undo = HashMap<Int, ArrayList<PageRecipe>>()
  private val redo = HashMap<Int, ArrayList<PageRecipe>>()

  /** Drags of one kind (corners, crop box) count as one step for undo */
  private var lastKey: String? = null

  val recipe: PageRecipe get() = recipes[index]

  init {
    open(index)
  }

  fun open(i: Int) {
    if (i !in pages.indices) return
    index = i
    lastKey = null
    notice = null
    flags()
    // a render for the page before must never land on this one
    renderVersion++
    renderJob?.cancel()
    val my = ++loadVersion
    source = null
    geometryImage = null
    preview = null
    scope.launch {
      working = true
      try {
        val b = withContext(Dispatchers.Default) { DocTools.mutable(pages[i].load(PREVIEW_SIDE)) }
        if (my != loadVersion) return@launch
        sourceBmp = b // (the old one may still be drawn: Android frees it)
        source = b.asImageBitmap()
        error = null
        refresh()
      } catch (e: Throwable) {
        if (my == loadVersion) error = if (e is OutOfMemoryError) "Not enough memory to show this page." else e.message ?: "The page could not be opened."
      } finally {
        if (my == loadVersion) working = false
      }
    }
  }

  /**
   * The preview made again from the source and the current recipe. Only the newest request
   * counts: an older one is cancelled, and stops between steps if it is already running.
   */
  private fun refresh() {
    val src = sourceBmp ?: return
    val r = recipe
    val my = ++renderVersion
    renderJob?.cancel()
    renderJob = scope.launch {
      working = true
      try {
        val made = withContext(Dispatchers.Default) {
          if (my != renderVersion) return@withContext null
          val g = PageRenderer.geometry(src.copy(Bitmap.Config.ARGB_8888, true), r)
          if (my != renderVersion) {
            g.recycle()
            return@withContext null
          }
          val shownGeo = g.asImageBitmap()
          val finished = PageRenderer.finish(g.copy(Bitmap.Config.ARGB_8888, true), r)
          shownGeo to finished.asImageBitmap()
        }
        if (made != null && my == renderVersion) {
          geometryImage = made.first
          preview = made.second
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        if (my == renderVersion) error = if (e is OutOfMemoryError) "Not enough memory for this preview." else e.message ?: "The preview could not be made."
      } finally {
        if (my == renderVersion) working = false
      }
    }
  }

  /** A change to this page (pushed on its undo list; drags with the same [key] are one step) */
  fun update(new: PageRecipe, key: String? = null) {
    val old = recipe
    var r = new
    // erasing is drawn on the straightened page: new corners or a new angle move everything under it
    if (old.erase.isNotEmpty() && (r.quad != old.quad || r.rotate != old.rotate)) {
      r = r.copy(erase = emptyList())
      notice = "Erasing was removed because the corners or the angle changed (Undo brings it back)."
    }
    if (r == old) return
    if (key == null || key != lastKey) {
      undo.getOrPut(index) { ArrayList() }.add(old)
      redo[index]?.clear()
    }
    lastKey = key
    recipes[index] = r
    flags()
    if (key != null) {
      // a drag: the box / corners follow the finger; the preview is made when it lifts
      dragDirty = true
    } else {
      refresh()
    }
  }

  /** The finger lifted: the next drag is a new undo step, and the preview catches up */
  fun endDrag() {
    lastKey = null
    if (dragDirty) {
      dragDirty = false
      refresh()
    }
  }

  fun undo() {
    val list = undo[index] ?: return
    if (list.isEmpty()) return
    redo.getOrPut(index) { ArrayList() }.add(recipe)
    recipes[index] = list.removeAt(list.size - 1)
    lastKey = null
    flags()
    refresh()
  }

  fun redo() {
    val list = redo[index] ?: return
    if (list.isEmpty()) return
    undo.getOrPut(index) { ArrayList() }.add(recipe)
    recipes[index] = list.removeAt(list.size - 1)
    lastKey = null
    flags()
    refresh()
  }

  private fun flags() {
    canUndo = undo[index]?.isNotEmpty() == true
    canRedo = redo[index]?.isNotEmpty() == true
  }

  /** The page as it came (every change undone in one step) */
  fun resetPage() = update(PageRecipe.Plain)

  /** This page's look (shadows, look, light) on every page; their crops and erasing kept */
  fun applyLookToAll() {
    val r = recipe
    for (j in recipes.indices) {
      if (j == index) continue
      val next = recipes[j].withLookOf(r)
      if (next == recipes[j]) continue
      // undoable on each page (open it and Undo)
      undo.getOrPut(j) { ArrayList() }.add(recipes[j])
      redo[j]?.clear()
      recipes[j] = next
    }
    notice = "This look is now on every page (Undo on a page takes it back there)."
  }

  fun findEdges() {
    val b = sourceBmp ?: return
    scope.launch {
      try {
        val q = withContext(Dispatchers.Default) { DocTools.findPaper(b) }
        if (q != null) update(recipe.copy(quad = q.toList())) else error = "No clear paper edges found: drag the corners by hand."
      } catch (e: Throwable) {
        error = e.message ?: "The paper's edges could not be found."
      }
    }
  }

  fun wholePage() = update(recipe.copy(quad = null, crop = null))

  /**
   * A quarter turn clockwise: the crop box and the erasing turn with the page (a point x, y
   * becomes 1 - y, x; a stroke's width follows the page's new width).
   */
  fun turn() {
    val r = recipe
    val geo = geometryImage
    val aspect = geo?.let { it.width.toFloat() / max(1, it.height) }
    val erase = when {
      r.erase.isEmpty() -> r.erase
      aspect == null -> emptyList()
      else -> r.erase.map { st ->
        val pts = ArrayList<Float>(st.points.size)
        for (k in 0 until st.points.size / 2) {
          pts.add(1f - st.points[k * 2 + 1])
          pts.add(st.points[k * 2])
        }
        EraseStroke(pts, st.radius * aspect, st.white)
      }
    }
    val crop = r.crop?.let { CropRect(1f - it.bottom, it.left, 1f - it.top, it.right) }
    update(r.copy(turn = (r.turn + 90) % 360, crop = crop, erase = erase))
  }

  /** A brush stroke drawn on the shown page (points as parts of it) */
  fun addStroke(points: List<Float>) {
    if (points.size < 2) return
    val crop = recipe.crop ?: CropRect(0f, 0f, 1f, 1f)
    val pts = points.mapIndexed { i, v -> if (i % 2 == 0) crop.left + v * crop.width else crop.top + v * crop.height }
    update(recipe.copy(erase = recipe.erase + EraseStroke(pts, brushSize * crop.width, brushWhite)))
  }

  fun done() = onDone(recipes.toList())

  fun close() = onClose()

  companion object {
    /** The preview's longer side: about a phone screen */
    const val PREVIEW_SIDE = 1400
  }
}

/** The editor, full screen (dark neutral chrome, green accents) */
@Composable
fun PageEditorScreen(state: PageEditorState) {
  BackHandler { state.close() }
  EditorTheme {
    Box(Modifier.fillMaxSize().background(EditorColors.Background).systemBarsPadding()) {
      Column(Modifier.fillMaxSize()) {
        EditorTopBar(state)
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
          EditorCanvas(state)
          if (state.working) {
            CircularProgressIndicator(
              modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp),
              strokeWidth = 2.5.dp,
            )
          }
        }
        state.error?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        state.notice?.let {
          Text(it, color = EditorColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Surface(color = EditorColors.Panel) {
          Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (state.tab) {
              EditorTab.CROP -> CropControls(state)
              EditorTab.CLEAN -> CleanControls(state)
              EditorTab.ERASE -> EraseControls(state)
            }
          }
        }
        EditorTabs(state)
      }
    }
  }
}

@Composable
private fun EditorTopBar(state: PageEditorState) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = { state.close() }) { Icon(AppIcons.Close, contentDescription = "Close without changes", tint = EditorColors.Text) }
    Column(Modifier.weight(1f)) {
      Text(
        if (state.pages.size > 1) "Page ${state.index + 1} of ${state.pages.size}" else state.pages.getOrNull(0)?.label ?: "Edit",
        color = EditorColors.Text,
        fontWeight = FontWeight.SemiBold,
      )
      if (state.pages.size > 1) {
        Text(state.pages[state.index].label, color = EditorColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
      }
    }
    if (state.pages.size > 1) {
      IconButton(onClick = { state.open(state.index - 1) }, enabled = state.index > 0) { Icon(AppIcons.Back, contentDescription = "Previous page") }
      IconButton(onClick = { state.open(state.index + 1) }, enabled = state.index < state.pages.size - 1) {
        Icon(AppIcons.Forward, contentDescription = "Next page")
      }
    }
    IconButton(onClick = { state.undo() }, enabled = state.canUndo) {
      Icon(AppIcons.Undo, contentDescription = "Undo")
    }
    IconButton(onClick = { state.redo() }, enabled = state.canRedo) { Icon(AppIcons.Redo, contentDescription = "Redo") }
    Button(onClick = { state.done() }, modifier = Modifier.padding(end = 4.dp)) { Text("Done") }
  }
}

@Composable
private fun EditorCanvas(state: PageEditorState) {
  val r = state.recipe
  when (state.tab) {
    EditorTab.CROP -> {
      if (!state.boxMode && state.mode == EditorMode.DOCUMENT) {
        val src = state.source ?: return
        QuadBox(
          src,
          r.quad?.toFloatArray() ?: fullQuad(),
          { q -> state.update(state.recipe.copy(quad = q.toList().takeIf { !isFullQuad(q) }), "quad") },
          onDragEnd = { state.endDrag() },
        )
      } else {
        val geo = state.geometryImage ?: return
        val c = r.crop
        CropBox(
          geo,
          if (c == null) NormBox(0f, 0f, 1f, 1f) else NormBox(c.left, c.top, c.right, c.bottom),
          { b ->
            val full = b.left <= 0.001f && b.top <= 0.001f && b.right >= 0.999f && b.bottom >= 0.999f
            state.update(state.recipe.copy(crop = if (full) null else CropRect(b.left, b.top, b.right, b.bottom)), "crop")
          },
          maxHeight = 520,
          onDragEnd = { state.endDrag() },
        )
      }
    }
    EditorTab.CLEAN, EditorTab.ERASE -> {
      val shown = state.preview ?: return
      ZoomableImage(
        image = shown,
        before = state.source,
        erase = state.tab == EditorTab.ERASE,
        brushSize = state.brushSize,
        brushWhite = state.brushWhite,
        onStroke = { state.addStroke(it) },
      )
    }
  }
}

/** The page with pinch-zoom and pan (double tap: fit); in erase mode one finger paints */
@Composable
private fun ZoomableImage(
  image: ImageBitmap,
  before: ImageBitmap?,
  erase: Boolean,
  brushSize: Float,
  brushWhite: Boolean,
  onStroke: (List<Float>) -> Unit,
) {
  var zoom by remember { mutableFloatStateOf(1f) }
  var pan by remember { mutableStateOf(Offset.Zero) }
  var box by remember { mutableStateOf(IntSize.Zero) }
  var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
  var showBefore by remember { mutableStateOf(false) }
  val img by rememberUpdatedState(image)
  val painting by rememberUpdatedState(erase)
  val stroke by rememberUpdatedState(onStroke)

  fun fitted(): Rect {
    val w = box.width.toFloat()
    val h = box.height.toFloat()
    if (w <= 0f || h <= 0f) return Rect(0f, 0f, 1f, 1f)
    val s = min(w / img.width, h / img.height)
    val fw = img.width * s
    val fh = img.height * s
    return Rect((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
  }

  /** A point on screen as parts of the page (zoom and pan undone) */
  fun toPage(p: Offset): Offset {
    val c = Offset(box.width / 2f, box.height / 2f)
    val q = c + (p - pan - c) / zoom
    val f = fitted()
    return Offset(((q.x - f.left) / f.width).coerceIn(0f, 1f), ((q.y - f.top) / f.height).coerceIn(0f, 1f))
  }

  fun clampPan(p: Offset): Offset {
    val mx = box.width * (zoom - 1f) / 2f
    val my = box.height * (zoom - 1f) / 2f
    return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
  }

  Box(Modifier.fillMaxSize()) {
    Box(
      Modifier
        .fillMaxSize()
        .clipToBounds()
        .onSizeChanged { box = it }
        .pointerInput(Unit) {
          detectTapGestures(onDoubleTap = {
            zoom = 1f
            pan = Offset.Zero
          })
        }
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val points = ArrayList<Offset>()
            var multi = false
            if (painting) points.add(down.position)
            do {
              val event = awaitPointerEvent()
              val pressed = event.changes.count { it.pressed }
              if (pressed >= 2) {
                multi = true
                points.clear()
                live = emptyList()
                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                pan = clampPan(pan + event.calculatePan())
              } else if (!multi) {
                val c = event.changes.firstOrNull { it.pressed }
                if (c != null) {
                  if (painting) {
                    points.add(c.position)
                    live = points.toList()
                  } else if (zoom > 1f) {
                    pan = clampPan(pan + c.positionChange())
                  }
                }
              }
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            } while (event.changes.any { it.pressed })
            if (painting && !multi && points.isNotEmpty()) {
              stroke(points.flatMap { p -> toPage(p).let { listOf(it.x, it.y) } })
            }
            live = emptyList()
          }
        },
    ) {
      Image(
        if (showBefore && before != null) before else image,
        contentDescription = "Page preview",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer {
          scaleX = zoom
          scaleY = zoom
          translationX = pan.x
          translationY = pan.y
        },
      )
      if (live.isNotEmpty()) {
        Canvas(Modifier.fillMaxSize()) {
          val width = brushSize * fitted().width * zoom * 2f
          val path = Path().apply {
            moveTo(live[0].x, live[0].y)
            for (p in live.drop(1)) lineTo(p.x, p.y)
          }
          drawPath(
            path,
            if (brushWhite) Color.White.copy(alpha = 0.8f) else EditorColors.Accent.copy(alpha = 0.6f),
            style = Stroke(width = max(2f, width), cap = StrokeCap.Round, join = StrokeJoin.Round),
          )
        }
      }
    }
    // press and hold: the page before any change
    if (before != null) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = EditorColors.PanelHigh.copy(alpha = 0.9f),
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(8.dp)
          .pointerInput(Unit) {
            detectTapGestures(onPress = {
              showBefore = true
              tryAwaitRelease()
              showBefore = false
            })
          },
      ) {
        Text(
          if (showBefore) "Before" else "Hold to compare",
          color = EditorColors.Text,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }
    }
    if (zoom > 1.01f) {
      Text(
        "${(zoom * 100).roundToInt()}% · double tap to fit",
        color = EditorColors.TextDim,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
      )
    }
  }
}

@Composable
private fun CropControls(state: PageEditorState) {
  val r = state.recipe
  if (state.mode == EditorMode.DOCUMENT) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(selected = !state.boxMode, onClick = { state.boxMode = false }, label = { Text("Corners") })
      FilterChip(selected = state.boxMode, onClick = { state.boxMode = true }, label = { Text("Free crop") })
    }
  }
  Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (state.mode == EditorMode.DOCUMENT) OutlinedButton(onClick = { state.findEdges() }) { Text("Find edges") }
    OutlinedButton(onClick = { state.wholePage() }) { Text("Whole page") }
    OutlinedButton(onClick = { state.turn() }) { Text("Turn 90°") }
  }
  var angle by remember(state.index, r.rotate) { mutableFloatStateOf(r.rotate) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Straighten", color = EditorColors.Text, modifier = Modifier.width(96.dp))
    Slider(
      value = angle,
      onValueChange = { angle = it },
      onValueChangeFinished = { state.update(state.recipe.copy(rotate = (angle * 2).roundToInt() / 2f)) },
      valueRange = -15f..15f,
      modifier = Modifier.weight(1f),
    )
    Text("${(angle * 2).roundToInt() / 2f}°", color = EditorColors.TextDim, modifier = Modifier.width(48.dp))
  }
}

@Composable
private fun CleanControls(state: PageEditorState) {
  val r = state.recipe
  if (state.mode == EditorMode.DOCUMENT) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Remove shadows", color = EditorColors.Text, fontWeight = FontWeight.SemiBold)
        Text("Evens out uneven light and phone shadows", color = EditorColors.TextDim, style = MaterialTheme.typography.bodySmall)
      }
      Switch(checked = r.removeShadows, onCheckedChange = { state.update(r.copy(removeShadows = it)) })
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (l in Look.entries) FilterChip(selected = r.look == l, onClick = { state.update(r.copy(look = l)) }, label = { Text(l.title) })
    }
  }
  if (state.mode != EditorMode.SIGNATURE) {
    LightSlider("Brightness", r.brightness, -1f..1f, state.index) { state.update(state.recipe.copy(brightness = it)) }
  }
  LightSlider("Contrast", r.contrast, -0.5f..1f, state.index) { state.update(state.recipe.copy(contrast = it)) }
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (state.mode == EditorMode.DOCUMENT && state.pages.size > 1) {
      OutlinedButton(onClick = { state.applyLookToAll() }) { Text("Apply look to all pages") }
    }
    TextButton(onClick = { state.resetPage() }) { Text("Reset page") }
  }
  if (state.mode == EditorMode.DOCUMENT && state.pages.size > 1) {
    Text(
      "“Apply to all” copies shadows, look and light, not crops or erasing. The original page is never changed.",
      color = EditorColors.TextDim,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun LightSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, page: Int, onDone: (Float) -> Unit) {
  var v by remember(page, value) { mutableFloatStateOf(value) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(title, color = EditorColors.Text, modifier = Modifier.width(96.dp))
    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone((v * 100).roundToInt() / 100f) }, valueRange = range, modifier = Modifier.weight(1f))
    Text("${(v * 100).roundToInt()}", color = EditorColors.TextDim, modifier = Modifier.width(40.dp))
  }
}

@Composable
private fun EraseControls(state: PageEditorState) {
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterChip(selected = !state.brushWhite, onClick = { state.brushWhite = false }, label = { Text("Brush: paper colour") })
    FilterChip(selected = state.brushWhite, onClick = { state.brushWhite = true }, label = { Text("Brush: white") })
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Size", color = EditorColors.Text, modifier = Modifier.width(96.dp))
    Slider(value = state.brushSize, onValueChange = { state.brushSize = it }, valueRange = 0.005f..0.08f, modifier = Modifier.weight(1f))
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      "Paint over marks with one finger; two fingers zoom and move.",
      color = EditorColors.TextDim,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = { state.resetPage() }) { Text("Reset page") }
  }
}

@Composable
private fun EditorTabs(state: PageEditorState) {
  val names = when (state.mode) {
    EditorMode.DOCUMENT -> listOf(EditorTab.CROP to "Crop", EditorTab.CLEAN to "Clean", EditorTab.ERASE to "Erase")
    EditorMode.PHOTO -> listOf(EditorTab.CROP to "Crop", EditorTab.CLEAN to "Light", EditorTab.ERASE to "Erase")
    EditorMode.SIGNATURE -> listOf(EditorTab.CROP to "Crop", EditorTab.CLEAN to "Contrast", EditorTab.ERASE to "Erase")
  }
  Row(
    Modifier.fillMaxWidth().background(EditorColors.Background).padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    for ((t, name) in names) {
      FilterChip(selected = state.tab == t, onClick = { state.tab = t }, label = { Text(name) })
    }
  }
}
