package com.docready.aamunim.data

import android.content.Context
import com.docready.aamunim.data.model.SavedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/** An app-managed folder (nested: [parentId] null = top level) */
@Serializable
data class FolderNode(
  val id: String,
  val name: String,
  val parentId: String? = null,
  val pinned: Boolean = false,
  /** New files saved here are queued for Google Drive */
  val autoUpload: Boolean = false,
  val createdAt: Long = 0L,
  val updatedAt: Long = 0L,
)

/** Where a file stands with Google Drive */
@Serializable
enum class DriveState { NONE, QUEUED, UPLOADING, DONE, FAILED }

/**
 * A saved file as the Folders tab knows it: a record pointing at the file (its Downloads /
 * DocReady content link, or the app's own link on Android 8-9); the file itself is never copied.
 */
@Serializable
data class FileRecord(
  val id: String,
  val folderId: String,
  val name: String,
  val uri: String,
  val mime: String,
  val bytes: Long,
  val savedAt: Long,
  /** Its item's limit when it was made for one */
  val maxKb: Int? = null,
  /** The preset it was made for (the Folders tab groups by it) */
  val preset: String? = null,
  /** Google Drive: the file uploaded from this record (a new revision next time), and its state */
  val driveFileId: String? = null,
  val drive: DriveState = DriveState.NONE,
  val driveError: String? = null,
  /** An unfinished resumable upload: its session address, and the size it was started for */
  val driveSession: String? = null,
  val driveSessionSize: Long? = null,
)

@Serializable
data class FolderData(
  /** Students' files once wrongly read in from the older list: removed (once) */
  val studentLinksCleaned: Boolean = false,
  val folders: List<FolderNode> = emptyList(),
  val files: List<FileRecord> = emptyList(),
  /** The folder last chosen in the save sheet, per preset (by name; "" = no preset) */
  val lastFolderByPreset: Map<String, String> = emptyMap(),
  /** Google Drive folder ids, per local folder id */
  val driveFolders: Map<String, String> = emptyMap(),
  /** "Also upload to Drive" in the save sheet, per preset (by name; "" = no preset) */
  val driveByPreset: Map<String, Boolean> = emptyMap(),
  /** Students' folders with Auto upload on ("student:<id>") */
  val autoUploadKeys: Set<String> = emptySet(),
)

/**
 * The folder tree's rules, pure (tested on the JVM): names made safe and unique among their
 * siblings, paths, moves that never make a cycle, deleting with or without the files.
 */
object FolderLogic {
  const val SCANS = "Scans"
  const val ZIPS = "ZIP exports"

