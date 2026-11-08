// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import android.util.Log
import com.github.se.oncompanion.model.carecircle.CarePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** What a screen can show of one section of someone's data, such as their symptoms. */
sealed interface CareSection<out T> {

  /**
   * Nothing to show yet: the access or the data isn't known.
   *
   * @property accessKnown whether only the data is loading. While the access isn't known, show none
   *   of the data, not even what was shown before; once it is, what is shown may stay until the
   *   data arrives (e.g. after a failed deletion).
   * @property canAdd whether adding may be offered already, once the access is known: it doesn't
   *   wait for the data
   */
  data class Loading(val accessKnown: Boolean = false, val canAdd: Boolean = false) :
      CareSection<Nothing>

  /**
   * The section's [data].
   *
   * @property canEdit whether edit and delete may be offered: only for the user's own data
   * @property canAdd whether adding may be offered (US-22)
   */
  data class Shown<T>(val data: T, val canEdit: Boolean, val canAdd: Boolean) : CareSection<T>

  /** The patient a caregiver follows doesn't share this section with them. */
  data object NotShared : CareSection<Nothing>

  /** Nothing to read: nobody is signed in, or the caregiver isn't in the patient's circle. */
  data object NoAccess : CareSection<Nothing>

  /**
   * The data couldn't be read. An access change still applies afterwards, e.g. "not shared" once
   * the server refused the read because the patient stopped sharing the section.
   *
   * @property canAdd whether adding may still be offered: for the user's own data only, since a
   *   caregiver's access may have been taken away
   */
  data class Failed(val canAdd: Boolean) : CareSection<Nothing>
}

/**
 * Follows one section of the data the signed-in user may see: their own, or what the patient they
 * follow shares with them (US-20). Every screen that shows a section of patient data reads it here,
 * so each only maps the result to its state and none can forget to reset a flag after an access
 * change (#323).
 */
class ObserveCareSection(
    private val resolveSection: ResolveSectionAccess = ResolveSectionAccess()
) {

  /**
   * Emits [section] of [patientUid]'s data (null for the user's own), read with [read] from the
   * owner's uid, and again whenever the access or the data change. A new access starts over from
   * [CareSection.Loading], so the previous person's data never shows. A read failure is
   * [CareSection.Failed] and the flow goes on; it only ends after a failure of the access itself.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  operator fun <T> invoke(
      section: CarePermission,
      patientUid: String?,
      read: (ownerUid: String) -> Flow<T>,
  ): Flow<CareSection<T>> = flow {
    // In a flow, so that even an auth that throws right away is a failure
    emitAll(resolveSection(section, patientUid))
  }
      .flatMapLatest { access -> sectionWith(access, section, read) }
      .catch { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Couldn't check the access to $section: ${e.javaClass.simpleName}")
        emit(CareSection.Failed(canAdd = patientUid == null))
      }

  /** What [access] lets the screen show of [section]: the data, or why there is none. */
  private fun <T> sectionWith(
      access: SectionAccess,
      section: CarePermission,
      read: (ownerUid: String) -> Flow<T>,
  ): Flow<CareSection<T>> =
      when (access) {
        SectionAccess.Loading -> flowOf(CareSection.Loading())
        SectionAccess.NoAccess -> flowOf(CareSection.NoAccess)
        SectionAccess.NotShared -> flowOf(CareSection.NotShared)
        is SectionAccess.Readable -> shown(access, section, read)
      }

  /** The data [access] lets [read] give, from [CareSection.Loading]; a failure is [Failed]. */
  private fun <T> shown(
      access: SectionAccess.Readable,
      section: CarePermission,
      read: (ownerUid: String) -> Flow<T>,
  ): Flow<CareSection<T>> =
  // In a flow, so that a read that throws right away is a failure too, not the end
  flow {
    emitAll(read(access.ownerUid))
  }
      .map<T, CareSection<T>> { CareSection.Shown(it, access.canEdit, access.canAdd) }
      .onStart { emit(CareSection.Loading(accessKnown = true, canAdd = access.canAdd)) }
      .catch { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Couldn't read $section: ${e.javaClass.simpleName}")
        emit(CareSection.Failed(canAdd = access.canEdit))
      }

  private companion object {
    const val TAG = "ObserveCareSection"
  }
}
