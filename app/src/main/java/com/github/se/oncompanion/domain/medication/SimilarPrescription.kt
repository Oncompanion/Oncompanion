// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.scan.comparable

/** A saved prescription that a new one repeats (US-39). */
sealed interface SimilarPrescription {
  val prescription: Prescription

  /** Same date, prescriber and medications: most likely the same paper, saved twice. */
  data class Same(override val prescription: Prescription) : SimilarPrescription

  /** Same medications on another date: most likely a renewal, which is saved as a new one. */
  data class Renewal(override val prescription: Prescription) : SimilarPrescription
}

/**
 * The saved prescription [draft] repeats, if any. Medications are compared by name and dosage,
 * ignoring case, accents, spaces and punctuation, and in any order; the prescriber the same way.
 * [Same][SimilarPrescription.Same] comes first; otherwise the most recent
 * [renewal][SimilarPrescription.Renewal].
 *
 * @param saved the prescriptions already saved
 * @param editedId the saved prescription [draft] corrects, if any: never compared with itself
 */
fun similarPrescription(
    draft: PrescriptionDraft,
    saved: List<Prescription>,
    editedId: String? = null,
): SimilarPrescription? {
  val medications = draft.medications.map { key(it.name, it.dosage) }.sorted()
  val withSameMedications = saved.filter { prescription ->
    prescription.id != editedId &&
        prescription.medications.map { key(it.name, it.dosage.orEmpty()) }.sorted() == medications
  }
  withSameMedications
      .firstOrNull {
        it.prescribedOn == draft.prescribedOn &&
            words(it.prescribedBy.orEmpty()) == words(draft.prescribedBy)
      }
      ?.let {
        return SimilarPrescription.Same(it)
      }
  return withSameMedications
      .filter { it.prescribedOn != draft.prescribedOn }
      .maxByOrNull { it.prescribedOn }
      ?.let { SimilarPrescription.Renewal(it) }
}

/** A medication as compared: its name and dosage, as [words]. */
private fun key(name: String, dosage: String) = words(name) + "|" + words(dosage)

/** Only the letters and digits of [text], lowercase and without accents: "8 mg" is "8mg". */
private fun words(text: String) = comparable(text).filter { it.isLetterOrDigit() }
