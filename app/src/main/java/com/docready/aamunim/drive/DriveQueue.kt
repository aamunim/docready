package com.docready.aamunim.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.docready.aamunim.data.DriveLogic
import com.docready.aamunim.data.DriveState
import com.docready.aamunim.data.FolderLogic
import com.docready.aamunim.docReady
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Drive upload queue: files are marked QUEUED (their record or student file), and one
 * unique background job uploads them when there is a network, retrying with a growing pause.
 * Never blocks the app; offline, the queue simply waits.
 */
object DriveQueue {
  private const val WORK = "drive-upload"

  /** Records (by id) and students' files (by their app path) put in the queue, and the job started */
  fun enqueue(context: Context, recordIds: Set<String> = emptySet(), studentPaths: Set<String> = emptySet()) {
    val app = context.docReady
    if (recordIds.isNotEmpty()) app.folderTree.updateFiles { if (it.id in recordIds) it.copy(drive = DriveLogic.queue(it.drive), driveError = null) else it }
    if (studentPaths.isNotEmpty()) app.students.updateFiles { _, f -> if (f.path in studentPaths) f.copy(drive = DriveLogic.queue(f.drive), driveError = null) else f }
    schedule(context)
  }

  /** "Retry all": every failed upload queued again */
  fun retryAll(context: Context) {
    val app = context.docReady
    app.folderTree.updateFiles { it.copy(drive = DriveLogic.retry(it.drive)) }
    app.students.updateFiles { _, f -> f.copy(drive = DriveLogic.retry(f.drive)) }
    schedule(context)
  }

  /** The job (again): needs a network; waits and retries with backoff */
  fun schedule(context: Context) {
    val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }
}

/** Uploads everything queued, one file at a time; Drive folders found or made as needed */
class DriveUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val ctx = applicationContext
    val app = ctx.docReady
    val tree = app.folderTree
    // (an upload cut off by the app stopping is tried again)
    tree.updateFiles { it.copy(drive = DriveLogic.recover(it.drive)) }
    app.students.updateFiles { _, f -> f.copy(drive = DriveLogic.recover(f.drive)) }
    var token = DriveAuth.tokenOrNull(ctx) ?: run {
      fail(ctx, "Connect Google Drive again in Settings.")
      return Result.failure()
    }
    var retry = false
    // a Drive folder per local folder (cached; made again when deleted on Drive)
    suspend fun <T> withToken(block: (DriveClient) -> T): T = try {
      block(DriveClient(token))
    } catch (e: DriveHttpException) {
      if (e.code != 401) throw e
      DriveAuth.clear(ctx, token)
      token = DriveAuth.tokenOrNull(ctx) ?: throw e
      block(DriveClient(token))
    }
    val cache = HashMap<String, String>()
    suspend fun folderFor(key: String, segments: List<String>): String {
      cache[key]?.let { return it }
      val known = tree.data.value.driveFolders[key]
      if (known != null && withToken { it.exists(known) }) return known.also { cache[key] = it }
      var parent = "root"
      for (s in segments) parent = withToken { it.folder(s, parent) }
      tree.setDriveFolder(key, parent)
      cache[key] = parent
      return parent
    }

    // saved files' records
    for (r in tree.data.value.files.filter { it.drive == DriveState.QUEUED }) {
      tree.updateFiles { if (it.id == r.id) it.copy(drive = DriveState.UPLOADING) else it }
      try {
        val parent = folderFor(r.folderId, DriveLogic.folderSegments(FolderLogic.pathOf(tree.data.value.folders, r.folderId)))
        val id = upload(r.name, r.mime, r.bytes, parent, r.driveFileId) { DriveClient.streamOf(ctx, r.uri) }
        tree.updateFiles { if (it.id == r.id) it.copy(drive = DriveState.DONE, driveFileId = id, driveError = null) else it }
      } catch (e: Exception) {
        val later = e is IOException && (e !is DriveHttpException || DriveLogic.transient(e.code))
        retry = retry || later
        tree.updateFiles { if (it.id == r.id) it.copy(drive = DriveLogic.afterUpload(false, later), driveError = e.message) else it }
      }
    }
    // students' files (DocReady / Students / <name>)
    for (s in app.students.students.value) {
      for (f in s.files.filter { it.drive == DriveState.QUEUED }) {
        app.students.updateFiles { _, x -> if (x.path == f.path) x.copy(drive = DriveState.UPLOADING) else x }
        try {
          val parent = folderFor("student:" + s.id, DriveLogic.studentSegments(s.name))
          val id = upload(f.name, f.mime, File(f.path).length(), parent, f.driveFileId) { File(f.path).inputStream() }
          app.students.updateFiles { _, x -> if (x.path == f.path) x.copy(drive = DriveState.DONE, driveFileId = id, driveError = null) else x }
        } catch (e: Exception) {
          val later = e is IOException && (e !is DriveHttpException || DriveLogic.transient(e.code))
          retry = retry || later
          app.students.updateFiles { _, x -> if (x.path == f.path) x.copy(drive = DriveLogic.afterUpload(false, later), driveError = e.message) else x }
        }
      }
    }
    return if (retry) Result.retry() else Result.success()
  }

  /** Uploaded (a new revision of the earlier one when it is still there); a 401 asks for a fresh token once */
  private suspend fun upload(name: String, mime: String, size: Long, parent: String, existing: String?, input: () -> java.io.InputStream): String {
    val ctx = applicationContext
    var token = DriveAuth.tokenOrNull(ctx) ?: throw IOException("Connect Google Drive again in Settings.")
    fun once(t: String): String {
      val c = DriveClient(t)
      val keep = existing?.takeIf { c.exists(it) }
      return c.uploadFile(name, mime, size, parent, keep, input)
    }
    return try {
      once(token)
    } catch (e: DriveHttpException) {
      if (e.code != 401) throw e
      DriveAuth.clear(ctx, token)
      token = DriveAuth.tokenOrNull(ctx) ?: throw e
      once(token)
    }
  }

  /** No token without the user: every queued file marked failed, to retry after connecting */
  private fun fail(context: Context, why: String) {
    val app = context.docReady
    app.folderTree.updateFiles { if (it.drive == DriveState.QUEUED) it.copy(drive = DriveState.FAILED, driveError = why) else it }
    app.students.updateFiles { _, f -> if (f.drive == DriveState.QUEUED) f.copy(drive = DriveState.FAILED, driveError = why) else f }
  }
}
