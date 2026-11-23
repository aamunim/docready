package com.docready.aamunim.intake

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.docready.aamunim.data.FileNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A file shared to the app, already copied into it */
data class IncomingFile(val file: File, val name: String, val mime: String) {
  val isPdf: Boolean get() = mime == "application/pdf"
}

enum class IntakeTarget { PHOTO, SIGNATURE, PDF_TOOLS }

/** Files shared to the app, waiting for a screen to take them; [target] null = not decided yet */
data class IntakeRequest(val files: List<IncomingFile>, val skipped: Int, val target: IntakeTarget?)

/**
 * "Share to DocReady": the shared photos / PDFs are copied in, then handed to PDF tools
 * (PDFs or several files) or, for one photo, to the screen the user picks.
 */
object Intake {
  private val _request = MutableStateFlow<IntakeRequest?>(null)
  val request: StateFlow<IntakeRequest?> = _request.asStateFlow()

  fun isShare(intent: Intent?): Boolean =
    intent != null && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)

  fun offer(files: List<IncomingFile>, skipped: Int) {
    if (files.isEmpty() && skipped == 0) return
    val target = when {
      files.isEmpty() -> null
      files.size == 1 && !files[0].isPdf -> null // one photo: the user picks what to make
      else -> IntakeTarget.PDF_TOOLS
    }
    _request.value = IntakeRequest(files, skipped, target)
  }

  fun choose(target: IntakeTarget) {
    _request.value = _request.value?.copy(target = target)
  }

  /** The files, if they are meant for [target] (then they are gone from here) */
  fun take(target: IntakeTarget): List<IncomingFile> {
    val r = _request.value ?: return emptyList()
    if (r.target != target) return emptyList()
    _request.value = null
    return r.files
  }

  fun dismiss() {
    _request.value = null
  }

  /** A share's files copied into the app (blocking: call off the main thread); `second` = how many were skipped */
  fun copyShared(context: Context, intent: Intent): Pair<List<IncomingFile>, Int> {
    val uris = mutableListOf<Uri>()
    try {
      when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
        Intent.ACTION_SEND_MULTIPLE ->
          IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
      }
    } catch (_: Exception) {
      return Pair(emptyList(), 1) // a sender's broken share
    }
    val out = mutableListOf<IncomingFile>()
    var skipped = 0
    val ownAuthority = context.packageName + ".fileprovider"
    for ((i, u) in uris.take(60).withIndex()) {
      // only other apps' content links: never file:// paths, never our own private files
      if (u.scheme != "content" || u.authority == null || u.authority!!.substringAfterLast('@') == ownAuthority) {
        skipped++
        continue
      }
      try {
        val name = FileNames.displayName(context, u) ?: "file-${i + 1}"
        val ext = name.substringAfterLast('.', "").lowercase()
        val told = context.contentResolver.getType(u) ?: ""
        // the file's own kind; else its name; else the share's
        val mime = when {
          told.startsWith("image/") || told == "application/pdf" -> told
          ext == "pdf" -> "application/pdf"
          ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") -> "image/${if (ext == "jpg") "jpeg" else ext}"
          told.isEmpty() -> intent.type ?: ""
          else -> told
        }
        if (!(mime.startsWith("image/") || mime == "application/pdf")) {
          skipped++
          continue
        }
        val file = FileNames.copyIn(context, u, if (mime == "application/pdf") "pdf" else "img")
        out.add(IncomingFile(file, name, mime))
      } catch (_: Exception) {
        skipped++ // a file the sender no longer lets us read
      }
    }
    skipped += maxOf(0, uris.size - 60)
    return Pair(out, skipped)
  }
}
