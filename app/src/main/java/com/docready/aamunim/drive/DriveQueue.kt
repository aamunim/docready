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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The Drive upload queue: files are marked QUEUED (their record or student file), and one
 * unique background job uploads them when there is a network, retrying with a growing pause.
 * Never blocks the app; offline, the queue simply waits. Nothing is queued or uploaded while
 * Google Drive is not connected. Blocking (disk): call off the main thread.
 */
object DriveQueue {
  const val WORK = "drive-upload"

  private fun connected() = DriveAuth.account.value.connected

  /** Records (by id) and students' files (by their app path) put in the queue, and the job started */
  fun enqueue(context: Context, recordIds: Set<String> = emptySet(), studentPaths: Set<String> = emptySet()) {
    if (!connected()) return
    val app = context.docReady
    if (recordIds.isNotEmpty()) app.folderTree.updateFiles { if (it.id in recordIds) it.copy(drive = DriveLogic.queue(it.drive), driveError = null) else it }
    if (studentPaths.isNotEmpty()) app.students.updateFiles { _, f -> if (f.path in studentPaths) f.copy(drive = DriveLogic.queue(f.drive), driveError = null) else f }
    schedule(context)
  }

  /** "Retry all": every failed upload queued again */
  fun retryAll(context: Context) {
    if (!connected()) return
    val app = context.docReady
    app.folderTree.updateFiles { it.copy(drive = DriveLogic.retry(it.drive)) }
    app.students.updateFiles { _, f -> f.copy(drive = DriveLogic.retry(f.drive)) }
    schedule(context)
  }

  /** The job (again): needs a network; waits and retries with backoff */
  fun schedule(context: Context) {
    if (!connected()) return
    val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  /**
   * Disconnect: the account forgotten first (a running upload stops at the next file), the job
   * cancelled, and every waiting, uploading or failed file set back to not uploaded (unfinished
   * sessions dropped). Files already on Drive stay marked as uploaded.
   */
  suspend fun disconnect(context: Context) {
    DriveAuth.disconnect(context)
    WorkManager.getInstance(context).cancelUniqueWork(WORK)
    withContext(Dispatchers.IO) { resetUnfinished(context) }
  }

  internal fun resetUnfinished(context: Context) {
    val app = context.docReady
    app.folderTree.updateFiles {
      if (it.drive == DriveState.DONE && it.driveSession == null) it
      else it.copy(drive = if (it.drive == DriveState.DONE) it.drive else DriveState.NONE, driveError = null, driveSession = null, driveSessionSize = null)
    }
    app.students.updateFiles { _, f ->
      if (f.drive == DriveState.DONE && f.driveSession == null) f
      else f.copy(drive = if (f.drive == DriveState.DONE) f.drive else DriveState.NONE, driveError = null, driveSession = null, driveSessionSize = null)
    }
  }
}

/** Uploads everything queued, one file at a time; Drive folders found or made as needed */
class DriveUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
  private fun connected() = DriveAuth.account.value.connected

