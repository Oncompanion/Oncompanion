// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CaregiverName
import com.github.se.oncompanion.domain.symptom.SymptomDraft
import com.github.se.oncompanion.domain.symptom.parseSpokenSymptom
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.speech.SpeechError
import com.github.se.oncompanion.model.speech.SpeechEvent
import com.github.se.oncompanion.model.speech.SpeechLanguage
import com.github.se.oncompanion.model.speech.SpeechRecognizer
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.common.SavePhase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * @property save where saving the symptom is; once [SavePhase.SAVED], the form is done
 * @property error why the last save failed, until the screen has shown it
 * @property isEditing the form edits a symptom already logged instead of logging a new one
 * @property edited the symptom being edited, once it is loaded
 * @property isLoading the symptom to edit is loading
 * @property hasLoadError the symptom to edit couldn't be loaded (or nobody is signed in)
 * @property isNotFound the symptom to edit doesn't exist (e.g. deleted on another device)
 * @property canDictate the phone has a speech recognizer, so the symptom can be dictated
 * @property voice where dictating is, shown instead of the form; `null` while the form shows
 * @property isDrafted the form was filled from what the patient said, so they should check it
 */
data class AddSymptomUiState(
    val now: LocalDateTime,
    val types: List<SymptomType> = emptyList(),
    val intensity: Int? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val notes: String = "",
    val isInFuture: Boolean = false,
    val save: SavePhase = SavePhase.EDITING,
    val error: AddSymptomError? = null,
    val isEditing: Boolean = false,
    val edited: EditedSymptom? = null,
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
    val isNotFound: Boolean = false,
    val canDictate: Boolean = false,
    val voice: VoiceUiState? = null,
    val isDrafted: Boolean = false,
) {
  /** The fields are shown: always when logging, once the symptom is loaded when editing. */
  val isFormShown: Boolean
    get() = !isEditing || edited != null

  /** The day or time differs from the symptom being edited; always true when logging. */
  val isWhenChanged: Boolean
    get() = edited == null || date != edited.date || time != edited.time

  /**
   * Something differs from the symptom being edited; always true when logging. The order of the
   * types doesn't count, so unticking and re-ticking one isn't a change.
   */
  val hasChanges: Boolean
    get() =
        edited == null ||
            isWhenChanged ||
            types.toSet() != edited.entry.types.toSet() ||
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
            save == SavePhase.EDITING &&
            hasChanges
}

/**
 * The symptom form. Without [symptomId], it logs a new symptom (US-07 "Log a symptom"): the user
 * picks one or more symptoms and saves them, optionally with how bad they felt, when, and notes.
 * With [symptomId], it edits that symptom (US-09): the form starts from what was logged and every
 * field can be changed. Nothing is saved before the user taps Save.
 *
 * In caregiver mode (US-22), it logs the symptom in the journal of [patientUid], signed with the
 * caregiver's uid and first name; the rules only accept it if the patient lets them add symptoms.
 * It edits one of the patient's the same way, signed as its last editor, if the patient lets them
 * edit and delete symptoms (US-46).
 *
 * @param clock the current time, and the time zone the day and time are chosen in
 * @param symptomId the symptom to edit, or `null` to log a new one
 * @param patientUid in caregiver mode, the patient whose journal gets the new or edited symptom;
 *   null for the user's own
 * @param profileRepository the caregiver's profile, for the first name the symptom is signed with
 * @param speechRecognizer listens when the symptom is dictated (US-44)
 * @param locale the phone's language: a symptom is dictated in French on a French phone, in English
 *   otherwise
 * @param picked a symptom already ticked when logging a new one (a check-in tile on the Overview);
 *   the user can still untick it. Ignored when editing
 */
class AddSymptomViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val symptomId: String? = null,
    private val patientUid: String? = null,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val speechRecognizer: SpeechRecognizer = SpeechRecognizerProvider.recognizer,
    private val locale: Locale = Locale.getDefault(),
    picked: SymptomType? = null,
) : ViewModel() {

  /** The first name a caregiver's symptom is signed with. */
  private val caregiverName = CaregiverName(profileRepository, authRepository)

  private val _uiState =
      MutableStateFlow(
          AddSymptomUiState(
              now = LocalDateTime.now(clock),
              types = listOfNotNull(picked.takeIf { symptomId == null }),
              isEditing = symptomId != null,
              canDictate = speechRecognizer.isAvailable(),
          )
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
            // A caregiver edits the patient's symptom
            symptomRepository.observeSymptom(patientUid ?: uid, id).first()
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
    _uiState.update { it.copy(save = SavePhase.SAVING, error = null) }
    viewModelScope.launch {
      try {
        if (!store(uid, entry)) {
          _uiState.update {
            it.copy(save = SavePhase.EDITING, error = AddSymptomError.NOT_SIGNED_IN)
          }
          return@launch
        }
        _uiState.update { it.copy(save = SavePhase.SAVED) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save the symptom", e)
        _uiState.update { it.copy(save = SavePhase.EDITING, error = AddSymptomError.SAVE_FAILED) }
      }
    }
  }

  /** The session listening to the patient, if one is running. */
  private var listening: Job? = null

  /**
   * The patient wants to dictate the symptom. Without the microphone ([hasMicrophone] false), the
   * app first says why it needs it; the screen then asks Android and calls [onMicrophoneAnswer].
   */
  fun onSpeak(hasMicrophone: Boolean) {
    if (!_uiState.value.canStartDictating) return
    if (hasMicrophone) startListening()
    else _uiState.update { it.copy(voice = VoiceUiState.ExplainingMicrophone) }
  }

  /**
   * Android's answer when asked for the microphone: listens if [granted]. An answer that comes
   * after the patient left dictating (cancelled, or the app was restarted meanwhile) is ignored.
   */
  fun onMicrophoneAnswer(granted: Boolean) {
    val state = _uiState.value
    if (state.voice != VoiceUiState.ExplainingMicrophone || !state.canStartDictating) return
    if (granted) startListening()
    else _uiState.update { it.copy(voice = VoiceUiState.MicrophoneRefused) }
  }

  /**
   * The patient allowed the microphone in the phone's settings after refusing it: listens right
   * away. Only while the form still says the microphone is off, so it never starts listening after
   * they left dictating.
   */
  fun onMicrophoneAllowedInSettings() {
    val state = _uiState.value
    if (state.voice == VoiceUiState.MicrophoneRefused && state.canStartDictating) startListening()
  }

  /** Drops what was heard and listens again. */
  fun restartListening() {
    startListening()
  }

  /** Back to the form without using what was said; it is dropped. */
  fun cancelVoice() {
    listening?.cancel()
    listening = null
    _uiState.update { it.copy(voice = null) }
  }

  /**
   * The patient is done: fills the form with what was heard, for them to check before saving. While
   * listening, it stops first and fills the form once the phone has the whole text. Only fields
   * still empty are filled: nothing they already entered is replaced.
   */
  fun onDone() {
    when (val voice = _uiState.value.voice) {
      is VoiceUiState.Heard -> useDraft(voice.draft)
      is VoiceUiState.Listening ->
          if (!voice.isFinishing) {
            _uiState.update { it.copy(voice = voice.copy(isFinishing = true)) }
            speechRecognizer.stop()
          }
      else -> Unit
    }
  }

  /** Fills the form with [draft] and goes back to it. */
  private fun useDraft(draft: SymptomDraft) {
    edit { it.filledFrom(draft).copy(voice = null, isDrafted = true).withFutureCheck() }
  }

  /** Listens to the patient in the phone's language, replacing any session already running. */
  private fun startListening() {
    listening?.cancel()
    _uiState.update { it.copy(voice = VoiceUiState.Listening()) }
    val language = SpeechLanguage.of(locale)
    listening = viewModelScope.launch {
      speechRecognizer.listen(language).collect { event ->
        if (event is SpeechEvent.Level) hearLevel(event.level)
        else voiceAfter(event, language)?.let(::show)
      }
    }
  }

  /**
   * Shows [next], unless the patient already tapped Done: then the whole text goes straight into
   * the form, and text still coming keeps Done tapped.
   */
  private fun show(next: VoiceUiState) {
    val current = _uiState.value.voice as? VoiceUiState.Listening
    when {
      current?.isFinishing == true && next is VoiceUiState.Heard -> useDraft(next.draft)
      // New words keep how loud the patient is and whether Done was tapped
      current != null && next is VoiceUiState.Listening ->
          _uiState.update {
            it.copy(voice = next.copy(isFinishing = current.isFinishing, level = current.level))
          }
      else -> _uiState.update { it.copy(voice = next) }
    }
  }

  /** How loud the patient is, shown while listening. */
  private fun hearLevel(level: Float) {
    _uiState.update { state ->
      val listening = state.voice as? VoiceUiState.Listening ?: return@update state
      state.copy(voice = listening.copy(level = level))
    }
  }

  /** What dictating shows after [event], or `null` if it doesn't change. */
  private fun voiceAfter(event: SpeechEvent, language: SpeechLanguage): VoiceUiState? =
      when (event) {
        SpeechEvent.Listening,
        is SpeechEvent.Level -> null
        is SpeechEvent.PartialText ->
            VoiceUiState.Listening(event.text, draftOf(event.text, language))
        is SpeechEvent.FinalText -> VoiceUiState.Heard(event.text, draftOf(event.text, language))
        is SpeechEvent.Failed ->
            if (event.error == SpeechError.NO_PERMISSION) VoiceUiState.MicrophoneRefused
            else VoiceUiState.Failed(event.error)
      }

  /** The form drafted from [text], parsed in the language it was heard in, not the phone's. */
  private fun draftOf(text: String, language: SpeechLanguage): SymptomDraft =
      parseSpokenSymptom(text, Locale.forLanguageTag(language.tag), ZonedDateTime.now(clock))

  /**
   * The form with [draft] in the fields still empty: its symptoms after those picked, its intensity
   * if none is set, its day and time if the symptom is still "now", and the transcript after the
   * notes.
   */
  private fun AddSymptomUiState.filledFrom(draft: SymptomDraft): AddSymptomUiState {
    val saysWhen = isNow && (draft.date != null || draft.time != null)
    return copy(
        types = types + (draft.types - types.toSet()),
        intensity = intensity ?: draft.intensity,
        // A time said without a day is today, as when the patient picks only a time
        date = if (saysWhen) draft.date ?: LocalDate.now(clock) else date,
        time = if (saysWhen) draft.time else time,
        notes =
            listOf(notes.trimEnd(), draft.notes)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .take(SymptomEntry.MAX_NOTES_LENGTH),
    )
  }

  /**
   * Logs or updates [entry] in the journal it belongs to. A caregiver's goes in the patient's
   * journal, signed with who added or edited it. Returns false, saving nothing, if the account
   * changed while the caregiver's name was read.
   */
  private suspend fun store(uid: String, entry: SymptomEntry): Boolean {
    val owner = patientUid ?: uid
    val signed =
        if (patientUid == null) entry
        else {
          val name = caregiverName(uid)
          // Reading the name can take a while: the account may have changed meanwhile
          currentCoroutineContext().ensureActive()
          if (authRepository.signedInUid(TAG) != uid) return false
          if (symptomId != null) entry.copy(editedBy = uid, editedByName = name)
          else entry.copy(addedBy = uid, addedByName = name)
        }
    if (symptomId != null) symptomRepository.updateSymptom(owner, signed)
    else symptomRepository.addSymptom(owner, signed)
    return true
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
      if (state.save != SavePhase.EDITING || !state.isFormShown) state else transform(state)
    }
  }

  /** The phone can dictate and the form can still change: not saving, and loaded when editing. */
  private val AddSymptomUiState.canStartDictating: Boolean
    get() = canDictate && save == SavePhase.EDITING && isFormShown

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
