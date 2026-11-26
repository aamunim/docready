package com.docready.aamunim.save

import android.content.Context
import android.net.Uri
import java.io.File

/** Files the app saved to Downloads / DocReady (MediaStore links; the app's own on Android 8-9) */
object DownloadsFiles {
  /**
   * Android 8-9 saves (the app's own FileProvider link): the file itself. Its provider answers a
   * query with a row even when the file is gone, so the file on disk is what tells.
   */
  fun ownFile(context: Context, uri: Uri): File? {
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

  fun stillThere(context: Context, uri: Uri): Boolean = ownFile(context, uri)?.exists() ?: try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
  } catch (_: SecurityException) {
    // (not readable by the app any more, but it may well be there)
    true
  } catch (_: Exception) {
    false
  }

  /** The file deleted (blocking); true when it is gone (also when it already was) */
  fun delete(context: Context, link: String): Boolean {
    val uri = Uri.parse(link)
    val own = ownFile(context, uri)
    val removed = try {
      if (own != null) own.delete() || !own.exists() else context.contentResolver.delete(uri, null, null) > 0
    } catch (_: Exception) {
      false
    }
    return removed || !stillThere(context, uri)
  }
}
