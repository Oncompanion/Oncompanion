// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** Why saving the symptom failed, so the screen can show the right message. */
enum class AddSymptomError {
  /** Nobody is signed in (e.g. the session expired): the user has to sign in again. */
  NOT_SIGNED_IN,
  /** Saving failed on the device. Never a refusal by the server, see [AddSymptomViewModel.save]. */
  SAVE_FAILED,
}

/**
 * The symptom being edited, as it was loaded, with its day and time in the phone's time zone: what
 * the form started from.
 */
data class EditedSymptom(val entry: SymptomEntry, val date: LocalDate, val time: LocalTime)

/**
 * What the symptom form shows, when logging a new symptom or editing one.
 *
 * @property now the current day and time, in the phone's time zone: what the form shows until the
 *   user picks a day or a time, kept up to date by [AddSymptomViewModel.refreshNow]
 * @property types the symptoms picked, in the order the user picked them
 * @property intensity how bad the user felt overall, or `null` if they didn't say (it's optional so
 *   logging takes as few taps as possible)
 * @property date the day the user chose, or `null` for today. Choosing only a time sets it to the
 *   day the time was chosen, so the entry doesn't move a day ahead if the form stays open past
 *   midnight
 * @property time the time the user chose, or `null` for the current time
 * @property notes free text; required when [SymptomType.OTHER] is picked
 * @property isInFuture the chosen day and time are later than now, which can't be saved
 * @property isSaving the symptom is being saved
 * @property isSaved the symptom is saved: the form is done
 * @property error why the last save failed, until the screen has shown it
 * @property isEditing the form edits a symptom already logged instead of logging a new one
 * @property edited the symptom being edited, once it is loaded
 * @property isLoading the symptom to edit is loading
 * @property hasLoadError the symptom to edit couldn't be loaded (or nobody is signed in)
 * @property isNotFound the symptom to edit doesn't exist (e.g. deleted on another device)
 */
data class AddSymptomUiState(
    val now: LocalDateTime,
    val types: List<SymptomType> = emptyList(),
    val intensity: Int? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val notes: String = "",
    val isInFuture: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: AddSymptomError? = null,
    val isEditing: Boolean = false,
    val edited: EditedSymptom? = null,
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
    val isNotFound: Boolean = false,
) {
  /** The fields are shown: always when logging, once the symptom is loaded when editing. */
  val isFormShown: Boolean
    get() = !isEditing || edited != null

  /** The day or time differs from the symptom being edited; always true when logging. */
  val isWhenChanged: Boolean
    get() = edited == null || date != edited.date || time != edited.time

  /** Something differs from the symptom being edited; always true when logging. */
  val hasChanges: Boolean
    get() =
        edited == null ||
            isWhenChanged ||
            types != edited.entry.types ||
            intensity != edited.entry.intensity ||
            notes.trim().ifEmpty { null } != edited.entry.notes

  /** "Other" is picked, so the notes have to say which symptom it was. */
  val notesRequired: Boolean
    get() = SymptomType.OTHER in types

  /** The notes are required but still empty. */
  val isMissingNotes: Boolean
    get() = notesRequired && notes.isBlank()

  /** Neither a day nor a time is chosen: the symptom happens "now". */
  val isNow: Boolean
    get() = date == null && time == null

  /**
   * The symptom can be saved: something is picked, nothing is missing or being saved, and when
   * editing, something changed.
   */
  val canSave: Boolean
    get() =
        isFormShown &&
            types.isNotEmpty() &&
            !isMissingNotes &&
            !isInFuture &&
            !isSaving &&
            !isSaved &&
            hasChanges
}

/**
 * The symptom form. Without [symptomId], it logs a new symptom (US-07 "Log a symptom"): the user
 * picks one or more symptoms and saves them, optionally with how bad they felt, when, and notes.
 * With [symptomId], it edits that symptom (US-09): the form starts from what was logged and every
 * field can be changed. Nothing is saved before the user taps Save.
 *
 * @param clock the current time, and the time zone the day and time are chosen in
 * @param symptomId the symptom to edit, or `null` to log a new one
 */
class AddSymptomViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val symptomId: String? = null,
) : ViewModel() {

  private val _uiState =
      MutableStateFlow(
          AddSymptomUiState(now = LocalDateTime.now(clock), isEditing = symptomId != null)
      )
  val uiState: StateFlow<AddSymptomUiState> = _uiState.asStateFlow()

  init {
    load()
  }

  /**
   * Loads the symptom to edit (again, after an error) and fills the form with it. Loaded once: a
   * change made on another device meanwhile doesn't replace what the user is typing. Does nothing
   * when logging a new symptom.
   */
  fun load() {
    val id = symptomId ?: return
    _uiState.update { it.copy(isLoading = true, hasLoadError = false, isNotFound = false) }
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
      return
    }
    viewModelScope.launch {
      // Null when the symptom no longer exists (e.g. deleted on another device)
      val entry: SymptomEntry? =
          try {
            symptomRepository.observeSymptom(uid, id).first()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Failed to load the symptom $id", e)
            _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
            return@launch
          }
      _uiState.update {
        if (entry == null) it.copy(isLoading = false, isNotFound = true)
        else it.filledWith(entry).withFutureCheck()
      }
    }
  }

  /** The form showing [entry], the symptom being edited. */
  private fun AddSymptomUiState.filledWith(entry: SymptomEntry): AddSymptomUiState {
    val at = entry.occurredAt.atZone(clock.zone)
    val edited = EditedSymptom(entry, at.toLocalDate(), at.toLocalTime())
    return copy(
        isLoading = false,
        edited = edited,
        types = entry.types,
        intensity = entry.intensity,
        date = edited.date,
        time = edited.time,
        notes = entry.notes.orEmpty(),
    )
  }

  /** Picks [type], or unpicks it if it is already picked. */
  fun toggleType(type: SymptomType) {
    edit { state ->
      state.copy(types = if (type in state.types) state.types - type else state.types + type)
    }
  }

  /**
   * Sets the intensity. Values outside [SymptomEntry.MIN_INTENSITY]..[SymptomEntry.MAX_INTENSITY]
   * are ignored.
   */
  fun onIntensityChange(intensity: Int) {
    if (intensity !in SymptomEntry.MIN_INTENSITY..SymptomEntry.MAX_INTENSITY) return
    edit { it.copy(intensity = intensity) }
  }

  /** Goes back to no intensity, so none is saved. */
  fun clearIntensity() {
    edit { it.copy(intensity = null) }
  }

  /** Chooses the day the symptom was felt. */
  fun onDateChange(date: LocalDate) {
    edit { it.copy(date = date).withFutureCheck() }
  }

  /** Chooses the time the symptom was felt, today unless a day is already chosen. */
  fun onTimeChange(time: LocalTime) {
    edit { it.copy(date = it.date ?: LocalDate.now(clock), time = time).withFutureCheck() }
  }

  /**
   * Updates [AddSymptomUiState.now] and checks again whether the chosen day and time are later than
   * now, since time passes while the form is open. Returns how long until the next minute starts,
   * when the screen should call it again.
   */
  fun refreshNow(): Duration {
    val now = LocalDateTime.now(clock)
    _uiState.update { it.copy(now = now).withFutureCheck() }
    return Duration.between(now, now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1))
  }

  /** Text longer than an entry can store is cut. */
  fun onNotesChange(notes: String) {
    edit { it.copy(notes = notes.take(SymptomEntry.MAX_NOTES_LENGTH)) }
  }

  /**
   * Saves the symptom for the signed-in user, if [AddSymptomUiState.canSave]: a new one, or the
   * changes to the one being edited. Without a chosen day or time, it is today or now, at the
   * moment of saving; an edit that doesn't change the day and time keeps the exact moment it had.
   * Saving returns as soon as the symptom is stored on the device, also offline.
   *
   * [AddSymptomError.SAVE_FAILED] only covers what fails before that, on the device. If the server
   * refuses the symptom later, the form isn't told: Firestore undoes the write.
   */
  fun save() {
    // Time passes while the form is open: a chosen time can only be checked now
    val state = _uiState.updateAndGet { it.copy(now = LocalDateTime.now(clock)).withFutureCheck() }
    if (!state.canSave) return
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(error = AddSymptomError.NOT_SIGNED_IN) }
      return
    }
    val entry =
        SymptomEntry(
            id = symptomId.orEmpty(),
            types = state.types,
            occurredAt = state.effectiveOccurredAt(ZonedDateTime.now(clock)),
            intensity = state.intensity,
            notes = state.notes.trim().ifEmpty { null },
        )
    _uiState.update { it.copy(isSaving = true, error = null) }
    viewModelScope.launch {
      try {
        if (symptomId == null) symptomRepository.addSymptom(uid, entry)
        else symptomRepository.updateSymptom(uid, entry)
        _uiState.update { it.copy(isSaving = false, isSaved = true) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save the symptom", e)
        _uiState.update { it.copy(isSaving = false, error = AddSymptomError.SAVE_FAILED) }
      }
    }
  }

  /** The error has been shown. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  /**
   * Applies a change the user made to the form. Ignored while the symptom is being saved and once
   * it is saved: the form would no longer show what was saved. Also ignored before the symptom to
   * edit is loaded, as loading it would replace the change.
   */
  private fun edit(transform: (AddSymptomUiState) -> AddSymptomUiState) {
    _uiState.update { state ->
      if (state.isSaving || state.isSaved || !state.isFormShown) state else transform(state)
    }
  }

  private fun AddSymptomUiState.withFutureCheck(): AddSymptomUiState {
    val now = ZonedDateTime.now(clock)
    return copy(isInFuture = effectiveOccurredAt(now).isAfter(now.toInstant()))
  }

  /**
   * When the symptom was felt, as the form says at [now]: [occurredAt] from the chosen day and
   * time, except that an edit leaving them as they were keeps the exact moment logged. Rebuilding
   * it from the day and time shown could move it (by its seconds, or by an hour when the clocks go
   * back).
   */
  private fun AddSymptomUiState.effectiveOccurredAt(now: ZonedDateTime): Instant =
      if (edited != null && !isWhenChanged) edited.entry.occurredAt else occurredAt(date, time, now)

  private companion object {
    const val TAG = "AddSymptomViewModel"
  }
}

/**
 * When a symptom was felt, from the day and the time the user chose (in [now]'s time zone): [now]
 * when they chose neither, and today's date or the current time for the one they didn't choose.
 * Kept apart from the form so other ways of logging a symptom can share it.
 */
internal fun occurredAt(date: LocalDate?, time: LocalTime?, now: ZonedDateTime): Instant =
    if (date == null && time == null) now.toInstant()
    else
        ZonedDateTime.of(date ?: now.toLocalDate(), time ?: now.toLocalTime(), now.zone).toInstant()
