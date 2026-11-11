// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.LocalDate
import java.time.LocalTime

/**
 * A symptom drafted from what the patient said, in the fields of the add symptom form, so it fills
 * the form and the patient checks it before saving. Anything not understood stays empty for the
 * patient to fill in; nothing here is stored.
 *
 * @property types the symptoms said, in the order they were first said
 * @property intensity the intensity said on a scale of 10, or `null`
 * @property date the day it was felt, or `null` for today (as in the form)
 * @property time the time it was felt, or `null` for the current time (as in the form)
 * @property notes everything that was said, so nothing is lost
 */
data class SymptomDraft(
    val types: List<SymptomType> = emptyList(),
    val intensity: Int? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val notes: String = "",
)
