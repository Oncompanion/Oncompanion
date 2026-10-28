// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.model.medication.scan.FakeTextRecognizer
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    assertEquals(readPrescription(sheet(1), today), scanned)
    assertEquals(listOf(photo), recognizer.photos)
  }

  @Test
  fun todayComesFromTheClockWhenNoDateIsRead() = runTest {
    val recognizer = FakeTextRecognizer(FakeTextRecognizer.textOf("Ondansetron 8 mg"))
    val scanned = DraftPrescriptionFromScan(recognizer, clock)(photo())
    assertEquals(today, scanned!!.draft.prescribedOn)
  }

  @Test
  fun aPhotoWithoutTextGivesNothing() = runTest {
    assertNull(DraftPrescriptionFromScan(FakeTextRecognizer(), clock)(photo()))
  }

  @Test
  fun aPhotoThatCantBeReadGivesNothing() = runTest {
    val recognizer = FakeTextRecognizer().apply { error = IOException("ML Kit couldn't read it") }
    assertNull(DraftPrescriptionFromScan(recognizer, clock)(photo()))
  }

  @Test
  fun thePhotoIsNeverDeleted() = runTest {
    val photo = photo()
    DraftPrescriptionFromScan(FakeTextRecognizer(sheet(2)), clock)(photo)
    DraftPrescriptionFromScan(FakeTextRecognizer(), clock)(photo)
    assertTrue(photo.exists())
  }
}
