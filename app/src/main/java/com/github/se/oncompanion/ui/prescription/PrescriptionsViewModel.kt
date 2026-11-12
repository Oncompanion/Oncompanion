// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.observeMemberNames
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.medication.SeenPrescriptions
import com.github.se.oncompanion.model.medication.SeenPrescriptionsProvider
import com.github.se.oncompanion.model.medication.visible
import com.github.se.oncompanion.ui.common.currentDay
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the prescriptions list shows.
 *
 * @property today the current date, which decides what counts as "This month"
 * @property prescriptions the saved prescriptions, most recently prescribed first
 * @property isLoading true until the prescriptions arrive
 * @property hasError true if they couldn't be loaded (or nobody is signed in)
 * @property canAdd whether a prescription can be added: only to the user's own, once it is known to
 *   be theirs
 * @property isNotShared in caregiver mode, the patient doesn't share their prescriptions
 * @property caregiverUid in caregiver mode, the signed-in caregiver: only the prescriptions they
 *   added are grouped, with whether the patient accepted or rejected them. Null for the patient,
 *   who sees every caregiver's grouped while they wait.
 * @property seen in caregiver mode, the accepted prescriptions whose acceptance they closed
 */
data class PrescriptionsUiState(
    val today: LocalDate,
    val prescriptions: List<Prescription> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val canAdd: Boolean = false,
    val isNotShared: Boolean = false,
    val caregiverUid: String? = null,
    val seen: Set<String> = emptySet(),
) {
  /**
   * The prescriptions a caregiver added that show above the others, in the order of [prescriptions]
   * (US-43): for the patient, every caregiver's that waits for them (Figma: "Added by a
   * caregiver"); for a caregiver, their own, waiting, rejected, or accepted until they close it
   * (Figma: "Added by you").
   */
  val waiting: List<Prescription>
    get() =
        if (caregiverUid == null) prescriptions.filter { !it.isConfirmed }
        else
            prescriptions.filter {
              it.addedBy == caregiverUid && !(it.isConfirmed && it.id in seen)
            }

  /**
   * The other prescriptions prescribed on or after the first day of the current month, in the order
   * of [prescriptions] (Figma: "This month").
   */
  val thisMonth: List<Prescription>
    get() = others.filterNot { it.prescribedOn.isBefore(today.withDayOfMonth(1)) }

  /**
   * The other prescriptions prescribed before the current month, in the same order (Figma:
   * "Earlier").
   */
  val earlier: List<Prescription>
    get() = others.filter { it.prescribedOn.isBefore(today.withDayOfMonth(1)) }

  /** The prescriptions not in [waiting]. */
  private val others: List<Prescription>
    get() = waiting.toSet().let { waiting -> prescriptions.filterNot { it in waiting } }
}

/**
 * Loads the signed-in user's saved prescriptions (US-11) and keeps them up to date, so a
 * prescription saved from the form appears without reloading. The repository reads from the local
 * cache, so the list also works offline.
 *
 * In caregiver mode (US-20), the prescriptions are the followed patient's, read-only, when they
 * share them.
 *
 * @param clock the current date, which decides what counts as "This month"
 * @param patientUid in caregiver mode, the patient whose prescriptions are shown; null for the
 *   user's own
 */
class PrescriptionsViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val patientUid: String? = null,
    resolveSection: ResolveSectionAccess =
        ResolveSectionAccess(ResolveCareCircleAccess(auth = authRepository), authRepository),
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    private val seenPrescriptions: SeenPrescriptions = SeenPrescriptionsProvider.store,
    private val manageMedicationSchedule: ManageMedicationSchedule =
        ManageMedicationSchedule(medicationRepository),
) : ViewModel() {

  private val observeSection = ObserveCareSection(resolveSection)

  private val _uiState = MutableStateFlow(PrescriptionsUiState(today = LocalDate.now(clock)))
  val uiState: StateFlow<PrescriptionsUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    load()
    followSeen()
  }

  /** In caregiver mode, follows which accepted prescriptions of the patient they closed. */
  private fun followSeen() {
    val patient = patientUid ?: return
    val caregiver = runCatching { authRepository.currentUser?.uid }.getOrNull() ?: return
    viewModelScope.launch {
      seenPrescriptions.observe(caregiver).collect { keys ->
        val ids = keys.filter { it.startsWith("$patient/") }.map { it.removePrefix("$patient/") }
        _uiState.update { it.copy(seen = ids.toSet()) }
      }
    }
  }

  /**
   * Closes the state of a prescription the caregiver added (US-43): an accepted one stays in the
   * list without it, a rejected one is deleted. Works offline. Does nothing for one still waiting.
   */
  fun dismiss(prescriptionId: String) {
    val patient = patientUid ?: return
    val state = _uiState.value
    val caregiver = state.caregiverUid ?: return
    val prescription = state.waiting.firstOrNull { it.id == prescriptionId } ?: return
    when (prescription.confirmation) {
      PrescriptionConfirmation.CONFIRMED ->
          seenPrescriptions.markSeen(caregiver, patient, prescriptionId)
      PrescriptionConfirmation.REJECTED ->
          viewModelScope.launch {
            try {
              manageMedicationSchedule.dismissRejected(patient, prescriptionId)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.e(TAG, "Failed to delete the rejected prescription $prescriptionId", e)
            }
          }
      PrescriptionConfirmation.PENDING -> Unit
    }
  }

  /** Starts (or, after an error, restarts) observing the prescriptions. */
  fun load() {
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    observation = viewModelScope.launch {
      observeSection(CarePermission.PRESCRIPTIONS, patientUid) { ownerUid ->
            // A rejected prescription is gone for everyone (US-43)
            val prescriptions =
                medicationRepository.observePrescriptions(ownerUid).map { it.visible() }
            // The patient sees the names they gave the members who added some; a caregiver the
            // stored ones, and those they added that the patient rejected
            if (patientUid != null) prescriptions.combine(rejectedBy(ownerUid), ::withRejected)
            else
                prescriptions.combine(careCircleRepository.observeMemberNames(ownerUid)) {
                    list,
                    names ->
                  list.map { it.namedBy(names) }
                }
          }
          .collect { section -> _uiState.update { it.showing(section) } }
    }
  }

  /**
   * [list] with the [rejected] ones, each prescription once. The two listeners answer apart: right
   * after a rejection, the rejected one can arrive before the one waiting is gone, and it then
   * replaces it, as it is the newer state.
   */
  private fun withRejected(
      list: List<Prescription>,
      rejected: List<Prescription>,
  ): List<Prescription> {
    val rejectedIds = rejected.map { it.id }.toSet()
    return list.filterNot { it.id in rejectedIds } + rejected
  }

  /**
   * The prescriptions the signed-in caregiver added that [ownerUid] rejected. Only a notice: if
   * they can't be read, the list shows without them.
   */
  private fun rejectedBy(ownerUid: String): Flow<List<Prescription>> {
    val caregiver = runCatching { authRepository.currentUser?.uid }.getOrNull()
    return if (caregiver == null) flowOf(emptyList())
    else
        medicationRepository.observeRejectedPrescriptions(ownerUid, caregiver).catch { e ->
          Log.w(TAG, "Couldn't read the rejected prescriptions", e)
          emit(emptyList())
        }
  }

  /**
   * The list for [section]: its prescriptions, or why there are none. Every flag is set each time,
   * so nothing from the previous access stays (e.g. an error after "not shared").
   */
  private fun PrescriptionsUiState.showing(
      section: CareSection<List<Prescription>>
  ): PrescriptionsUiState =
      when (section) {
        // Whose prescriptions they are is known before the list: adding doesn't wait for it
        is CareSection.Loading ->
            withoutPrescriptions(isLoading = true).copy(canAdd = section.canAdd)
        // Nobody signed in, or no longer in the patient's circle: nothing can be read
        CareSection.NoAccess -> withoutPrescriptions(hasError = true)
        CareSection.NotShared -> withoutPrescriptions(isNotShared = true)
        // A patient can still add one if their list fails; a caregiver can't
        is CareSection.Failed -> withoutPrescriptions(hasError = true).copy(canAdd = section.canAdd)
        is CareSection.Shown ->
            withoutPrescriptions()
                .copy(
                    prescriptions = section.data,
                    canAdd = section.canAdd,
                    // A caregiver's own prescriptions waiting for the patient show apart
                    caregiverUid = patientUid?.let { authRepository.currentUser?.uid },
                )
      }

  /** No prescriptions and nothing to add, for one of the reasons given. */
  private fun PrescriptionsUiState.withoutPrescriptions(
      isLoading: Boolean = false,
      hasError: Boolean = false,
      isNotShared: Boolean = false,
  ) =
      copy(
          prescriptions = emptyList(),
          isLoading = isLoading,
          hasError = hasError,
          isNotShared = isNotShared,
          canAdd = false,
      )

  /**
   * Keeps the current date right, so "This month" stays right while the list is open: updates it
   * now, then at each midnight, until the returned job is cancelled. The screen runs it while it is
   * shown.
   */
  fun followToday(): Job = viewModelScope.launch {
    currentDay(clock).collect { today -> _uiState.update { it.copy(today = today) } }
  }

  private companion object {
    const val TAG = "PrescriptionsViewModel"
  }
}
