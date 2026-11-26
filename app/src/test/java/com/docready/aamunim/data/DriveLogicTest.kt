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

  @Test
  fun errorsClassified() {
    // gone from the phone: permanent, never retried by itself
    assertEquals(DriveLogic.Failure.MISSING, DriveLogic.classify(java.io.FileNotFoundException(DriveLogic.MISSING_MESSAGE)))
    assertEquals(DriveLogic.Failure.MISSING, DriveLogic.classify(SecurityException("no permission")))
    assertEquals(DriveState.FAILED, DriveLogic.stateAfter(DriveLogic.Failure.MISSING))
    // network trouble: tried again
    assertEquals(DriveLogic.Failure.RETRY, DriveLogic.classify(java.net.SocketTimeoutException()))
    assertEquals(DriveLogic.Failure.RETRY, DriveLogic.classify(java.net.SocketException("reset")))
    assertEquals(DriveLogic.Failure.RETRY, DriveLogic.classify(java.net.UnknownHostException("www.googleapis.com")))
    assertEquals(DriveState.QUEUED, DriveLogic.stateAfter(DriveLogic.Failure.RETRY))
    // Drive's answers: only busy ones are tried again
    assertEquals(DriveLogic.Failure.RETRY, DriveLogic.classify(java.io.IOException("x"), 503))
    assertEquals(DriveLogic.Failure.RETRY, DriveLogic.classify(java.io.IOException("x"), 429))
    assertEquals(DriveLogic.Failure.RETRY, DriveLogic.classify(java.io.IOException("x"), 408))
    assertEquals(DriveLogic.Failure.FAILED, DriveLogic.classify(java.io.IOException("x"), 403))
    assertEquals(DriveLogic.Failure.FAILED, DriveLogic.classify(java.io.IOException("x"), 401))
    assertEquals(DriveLogic.Failure.FAILED, DriveLogic.classify(java.io.IOException("x"), 400))
    // anything else: failed
    assertEquals(DriveLogic.Failure.FAILED, DriveLogic.classify(java.io.IOException("Google Drive gave no upload address.")))
    assertEquals(DriveLogic.Failure.FAILED, DriveLogic.classify(IllegalStateException("x")))
  }

  @Test
  fun resumeOffsetParsed() {
    assertEquals(0L, DriveLogic.resumeOffset(null))
    assertEquals(0L, DriveLogic.resumeOffset(""))
    assertEquals(1048576L, DriveLogic.resumeOffset("bytes=0-1048575"))
    assertEquals(1L, DriveLogic.resumeOffset("bytes=0-0"))
    assertEquals(5000L, DriveLogic.resumeOffset("bytes = 0 - 4999"))
    // (not from the start: unusable, start over from 0)
    assertEquals(0L, DriveLogic.resumeOffset("bytes=100-200"))
    assertEquals(0L, DriveLogic.resumeOffset("garbage"))
  }

  @Test
  fun sessionStates() {
    assertEquals(DriveLogic.SessionState.Complete, DriveLogic.sessionState(200, null, 10))
    assertEquals(DriveLogic.SessionState.Complete, DriveLogic.sessionState(201, null, 10))
    assertEquals(DriveLogic.SessionState.Resume(0), DriveLogic.sessionState(308, null, 10))
    assertEquals(DriveLogic.SessionState.Resume(6), DriveLogic.sessionState(308, "bytes=0-5", 10))
    assertEquals(DriveLogic.SessionState.Expired, DriveLogic.sessionState(308, "bytes=0-9", 10))
    assertEquals(DriveLogic.SessionState.Expired, DriveLogic.sessionState(404, null, 10))
    assertEquals(DriveLogic.SessionState.Expired, DriveLogic.sessionState(410, null, 10))
    assertEquals(DriveLogic.SessionState.Error(503), DriveLogic.sessionState(503, null, 10))
  }

  @Test
  fun docreadyIdQueryAndStudentIds() {
    assertEquals(
      "appProperties has { key='docready_id' and value='abc' } and trashed = false",
      DriveLogic.idQuery("abc"),
    )
    assertEquals("appProperties has { key='docready_id' and value='a\\'b' } and trashed = false", DriveLogic.idQuery("a'b"))
    val a = DriveLogic.studentFileId("s1", "/data/files/students/s1/a.pdf")
    assertEquals(a, DriveLogic.studentFileId("s1", "/data/files/students/s1/a.pdf"))
    assertFalse(a == DriveLogic.studentFileId("s1", "/data/files/students/s1/b.pdf"))
    assertTrue(a.length <= 80)
  }
}
