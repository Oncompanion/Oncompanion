// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.user

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {

  private val valid = UserProfile(uid = "uid-1", role = Role.PATIENT, firstName = "Alice")

  @Test
  fun constantsHaveSpecifiedValues() {
    assertEquals(50, UserProfile.MAX_NAME_LENGTH)
    assertEquals(100, UserProfile.MAX_CANCER_TYPE_LENGTH)
  }

  @Test
  fun optionalFieldsDefaultToNull() {
    assertNull(valid.familyName)
    assertNull(valid.cancerType)
    assertNull(valid.createdAt)
  }

  @Test
  fun roleHasExactlyPatientAndCaregiver() {
    assertEquals(listOf(Role.PATIENT, Role.CAREGIVER), Role.values().toList())
    assertEquals(Role.PATIENT, Role.valueOf("PATIENT"))
    assertEquals(Role.CAREGIVER, Role.valueOf("CAREGIVER"))
  }

  @Test
  fun minimalProfileIsValid() {
    assertTrue(valid.isValid())
    assertTrue(valid.copy(role = Role.CAREGIVER).isValid())
  }

  @Test
  fun fullProfileIsValid() {
    assertTrue(
        valid
            .copy(familyName = "Smith", cancerType = "Breast cancer", createdAt = Instant.now())
            .isValid()
    )
  }

  @Test
  fun blankUidIsInvalid() {
    assertFalse(valid.copy(uid = "").isValid())
    assertFalse(valid.copy(uid = "   ").isValid())
  }

  @Test
  fun blankFirstNameIsInvalid() {
    assertFalse(valid.copy(firstName = "").isValid())
    assertFalse(valid.copy(firstName = "  ").isValid())
  }

  @Test
  fun firstNameLengthBoundary() {
    assertTrue(valid.copy(firstName = "a".repeat(50)).isValid())
    assertFalse(valid.copy(firstName = "a".repeat(51)).isValid())
  }

  @Test
  fun familyNameLengthBoundary() {
    assertTrue(valid.copy(familyName = "b".repeat(50)).isValid())
    assertFalse(valid.copy(familyName = "b".repeat(51)).isValid())
  }

  @Test
  fun cancerTypeLengthBoundary() {
    assertTrue(valid.copy(cancerType = "c".repeat(100)).isValid())
    assertFalse(valid.copy(cancerType = "c".repeat(101)).isValid())
  }

  @Test
  fun nullOptionalsAreValid() {
    assertTrue(valid.copy(familyName = null, cancerType = null, createdAt = null).isValid())
  }

  /** Optional hospital matches the existing nullable optional-string rules and shared limit. */
  @Test
  fun hospitalIsOptionalAndHasABoundedLength() {
    assertNull(valid.hospital)
    assertEquals(100, UserProfile.MAX_HOSPITAL_LENGTH)
    listOf(null, "", "   ", "Manually entered hospital", "h".repeat(100)).forEach {
      assertTrue(valid.copy(hospital = it).isValid())
    }
    assertFalse(valid.copy(hospital = "h".repeat(101)).isValid())
  }

  /** The date of birth is optional and refused once it is after today, like in the rules. */
  @Test
  fun dateOfBirthIsOptionalAndNeverInTheFuture() {
    assertNull(valid.dateOfBirth)
    val today = LocalDate.of(2026, 11, 15)
    val cases =
        listOf(
            null to true,
            LocalDate.of(1970, 1, 1) to true,
            today.minusDays(1) to true,
            today to true,
            today.plusDays(1) to false,
            today.plusYears(1) to false,
        )
    for ((dateOfBirth, expected) in cases) {
      assertEquals("$dateOfBirth", expected, valid.copy(dateOfBirth = dateOfBirth).isValid(today))
    }
  }

  /**
   * Without an explicit day, isValid compares with the phone's today, so a phone ahead of Zurich
   * (Australia, Kiribati) can enter its own today and a phone behind it can't enter tomorrow.
   */
  @Test
  fun dateOfBirthIsComparedWithThePhonesToday() {
    val phoneZone = TimeZone.getDefault()
    try {
      for (zone in listOf("Pacific/Kiritimati", "Australia/Sydney", "America/Los_Angeles")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val phoneToday = LocalDate.now(ZoneId.of(zone))
        assertTrue(zone, valid.copy(dateOfBirth = phoneToday).isValid())
        assertFalse(zone, valid.copy(dateOfBirth = phoneToday.plusDays(1)).isValid())
      }
    } finally {
      TimeZone.setDefault(phoneZone)
    }
  }
}
