// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.events

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.CaregiverName
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.addedByName
import com.github.se.oncompanion.model.carecircle.observeMemberNames
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRegistration
import com.github.se.oncompanion.model.event.EventRegistrationRepository
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryProvider
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the event page offers about the patient's registration with the organizer (US-25). */
enum class RegistrationStatus {
  /** Not known yet. */
  LOADING,
  /** Not confirmed, and the event hasn't started: the patient can confirm. */
  CAN_CONFIRM,
  /** Confirmed, until a day after the event: the patient can remove it. */
  CONFIRMED,
  /**
   * Not confirmed, or confirmed but more than a day after the event, and the event has started:
   * nothing can be confirmed.
   */
  CLOSED,
  /** The registration couldn't be read or changed. */
  ERROR,
  /**
   * The patient a caregiver follows doesn't share Events with them: the page says so, so the
   * caregiver doesn't take it for "not registered".
   */
  NOT_SHARED,
  /** Nothing is shown about it: the caregiver has no access to the patient anymore. */
  HIDDEN,
}

/**
 * What the event detail screen shows.
 *
 * @property event the event, once loaded; null once loaded means no event has this id
 * @property isLoading true until the event is loaded
 * @property hasError true if the event couldn't be loaded
 * @property registration what the screen offers about the patient's registration
 * @property isConfirmingRemoval the patient asked to remove their confirmation and hasn't confirmed
 *   or cancelled yet
 * @property caregiverCanConfirm a caregiver the patient lets confirm events (US-42) may confirm the
 *   registration for them; they can never remove it
 * @property addedByName who confirmed it, if a caregiver did: for the patient, the name they gave
 *   that member in their circle
 */
data class EventDetailUiState(
    val event: Event? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val registration: RegistrationStatus = RegistrationStatus.LOADING,
    val isConfirmingRemoval: Boolean = false,
    val caregiverCanConfirm: Boolean = false,
    val addedByName: String? = null,
)

/**
 * Loads one of the Ligue's events for its detail screen, with whether the patient confirmed their
 * registration for it: the signed-in patient, or the patient a caregiver follows when they share
 * Events (US-20). A caregiver only reads it, unless the patient lets them confirm events (US-42).
 *
 * @param patientUid the followed patient in caregiver mode, null for the user's own registration
 * @param clock the current time, in the time zone the event's times are given in
 */
