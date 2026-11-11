// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.prescription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.observeMemberNames
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The recorded medication, or why its private content cannot currently be shown. */
sealed interface MedicationDetailUiState {
  /** Access or the first prescription snapshot is still being checked. */
  data object Loading : MedicationDetailUiState

  /** The requested medication and its containing prescription, from the same snapshot. */
  data class Loaded(val medication: Medication, val prescription: Prescription) :
      MedicationDetailUiState

  /** Missing from the local snapshot, removed, or rejected by the patient. */
  data object Unavailable : MedicationDetailUiState

  /** A failed read can be retried; stale content must not remain actionable. */
  data object Error : MedicationDetailUiState

  /** The followed patient no longer shares prescriptions. */
  data object NotShared : MedicationDetailUiState
}

/**
 * Observes one recorded medication through its prescription, including offline edits and access
 * changes. It never interprets the frequency or writes treatment data.
 */
class MedicationDetailViewModel(
    private val prescriptionId: String,
    private val medicationId: String,
    authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
    private val patientUid: String? = null,
    resolveSection: ResolveSectionAccess =
        ResolveSectionAccess(ResolveCareCircleAccess(auth = authRepository), authRepository),
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
) : ViewModel() {
  private val observeSection = ObserveCareSection(resolveSection)
  private val state = MutableStateFlow<MedicationDetailUiState>(MedicationDetailUiState.Loading)
  /** One live state for the screen; access changes remove the previously displayed data. */
  val uiState = state.asStateFlow()
  private var observation: Job? = null

  init {
    retry()
  }

  /** Replaces the previous read after a failure, without leaving stale actions on screen. */
  fun retry() {
    observation?.cancel()
    state.value = MedicationDetailUiState.Loading
    observation = viewModelScope.launch {
      observeSection(CarePermission.PRESCRIPTIONS, patientUid) { ownerUid ->
            val prescription = medicationRepository.observePrescription(ownerUid, prescriptionId)
            // Only the patient may read their circle's names; a caregiver sees the stored
            // attribution.
            if (patientUid != null) prescription
            else
                prescription.combine(careCircleRepository.observeMemberNames(ownerUid)) {
                    item,
                    names ->
                  item?.copy(addedByName = names[item.addedBy] ?: item.addedByName)
                }
          }
          .collect { section ->
            state.value =
                when (section) {
                  is CareSection.Loading -> MedicationDetailUiState.Loading
                  CareSection.NoAccess -> MedicationDetailUiState.Unavailable
                  CareSection.NotShared -> MedicationDetailUiState.NotShared
                  is CareSection.Failed -> MedicationDetailUiState.Error
                  is CareSection.Shown -> {
                    val prescription =
                        section.data?.takeUnless {
                          it.confirmation == PrescriptionConfirmation.REJECTED
                        }
                    val medication = prescription?.medications?.find { it.id == medicationId }
                    if (prescription == null || medication == null)
                        MedicationDetailUiState.Unavailable
                    else MedicationDetailUiState.Loaded(medication, prescription)
                  }
                }
          }
    }
  }
}
