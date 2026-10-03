package com.github.se.oncompanion.resources

// Like R, but C
object C {
  object Tag {
    const val planning_screen = "planning_screen"
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

    const val greeting = "main_screen_greeting"
    const val greeting_robo = "second_screen_greeting"

    const val main_screen_container = "main_screen_container"
    const val second_screen_container = "second_screen_container"
  }
}
