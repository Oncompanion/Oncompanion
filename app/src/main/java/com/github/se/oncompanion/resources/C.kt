package com.github.se.oncompanion.resources

// Like R, but C
object C {
  object Tag {
    const val planning_week = "planning_week"
    const val planning_list = "planning_list"
    const val planning_previous = "planning_previous"
    const val planning_next = "planning_next"
    const val planning_today = "planning_today"
    const val planning_heading = "planning_heading"
    const val planning_empty = "planning_empty"
    const val planning_loading = "planning_loading"
    const val planning_error = "planning_error"
    const val planning_retry = "planning_retry"
    const val planning_add = "planning_add"

    fun planningDay(date: String) = "planning_day_$date"

    fun planningItem(key: String) = "planning_item_$key"

    const val main_screen_container = "main_screen_container"

    const val sign_in_screen = "sign_in_screen"
    const val onboarding_role_screen = "onboarding_role_screen"
    const val onboarding_information_screen = "onboarding_information_screen"
    const val overview_screen = "overview_screen"
    const val overview_shortcut_symptoms = "overview_shortcut_symptoms"
    const val overview_shortcut_prescriptions = "overview_shortcut_prescriptions"
    const val overview_shortcut_care_circle = "overview_shortcut_care_circle"
    const val overview_shortcut_profile = "overview_shortcut_profile"
    const val planning_screen = "planning_screen"
    const val events_screen = "events_screen"
    const val symptoms_screen = "symptoms_screen"
    const val prescriptions_screen = "prescriptions_screen"
    const val care_circle_screen = "care_circle_screen"
    const val profile_screen = "profile_screen"
  }
}
