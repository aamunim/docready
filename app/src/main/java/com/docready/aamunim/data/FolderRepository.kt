package com.docready.aamunim.data

import android.content.Context
import com.docready.aamunim.data.model.LocalFolder
import com.docready.aamunim.data.model.SavedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The user's local folders: which ready files were saved on this phone, and where
 * (folders.json in the app's private files). The files themselves are in Downloads/DocReady.
 */
class FolderRepository(context: Context) {
  private val file = File(context.filesDir, "folders.json")
  private val lock = Any()
  private val _folders = MutableStateFlow(read())

  /** Most recently used first */
  val folders: StateFlow<List<LocalFolder>> = _folders.asStateFlow()

  fun get(name: String): LocalFolder? = _folders.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

  fun namesIn(folder: String): Set<String> = get(folder)?.files?.map { it.name }?.toSet() ?: emptySet()

  fun addFiles(folder: String, files: List<SavedFile>) {
    synchronized(lock) {
      val now = System.currentTimeMillis()
      val old = get(folder)
      val updated = LocalFolder(
        name = old?.name ?: folder,
        files = (old?.files ?: emptyList()) + files,
        updatedAt = now,
      )
      write(listOf(updated) + _folders.value.filter { !it.name.equals(folder, ignoreCase = true) })
    }
  }

  /** Forget a folder in the app (its files stay in Downloads) */
  fun forget(name: String) {
    synchronized(lock) { write(_folders.value.filter { !it.name.equals(name, ignoreCase = true) }) }
  }

  private fun write(list: List<LocalFolder>) {
    val sorted = list.sortedByDescending { it.updatedAt }
    file.writeTextAtomic(AppJson.encodeToString(ListSerializer(LocalFolder.serializer()), sorted))
    _folders.value = sorted
  }

  private fun read(): List<LocalFolder> = try {
    if (file.exists()) AppJson.decodeFromString(ListSerializer(LocalFolder.serializer()), file.readText()) else emptyList()
  } catch (_: Exception) {
    emptyList()
  }
}
