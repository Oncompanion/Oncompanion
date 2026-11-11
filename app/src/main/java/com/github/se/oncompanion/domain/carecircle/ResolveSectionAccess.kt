// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Whose data of one section a screen may read, and whether it may offer to change it. */
sealed interface SectionAccess {

  /** Not known yet: show none of the data. */
  data object Loading : SectionAccess

  /**
   * [ownerUid]'s data of the section may be read.
   *
   * @property canEdit whether edit and delete may be offered: only for the user's own data
   * @property canAdd whether adding may be offered: for the user's own data, or when the patient
   *   lets the caregiver add to this section (US-22)
   */
  data class Readable(
      val ownerUid: String,
      val canEdit: Boolean,
      val canAdd: Boolean = canEdit,
  ) : SectionAccess

  /** The patient a caregiver follows doesn't share this section with them. */
  data object NotShared : SectionAccess

  /** Nothing to read: nobody is signed in, or the caregiver isn't in the patient's circle. */
  data object NoAccess : SectionAccess
}

/**
 * Decides whose data of a [CarePermission] section a screen reads: the user's own, or that of the
 * patient they follow when the patient shares the section (US-20). Every screen that shows a
 * section asks it, so caregiver mode is the same code path everywhere.
 *
 * The user's own data needs no role, only to be signed in: anyone sees their own data, also offline
 * before their profile is on the device. A followed patient's data goes through [resolveAccess].
 */
class ResolveSectionAccess(
    private val resolveAccess: ResolveCareCircleAccess = ResolveCareCircleAccess(),
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
) {

  /**
   * Emits the access to [section] of [patientUid]'s data (null for the user's own) now and every
   * time it changes: sign-in or out, the patient sharing or hiding the section, or removing the
   * caregiver. Fails like [resolveAccess].
   */
  operator fun invoke(section: CarePermission, patientUid: String?): Flow<SectionAccess> =
      withSharedSections(section, patientUid).map { it.access }.distinctUntilChanged()

  /**
   * Like [invoke], with every section of the same data the user may read, from the same check: a
   * screen that shows several sections (Planning shows medications and events) reads only those.
   */
  fun withSharedSections(
      section: CarePermission,
      patientUid: String?,
  ): Flow<SharedSectionAccess> =
      if (patientUid == null) {
        auth
            .observeCurrentUser()
            .map { it?.uid }
            .distinctUntilChanged()
            .map { uid ->
              uid?.let {
                SharedSectionAccess(
                    SectionAccess.Readable(it, canEdit = true),
                    CarePermission.entries.toSet(),
                )
              } ?: SharedSectionAccess(SectionAccess.NoAccess)
            }
      } else {
        resolveAccess(patientUid).map { sharedAccess(it, section) }.distinctUntilChanged()
      }

  private fun sharedAccess(access: CareAccess, section: CarePermission): SharedSectionAccess =
      when (access) {
        CareAccess.Unconfirmed -> SharedSectionAccess(SectionAccess.Loading)
        CareAccess.Denied -> SharedSectionAccess(SectionAccess.NoAccess)
        is CareAccess.Granted ->
            SharedSectionAccess(
                if (access.canSee(section))
                    SectionAccess.Readable(
                        access.ownerUid,
                        access.canEdit,
                        canAdd = access.canEdit || section in access.addable,
                    )
                else SectionAccess.NotShared,
                access.sections,
            )
      }
}

/**
 * The [access] to one section, and the [shared] sections of the same data that may be read: every
 * section of the user's own data, the ones the patient shares for a caregiver, none without access.
 */
data class SharedSectionAccess(
    val access: SectionAccess,
    val shared: Set<CarePermission> = emptySet(),
)
