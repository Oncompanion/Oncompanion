package com.github.se.oncompanion.ui.symptom

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryFirestore
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An entry of the journal, with the local [time] it happened at. */
data class JournalEntry(val entry: SymptomEntry, val time: LocalTime)

/** The entries of one day of the journal, newest first. */
data class JournalDay(val date: LocalDate, val entries: List<JournalEntry>)

/**
 * What the symptom journal shows.
 *
 * @property days the days with symptoms, newest first (Figma: "Symptoms – History")
 * @property today the current date, to call the days "Today" and "Yesterday"
 * @property isLoading true until the entries arrive
 * @property hasError true if they couldn't be loaded (or nobody is signed in)
 */
data class SymptomJournalUiState(
    val today: LocalDate,
    val days: List<JournalDay> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
)

/**
 * Loads the signed-in user's symptom journal (US-8), grouped by day, and keeps it up to date.
 *
 * @param clock the current time and the time zone the days are counted in
 */
class SymptomJournalViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val symptomRepository: SymptomRepository = SymptomRepositoryFirestore(),
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(SymptomJournalUiState(today = LocalDate.now(clock)))
  val uiState: StateFlow<SymptomJournalUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the journal. */
  fun load() {
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(isLoading = false, hasError = true) }
      return
    }
    observation = viewModelScope.launch {
      symptomRepository
          .observeSymptoms(uid)
          .catch { e ->
            Log.e(TAG, "Failed to load the symptom journal", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { entries ->
            _uiState.update { it.copy(days = daysOf(entries), isLoading = false, hasError = false) }
          }
    }
  }

  /**
   * Updates the current date, so "Today" and "Yesterday" stay right while the ViewModel lives (the
   * screen calls it each time it comes back).
   */
  fun refreshToday() {
    _uiState.update { it.copy(today = LocalDate.now(clock)) }
  }

  /** Groups [entries] (newest first) by their local day, keeping the order. */
  private fun daysOf(entries: List<SymptomEntry>): List<JournalDay> =
      entries
          .map { entry ->
            val local = entry.occurredAt.atZone(clock.zone)
            local.toLocalDate() to JournalEntry(entry, local.toLocalTime())
          }
          .groupBy({ it.first }, { it.second })
          .map { (date, dayEntries) -> JournalDay(date, dayEntries) }

  private companion object {
    const val TAG = "SymptomJournalViewModel"
  }
}