  /** A name a folder (and a Downloads directory) can have: no slashes or odd marks, not empty */
  fun sanitize(name: String): String {
    val clean = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]+"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(60).trim()
    return clean.ifEmpty { "New folder" }
  }

  /** "B.Ed / Batch 1" → ["B.Ed", "Batch 1"] (each part made safe) */
  fun splitPath(path: String): List<String> = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }.map(::sanitize)

  fun children(folders: List<FolderNode>, parentId: String?): List<FolderNode> = folders.filter { it.parentId == parentId }

  /** "B.Ed/Batch 1" for a folder (its names from the top) */
  fun pathOf(folders: List<FolderNode>, id: String?): String {
    val parts = ArrayList<String>()
    var cur = folders.firstOrNull { it.id == id }
    val seen = HashSet<String>()
    while (cur != null && seen.add(cur.id)) {
      parts.add(cur.name)
      cur = folders.firstOrNull { it.id == cur!!.parentId }
    }
    return parts.asReversed().joinToString("/")
  }

  /** [node] is [ancestor] or inside it */
  fun isWithin(folders: List<FolderNode>, node: String?, ancestor: String): Boolean {
    var cur = node
    val seen = HashSet<String>()
    while (cur != null && seen.add(cur)) {
      if (cur == ancestor) return true
      cur = folders.firstOrNull { it.id == cur }?.parentId
    }
    return false
  }

  /** Kept for the app at the top level: "Students" is where students' folders are */
  const val RESERVED_TOP = "Students"

  /** A name not used by another folder in the same place ("Name (2)"…); "Students" at the top is the app's */
  fun uniqueName(folders: List<FolderNode>, parentId: String?, name: String, exceptId: String? = null): String {
    val taken = children(folders, parentId).filter { it.id != exceptId }.map { it.name.lowercase() }.toSet() +
      (if (parentId == null) setOf(RESERVED_TOP.lowercase()) else emptySet())
    val base = sanitize(name)
    if (base.lowercase() !in taken) return base
    var n = 2
    while ("$base ($n)".lowercase() in taken) n++
    return "$base ($n)"
  }

  /** The folder at [path] (made where missing); the folders after, and its id */
  fun ensurePath(folders: List<FolderNode>, path: String, now: Long, newId: () -> String): Pair<List<FolderNode>, String> {
    val parts = splitPath(path).ifEmpty { listOf(SCANS) }
    var list = folders
    var parent: String? = null
    for (p in parts) {
      val found = children(list, parent).firstOrNull { it.name.equals(p, ignoreCase = true) }
      parent = if (found != null) {
        found.id
      } else {
        val node = FolderNode(newId(), uniqueName(list, parent, p), parent, createdAt = now, updatedAt = now)
        list = list + node
        node.id
      }
    }
    return list to parent!!
  }

  fun create(folders: List<FolderNode>, parentId: String?, name: String, now: Long, id: String): List<FolderNode> =
    folders + FolderNode(id, uniqueName(folders, parentId, name), parentId, createdAt = now, updatedAt = now)

  fun rename(folders: List<FolderNode>, id: String, name: String, now: Long): List<FolderNode> {
    val f = folders.firstOrNull { it.id == id } ?: return folders
    val n = uniqueName(folders, f.parentId, name, exceptId = id)
    return folders.map { if (it.id == id) it.copy(name = n, updatedAt = now) else it }
  }

  /** Moved under [newParent] (null = top level); refused (unchanged) when that would make a cycle */
  fun move(folders: List<FolderNode>, id: String, newParent: String?, now: Long): List<FolderNode>? {
    val f = folders.firstOrNull { it.id == id } ?: return null
    if (newParent != null && (folders.none { it.id == newParent } || isWithin(folders, newParent, id))) return null
    val n = uniqueName(folders, newParent, f.name, exceptId = id)
    return folders.map { if (it.id == id) it.copy(parentId = newParent, name = n, updatedAt = now) else it }
  }

  /** The folder and everything inside it */
  fun subtree(folders: List<FolderNode>, id: String): Set<String> = folders.filter { isWithin(folders, it.id, id) }.map { it.id }.toSet()

  /**
   * The folder (and its sub-folders) deleted. [keepFiles]: their file records go to Scans; else
   * they are removed and returned (to delete the files themselves too).
   */
  fun delete(data: FolderData, id: String, keepFiles: Boolean, now: Long, newId: () -> String): Pair<FolderData, List<FileRecord>> {
    val gone = subtree(data.folders, id)
    if (gone.isEmpty()) return data to emptyList()
    var folders = data.folders.filter { it.id !in gone }
    val inside = data.files.filter { it.folderId in gone }
    var files = data.files.filter { it.folderId !in gone }
    if (keepFiles && inside.isNotEmpty()) {
      val (f2, scans) = ensurePath(folders, SCANS, now, newId)
      folders = f2
      files = files + inside.map { it.copy(folderId = scans) }
      return data.copy(folders = folders, files = files, driveFolders = data.driveFolders - gone) to emptyList()
    }
    return data.copy(folders = folders, files = files, driveFolders = data.driveFolders - gone) to inside
  }
}

/**
 * The Folders tab's tree (folders2.json): folders and file records. Saves record their files
 * here; students' folders are not stored (each student's files are their folder). Made once from
 * the older list of folders (folders.json, kept as it was).
 */
class FolderTree(context: Context, private val legacy: FolderRepository) {
  private val file = File(context.filesDir, "folders2.json")
  private val backup = File(context.filesDir, "folders2.json.bak")
  private val studentsFile = File(context.filesDir, "students.json")
  private val lock = Any()
  private val _data = MutableStateFlow(FolderData())
  val data: StateFlow<FolderData> = _data.asStateFlow()

  /** Read from disk (in the background at app start: never on the main thread) */
  private val _loaded = MutableStateFlow(false)
  val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
  private val ready = java.util.concurrent.CountDownLatch(1)

  init {
    Thread {
      val d = try {
        load()
      } catch (_: Throwable) {
        FolderData()
      }
      synchronized(lock) { _data.value = d }
      _loaded.value = true
      ready.countDown()
    }.start()
  }

  private fun newId() = UUID.randomUUID().toString()

