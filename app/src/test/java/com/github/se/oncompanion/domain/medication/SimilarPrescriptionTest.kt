// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [similarPrescription]: when a new prescription repeats a saved one (US-39). */
class SimilarPrescriptionTest {
  private val october = LocalDate.of(2026, 10, 14)
  private val september = LocalDate.of(2026, 9, 14)

  private fun saved(
      id: String,
      on: LocalDate = october,
      by: String? = "Dre Claire Fontaine",
      vararg medications: Pair<String, String?> =
          arrayOf("Tamoxifène 20 mg" to "1 comprimé", "Pantoprazole 40 mg" to null),
  ) =
      Prescription(
          id = id,
          prescribedBy = by,
          prescribedOn = on,
          medications =
              medications.mapIndexed { i, (name, dosage) ->
                Medication("$id-$i", id, name, dosage, startDate = on)
              },
      )

  private fun draft(
      on: LocalDate = october,
      by: String = "Dre Claire Fontaine",
      vararg medications: Pair<String, String> =
          arrayOf("Tamoxifène 20 mg" to "1 comprimé", "Pantoprazole 40 mg" to ""),
  ) =
      PrescriptionDraft(
          prescribedBy = by,
          prescribedOn = on,
          medications =
              medications.map { (name, dosage) ->
                MedicationDraft(name = name, dosage = dosage, startDate = on)
              },
      )

  @Test
  fun theSamePrescriptionWrittenDifferentlyIsTheSame() {
    val drafts =
        listOf(
            draft(),
            // Other order, case, accents, spaces and punctuation
            draft(
                by = "dre claire fontaine.",
                medications =
                    arrayOf("PANTOPRAZOLE 40mg" to " ", "Tamoxifene  20 MG" to "1 Comprimé"),
            ),
        )
    for (draft in drafts) {
      assertEquals(
          draft.toString(),
          SimilarPrescription.Same(saved("a")),
          similarPrescription(draft, listOf(saved("a"))),
      )
    }
  }

  @Test
  fun theSameMedicationsOnAnotherDateAreARenewal_theMostRecentOne() {
    val older = saved("old", on = LocalDate.of(2026, 6, 1))
    val recent = saved("recent", on = september)
    assertEquals(
        SimilarPrescription.Renewal(recent),
        similarPrescription(draft(), listOf(older, recent)),
    )
    // The same one comes first, even with a renewal saved too
    assertEquals(
        SimilarPrescription.Same(saved("a")),
        similarPrescription(draft(), listOf(recent, saved("a"))),
    )
  }

  @Test
  fun aDifferentMedicationDosageOrPrescriberIsNothing() {
    val different =
        listOf(
            draft(
                medications =
                    arrayOf("Tamoxifène 20 mg" to "2 comprimés", "Pantoprazole 40 mg" to "")
            ),
            draft(medications = arrayOf("Tamoxifène 20 mg" to "1 comprimé")),
            draft(
                medications =
                    arrayOf(
                        "Tamoxifène 20 mg" to "1 comprimé",
                        "Pantoprazole 40 mg" to "",
                        "Ondansétron 8 mg" to "",
                    )
            ),
            draft(
                medications =
                    arrayOf("Tamoxifène 10 mg" to "1 comprimé", "Pantoprazole 40 mg" to "")
            ),
            // Same day and medications, another doctor: not the same paper, nor a renewal
            draft(by = "Dr Marc Dubois"),
            draft(by = ""),
            // A dosage where the saved one has none
            draft(
                medications =
                    arrayOf("Tamoxifène 20 mg" to "1 comprimé", "Pantoprazole 40 mg" to "1 gélule")
            ),
        )
    for (draft in different) {
      assertNull(draft.toString(), similarPrescription(draft, listOf(saved("a"))))
    }
  }

  @Test
  fun thePrescriptionBeingCorrectedIsNeverComparedWithItself() {
    assertNull(similarPrescription(draft(), listOf(saved("a")), editedId = "a"))
    assertEquals(
        SimilarPrescription.Same(saved("b")),
        similarPrescription(draft(), listOf(saved("a"), saved("b")), editedId = "a"),
    )
  }

  @Test
  fun nothingSavedIsNothingSimilar() {
    assertNull(similarPrescription(draft(), emptyList()))
  }

  @Test
  fun noPrescriberMatchesNoPrescriber() {
    assertEquals(
        SimilarPrescription.Same(saved("a", by = null)),
        similarPrescription(draft(by = "  "), listOf(saved("a", by = null))),
    )
  }
}
