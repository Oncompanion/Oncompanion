// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.domain.medication.toDraft
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why saving the prescription failed, so the screen can show the right message. */
enum class PrescriptionFormError {
  /** Nobody is signed in (e.g. the session expired): the user has to sign in again. */
  NOT_SIGNED_IN,
  /**
   * Saving failed on the device. Never a refusal by the server, see
   * [PrescriptionFormViewModel.save].
   */
  SAVE_FAILED,
}

/**
 * Whether the form has the prescription it works on, so the screen can show the form, a progress
 * indicator, or why there is nothing to edit.
 */
enum class PrescriptionFormLoad {
  /** The form can be filled in: a new prescription, or the saved one once it is read. */
  READY,
  /** The saved prescription is being read. */
  LOADING,
  /**
   * There is no such prescription: it was deleted (e.g. from another device), or the device is
   * offline and has never loaded it.
   */
  NOT_FOUND,
  /** Nobody is signed in (e.g. the session expired): the user has to sign in again. */
  NOT_SIGNED_IN,
  /** Reading the prescription failed. Not a connection problem: offline, the cache answers. */
  FAILED,
}

/**
 * What the prescription form shows.
 *
 * @property draft the prescription as typed so far
 * @property openMedication position in the draft of the medication open for editing
 * @property startDateChosen for each medication of the draft, in order: whether the user chose its
 *   start date. Until then it follows the date of the prescription.
 * @property nameTouched for each medication of the draft, in order: whether the user edited its
 *   name or left it for another medication. A missing name is only flagged from then on.
 * @property isSaving the prescription is being saved
 * @property isSaved the prescription is saved: the form is done
 * @property error why the last save failed, until it has been shown
 * @property isEditing the form corrects a saved prescription instead of adding a new one
 * @property load whether the form can be filled in. Until it is [PrescriptionFormLoad.READY],
 *   [draft] isn't the saved prescription and must not be shown.
 */
data class PrescriptionFormUiState(
    val draft: PrescriptionDraft,
    val openMedication: Int = 0,
    val startDateChosen: List<Boolean> = draft.medications.map { true },
    val nameTouched: List<Boolean> = draft.medications.map { true },
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: PrescriptionFormError? = null,
    val isEditing: Boolean = false,
    val load: PrescriptionFormLoad = PrescriptionFormLoad.READY,
) {
  /** Another medication can be added: the prescription isn't full. */
  val canAddMedication: Boolean
    get() = draft.medications.size < Prescription.MAX_MEDICATIONS

  /** A medication can be removed: a prescription keeps at least one. */
  val canRemoveMedication: Boolean
    get() = draft.medications.size > 1

  /**
   * Positions of the medications that can't be saved as they are, so the screen can show which ones
   * block Save, also when they aren't open.
   */
  val invalidMedications: Set<Int>
    get() = draft.medications.indices.filterNot { draft.medications[it].isValid() }.toSet()

  /**
   * The prescription can be saved: the form has its prescription, the draft is valid and nothing is
   * being saved.
   */
  val canSave: Boolean
    get() = load == PrescriptionFormLoad.READY && draft.isValid() && !isSaving && !isSaved
}

/**
 * The prescription form (US-24): the user types a prescription and its medications, one open for
 * editing at a time, then saves it. Nothing is saved before the user confirms with Save.
 *
 * Given a [prescriptionId], the form corrects that saved prescription instead (US-12): it reads it
 * (see [load]), starts from what was saved, and Save replaces it. The fields and their validation
 * are the same as for a new prescription.
 *
 * @param clock the current date, which a new prescription starts with
 * @param initialDraft what the form starts with; `null` for an empty form. Replaced by the saved
 *   prescription when [prescriptionId] is given.
 * @param prescriptionId the saved prescription to correct; `null` to add a new one
 * @param medicationRepository reads the prescription to correct. Writes go through
 *   [manageMedicationSchedule].
 */
class PrescriptionFormViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val manageMedicationSchedule: ManageMedicationSchedule = ManageMedicationSchedule(),
    clock: Clock = Clock.systemDefaultZone(),
    initialDraft: PrescriptionDraft? = null,
    private val prescriptionId: String? = null,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
) : ViewModel() {

  // A new form follows the date of the prescription; in a given draft every start date is chosen
  private val _uiState =
      MutableStateFlow(
          if (initialDraft != null) PrescriptionFormUiState(initialDraft.cutToLimits())
          else
              emptyDraft(LocalDate.now(clock)).let {
                PrescriptionFormUiState(
                    it,
                    startDateChosen = listOf(false),
                    nameTouched = listOf(false),
                )
              }
      )
  val uiState: StateFlow<PrescriptionFormUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  // The draft the form last started from: while the form still shows it, the user changed nothing
  private var shownDraft: PrescriptionDraft? = null

  init {
    // A saved prescription has to be read before the form can show it
    if (prescriptionId != null) {
      _uiState.update { it.copy(isEditing = true, load = PrescriptionFormLoad.LOADING) }
    }
    load()
  }

  /**
   * Starts (or, after an error, restarts) observing the saved prescription to correct, and starts
   * the form from it. The screen calls it to try again when the prescription couldn't be loaded.
   *
   * The form keeps following the saved prescription while it is open:
   * - A prescription that arrives later (e.g. once the connection is back on a device that had
   *   never loaded it) fills the form by itself.
   * - A change made elsewhere refreshes the form while it still shows what was saved (the user
   *   changed nothing, or undid their changes). Otherwise it is ignored, so nothing replaces what
   *   they are correcting. It is also ignored while saving and once saved.
   * - A deletion makes the form [PrescriptionFormLoad.NOT_FOUND] right away, also while saving, and
   *   what was typed is dropped: there is nothing left to correct.
   *
   * Does nothing for a new prescription, which has nothing to read, and once the form is
   * [PrescriptionFormLoad.READY]: it is already following the prescription.
   *
   * Every saved start date counts as chosen and every name as touched, as in a draft the form is
   * given. This is the only place the form reads a prescription.
   */
  fun load() {
    val id = prescriptionId ?: return
    if (_uiState.value.load == PrescriptionFormLoad.READY) return
    observation?.cancel()
    _uiState.update { it.copy(load = PrescriptionFormLoad.LOADING) }
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(load = PrescriptionFormLoad.NOT_SIGNED_IN) }
      return
    }
    observation = viewModelScope.launch {
      try {
        medicationRepository.observePrescription(uid, id).collect(::show)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load the prescription $id", e)
        _uiState.update { it.copy(load = PrescriptionFormLoad.FAILED) }
      }
    }
  }

  /**
   * Applies what is saved for the prescription to correct, `null` if it doesn't exist. Like every
   * change to the state, it runs on the main thread, so reading then writing the state can't lose
   * an update.
   */
  private fun show(saved: Prescription?) {
    val state = _uiState.value
    // Once saved, the form is done: what arrives next is its own save
    if (state.isSaved) return
    if (saved == null) {
      _uiState.value = state.copy(load = PrescriptionFormLoad.NOT_FOUND)
      return
    }
    // While saving, the form keeps showing what is being saved
    if (state.isSaving) return
    val draft = saved.toDraft().cutToLimits()
    // A form in use is left alone if the user changed it, and also if the same content arrives
    // again (e.g. the server confirmed it), which would close the medication they opened
    val ready = state.load == PrescriptionFormLoad.READY
    if (ready && (state.draft != shownDraft || draft == state.draft)) return
    // Anything typed before a deletion or a failure is dropped: the form starts from what is saved
    shownDraft = draft
    _uiState.value = PrescriptionFormUiState(draft, isEditing = true, error = state.error)
  }

  /** Text longer than the prescription can store is cut. */
  fun onPrescribedByChange(value: String) {
    edit { state ->
      state.withDraft { it.copy(prescribedBy = value.take(Prescription.MAX_PRESCRIBED_BY_LENGTH)) }
    }
  }

  /**
   * Changes the date of the prescription. The start date of a medication follows it as long as the
   * user hasn't chosen one.
   */
  fun onPrescribedOnChange(date: LocalDate) {
    edit { state ->
      state.withDraft { draft ->
        draft.copy(
            prescribedOn = date,
            medications =
                draft.medications.mapIndexed { position, medication ->
                  if (state.startDateChosen[position]) medication
                  else medication.copy(startDate = date)
                },
        )
      }
    }
  }

  /** Text longer than a medication can store is cut. */
  fun onMedicationNameChange(value: String) {
    edit { state ->
      state
          .withOpenMedication { it.copy(name = value.take(Medication.MAX_TEXT_LENGTH)) }
          .withOpenNameTouched()
    }
  }

  /** Text longer than a medication can store is cut. */
  fun onDosageChange(value: String) {
    editOpenMedication { it.copy(dosage = value.take(Medication.MAX_TEXT_LENGTH)) }
  }

  /** Text longer than a medication can store is cut. */
  fun onFrequencyChange(value: String) {
    editOpenMedication { it.copy(frequency = value.take(Medication.MAX_TEXT_LENGTH)) }
  }

  /**
   * Changes the first day of intake of the open medication. From then on it no longer follows the
   * date of the prescription, even if the user chose the same day.
   */
  fun onStartDateChange(date: LocalDate) {
    edit { state ->
      state
          .withOpenMedication { it.copy(startDate = date) }
          .copy(
              startDateChosen =
                  state.startDateChosen.mapIndexed { position, chosen ->
                    chosen || position == state.openMedication
                  }
          )
    }
  }

  /**
   * Keeps the digits of [value] as a number of days, [MAX_DURATION_DIGITS] at most; no digit means
   * no end.
   */
  fun onDurationChange(value: String) {
    val days = value.filter(Char::isDigit).take(MAX_DURATION_DIGITS).toIntOrNull()
    editOpenMedication { it.copy(durationDays = days) }
  }

  /**
   * Adds an empty medication at the end, starting on the date of the prescription, and opens it.
   * Does nothing if the prescription is full.
   */
  fun addMedication() {
    edit { state ->
      if (!state.canAddMedication) return@edit state
      val added = MedicationDraft(startDate = state.draft.prescribedOn)
      state.copy(
          draft = state.draft.copy(medications = state.draft.medications + added),
          openMedication = state.draft.medications.size,
          startDateChosen = state.startDateChosen + false,
          // The medication the user leaves counts as touched, the new one doesn't yet
          nameTouched = state.withOpenNameTouched().nameTouched + false,
      )
    }
  }

  /** Opens the medication at [position] for editing. Ignores a position that doesn't exist. */
  fun openMedication(position: Int) {
    _uiState.update { state ->
      // The medication the user leaves counts as touched: a missing name is flagged from then on
      if (position in state.draft.medications.indices) {
        state.withOpenNameTouched().copy(openMedication = position)
      } else state
    }
  }

  /**
   * Removes the medication at [position]. The medication that was open stays open; if it is the one
   * removed, the next one opens (or the previous one, if it was the last). Does nothing if it is
   * the only medication or if the position doesn't exist.
   */
  fun removeMedication(position: Int) {
    edit { state ->
      if (!state.canRemoveMedication || position !in state.draft.medications.indices) {
        return@edit state
      }
      val remaining = state.draft.medications.filterIndexed { index, _ -> index != position }
      // Medications after the removed one move up by one position
      val open =
          if (position < state.openMedication) state.openMedication - 1 else state.openMedication
      state.copy(
          draft = state.draft.copy(medications = remaining),
          openMedication = open.coerceAtMost(remaining.lastIndex),
          startDateChosen = state.startDateChosen.filterIndexed { index, _ -> index != position },
          nameTouched = state.nameTouched.filterIndexed { index, _ -> index != position },
      )
    }
  }

  /**
   * Saves the prescription through [ManageMedicationSchedule]: a new one is added, a saved one
   * ([PrescriptionFormUiState.isEditing]) is replaced. Does nothing unless
   * [PrescriptionFormUiState.canSave]. Saving returns as soon as the prescription is stored on the
   * device, also offline.
   *
   * If the prescription to correct was deleted since the form was opened (e.g. from another
   * device), or while saving, nothing is saved and the form becomes
   * [PrescriptionFormLoad.NOT_FOUND] instead of saved. See [ManageMedicationSchedule.update] for
   * what this can't catch.
   *
   * [PrescriptionFormError.SAVE_FAILED] only covers what fails before that, on the device. If the
   * server refuses the prescription later, the form isn't told: it has already reported the save as
   * done, and Firestore then undoes the write. Don't rely on it for server errors.
   */
  fun save() {
    val state = _uiState.value
    if (!state.canSave) return
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(error = PrescriptionFormError.NOT_SIGNED_IN) }
      return
    }
    _uiState.update { it.copy(isSaving = true, error = null) }
    viewModelScope.launch {
      try {
        val stillExists =
            if (prescriptionId == null) {
              manageMedicationSchedule.add(uid, state.draft)
              true
            } else manageMedicationSchedule.update(uid, prescriptionId, state.draft)
        _uiState.update {
          // Deleted since the form was opened, or while saving (the repository then writes
          // nothing): there is nothing left to correct
          if (stillExists && it.load != PrescriptionFormLoad.NOT_FOUND) {
            it.copy(isSaving = false, isSaved = true)
          } else it.copy(isSaving = false, load = PrescriptionFormLoad.NOT_FOUND)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save the prescription", e)
        _uiState.update { it.copy(isSaving = false, error = PrescriptionFormError.SAVE_FAILED) }
      }
    }
  }

  /** The error has been shown. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  /**
   * Applies a change the user made to the form. Ignored until the form has its prescription, while
   * the prescription is being saved and once it is saved: the form would no longer show what was
   * saved.
   */
  private fun edit(transform: (PrescriptionFormUiState) -> PrescriptionFormUiState) {
    _uiState.update { state ->
      val locked = state.load != PrescriptionFormLoad.READY || state.isSaving || state.isSaved
      if (locked) state else transform(state)
    }
  }

  private fun editOpenMedication(transform: (MedicationDraft) -> MedicationDraft) {
    edit { it.withOpenMedication(transform) }
  }

  private fun PrescriptionFormUiState.withOpenNameTouched() =
      copy(
          nameTouched =
              nameTouched.mapIndexed { position, touched -> touched || position == openMedication }
      )

  private fun PrescriptionFormUiState.withDraft(
      transform: (PrescriptionDraft) -> PrescriptionDraft
  ) = copy(draft = transform(draft))

  private fun PrescriptionFormUiState.withOpenMedication(
      transform: (MedicationDraft) -> MedicationDraft
  ) = withDraft { draft ->
    draft.copy(
        medications =
            draft.medications.mapIndexed { position, medication ->
              if (position == openMedication) transform(medication) else medication
            }
    )
  }

  private companion object {
    const val TAG = "PrescriptionFormViewModel"

    /** Enough digits for [Medication.MAX_DURATION_DAYS]. */
    const val MAX_DURATION_DIGITS = 4

    /**
     * The draft with its text cut to what a prescription can store, as typed text is. A draft the
     * form is given (e.g. read from a scan) can be longer, and nothing on screen could say why Save
     * is disabled.
     */
    fun PrescriptionDraft.cutToLimits() =
        copy(
            prescribedBy = prescribedBy.take(Prescription.MAX_PRESCRIBED_BY_LENGTH),
            medications =
                medications.map {
                  it.copy(
                      name = it.name.take(Medication.MAX_TEXT_LENGTH),
                      dosage = it.dosage.take(Medication.MAX_TEXT_LENGTH),
                      frequency = it.frequency.take(Medication.MAX_TEXT_LENGTH),
                  )
                },
        )

    /** A prescription of [today] with one medication to fill in, starting the same day. */
    fun emptyDraft(today: LocalDate) =
        PrescriptionDraft(
            prescribedOn = today,
            medications = listOf(MedicationDraft(startDate = today)),
        )
  }
}
