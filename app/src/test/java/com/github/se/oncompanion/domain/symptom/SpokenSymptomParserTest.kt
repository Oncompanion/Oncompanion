// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.symptom.SymptomType.APPETITE_LOSS
import com.github.se.oncompanion.model.symptom.SymptomType.BREATHLESSNESS
import com.github.se.oncompanion.model.symptom.SymptomType.FATIGUE
import com.github.se.oncompanion.model.symptom.SymptomType.FEVER
import com.github.se.oncompanion.model.symptom.SymptomType.HEADACHE
import com.github.se.oncompanion.model.symptom.SymptomType.NAUSEA
import com.github.se.oncompanion.model.symptom.SymptomType.PAIN
import com.github.se.oncompanion.model.symptom.SymptomType.SLEEP_PROBLEMS
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenSymptomParserTest {

  private val today = LocalDate.of(2026, 10, 5)
  /** Monday 5 October 2026 at 14:00 in Zurich. */
  private val now = today.atTime(14, 0).atZone(ZoneId.of("Europe/Zurich"))
  private val yesterday = today.minusDays(1)

  /** What the parser should draft from [transcript]; the notes are always the transcript. */
  private data class Case(
      val transcript: String,
      val types: List<SymptomType> = emptyList(),
      val intensity: Int? = null,
      val date: LocalDate? = null,
      val time: LocalTime? = null,
  )

  private fun check(locale: Locale, cases: List<Case>) {
    for (case in cases) {
      val expected = SymptomDraft(case.types, case.intensity, case.date, case.time, case.transcript)
      assertEquals(case.transcript, expected, parseSpokenSymptom(case.transcript, locale, now))
    }
  }

  @Test
  fun english_draftsWhatWasSaid_andLeavesTheRestEmpty() {
    check(
        Locale.ENGLISH,
        listOf(
            // Symptom words, synonyms and several symptoms at once
            Case("i'm really tired about 7 out of 10 since this morning", listOf(FATIGUE), 7),
            Case("exhausted today no appetite", listOf(FATIGUE, APPETITE_LOSS)),
            Case("my head hurts and I have a fever", listOf(HEADACHE, FEVER)),
            Case("pain in my back eight out of ten", listOf(PAIN), 8),
            Case("I'm not hungry", listOf(APPETITE_LOSS)),
            Case("I couldn't sleep last night", listOf(SLEEP_PROBLEMS)),
            Case("I feel sick 7/10", listOf(NAUSEA), 7),
            Case("I felt tired, tired, so tired", listOf(FATIGUE)),
            // Negation, up to the end of the clause
            Case(
                "I felt nauseous two hours ago but no headache",
                listOf(NAUSEA),
                null,
                today,
                at(12),
            ),
            Case("no nausea or headache just tired", listOf(FATIGUE)),
            Case("I don't feel tired"),
            Case("Not tired, nauseous.", listOf(NAUSEA)),
            Case("I don't know, I'm tired", listOf(FATIGUE)),
            // When it was felt
            Case("short of breath since yesterday", listOf(BREATHLESSNESS), null, yesterday),
            Case("half an hour ago I threw up", listOf(NAUSEA), null, today, at(13, 30)),
            Case("an hour ago", date = today, time = at(13)),
            Case("20 minutes ago", date = today, time = at(13, 40)),
            Case("at 0:30", time = at(0, 30)),
            Case("at 25:00"),
            Case("0 hours ago"),
            Case("headache at 9 am", listOf(HEADACHE), time = at(9)),
            Case("headache at 1:30 p.m.", listOf(HEADACHE), time = at(13, 30)),
            // 9 pm hasn't come yet, so only 9 in the morning is possible
            Case("headache at 9 o'clock", listOf(HEADACHE), time = at(9)),
            // Both 9:00 and 21:00 are possible yesterday: not guessed
            Case("yesterday at 9 o'clock", date = yesterday),
            // A bare number isn't a time, and a time that hasn't come yet is dropped
            Case("headache at 9", listOf(HEADACHE)),
            Case("headache at 3 pm", listOf(HEADACHE)),
            // Contradicting times or intensities: not guessed
            Case("yesterday, no, 2 hours ago"),
            Case("2 hours ago or 3 hours ago"),
            Case("yesterday or the day before yesterday"),
            Case("5 out of 10 then 7 out of 10"),
            // Only 1 to 10 is an intensity
            Case("11 out of 10"),
            Case("0 out of 10"),
            // Words that look like a symptom but aren't one
            Case("I'm not so tired"),
            Case("tired of waiting"),
            Case("sick and tired of this"),
            Case("pain free today"),
            Case("no appetite problems"),
            Case("the drain was drained"),
            // Difficulty eating isn't loss of appetite: it stays in the notes
            Case("I can't eat"),
            Case("feeling drained", listOf(FATIGUE)),
            Case("head ache", listOf(HEADACHE)),
            Case("I can't stop throwing up", listOf(NAUSEA)),
            Case("nothing hurts just tired", listOf(FATIGUE)),
            // Nothing understood
            Case("I don't feel great"),
            Case(""),
        ),
    )
  }

  @Test
  fun french_draftsWhatWasSaid_andLeavesTheRestEmpty() {
    check(
        Locale.FRENCH,
        listOf(
            Case("je suis épuisé sept sur dix depuis ce matin", listOf(FATIGUE), 7),
            Case(
                "j'ai eu des nausées il y a deux heures, pas de mal de tête",
                listOf(NAUSEA),
                null,
                today,
                at(12),
            ),
            Case("mal à la tête 8 sur 10", listOf(HEADACHE), 8),
            Case("je n'ai pas faim", listOf(APPETITE_LOSS)),
            Case("je n'ai plus de fièvre mais j'ai mal au ventre", listOf(PAIN)),
            Case("j'ai mal dormi cette nuit", listOf(SLEEP_PROBLEMS)),
            Case("j'ai du mal à respirer", listOf(BREATHLESSNESS)),
            Case("Envie de vomir, 6/10.", listOf(NAUSEA), 6),
            // "pain" is bread in French, and "pas mal" isn't pain
            Case("j'ai mangé du pain"),
            Case("je me sens pas mal"),
            // Words that look like a symptom but aren't one
            Case("on a mangé des crevettes"),
            Case("mon téléphone est à plat"),
            Case("le médicament est épuisé"),
            Case("j'ai plus d'énergie qu'avant"),
            Case("je suis mal à l'aise"),
            Case("j'ai du mal à la comprendre"),
            Case("j'ai un peu dormi"),
            Case("rien ne me fait mal"),
            Case("je suis crevée", listOf(FATIGUE)),
            Case("je n'ai plus d'énergie", listOf(FATIGUE)),
            Case("ça me fait mal à la tête", listOf(HEADACHE)),
            Case("mal de gorge", listOf(PAIN)),
            Case("je n'ai pas fermé l'œil", listOf(SLEEP_PROBLEMS)),
            // "pas mal de" means "quite a lot of", and "je ne sais pas" negates nothing
            Case("j'ai pas mal de fatigue", listOf(FATIGUE)),
            Case("je ne sais pas je suis fatiguée", listOf(FATIGUE)),
            // "plus" only negates after "ne": here it means "more"
            Case("encore plus fatiguée", listOf(FATIGUE)),
            // "il y a" without a duration is "there is"
            Case("il y a des nausées", listOf(NAUSEA)),
            // When it was felt
            Case("essoufflée depuis hier", listOf(BREATHLESSNESS), null, yesterday),
            Case("avant-hier j'avais de la fièvre", listOf(FEVER), null, today.minusDays(2)),
            Case("fatiguée il y a une demi-heure", listOf(FATIGUE), null, today, at(13, 30)),
            Case("nausées il y a 3h", listOf(NAUSEA), null, today, at(11)),
            Case("nausées à 9h", listOf(NAUSEA), time = at(9)),
            Case("nausées à 13h30", listOf(NAUSEA), time = at(13, 30)),
            Case("nausées à 8 heures du soir hier", listOf(NAUSEA), null, yesterday, at(20)),
            Case("nausées à 15h", listOf(NAUSEA)),
            Case("je ne me sens pas très bien"),
        ),
    )
  }

  @Test
  fun anotherLanguage_isParsedAsEnglish() {
    check(Locale.GERMAN, listOf(Case("tired 4 out of 10", listOf(FATIGUE), 4)))
  }

  @Test
  fun notes_areTheTranscript_trimmedAndCutToTheLimit() {
    val long = "tired ".repeat(400)
    val cases =
        listOf(
            "  tired  " to "tired",
            long to long.trim().take(SymptomEntry.MAX_NOTES_LENGTH),
        )
    for ((transcript, notes) in cases) {
      assertEquals(notes, parseSpokenSymptom(transcript, Locale.ENGLISH, now).notes)
    }
  }

  private fun at(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)
}
