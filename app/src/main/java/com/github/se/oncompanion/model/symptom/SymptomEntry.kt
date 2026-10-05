package com.github.se.oncompanion.model.symptom

import java.time.Instant

/** The symptoms the user can pick from (Figma: "Add symptom"). [OTHER] needs a label. */
enum class SymptomType {
  FATIGUE,
  NAUSEA,
  PAIN,
  APPETITE_LOSS,
  SLEEP_PROBLEMS,
  BREATHLESSNESS,
  OTHER,
}

/**
 * A symptom the user logged, stored at `/users/{uid}/symptoms/{id}`. The app only records what the
 * user entered: it never interprets the intensity.
 *
 * @property id the document ID; empty for an entry that isn't saved yet
 * @property type what the symptom is
 * @property otherLabel what the symptom is, typed by the user; set only when [type] is
 *   [SymptomType.OTHER]
 * @property intensity how strong it felt, from [MIN_INTENSITY] to [MAX_INTENSITY]
 * @property occurredAt when the user felt it (defaults to now when logging, can be earlier)
 * @property notes optional free text
 * @property createdAt set by the server when the entry is saved; `null` before that
 */
data class SymptomEntry(
    val id: String = "",
    val type: SymptomType,
    val intensity: Int,
    val occurredAt: Instant,
    val otherLabel: String? = null,
    val notes: String? = null,
    val createdAt: Instant? = null,
) {
  /** Whether the entry satisfies the same constraints as the Firestore security rules. */
  fun isValid(): Boolean =
      intensity in MIN_INTENSITY..MAX_INTENSITY &&
          (if (type == SymptomType.OTHER) {
            !otherLabel.isNullOrBlank() && otherLabel.length <= MAX_OTHER_LABEL_LENGTH
          } else {
            otherLabel == null
          }) &&
          (notes == null || notes.length <= MAX_NOTES_LENGTH)

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MIN_INTENSITY = 1
    /** Keep in sync with `firestore.rules`. */
    const val MAX_INTENSITY = 10
    /** Keep in sync with `firestore.rules`. */
    const val MAX_OTHER_LABEL_LENGTH = 50
    /** Keep in sync with `firestore.rules`. */
    const val MAX_NOTES_LENGTH = 1000
  }
}
