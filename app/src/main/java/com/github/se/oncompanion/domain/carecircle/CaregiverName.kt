// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import kotlinx.coroutines.flow.first

/**
 * The first name a caregiver signs what they add for the patient with (a symptom in US-22, an
 * appointment in US-41), shown to the patient as "Added by Sam". Every form a caregiver adds from
 * signs the same way.
 */
class CaregiverName(
    private val profile: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
) {

  /**
   * [uid]'s first name from their profile (in the local cache, also offline), or else from their
   * Google account. Never blank, and at most [MAX_LENGTH] characters, as the rules require.
   */
  suspend operator fun invoke(uid: String): String =
      (runCatching { profile.observeProfile(uid).first()?.firstName }
              .getOrNull()
              ?.takeIf { it.isNotBlank() }
              ?: runCatching { auth.currentUser?.firstNameGuess() }.getOrNull()
              ?: DEFAULT)
          .take(MAX_LENGTH)

  companion object {
    /** Longest name stored with an entry; keep in sync with `isValidAttribution` in the rules. */
    const val MAX_LENGTH = 50

    /**
     * Stored as the name of a caregiver with neither a profile name nor a Google name, which
     * onboarding prevents: the rules need a name, and the uid still says who it was.
     */
    const val DEFAULT = "Caregiver"
  }
}
