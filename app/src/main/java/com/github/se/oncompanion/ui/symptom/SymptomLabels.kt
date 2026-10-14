// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The label of a symptom type, as shown in the app (Figma: "Add symptom"). */
@get:StringRes
val SymptomType.label: Int
  get() =
      when (this) {
        SymptomType.FATIGUE -> R.string.symptom_type_fatigue
        SymptomType.NAUSEA -> R.string.symptom_type_nausea
        SymptomType.PAIN -> R.string.symptom_type_pain
        SymptomType.FEVER -> R.string.symptom_type_fever
        SymptomType.HEADACHE -> R.string.symptom_type_headache
        SymptomType.APPETITE_LOSS -> R.string.symptom_type_appetite_loss
        SymptomType.SLEEP_PROBLEMS -> R.string.symptom_type_sleep_problems
        SymptomType.BREATHLESSNESS -> R.string.symptom_type_breathlessness
        SymptomType.OTHER -> R.string.symptom_type_other
      }

/** The label of a severity level, as shown in the journal (Figma: "Symptoms – History"). */
@get:StringRes
val Severity.label: Int
  get() =
      when (this) {
        Severity.MILD -> R.string.symptom_severity_mild
        Severity.MODERATE -> R.string.symptom_severity_moderate
        Severity.SEVERE -> R.string.symptom_severity_severe
      }

/** "Mon 28 Sep", as in the mockups. The app's UI is in English. */
private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "Mon 28 Sep 2025", for a day of another year. */
private val dayWithYearFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

/** "14:30", the time of day shown by the symptom screens. */
internal val symptomTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * "Today", "Yesterday" or the day of [date], seen on [today]; with its year if it isn't this year.
 */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String =
    when {
      date == today -> stringResource(R.string.symptom_journal_today)
      date == today.minusDays(1) -> stringResource(R.string.symptom_journal_yesterday)
      date.year == today.year -> date.format(dayFormatter)
      else -> date.format(dayWithYearFormatter)
    }

/** What the entry is about: the labels of its symptoms, e.g. "Fatigue, Headache". */
@Composable
fun SymptomEntry.title(): String = types.map { stringResource(it.label) }.joinToString(", ")
