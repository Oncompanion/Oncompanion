package com.github.se.oncompanion.resources

// Like R, but C
object C {
  object Tag {
    const val main_screen_container = "main_screen_container"

    const val sign_in_screen = "sign_in_screen"
    const val onboarding_role_screen = "onboarding_role_screen"
    const val onboarding_information_screen = "onboarding_information_screen"
    const val overview_screen = "overview_screen"
    const val overview_shortcut_symptoms = "overview_shortcut_symptoms"
    const val overview_shortcut_prescriptions = "overview_shortcut_prescriptions"
    const val overview_shortcut_care_circle = "overview_shortcut_care_circle"
    const val overview_shortcut_profile = "overview_shortcut_profile"

    const val bottom_navigation_bar = "bottom_navigation_bar"
    const val bottom_navigation_tab_overview = "bottom_navigation_tab_overview"
    const val bottom_navigation_tab_planning = "bottom_navigation_tab_planning"
    const val bottom_navigation_tab_events = "bottom_navigation_tab_events"
    const val planning_screen = "planning_screen"
    const val events_screen = "events_screen"
    const val events_title = "events_title"
    const val events_empty_state = "events_empty_state"
    const val events_list = "events_list"
    const val events_loading = "events_loading"
    const val events_error = "events_error"
    const val events_retry = "events_retry"
    const val event_detail_screen = "event_detail_screen"
    const val event_detail_back = "event_detail_back"
    const val event_detail_loading = "event_detail_loading"
    const val event_detail_error = "event_detail_error"
    const val event_detail_retry = "event_detail_retry"
    const val event_detail_not_found = "event_detail_not_found"
    const val event_detail_category = "event_detail_category"
    const val event_detail_event_title = "event_detail_event_title"
    const val event_detail_date_time = "event_detail_date_time"
    const val event_detail_location = "event_detail_location"
    const val event_detail_description = "event_detail_description"

    fun eventCard(eventId: String) = "event_card_$eventId"

    const val symptoms_screen = "symptoms_screen"
    const val prescriptions_screen = "prescriptions_screen"
    const val care_circle_screen = "care_circle_screen"
    const val profile_screen = "profile_screen"
  }
}
