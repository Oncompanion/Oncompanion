package com.github.se.oncompanion.model.symptom

/**
 * How strong a symptom felt, in words: the usual bands of a 0–10 symptom scale. It only restates
 * the intensity the user entered; the app never draws a conclusion from it.
 */
enum class Severity {
  /** Intensity 1 to 3. */
  MILD,
  /** Intensity 4 to 6. */
  MODERATE,
  /** Intensity 7 to 10. */
  SEVERE;

  companion object {
    /** Highest intensity that is [MILD]. */
    const val MAX_MILD = 3
    /** Highest intensity that is [MODERATE]. */
    const val MAX_MODERATE = 6

    /**
     * The severity of [intensity] (from [SymptomEntry.MIN_INTENSITY] to
     * [SymptomEntry.MAX_INTENSITY]).
     */
    fun of(intensity: Int): Severity =
        when {
          intensity <= MAX_MILD -> MILD
          intensity <= MAX_MODERATE -> MODERATE
          else -> SEVERE
        }
  }
}

/** The [Severity] of this entry's intensity. */
val SymptomEntry.severity: Severity
  get() = Severity.of(intensity)
