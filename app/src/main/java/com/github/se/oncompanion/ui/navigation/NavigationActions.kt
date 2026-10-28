// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.navigation

import android.net.Uri
import androidx.navigation.NavHostController
import java.time.LocalDate

/**
 * Top-level navigation graphs. Navigating to a route opens the graph at its start screen.
 *
 * To add a feature: add a route here, its screens in [Screen], and a `navigation(...)` block in
 * [AppNavHost].
 */
object Route {
  const val AUTH = "auth"
  const val ONBOARDING = "onboarding"
  const val JOIN_CIRCLE = "join_circle"
  const val WHOSE_CARE = "whose_care"
  const val OVERVIEW = "overview"
  const val PLANNING = "planning"
  const val EVENTS = "events"
  const val SYMPTOMS = "symptoms"
  const val PRESCRIPTIONS = "prescriptions"
  const val CARE_CIRCLE = "care_circle"
  const val PROFILE = "profile"
}

/** Individual screens, grouped by the [Route] they belong to. */
object Screen {
  // Route.AUTH
  const val SIGN_IN = "sign_in"

  // Route.ONBOARDING
  const val ONBOARDING_ROLE = "onboarding_role"
  const val ONBOARDING_INFORMATION = "onboarding_information"

  // Route.JOIN_CIRCLE
  const val JOIN_CIRCLE = "join_circle_home"

  // Route.WHOSE_CARE
  /** Whether "Whose care?" chooses the only patient by itself, argument of [WHOSE_CARE]. */
  const val WHOSE_CARE_AUTO_SELECT = "autoSelect"
  const val WHOSE_CARE = "whose_care_home?$WHOSE_CARE_AUTO_SELECT={$WHOSE_CARE_AUTO_SELECT}"

  /** "Whose care?" opened to switch patient on purpose: the list shows even with one patient. */
  const val WHOSE_CARE_SWITCH = "whose_care_home?$WHOSE_CARE_AUTO_SELECT=false"

  // Route.OVERVIEW
  const val OVERVIEW = "overview_home"

  // Route.PLANNING
  const val PLANNING = "planning_home"
  /** Argument identifying the appointment owned by the current patient. */
  const val APPOINTMENT_ID = "appointmentId"
  const val APPOINTMENT_DETAIL = "appointment_detail/{$APPOINTMENT_ID}"

  /** Opens one appointment without changing the originating calendar selection. */
  fun appointmentDetail(id: String) = "appointment_detail/${Uri.encode(id)}"

  const val APPOINTMENT_DATE = "appointmentDate"
  const val APPOINTMENT_ADD = "appointment_add?date={$APPOINTMENT_DATE}"
  const val APPOINTMENT_SAVED_DATE = "appointmentSavedDate"

  /** Opens a new appointment on the selected calendar day. */
  fun appointmentAdd(date: LocalDate) = "appointment_add?date=$date"

  // Route.EVENTS
  const val EVENTS = "events_home"
  /** The event's id, argument of [EVENT_DETAIL]. */
  const val EVENT_ID = "eventId"
  const val EVENT_DETAIL = "event_detail/{$EVENT_ID}"

  /** The [EVENT_DETAIL] route of the event [eventId]. */
  fun eventDetail(eventId: String) = "event_detail/${Uri.encode(eventId)}"

  // Route.SYMPTOMS
  const val SYMPTOMS = "symptoms_home"
  const val SYMPTOM_DETAIL_ID = "symptomId"
  const val SYMPTOM_DETAIL = "symptoms_detail/{$SYMPTOM_DETAIL_ID}"

  /** The [SYMPTOM_DETAIL] screen of the symptom [symptomId]. */
  fun symptomDetail(symptomId: String) = "symptoms_detail/$symptomId"

  const val SYMPTOM_ADD = "symptoms_add"
  const val SYMPTOM_EDIT = "symptoms_edit/{$SYMPTOM_DETAIL_ID}"

  /** The [SYMPTOM_EDIT] screen of the symptom [symptomId]. */
  fun symptomEdit(symptomId: String) = "symptoms_edit/$symptomId"

  // Route.PRESCRIPTIONS
  const val PRESCRIPTIONS = "prescriptions_home"
  const val PRESCRIPTION_ADD = "prescriptions_add"
  const val PRESCRIPTION_SCAN = "prescriptions_scan"

  /** The argument of [PRESCRIPTION_DETAIL] and [PRESCRIPTION_EDIT]: the prescription's ID. */
  const val PRESCRIPTION_ID = "prescriptionId"