  override suspend fun doWork(): Result {
    // (disconnected: nothing to do, and nothing is touched)
    if (!connected()) return Result.success()
    val ctx = applicationContext
    val app = ctx.docReady
    val tree = app.folderTree
    // (an upload cut off by the app stopping is tried again)
    tree.updateFiles { it.copy(drive = DriveLogic.recover(it.drive)) }
    app.students.updateFiles { _, f -> f.copy(drive = DriveLogic.recover(f.drive)) }
    var token = DriveAuth.tokenOrNull(ctx) ?: run {
      if (connected()) fail(ctx, "Connect Google Drive again in Settings.")
      return Result.success()
    }
    var retry = false
    suspend fun <T> withToken(block: (DriveClient) -> T): T = try {
      block(DriveClient(token))
    } catch (e: DriveHttpException) {
      if (e.code != 401) throw e
      DriveAuth.clear(ctx, token)
      token = DriveAuth.tokenOrNull(ctx) ?: throw e
      block(DriveClient(token))
    }
    // a Drive folder per local folder (cached; made again when deleted on Drive)
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
    /** What a failure leaves (disconnected meanwhile: not uploaded, quietly) */
    fun failure(e: Exception): Triple<DriveState, String?, Boolean> {
      if (!connected()) return Triple(DriveState.NONE, null, false)
      val f = DriveLogic.classify(e, (e as? DriveHttpException)?.code)
      val msg = if (f == DriveLogic.Failure.MISSING) DriveLogic.MISSING_MESSAGE else e.message
      return Triple(DriveLogic.stateAfter(f), msg, f == DriveLogic.Failure.RETRY)
    }

    // saved files' records
    for (r in tree.data.value.files.filter { it.drive == DriveState.QUEUED }) {
      if (isStopped || !connected()) break
      tree.updateFiles { if (it.id == r.id) it.copy(drive = DriveState.UPLOADING) else it }
      try {
        val parent = folderFor(r.folderId, DriveLogic.folderSegments(FolderLogic.pathOf(tree.data.value.folders, r.folderId)))
        // (the length now, not the one recorded at saving)
        val size = DriveClient.lengthOf(ctx, r.uri).takeIf { it >= 0 } ?: DriveClient.streamOf(ctx, r.uri).use { countBytes(it) }
        val cur = tree.data.value.files.firstOrNull { it.id == r.id } ?: continue
        val session = cur.driveSession?.takeIf { cur.driveSessionSize == size }
        val keeper = DriveClient.SessionKeeper { s, n ->
          tree.updateFiles { if (it.id == r.id) it.copy(driveSession = s, driveSessionSize = if (s == null) null else n) else it }
        }
        val id = upload(r.name, r.mime, size, parent, r.driveFileId, r.id, session, keeper) { DriveClient.streamOf(ctx, r.uri) }
        tree.updateFiles { if (it.id == r.id) it.copy(drive = DriveState.DONE, driveFileId = id, driveError = null, driveSession = null, driveSessionSize = null) else it }
      } catch (e: Exception) {
        val (state, msg, later) = failure(e)
        retry = retry || later
        tree.updateFiles { if (it.id == r.id) it.copy(drive = state, driveError = msg) else it }
      }
    }
    // students' files (DocReady / Students / <name>)
    for (s in app.students.students.value) {
      for (f in s.files.filter { it.drive == DriveState.QUEUED }) {
        if (isStopped || !connected()) break
        app.students.updateFiles { _, x -> if (x.path == f.path) x.copy(drive = DriveState.UPLOADING) else x }
        try {
          val parent = folderFor("student:" + s.id, DriveLogic.studentSegments(s.name))
          val file = File(f.path)
          if (!file.exists()) throw java.io.FileNotFoundException(DriveLogic.MISSING_MESSAGE)
          val size = file.length()
          val cur = app.students.get(s.id)?.files?.firstOrNull { it.path == f.path } ?: continue
          val session = cur.driveSession?.takeIf { cur.driveSessionSize == size }
          val keeper = DriveClient.SessionKeeper { ses, n ->
            app.students.updateFiles { _, x -> if (x.path == f.path) x.copy(driveSession = ses, driveSessionSize = if (ses == null) null else n) else x }
          }
          val id = upload(f.name, f.mime, size, parent, f.driveFileId, DriveLogic.studentFileId(s.id, f.path), session, keeper) { file.inputStream() }
          app.students.updateFiles { _, x ->
            if (x.path == f.path) x.copy(drive = DriveState.DONE, driveFileId = id, driveError = null, driveSession = null, driveSessionSize = null) else x
          }
        } catch (e: Exception) {
          val (state, msg, later) = failure(e)
          retry = retry || later
          app.students.updateFiles { _, x -> if (x.path == f.path) x.copy(drive = state, driveError = msg) else x }
        }
      }
    }
    return if (retry && connected()) Result.retry() else Result.success()
  }

  private fun countBytes(s: java.io.InputStream): Long {
    val buf = ByteArray(64 * 1024)
    var n = 0L
    while (true) {
      val r = s.read(buf)
      if (r < 0) return n
      n += r
    }
  }

  /**
   * Uploaded: a new revision of the earlier one when it is still there, else the file DocReady
   * made for this id before (found by its mark), else a new one; a 401 asks for a fresh token once
   */
  private suspend fun upload(
    name: String,
    mime: String,
    size: Long,
    parent: String,
    existing: String?,
    docreadyId: String,
    session: String?,
    keeper: DriveClient.SessionKeeper,
    input: () -> java.io.InputStream,
  ): String {
    val ctx = applicationContext
    var token = DriveAuth.tokenOrNull(ctx) ?: throw DriveHttpException(401, "Connect Google Drive again in Settings.")
    var ses = session
    val keep = DriveClient.SessionKeeper { s, n ->
      ses = s
      keeper.keep(s, n)
    }
    fun once(t: String): String {
      val c = DriveClient(t)
      val known = existing?.takeIf { c.exists(it) }
      return c.uploadFile(name, mime, size, parent, known, docreadyId, ses, keep, input)
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
