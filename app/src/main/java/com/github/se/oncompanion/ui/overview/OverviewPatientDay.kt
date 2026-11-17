// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.elevation1
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "10:00" */
private val nextUpTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** "Thu 8 Oct · 10:00", for a later day. The app's UI is in English. */
private val nextUpDayFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/**
 * The patient's day on the Overview (Overview UX Refactor): "Next up", then today's list until the
 * medications card replaces it. Without a next event or appointment, the empty appointment card
 * still invites to add one.
 *
 * @param modifier for the first card, e.g. to overlap the header's lower edge
 */
@Composable
internal fun PatientDay(
    uiState: OverviewUiState,
    onOpenPlanning: () -> Unit,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val nextUp = uiState.nextUp
  if (nextUp != null) {
    NextUpCard(
        nextUp,
        uiState.now.toLocalDate(),
        onOpen = {
          if (nextUp.kind == TodayItemKind.EVENT) onOpenEvent(nextUp.id)
          else onOpenAppointment(nextUp.id)
        },
        modifier = modifier,
    )
  } else {
    // Without a next event, there is no next appointment either
    NextAppointmentCard(
        null,
        uiState.now.toLocalDate(),
        onOpenPlanning,
        onOpenAppointment,
        modifier = modifier.elevation1(MaterialTheme.shapes.large),
    )
  }
  TodaySection(uiState.todayEntries, onOpenPlanning)
}

/** A white card of the patient's day, raised with the shared shadow; tapping it opens [onClick]. */
@Composable
private fun DayCard(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
  val shape = MaterialTheme.shapes.large
  Surface(
      onClick = onClick,
      modifier = modifier.fillMaxWidth().elevation1(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      content = content,
  )
}

/** The card's icon, white in a teal disc. */
@Composable
private fun CardBadge(@DrawableRes icon: Int) {
  Box(
      Modifier.size(BADGE_SIZE).background(MaterialTheme.colorScheme.primary, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(BADGE_ICON_SIZE),
    )
  }
}

/** The small title at the top of a card ("Next up", "Today"): the Figma's 14/18 eyebrow. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
  Text(
      text,
      style = MaterialTheme.typography.cardEyebrow,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier,
  )
}

/**
 * "Next up": the event or appointment, when it starts (with its day if not today) and where.
 * Tapping it opens its page.
 */
@Composable
private fun NextUpCard(
    nextUp: NextUp,
    today: LocalDate,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
  DayCard(modifier.testTag(C.Tag.overview_next_up), onClick = onOpen) {
    Row(
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      CardBadge(R.drawable.ic_clock)
      Column(Modifier.weight(1f)) {
        Eyebrow(stringResource(R.string.overview_next_up))
        Text(
            nextUp.title,
            style = MaterialTheme.typography.cardTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            nextUpWhen(nextUp, today),
            style = MaterialTheme.typography.cardDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** "10:00 · Wellness center" today; "Tomorrow, Sun 4 Oct · 10:00" or "Thu 8 Oct · 10:00" later. */
@Composable
private fun nextUpWhen(nextUp: NextUp, today: LocalDate): String {
  val start = nextUp.dateTime
  val time =
      when (start.toLocalDate()) {
        today -> start.format(nextUpTimeFormatter)
        today.plusDays(1) ->
            stringResource(R.string.overview_tomorrow_at, start.format(nextUpDayFormatter))
        else -> start.format(nextUpDayFormatter)
      }
  val place = nextUp.place?.takeIf { it.isNotBlank() } ?: return time
  return stringResource(R.string.overview_with_place, time, place)
}

/** The Figma's teal disc behind each card's icon, and the icon in it. */
private val BADGE_SIZE = 36.dp
private val BADGE_ICON_SIZE = 18.dp

/** The Figma's card eyebrow ("Next up"): SemiBold 14/18. */
private val Typography.cardEyebrow: TextStyle
  get() = labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.015.em)

/** The Figma's card title ("Gentle yoga for patients"): SemiBold 18/24. */
private val Typography.cardTitle: TextStyle
  get() = titleMedium.copy(fontSize = 18.sp)

/** The Figma's card detail ("10:00 · Wellness center"): Regular 15/20. */
private val Typography.cardDetail: TextStyle
  get() = bodyMedium.copy(lineHeight = 20.sp)
