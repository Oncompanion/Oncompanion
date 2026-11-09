// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle.ExpiredInvite
import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle.Member
import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle.PendingInvite
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** [emailAlreadyInCircle]: who in the circle already has the email being invited (#257). */
class EmailAlreadyInCircleTest {
  private val now = Instant.parse("2026-11-04T10:00:00Z")
  private val expiredAt = now.minus(CareCircleInvite.VALIDITY)

  private fun member(firstName: String, email: String?) =
      CareCircleMember(uid = firstName.lowercase(), firstName = firstName, email = email)

  private fun invite(firstName: String, email: String?, createdAt: Instant? = now) =
      CareCircleInvite(
          inviteCode = "CODE${firstName.uppercase()}",
          ownerUid = "owner",
          ownerFirstName = "Anne",
          details = CareCircleMemberDetails(firstName = firstName),
          email = email,
          createdAt = createdAt,
      )

  private fun check(
      email: String,
      members: List<CareCircleMember> = emptyList(),
      invites: List<CareCircleInvite> = emptyList(),
  ) = emailAlreadyInCircle(email, members, invites, now)

  @Test
  fun caseAndSpacesDontCount_onEitherSide() {
    val stored = listOf("paul@example.com", " Paul@EXAMPLE.com ")
    val typed =
        listOf("paul@example.com", " paul@example.com  ", "PAUL@Example.COM", "\tPaul@example.com")
    for (storedEmail in stored) {
      for (typedEmail in typed) {
        val case = "stored '$storedEmail', typed '$typedEmail'"
        assertEquals(case, Member("Paul"), check(typedEmail, listOf(member("Paul", storedEmail))))
        assertEquals(
            case,
            PendingInvite("Paul", "CODEPAUL"),
            check(typedEmail, invites = listOf(invite("Paul", storedEmail))),
        )
      }
    }
    // Another email is someone else
    assertEquals(null, check("paul.other@example.com", listOf(member("Paul", "paul@example.com"))))
  }

  @Test
  fun isSameEmail_ignoresCaseAndSpaces_andABlankEmailMatchesNothing() {
    val cases =
        listOf(
            Triple("anne@example.com", " ANNE@Example.com ", true),
            Triple("anne@example.com", "marc@example.com", false),
            Triple("anne@example.com", null, false),
            Triple(null, null, false),
            Triple("", null, false),
            Triple("  ", "", false),
        )
    for ((first, second, expected) in cases) {
      assertEquals("'$first', '$second'", expected, isSameEmail(first, second))
    }
  }

  @Test
  fun aBlankEmail_orPeopleWithoutOne_neverMatch() {
    val members = listOf(member("Julie", null), member("Marc", " "))
    val invites = listOf(invite("Julie", null), invite("Lea", "  "))
    for (typed in listOf("", "   ")) {
      assertEquals("typed '$typed'", null, check(typed, members, invites))
    }
    // A shared first name without an email doesn't make two people the same
    assertEquals(null, check("julie@example.com", members, invites))
  }

  @Test
  fun anInvitation_expiresAtExactlyItsValidity() {
    val cases =
        listOf(
            now to PendingInvite("Paul", "CODEPAUL"),
            expiredAt.plusMillis(1) to PendingInvite("Paul", "CODEPAUL"),
            expiredAt to ExpiredInvite("Paul", "CODEPAUL"),
            expiredAt.minus(Duration.ofDays(30)) to ExpiredInvite("Paul", "CODEPAUL"),
            // Created offline: not on the server yet, so its validity hasn't started
            null to PendingInvite("Paul", "CODEPAUL"),
        )
    for ((createdAt, expected) in cases) {
      val invites = listOf(invite("Paul", "paul@example.com", createdAt))
      assertEquals("created at $createdAt", expected, check("paul@example.com", invites = invites))
    }
  }

  @Test
  fun aMember_thenAWorkingInvitation_thenAnExpiredOne() {
    val email = "paul@example.com"
    val memberPaulo = member("Paulo", email)
    val working = invite("Pablo", email)
    val expired = invite("Paul", email, expiredAt)
    val cases =
        listOf(
            Triple(listOf(memberPaulo), listOf(expired, working), Member("Paulo")),
            Triple(emptyList(), listOf(expired, working), PendingInvite("Pablo", "CODEPABLO")),
            Triple(emptyList(), listOf(expired), ExpiredInvite("Paul", "CODEPAUL")),
        )
    for ((members, invites, expected) in cases) {
      assertEquals(expected, check(email, members, invites))
    }
  }

  @Test
  fun amongSeveralInvitationsOfTheSameKind_theNewest() {
    val email = "paul@example.com"
    val cases =
        listOf(
            // Listed by name, not by date: the newest isn't the first
            listOf(
                invite("Ann", email, now.minus(Duration.ofDays(2))),
                invite("Bea", email, now),
                invite("Cid", email, now.minus(Duration.ofDays(1))),
            ) to PendingInvite("Bea", "CODEBEA"),
            // One the server hasn't seen yet was created last
            listOf(invite("Ann", email, now), invite("Bea", email, null)) to
                PendingInvite("Bea", "CODEBEA"),
            listOf(
                invite("Ann", email, expiredAt.minus(Duration.ofDays(1))),
                invite("Bea", email, expiredAt),
            ) to ExpiredInvite("Bea", "CODEBEA"),
        )
    for ((invites, expected) in cases) {
      assertEquals(expected, check(email, invites = invites))
    }
  }
}
