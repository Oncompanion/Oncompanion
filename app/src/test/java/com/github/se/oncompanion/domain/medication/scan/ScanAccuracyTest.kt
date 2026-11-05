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
 * printed in the test's output; the test fails if a sheet gets worse than its [BASELINE]: fewer
 * details read right, more wrong without being flagged, or a medication missed or made up. Each
 * sheet is checked on its own, so a gain on one can't hide a loss on another. When the reading
 * improves, update the baseline in the same commit, so the score can only get better.
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

    val worse = scores.mapNotNull { (n, score) ->
      val baseline = BASELINE.getValue(n)
      listOfNotNull(
              "${score.count(Outcome.RIGHT)} right instead of ${baseline.right}"
                  .takeIf { score.count(Outcome.RIGHT) < baseline.right },
              "${score.count(Outcome.WRONG)} wrong without a flag instead of ${baseline.wrong}"
                  .takeIf { score.count(Outcome.WRONG) > baseline.wrong },
              "${score.count(Outcome.MISSED)} details of missed medications"
                  .takeIf { score.count(Outcome.MISSED) > 0 },
              "${score.extra} medications made up".takeIf { score.extra > 0 },
          )
          .takeIf { it.isNotEmpty() }
          ?.joinToString(", ", prefix = "sheet %02d: ".format(n))
    }
    assertTrue("Read worse than before:\n${worse.joinToString("\n")}", worse.isEmpty())
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

  /** What a sheet must still reach: details [right] at least, [wrong] without a flag at most. */
  private data class Baseline(val right: Int, val wrong: Int = 0)

  private companion object {
    /**
     * Per sheet, the score of the last change. No sheet may miss or make up a medication: 11, the
     * drink label, must give none.
     */
    val BASELINE =
        mapOf(
            1 to Baseline(right = 10),
            2 to Baseline(right = 8),
            3 to Baseline(right = 10),
            4 to Baseline(right = 14),
            5 to Baseline(right = 10),
            6 to Baseline(right = 8),
            7 to Baseline(right = 11),
            8 to Baseline(right = 13),
            9 to Baseline(right = 15),
            10 to Baseline(right = 18, wrong = 1),
            11 to Baseline(right = 0),
        )
  }
}
