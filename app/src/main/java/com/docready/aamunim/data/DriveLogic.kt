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
}
