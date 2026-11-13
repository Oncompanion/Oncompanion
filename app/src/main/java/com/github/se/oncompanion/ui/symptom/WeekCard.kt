// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.full
import java.time.format.TextStyle
import java.util.Locale

/**
 * The "This week" card at the top of the journal (Figma: "Symptoms – History"): how many symptoms
 * were logged this calendar week and which one most often, then a bar per day whose height is the
 * highest intensity the user entered that day. Nothing is judged: the bars share one colour and the
 * app draws no conclusion from them.
 */
@Composable
fun WeekCard(summary: WeekSummary, modifier: Modifier = Modifier) {
  Surface(
      modifier = modifier.fillMaxWidth().testTag(C.Tag.symptom_journal_week),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(
          text = stringResource(R.string.symptom_week_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.semantics { heading() },
      )
      Text(
          text =
              pluralStringResource(
                  R.plurals.symptom_week_summary,
                  summary.entryCount,
                  summary.entryCount,
                  stringResource(summary.mostOften.label),
              ),
          style = MaterialTheme.typography.bodyMedium,
      )
      Text(
          text = stringResource(R.string.symptom_week_hint),
          style = MaterialTheme.typography.bodySmall,
      )
      WeekBars(summary.days, Modifier.padding(top = Spacing.md))
    }
  }
}

/** The seven days side by side, each a bar over its short weekday name. */
@Composable
private fun WeekBars(days: List<WeekDay>, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth()) { days.forEach { day -> DayBar(day, Modifier.weight(1f)) } }
}

/**
 * One day: a bar as high as its intensity, an open dot if something was logged without one, or a
 * short baseline if nothing was (or the day hasn't come yet). TalkBack reads the day and what was
 * logged, never a colour or a height.
 */
@Composable
private fun DayBar(day: WeekDay, modifier: Modifier = Modifier) {
  val dayName = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
  val description =
      when {
        day.intensity != null ->
            stringResource(
                R.string.symptom_week_day_intensity,
                dayName,
                stringResource(Severity.of(day.intensity).label),
            )
        day.hasEntries -> stringResource(R.string.symptom_week_day_no_intensity, dayName)
        else -> stringResource(R.string.symptom_week_day_nothing, dayName)
      }
  Column(
      modifier =
          modifier.testTag(C.Tag.symptomJournalWeekDay(day.date.toString())).semantics(
              mergeDescendants = true
          ) {
            contentDescription = description
          },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    // The bars all start on the same line, so their heights compare
    Box(Modifier.height(BAR_AREA_HEIGHT), contentAlignment = Alignment.BottomCenter) {
      val color = MaterialTheme.colorScheme.primary
      when {
        day.intensity != null ->
            Box(
                Modifier.width(BAR_WIDTH)
                    .height(barHeight(day.intensity))
                    .clip(MaterialTheme.shapes.small)
                    .background(color)
            )
        day.hasEntries ->
            Box(Modifier.size(DOT_SIZE).border(DOT_BORDER, color, MaterialTheme.shapes.full))
        else ->
            Box(
                Modifier.width(BAR_WIDTH)
                    .height(BASELINE_HEIGHT)
                    .clip(MaterialTheme.shapes.full)
                    .background(color.copy(alpha = BASELINE_ALPHA))
            )
      }
    }
    Text(
        // The bar says it all to TalkBack through the column's description
        text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
        style = MaterialTheme.typography.labelSmall,
        // One line at any font size: a day name is never split, it may use its neighbours' room
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.wrapContentWidth(unbounded = true).clearAndSetSemantics {},
    )
  }
}

/** The height of the bar of [intensity], from [MIN_BAR_HEIGHT] at 1 to [BAR_AREA_HEIGHT] at 10. */
private fun barHeight(intensity: Int): Dp =
    MIN_BAR_HEIGHT +
        (BAR_AREA_HEIGHT - MIN_BAR_HEIGHT) *
            ((intensity - SymptomEntry.MIN_INTENSITY).toFloat() /
                (SymptomEntry.MAX_INTENSITY - SymptomEntry.MIN_INTENSITY))

// The chart of the Figma's "This week" card
private val BAR_AREA_HEIGHT = 56.dp
private val MIN_BAR_HEIGHT = 12.dp
private val BAR_WIDTH = 20.dp
private val BASELINE_HEIGHT = 4.dp
private const val BASELINE_ALPHA = 0.35f
private val DOT_SIZE = 12.dp
private val DOT_BORDER = 1.5.dp
