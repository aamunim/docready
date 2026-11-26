package com.docready.aamunim.data

/**
 * Google Drive rules, pure (tested on the JVM): where a local folder goes on Drive, how a file is
 * uploaded, and how the upload queue's states move.
 */
object DriveLogic {
  /** Everything goes under My Drive / DocReady */
  const val ROOT = "DocReady"

  /** Files up to this size go in one request; bigger ones as a resumable upload */
  const val MULTIPART_MAX = 5L * 1024 * 1024

  /** "B.Ed/Batch 1" → DocReady / B.Ed / Batch 1 */
  fun folderSegments(localPath: String): List<String> = listOf(ROOT) + FolderLogic.splitPath(localPath)

  /** A student's files → DocReady / Students / <name> */
  fun studentSegments(studentName: String): List<String> = folderSegments("Students/$studentName")

  fun useMultipart(bytes: Long): Boolean = bytes <= MULTIPART_MAX

  /** Drive's query for a folder of this name inside [parentId] (quotes and backslashes escaped) */
  fun folderQuery(name: String, parentId: String): String {
    val n = name.replace("\\", "\\\\").replace("'", "\\'")
    return "name = '$n' and mimeType = 'application/vnd.google-apps.folder' and '$parentId' in parents and trashed = false"
  }

  /** What an upload's outcome makes of the file's state */
  fun afterUpload(ok: Boolean, retryLater: Boolean): DriveState = when {
    ok -> DriveState.DONE
    retryLater -> DriveState.QUEUED
    else -> DriveState.FAILED
  }

  /** "Retry all": failed ones go back in the queue (the others stay as they are) */
  fun retry(state: DriveState): DriveState = if (state == DriveState.FAILED) DriveState.QUEUED else state

  /** Asked to upload: queued, unless it is uploading right now */
  fun queue(state: DriveState): DriveState = if (state == DriveState.UPLOADING) state else DriveState.QUEUED

  /** An upload cut off by the app stopping: back in the queue */
  fun recover(state: DriveState): DriveState = if (state == DriveState.UPLOADING) DriveState.QUEUED else state

  /** "N waiting": queued or uploading */
  fun waiting(states: List<DriveState>): Int = states.count { it == DriveState.QUEUED || it == DriveState.UPLOADING }

  fun failed(states: List<DriveState>): Int = states.count { it == DriveState.FAILED }

  /** HTTP answers worth trying again later (rate limits, server trouble, no network) */
  fun transient(httpCode: Int): Boolean = httpCode == 408 || httpCode == 429 || httpCode >= 500

  /** The key DocReady sets on each file it creates on Drive (appProperties), so it is found again */
  const val ID_KEY = "docready_id"

  /** A student's file's mark on Drive (stable for its app copy; short enough for appProperties) */
  fun studentFileId(studentId: String, path: String): String {
    val h = java.security.MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
    return "s-" + studentId.take(40) + "-" + h.take(20)
  }

  /** Drive's query for the file DocReady made from [docreadyId] (not in the bin) */
  fun idQuery(docreadyId: String): String {
    val v = docreadyId.replace("\\", "\\\\").replace("'", "\\'")
    return "appProperties has { key='$ID_KEY' and value='$v' } and trashed = false"
  }

  /** What went wrong with one file's upload, and what happens next */
  enum class Failure {
    /** Network trouble or Drive busy: queued again, tried later */
    RETRY,
    /** The file is gone from the phone (or no longer readable): failed, never retried by itself */
    MISSING,
    /** Anything else Drive refused: failed (Retry all queues it again) */
    FAILED,
  }

  const val MISSING_MESSAGE = "Not on the phone any more"

  /**
   * [e] thrown while uploading; [httpCode] when Drive answered with an error. Only socket and
   * timeout errors and Drive's busy answers (408, 429, 5xx) are tried again.
   */
  fun classify(e: Throwable, httpCode: Int? = null): Failure = when {
    e is java.io.FileNotFoundException || e is SecurityException -> Failure.MISSING
    httpCode != null -> if (transient(httpCode)) Failure.RETRY else Failure.FAILED
    e is java.net.SocketTimeoutException || e is java.net.SocketException || e is java.net.UnknownHostException ||
      e is java.io.InterruptedIOException || e is javax.net.ssl.SSLException -> Failure.RETRY
    else -> Failure.FAILED
  }

  /** The state a failure leaves the file in */
  fun stateAfter(f: Failure): DriveState = if (f == Failure.RETRY) DriveState.QUEUED else DriveState.FAILED

  /**
   * A resumable upload's progress, from Drive's Range header ("bytes=0-1048575"): where to go
   * on from (the next byte). No header: nothing was received yet (0).
   */
  fun resumeOffset(range: String?): Long {
    val m = Regex("""bytes\s*=\s*(\d+)\s*-\s*(\d+)""").find(range ?: return 0L) ?: return 0L
    val start = m.groupValues[1].toLongOrNull() ?: return 0L
    val end = m.groupValues[2].toLongOrNull() ?: return 0L
    return if (start == 0L && end >= 0) end + 1 else 0L
  }

  /** Asking a resumable session where it stands: its answer */
  sealed class SessionState {
    /** Already complete (the answer carries the file) */
    object Complete : SessionState()
    /** Go on from this byte */
    data class Resume(val offset: Long) : SessionState()
    /** The session is gone (expired, or never valid): start a new upload */
    object Expired : SessionState()
    /** Another error: handled like any upload error */
    data class Error(val code: Int) : SessionState()
  }

  fun sessionState(code: Int, range: String?, total: Long): SessionState = when (code) {
    200, 201 -> SessionState.Complete
    308 -> resumeOffset(range).let { if (it in 0 until total) SessionState.Resume(it) else SessionState.Expired }
    404, 410 -> SessionState.Expired
    else -> SessionState.Error(code)
  }
}
