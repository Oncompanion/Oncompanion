// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.model.medication.scan.FakeTextRecognizer
import com.github.se.oncompanion.model.medication.scan.RecognizedLine
import com.github.se.oncompanion.model.medication.scan.RecognizedText
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import com.github.se.oncompanion.model.medication.scan.TextBox
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [DraftPrescriptionFromScan] reads the photo, then the prescription, and keeps the photo. */
@RunWith(RobolectricTestRunner::class)
class DraftPrescriptionFromScanTest {
  @get:Rule val folder = TemporaryFolder()

  private val zone = ZoneId.of("Europe/Zurich")
  private val today = LocalDate.of(2026, 11, 20)
  private val clock = Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)

  private fun photo(): File = folder.newFile("scan-1.jpg")

  @Test
  fun aPhotoWithAPrescriptionGivesItsDraft() = runTest {
    val photo = photo()
    val recognizer = FakeTextRecognizer(sheet(1))

    val scanned = DraftPrescriptionFromScan(recognizer, clock)(photo)

    assertEquals(ScanOutcome.Read(readPrescription(sheet(1), today)!!), scanned)
    assertEquals(listOf(photo), recognizer.photos)
  }

  @Test
  fun todayComesFromTheClockWhenNoDateIsRead() = runTest {
    val recognizer = FakeTextRecognizer(FakeTextRecognizer.textOf("Ondansetron 8 mg"))
    val scanned = DraftPrescriptionFromScan(recognizer, clock)(photo())
    assertEquals(today, (scanned as ScanOutcome.Read).prescription.draft.prescribedOn)
  }

  @Test
  fun aPhotoWithoutTextHasNoText() = runTest {
    assertEquals(
        ScanOutcome.NoText,
        DraftPrescriptionFromScan(FakeTextRecognizer(), clock)(photo()),
    )
  }

  @Test
  fun aPhotoThatCantBeReadHasNoText() = runTest {
    val recognizer = FakeTextRecognizer().apply { error = IOException("ML Kit couldn't read it") }
    assertEquals(ScanOutcome.NoText, DraftPrescriptionFromScan(recognizer, clock)(photo()))
  }

  @Test
  fun textReadSurelyWithoutAMedicationIsNotAPrescription() = runTest {
    fun lines(count: Int, confidence: Float) =
        RecognizedText(
            1000,
            1000,
            List(count) {
              RecognizedLine(
                  "Ingredients line $it",
                  TextBox(0, it * 50, 900, it * 50 + 40),
                  confidence,
              )
            },
        )
    val outcomes =
        mapOf(
            // A drink label (sheet 11)
            sheet(11) to ScanOutcome.NotAPrescription,
            lines(3, SURE) to ScanOutcome.NotAPrescription,
            // Too little to tell
            lines(2, SURE) to ScanOutcome.NoText,
            // Handwriting is read unsurely: it couldn't be read, not "not a prescription"
            lines(6, 0.57f) to ScanOutcome.NoText,
        )
    val photo = photo()
    for ((text, outcome) in outcomes) {
      assertEquals(outcome, DraftPrescriptionFromScan(FakeTextRecognizer(text), clock)(photo))
    }
  }

  @Test
  fun thePhotoIsNeverDeleted() = runTest {
    val photo = photo()
    DraftPrescriptionFromScan(FakeTextRecognizer(sheet(2)), clock)(photo)
    DraftPrescriptionFromScan(FakeTextRecognizer(), clock)(photo)
    assertTrue(photo.exists())
  }
}
