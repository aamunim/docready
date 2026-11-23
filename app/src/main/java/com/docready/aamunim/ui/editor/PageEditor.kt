package com.docready.aamunim.ui.editor

import kotlinx.coroutines.delay
import com.docready.aamunim.ui.components.ScanAnimationCard
import com.docready.aamunim.ui.components.ScanAnim
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.docready.aamunim.data.model.BeautifyRecipe
import com.docready.aamunim.data.model.BeautyStrength
import com.docready.aamunim.data.model.CropRect
import com.docready.aamunim.data.model.EraseStroke
import com.docready.aamunim.data.model.Look
import com.docready.aamunim.data.model.PageRecipe
import com.docready.aamunim.imaging.BeautyResult
import com.docready.aamunim.imaging.Beautifier
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

enum class EditorTab { CROP, CLEAN, BEAUTIFY, ERASE }

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
  /** The phone's animations are on (else no pauses to show a reveal) */
  private val animate: Boolean = true,
) {
  val recipes = mutableStateListOf<PageRecipe>().apply { addAll(pages.map { it.recipe }) }
  var index by mutableIntStateOf(start.coerceIn(0, max(0, pages.size - 1)))
    private set
  var tab by mutableStateOf(EditorTab.CROP)
    private set

  fun selectTab(t: EditorTab) {
    if (t == tab) return
    tab = t
    // the Beautify tab also shows the page without Beautify's clean-up (compare, loupe)
    if (t == EditorTab.BEAUTIFY) refresh()
  }

  // ---- ✨ Beautify
  /** The page without Beautify's clean-up (same framing), for compare and the loupe */
  var beforeBeautify by mutableStateOf<ImageBitmap?>(null)
    private set

  /** What the last analysis of each page found (to switch a step back on), and its notes */
  private val found = HashMap<Int, BeautyResult>()
  var notes by mutableStateOf<List<String>>(emptyList())
    private set
  var askAngle by mutableStateOf<Float?>(null)
    private set
  var beautifying by mutableStateOf<String?>(null)
    private set
  private var beautifyJob: Job? = null
  var showFolds by mutableStateOf(false)

  /** The loupe's middle (parts of the shown page) */
  var loupe by mutableStateOf(Offset(0.5f, 0.35f))

  val foundHere: BeautyResult? get() = found[index]

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
    notes = found[i]?.notes ?: emptyList()
    askAngle = found[i]?.askAngle
    beforeBeautify = null
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
    val withBefore = tab == EditorTab.BEAUTIFY && r.beautify?.hasPixels == true
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
          val before = if (withBefore) {
            // the same framing without Beautify's clean-up
            PageRenderer.finish(PageRenderer.geometry(src.copy(Bitmap.Config.ARGB_8888, true), r, beautyPixels = false), r).asImageBitmap()
          } else {
            null
          }
          if (my != renderVersion) return@withContext null
          val finished = PageRenderer.finish(g.copy(Bitmap.Config.ARGB_8888, true), r)
          Triple(shownGeo, finished.asImageBitmap(), before)
        }
        if (made != null && my == renderVersion) {
          geometryImage = made.first
          preview = made.second
          beforeBeautify = made.third
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
  /**
   * The rules every change goes through (also Beautify all): new corners / turn / angle reset
   * Beautify (its boxes belong to the old framing) and erasing; a new Beautify framing (trim,
   * upside down, straightening) resets erasing. Returns the page's recipe and a note, if any.
   */
  private fun ruled(old: PageRecipe, new: PageRecipe): Pair<PageRecipe, String?> {
    var r = new
    var note: String? = null
    val userMoved = r.quad != old.quad || r.turn != old.turn || r.rotate != old.rotate
    if (userMoved && old.beautify != null && r.beautify != null) {
      r = r.copy(beautify = null)
      note = "Beautify was reset because the corners, turn or angle changed: tap ✨ again (Undo brings it back)."
    }
    val beautyMoved = r.beautify?.sameGeometry(old.beautify) == false || (r.beautify == null && old.beautify?.hasGeometry == true)
    // (Turn 90 carries erasing along itself: its strokes are already moved)
    // (only when Beautify's framing stays as it was: else the strokes would land elsewhere)
    val turnedOnly = r.turn != old.turn && r.quad == old.quad && r.rotate == old.rotate && r.erase != old.erase && !beautyMoved
    if (old.erase.isNotEmpty() && !turnedOnly && (r.quad != old.quad || r.rotate != old.rotate || beautyMoved)) {
      r = r.copy(erase = emptyList())
      note = (note?.let { "$it " } ?: "") + "Erasing was removed because the page moved under it (Undo brings it back)."
    }
    // the crop box is drawn on the framed page: a new Beautify framing would shift it
    if (beautyMoved && r.crop != null) {
      r = r.copy(crop = null)
      note = (note?.let { "$it " } ?: "") + "The crop box was reset because the page's framing changed (Undo brings it back)."
    }
    return r to note
  }

  fun update(new: PageRecipe, key: String? = null) {
    val old = recipe
    if (key == null || key != lastKey) notice = null // (an older note is about an older step)
    val (r, note) = ruled(old, new)
    if (note != null) notice = note
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
    // (the radius is a part of the page's shorter side: a turn leaves it as it is)
    val erase = when {
      r.erase.isEmpty() -> r.erase
      else -> r.erase.map { st ->
        val pts = ArrayList<Float>(st.points.size)
        for (k in 0 until st.points.size / 2) {
          pts.add(1f - st.points[k * 2 + 1])
          pts.add(st.points[k * 2])
        }
        EraseStroke(pts, st.radius, st.white)
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
    // the brush was sized on the shown page (its width): as a part of the uncropped page's shorter side
    val shown = preview
    val radius = if (shown != null) {
      val pageW = shown.width / max(0.0001f, crop.width)
      val pageH = shown.height / max(0.0001f, crop.height)
      brushSize * shown.width / min(pageW, pageH)
    } else {
      brushSize
    }
    update(recipe.copy(erase = recipe.erase + EraseStroke(pts, radius, brushWhite)))
  }

  /** The page as Beautify will see it: framed by the user's corners / turns, before its own straightening */
  private fun analysable(src: Bitmap, r: PageRecipe): Bitmap = PageRenderer.geometry(src, r.copy(beautify = null))

  private fun applyFound(r: PageRecipe, res: BeautyResult): PageRecipe =
    r.copy(beautify = res.recipe, removeShadows = r.removeShadows || res.shadow)

  /** ✨ One tap: this page analysed, only the steps it needs turned on */
  fun beautify() {
    val src = sourceBmp ?: return
    val r = recipe
    val i = index
    val strength = r.beautify?.strength ?: BeautyStrength.MEDIUM
    beautifyJob?.cancel()
    beautifyJob = scope.launch {
      beautifying = "Looking at the page…"
      startAnim(if (mode == EditorMode.SIGNATURE) "Boosting the ink…" else "Cleaning the page…")
      try {
        val res = withContext(Dispatchers.Default) {
          val page = analysable(src.copy(Bitmap.Config.ARGB_8888, true), r)
          try {
            Beautifier.analyze(page, strength, mode == EditorMode.SIGNATURE)
          } finally {
            page.recycle()
          }
        }
        if (i != index) return@launch
        found[i] = res
        notes = res.notes
        askAngle = res.askAngle
        update(applyFound(recipe, res))
        if (res.recipe.labels().isEmpty() && !res.shadow) notice = "This page looks fine: nothing to fix."
        finishAnim("Page cleaned")
      } catch (e: CancellationException) {
        anim = null
        throw e
      } catch (e: Throwable) {
        anim = null
        error = if (e is OutOfMemoryError) "Not enough memory to look at this page." else e.message ?: "Beautify could not look at this page."
      } finally {
        // (a stopped job must not clear the progress of the one that replaced it)
        if (beautifyJob === coroutineContext[Job]) beautifying = null
      }
    }
  }

  /** ✨ Every page, each by its own analysis (undoable page by page) */
  fun beautifyAll() {
    beautifyJob?.cancel()
    beautifyJob = scope.launch {
      try {
        for (j in pages.indices) {
          beautifying = "Beautifying page ${j + 1} of ${pages.size}…"
          val r = recipes[j]
          val strength = r.beautify?.strength ?: BeautyStrength.MEDIUM
          val (res, raw) = withContext(Dispatchers.Default) {
            val page = analysable(pages[j].load(Beautifier.ANALYSIS_SIDE), r)
            try {
              // (the page as it is, for the scanning animation)
              val shown = DocTools.scaleToMax(page.copy(Bitmap.Config.ARGB_8888, false), ANIM_SIDE).asImageBitmap()
              Beautifier.analyze(page, strength, mode == EditorMode.SIGNATURE) to shown
            } finally {
              page.recycle()
            }
          }
          anim = ScanAnim(animKey++, raw, null, "Beautifying page ${j + 1} of ${pages.size}…")
          found[j] = res
          // the same rules as a change made by hand (erasing that no longer fits is removed)
          val (next, _) = ruled(recipes[j], applyFound(recipes[j], res))
          if (next != recipes[j]) {
            undo.getOrPut(j) { ArrayList() }.add(recipes[j])
            redo[j]?.clear()
            recipes[j] = next
          }
          // the cleaned page revealed under the sweep, then the tick
          // (long documents: no extra render just for the reveal)
          val cleaned = if (pages.size <= 20) {
            withContext(Dispatchers.Default) { PageRenderer.render(pages[j].load(ANIM_SIDE), recipes[j]).asImageBitmap() }
          } else {
            raw
          }
          anim = anim?.copy(after = cleaned, done = true, label = "Page ${j + 1} of ${pages.size} ready")
          // (a moment to see it; never slowing a long document down)
          if (animate && pages.size <= 20) delay(550)
        }
        notice = "Every page was looked at; each got only the steps it needs."
      } catch (e: CancellationException) {
        notice = "Beautify all stopped: the pages done so far keep their changes."
        throw e
      } catch (e: Throwable) {
        error = if (e is OutOfMemoryError) "Not enough memory for Beautify all." else e.message ?: "Beautify all stopped."
      } finally {
        // (a stopped job must not clear the progress of the one that replaced it)
        if (beautifyJob === coroutineContext[Job]) beautifying = null
        anim = null
        // finished or stopped: this page's view, notes and undo catch up
        notes = found[index]?.notes ?: emptyList()
        askAngle = found[index]?.askAngle
        lastKey = null
        flags()
        refresh()
      }
    }
  }

  // ---- the scanning animation while a page is cleaned (Beautify, Magic)
  var anim by mutableStateOf<ScanAnim?>(null)
    private set
  private var animKey = 0

  /** The sweep starts over the page as it is shown now */
  private fun startAnim(label: String) {
    val shown = preview ?: source ?: return
    anim = ScanAnim(animKey++, shown, null, label)
  }

  /** Once the new preview is made: the reveal, the tick, then the page */
  private suspend fun finishAnim(label: String) {
    if (anim == null) return
    renderJob?.join()
    val after = preview
    anim = if (after != null) anim?.copy(after = after, done = true, label = label) else null
    delay(if (animate) 900 else 300)
    anim = null
  }

  /** A look chosen in the Clean tab; the Magic looks show the scanning animation while they run */
  fun setLook(l: Look) {
    if (l == recipe.look) return
    val magic = l == Look.MAGIC || l == Look.MAGIC_BW || l == Look.LIGHTEN
    // one reveal at a time: a quick second choice replaces the first
    lookAnimJob?.cancel()
    if (magic) startAnim("Applying ${l.title}…") else anim = null
    update(recipe.copy(look = l))
    if (magic) lookAnimJob = scope.launch { finishAnim("${l.title} applied") }
  }

  private var lookAnimJob: Job? = null

  fun stopBeautify() {
    beautifyJob?.cancel()
    beautifying = null
  }

  /** A preset strength: every step that is on moves to its level */
  fun setStrength(s: BeautyStrength) {
    val b = recipe.beautify ?: BeautifyRecipe()
    fun lv(v: Float, k: Float = 1f) = if (v > 0f) s.level * k else 0f
    update(
      recipe.copy(
        beautify = b.copy(
          strength = s,
          glare = lv(b.glare),
          stains = lv(b.stains),
          whiten = lv(b.whiten),
          folds = lv(b.folds),
          clarity = lv(b.clarity, 0.8f),
          ink = lv(b.ink),
        ),
      ),
    )
  }

  /** One step changed (its switch or its slider) */
  fun setBeauty(change: (BeautifyRecipe) -> BeautifyRecipe) {
    val b = recipe.beautify ?: BeautifyRecipe()
    update(recipe.copy(beautify = change(b)))
  }

  /** The large tilt Beautify only offered, applied because the user asked */
  fun acceptAskAngle() {
    val a = askAngle ?: return
    askAngle = null
    setBeauty { it.copy(deskew = a, foundDeskew = a) }
  }

  fun done() = onDone(recipes.toList())

  fun close() = onClose()

  companion object {
    /** The preview's longer side: about a phone screen */
    const val PREVIEW_SIDE = 1400

    /** The animation's pages in Beautify all */
    private const val ANIM_SIDE = 800
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
          state.anim?.let {
            Box(Modifier.fillMaxSize().background(EditorColors.Background.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
              ScanAnimationCard(it, height = 380, textColor = EditorColors.Text)
            }
          }
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
              EditorTab.BEAUTIFY -> BeautifyControls(state)
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
    EditorTab.CLEAN, EditorTab.ERASE, EditorTab.BEAUTIFY -> {
      val shown = state.preview ?: return
      val beauty = state.tab == EditorTab.BEAUTIFY
      ZoomableImage(
        image = shown,
        before = if (beauty) state.beforeBeautify ?: state.source else state.source,
        erase = state.tab == EditorTab.ERASE,
        brushSize = state.brushSize,
        brushWhite = state.brushWhite,
        onStroke = { state.addStroke(it) },
        onTap = if (beauty) ({ p -> state.loupe = p }) else null,
        overlay = if (beauty) beautyOverlay(state) else null,
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
  onTap: ((Offset) -> Unit)? = null,
  overlay: (DrawScope.(Rect) -> Unit)? = null,
) {
  val tapped by rememberUpdatedState(onTap)
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
          detectTapGestures(
            onDoubleTap = {
              zoom = 1f
              pan = Offset.Zero
            },
            onTap = { p -> tapped?.invoke(toPage(p)) },
          )
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
      if (overlay != null && !showBefore) {
        // drawn with the page: same zoom and pan
        Canvas(
          Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = pan.x
            translationY = pan.y
          },
        ) { overlay(fitted()) }
      }
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
      for (l in Look.entries) FilterChip(selected = r.look == l, onClick = { state.setLook(l) }, label = { Text(l.title) })
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
    EditorMode.DOCUMENT -> listOf(EditorTab.CROP to "Crop", EditorTab.CLEAN to "Clean", EditorTab.BEAUTIFY to "✨ Beautify", EditorTab.ERASE to "Erase")
    // (never on photos: a face is not paper)
    EditorMode.PHOTO -> listOf(EditorTab.CROP to "Crop", EditorTab.CLEAN to "Light", EditorTab.ERASE to "Erase")
    EditorMode.SIGNATURE -> listOf(EditorTab.CROP to "Crop", EditorTab.CLEAN to "Contrast", EditorTab.BEAUTIFY to "✨ Ink", EditorTab.ERASE to "Erase")
  }
  Row(
    Modifier.fillMaxWidth().background(EditorColors.Background).padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    for ((t, name) in names) {
      FilterChip(selected = state.tab == t, onClick = { state.selectTab(t) }, label = { Text(name) })
    }
  }
}
