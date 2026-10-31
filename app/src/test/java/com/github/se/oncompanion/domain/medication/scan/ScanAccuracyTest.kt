// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.domain.medication.scan.ScanAnswerKey.Written
import com.github.se.oncompanion.domain.medication.scan.ScanAnswerKey.same
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How well every test sheet is read, detail by detail, against [ScanAnswerKey]. The table is
 * printed in the test's output; the test fails if fewer details are read right than [READ_RIGHT],
 * or if more are wrong without being flagged than [WRONG_UNFLAGGED]. When the reading improves,
 * raise or lower those numbers in the same commit, so the score can only get better.
 */
class ScanAccuracyTest {
  private val today = LocalDate.of(2026, 11, 20)

  /** What happened to one detail of a sheet. */
  private enum class Outcome(val label: String) {
    /** Read right and not flagged: the patient has nothing to do. */
    RIGHT("right"),
    /** Flagged to check: the patient looks at it, whether it was read right or not. */
    FLAGGED("flagged"),
    /** Wrong and not flagged: the patient would trust it. */
    WRONG("wrong"),
    /** Part of a medication that wasn't read at all. */
    MISSED("missed"),
  }

  private data class Score(val outcomes: List<Outcome>, val extra: Int) {
    fun count(outcome: Outcome) = outcomes.count { it == outcome }
  }

  private fun outcome(right: Boolean, flagged: Boolean) =
      when {
        flagged -> Outcome.FLAGGED
        right -> Outcome.RIGHT
        else -> Outcome.WRONG
      }

  /**
   * Scores [read] against [written]. Each written medication is matched with the medication read
   * under the same name, else the one in its place; medications read but not written are [extra].
   */
  private fun score(written: Written?, read: ScannedPrescription?): Score {
    // Not a prescription: anything read is made up
    if (written == null) return Score(emptyList(), read?.draft?.medications?.size ?: 0)
    val details = 2 + 4 * written.medications.size
    if (read == null) return Score(List(details) { Outcome.MISSED }, 0)
    val toCheck = read.toCheck
    val outcomes = mutableListOf<Outcome>()
    outcomes +=
        outcome(
            same(written.doctor, read.draft.prescribedBy),
            ScannedField.PrescribedBy in toCheck,
        )
    outcomes +=
        outcome(written.date == read.draft.prescribedOn, ScannedField.PrescribedOn in toCheck)
    val unused = read.draft.medications.indices.toMutableList()
    written.medications.forEachIndexed { place, expected ->
      val i =
          unused.firstOrNull { same(expected.name, read.draft.medications[it].name) }
              ?: place.takeIf { it in unused }
      if (i == null) {
        repeat(4) { outcomes += Outcome.MISSED }
        return@forEachIndexed
      }
      unused -= i
      val actual = read.draft.medications[i]
      fun flagged(detail: MedicationDetail) = ScannedField.OfMedication(i, detail) in toCheck
      outcomes += outcome(same(expected.name, actual.name), flagged(MedicationDetail.NAME))
      outcomes += outcome(same(expected.dosage, actual.dosage), flagged(MedicationDetail.DOSAGE))
      outcomes +=
          outcome(same(expected.frequency, actual.frequency), flagged(MedicationDetail.FREQUENCY))
      outcomes += outcome(expected.days == actual.durationDays, flagged(MedicationDetail.DURATION))
    }
    return Score(outcomes, unused.size)
  }

  @Test
  fun everySheetIsReadAtLeastAsWellAsBefore() {
    val sheets = ScanAnswerKey.tuned + ScanAnswerKey.heldOut
    val scores = sheets.mapValues { (n, written) ->
      score(written, readPrescription(sheet(n), today))
    }
    printTable(scores)

    val all = Score(scores.values.flatMap { it.outcomes }, scores.values.sumOf { it.extra })
    assertTrue(
        "Only ${all.count(Outcome.RIGHT)} details read right, expected at least $READ_RIGHT",
        all.count(Outcome.RIGHT) >= READ_RIGHT,
    )
    assertTrue(
        "${all.count(Outcome.WRONG)} details wrong without being flagged, at most $WRONG_UNFLAGGED",
        all.count(Outcome.WRONG) <= WRONG_UNFLAGGED,
    )
  }

  private fun printTable(scores: Map<Int, Score>) {
    val columns = Outcome.entries.joinToString(" | ") { it.label }
    println("| Sheet | details | $columns | medications made up |")
    println("|---|---|${Outcome.entries.joinToString("") { "---|" }}---|")
    for ((n, score) in scores) {
      val counts = Outcome.entries.joinToString(" | ") { score.count(it).toString() }
      println("| %02d | ${score.outcomes.size} | $counts | ${score.extra} |".format(n))
    }
  }

  private companion object {
    /** Details read right and not flagged, on all the sheets together. */
    const val READ_RIGHT = 100

    /** Details wrong without being flagged: the ones the patient would trust. */
    const val WRONG_UNFLAGGED = 11
  }
}
