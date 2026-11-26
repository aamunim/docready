package com.docready.aamunim.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.docready.aamunim.data.model.PageRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One page of a draft. [file]: its source (a picture, or the PDF it is a page of): a full path
 * while the tool is open, only the file's name inside the draft's folder on disk.
 */
@Serializable
data class DraftPage(
  val file: String,
  /** Its page number in [file] when that is a PDF; -1 = a picture */
  val pdfPage: Int = -1,
  val from: String = "",
  val sourceBytes: Long = 0L,
  val recipe: PageRecipe = PageRecipe.Plain,
  val warning: String? = null,
  val checkCorners: Boolean = false,
  /** Split by preset: the preset item it is given to */
  val item: Int? = null,
)

/** Split by preset: the part of an item's first page that is cut (left, top, right, bottom) */
@Serializable
data class DraftCut(val item: Int, val box: List<Float>)

/**
 * Work in progress in a tool (Scan, PDF tools, Split by preset, Photo): its pages with their
 * edits and the tool's settings, kept in files/drafts/<key>/ so it survives the app being closed.
 * Only sources and recipes are kept (never made files): the tool makes everything again.
 */
@Serializable
data class Draft(
  val key: String,
  /** "scan", "pdf", "split" or "photo" */
  val tool: String,
  val title: String,
  val presetId: String? = null,
  val itemIndex: Int = -1,
  val studentId: String? = null,
  val pages: List<DraftPage> = emptyList(),
  val values: Map<String, String> = emptyMap(),
  val cuts: List<DraftCut> = emptyList(),
  val updatedAt: Long = 0L,
)

/** "Scan · 3 pages · 2 h ago" style words for a draft */
fun Draft.summary(now: Long = System.currentTimeMillis()): String {
  val what = when {
    tool == "photo" -> "1 photo"
    pages.size == 1 -> "1 page"
    else -> "${pages.size} pages"
  }
  return "$what · ${ago(now - updatedAt)}"
}

private fun ago(ms: Long): String {
  val min = ms / 60_000L
  return when {
    min < 1 -> "just now"
    min < 60 -> "$min min ago"
    min < 24 * 60 -> "${min / 60} h ago"
    else -> "${min / (24 * 60)} days ago"
  }
}

/**
 * The drafts on disk. A draft's sources are copied into its own folder (a picked file's link and
 * the app's cache do not last); drafts not touched for 30 days are removed when the app starts.
 */
class DraftRepository(private val context: Context) {
  private val root = File(context.filesDir, "drafts")
  private val lock = Mutex()
  private var tidied = false
  private val _drafts = MutableStateFlow<List<Draft>>(emptyList())

  /** Most recently changed first (read by [refresh]) */
  val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

  /** The list read again (and, once per app start, old drafts removed) */
  suspend fun refresh() = io { publish() }

  /** Writes started as a tool closed (or ended a draft), by draft: a new screen's load waits for them */
  private val pending = java.util.concurrent.ConcurrentHashMap<String, Job>()

  /**
   * [block] on the repository's own scope, after any earlier such work for [key]; [load] of that
   * key waits for it, so a draft flushed by a closing screen is never missed by the next one.
   */
  fun launchFor(key: String, block: suspend () -> Unit) {
    synchronized(pending) {
      val before = pending[key]
      val job = scope.launch {
        before?.join()
        block()
      }
      pending[key] = job
      job.invokeOnCompletion { pending.remove(key, job) }
    }
  }

  suspend fun load(key: String): Draft? {
    pending[key]?.join()
    return loadNow(key)
  }

  private suspend fun loadNow(key: String): Draft? = io {
    val dir = dirOf(key)
    val d = read(dir) ?: return@io null
    // full paths again, pages whose file is gone left out
    val pages = d.pages.mapNotNull { p -> File(dir, p.file).takeIf { it.exists() }?.let { p.copy(file = it.path) } }
    if (pages.isEmpty()) null else d.copy(pages = pages)
  }

  /**
   * Work outliving the screen that started it (a draft written as the tool closes, a draft ended
   * just before going back): never cancelled with the screen.
   */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Kept (sources copied in once, files no longer used removed). [still] is asked once the
   * draft's turn has come (inside the lock): false = no longer wanted (the work was saved meanwhile).
   */
  suspend fun save(d: Draft, still: () -> Boolean = { true }) = io {
    if (!still()) return@io
    val dir = dirOf(d.key).apply { mkdirs() }
    val pages = d.pages.map { p ->
      val src = File(p.file)
      val inside = if (src.parentFile?.canonicalPath == dir.canonicalPath) src else File(dir, src.name)
      if (!inside.exists() || inside.length() != src.length()) {
        if (!src.exists()) return@map null
        src.copyTo(inside, overwrite = true)
      }
      p.copy(file = inside.name)
    }.filterNotNull()
    val stored = d.copy(pages = pages, updatedAt = System.currentTimeMillis())
    File(dir, JSON).writeTextAtomic(AppJson.encodeToString(Draft.serializer(), stored))
    val used = pages.map { it.file }.toSet() + JSON
    dir.listFiles()?.forEach { if (it.name !in used) it.delete() }
    publish()
  }

  /**
   * The work was saved: no longer offered. Its files stay until the app starts again (the open
   * tool may still be showing them).
   */
  suspend fun clear(key: String, still: () -> Boolean = { true }) = io {
    if (!still()) return@io
    File(dirOf(key), JSON).delete()
    publish()
  }

  /** Thrown away with its files (only when no open tool is using them) */
  suspend fun discard(key: String) = io {
    dirOf(key).deleteRecursively()
    publish()
  }

