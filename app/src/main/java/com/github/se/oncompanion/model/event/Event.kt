package com.github.se.oncompanion.model.event

import java.time.LocalDateTime

/**
 * A support event organized by the Ligue (workshop, support group, charity walk...).
 *
 * @property id unique identifier
 * @property title short name of the event
 * @property place where it takes place
 * @property startDateTime when it starts, in local time
 * @property endDateTime when it ends, if known
 * @property category kind of event shown above the title (e.g. "Wellbeing"), if any
 * @property summary one or two sentences shown in the list
 * @property description the full description shown in the event's detail
 */
data class Event(
    val id: String,
    val title: String,
    val place: String,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime? = null,
    val category: String? = null,
    val summary: String,
    val description: String,
)
