// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.AddedBy
import com.github.se.oncompanion.ui.common.EmptyLake
import com.github.se.oncompanion.ui.common.LARGE_FONT_SCALE
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.full
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Sun 4 Oct · 09:30". The app's UI is in English. */
private val appointmentFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** "08:00" */
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * The next appointment (Figma: "Appointment card"), or "No upcoming appointment": a white card with
 * the title, the place and when it is in a pill. Tapping it opens its detail; the empty card opens
 * Planning.
 *
 * @param modifier e.g. to overlap the header's lower edge
 */
@Composable
internal fun NextAppointmentCard(
    appointment: NextAppointment?,
    today: LocalDate,
    onOpenPlanning: () -> Unit,
    onOpenAppointment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
      onClick = {
        if (appointment == null) onOpenPlanning() else onOpenAppointment(appointment.id)
      },
      modifier =
          modifier
              .fillMaxWidth()
              .testTag(
                  if (appointment != null) C.Tag.overview_next_appointment
                  else C.Tag.overview_no_appointment
              ),
      shape = MaterialTheme.shapes.large,
      color = colors.surfaceContainerLowest,
      contentColor = colors.onSurfaceVariant,
  ) {
    Column(
        Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
      // Kept from before the Figma: without it, the card only says "Oncology check-up"
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.overview_next_appointment),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
      }
      if (appointment == null) {
        Text(
            text = stringResource(R.string.overview_no_appointment),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(R.string.overview_open_planning),
            style = MaterialTheme.typography.buttonLabel,
            color = MaterialTheme.lakeColors.primaryText,
            modifier = Modifier.padding(top = Spacing.sm),
        )
      } else {
        Text(
            text = appointment.title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        appointment.place?.let { place ->
          Text(text = place, style = MaterialTheme.typography.bodyMedium)
        }
        WhenPill(appointmentWhen(appointment, today))
      }
    }
  }
}

/** The Figma's height of the pill saying when the appointment is. */
private val WHEN_PILL_HEIGHT = 32.dp

/** "Tomorrow, Sun 4 Oct · 09:30" with a clock, in a pill (Figma: "When"). */
@Composable
private fun WhenPill(text: String) {
  Row(
      modifier =
          Modifier.padding(top = Spacing.sm)
              .heightIn(min = WHEN_PILL_HEIGHT)
              .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.full)
              .padding(start = Spacing.sm, end = Spacing.md),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Icon(
        painterResource(R.drawable.ic_schedule),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(WHEN_ICON_SIZE),
    )
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}

/** The Figma's clock in the pill. */
private val WHEN_ICON_SIZE = 18.dp

/** "Tomorrow, Sun 4 Oct · 09:30" ("Today, ..." / "Tomorrow, ..." when that's the case). */
@Composable
private fun appointmentWhen(appointment: NextAppointment, today: LocalDate): String {
  val formatted = appointment.dateTime.format(appointmentFormatter)
  return when (appointment.dateTime.toLocalDate()) {
    today -> stringResource(R.string.overview_today_at, formatted)
    today.plusDays(1) -> stringResource(R.string.overview_tomorrow_at, formatted)
    else -> formatted
  }
}

/**
 * "Today" with a link to Planning, and today's items in one white card (Figma: "Today list"), or
 * the Empty lake with a message.
 */
@Composable
internal fun TodaySection(entries: List<TodayEntry>, onOpenPlanning: () -> Unit) {
  Column {
    Row(
        modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = stringResource(R.string.overview_today),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
      )
      LakeButton(
          text = stringResource(R.string.overview_see_planning),
          onClick = onOpenPlanning,
          style = LakeButtonStyle.Text,
          modifier = Modifier.testTag(C.Tag.overview_see_planning),
      )
    }
    Spacer(Modifier.height(Spacing.md))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
      if (entries.isEmpty()) NothingToday()
      else {
        Column(
            modifier = Modifier.padding(vertical = Spacing.xs).testTag(C.Tag.overview_today_list),
        ) {
          entries.forEachIndexed { index, entry ->
            if (index > 0) {
              HorizontalDivider(
                  modifier = Modifier.padding(horizontal = Spacing.lg),
                  color = MaterialTheme.colorScheme.outlineVariant,
              )
            }
            TodayRow(entry)
          }
        }
      }
    }
  }
}

