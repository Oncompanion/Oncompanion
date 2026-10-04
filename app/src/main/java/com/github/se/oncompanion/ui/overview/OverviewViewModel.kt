package com.github.se.oncompanion.ui.overview

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.overview.FakeOverviewRepository
import com.github.se.oncompanion.model.overview.NextAppointment
import com.github.se.oncompanion.model.overview.OverviewRepository
import com.github.se.oncompanion.model.overview.TodayItem
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a [TodayItem] stands in the day. */
enum class TodayItemStatus {
  /** Over, or taken for a medication. */
  DONE,
  /** The first item that isn't done yet. */
  NEXT,
  /** Planned after the next one. */
  LATER,
}

/** A [TodayItem] with its [status] in the day. */
data class TodayEntry(val item: TodayItem, val status: TodayItemStatus)

/**
 * What the Overview shows.
 *
 * @property now when the day was loaded, for the greeting and today's date
 * @property firstName the user's first name, or `null` for a greeting without a name
 * @property todayEntries today's items, in time order
 * @property isLoading true until the appointment and today's items arrive
 * @property hasError true if they couldn't be loaded
 */
data class OverviewUiState(
    val now: LocalDateTime,
    val firstName: String? = null,
    val nextAppointment: NextAppointment? = null,
    val todayEntries: List<TodayEntry> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
)

/**
 * Loads the user's day for the Overview (US-23): their first name, their next appointment and
 * today's items.
 *
 * @param profileRepository creates the profile repository; it is only called when a user is signed
 *   in, so the Overview also works where Firebase isn't available
 * @param clock the current time
 */
class OverviewViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val profileRepository: () -> UserProfileRepository = {
      UserProfileRepositoryFirestore()
    },
    private val overviewRepository: OverviewRepository = FakeOverviewRepository(),
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

  private val _uiState = MutableStateFlow(OverviewUiState(now = clock()))
  val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

  private var dayObservation: Job? = null

  init {
    loadFirstName()
    loadDay()
  }

  /** Starts (or, after an error, restarts) observing the next appointment and today's items. */
  fun loadDay() {
    dayObservation?.cancel()
    _uiState.update { it.copy(now = clock(), isLoading = true, hasError = false) }
    dayObservation = viewModelScope.launch {
      combine(
              overviewRepository.observeNextAppointment(),
              overviewRepository.observeTodayItems(),
          ) { appointment, items ->
            appointment to items
          }
          .catch { e ->
            Log.e(TAG, "Failed to load the day", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { (appointment, items) ->
            _uiState.update {
              it.copy(
                  nextAppointment = appointment,
                  todayEntries = entriesOf(items),
                  isLoading = false,
                  hasError = false,
              )
            }
          }
    }
  }

  /**
   * Shows the first name from the user's profile, or else from their Google account. The greeting
   * is only a nicety: if anything fails here, the Overview simply greets without a name.
   */
  private fun loadFirstName() {
    val user =
        try {
          authRepository.currentUser
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't read the signed-in user", e)
          null
        } ?: return
    _uiState.update { it.copy(firstName = user.firstNameGuess()) }
    viewModelScope.launch {
      try {
        profileRepository().observeProfile(user.uid).collect { profile ->
          val name = profile?.firstName?.takeIf { it.isNotBlank() } ?: return@collect
          _uiState.update { it.copy(firstName = name) }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't load the profile", e)
      }
    }
  }

  private fun entriesOf(items: List<TodayItem>): List<TodayEntry> {
    val sorted = items.sortedBy { it.time }
    val next = sorted.firstOrNull { !it.isDone }
    return sorted.map { item ->
      val status =
          when {
            item.isDone -> TodayItemStatus.DONE
            item == next -> TodayItemStatus.NEXT
            else -> TodayItemStatus.LATER
          }
      TodayEntry(item, status)
    }
  }

  private companion object {
    const val TAG = "OverviewViewModel"
  }
}