  private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
    lock.withLock {
      if (!tidied) {
        tidied = true
        tidy()
      }
      block()
    }
  }

  /** At app start: drafts older than 30 days, and folders of drafts already saved, removed */
  private fun tidy() {
    val now = System.currentTimeMillis()
    root.listFiles()?.forEach { dir ->
      val d = read(dir)
      if (d == null || now - d.updatedAt > MAX_AGE_MS) dir.deleteRecursively()
    }
  }

  private fun publish() {
    _drafts.value = (root.listFiles() ?: emptyArray()).mapNotNull { read(it) }.sortedByDescending { it.updatedAt }
  }

  private fun read(dir: File): Draft? = try {
    File(dir, JSON).takeIf { it.exists() }?.let { AppJson.decodeFromString(Draft.serializer(), it.readText()) }
  } catch (_: Exception) {
    null
  }

  private fun dirOf(key: String) = File(root, key)

  companion object {
    private const val JSON = "draft.json"
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** One draft per tool and what it is made for (a preset's item, a student) */
    fun keyOf(tool: String, presetId: String?, item: Int, studentId: String?): String {
      val raw = listOfNotNull(tool, studentId?.let { "s$it" }, presetId?.let { "p$it-$item" }).joinToString("_")
      val safe = raw.replace(Regex("[^A-Za-z0-9_-]"), "-")
      return if (safe.length <= 80) safe else "${tool}_${Integer.toHexString(raw.hashCode())}"
    }
  }
}

/**
 * Autosave for a tool: once started, the tool's state ([snapshot], null = nothing to keep) is
 * written a moment after it stops changing, and at once when the tool closes ([flush]).
 * [done] after a save: the draft is no longer offered (until the work changes again).
 */
class DraftKeeper(
  private val scope: CoroutineScope,
  private val repo: DraftRepository,
  val key: String,
  private val snapshot: () -> Draft?,
) {
  private var job: Job? = null

  /** The state that was saved: not kept as a draft again until it changes */
  @Volatile
  private var savedState: Draft? = null

  /** The tool had something to keep in this session (only then does "nothing" end the draft) */
  @Volatile
  private var hadContent = false

  /** The last state written (or ended), to skip writing the same again on [flush] */
  @Volatile
  private var written: Draft? = null

  val started: Boolean get() = job != null

  fun start() {
    if (job != null) return
    job = scope.launch {
      snapshotFlow { snapshot() }.collectLatest { d ->
        if (d != null) hadContent = true
        // changed since it was saved: a draft again (and "nothing" ends it again)
        if (d != savedState) savedState = null
        delay(1200)
        write(d)
      }
    }
  }

  /**
   * The state now, written unless already: as the tool closes (its own scope is being cancelled,
   * so the write runs on the repository's).
   */
  fun flush() {
    if (job == null) return
    val d = snapshot()
    if (d != null) hadContent = true
    if (d == written) return
    if (d != savedState) savedState = null
    repo.launchFor(key) { write(d) }
  }

  private suspend fun write(d: Draft?) {
    try {
      when {
        d == null && !hadContent -> Unit
        d == null -> repo.clear(key).also { written = null }
        else -> repo.save(d) { d != savedState }.also { written = d }
      }
    } catch (_: Exception) {
      // (a draft is a convenience: a full disk never stops the work)
    }
  }

  fun done() {
    val d = snapshot()
    savedState = d
    written = d
    // (on the repository's scope: the tool usually closes right after)
    repo.launchFor(key) {
      try {
        repo.clear(key)
      } catch (_: Exception) {
      }
    }
  }
}

/**
 * A tool's draft: on opening, an earlier draft is offered ([offer]: "Continue where you left
 * off?") or, opened from the Drafts list ([begin] with resume), brought back at once; autosave
 * starts only after that, so it never overwrites a draft that was not answered yet.
 * [restore] brings a draft's pages and settings back into the tool and says whether it could:
 * when it could not, autosave stays off and the draft stays on disk untouched.
 */
class DraftHost(
  private val scope: CoroutineScope,
  private val repo: DraftRepository,
  val key: String,
  snapshot: () -> Draft?,
  private val restore: suspend (Draft) -> Boolean,
) {
  private val keeper = DraftKeeper(scope, repo, key, snapshot)

  var offer by mutableStateOf<Draft?>(null)
    private set

  fun begin(resume: Boolean) {
    scope.launch {
      val d = try {
        repo.load(key)
      } catch (_: Exception) {
        null
      }
      when {
        d == null -> keeper.start()
        resume -> bringBack(d)
        else -> offer = d
      }
    }
  }

  /** A fresh start (pages handed over for this item): any older draft here is replaced */
  fun beginFresh() {
    scope.launch {
      try {
        repo.discard(key)
      } catch (_: Exception) {
      }
      keeper.start()
    }
  }

  private suspend fun bringBack(d: Draft) {
    val ok = try {
      restore(d)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Throwable) {
      false
    }
    if (ok) keeper.start()
  }

  fun continueDraft() {
    val d = offer ?: return
    offer = null
    scope.launch { bringBack(d) }
  }

  fun discard() {
    offer = null
    scope.launch {
      try {
        repo.discard(key)
      } catch (_: Exception) {
      }
      keeper.start()
    }
  }

  /** The work was saved or added to a student: no longer a draft */
  fun done() = keeper.done()

  /** The tool is closing (ViewModel.onCleared): the latest state written now */
  fun flush() = keeper.flush()
}
