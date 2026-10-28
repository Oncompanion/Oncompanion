// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.se.oncompanion.domain.medication.scan.readPrescription
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ScanHandoffViewModel] passes the scanned prescription to the form and deletes its photo once the
 * form is done with it, or when the prescriptions screens are left.
 */
class ScanHandoffViewModelTest {
  @get:Rule val folder = TemporaryFolder()

  private val store by lazy { ScanPhotoStore(folder.root) }
  private val prescription = readPrescription(sheet(1), LocalDate.of(2026, 11, 20))!!

  private fun photo(): File = store.newPhotoFile().apply { writeText("jpeg") }

  @Test
  fun theFormStartsFromWhatTheScanRead() {
    val handoff = ScanHandoffViewModel(store)
    handoff.hand(ReadScan(prescription, photo()))
    assertEquals(prescription, handoff.scanned)
  }

  @Test
  fun aFormOpenedWithoutAScanStartsEmpty() {
    assertNull(ScanHandoffViewModel(store).scanned)
  }

  @Test
  fun thePhotoIsKeptUntilTheFormIsDone() {
    val handoff = ScanHandoffViewModel(store)
    val photo = photo()
    handoff.hand(ReadScan(prescription, photo))
    assertTrue(photo.exists())

    handoff.finish()

    assertFalse(photo.exists())
    assertNull(handoff.scanned)
  }

  @Test
  fun aNewScanReplacesThePreviousOneAndItsPhoto() {
    val handoff = ScanHandoffViewModel(store)
    val first = photo()
    val second = photo()
    handoff.hand(ReadScan(prescription, first))
    handoff.hand(ReadScan(prescription, second))
    assertFalse(first.exists())
    assertTrue(second.exists())
  }

  @Test
  fun theSameScanHandedTwiceKeepsItsPhoto() {
    val handoff = ScanHandoffViewModel(store)
    val scan = ReadScan(prescription, photo())
    handoff.hand(scan)
    handoff.hand(scan)
    assertTrue(scan.photo.exists())
    assertEquals(prescription, handoff.scanned)
  }

  @Test
  fun finishingTwiceIsHarmless() {
    val handoff = ScanHandoffViewModel(store)
    val other = photo()
    handoff.hand(ReadScan(prescription, photo()))
    handoff.finish()
    handoff.finish()
    // Only the scan's own photo was deleted
    assertTrue(other.exists())
  }

  @Test
  fun finishingWithoutAScanDoesNothing() {
    val handoff = ScanHandoffViewModel(store)
    handoff.finish()
    assertNull(handoff.scanned)
  }

  @Test
  fun leavingThePrescriptionsScreensDeletesThePhoto() {
    val owner = ViewModelStore()
    val handoff =
        ViewModelProvider.create(
                owner,
                viewModelFactory { initializer { ScanHandoffViewModel(store) } },
            )[ScanHandoffViewModel::class]
    val photo = photo()
    handoff.hand(ReadScan(prescription, photo))

    owner.clear()

    assertFalse(photo.exists())
  }
}
