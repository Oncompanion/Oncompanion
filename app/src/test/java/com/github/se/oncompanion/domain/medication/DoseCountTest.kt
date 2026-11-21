// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseCountTest {

  /** Checks each frequency gives its count, with the frequency in the failure message. */
  private fun assertCounts(cases: List<Pair<String?, Int?>>) =
      cases.forEach { (frequency, expected) ->
        assertEquals("\"$frequency\"", expected, DoseCount.of(frequency))
      }

  @Test
  fun englishWordings_giveTheirCount() =
      assertCounts(
          listOf(
              "Once a day" to 1,
              "once daily" to 1,
              "Twice a day" to 2,
              "twice daily" to 2,
              "Three times a day" to 3,
              "four times per day" to 4,
              "3 times a day" to 3,
              "5 times per day" to 5,
              "Daily" to 1,
              "every day" to 1,
              "1 a day" to 1,
              "bid" to 2,
              "TID" to 3,
              "qid" to 4,
              "qd" to 1,
              "Every 8 hours" to 3,
              "q12h" to 2,
              "BID" to 2,
              "24 times a day" to 24,
              "Morning and evening" to 2,
              "morning, noon and evening" to 3,
              "At bedtime" to 1,
          )
      )

  @Test
  fun scannedFrenchAndGermanWordings_andTheSwissScheme_giveTheirCount() =
      assertCounts(
          listOf(
              "1-0-1" to 2,
              "1 - 1 - 1" to 3,
              "1-1-1-1" to 4,
              "0-0-1" to 1,
              "2-0-0" to 1,
              "1-0-1 (matin et soir)" to 2,
              "3x/jour" to 3,
              "2 x par jour" to 2,
              "2 fois par jour" to 2,
              "1 fois/j" to 1,
              "2/j" to 2,
              "x 2/j" to 2,
              "Une fois par jour" to 1,
              "deux fois par jour" to 2,
              "trois fois par jour" to 3,
              "1 par jour" to 1,
              "par jour" to 1,
              "tous les jours" to 1,
              "Quotidien" to 1,
              "toutes les 6 heures" to 4,
              "toutes les 12h" to 2,
              "matin et soir" to 2,
              "le soir au coucher" to 1,
              "matin, midi et soir" to 3,
              "3x/jour pendant 5 jours" to 3,
              "2x täglich" to 2,
              "3 mal täglich" to 3,
              "zweimal täglich" to 2,
              "2X TÄGLICH" to 2,
              "Täglich" to 1,
              "morgens und abends" to 2,
              "alle 8 Stunden" to 3,
          )
      )

  @Test
  fun ifNeeded_lessThanDaily_ambiguous_orUnreadable_haveNoCount() =
      assertCounts(
          listOf(
              null to null,
              "" to null,
              "   " to null,
              // Only if needed: there is nothing to tick each day
              "If needed" to null,
              "as needed" to null,
              "si besoin" to null,
              "3x/jour si douleurs" to null,
              "1 cp si nausées" to null,
              "bei Bedarf" to null,
              "2x täglich bei Bedarf" to null,
              "1-0-1 si besoin" to null,
              "prn" to null,
              // Less often than daily
              "Once a week" to null,
              "1x/semaine" to null,
              "2 fois par semaine" to null,
              "2 mal pro Woche" to null,
              "hebdomadaire" to null,
              "tous les 21 jours" to null,
              "toutes les 3 semaines" to null,
              "every 2 weeks" to null,
              "every 2 days" to null,
              "every other day" to null,
              "un jour sur deux" to null,
              "alle 3 Tage" to null,
              // Ambiguous: "2 par jour" or "2 daily" can mean two tablets at once
              "2 par jour" to null,
              "2 daily" to null,
              // Every few hours that don't divide a day, or a count no one takes
              "every 5 hours" to null,
              "every 48 hours" to null,
              "25 times a day" to null,
              "30x/jour" to null,
              "0-0-0" to null,
              // Not a frequency
              "1 tablet" to null,
              "with food" to null,
          )
      )
}
