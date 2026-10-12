// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import com.github.se.oncompanion.model.carecircle.CareCircleInvite.Companion.INVITE_CODE_ALPHABET
import com.github.se.oncompanion.model.carecircle.CareCircleInvite.Companion.INVITE_CODE_LENGTH
import com.github.se.oncompanion.model.carecircle.CareCircleInvite.Companion.MAX_EMAIL_LENGTH
import com.github.se.oncompanion.model.carecircle.CareCircleInvite.Companion.VALIDITY
import java.time.Instant
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareCircleInviteTest {

  private val createdAt = Instant.parse("2026-10-01T10:00:00Z")

  private fun invite(inviteCode: String = "ABCDEFGHJK", firstName: String = "Julie") =
      CareCircleInvite(
          inviteCode = inviteCode,
          ownerUid = "anne",
          ownerFirstName = "Anne",
          details = CareCircleMemberDetails(firstName),
          createdAt = createdAt,
      )

  @Test
  fun generateInviteCode_isWellFormed() {
    repeat(100) {
      val inviteCode = CareCircleInvite.generateInviteCode()
      assertEquals(INVITE_CODE_LENGTH, inviteCode.length)
      assertTrue(CareCircleInvite.isWellFormedInviteCode(inviteCode))
    }
  }

  @Test
  fun generateInviteCode_usesTheGivenRandom() {
    assertEquals(
        CareCircleInvite.generateInviteCode(Random(42)),
        CareCircleInvite.generateInviteCode(Random(42)),
    )
    assertNotEquals(
        CareCircleInvite.generateInviteCode(Random(1)),
        CareCircleInvite.generateInviteCode(Random(2)),
    )
  }

  @Test
  fun alphabet_hasNoLookAlikeCharacters() {
    "01OIL".forEach { assertFalse("$it", it in INVITE_CODE_ALPHABET) }
  }

  @Test
  fun normalizeInviteCode_upperCasesAndRemovesSpacesAndDashes() {
    assertEquals("ABCDEFGHJK", CareCircleInvite.normalizeInviteCode(" abcde-fghjk "))
    assertEquals("ABCDEFGHJK", CareCircleInvite.normalizeInviteCode("ABCDE FGHJK"))
  }

  @Test
  fun isWellFormedInviteCode_refusesWrongLengthOrCharacters() {
    assertTrue(CareCircleInvite.isWellFormedInviteCode("ABCDEFGHJK"))
    assertFalse(CareCircleInvite.isWellFormedInviteCode("ABCDEFGHJ"))
    assertFalse(CareCircleInvite.isWellFormedInviteCode("ABCDEFGHJKM"))
    assertFalse(CareCircleInvite.isWellFormedInviteCode("ABCDEFGHJ0"))
    assertFalse(CareCircleInvite.isWellFormedInviteCode("abcdefghjk"))
    assertFalse(CareCircleInvite.isWellFormedInviteCode("ABCDE-FGHJ"))
  }

  @Test
  fun displayInviteCode_splitsTheCodeInTwo() {
    assertEquals("ABCDE-FGHJK", invite().displayInviteCode)
  }

  @Test
  fun isExpired_onlyOnceTheValidityHasPassed() {
    assertFalse(invite().isExpired(createdAt))
    assertFalse(invite().isExpired(createdAt + VALIDITY - java.time.Duration.ofSeconds(1)))
    assertTrue(invite().isExpired(createdAt + VALIDITY))
  }

  @Test
  fun isExpired_neverBeforeTheServerHasTheInvite() {
    assertFalse(invite().copy(createdAt = null).isExpired(Instant.MAX))
  }

  @Test
  fun isValidEmail_acceptsAPlainAddress() {
    assertTrue(CareCircleInvite.isValidEmail("julie@example.com"))
  }

  @Test
  fun isValidEmail_refusesMalformedAddresses() {
    listOf("", "julie", "julie@", "julie@example", "@example.com", "ju lie@example.com").forEach {
      assertFalse(it, CareCircleInvite.isValidEmail(it))
    }
  }

  @Test
  fun isValidEmail_refusesTooLongAddresses() {
    val domain = "@example.com"
    val longest = "a".repeat(MAX_EMAIL_LENGTH - domain.length) + domain
    assertTrue(CareCircleInvite.isValidEmail(longest))
    assertFalse(CareCircleInvite.isValidEmail("a$longest"))
  }

  @Test
  fun byName_sortsByFullNameIgnoringCase_thenByInviteCode() {
    val invites =
        listOf(
            invite("CCCCCCCCCC", "julie"),
            invite("BBBBBBBBBB", "Marc"),
            invite("AAAAAAAAAA", "Julie"),
        )
    assertEquals(
        listOf("AAAAAAAAAA", "CCCCCCCCCC", "BBBBBBBBBB"),
        invites.sortedWith(CareCircleInvite.BY_NAME).map { it.inviteCode },
    )
  }

  @Test
  fun defaults_areUnusedAndSynced() {
    val invite = invite()
    assertEquals(null, invite.redeemerUid)
    assertEquals(null, invite.email)
    assertTrue(invite.isSynced)
  }
}
