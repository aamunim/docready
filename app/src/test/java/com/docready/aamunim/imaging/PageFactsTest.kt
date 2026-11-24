package com.docready.aamunim.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The suggestions read from a page's text: offered to the user, so a wrong one must be rare */
class PageFactsTest {
  private fun name(text: String): String? = PageFacts.find(text).firstOrNull { it.kind == FactKind.NAME }?.value

  private fun fact(text: String, kind: FactKind): String? = PageFacts.find(text).firstOrNull { it.kind == kind }?.value

  @Test
  fun studentsNameOnACertificate() {
    val t = "SECONDARY SCHOOL CERTIFICATE\nName of Student: RAHIM UDDIN\nFather's Name: KARIM UDDIN\nRoll No: 123456 Registration No. 1510987654\nDate of Birth: 05/03/2004"
    assertEquals("Rahim Uddin", name(t))
    assertEquals("123456", fact(t, FactKind.ROLL))
    assertEquals("1510987654", fact(t, FactKind.REG))
    assertEquals("05/03/2004", fact(t, FactKind.DOB))
  }

  @Test
  fun nameOfExaminationIsNotTheName() {
    assertEquals("Fatema Begum", name("Name of Examination: Secondary School Certificate\nName of the Candidate: Fatema Begum"))
    assertEquals("Abdul Karim", name("Name of Examination: SSC\nName: Abdul Karim\nRoll: 112233"))
    assertNull(name("Name of Examination: HSC"))
  }

  @Test
  fun namesOfOtherThingsAreNotOffered() {
    assertNull(name("Name of Post: Assistant Teacher\nName of Bank: Sonali Bank"))
    assertNull(name("Name of Institution: Dhaka College\nName of Subject: Physics"))
    assertNull(name("Father's Name: Karim Uddin\nMother's Name: Rokeya Begum"))
  }

  @Test
  fun theApplicantsOwnNameIsPreferred() {
    assertEquals("Md. Abdul Karim", name("Name: Dhaka Board\nApplicant's Name: Md. Abdul Karim"))
    assertEquals("Rahim Uddin", name("Name of Student: RAHIM UDDIN Father's Name: KARIM UDDIN"))
  }

  @Test
  fun labelsOnOneLine() {
    val t = "Name : Fatema Begum Roll : 445566\nDOB 12 March 2005\nReg No-2019 123 456"
    assertEquals("Fatema Begum", name(t))
    assertEquals("445566", fact(t, FactKind.ROLL))
    assertEquals("2019123456", fact(t, FactKind.REG))
    assertEquals("12 March 2005", fact(t, FactKind.DOB))
  }
}
