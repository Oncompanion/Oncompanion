// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a [TodayEntry] is, which decides its icon. */
enum class TodayItemKind {
  MEDICATION,
  EVENT,
  APPOINTMENT,
}

/** Where a timed [TodayEntry] stands in the day, at the current time. */
enum class TodayItemStatus {
  /** An event or appointment that is over. */
  DONE,
  /** An event or appointment happening right now. */
  NOW,
  /** The first timed entry still to come. */
  NEXT,
  /** Planned after the next one. */
  LATER,
}

/**
 * The user's next appointment, as shown on the Overview.
 *
 * @property id the source appointment identity used to open its details
 * @property title what the appointment is (e.g. "Oncology check-up")
 * @property dateTime when it starts, in the phone's time zone
 * @property place where it takes place, if the user entered it
 */
data class NextAppointment(
    val id: String,
    val title: String,
    val dateTime: LocalDateTime,
    val place: String? = null,
)

/**
 * One entry of the Overview's "Today" list, taken from the planning data.
 *
 * @property key identifies the entry, the same as in Planning
 * @property time when it starts, or `null` for an entry with a date but no time (a medication)
 * @property subtitle a short detail line (place, recorded frequency...), if any
 * @property status where a timed entry stands now; `null` for an entry without a time, since the
 *   app never decides whether a medication was taken
 */
data class TodayEntry(
    val key: String,
    val kind: TodayItemKind,
    val time: LocalTime?,
    val title: String,
    val subtitle: String? = null,
    val status: TodayItemStatus? = null,
)

/**
 * What the Overview shows.
 *
 * @property now the current time, for the greeting, today's date and the entries' status
 * @property firstName the user's first name, or `null` for a greeting without a name
 * @property todayEntries today's entries: those without a time first, then by time
 * @property isLoading true until the planning data arrives
 * @property hasError true if it couldn't be loaded
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
 * today's entries. The appointment and the entries come from the same planning data as the Planning
 * screen, so both always agree.
 *
 * @param clock the current time
 * @param zoneId the time zone the day is shown in
 */
class OverviewViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val planningRepository: PlanningRepository = PlanningRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(OverviewUiState(now = now()))
  val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

  private var dayObservation: Job? = null

  /** The planning entries as the repository gave them; what they show depends on the time. */
  private var items: List<PlanningItem> = emptyList()

  init {
    loadFirstName()
    loadDay()
  }

  /**
   * Starts (or, after an error, restarts) observing the planning data from today to [LOOKAHEAD]
   * later, so the next appointment is found even months ahead, as between two oncology check-ups.
   */
  fun loadDay() {
    dayObservation?.cancel()
    val now = now()
    _uiState.update { it.copy(now = now, isLoading = true, hasError = false) }
    val today = now.toLocalDate()
    val range = PlanningRange(today, today.plus(LOOKAHEAD), zoneId)
    dayObservation = viewModelScope.launch {
      planningRepository
          .observeItems(range)
          .catch { e ->
            Log.e(TAG, "Failed to load the day", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { planned ->
            items = planned
            _uiState.update { state ->
              state.copy(isLoading = false, hasError = false).withItems(planned, state.now)
            }
          }
    }
  }

  /**
   * Updates the time, so the greeting, the date, the next appointment and the entries' status stay
   * right while the ViewModel lives (the screen calls it each time it comes back). On a new day, it
   * also reloads the planning data from the new day.
   */
  fun refreshNow() {
    val now = now()
    val newDay = now.toLocalDate() != _uiState.value.now.toLocalDate()
    _uiState.update { it.copy(now = now).withItems(items, now) }
    if (newDay) loadDay()
  }

  private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zoneId)

  /** The next appointment and today's entries from [planned], at [now]. */
  private fun OverviewUiState.withItems(planned: List<PlanningItem>, now: LocalDateTime) =
      copy(
          nextAppointment = nextAppointmentOf(planned, now),
          todayEntries = entriesOf(planned, now),
      )

  /** The first appointment that hasn't started yet. */
  private fun nextAppointmentOf(planned: List<PlanningItem>, now: LocalDateTime): NextAppointment? =
      planned
          .mapNotNull { item ->
            (item.source as? PlanningSource.Appointment)?.let { source ->
              item.startIn()?.let { start ->
                NextAppointment(source.appointmentId, item.title, start, item.subtitle)
              }
            }
          }
          .filter { it.dateTime.isAfter(now) }
          .minByOrNull { it.dateTime }

  /**
   * Today's entries: those without a time first, as in Planning, then the timed ones by time. A
   * timed entry is happening from its start until [DEFAULT_DURATION] later (the planning data has
   * no end time), then it's done; the first one still to come is the next.
   */
  private fun entriesOf(planned: List<PlanningItem>, now: LocalDateTime): List<TodayEntry> {
    val today = now.toLocalDate()
    val ofToday = planned.filter { it.timing.dateIn(zoneId) == today }
    val untimed = ofToday.filter { it.timing is PlanningTiming.DateOnly }
    val timed =
        ofToday.mapNotNull { item -> item.startIn()?.let { item to it } }.sortedBy { it.second }
    val next = timed.firstOrNull { (_, start) -> start.isAfter(now) }?.first
    return untimed.map { it.toEntry(time = null, status = null) } +
        timed.map { (item, start) ->
          val status =
              when {
                !start.plus(DEFAULT_DURATION).isAfter(now) -> TodayItemStatus.DONE
                !start.isAfter(now) -> TodayItemStatus.NOW
                item == next -> TodayItemStatus.NEXT
                else -> TodayItemStatus.LATER
              }
          item.toEntry(time = start.toLocalTime(), status = status)
        }
  }

  /** When a timed entry starts, in [zoneId]; `null` for an entry without a time. */
  private fun PlanningItem.startIn(): LocalDateTime? =
      (timing as? PlanningTiming.Timed)?.let { LocalDateTime.ofInstant(it.instant, zoneId) }

  private fun PlanningItem.toEntry(time: LocalTime?, status: TodayItemStatus?) =
      TodayEntry(
          key = key,
          kind =
              when (source) {
                is PlanningSource.Medication -> TodayItemKind.MEDICATION
                is PlanningSource.Event -> TodayItemKind.EVENT
                is PlanningSource.Appointment -> TodayItemKind.APPOINTMENT
              },
          time = time,
          title = title,
          // A medication's detail is its recorded frequency, as in Planning
          subtitle = subtitle ?: frequency,
          status = status,
      )

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
        profileRepository.observeProfile(user.uid).collect { profile ->
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

  private companion object {
    const val TAG = "OverviewViewModel"

    /** How far ahead the next appointment is looked for. */
    val LOOKAHEAD: Period = Period.ofYears(1)

    /**
     * How long an event or appointment counts as happening (the planning data only has start
     * times), so it isn't ticked as done the minute it starts.
     */
    val DEFAULT_DURATION: Duration = Duration.ofHours(1)
  }
}
