// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import java.net.URI
import java.net.URISyntaxException
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
 * @property websiteUrl the organizer's page where patients register, if any (see [webLinkOrNull])
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
    val websiteUrl: String? = null,
)

/**
 * [url] without surrounding spaces if it is a web address (`http` or `https` with a host), or
 * `null` otherwise. The event page opens this link in the browser, so a link typed wrong in the
 * console, or one that would open another app (`intent:`, `tel:`...), is left out.
 */
fun webLinkOrNull(url: String?): String? {
  val link = url?.trim() ?: return null
  return try {
    val uri = URI(link)
    link.takeIf { uri.scheme?.lowercase() in WEB_SCHEMES && !uri.host.isNullOrBlank() }
  } catch (_: URISyntaxException) {
    null
  }
}

private val WEB_SCHEMES = setOf("http", "https")
