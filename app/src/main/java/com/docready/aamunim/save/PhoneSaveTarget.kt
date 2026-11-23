package com.docready.aamunim.save

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.FolderRepository
import com.docready.aamunim.data.model.SavedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "This phone": Downloads/DocReady/<folder>/ through MediaStore (Android 10 and newer, no
 * permission needed). Android 8-9: the app's own Downloads folder (no storage permission),
 * with Share to put a copy elsewhere. A name already there gets "-2", "-3"... (never overwritten).
 */
class PhoneSaveTarget(private val folders: FolderRepository) : SaveTarget {
  override val kind = SaveTargetKind.PHONE
  override val available = true

  override fun description(folder: String): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      "Downloads / DocReady / ${FileNames.safeFolder(folder)}"
    } else {
      "DocReady's own folder on this phone (use Share to copy it elsewhere)"
    }

  override suspend fun save(context: Context, files: List<ReadyFile>, folder: String): SaveOutcome =
    withContext(Dispatchers.IO) {
      val name = FileNames.safeFolder(folder)
      try {
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          saveToDownloads(context, files, name)
        } else {
          saveToAppFolder(context, files, name)
        }
        folders.addFiles(name, saved)
        SaveOutcome.Saved(
          where = description(name),
          names = saved.map { it.name },
          note = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) null else "On Android 9 and older, files stay in DocReady's own folder.",
        )
      } catch (e: Exception) {
        SaveOutcome.Failed(e.message ?: "The file could not be saved.")
      }
    }

  private fun saveToDownloads(context: Context, files: List<ReadyFile>, folder: String): List<SavedFile> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
    val resolver = context.contentResolver
    val relative = Environment.DIRECTORY_DOWNLOADS + "/DocReady/" + folder + "/"
    val taken = (existingNames(context, relative) + folders.namesIn(folder)).toMutableSet()
    val out = mutableListOf<SavedFile>()
    for (f in files) {
      val name = uniqueName(f.name, taken)
      taken.add(name)
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, f.mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Android did not let DocReady save in Downloads.")
      try {
        val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("The file could not be written.")
        stream.use { o -> f.file.inputStream().use { it.copyTo(o) } }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
      } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
      }
      out.add(SavedFile(name, uri.toString(), f.mime, f.file.length(), System.currentTimeMillis()))
    }
    return out
  }

  /** The names DocReady already put in that folder (an app sees its own files there) */
  private fun existingNames(context: Context, relative: String): Set<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
    val names = mutableSetOf<String>()
    try {
      context.contentResolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        MediaStore.MediaColumns.RELATIVE_PATH + "=?",
        arrayOf(relative),
        null,
      )?.use { c ->
        while (c.moveToNext()) c.getString(0)?.let { names.add(it) }
      }
    } catch (_: Exception) {
      // not able to look: the app's own list still keeps names apart
    }
    return names
  }

  private fun saveToAppFolder(context: Context, files: List<ReadyFile>, folder: String): List<SavedFile> {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Downloads")
    val dir = File(base, "DocReady/$folder").apply { mkdirs() }
    val taken = ((dir.list()?.toSet() ?: emptySet()) + folders.namesIn(folder)).toMutableSet()
    val out = mutableListOf<SavedFile>()
    for (f in files) {
      val name = uniqueName(f.name, taken)
      taken.add(name)
      val target = File(dir, name)
      f.file.copyTo(target, overwrite = false)
      val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
      out.add(SavedFile(name, uri.toString(), f.mime, target.length(), System.currentTimeMillis()))
    }
    return out
  }

  private fun uniqueName(name: String, taken: Set<String>): String {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot + 1) else "bin"
    return FileNames.unique(base, ext, taken)
  }
}
