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

  /**
   * The file's bytes uploaded: a new revision of [existingId] when given, else a new file in
   * [parentId]. Returns the Drive file's id.
   */
  fun uploadFile(name: String, mime: String, size: Long, parentId: String, existingId: String?, input: () -> InputStream): String {
    val meta = buildJsonObject {
      put("name", name)
      if (existingId == null) put("parents", JsonArray(listOf(JsonPrimitive(parentId))))
    }.toString()
    val base = if (existingId != null) "$upload/${enc(existingId)}" else upload
    val method = if (existingId != null) "PATCH" else "POST"
    return if (DriveLogic.useMultipart(size)) multipart(base, method, meta, mime, input) else resumable(base, method, meta, mime, size, input)
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

  private fun resumable(base: String, method: String, meta: String, mime: String, size: Long, input: () -> InputStream): String {
    val start = open("$base?uploadType=resumable&fields=id", if (method == "PATCH") "POST" else method)
    if (method == "PATCH") start.setRequestProperty("X-HTTP-Method-Override", "PATCH")
    start.doOutput = true
    start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
    start.setRequestProperty("X-Upload-Content-Type", mime)
    start.setRequestProperty("X-Upload-Content-Length", size.toString())
    start.outputStream.use { it.write(meta.toByteArray()) }
    answer(start)
    val session = start.getHeaderField("Location") ?: throw IOException("Google Drive gave no upload address.")
    val put = open(session, "PUT")
    put.doOutput = true
    put.setRequestProperty("Content-Type", mime)
    put.setFixedLengthStreamingMode(size)
    put.outputStream.use { out -> input().use { it.copyTo(out, 256 * 1024) } }
    return obj(answer(put))["id"]!!.jsonPrimitive.content
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  companion object {
    /** A content link's bytes */
    fun streamOf(context: Context, link: String): InputStream =
      context.contentResolver.openInputStream(Uri.parse(link)) ?: throw IOException("The file is no longer on the phone.")
  }
}
