package com.docready.aamunim.data

import android.content.Context
import android.net.Uri
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.Student
import com.docready.aamunim.data.model.StudentFile
import com.docready.aamunim.data.model.title
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.save.SaveOutcome
import com.docready.aamunim.save.SaveTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * Students and their files: the list as JSON (students.json); each student's files copied into
 * the app's own storage (files/students/<id>/) and saved to Downloads/DocReady/<student name>/.
 */
class StudentRepository(
  private val context: Context,
  private val phone: SaveTarget,
  private val settings: SettingsRepository,
  private val presets: PresetRepository,
) {
  private val file = File(context.filesDir, "students.json")
  private val lock = Any()
  private val _students = MutableStateFlow(read())

  /** Most recently changed first */
  val students: StateFlow<List<Student>> = _students.asStateFlow()

  /** What pages made for a student said about them (name, roll…), offered on their page until used */
  private val _facts = MutableStateFlow<Map<String, List<com.docready.aamunim.imaging.Fact>>>(emptyMap())
  val facts: StateFlow<Map<String, List<com.docready.aamunim.imaging.Fact>>> = _facts.asStateFlow()

  fun offerFacts(id: String, list: List<com.docready.aamunim.imaging.Fact>) {
    if (list.isEmpty()) return
    _facts.value = _facts.value + (id to (list + (_facts.value[id] ?: emptyList())).distinct())
  }

  fun dropFact(id: String, f: com.docready.aamunim.imaging.Fact) {
    _facts.value = _facts.value + (id to (_facts.value[id] ?: emptyList()).filter { it != f })
  }

  fun get(id: String?): Student? = if (id == null) null else _students.value.firstOrNull { it.id == id }

  fun save(s: Student): Student {
    val now = System.currentTimeMillis()
    val stored = s.copy(createdAt = if (s.createdAt == 0L) now else s.createdAt, updatedAt = now)
    synchronized(lock) { write(_students.value.filter { it.id != stored.id } + stored) }
    return stored
  }

  /**
   * The student forgotten, their app copies removed; [alsoDownloads]: their files in Downloads too.
   * Returns how many Downloads files could not be removed (still there: e.g. no longer the app's
   * own after a reinstall), to be deleted in the Files app.
   */
  suspend fun delete(id: String, alsoDownloads: Boolean): Int = withContext(Dispatchers.IO) {
    val s = get(id) ?: return@withContext 0
    var left = 0
    if (alsoDownloads) {
      for (f in s.files) {
        val uri = Uri.parse(f.downloadsUri ?: continue)
        val own = ownFile(uri)
        val removed = try {
          if (own != null) own.delete() || !own.exists() else context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
          false
        }
        // (a file already gone by other means is not a failure)
        if (!removed && stillThere(uri)) left++
      }
    }
    dirOf(id).deleteRecursively()
    synchronized(lock) { write(_students.value.filter { it.id != id }) }
    left
  }

  /**
   * Android 8-9 saves (the app's own FileProvider link): the file itself. Its provider answers a
   * query with a row even when the file is gone, so the file on disk is what tells.
   */
  private fun ownFile(uri: Uri): File? {
    if (uri.authority != context.packageName + ".fileprovider") return null
    val seg = uri.pathSegments
    if (seg.size < 2) return null
    val root = when (seg[0]) {
      "saved" -> context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "DocReady") }
      "saved_internal" -> File(context.filesDir, "Downloads/DocReady")
      else -> null
    } ?: return null
    val f = File(root, seg.drop(1).joinToString("/"))
    // (never outside the folder the link stands for)
    return f.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
  }

  private fun stillThere(uri: Uri): Boolean = ownFile(uri)?.exists() ?: try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
  } catch (_: SecurityException) {
    // (not readable by the app any more, but it may well be there)
    true
  } catch (_: Exception) {
    false
  }

  /** One file removed from the student (its app copy; the Downloads copy stays) */
  fun removeFile(id: String, f: StudentFile) {
    val s = get(id) ?: return
    File(f.path).delete()
    save(s.copy(files = s.files.filter { it != f }))
  }

  /**
   * Ready files added to a student (blocking IO inside): named by the file-name pattern with the
   * student's tokens, copied into the app's storage and saved to Downloads/DocReady/<student>.
   * Returns where they went (or why not).
   */
  suspend fun attach(id: String, files: List<ReadyFile>, preset: Preset?): SaveOutcome = withContext(Dispatchers.IO) {
    val s = get(id) ?: return@withContext SaveOutcome.Failed("This student is no longer here.")
    // a file counts for an item only when made for the student's own preset (a split made with
    // another preset still adds its files, without ticking anything)
    val linkPreset = preset?.takeIf { s.presetId == null || s.presetId == it.id }
    val naming = NameContext(preset = preset?.name, presetPattern = preset?.namePattern, student = s.name, roll = s.roll)
    val dir = dirOf(id).apply { mkdirs() }
    val used = (dir.list()?.toSet() ?: emptySet()).toMutableSet()
    val named = files.mapIndexed { i, f ->
      val dot = f.name.lastIndexOf('.')
      val base = if (dot > 0) f.name.substring(0, dot) else f.name
      val ext = if (dot > 0) f.name.substring(dot + 1) else "bin"
      val name = FileNames.unique(Naming.apply(settings.naming.value, naming, base, if (files.size > 1) i + 1 else 1), ext, used)
      used.add(name)
      val kept = File(dir, name)
      f.file.copyTo(kept, overwrite = true)
      f.copy(file = kept, name = name)
    }
    // also in Downloads/DocReady/<student> (a failure there still keeps the app's copies)
    val outcome = phone.save(context, named, s.name)
    val links = (outcome as? SaveOutcome.Saved)?.files ?: emptyList()
    val now = System.currentTimeMillis()
    val added = named.mapIndexed { i, f ->
      StudentFile(
        name = f.name,
        path = f.file.path,
        mime = f.mime,
        bytes = f.file.length(),
        itemId = f.itemIndex?.let { linkPreset?.items?.getOrNull(it)?.id?.ifEmpty { null } },
        itemTitle = f.itemIndex?.let { linkPreset?.items?.getOrNull(it)?.title },
        downloadsUri = links.getOrNull(i)?.uri,
        savedAt = now + i,
      )
    }
    val fresh = get(id) ?: return@withContext outcome
    save(fresh.copy(files = fresh.files + added, presetId = fresh.presetId ?: preset?.id))
    outcome
  }

  /**
   * The student's files as ready files in the app's share folder (copies: the student's own
   * storage is never handed to other apps, and the save sheet may rename what it gets). Blocking.
   */
  fun exportCopies(s: Student): List<ReadyFile> {
    val items = presets.get(s.presetId)?.items ?: emptyList()
    return s.files.filter { File(it.path).exists() }.map { f ->
      val out = FileNames.outputFile(context, f.name)
      File(f.path).copyTo(out, overwrite = true)
      ReadyFile(out, f.name, f.mime, itemIndex = f.itemId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } })
    }
  }

  private fun dirOf(id: String) = File(File(context.filesDir, "students"), id)

  private fun write(list: List<Student>) {
    val sorted = list.sortedByDescending { it.updatedAt }
    file.writeTextAtomic(AppJson.encodeToString(ListSerializer(Student.serializer()), sorted))
    _students.value = sorted
  }

  private fun read(): List<Student> = try {
    if (file.exists()) {
      val loaded = AppJson.decodeFromString(ListSerializer(Student.serializer()), file.readText()).sortedByDescending { it.updatedAt }
      // files saved by older versions point to an item by its place: given the item's id once
      val list = loaded.map { s ->
        val items = presets.get(s.presetId)?.items
        if (s.files.none { it.itemId == null && it.itemIndex != null }) {
          s
        } else {
          s.copy(files = s.files.map { f ->
            if (f.itemId != null || f.itemIndex == null) f else f.copy(itemId = items?.getOrNull(f.itemIndex)?.id?.ifEmpty { null }, itemIndex = null)
          })
        }
      }
      if (list != loaded) {
        // (a failed write-back, e.g. a full disk, must never lose the students: written next save)
        try {
          file.writeTextAtomic(AppJson.encodeToString(ListSerializer(Student.serializer()), list))
        } catch (_: Exception) {
        }
      }
      list
    } else {
      emptyList()
    }
  } catch (_: Exception) {
    emptyList()
  }

  companion object {
    fun newId(): String = UUID.randomUUID().toString()
  }
}
