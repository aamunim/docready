package com.docready.aamunim.drive

import android.content.Context
import android.net.Uri
import com.docready.aamunim.data.DriveLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Drive answered with an error (its HTTP code; 401 = the token is no longer good) */
class DriveHttpException(val code: Int, message: String) : IOException(message)

/**
 * Google Drive's REST API (v3) with plain HttpURLConnection: folders found or made by name, and
 * files uploaded in one request (up to 5 MB) or as a resumable upload (bigger), or as a new
 * revision of the file uploaded before. Blocking: call off the main thread.
 */
class DriveClient(private val token: String) {
  private val api = "https://www.googleapis.com/drive/v3/files"
  private val upload = "https://www.googleapis.com/upload/drive/v3/files"
  private val json = Json { ignoreUnknownKeys = true }

  private fun open(url: String, method: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = 20_000
      readTimeout = 60_000
      setRequestProperty("Authorization", "Bearer $token")
    }

  private fun answer(c: HttpURLConnection): String {
    val code = c.responseCode
    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    if (code !in 200..299) throw DriveHttpException(code, "Google Drive answered $code")
    return body
  }

  private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

  /** The file still there (and not in the bin)? */
  fun exists(id: String): Boolean = try {
    val o = obj(answer(open("$api/${enc(id)}?fields=id,trashed", "GET")))
    o["trashed"]?.jsonPrimitive?.content != "true"
  } catch (e: DriveHttpException) {
    if (e.code == 404) false else throw e
  }

  /** A folder of this name inside [parentId] ("root" = My Drive), made when missing */
  fun folder(name: String, parentId: String): String {
    val q = enc(DriveLogic.folderQuery(name, parentId))
    val found = obj(answer(open("$api?q=$q&fields=files(id)&spaces=drive", "GET")))["files"]?.jsonArray
    found?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content?.let { return it }
    val meta = buildJsonObject {
      put("name", name)
      put("mimeType", "application/vnd.google-apps.folder")
      put("parents", JsonArray(listOf(JsonPrimitive(parentId))))
    }
    val c = open(api + "?fields=id", "POST")
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
    c.outputStream.use { it.write(meta.toString().toByteArray()) }
    return obj(answer(c))["id"]!!.jsonPrimitive.content
  }

  /** The file DocReady made from [docreadyId] (its appProperties), when it is on Drive */
  fun findByDocreadyId(docreadyId: String): String? {
    val q = enc(DriveLogic.idQuery(docreadyId))
    val found = obj(answer(open("$api?q=$q&fields=files(id)&spaces=drive", "GET")))["files"]?.jsonArray
    return found?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
  }

  /** Where an upload stands, for the caller to keep: a resumable session started (or ended: null) */
  fun interface SessionKeeper {
    fun keep(session: String?, size: Long)
  }

  /**
   * The file's bytes uploaded: a new revision of [existingId] when given, else a new file in
   * [parentId] (marked with [docreadyId], so a retry after a lost answer finds it instead of
   * making a second one). [size] is the length read now; [session] an unfinished resumable
   * upload of that same length, gone on from where Drive stopped. Returns the Drive file's id.
   */
  fun uploadFile(
    name: String,
    mime: String,
    size: Long,
    parentId: String,
    existingId: String?,
    docreadyId: String,
    session: String?,
    keeper: SessionKeeper,
    input: () -> InputStream,
  ): String {
    if (session != null) {
      resume(session, mime, size, keeper, input)?.let { return it }
    }
    val target = existingId ?: findByDocreadyId(docreadyId)
    val meta = buildJsonObject {
      put("name", name)
      if (target == null) put("parents", JsonArray(listOf(JsonPrimitive(parentId))))
      put("appProperties", buildJsonObject { put(DriveLogic.ID_KEY, docreadyId) })
    }.toString()
    val base = if (target != null) "$upload/${enc(target)}" else upload
    val method = if (target != null) "PATCH" else "POST"
    return if (DriveLogic.useMultipart(size)) multipart(base, method, meta, mime, input) else resumable(base, method, meta, mime, size, keeper, input)
  }

  private fun multipart(base: String, method: String, meta: String, mime: String, input: () -> InputStream): String {
    val boundary = "docready-" + System.nanoTime()
    val c = open("$base?uploadType=multipart&fields=id", if (method == "PATCH") "POST" else method)
    // (HttpURLConnection has no PATCH: Drive takes the override header)
    if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
    c.doOutput = true
    c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
    c.setChunkedStreamingMode(64 * 1024)
    c.outputStream.use { out ->
      out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
      input().use { it.copyTo(out, 64 * 1024) }
      out.write("\r\n--$boundary--\r\n".toByteArray())
    }
    return obj(answer(c))["id"]!!.jsonPrimitive.content
  }

