// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CarePermission

/**
 * Whose data the signed-in user may see, and which sections of it, as decided by
 * [ResolveCareCircleAccess]. Screens that show patient data read the uid and sections from here, so
 * caregiver mode is not a separate code path.
 */
sealed interface CareAccess {

  /**
   * The user may not see this data. Either nobody is signed in, or they are a caregiver who isn't
   * in the patient's care circle (the patient removed them, never added them, or their member
   * document is malformed: stored without a first name, see
   * [CareCircleRepository.observeMembership]), or their role doesn't allow it: a caregiver has no
   * data of their own, and a patient only sees their own. A missing member document only counts
   * once the server confirmed it: until then, the access is [Unconfirmed]. Show none of the data.
   */
  data object Denied : CareAccess

  /**
   * Not confirmed yet: this device has no record of the user's profile or of the caregiver's
   * access, and the server hasn't answered. Show none of the data; it becomes [Granted] or [Denied]
   * once the server confirms.
   */
  data object Unconfirmed : CareAccess

  /**
   * The signed-in user may see [ownerUid]'s data, limited to [sections].
   *
   * @property ownerUid the uid whose data the screen shows
   * @property sections what may be shown: every section for the patient's own data, only the shared
   *   ones for a caregiver
   * @property isOwn true for the patient's own data, false when a caregiver looks at a patient's
   * @property addable the sections a caregiver may also add to (`canAdd`, US-22), among [sections]
   */
  data class Granted(
      val ownerUid: String,
      val sections: Set<CarePermission>,
      val isOwn: Boolean,
      val addable: Set<CarePermission> = emptySet(),
  ) : CareAccess {

    /** Whether [section] of [ownerUid]'s data may be shown. */
    fun canSee(section: CarePermission): Boolean = section in sections

    /**
     * Whether edit and delete actions may be offered. Only the patient edits their own data; a
     * member may at most add to the sections in [addable].
     */
    val canEdit: Boolean
      get() = isOwn
  }
}
