package com.github.se.oncompanion.model.symptom

import java.time.Duration
import java.time.Instant

/**
 * The symptoms the user can pick from (Figma: "Log symptoms"). With [OTHER], the notes say which
 * symptom it was.
 */
enum class SymptomType {
  FATIGUE,
  NAUSEA,
  PAIN,
  FEVER,
  HEADACHE,
  APPETITE_LOSS,
  SLEEP_PROBLEMS,
  BREATHLESSNESS,
  OTHER,
}

/**
 * What the user logged at one moment, stored at `/users/{uid}/symptoms/{id}`: one or more symptoms
 * felt together, how bad they felt overall and notes. The app only records what the user entered:
 * it never interprets the intensity.
 *
 * @property id the document ID; empty for an entry that isn't saved yet
 * @property types the symptoms felt, at least one and each at most once, in the order the user
 *   picked them
 * @property intensity how bad the user felt overall, from [MIN_INTENSITY] to [MAX_INTENSITY], or
 *   `null` if they didn't say
 * @property occurredAt when the user felt it (defaults to now when logging, can be earlier but not
 *   later, see [isValid])
 * @property notes free text; required with [SymptomType.OTHER], to say which symptom it was
 * @property createdAt set by the server when the entry is saved; `null` before that
 */
data class SymptomEntry(
    val id: String = "",
    val types: List<SymptomType>,
    val occurredAt: Instant,
    val intensity: Int? = null,
    val notes: String? = null,
    val createdAt: Instant? = null,
) {
  /**
   * Whether the entry satisfies the same constraints as the Firestore security rules at [now]. A
   * symptom can't be in the future (it would stay at the top of the journal), give or take
   * [MAX_CLOCK_DRIFT].
   */
  fun isValid(now: Instant = Instant.now()): Boolean =
      types.isNotEmpty() &&
          types.distinct().size == types.size &&
          (intensity == null || intensity in MIN_INTENSITY..MAX_INTENSITY) &&
          !occurredAt.isAfter(now.plus(MAX_CLOCK_DRIFT)) &&
          (notes == null || notes.length <= MAX_NOTES_LENGTH) &&
          // "Other" only says something was felt: the notes say what
          (SymptomType.OTHER !in types || !notes.isNullOrBlank())

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MIN_INTENSITY = 1
    /** Keep in sync with `firestore.rules`. */
    const val MAX_INTENSITY = 10
    /** Keep in sync with `firestore.rules`. */
    const val MAX_NOTES_LENGTH = 1000
    /**
     * How far [occurredAt] may be after the current time, for a phone clock slightly ahead of the
     * server's. Keep in sync with `firestore.rules`.
     */
    val MAX_CLOCK_DRIFT: Duration = Duration.ofMinutes(5)
  }
}
