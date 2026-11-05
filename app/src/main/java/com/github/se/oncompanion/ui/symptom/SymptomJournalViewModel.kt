// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.domain.carecircle.SectionAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.addedByName
import com.github.se.oncompanion.model.carecircle.observeMemberNames
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.ui.common.currentDay
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * An entry of the journal, with the local [time] it happened at.
 *
 * @property addedByName who added it, if a caregiver did: for the patient, the name they gave that
 *   member in their circle
 */
data class JournalEntry(
    val entry: SymptomEntry,
    val time: LocalTime,
    val addedByName: String? = entry.addedByName,
)

/** The entries of one day of the journal, newest first. */
data class JournalDay(val date: LocalDate, val entries: List<JournalEntry>)

/**
 * What the symptom journal shows.
 *
 * @property days the days with symptoms, newest first (Figma: "Symptoms – History")
 * @property today the current date, to call the days "Today" and "Yesterday"
 * @property isLoading true until the entries arrive
 * @property hasError true if they couldn't be loaded (or nobody is signed in)
 * @property canAdd whether a symptom can be added: to the user's own journal, or to the patient's
 *   if they let this caregiver add symptoms, once the access is known
 * @property isNotShared in caregiver mode, the patient doesn't share their symptoms: no entries
 */
data class SymptomJournalUiState(
    val today: LocalDate,
    val days: List<JournalDay> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val canAdd: Boolean = false,
    val isNotShared: Boolean = false,
)

/**
 * Loads a symptom journal (US-8), grouped by day, and keeps it up to date: the signed-in user's, or
 * in caregiver mode (US-20) that of the patient they follow, when shared. The caregiver can also
 * add to it if the patient allows it (US-22).
 *
 * @param clock the current time and the time zone the days are counted in
 * @param patientUid in caregiver mode, the patient whose journal is shown; null for the user's own
 */
class SymptomJournalViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val patientUid: String? = null,
    private val resolveSection: ResolveSectionAccess =
        ResolveSectionAccess(ResolveCareCircleAccess(auth = authRepository), authRepository),
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SymptomJournalUiState(today = LocalDate.now(clock)))
  val uiState: StateFlow<SymptomJournalUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the journal. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun load() {
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    observation = viewModelScope.launch {
      // In a flow, so that even an auth that throws right away shows the error state
      flow { emitAll(resolveSection(CarePermission.SYMPTOMS, patientUid)) }
          // Whose journal it is is known before its entries: adding doesn't wait for them, and
          // still works if they can't be read
          .onEach { access ->
            if (access is SectionAccess.Readable) {
              _uiState.update { it.copy(isNotShared = false, canAdd = access.canAdd) }
            }
          }
          .flatMapLatest(::journalOf)
          .catch { e ->
            Log.e(TAG, "Failed to load the symptom journal", e)
            // A patient can still log a symptom if their journal fails. A caregiver can't: their
            // access may have been taken away, and nothing would tell them once it is back
            _uiState.update {
              it.copy(
                  isLoading = false,
                  hasError = true,
                  isNotShared = false,
                  canAdd = it.canAdd && patientUid == null,
              )
            }
          }
          .collect { change -> _uiState.update(change) }
    }
  }

  /**
   * How the journal changes with [access]: the entries it may read, or why there are none. Each
   * access sets every flag, so nothing from the previous one stays (e.g. an error after "not
   * shared"), and only a journal the user may write to offers to add.
   */
  private fun journalOf(
      access: SectionAccess
  ): Flow<(SymptomJournalUiState) -> SymptomJournalUiState> =
      when (access) {
        SectionAccess.Loading -> flowOf { it.withoutEntries(isLoading = true) }
        // Nobody signed in, or no longer in the patient's circle: nothing can be read
        SectionAccess.NoAccess -> flowOf { it.withoutEntries(hasError = true) }
        SectionAccess.NotShared -> flowOf { it.withoutEntries(isNotShared = true) }
        is SectionAccess.Readable ->
            combine(symptomRepository.observeSymptoms(access.ownerUid), namesOf(access.ownerUid)) {
                entries,
                names ->
              { state: SymptomJournalUiState ->
                state.copy(days = daysOf(entries, names), isLoading = false, hasError = false)
              }
            }
      }

  /**
   * The names the patient gave their circle, to show who added each entry. A caregiver gets none:
   * they see the name stored with the entry, never what the patient calls them.
   */
  private fun namesOf(ownerUid: String): Flow<Map<String, String>> =
      if (patientUid == null) careCircleRepository.observeMemberNames(ownerUid)
      else flowOf(emptyMap())

  /** No entries and nothing to add, for one of the reasons given. */
  private fun SymptomJournalUiState.withoutEntries(
      isLoading: Boolean = false,
      hasError: Boolean = false,
      isNotShared: Boolean = false,
  ) =
      copy(
          days = emptyList(),
          isLoading = isLoading,
          hasError = hasError,
          isNotShared = isNotShared,
          canAdd = false,
      )

  /**
   * Keeps the current date right, so "Today" and "Yesterday" stay right while the journal is open:
   * updates it now, then at each midnight, until the returned job is cancelled. The screen runs it
   * while it is shown.
   */
  fun followToday(): Job = viewModelScope.launch {
    currentDay(clock).collect { today -> _uiState.update { it.copy(today = today) } }
  }

  /** Groups [entries] (newest first) by their local day, keeping the order. */
  private fun daysOf(entries: List<SymptomEntry>, names: Map<String, String>): List<JournalDay> =
      entries
          .map { entry ->
            val local = entry.occurredAt.atZone(clock.zone)
            val addedBy = addedByName(entry.addedBy, entry.addedByName, names)
            local.toLocalDate() to JournalEntry(entry, local.toLocalTime(), addedBy)
          }
          .groupBy({ it.first }, { it.second })
          .map { (date, dayEntries) -> JournalDay(date, dayEntries) }

  private companion object {
    const val TAG = "SymptomJournalViewModel"
  }
}
