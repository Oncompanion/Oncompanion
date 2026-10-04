package com.github.se.oncompanion.model.closecircle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseCircleMemberTest {

  @Test
  fun relationshipFromName_readsEveryStoredName() {
    Relationship.entries.forEach { assertEquals(it, Relationship.fromName(it.name)) }
  }

  @Test
  fun relationshipFromName_unknownOrMissing_isOther() {
    assertEquals(Relationship.OTHER, Relationship.fromName("COUSIN"))
    assertEquals(Relationship.OTHER, Relationship.fromName("son"))
    assertEquals(Relationship.OTHER, Relationship.fromName(null))
  }

  @Test
  fun permissionsFromNames_ignoresUnknownNames_andKeepsDisplayOrder() {
    assertEquals(
        listOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
        CarePermission.fromNames(listOf("SYMPTOMS", "EDIT", "PLANNING", "PLANNING")).toList(),
    )
    assertEquals(emptySet<CarePermission>(), CarePermission.fromNames(emptyList()))
  }

  @Test
  fun fullName_joinsFirstAndFamilyName() {
    assertEquals(
        "Sophie Dubois",
        CloseCircleMember(uid = "1", firstName = "Sophie", familyName = "Dubois").fullName,
    )
  }

  @Test
  fun fullName_withoutFamilyName_isTheFirstName() {
    assertEquals("Sophie", CloseCircleMember(uid = "1", firstName = "Sophie").fullName)
    assertEquals(
        "Sophie",
        CloseCircleMember(uid = "1", firstName = "Sophie", familyName = " ").fullName,
    )
  }

  @Test
  fun initial_isTheUppercaseFirstLetter() {
    assertEquals("S", CloseCircleMember(uid = "1", firstName = " sophie").initial)
  }

  @Test
  fun hasFullAccess_onlyWithEveryPermission() {
    val all = CloseCircleMember("1", "Sophie", permissions = CarePermission.entries.toSet())
    assertTrue(all.hasFullAccess)
    assertFalse(all.copy(permissions = all.permissions - CarePermission.EVENTS).hasFullAccess)
    assertFalse(all.copy(permissions = emptySet()).hasFullAccess)
  }

  @Test
  fun defaults_areOtherRelationshipAndNoAccess() {
    val member = CloseCircleMember(uid = "1", firstName = "Sophie")
    assertEquals(Relationship.OTHER, member.relationship)
    assertTrue(member.permissions.isEmpty())
  }
}