  private fun resumable(base: String, method: String, meta: String, mime: String, size: Long, keeper: SessionKeeper, input: () -> InputStream): String {
    val start = open("$base?uploadType=resumable&fields=id", if (method == "PATCH") "POST" else method)
    if (method == "PATCH") start.setRequestProperty("X-HTTP-Method-Override", "PATCH")
    start.doOutput = true
    start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
    start.setRequestProperty("X-Upload-Content-Type", mime)
    start.setRequestProperty("X-Upload-Content-Length", size.toString())
    start.outputStream.use { it.write(meta.toByteArray()) }
    answer(start)
    val session = start.getHeaderField("Location") ?: throw IOException("Google Drive gave no upload address.")
    // (kept: a retry goes on from where this one stops)
    keeper.keep(session, size)
    return send(session, mime, size, 0L, keeper, input)
  }

  /**
   * An unfinished session asked where it stands (an empty PUT whose Content-Range gives only the total) and gone on
   * from there; null when it is gone (a new upload starts)
   */
  private fun resume(session: String, mime: String, size: Long, keeper: SessionKeeper, input: () -> InputStream): String? {
    val q = open(session, "PUT")
    q.instanceFollowRedirects = false
    q.doOutput = true
    q.setRequestProperty("Content-Range", "bytes */$size")
    q.setFixedLengthStreamingMode(0)
    q.outputStream.close()
    val code = q.responseCode
    return when (val st = DriveLogic.sessionState(code, q.getHeaderField("Range"), size)) {
      DriveLogic.SessionState.Complete -> {
        keeper.keep(null, size)
        obj(answer(q))["id"]!!.jsonPrimitive.content
      }
      is DriveLogic.SessionState.Resume -> {
        q.disconnect()
        send(session, mime, size, st.offset, keeper, input)
      }
      DriveLogic.SessionState.Expired -> {
        q.disconnect()
        keeper.keep(null, size)
        null
      }
      is DriveLogic.SessionState.Error -> {
        q.disconnect()
        throw DriveHttpException(st.code, "Google Drive answered ${st.code}")
      }
    }
  }

  /** The bytes from [offset] to the end sent to the session */
  private fun send(session: String, mime: String, size: Long, offset: Long, keeper: SessionKeeper, input: () -> InputStream): String {
    val put = open(session, "PUT")
    put.instanceFollowRedirects = false
    put.doOutput = true
    put.setRequestProperty("Content-Type", mime)
    if (offset > 0) put.setRequestProperty("Content-Range", "bytes $offset-${size - 1}/$size")
    put.setFixedLengthStreamingMode(size - offset)
    put.outputStream.use { out ->
      input().use { s ->
        skipFully(s, offset)
        copyExactly(s, out, size - offset)
      }
    }
    val id = obj(answer(put))["id"]!!.jsonPrimitive.content
    keeper.keep(null, size)
    return id
  }

  private fun skipFully(s: InputStream, n: Long) {
    var left = n
    val buf = ByteArray(64 * 1024)
    while (left > 0) {
      val skipped = s.skip(left)
      if (skipped > 0) {
        left -= skipped
        continue
      }
      val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
      if (r < 0) throw IOException("The file changed while it was being uploaded.")
      left -= r
    }
  }

  /** Exactly [n] bytes copied (the length told to Drive); a file that changed size is an error */
  private fun copyExactly(s: InputStream, out: java.io.OutputStream, n: Long) {
    var left = n
    val buf = ByteArray(256 * 1024)
    while (left > 0) {
      val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
      if (r < 0) throw IOException("The file changed while it was being uploaded.")
      out.write(buf, 0, r)
      left -= r
    }
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  companion object {
    /** A content link's bytes (gone: FileNotFoundException, never retried) */
    fun streamOf(context: Context, link: String): InputStream =
      context.contentResolver.openInputStream(Uri.parse(link)) ?: throw java.io.FileNotFoundException(DriveLogic.MISSING_MESSAGE)

    /**
     * A content link's length, read now (the size Drive is told; a stored size can be out of
     * date); -1 when the provider cannot tell
     */
    fun lengthOf(context: Context, link: String): Long {
      val uri = Uri.parse(link)
      try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd -> if (fd.length >= 0) return fd.length }
          ?: throw java.io.FileNotFoundException(DriveLogic.MISSING_MESSAGE)
      } catch (e: java.io.FileNotFoundException) {
        throw e
      } catch (e: SecurityException) {
        throw e
      } catch (_: Exception) {
      }
      return try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
          if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
      } catch (_: Exception) {
        -1L
      }
    }
  }
}
