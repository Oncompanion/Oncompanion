package com.github.se.oncompanion.model.carecircle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareCircleMemberTest {

  @Test
  fun relationshipFromName_readsEveryStoredName() {
    Relationship.entries.forEach { assertEquals(it, Relationship.fromName(it.name)) }
  }

  @Test
  fun relationshipFromName_unknownOrMissing_isOther() {
    assertEquals(Relationship.OTHER, Relationship.fromName("COUSIN"))
    assertEquals(Relationship.OTHER, Relationship.fromName("son"))
    assertEquals(Relationship.OTHER, Relationship.fromName(null))
    assertEquals(Relationship.OTHER, Relationship.fromName(""))
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
  fun permissionsFromNames_acceptsASet_andKeepsDisplayOrder() {
    val reversed = CarePermission.entries.reversed().map { it.name }.toSet()
    assertEquals(CarePermission.entries, CarePermission.fromNames(reversed).toList())
  }

  @Test
  fun fullName_joinsFirstAndFamilyName() {
    assertEquals(
        "Sophie Dubois",
        CareCircleMember(uid = "1", firstName = "Sophie", familyName = "Dubois").fullName,
    )
  }

  @Test
  fun fullName_withoutFamilyName_isTheFirstName() {
    assertEquals("Sophie", CareCircleMember(uid = "1", firstName = "Sophie").fullName)
    assertEquals(
        "Sophie",
        CareCircleMember(uid = "1", firstName = "Sophie", familyName = " ").fullName,
    )
    assertEquals(
        "Sophie",
        CareCircleMember(uid = "1", firstName = "Sophie", familyName = "").fullName,
    )
  }

  @Test
  fun initial_isTheUppercaseFirstLetter() {
    assertEquals("S", CareCircleMember(uid = "1", firstName = " sophie").initial)
  }

  @Test
  fun initial_keepsAccents() {
    assertEquals("É", CareCircleMember(uid = "1", firstName = "élise").initial)
  }

  @Test
  fun initial_ofAnEmptyOrBlankName_isEmpty() {
    assertEquals("", CareCircleMember(uid = "1", firstName = "").initial)
    assertEquals("", CareCircleMember(uid = "1", firstName = "   ").initial)
  }

  @Test
  fun hasFullAccess_onlyWithEveryPermission() {
    val all = CareCircleMember("1", "Sophie", permissions = CarePermission.entries.toSet())
    assertTrue(all.hasFullAccess)
    assertFalse(all.copy(permissions = all.permissions - CarePermission.EVENTS).hasFullAccess)
    assertFalse(all.copy(permissions = setOf(CarePermission.PLANNING)).hasFullAccess)
    assertFalse(all.copy(permissions = emptySet()).hasFullAccess)
  }

  @Test
  fun byName_sortsByFullNameIgnoringCase_thenByUid() {
    val members =
        listOf(
            CareCircleMember("c", "sophie", "Dubois"),
            CareCircleMember("b", "Marc"),
            CareCircleMember("a", "Sophie", "dubois"),
            CareCircleMember("d", "laura"),
        )
    assertEquals(
        listOf("d", "b", "a", "c"),
        members.sortedWith(CareCircleMember.BY_NAME).map { it.uid },
    )
  }

  @Test
  fun defaults_areOtherRelationshipAndNoAccess() {
    val member = CareCircleMember(uid = "1", firstName = "Sophie")
    assertEquals(Relationship.OTHER, member.relationship)
    assertTrue(member.permissions.isEmpty())
  }

  @Test
  fun defaults_haveNoEmailNorAddedDate() {
    val member = CareCircleMember(uid = "1", firstName = "Sophie")
    assertNull(member.email)
    assertNull(member.addedAt)
  }
}
