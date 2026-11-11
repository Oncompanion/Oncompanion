// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

/**
 * A patient's confirmation that they registered for an event with its organizer (US-25), and who
 * saved it when a caregiver did (US-42), so the page can show "Added by Sam".
 *
 * @property addedBy the uid of the caregiver who confirmed it for the patient, or `null` when the
 *   patient did
 * @property addedByName that caregiver's first name
 */
data class EventRegistration(val addedBy: String? = null, val addedByName: String? = null)
