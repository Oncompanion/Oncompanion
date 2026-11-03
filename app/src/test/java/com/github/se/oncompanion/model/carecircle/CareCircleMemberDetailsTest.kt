// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails.Companion.MAX_NAME_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareCircleMemberDetailsTest {

  @Test
  fun isValid_withAFirstNameOnly() {
    assertTrue(CareCircleMemberDetails(firstName = "Julie").isValid())
  }

  @Test
  fun isValid_refusesAnEmptyOrBlankFirstName() {
    assertFalse(CareCircleMemberDetails(firstName = "").isValid())
    assertFalse(CareCircleMemberDetails(firstName = "   ").isValid())
  }

  @Test
  fun isValid_acceptsNamesOfTheMaximumLength() {
    val name = "a".repeat(MAX_NAME_LENGTH)
    assertTrue(CareCircleMemberDetails(firstName = name, familyName = name).isValid())
  }

  @Test
  fun isValid_refusesATooLongFirstName() {
    assertFalse(CareCircleMemberDetails(firstName = "a".repeat(MAX_NAME_LENGTH + 1)).isValid())
  }

  @Test
  fun isValid_refusesATooLongFamilyName() {
    val details =
        CareCircleMemberDetails(firstName = "Julie", familyName = "a".repeat(MAX_NAME_LENGTH + 1))
    assertFalse(details.isValid())
  }

  @Test
  fun isValid_acceptsABlankFamilyName() {
    assertTrue(CareCircleMemberDetails(firstName = "Julie", familyName = "").isValid())
  }

  @Test
  fun canAdd_isEmptyByDefault() {
    assertTrue(
        CareCircleMemberDetails("Julie", permissions = setOf(CarePermission.SYMPTOMS))
            .canAdd
            .isEmpty()
    )
  }

  @Test
  fun isValid_addingToASectionNeedsSeeingIt() {
    val sees = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS)
    assertTrue(
        CareCircleMemberDetails(
                "Julie",
                permissions = sees,
                canAdd = setOf(CarePermission.SYMPTOMS),
            )
            .isValid()
    )
    assertTrue(CareCircleMemberDetails("Julie", permissions = sees, canAdd = sees).isValid())
    assertFalse(
        CareCircleMemberDetails(
                "Julie",
                permissions = sees,
                canAdd = setOf(CarePermission.SYMPTOMS, CarePermission.EVENTS),
            )
            .isValid()
    )
  }

  @Test
  fun fullName_joinsFirstAndFamilyName() {
    assertEquals("Julie Martin", CareCircleMemberDetails("Julie", "Martin").fullName)
  }

  @Test
  fun fullName_withABlankOrMissingFamilyName_isTheFirstName() {
    assertEquals("Julie", CareCircleMemberDetails("Julie").fullName)
    assertEquals("Julie", CareCircleMemberDetails("Julie", " ").fullName)
  }

  @Test
  fun initial_isTheUppercaseFirstLetter() {
    assertEquals("J", CareCircleMemberDetails(" julie").initial)
    assertEquals("", CareCircleMemberDetails("  ").initial)
  }

  @Test
  fun hasFullAccess_onlyWithEveryPermission() {
    assertTrue(
        CareCircleMemberDetails("Julie", permissions = CarePermission.entries.toSet()).hasFullAccess
    )
    assertFalse(
        CareCircleMemberDetails("Julie", permissions = setOf(CarePermission.EVENTS)).hasFullAccess
    )
  }

  @Test
  fun memberDetails_copyTheMembersDecidedFields() {
    val member =
        CareCircleMember(
            uid = "u1",
            firstName = "Julie",
            familyName = "Martin",
            relationship = Relationship.DAUGHTER,
            permissions = setOf(CarePermission.PLANNING),
            email = "julie@example.com",
        )
    assertEquals(
        CareCircleMemberDetails(
            "Julie",
            "Martin",
            Relationship.DAUGHTER,
            setOf(CarePermission.PLANNING),
        ),
        member.details,
    )
  }

  @Test
  fun memberDetails_copyWhatTheyCanAddTo() {
    val member =
        CareCircleMember(
            uid = "u1",
            firstName = "Julie",
            permissions = setOf(CarePermission.SYMPTOMS),
            canAdd = setOf(CarePermission.SYMPTOMS),
        )
    assertEquals(setOf(CarePermission.SYMPTOMS), member.details.canAdd)
  }
}
