package com.docready.aamunim.data

import com.docready.aamunim.data.model.Preset
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Presets as QR codes. The preset's compact JSON (the shared file's format) is deflated and
 * written in Base45 (RFC 9285), whose letters are all in the QR code's alphanumeric mode (about
 * 5.5 bits a letter instead of 8). A big preset is cut into parts, each its own code:
 *
 *     DR1:<part>/<parts>:<CRC32 of all the deflated bytes, 8 hex>:<Base45 of this part's bytes>
 *
 * The check value ties the parts together (parts of another preset are refused) and checks the
 * whole once all are in. Reading never throws on junk: anything not ours is simply not ours.
 */
object PresetQr {
  const val PREFIX = "DR1:"

  /** Deflated bytes per code: at most 1200 Base45 letters, a dense but readable code at ECC M */
  const val PART_BYTES = 800
  const val MAX_PARTS = 12

  private val pattern = Regex("^DR1:(\\d{1,2})/(\\d{1,2}):([0-9A-F]{8}):([0-9A-Z $%*+\\-./:]{2,1300})$")

  /** One code's content */
  class Part(val index: Int, val total: Int, val check: String, val bytes: ByteArray)

  /** The preset's codes, in order (one for most presets) */
  fun encode(preset: Preset): List<String> {
    val packed = deflate(PresetCodec.encodeCompact(preset).toByteArray(Charsets.UTF_8))
    val check = crc(packed)
    val chunks = packed.toList().chunked(PART_BYTES).map { it.toByteArray() }
    if (chunks.size > MAX_PARTS) throw IllegalArgumentException("This preset is too big for QR codes: share it as a file.")
    return chunks.mapIndexed { i, c -> "$PREFIX${i + 1}/${chunks.size}:$check:${Base45.encode(c)}" }
  }

  /** A scanned code's content as one of our parts, or null (another kind of code, or damaged) */
  fun parse(text: String?): Part? {
    if (text == null || !text.startsWith(PREFIX) || text.length > 1400) return null
    val m = pattern.matchEntire(text) ?: return null
    val (i, n, check, data) = m.destructured
    val index = i.toIntOrNull() ?: return null
    val total = n.toIntOrNull() ?: return null
    if (total !in 1..MAX_PARTS || index !in 1..total) return null
    val bytes = Base45.decode(data) ?: return null
    if (bytes.isEmpty() || bytes.size > PART_BYTES) return null
    return Part(index, total, check, bytes)
  }

  /** What scanning one more code did */
  enum class Added { NEW, ALREADY, OTHER_PRESET, NOT_OURS }

  /** The parts scanned so far (in any order) of one preset */
  class Collector {
    private val parts = HashMap<Int, Part>()
    var total: Int = 0
      private set
    private var check: String? = null

    val count: Int get() = parts.size
    val complete: Boolean get() = total > 0 && parts.size == total

    /** The part numbers still missing (1-based) */
    val missing: List<Int> get() = (1..total).filter { it !in parts }

    fun add(text: String?): Added {
      val p = parse(text) ?: return Added.NOT_OURS
      if (check != null && (p.check != check || p.total != total)) return Added.OTHER_PRESET
      if (p.index in parts) return Added.ALREADY
      check = p.check
      total = p.total
      parts[p.index] = p
      return Added.NEW
    }

    fun clear() {
      parts.clear()
      total = 0
      check = null
    }

    /** The preset, once complete: put together, checked, inflated and read strictly */
    fun preset(): Preset {
      if (!complete) throw IllegalArgumentException("Scan every part first.")
      val all = ByteArrayOutputStream()
      for (i in 1..total) all.write(parts.getValue(i).bytes)
      val packed = all.toByteArray()
      if (crc(packed) != check) throw IllegalArgumentException("These codes don't fit together: scan them again.")
      val json = inflate(packed, PresetCodec.MAX_BYTES) ?: throw IllegalArgumentException("This is not a DocReady preset.")
      return PresetCodec.readText(String(json, Charsets.UTF_8))
    }
  }

  private fun crc(b: ByteArray): String = CRC32().apply { update(b) }.value.let { String.format(Locale.US, "%08X", it) }

  private fun deflate(b: ByteArray): ByteArray {
    val d = Deflater(Deflater.BEST_COMPRESSION, true)
    try {
      d.setInput(b)
      d.finish()
      val out = ByteArrayOutputStream()
      val buf = ByteArray(4096)
      while (!d.finished()) out.write(buf, 0, d.deflate(buf))
      return out.toByteArray()
    } finally {
      d.end()
    }
  }

  /** Inflated, or null when damaged or bigger than [max] (never a "zip bomb") */
  private fun inflate(b: ByteArray, max: Int): ByteArray? {
    val inf = Inflater(true)
    try {
      inf.setInput(b)
      val out = ByteArrayOutputStream()
      val buf = ByteArray(4096)
      while (!inf.finished()) {
        val n = inf.inflate(buf)
        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
        out.write(buf, 0, n)
        if (out.size() > max) return null
      }
      return out.toByteArray()
    } catch (_: Exception) {
      return null
    } finally {
      inf.end()
    }
  }
}

/** Base45 (RFC 9285): bytes as letters of the QR alphanumeric set */
object Base45 {
  private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

  fun encode(b: ByteArray): String {
    val sb = StringBuilder((b.size + 1) / 2 * 3)
    var i = 0
    while (i + 1 < b.size) {
      var n = (b[i].toInt() and 255) * 256 + (b[i + 1].toInt() and 255)
      sb.append(ALPHABET[n % 45]); n /= 45
      sb.append(ALPHABET[n % 45]); n /= 45
      sb.append(ALPHABET[n])
      i += 2
    }
    if (i < b.size) {
      val n = b[i].toInt() and 255
      sb.append(ALPHABET[n % 45]).append(ALPHABET[n / 45])
    }
    return sb.toString()
  }

  /** The bytes, or null for anything that is not valid Base45 */
  fun decode(s: String): ByteArray? {
    if (s.length % 3 == 1) return null
    val out = ByteArrayOutputStream(s.length / 3 * 2 + 1)
    var i = 0
    while (i < s.length) {
      val c = ALPHABET.indexOf(s[i])
      val d = if (i + 1 < s.length) ALPHABET.indexOf(s[i + 1]) else -1
      if (c < 0 || d < 0) return null
      if (i + 2 < s.length) {
        val e = ALPHABET.indexOf(s[i + 2])
        if (e < 0) return null
        val n = c + d * 45 + e * 45 * 45
        if (n > 65535) return null
        out.write(n shr 8)
        out.write(n and 255)
        i += 3
      } else {
        val n = c + d * 45
        if (n > 255) return null
        out.write(n)
        i += 2
      }
    }
    return out.toByteArray()
  }
}
