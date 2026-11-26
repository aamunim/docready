package com.docready.aamunim.save

import android.content.Context
import com.docready.aamunim.data.FileNames
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Several ready files as one ZIP, written file by file (never all in memory) */
object Zipper {
  const val MIME = "application/zip"

  /** Blocking: call off the main thread. Entry names are made unique ("name (2).pdf"). */
  fun zip(context: Context, files: List<ReadyFile>, zipBase: String): ReadyFile {
    val name = "${FileNames.safeFolder(zipBase).replace(' ', '_')}.zip"
    val out = FileNames.outputFile(context, name)
    val used = mutableSetOf<String>()
    ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
      val buf = ByteArray(64 * 1024)
      for (f in files) {
        val dot = f.name.lastIndexOf('.')
        val entry = FileNames.unique(if (dot > 0) f.name.substring(0, dot) else f.name, if (dot > 0) f.name.substring(dot + 1) else "bin", used)
        used.add(entry)
        zip.putNextEntry(ZipEntry(entry))
        f.file.inputStream().use { input ->
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            zip.write(buf, 0, n)
          }
        }
        zip.closeEntry()
      }
    }
    return ReadyFile(out, name, MIME)
  }

  /**
   * Files read from wherever they are (Downloads links, the app's copies) streamed into one ZIP,
   * one at a time. [entries]: each file's name and how to open it. Blocking.
   */
  fun zipStreams(context: Context, entries: List<Pair<String, () -> java.io.InputStream?>>, zipBase: String): ReadyFile {
    val name = "${FileNames.safeFolder(zipBase).replace(' ', '_')}.zip"
    val out = FileNames.outputFile(context, name)
    val used = mutableSetOf<String>()
    ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
      val buf = ByteArray(64 * 1024)
      for ((fileName, open) in entries) {
        val input = open() ?: continue
        val dot = fileName.lastIndexOf('.')
        val entry = FileNames.unique(if (dot > 0) fileName.substring(0, dot) else fileName, if (dot > 0) fileName.substring(dot + 1) else "bin", used)
        used.add(entry)
        zip.putNextEntry(ZipEntry(entry))
        input.use {
          while (true) {
            val n = it.read(buf)
            if (n < 0) break
            zip.write(buf, 0, n)
          }
        }
        zip.closeEntry()
      }
    }
    return ReadyFile(out, name, MIME)
  }
}
