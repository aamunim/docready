package com.docready.aamunim.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderLogicTest {
  private var n = 0
  private fun id() = "f${++n}"

  @Test
  fun namesAreSafe() {
    assertEquals("B.Ed 2026 batch", FolderLogic.sanitize("  B.Ed 2026   batch "))
    assertEquals("a b", FolderLogic.sanitize("a/b"))
    assertEquals("x y", FolderLogic.sanitize("x:*?\"<>|y"))
    assertEquals("New folder", FolderLogic.sanitize(" /// "))
    assertEquals("dots", FolderLogic.sanitize("..dots.."))
    assertEquals(60, FolderLogic.sanitize("a".repeat(100)).length)
    assertEquals(listOf("Students", "Rahim Uddin"), FolderLogic.splitPath("Students / Rahim Uddin/"))
  }

  @Test
  fun pathsAreMadeOnceAndFound() {
    val (f1, a) = FolderLogic.ensurePath(emptyList(), "B.Ed/Batch 1", 0, ::id)
    assertEquals(2, f1.size)
    assertEquals("B.Ed/Batch 1", FolderLogic.pathOf(f1, a))
    val (f2, b) = FolderLogic.ensurePath(f1, "b.ed/batch 1", 0, ::id)
    assertEquals(f1, f2)
    assertEquals(a, b)
    val (_, scans) = FolderLogic.ensurePath(f1, "", 0, ::id)
    assertTrue(scans != a)
  }

  @Test
  fun renameKeepsSiblingNamesApart() {
    var f = FolderLogic.create(emptyList(), null, "Forms", 0, "a")
    f = FolderLogic.create(f, null, "Forms", 0, "b")
    assertEquals("Forms (2)", f.first { it.id == "b" }.name)
    f = FolderLogic.rename(f, "b", "forms", 0)
    assertEquals("forms (2)", f.first { it.id == "b" }.name)
    f = FolderLogic.rename(f, "b", "Jobs/2026", 0)
    assertEquals("Jobs 2026", f.first { it.id == "b" }.name)
  }

  @Test
  fun movesNeverMakeCycles() {
    val (f1, child) = FolderLogic.ensurePath(emptyList(), "A/B/C", 0, ::id)
    val a = f1.first { it.name == "A" }.id
    val b = f1.first { it.name == "B" }.id
    // A into its own grandchild: refused
    assertNull(FolderLogic.move(f1, a, child, 0))
    assertNull(FolderLogic.move(f1, a, a, 0))
    // C to the top: fine
    val moved = FolderLogic.move(f1, child, null, 0)!!
    assertEquals("C", FolderLogic.pathOf(moved, child))
    assertFalse(FolderLogic.isWithin(moved, child, a))
    // B under C (now separate): fine
    val again = FolderLogic.move(moved, b, child, 0)!!
    assertEquals("C/B", FolderLogic.pathOf(again, b))
    // a folder that does not exist: refused
    assertNull(FolderLogic.move(f1, a, "nope", 0))
  }

  @Test
  fun deletingKeepsOrRemovesFiles() {
    val (f1, inner) = FolderLogic.ensurePath(emptyList(), "A/B", 0, ::id)
    val a = f1.first { it.name == "A" }.id
    val rec = { fid: String, name: String -> FileRecord(name, fid, name, "content://x/$name", "application/pdf", 10, 0) }
    val data = FolderData(f1, listOf(rec(a, "one"), rec(inner, "two")))
    val (kept, none) = FolderLogic.delete(data, a, keepFiles = true, now = 0, newId = ::id)
    assertTrue(none.isEmpty())
    assertEquals(listOf(FolderLogic.SCANS), kept.folders.map { it.name })
    assertEquals(2, kept.files.size)
    assertTrue(kept.files.all { it.folderId == kept.folders.single().id })
    val (gone, removed) = FolderLogic.delete(data, a, keepFiles = false, now = 0, newId = ::id)
    assertTrue(gone.folders.isEmpty())
    assertTrue(gone.files.isEmpty())
    assertEquals(setOf("one", "two"), removed.map { it.name }.toSet())
  }
}
