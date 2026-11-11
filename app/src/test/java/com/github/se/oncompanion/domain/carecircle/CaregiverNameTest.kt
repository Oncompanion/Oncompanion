// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the name a caregiver signs what they add with: always one the rules accept, whatever is
 * known about them. sam is the signed-in caregiver.
 */
class CaregiverNameTest {

  /** One case: the profile's first name, the Google name, whether the profile fails, the result. */
  private data class Case(
      val profileName: String?,
      val googleName: String?,
      val profileFails: Boolean,
      val expected: String,
  )

  @Test
  fun signsWithTheProfileName_thenTheGoogleName_thenADefault_neverTooLong() = runTest {
    val cases =
        listOf(
            Case("Sam", "Samuel Martin", profileFails = false, expected = "Sam"),
            Case("  ", "Samuel Martin", profileFails = false, expected = "Samuel"),
            Case("Sam", "Samuel Martin", profileFails = true, expected = "Samuel"),
            Case(null, null, profileFails = false, expected = CaregiverName.DEFAULT),
            Case("a".repeat(60), null, profileFails = false, expected = "a".repeat(50)),
            Case(null, "b".repeat(60), profileFails = false, expected = "b".repeat(50)),
        )
    for (case in cases) {
      val auth = FakeAuthRepository { AuthUser(uid = "sam", displayName = case.googleName) }
      auth.signInWithGoogle("token")
      val profiles =
          FakeUserProfileRepository().apply {
            case.profileName?.let { seed(UserProfile("sam", Role.CAREGIVER, firstName = it)) }
            if (case.profileFails) getProfileError = IllegalStateException("Offline, no cache")
          }

      assertEquals("$case", case.expected, CaregiverName(profiles, auth)("sam"))
    }
  }
}
