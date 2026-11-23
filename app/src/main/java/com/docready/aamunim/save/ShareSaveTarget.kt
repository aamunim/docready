package com.docready.aamunim.save

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Share": Android's share sheet (WhatsApp, Gmail, Files, Drive app...) with the ready files */
object ShareSaveTarget : SaveTarget {
  override val kind = SaveTargetKind.SHARE
  override val available = true

  override fun description(folder: String) = "Send with another app"

  override suspend fun save(context: Context, files: List<ReadyFile>, folder: String): SaveOutcome {
    if (files.isEmpty()) return SaveOutcome.Failed("Nothing to share.")
    return withContext(Dispatchers.Main) {
      try {
        val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it.file) }
        val mimes = files.map { it.mime }.distinct()
        shareUris(context, uris, if (mimes.size == 1) mimes[0] else "*/*")
        SaveOutcome.Shared
      } catch (e: Exception) {
        SaveOutcome.Failed(e.message ?: "Sharing did not open.")
      }
    }
  }

  /** The share sheet for links the app may hand on (its own FileProvider / MediaStore files) */
  fun shareUris(context: Context, uris: List<Uri>, mime: String) {
    val intent = if (uris.size == 1) {
      Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uris[0])
      }
    } else {
      Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = mime
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
      }
    }
    val clip = ClipData.newRawUri("", uris[0])
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(intent, "Share")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
  }

  /** Opens one saved file in an app that can show it */
  fun open(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, mime)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Open with"))
  }
}
