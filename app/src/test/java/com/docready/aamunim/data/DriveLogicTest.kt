package com.docready.aamunim.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveLogicTest {
  @Test
  fun localFoldersMapUnderDocReady() {
    assertEquals(listOf("DocReady", "Scans"), DriveLogic.folderSegments("Scans"))
    assertEquals(listOf("DocReady", "B.Ed", "Batch 1"), DriveLogic.folderSegments("B.Ed/Batch 1"))
    assertEquals(listOf("DocReady", "Students", "Rahim Uddin"), DriveLogic.studentSegments("Rahim Uddin"))
    // names made safe the same way as on the phone
    assertEquals(listOf("DocReady", "a b"), DriveLogic.folderSegments("a:b"))
    assertEquals(listOf("DocReady"), DriveLogic.folderSegments(""))
  }

  @Test
  fun queriesAreEscaped() {
    assertEquals(
      "name = 'Rahim\\'s' and mimeType = 'application/vnd.google-apps.folder' and 'p1' in parents and trashed = false",
      DriveLogic.folderQuery("Rahim's", "p1"),
    )
  }

  @Test
  fun uploadKinds() {
    assertTrue(DriveLogic.useMultipart(5L * 1024 * 1024))
    assertFalse(DriveLogic.useMultipart(5L * 1024 * 1024 + 1))
  }

  @Test
  fun queueStates() {
    assertEquals(DriveState.DONE, DriveLogic.afterUpload(ok = true, retryLater = false))
    assertEquals(DriveState.QUEUED, DriveLogic.afterUpload(ok = false, retryLater = true))
    assertEquals(DriveState.FAILED, DriveLogic.afterUpload(ok = false, retryLater = false))
    assertEquals(DriveState.QUEUED, DriveLogic.retry(DriveState.FAILED))
    assertEquals(DriveState.DONE, DriveLogic.retry(DriveState.DONE))
    assertEquals(DriveState.UPLOADING, DriveLogic.queue(DriveState.UPLOADING))
    assertEquals(DriveState.QUEUED, DriveLogic.queue(DriveState.DONE))
    assertEquals(DriveState.QUEUED, DriveLogic.recover(DriveState.UPLOADING))
    val states = listOf(DriveState.QUEUED, DriveState.UPLOADING, DriveState.FAILED, DriveState.DONE, DriveState.NONE)
    assertEquals(2, DriveLogic.waiting(states))
    assertEquals(1, DriveLogic.failed(states))
    assertTrue(DriveLogic.transient(503))
    assertTrue(DriveLogic.transient(429))
    assertFalse(DriveLogic.transient(403))
  }
}
