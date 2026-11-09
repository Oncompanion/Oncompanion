// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.ui.common.currentDay
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
data class PrescriptionsUiState(
    val today: LocalDate,
    val prescriptions: List<Prescription> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val canAdd: Boolean = false,
    val isNotShared: Boolean = false,
) {
  /**
   * The prescriptions prescribed on or after the first day of the current month, in the order of
   * [prescriptions] (Figma: "This month").
   */
  val thisMonth: List<Prescription>
    get() = prescriptions.filterNot { it.prescribedOn.isBefore(today.withDayOfMonth(1)) }

  /**
   * The prescriptions prescribed before the current month, in the same order (Figma: "Earlier").
   */
  val earlier: List<Prescription>
    get() = prescriptions.filter { it.prescribedOn.isBefore(today.withDayOfMonth(1)) }
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
) : ViewModel() {

  private val observeSection = ObserveCareSection(resolveSection)

  private val _uiState = MutableStateFlow(PrescriptionsUiState(today = LocalDate.now(clock)))
  val uiState: StateFlow<PrescriptionsUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the prescriptions. */
  fun load() {
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    observation = viewModelScope.launch {
      observeSection(CarePermission.PRESCRIPTIONS, patientUid) { ownerUid ->
            medicationRepository.observePrescriptions(ownerUid)
          }
          .collect { section -> _uiState.update { it.showing(section) } }
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
            withoutPrescriptions().copy(prescriptions = section.data, canAdd = section.canAdd)
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
}