class EventDetailViewModel(
    private val eventId: String,
    private val repository: EventRepository = EventRepositoryProvider.repository,
    private val registrationRepository: EventRegistrationRepository =
        EventRegistrationRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val patientUid: String? = null,
    resolveSection: ResolveSectionAccess = ResolveSectionAccess(auth = authRepository),
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
) : ViewModel() {

  private val observeSection = ObserveCareSection(resolveSection)

  private val caregiverName = CaregiverName(profileRepository, authRepository)

  private val _uiState = MutableStateFlow(EventDetailUiState())
  val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

  private var registrationObservation: Job? = null

  /**
   * The loaded event and whether the patient confirmed it, to recompute the status as time passes.
   */
  private var lastRegistration: Pair<Event, Boolean>? = null

  /** Whose registration may be removed: only the user's own, once known. */
  private var editableOwner: String? = null

  /**
   * Whose registration may be confirmed, once known: the user's own, or the followed patient's when
   * they let the caregiver confirm events.
   */
  private var confirmableOwner: String? = null

  /** A caregiver's confirmation being signed, so repeated taps save only one. */
  private var signing: Job? = null

  init {
    loadEvent()
  }

  /** Loads (or, after an error, reloads) the event, then observes the patient's registration. */
  fun loadEvent() {
    registrationObservation?.cancel()
    lastRegistration = null
    editableOwner = null
    confirmableOwner = null
    _uiState.value = EventDetailUiState()
    viewModelScope.launch {
      // Null when the event no longer exists: the page then says it wasn't found
      val event: Event? =
          try {
            repository.getEvent(eventId)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Failed to load event $eventId", e)
            _uiState.value = EventDetailUiState(isLoading = false, hasError = true)
            return@launch
          }
      _uiState.value = EventDetailUiState(event = event, isLoading = false)
      if (event != null) observeRegistration(event)
    }
  }

  /**
   * Saves that the patient registered with the organizer. Only offered before the event starts; the
   * page then shows the confirmation once it is saved on the device.
   */
  fun confirmRegistration() {
    // The event may have started since the status was last computed
    refreshStatus()
    val owner = confirmableOwner
    if (_uiState.value.registration != RegistrationStatus.CAN_CONFIRM || owner == null) return
    if (patientUid == null) {
      changeRegistration("confirm", owner) { uid -> registrationRepository.confirm(uid, eventId) }
      return
    }
    // A caregiver signs it with their first name, read once per confirmation
    if (signing?.isActive == true) return
    signing = viewModelScope.launch {
      val caregiverUid = authRepository.currentUser?.uid
      val by = caregiverUid?.let { EventRegistration(it, caregiverName(it)) }
      // The patient may have taken the permission away, or confirmed it, while the name was read
      refreshStatus()
      if (
          confirmableOwner != owner || _uiState.value.registration != RegistrationStatus.CAN_CONFIRM
      )
          return@launch
      changeRegistration("confirm", owner) { uid ->
        registrationRepository.confirm(uid, eventId, by ?: error("Nobody is signed in"))
      }
    }
  }

  /**
   * Recomputes the registration status at the current time, e.g. once the event has started (the
   * screen calls it each time it comes back).
   */
  fun refreshStatus() {
    lastRegistration?.let { (event, isRegistered) ->
      showRegistration(statusOf(event, isRegistered))
    }
  }

  /** Asks the patient to confirm the removal. Nothing is removed before [removeRegistration]. */
  fun requestRemoval() {
    _uiState.update {
      if (it.registration == RegistrationStatus.CONFIRMED && editableOwner != null)
          it.copy(isConfirmingRemoval = true)
      else it
    }
  }

  /** Closes the removal confirmation and keeps the patient's confirmation. */
  fun cancelRemoval() {
    _uiState.update { it.copy(isConfirmingRemoval = false) }
  }

  /**
   * Removes the patient's confirmation, once they confirmed the removal. It doesn't cancel anything
   * with the organizer.
   */
  fun removeRegistration() {
    val state = _uiState.value
    if (!state.isConfirmingRemoval || state.registration != RegistrationStatus.CONFIRMED) return
    _uiState.update { it.copy(isConfirmingRemoval = false) }
    changeRegistration("remove", editableOwner) { uid ->
      registrationRepository.remove(uid, eventId)
    }
  }

  /**
   * Shows the registration of whoever [observeSection] says the page shows, and follows changes:
   * signing out, or the patient no longer sharing Events, hides the previous person's registration.
   */
  private fun observeRegistration(event: Event) {
    registrationObservation = viewModelScope.launch {
      observeSection(CarePermission.EVENTS, patientUid, ::registrationOf).collect { section ->
        showRegistration(statusOf(event, section))
      }
    }
  }

  /**
   * [ownerUid]'s registration, with who confirmed it as the patient named them in their circle (a
   * caregiver sees the stored name instead).
   */
  private fun registrationOf(ownerUid: String): Flow<ShownRegistration> {
    val registration = registrationRepository.observeRegistration(ownerUid, eventId)
    if (patientUid != null) {
      return registration.map { ShownRegistration(ownerUid, it, it?.addedByName) }
    }
    return registration.combine(careCircleRepository.observeMemberNames(ownerUid)) { it, names ->
      ShownRegistration(ownerUid, it, it?.let { addedByName(it.addedBy, it.addedByName, names) })
    }
  }

  /** What [section] lets the page show about [event]'s registration. */
  private fun statusOf(
      event: Event,
      section: CareSection<ShownRegistration>,
  ): RegistrationStatus {
    if (section !is CareSection.Shown) {
      // Another person's registration never shows, and nothing can be changed, until it is read
      lastRegistration = null
      editableOwner = null
      confirmableOwner = null
      _uiState.update { it.copy(caregiverCanConfirm = false, addedByName = null) }
    }
    return when (section) {
      is CareSection.Loading -> RegistrationStatus.LOADING
      // A patient who isn't signed in can't confirm anything
      CareSection.NoAccess ->
          if (patientUid == null) RegistrationStatus.ERROR else RegistrationStatus.HIDDEN
      CareSection.NotShared -> RegistrationStatus.NOT_SHARED
      is CareSection.Failed -> RegistrationStatus.ERROR
      is CareSection.Shown -> {
        val (ownerUid, registration, addedByName) = section.data
        val isRegistered = registration != null
        lastRegistration = event to isRegistered
        // A caregiver never removes it, and only confirms it when the patient lets them
        editableOwner = ownerUid.takeIf { section.canEdit }
        confirmableOwner = ownerUid.takeIf { section.canAdd }
        _uiState.update {
          it.copy(
              caregiverCanConfirm = patientUid != null && section.canAdd,
              addedByName = addedByName,
          )
        }
        statusOf(event, isRegistered)
      }
    }
  }

  /**
   * Shows [status]. The removal confirmation only stays open while there is a confirmation to
   * remove, e.g. not once it was removed on another device.
   */
  private fun showRegistration(status: RegistrationStatus) {
    _uiState.update {
      it.copy(
          registration = status,
          isConfirmingRemoval = it.isConfirmingRemoval && status == RegistrationStatus.CONFIRMED,
      )
    }
  }

  /**
   * Runs [change] for [owner], whose registration this is; the observation then shows the new
   * status. Without an owner (e.g. a caregiver removing it), nothing changes but the error.
   */
  private fun changeRegistration(action: String, owner: String?, change: (uid: String) -> Unit) {
    try {
      val uid = owner ?: error("This registration can't be changed")
      change(uid)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to $action the registration for event $eventId", e)
      showError()
    }
  }

  /** Shows the registration error, which coming back to the screen doesn't hide. */
  private fun showError() {
    lastRegistration = null
    showRegistration(RegistrationStatus.ERROR)
  }

  /**
   * A confirmation stays shown until a day after the event ends (or starts, when its end isn't
   * known), then the page treats it as if there were none.
   */
  private fun statusOf(event: Event, isRegistered: Boolean): RegistrationStatus {
    val now = LocalDateTime.now(clock)
    val confirmationShownUntil = (event.endDateTime ?: event.startDateTime) + CONFIRMATION_KEPT
    return when {
      isRegistered && !now.isAfter(confirmationShownUntil) -> RegistrationStatus.CONFIRMED
      now.isBefore(event.startDateTime) -> RegistrationStatus.CAN_CONFIRM
      else -> RegistrationStatus.CLOSED
    }
  }

  /** Whose registration is shown, the registration if any, and who confirmed it. */
  private data class ShownRegistration(
      val ownerUid: String,
      val registration: EventRegistration?,
      val addedByName: String?,
  )

  private companion object {
    const val TAG = "EventDetailViewModel"

    /** How long after an event its confirmation stays shown. */
    val CONFIRMATION_KEPT: Duration = Duration.ofHours(24)
  }
}
