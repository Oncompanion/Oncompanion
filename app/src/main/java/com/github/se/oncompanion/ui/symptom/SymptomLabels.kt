package com.github.se.oncompanion.ui.symptom

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType

/** The label of a symptom type, as shown in the app (Figma: "Add symptom"). */
@get:StringRes
val SymptomType.label: Int
  get() =
      when (this) {
        SymptomType.FATIGUE -> R.string.symptom_type_fatigue
        SymptomType.NAUSEA -> R.string.symptom_type_nausea
        SymptomType.PAIN -> R.string.symptom_type_pain
        SymptomType.APPETITE_LOSS -> R.string.symptom_type_appetite_loss
        SymptomType.SLEEP_PROBLEMS -> R.string.symptom_type_sleep_problems
        SymptomType.BREATHLESSNESS -> R.string.symptom_type_breathlessness
        SymptomType.OTHER -> R.string.symptom_type_other
      }

/** What the entry is about: the label the user typed for "Other", or else the type's label. */
@Composable
fun SymptomEntry.title(): String =
    otherLabel?.takeIf { type == SymptomType.OTHER && it.isNotBlank() }
        ?: stringResource(type.label)
