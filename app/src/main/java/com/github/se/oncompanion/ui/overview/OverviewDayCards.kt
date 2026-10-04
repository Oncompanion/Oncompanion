package com.github.se.oncompanion.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.overview.NextAppointment
import com.github.se.oncompanion.model.overview.TodayItemKind
import com.github.se.oncompanion.resources.C
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Sun 4 Oct · 09:30". The app's UI is in English. */
private val appointmentFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** "08:00" */
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * The next appointment (Figma: "Next appointment card"), or "No upcoming appointment". Tapping it
 * opens Planning.
 */
@Composable
internal fun NextAppointmentCard(
    appointment: NextAppointment?,
    today: LocalDate,
    onOpenPlanning: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val upcoming = appointment != null
  val content = if (upcoming) colors.onPrimaryContainer else colors.onSurfaceVariant
  Surface(
      onClick = onOpenPlanning,
      modifier =
          Modifier.fillMaxWidth()
              .testTag(
                  if (upcoming) C.Tag.overview_next_appointment else C.Tag.overview_no_appointment
              ),
      shape = RoundedCornerShape(16.dp),
      color = if (upcoming) colors.primaryContainer else colors.surfaceContainerLow,
      contentColor = content,
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(R.string.overview_no_appointment_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.overview_open_planning),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
        )
      } else {
        Text(text = appointment.title, style = MaterialTheme.typography.headlineSmall)
        appointment.doctor?.let {
          Text(
              text = stringResource(R.string.overview_appointment_with, it),
              style = MaterialTheme.typography.bodyMedium,
          )
        }
        IconLine(R.drawable.ic_schedule) {
          Text(
              text = appointmentWhen(appointment, today),
              style = MaterialTheme.typography.titleMedium,
          )
        }
        appointment.place?.let { place ->
          IconLine(R.drawable.ic_location) {
            Text(text = place, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
  }
}

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

@Composable
private fun IconLine(icon: Int, content: @Composable () -> Unit) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(painterResource(icon), contentDescription = null)
    content()
  }
}

/** "Today" with a link to Planning, and today's items or an empty message. */
@Composable
internal fun TodaySection(entries: List<TodayEntry>, onOpenPlanning: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = stringResource(R.string.overview_today),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
      )
      TextButton(
          onClick = onOpenPlanning,
          modifier = Modifier.testTag(C.Tag.overview_see_planning),
      ) {
        Text(stringResource(R.string.overview_see_planning))
      }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
      if (entries.isEmpty()) {
        Column(
            modifier =
                Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                    .testTag(C.Tag.overview_today_empty),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
              text = stringResource(R.string.overview_nothing_today),
              style = MaterialTheme.typography.titleMedium,
          )
          Text(
              text = stringResource(R.string.overview_nothing_today_hint),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
          )
        }
      } else {
        Column(
            modifier = Modifier.padding(vertical = 4.dp).testTag(C.Tag.overview_today_list),
        ) {
          entries.forEach { TodayRow(it) }
        }
      }
    }
  }
}

/** One of today's items (Figma: "Today item"): time, icon, title, detail and its status. */
@Composable
private fun TodayRow(entry: TodayEntry) {
  val colors = MaterialTheme.colorScheme
  val item = entry.item
  val done = entry.status == TodayItemStatus.DONE
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp)
              .testTag(C.Tag.overviewTodayItem(item.id)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        text = item.time.format(timeFormatter),
        style = MaterialTheme.typography.labelMedium,
        color = if (done) colors.onSurfaceVariant else colors.onSurface,
        // At least 40dp so times line up, wider if the user enlarged the font
        modifier = Modifier.widthIn(min = 40.dp),
    )
    Box(
        modifier = Modifier.size(40.dp).background(colors.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painter = painterResource(item.kind.icon()),
          contentDescription = null,
          tint = colors.onSecondaryContainer,
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          text = item.title,
          style = MaterialTheme.typography.titleMedium,
          color = if (done) colors.onSurfaceVariant else colors.onSurface,
      )
      item.subtitle?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
    }
    when (entry.status) {
      TodayItemStatus.DONE ->
          Icon(
              painter = painterResource(R.drawable.ic_check_circle),
              contentDescription = stringResource(R.string.overview_done),
              tint = colors.primary,
          )
      TodayItemStatus.NEXT ->
          StatusChip(
              text = stringResource(R.string.overview_next),
              container = colors.tertiaryContainer,
              content = colors.onTertiaryContainer,
          )
      // Neutral, not alarming: the app only reports what the user entered
      TodayItemStatus.NOT_TAKEN ->
          StatusChip(
              text = stringResource(R.string.overview_not_taken),
              container = colors.surfaceContainerHighest,
              content = colors.onSurfaceVariant,
          )
      TodayItemStatus.LATER -> {}
    }
  }
}

@Composable
private fun StatusChip(text: String, container: Color, content: Color) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = content,
      modifier =
          Modifier.background(container, RoundedCornerShape(8.dp))
              .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

private fun TodayItemKind.icon(): Int =
    when (this) {
      TodayItemKind.MEDICATION -> R.drawable.ic_medication
      TodayItemKind.EVENT -> R.drawable.ic_event
      TodayItemKind.APPOINTMENT -> R.drawable.ic_calendar_month
    }