  const val PRESCRIPTION_DETAIL = "prescriptions_detail/{$PRESCRIPTION_ID}"

  /** The [PRESCRIPTION_DETAIL] screen of the prescription [prescriptionId]. */
  fun prescriptionDetail(prescriptionId: String) = "prescriptions_detail/$prescriptionId"

  const val PRESCRIPTION_EDIT = "prescriptions_edit/{$PRESCRIPTION_ID}"

  /** The [PRESCRIPTION_EDIT] screen of the prescription [prescriptionId]. */
  fun prescriptionEdit(prescriptionId: String) = "prescriptions_edit/$prescriptionId"

  // Route.CARE_CIRCLE
  const val CARE_CIRCLE = "care_circle_home"
  /** Name of the [CARE_CIRCLE_MEMBER] argument holding the member's uid. */
  const val CARE_CIRCLE_MEMBER_ARG = "memberUid"
  /** One member's details. Navigate with [careCircleMember], not this pattern. */
  const val CARE_CIRCLE_MEMBER = "care_circle_member/{$CARE_CIRCLE_MEMBER_ARG}"

  /** The [CARE_CIRCLE_MEMBER] screen of [memberUid]. Firebase uids are safe in a route. */
  fun careCircleMember(memberUid: String) = "care_circle_member/$memberUid"

  /** The form adding someone to the circle. */
  const val CARE_CIRCLE_ADD = "care_circle_add"
  /** Name of the [CARE_CIRCLE_INVITE] argument holding the invite code. */
  const val CARE_CIRCLE_INVITE_ARG = "inviteCode"
  /** One invitation and its invite code. Navigate with [careCircleInvite], not this pattern. */
  const val CARE_CIRCLE_INVITE = "care_circle_invite/{$CARE_CIRCLE_INVITE_ARG}"

  /**
   * The [CARE_CIRCLE_INVITE] screen of [inviteCode]. Invite codes use [A-Z2-9], safe in a route.
   */
  fun careCircleInvite(inviteCode: String) = "care_circle_invite/$inviteCode"

  // Route.PROFILE
  const val PROFILE = "profile_home"
  const val EDIT_PROFILE = "edit_profile"
}

/** Navigation helpers shared by all screens, so screens never use the NavController directly. */
open class NavigationActions(private val navController: NavHostController) {

  /** Opens [screen] on top of the current one. Back returns to the current screen. */
  open fun navigateTo(screen: String) {
    navController.navigate(screen) { launchSingleTop = true }
  }

  /**
   * Opens [screen] in place of the current one, so Back skips the current screen: e.g. after a form
   * is saved, Back from the result returns to where the form was opened, not to the filled form.
   * Does nothing before the graph is set: there is no screen to replace yet.
   */
  open fun navigateReplacing(screen: String) {
    val current = navController.currentDestination?.id ?: return
    navController.navigate(screen) { popUpTo(current) { inclusive = true } }
  }

  /**
   * Opens [route] and clears the whole back stack, so Back can't return to the previous flow (e.g.
   * after signing in or finishing onboarding). Not for switching between bottom-bar tabs, which
   * should keep each tab's state.
   */
  open fun navigateAndClearBackStack(route: String) {
    // Saved tab stacks also own ViewModels; never carry them across authentication flows.
    listOf(Route.OVERVIEW, Route.PLANNING, Route.EVENTS).forEach { tab ->
      navController.clearBackStack(tab)
    }
    navController.navigate(route) {
      popUpTo(navController.graph.id) { inclusive = true }
      launchSingleTop = true
    }
  }

  /**
   * Switches to the bottom-bar tab [route] (Overview, Planning or Events). Tabs never stack: only
   * Overview stays below the current tab, so Back from Planning or Events returns to Overview, and
   * re-selecting the current tab does nothing. Each tab keeps its state when you switch away.
   */
  open fun navigateToTab(route: String) {
    navController.navigate(route) {
      popUpTo(Screen.OVERVIEW) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  /**
   * Returns to the previous screen. Does nothing on the first screen of the back stack, so the
   * NavHost is never left empty (blank screen).
   */
  open fun goBack() {
    if (navController.previousBackStackEntry != null) navController.popBackStack()
  }

  /** The current screen's [Screen] constant, or an empty string before the graph is set. */
  open fun currentRoute(): String = navController.currentDestination?.route ?: ""
}
