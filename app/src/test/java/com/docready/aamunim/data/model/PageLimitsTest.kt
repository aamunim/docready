package com.docready.aamunim.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLimitsTest {
  @Test
  fun oldHintsBecomeNumbers() {
    assertEquals(1 to 1, PageLimits.parseHint("1"))
    assertEquals(2 to 2, PageLimits.parseHint("2"))
    assertEquals(1 to 2, PageLimits.parseHint("1-2"))
    assertEquals(1 to 2, PageLimits.parseHint("1–2"))
    assertEquals(1 to 3, PageLimits.parseHint(" 1 - 3 "))
    assertEquals(2 to 4, PageLimits.parseHint("4-2"))
    assertEquals(1 to 2, PageLimits.parseHint("1 to 2"))
    assertEquals(3 to 3, PageLimits.parseHint("3 pages"))
    assertEquals(null to 3, PageLimits.parseHint("up to 3"))
    assertEquals(null to PageLimits.ANY, PageLimits.parseHint("any"))
    assertEquals(PageLimits.ANY to PageLimits.ANY, PageLimits.parseHint("999"))
    assertNull(PageLimits.parseHint("front and back"))
    assertNull(PageLimits.parseHint(""))
    assertNull(PageLimits.parseHint(null))
  }

  @Test
  fun notUnderstoodMeansOnePage() {
    assertEquals(null to 1, PageLimits.migrate("both sides"))
    assertEquals(null to 1, PageLimits.migrate(null))
    assertEquals(1 to 2, PageLimits.migrate("1-2"))
  }

  @Test
  fun olderAppsStillReadAHint() {
    assertEquals("1", PageLimits.hintOf(null, null))
    assertEquals("2", PageLimits.hintOf(2, 2))
    assertEquals("up to 3", PageLimits.hintOf(null, 3))
    assertEquals("2-4", PageLimits.hintOf(2, 4))
    assertEquals("any", PageLimits.hintOf(null, PageLimits.ANY))
    // and they read back as the same numbers
    assertEquals(2 to 2, PageLimits.parseHint(PageLimits.hintOf(2, 2)))
    assertEquals(null to 3, PageLimits.parseHint(PageLimits.hintOf(null, 3)))
    assertEquals(2 to 4, PageLimits.parseHint(PageLimits.hintOf(2, 4)))
    assertEquals(null to PageLimits.ANY, PageLimits.parseHint(PageLimits.hintOf(null, PageLimits.ANY)))
  }

  @Test
  fun itemRanges() {
    assertEquals(1..1, PageLimits.range(ItemKind.PHOTO, OutputFormat.JPG, 3, 5))
    assertEquals(1..1, PageLimits.range(ItemKind.SIGNATURE, OutputFormat.PNG, null, 9))
    // a document made as a picture is one page whatever its numbers
    assertEquals(1..1, PageLimits.range(ItemKind.DOCUMENT, OutputFormat.JPG, 2, 4))
    // unset: at most one page
    assertEquals(1..1, PageLimits.range(ItemKind.DOCUMENT, OutputFormat.PDF, null, null))
    assertEquals(1..3, PageLimits.range(ItemKind.DOCUMENT, OutputFormat.PDF, null, 3))
    assertEquals(2..2, PageLimits.range(ItemKind.DOCUMENT, OutputFormat.PDF, 2, 2))
    assertEquals(3..3, PageLimits.range(ItemKind.DOCUMENT, OutputFormat.PDF, 5, 3))
  }

  @Test
  fun pickingRules() {
    val one = 1..1
    val upTo3 = 1..3
    val exactly2 = 2..2
    // 5 photos or a 60-page PDF for a one-page item: always picked
    assertTrue(PageLimits.needsPicking(5, one))
    assertTrue(PageLimits.needsPicking(60, one))
    assertFalse(PageLimits.needsPicking(1, one))
    assertFalse(PageLimits.needsPicking(3, upTo3))
    assertTrue(PageLimits.needsPicking(4, upTo3))
    // never more than the item takes
    assertTrue(PageLimits.canSelectMore(0, one))
    assertFalse(PageLimits.canSelectMore(1, one))
    assertFalse(PageLimits.canSelectMore(3, upTo3))
    // "Use selected": between the fewest and the most
    assertFalse(PageLimits.selectionOk(0, upTo3))
    assertTrue(PageLimits.selectionOk(2, upTo3))
    assertFalse(PageLimits.selectionOk(1, exactly2))
    assertTrue(PageLimits.selectionOk(2, exactly2))
    assertEquals("Choose 1 page", PageLimits.chooseTitle(one))
    assertEquals("Choose up to 3 pages", PageLimits.chooseTitle(upTo3))
    assertEquals("Choose 2 pages", PageLimits.chooseTitle(exactly2))
  }

  @Test
  fun oldItemsAreMigrated() {
    val old = PresetItem(ItemKind.DOCUMENT, format = OutputFormat.PDF, pagesHint = "1-2")
    val m = old.withPageNumbers()
    assertEquals(1, m.minPages)
    assertEquals(2, m.maxPages)
    assertNull(m.pagesHint)
    assertEquals(1..2, m.pageRange)
    val vague = PresetItem(ItemKind.DOCUMENT, format = OutputFormat.PDF, pagesHint = "as needed").withPageNumbers()
    assertEquals(1, vague.maxPages)
    val photo = PresetItem(ItemKind.PHOTO, 300, 300, pagesHint = "2").withPageNumbers()
    assertNull(photo.maxPages)
    assertEquals(1..1, photo.pageRange)
  }
}