  /**
   * The folder's path ("B.Ed/Batch 1"); null while the tree is still being read (the Folders
   * screens show "Reading your folders…" until [loaded]). Never waits: safe on the main thread.
   */
  fun path(id: String?): String? = if (_loaded.value) FolderLogic.pathOf(_data.value.folders, id) else null

  /** The folder; null while the tree is still being read (or when there is no such folder). Never waits. */
  fun folder(id: String?): FolderNode? = if (_loaded.value) _data.value.folders.firstOrNull { it.id == id } else null

  /** The folder's path once the tree is read (suspends, never blocks a thread) */
  suspend fun pathWhenRead(id: String?): String {
    _loaded.first { it }
    return FolderLogic.pathOf(_data.value.folders, id)
  }

  /** The names already used for files in the folder at [path] (never two the same) */
  fun namesIn(path: String): Set<String> {
    ready.await()
    val d = _data.value
    val id = idOfPath(d.folders, path) ?: return emptySet()
    return d.files.filter { it.folderId == id }.map { it.name }.toSet()
  }

  private fun idOfPath(folders: List<FolderNode>, path: String): String? {
    var parent: String? = null
    for (p in FolderLogic.splitPath(path)) {
      parent = FolderLogic.children(folders, parent).firstOrNull { it.name.equals(p, ignoreCase = true) }?.id ?: return null
    }
    return parent
  }

  /** The folder at [path], made where missing; its id */
  fun ensure(path: String): String = update { d ->
    val (folders, id) = FolderLogic.ensurePath(d.folders, path, now(), ::newId)
    d.copy(folders = folders) to id
  }

  /** Saved files recorded in the folder at [path] (made where missing); the records */
  fun addSaved(path: String, saved: List<SavedFile>, preset: String? = null): List<FileRecord> = update { d ->
    val (folders, id) = FolderLogic.ensurePath(d.folders, path, now(), ::newId)
    // (Auto upload queues only while Google Drive is connected)
    val auto = folders.firstOrNull { it.id == id }?.autoUpload == true && com.docready.aamunim.drive.DriveAuth.account.value.connected
    val recs = saved.map {
      FileRecord(newId(), id, it.name, it.uri, it.mime, it.bytes, it.savedAt, it.maxKb, preset, drive = if (auto) DriveState.QUEUED else DriveState.NONE)
    }
    d.copy(folders = folders.map { if (it.id == id) it.copy(updatedAt = now()) else it }, files = d.files + recs) to recs
  }

  fun create(parentId: String?, name: String): String = update { d ->
    val id = newId()
    d.copy(folders = FolderLogic.create(d.folders, parentId, name, now(), id)) to id
  }

  fun rename(id: String, name: String) = change { it.copy(folders = FolderLogic.rename(it.folders, id, name, now())) }

  fun setPinned(id: String, pinned: Boolean) = change { d -> d.copy(folders = d.folders.map { if (it.id == id) it.copy(pinned = pinned) else it }) }

  fun setAutoUpload(id: String, on: Boolean) = change { d -> d.copy(folders = d.folders.map { if (it.id == id) it.copy(autoUpload = on) else it }) }

  /** Records moved to another folder (the files stay where they are on the phone) */
  fun moveFiles(ids: Set<String>, folderId: String) = change { d -> d.copy(files = d.files.map { if (it.id in ids) it.copy(folderId = folderId) else it }) }

  /** Records forgotten (the caller deletes the files too when asked) */
  fun removeFiles(ids: Set<String>) = change { d -> d.copy(files = d.files.filter { it.id !in ids }) }

  /** The folder deleted; the records whose files should be deleted too (none when kept) */
  fun deleteFolder(id: String, keepFiles: Boolean): List<FileRecord> = update { d -> FolderLogic.delete(d, id, keepFiles, now(), ::newId) }

  fun rememberFolder(preset: String?, folderId: String) = change { it.copy(lastFolderByPreset = it.lastFolderByPreset + ((preset ?: "") to folderId)) }

  fun lastFolder(preset: String?): String? = ready.await().let { _data.value }.lastFolderByPreset[preset ?: ""]?.takeIf { id -> _data.value.folders.any { it.id == id } }

  /** Any change to the records (Drive states…) */
  fun updateFiles(change: (FileRecord) -> FileRecord) = change { d -> d.copy(files = d.files.map(change)) }

  fun rememberDrive(preset: String?, on: Boolean) = change { it.copy(driveByPreset = it.driveByPreset + ((preset ?: "") to on)) }

