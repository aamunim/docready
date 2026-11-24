package com.docready.aamunim.data

import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PhotoBackground
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.PresetItem
import com.docready.aamunim.imaging.QrCodes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presets in QR codes: Base45, parts, checks, and junk that must never crash */
class PresetQrTest {
  private fun preset(items: Int, name: String = "B.Ed admission 2026") = Preset(
    id = "x",
    name = name,
    items = List(items) { i ->
      when (i % 3) {
        0 -> PresetItem(ItemKind.PHOTO, 300, 300, 100, OutputFormat.JPG, PhotoBackground.WHITE, "Photo $i", id = "id$i")
        1 -> PresetItem(ItemKind.SIGNATURE, 300, 80, 60, OutputFormat.JPG, name = "Signature $i", id = "id$i")
        else -> PresetItem(
          ItemKind.DOCUMENT, null, null, 500, OutputFormat.PDF, name = "Certificate number $i of the board", pagesHint = "1-2",
          keywords = listOf("secondary", "school $i", "marksheet"), id = "id$i",
        )
      }
    },
  )

  @Test
  fun base45MatchesTheRfc() {
    assertEquals("BB8", Base45.encode("AB".toByteArray()))
    assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
    assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
    assertArrayEquals("ietf!".toByteArray(), Base45.decode("QED8WEX0"))
    assertNull(Base45.decode("GGW"))  // 65535 + 1: too big
    assertNull(Base45.decode("ZZZZ")) // a lone letter at the end
    assertNull(Base45.decode("ab"))   // not in the alphabet
  }

  @Test
  fun smallPresetIsOneCodeAndComesBack() {
    val codes = PresetQr.encode(preset(6))
    assertEquals(1, codes.size)
    assertTrue(codes[0].startsWith("DR1:1/1:"))
    val c = PresetQr.Collector()
    assertEquals(PresetQr.Added.NEW, c.add(codes[0]))
    assertTrue(c.complete)
    val back = c.preset()
    assertEquals("B.Ed admission 2026", back.name)
    assertEquals(6, back.items.size)
    assertEquals(listOf("secondary", "school 2", "marksheet"), back.items[2].keywords)
    // imported items get new ids, never the sender's
    assertTrue(back.items.none { it.id.startsWith("id") || it.id.isEmpty() })
  }

  @Test
  fun bigPresetIsSplitAndPartsComeInAnyOrder() {
    val codes = PresetQr.encode(preset(40, "Long ".repeat(10)))
    assertTrue("parts: ${codes.size}", codes.size >= 1)
    assertTrue(codes.all { it.length <= 1240 })
    val c = PresetQr.Collector()
    for (code in codes.reversed()) assertEquals(PresetQr.Added.NEW, c.add(code))
    assertEquals(PresetQr.Added.ALREADY, c.add(codes[0]))
    assertTrue(c.complete)
    assertEquals(40, c.preset().items.size)
  }

  @Test
  fun forcedPartsDoNotMix() {
    // a preset big enough for two codes: random-looking names do not deflate well
    val r = java.util.Random(7)
    val p = Preset("x", "Mixed", List(40) { i ->
      PresetItem(ItemKind.DOCUMENT, maxKb = 100 + i, format = OutputFormat.PDF, name = List(50) { ('a' + r.nextInt(26)) }.joinToString(""), keywords = List(3) { List(20) { ('a' + r.nextInt(26)) }.joinToString("") })
    })
    val a = PresetQr.encode(p)
    assertTrue("parts: ${a.size}", a.size >= 2)
    val b = PresetQr.encode(p.copy(name = "Other"))
    val c = PresetQr.Collector()
    c.add(a[0])
    assertEquals(PresetQr.Added.OTHER_PRESET, c.add(b[1]))
    assertFalse(c.complete)
    assertEquals(listOf(2) + (3..a.size).toList(), c.missing.filter { it >= 2 }.take(a.size - 1))
    for (code in a.drop(1)) c.add(code)
    assertEquals("Mixed", c.preset().name)
  }

  @Test
  fun junkIsNeverAPresetAndNeverCrashes() {
    val c = PresetQr.Collector()
    for (junk in listOf(null, "", "hello", "https://example.com", "DR1:", "DR1:0/1:00000000:AB", "DR1:2/1:00000000:ABC",
      "DR1:1/1:ZZZZZZZZ:ABC", "DR1:1/1:00000000:abc", "DR1:1/99:00000000:ABC", "DR1:1/1:00000000:" + "A".repeat(5000))) {
      assertEquals(PresetQr.Added.NOT_OURS, c.add(junk))
    }
    // well formed but wrong check / not deflate / not a preset: a message, not a crash
    for (bad in listOf("DR1:1/1:00000000:BB8", "DR1:1/1:${crcOf(byteArrayOf(1, 2, 3))}:${Base45.encode(byteArrayOf(1, 2, 3))}")) {
      val k = PresetQr.Collector()
      assertEquals(PresetQr.Added.NEW, k.add(bad))
      try {
        k.preset()
        throw AssertionError("junk read as a preset")
      } catch (_: IllegalArgumentException) {
      }
    }
  }

  @Test
  fun qrCodesReadBack() {
    for (code in PresetQr.encode(preset(12))) {
      val m = QrCodes.matrix(code)
      val scale = 4
      val w = m.width * scale
      val px = IntArray(w * w) { i -> if (m.get((i % w) / scale, (i / w) / scale)) 0xFF000000.toInt() else -1 }
      assertEquals(listOf(code), QrCodes.decode(px, w, w))
    }
  }

  private fun crcOf(b: ByteArray) = String.format("%08X", java.util.zip.CRC32().apply { update(b) }.value)
}
