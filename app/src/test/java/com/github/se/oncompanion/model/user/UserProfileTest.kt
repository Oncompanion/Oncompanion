package com.github.se.oncompanion.model.user

import java.time.Instant
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
}
