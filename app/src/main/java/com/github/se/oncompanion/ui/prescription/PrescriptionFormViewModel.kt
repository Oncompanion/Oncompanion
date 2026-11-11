// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CaregiverName
import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.domain.medication.SimilarPrescription
import com.github.se.oncompanion.domain.medication.scan.MedicationDetail
import com.github.se.oncompanion.domain.medication.scan.ScannedField
import com.github.se.oncompanion.domain.medication.scan.ScannedPrescription
import com.github.se.oncompanion.domain.medication.similarPrescription
import com.github.se.oncompanion.domain.medication.toDraft
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.medication.visible
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.common.SavePhase
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * @property save where saving the prescription is; once [SavePhase.SAVED], the form is done
 * @property error why the last save failed, until it has been shown
 * @property isEditing the form corrects a saved prescription instead of adding a new one
 * @property load whether the form can be filled in. Until it is [PrescriptionFormLoad.READY],
 *   [draft] isn't the saved prescription and must not be shown.
 * @property isScanned the draft was read from a scan: the patient reviews it before saving
 * @property toCheck the details read from the scan the patient should check, until they edit them
 * @property leftOut how many medications the scan read beyond what a prescription can hold
 * @property dateFound whether the scan read a date; if not, the date is today's
 * @property renewalOf a saved prescription with the same medications on another date: this one
 *   looks like its renewal (US-39), said above the form
 * @property alreadySaved a saved prescription this one is the same as: Save asked first whether to
 *   save it anyway (US-39)
 * @property isToConfirm the saved prescription was added by a caregiver and waits for the patient
 *   (US-43): saving it confirms it, and it can be rejected instead
 * @property isConfirmingRejection the patient asked to reject it and hasn't confirmed or cancelled
 *   yet
 * @property wasRejected the form is done because the patient rejected it: it no longer exists
 *   anywhere, not even its detail
 */
data class PrescriptionFormUiState(
    val draft: PrescriptionDraft,
    val openMedication: Int = 0,
    val startDateChosen: List<Boolean> = draft.medications.map { true },
    val nameTouched: List<Boolean> = draft.medications.map { true },
    val save: SavePhase = SavePhase.EDITING,
    val error: PrescriptionFormError? = null,
    val isEditing: Boolean = false,
    val load: PrescriptionFormLoad = PrescriptionFormLoad.READY,
    val isScanned: Boolean = false,
    val toCheck: Set<ScannedField> = emptySet(),
    val leftOut: Int = 0,
    val dateFound: Boolean = true,
    val renewalOf: Prescription? = null,
    val alreadySaved: Prescription? = null,
    val isToConfirm: Boolean = false,
    val isConfirmingRejection: Boolean = false,
    val wasRejected: Boolean = false,
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
    get() = load == PrescriptionFormLoad.READY && draft.isValid() && save == SavePhase.EDITING
}

/**
 * The prescription form (US-24): the user types a prescription and its medications, one open for
 * editing at a time, then saves it. Nothing is saved before the user confirms with Save.
 *
 * Given a [prescriptionId], the form corrects that saved prescription instead (US-12): it reads it
 * (see [load]), starts from what was saved, and Save replaces it. The fields and their validation
 * are the same as for a new prescription.
 *
 * @param medicationRepository reads the prescription to correct. Writes go through
 *   [manageMedicationSchedule].
 * @param manageMedicationSchedule saves the prescription; by default through
 *   [medicationRepository], so the form never reads from one repository and writes to another
 * @param clock the current date, which a new prescription starts with
 * @param initialDraft what the form starts with; `null` for an empty form. Replaced by the saved
 *   prescription when [prescriptionId] is given.
 * @param prescriptionId the saved prescription to correct; `null` to add a new one
 * @param scan the prescription read from a scan, which the form starts from instead of
 *   [initialDraft]: the patient reviews it, with the details to check marked
 * @param patientUid in caregiver mode (US-43), the patient whose prescriptions get the new one;
 *   null for the user's own. It is saved waiting for the patient to confirm it, signed with the
 *   caregiver's first name, and the rules only accept it if the patient lets them add
 *   prescriptions. Only the patient corrects a saved prescription.
 * @param profileRepository the caregiver's profile, for the first name their prescription is signed
 *   with
 */
class PrescriptionFormViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
    private val manageMedicationSchedule: ManageMedicationSchedule =
        ManageMedicationSchedule(medicationRepository),
    clock: Clock = Clock.systemDefaultZone(),
    initialDraft: PrescriptionDraft? = null,
    private val prescriptionId: String? = null,
    scan: ScannedPrescription? = null,
    private val patientUid: String? = null,
    profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
) : ViewModel() {

  /** The first name a caregiver's prescription is signed with. */
  private val caregiverName = CaregiverName(profileRepository, authRepository)

  // A new form follows the date of the prescription; in a given draft every start date is chosen
  private val _uiState =
      MutableStateFlow(
          if (scan != null) {
            PrescriptionFormUiState(
                scan.draft.cutToLimits(),
                isScanned = true,
                toCheck = scan.toCheck,
                leftOut = scan.leftOut,
                dateFound = scan.dateFound,
            )
          } else if (initialDraft != null) PrescriptionFormUiState(initialDraft.cutToLimits())
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

  // The prescriptions already saved, which a new one is compared with (US-39)
  private var saved: List<Prescription> = emptyList()

  init {
    require(patientUid == null || prescriptionId == null) {
      "Only the patient corrects a saved prescription"
    }
    // A saved prescription has to be read before the form can show it
    if (prescriptionId != null) {
      _uiState.update { it.copy(isEditing = true, load = PrescriptionFormLoad.LOADING) }
    } else observeSaved()
    load()
  }

  /**
   * Follows the prescriptions already saved, from the device's cache when offline, so a new one can
   * be compared with them: the patient's, confirmed or not, also when a caregiver adds one. A
   * rejected prescription is no longer a duplicate, also for the patient, so it is left out; the
   * list doesn't hide it yet (#339 does). Comparing is only a warning: if they can't be read, the
   * form works as before.
   */
  private fun observeSaved() {
    val uid = authRepository.signedInUid(TAG) ?: return
    viewModelScope.launch {
      try {
        medicationRepository.observePrescriptions(patientUid ?: uid).collect { prescriptions ->
          saved = prescriptions.visible()
          _uiState.update { it.withRenewal() }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the saved prescriptions to compare with", e)
      }
    }
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
    if (state.save == SavePhase.SAVED) return
    // A deletion ends the form at once, also while saving
    if (saved == null) {
      _uiState.value = state.copy(load = PrescriptionFormLoad.NOT_FOUND)
      return
    }
    // While saving, the form keeps showing what is being saved: also its own rejection, which
    // arrives before reject() returns
    if (state.save == SavePhase.SAVING) return
    // A prescription rejected elsewhere is gone for the patient, as a deleted one
    if (saved.isRejected) {
      _uiState.value = state.copy(load = PrescriptionFormLoad.NOT_FOUND)
      return
    }
    val draft = saved.toDraft().cutToLimits()
    val isToConfirm = saved.confirmation == PrescriptionConfirmation.PENDING
    // A form in use is left alone if the user changed it, and also if the same content arrives
    // again (e.g. the server confirmed it), which would close the medication they opened
    val ready = state.load == PrescriptionFormLoad.READY
    if (ready && (state.draft != shownDraft || draft == state.draft)) {
      // Confirmed on another device meanwhile: nothing left to reject
      _uiState.value = state.copy(isToConfirm = isToConfirm)
      return
    }
    // Anything typed before a deletion or a failure is dropped: the form starts from what is saved
    shownDraft = draft
    _uiState.value =
        PrescriptionFormUiState(
            draft,
            isEditing = true,
            error = state.error,
            isToConfirm = isToConfirm,
        )
  }

  /** Text longer than the prescription can store is cut. */
  fun onPrescribedByChange(value: String) {
    edit { state ->
      state
          .withDraft { it.copy(prescribedBy = value.take(Prescription.MAX_PRESCRIBED_BY_LENGTH)) }
          .checked(ScannedField.PrescribedBy)
    }
  }

  /**
   * Changes the date of the prescription. The start date of a medication follows it as long as the
   * user hasn't chosen one.
   */
  fun onPrescribedOnChange(date: LocalDate) {
    edit { state ->
      state
          .withDraft { draft ->
            draft.copy(
                prescribedOn = date,
                medications =
                    draft.medications.mapIndexed { position, medication ->
                      if (state.startDateChosen[position]) medication
                      else medication.copy(startDate = date)
                    },
            )
          }
          .checked(ScannedField.PrescribedOn)
    }
  }

  /** Text longer than a medication can store is cut. */
  fun onMedicationNameChange(value: String) {
    edit { state ->
      state
          .withOpenMedication { it.copy(name = value.take(Medication.MAX_TEXT_LENGTH)) }
          .withOpenNameTouched()
          .openChecked(MedicationDetail.NAME)
    }
  }

  /** Text longer than a medication can store is cut. */
  fun onDosageChange(value: String) {
    editOpenMedication(MedicationDetail.DOSAGE) {
      it.copy(dosage = value.take(Medication.MAX_TEXT_LENGTH))
    }
  }

  /** Text longer than a medication can store is cut. */
  fun onFrequencyChange(value: String) {
    editOpenMedication(MedicationDetail.FREQUENCY) {
      it.copy(frequency = value.take(Medication.MAX_TEXT_LENGTH))
    }
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
    editOpenMedication(MedicationDetail.DURATION) { it.copy(durationDays = days) }
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
          // The removed medication's details have nothing left to check; the others move with it
          toCheck =
              state.toCheck.mapNotNullTo(mutableSetOf()) { field ->
                when {
                  field !is ScannedField.OfMedication -> field
                  field.position == position -> null
                  field.position > position -> field.copy(position = field.position - 1)
                  else -> field
                }
              },
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
  fun save() = save(confirmed = false)

  /** Saves the prescription although it is the same as [PrescriptionFormUiState.alreadySaved]. */
  fun saveAnyway() {
    if (_uiState.value.alreadySaved == null) return
    _uiState.update { it.copy(alreadySaved = null) }
    save(confirmed = true)
  }

  /** Goes back to the form without saving: the patient keeps editing it. */
  fun keepEditing() {
    _uiState.update { it.copy(alreadySaved = null) }
  }

  /**
   * Saves, unless a new prescription is the same as a saved one and the patient hasn't [confirmed]
   * it: [PrescriptionFormUiState.alreadySaved] then asks first (US-39).
   */
  private fun save(confirmed: Boolean) {
    val state = _uiState.value
    if (!state.canSave) return
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(error = PrescriptionFormError.NOT_SIGNED_IN) }
      return
    }
    if (!confirmed && asksAboutTheSame(state.draft)) return
    _uiState.update { it.copy(save = SavePhase.SAVING, error = null) }
    viewModelScope.launch {
      try {
        val stillExists = write(uid, state.draft)
        _uiState.update {
          // Deleted since the form was opened, or while saving (the repository then writes
          // nothing): there is nothing left to correct
          if (stillExists && it.load != PrescriptionFormLoad.NOT_FOUND) {
            it.copy(save = SavePhase.SAVED)
          } else it.copy(save = SavePhase.EDITING, load = PrescriptionFormLoad.NOT_FOUND)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: NotSignedIn) {
        _uiState.update {
          it.copy(save = SavePhase.EDITING, error = PrescriptionFormError.NOT_SIGNED_IN)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save the prescription", e)
        _uiState.update {
          it.copy(save = SavePhase.EDITING, error = PrescriptionFormError.SAVE_FAILED)
        }
      }
    }
  }

  /**
   * Writes [draft] for the signed-in [uid]: corrects the saved prescription, adds the user's own,
   * or adds it for the patient a caregiver follows.
   *
   * @return `false` if the prescription to correct no longer exists
   */
  private suspend fun write(uid: String, draft: PrescriptionDraft): Boolean =
      when {
        prescriptionId != null -> manageMedicationSchedule.update(uid, prescriptionId, draft)
        patientUid != null -> addForThePatient(patientUid, uid, draft)
        else -> {
          manageMedicationSchedule.add(uid, draft)
          true
        }
      }

  /**
   * Adds [draft] to [patientUid]'s prescriptions, signed by the caregiver [uid] and waiting for the
   * patient. Reading the name can take a while: if the caregiver signed out meanwhile, nothing is
   * saved and the form says they have to sign in again.
   *
   * @return `true` once saved
   */
  private suspend fun addForThePatient(
      patientUid: String,
      uid: String,
      draft: PrescriptionDraft,
  ): Boolean {
    val name = caregiverName(uid)
    // Reading the name can take a while: the form may be gone, or the account changed, meanwhile
    currentCoroutineContext().ensureActive()
    if (authRepository.signedInUid(TAG) != uid) throw NotSignedIn()
    manageMedicationSchedule.add(patientUid, draft, addedBy = uid, addedByName = name)
    return true
  }

  /** The caregiver signed out while their prescription was being signed. */
  private class NotSignedIn : Exception()

  /**
   * Whether a new [draft] is the same as a saved prescription:
   * [PrescriptionFormUiState.alreadySaved] then asks the patient before saving it again.
   */
  private fun asksAboutTheSame(draft: PrescriptionDraft): Boolean {
    if (prescriptionId != null) return false
    val same = similarPrescription(draft, saved) as? SimilarPrescription.Same ?: return false
    _uiState.update { it.copy(alreadySaved = same.prescription) }
    return true
  }

  /**
   * Asks the patient to confirm the rejection of the prescription a caregiver added. Nothing is
   * rejected before [reject].
   */
  fun requestRejection() {
    _uiState.update {
      if (it.isToConfirm && it.load == PrescriptionFormLoad.READY && it.save == SavePhase.EDITING)
          it.copy(isConfirmingRejection = true)
      else it
    }
  }

  /** Closes the rejection question: the patient keeps reviewing the prescription. */
  fun cancelRejection() {
    _uiState.update { it.copy(isConfirmingRejection = false) }
  }

  /**
   * Rejects the prescription, once the patient confirmed it (US-43): it disappears from their
   * prescriptions, Planning and the Overview, and is never reminded. The form is then done, as
   * after saving. If it was deleted or decided elsewhere meanwhile, nothing is rejected: the form
   * then shows what is saved, as it follows the prescription.
   */
  fun reject() {
    val state = _uiState.value
    val id = prescriptionId
    if (!state.isConfirmingRejection || state.save != SavePhase.EDITING || id == null) return
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update {
        it.copy(isConfirmingRejection = false, error = PrescriptionFormError.NOT_SIGNED_IN)
      }
      return
    }
    _uiState.update { it.copy(save = SavePhase.SAVING, error = null) }
    viewModelScope.launch {
      try {
        val rejected = manageMedicationSchedule.reject(uid, id)
        _uiState.update {
          // Not rejected: it was confirmed elsewhere (a deletion shows as not found by itself)
          it.copy(
              save = if (rejected) SavePhase.SAVED else SavePhase.EDITING,
              isConfirmingRejection = false,
              isToConfirm = rejected,
              wasRejected = rejected,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to reject the prescription", e)
        _uiState.update {
          it.copy(
              save = SavePhase.EDITING,
              isConfirmingRejection = false,
              error = PrescriptionFormError.SAVE_FAILED,
          )
        }
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
      val locked = state.load != PrescriptionFormLoad.READY || state.save != SavePhase.EDITING
      if (locked) state else transform(state).withRenewal()
    }
  }

  private fun editOpenMedication(
      detail: MedicationDetail,
      transform: (MedicationDraft) -> MedicationDraft,
  ) {
    edit { it.withOpenMedication(transform).openChecked(detail) }
  }

  /**
   * Sets [PrescriptionFormUiState.renewalOf] to the saved prescription with the same medications on
   * another date (US-39), or to null when there is none. A correction of a saved prescription is
   * never compared, so its renewalOf stays null.
   */
  private fun PrescriptionFormUiState.withRenewal() =
      copy(
          renewalOf =
              if (isEditing) null
              else (similarPrescription(draft, saved) as? SimilarPrescription.Renewal)?.prescription
      )

  /** The patient edited [field]: it is no longer to check. */
  private fun PrescriptionFormUiState.checked(field: ScannedField) = copy(toCheck = toCheck - field)

  /** The patient edited [detail] of the open medication: it is no longer to check. */
  private fun PrescriptionFormUiState.openChecked(detail: MedicationDetail) =
      checked(ScannedField.OfMedication(openMedication, detail))

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
