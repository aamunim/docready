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

  /** What deleting did */
  enum class Deleted {
    /** Deleted now */
    DONE,

    /** It was already gone */
    GONE,

    /** Android asks the user first (a file the app no longer owns, e.g. after a reinstall) */
    NEEDS_USER,

    /** Not deleted */
    FAILED,
  }

  /** The file deleted (blocking). "Gone" only from a delete that worked, or a file truly not there */
  fun deleteFile(context: Context, link: String): Deleted {
    val uri = Uri.parse(link)
    val own = ownFile(context, uri)
    if (own != null) {
      return when {
        !own.exists() -> Deleted.GONE
        own.delete() -> Deleted.DONE
        else -> Deleted.FAILED
      }
    }
    return try {
      if (context.contentResolver.delete(uri, null, null) > 0) {
        Deleted.DONE
      } else if (exists(context, uri) == false) {
        Deleted.GONE
      } else {
        Deleted.FAILED
      }
    } catch (_: SecurityException) {
      // (RecoverableSecurityException on Android 10, plain on 11+: the user is asked)
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) Deleted.NEEDS_USER else Deleted.FAILED
    } catch (_: Exception) {
      Deleted.FAILED
    }
  }

  /** True / false when known; null when the app may not look */
  private fun exists(context: Context, uri: Uri): Boolean? = try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
  } catch (_: SecurityException) {
    null
  } catch (_: Exception) {
    false
  }

  /** True when the file is gone (deleted now or already) */
  fun delete(context: Context, link: String): Boolean = deleteFile(context, link).let { it == Deleted.DONE || it == Deleted.GONE }

  /**
   * Android 11+: the system's "Allow DocReady to delete these files?" for files the app can't
   * delete by itself (its PendingIntent, to launch with StartIntentSenderForResult); null before
   */
  fun deleteRequest(context: Context, links: List<String>): android.app.PendingIntent? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && links.isNotEmpty()) {
      try {
        android.provider.MediaStore.createDeleteRequest(context.contentResolver, links.map { Uri.parse(it) })
      } catch (_: Exception) {
        null
      }
    } else {
      null
    }
}