/** The Empty lake next to "Nothing planned today" (Figma: "Nothing today"). */
@Composable
private fun NothingToday() {
  Row(
      modifier = Modifier.padding(Spacing.lg).testTag(C.Tag.overview_today_empty),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
  ) {
    EmptyLake()
    Column {
      Text(
          text = stringResource(R.string.overview_nothing_today),
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          text = stringResource(R.string.overview_nothing_today_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The column of times, so titles line up (DESIGN.md: at least 52 dp, growing with the text). */
private val TIME_COLUMN_WIDTH = 52.dp

/** The Figma's filled circle of a done entry, inside its 48 dp target. */
private val CHECK_SIZE = 28.dp
private val CHECK_ICON_SIZE = 18.dp

/**
 * One of today's entries (Figma: "Today row"): time (or "No set time"), icon, title, detail and,
 * for a timed entry, its status. The kind's icon is kept, unlike the Figma: the detail line doesn't
 * say whether it's a medication, an event or an appointment.
 */
@Composable
private fun TodayRow(entry: TodayEntry) {
  val colors = MaterialTheme.colorScheme
  val done = entry.status == TodayItemStatus.DONE
  // At a large font (as for the header's band), the time goes above the title, so a long name
  // isn't cut in the middle
  val stacked = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = Spacing.lg, vertical = Spacing.md)
              .testTag(C.Tag.overviewTodayItem(entry.key)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    val time =
        @Composable { modifier: Modifier ->
          Text(
              text =
                  entry.time?.format(timeFormatter)
                      ?: stringResource(R.string.overview_no_set_time),
              style = MaterialTheme.typography.titleMedium,
              color = if (done) colors.onSurfaceVariant else colors.onSurface,
              modifier = modifier,
          )
        }
    if (!stacked) time(Modifier.widthIn(min = TIME_COLUMN_WIDTH))
    Box(
        modifier = Modifier.size(40.dp).background(colors.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painter = painterResource(entry.kind.icon()),
          contentDescription = null,
          tint = colors.onSecondaryContainer,
      )
    }
    Column(Modifier.weight(1f)) {
      if (stacked) time(Modifier)
      Text(
          text = entry.title,
          style = MaterialTheme.typography.titleMedium,
          color = if (done) colors.onSurfaceVariant else colors.onSurface,
      )
      entry.subtitle?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
      }
      entry.notConfirmedAddedBy?.let { AddedBy(it, isConfirmed = false) }
    }
    when (entry.status) {
      TodayItemStatus.DONE -> DoneCheck()
      TodayItemStatus.NEXT ->
          StatusChip(
              text = stringResource(R.string.overview_next),
              container = colors.tertiaryContainer,
              content = colors.onTertiaryContainer,
          )
      TodayItemStatus.NOW ->
          StatusChip(
              text = stringResource(R.string.overview_now),
              container = colors.primaryContainer,
              content = colors.onPrimaryContainer,
          )
      // No status for an entry without a time, and no empty circle for one still to come: the app
      // never decides whether a medication was taken
      TodayItemStatus.LATER,
      null -> {}
    }
  }
}

/** A done entry's check, white in a filled circle (Figma: "Check"). */
@Composable
private fun DoneCheck() {
  Box(
      modifier =
          Modifier.size(CHECK_SIZE).background(MaterialTheme.lakeColors.primaryText, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = stringResource(R.string.overview_done),
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(CHECK_ICON_SIZE),
    )
  }
}

@Composable
private fun StatusChip(text: String, container: Color, content: Color) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = content,
      modifier =
          Modifier.background(container, MaterialTheme.shapes.small)
              .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
  )
}

private fun TodayItemKind.icon(): Int =
    when (this) {
      TodayItemKind.MEDICATION -> R.drawable.ic_medication
      TodayItemKind.EVENT -> R.drawable.ic_event
      TodayItemKind.APPOINTMENT -> R.drawable.ic_calendar_month
    }
