// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.profile

import androidx.compose.runtime.Composable
import com.github.se.oncompanion.ui.common.rememberDayFormatter
import java.time.LocalDate

/**
 * [date] as the Profile and Edit profile screens show it: a calendar day in the phone's language
 * with a short month, e.g. "12 Mar 1968" on a British phone. A calendar day has no time zone, so it
 * is never converted.
 */
@Composable
internal fun dateOfBirthText(date: LocalDate): String =
    date.format(rememberDayFormatter("dMMMyyyy"))