  fun driveFor(preset: String?): Boolean = ready.await().let { _data.value }.driveByPreset[preset ?: ""] ?: false

  fun setAutoUploadKey(key: String, on: Boolean) = change { it.copy(autoUploadKeys = if (on) it.autoUploadKeys + key else it.autoUploadKeys - key) }

  fun setDriveFolder(localId: String, driveId: String?) = change { d ->
    d.copy(driveFolders = if (driveId == null) d.driveFolders - localId else d.driveFolders + (localId to driveId))
  }

  private fun now() = System.currentTimeMillis()

  private fun change(block: (FolderData) -> FolderData) {
    update { block(it) to Unit }
  }

  /**
   * A change, once the tree is read (it is never written over before). Blocking: every caller
   * runs it off the main thread (Dispatchers.IO, a worker), so the wait never blocks the UI.
   */
  private fun <T> update(block: (FolderData) -> Pair<FolderData, T>): T {
    ready.await()
    return synchronized(lock) {
      val (d, r) = block(_data.value)
      if (d != _data.value) {
        write(d)
        _data.value = d
      }
      r
    }
  }

  /** Written safely: the last good file kept as .bak first */
  private fun write(d: FolderData) {
    try {
      if (file.exists()) file.copyTo(backup, overwrite = true)
      file.writeTextAtomic(AppJson.encodeToString(FolderData.serializer(), d))
    } catch (_: Exception) {
      // (kept in memory; written with the next change)
    }
  }

  private fun read(f: File): FolderData? = try {
    if (f.exists()) AppJson.decodeFromString(FolderData.serializer(), f.readText()) else null
  } catch (_: Exception) {
    null
  }

  /**
   * The tree from its file, else from its backup; made from the older list only when neither
   * file exists (a damaged file never starts the tree again from scratch)
   */
  private fun load(): FolderData {
    val d = read(file) ?: read(backup)
    if (d != null) return cleanStudentLinks(d)
    if (file.exists() || backup.exists()) {
      // (both damaged: kept aside, and an empty tree; nothing older is read over them)
      try {
        file.copyTo(File(file.path + ".broken"), overwrite = true)
      } catch (_: Exception) {
      }
      return FolderData(studentLinksCleaned = true)
    }
    return migrate()
  }

  /** Students' files in Downloads (their links): the student's own folder lists them */
  private fun studentLinks(): Set<String> = try {
    if (studentsFile.exists()) {
      AppJson.decodeFromString(ListSerializer(com.docready.aamunim.data.model.Student.serializer()), studentsFile.readText())
        .flatMap { s -> s.files.mapNotNull { it.downloadsUri } }.toSet()
    } else {
      emptySet()
    }
  } catch (_: Exception) {
    emptySet()
  }

  /**
   * Once, for trees made by an earlier version: records of students' files (read in from the
   * older list) removed, and folders left empty by that removed — so no file shows twice, and
   * deleting from a folder never touches a student's checklist file
   */
  private fun cleanStudentLinks(d: FolderData): FolderData {
    if (d.studentLinksCleaned) return d
    val links = studentLinks()
    val gone = d.files.filter { it.uri in links }
    val emptied = gone.map { it.folderId }.toSet()
    val files = d.files - gone.toSet()
    val folders = d.folders.filter { f ->
      f.id !in emptied || files.any { it.folderId == f.id } || d.folders.any { it.parentId == f.id }
    }
    val cleaned = d.copy(folders = folders, files = files, studentLinksCleaned = true)
    write(cleaned)
    return cleaned
  }

  /** Once: the older folders (folders.json) as top-level folders with their files (not students') */
  private fun migrate(): FolderData {
    var d = FolderData(studentLinksCleaned = true)
    val links = studentLinks()
    try {
      for (old in legacy.folders.value.asReversed()) {
        // (a student's files are their own folder: never read in twice)
        val files = old.files.filter { it.uri !in links }
        if (files.isEmpty()) continue
        val (folders, id) = FolderLogic.ensurePath(d.folders, old.name, old.updatedAt, ::newId)
        d = d.copy(
          folders = folders,
          files = d.files + files.map { FileRecord(newId(), id, it.name, it.uri, it.mime, it.bytes, it.savedAt, it.maxKb) },
        )
      }
      write(d)
    } catch (_: Exception) {
      // (nothing is lost: the older list stays readable, and this is tried again next start)
    }
    return d
  }
}
