// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.se.oncompanion.domain.medication.scan.DraftPrescriptionFromScan
import com.github.se.oncompanion.domain.medication.scan.readPrescription
import com.github.se.oncompanion.model.medication.scan.FakeTextRecognizer
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import com.github.se.oncompanion.model.medication.scan.ScannedSheets
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val dispatcher = StandardTestDispatcher()
  private lateinit var cacheDir: File
  private lateinit var viewModel: ScanViewModel

  // Reads sheet 01 of the test prescriptions, unless a test says otherwise
  private val recognizer = FakeTextRecognizer(ScannedSheets.sheet(1))
  private val zone = ZoneId.of("Europe/Zurich")
  private val clock = Clock.fixed(LocalDate.of(2026, 11, 20).atStartOfDay(zone).toInstant(), zone)

  private val state
    get() = viewModel.uiState.value

  /** The photos currently on the phone. */
  private val photosOnDisk: List<File>
    get() = File(cacheDir, ScanPhotoStore.DIRECTORY).listFiles()?.toList().orEmpty()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    cacheDir = temporaryFolder.newFolder("cache")
    viewModel =
        ScanViewModel(ScanPhotoStore(cacheDir), DraftPrescriptionFromScan(recognizer, clock))
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** Permission granted and camera on: a photo can be taken. */
  private fun cameraReady() {
    viewModel.onPermissionGranted()
    viewModel.onCameraReady()
  }

  /** What the real camera does: write the photo into the file it's given. */
  private val writePhoto: suspend (File) -> Unit = { it.writeText("photo") }

  // ---- permission ----

  @Test
  fun startsWithThePermissionUnknown_andNotRequested() {
    assertEquals(CameraPermission.UNKNOWN, state.permission)
    assertFalse(state.permissionRequested)
    assertEquals(CameraStatus.STARTING, state.camera)
    assertFalse(state.canCapture)
  }

  @Test
  fun requestingThePermission_isRemembered_butDecidesNothing() {
    viewModel.onPermissionRequested()

    assertTrue(state.permissionRequested)
    assertEquals(CameraPermission.UNKNOWN, state.permission)
  }

  @Test
  fun grantedPermission_allowsTheCamera() {
    viewModel.onPermissionRequested()
    viewModel.onPermissionGranted()

    assertEquals(CameraPermission.GRANTED, state.permission)
    assertTrue(state.permissionRequested)
  }

  @Test
  fun refusedPermission_isShownAsRefused() {
    viewModel.onPermissionRequested()
    viewModel.onPermissionRefused()

    assertEquals(CameraPermission.REFUSED, state.permission)
  }

  @Test
  fun cameraAllowedInSettingsAfterARefusal_allowsTheCamera() {
    viewModel.onPermissionRequested()
    viewModel.onPermissionRefused()

    viewModel.onPermissionGranted()

    assertEquals(CameraPermission.GRANTED, state.permission)
  }

  // ---- camera ----

  @Test
  fun aPhotoCanOnlyBeTakenOnceTheCameraIsOn() {
    viewModel.onPermissionGranted()
    assertFalse(state.canCapture)

    viewModel.onCameraReady()

    assertTrue(state.canCapture)
  }

  @Test
  fun cameraUnavailable_noPhotoCanBeTaken() {
    viewModel.onPermissionGranted()
    viewModel.onCameraUnavailable()

    assertEquals(CameraStatus.UNAVAILABLE, state.camera)
    assertFalse(state.canCapture)
  }

  @Test
  fun cameraStartingAgain_noPhotoCanBeTaken_untilItIsOpen() {
    cameraReady()

    // A new camera, e.g. after the phone was rotated
    viewModel.onCameraStarting()

    assertEquals(CameraStatus.STARTING, state.camera)
    assertFalse(state.canCapture)
    viewModel.onCameraReady()
    assertTrue(state.canCapture)
  }

  // ---- capture ----

  @Test
  fun capture_savesThePhotoInTheScansFolder() =
      runTest(dispatcher) {
        cameraReady()

        viewModel.capture(writePhoto)
        advanceUntilIdle()

        assertEquals(1, photosOnDisk.size)
        // The photo is shown for the user to check
        assertEquals(photosOnDisk.single().absoluteFile, state.photo?.absoluteFile)
        assertFalse(state.isCapturing)
      }

  @Test
  fun whileThePhotoIsChecked_noOtherPhotoCanBeTaken() =
      runTest(dispatcher) {
        cameraReady()
        viewModel.capture(writePhoto)
        advanceUntilIdle()
        var calls = 0

        viewModel.capture { calls++ }
        advanceUntilIdle()

        assertFalse(state.canCapture)
        assertEquals(0, calls)
        assertEquals(1, photosOnDisk.size)
      }

  @Test
  fun capture_whileTheCameraIsntOn_doesNothing() =
      runTest(dispatcher) {
        viewModel.onPermissionGranted()
        var calls = 0

        viewModel.capture { calls++ }
        advanceUntilIdle()

        assertEquals(0, calls)
        assertTrue(photosOnDisk.isEmpty())
      }

  @Test
  fun capture_whileAPhotoIsBeingTaken_isIgnored() =
      runTest(dispatcher) {
        cameraReady()
        val shutter = CompletableDeferred<Unit>()
        var calls = 0
        viewModel.capture {
          calls++
          shutter.await()
          it.writeText("photo")
        }
        advanceUntilIdle()
        assertTrue(state.isCapturing)
        assertFalse(state.canCapture)

        viewModel.capture(writePhoto)
        shutter.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(1, photosOnDisk.size)
      }

  @Test
  fun failedCapture_leavesNoFileBehind_andIsReported() =
      runTest(dispatcher) {
        cameraReady()

        viewModel.capture {
          it.writeText("half a photo")
          throw IOException("camera error")
        }
        advanceUntilIdle()

        assertTrue(photosOnDisk.isEmpty())
        assertTrue(state.captureFailed)
        assertNull(state.photo)
        assertTrue(state.canCapture)
      }

  @Test
  fun retake_deletesThePhoto_andShowsTheCameraAgain() =
      runTest(dispatcher) {
        cameraReady()
        viewModel.capture(writePhoto)
        advanceUntilIdle()
        val first = photosOnDisk.single()

        viewModel.retake()

        assertFalse(first.exists())
        assertNull(state.photo)
        assertTrue(state.canCapture)
      }

  @Test
  fun retake_thenANewPhoto_keepsOnlyTheNewOne() =
      runTest(dispatcher) {
        cameraReady()
        viewModel.capture(writePhoto)
        advanceUntilIdle()
        val first = photosOnDisk.single()

        viewModel.retake()
        viewModel.capture(writePhoto)
        advanceUntilIdle()

        assertFalse(first.exists())
        assertEquals(1, photosOnDisk.size)
        assertEquals(photosOnDisk.single().absoluteFile, state.photo?.absoluteFile)
      }

  // ---- reading the prescription ----

  /** A photo taken and being checked, ready to be used. */
  private suspend fun TestScope.photoTaken(): File {
    cameraReady()
    viewModel.capture(writePhoto)
    advanceUntilIdle()
    return photosOnDisk.single()
  }

  @Test
  fun usePhoto_readsThePrescription_andHandsItOverWithThePhoto() =
      runTest(dispatcher) {
        val photo = photoTaken()

        viewModel.usePhoto()
        assertTrue(state.isReading)
        advanceUntilIdle()

        assertFalse(state.isReading)
        assertEquals(
            readPrescription(ScannedSheets.sheet(1), LocalDate.of(2026, 11, 20)),
            state.read?.prescription,
        )
        assertEquals(photo.absoluteFile, state.read?.photo?.absoluteFile)
        // The photo goes with the prescription: this screen no longer shows or deletes it
        assertNull(state.photo)
        assertTrue(photo.exists())
        assertEquals(listOf(photo.absoluteFile), recognizer.photos.map { it.absoluteFile })
      }

  @Test
  fun whileReading_thePhotoCantBeRetakenOrUsedAgain() =
      runTest(dispatcher) {
        val photo = photoTaken()
        recognizer.reading = CompletableDeferred()

        viewModel.usePhoto()
        advanceUntilIdle()
        viewModel.usePhoto()
        viewModel.retake()
        advanceUntilIdle()

        assertTrue(state.isReading)
        assertTrue(photo.exists())
        assertEquals(1, recognizer.photos.size)
      }

  @Test
  fun theReadPrescriptionIsForgottenOnceOpened() =
      runTest(dispatcher) {
        val photo = photoTaken()
        viewModel.usePhoto()
        advanceUntilIdle()

        viewModel.onReadOpened()

        assertNull(state.read)
        // Its photo is the form's now
        assertTrue(photo.exists())
      }

  @Test
  fun nothingRead_keepsThePhotoForRetakeOrManualEntry() =
      runTest(dispatcher) {
        recognizer.result = FakeTextRecognizer.NO_TEXT
        val photo = photoTaken()

        viewModel.usePhoto()
        advanceUntilIdle()

        assertTrue(state.nothingRead)
        assertFalse(state.isReading)
        assertNull(state.read)
        assertEquals(photo.absoluteFile, state.photo?.absoluteFile)
        // Using it again would read the same nothing
        viewModel.usePhoto()
        advanceUntilIdle()
        assertEquals(1, recognizer.photos.size)
      }

  @Test
  fun aFailedReading_isNothingRead() =
      runTest(dispatcher) {
        recognizer.error = IOException("ML Kit couldn't read the photo")
        photoTaken()

        viewModel.usePhoto()
        advanceUntilIdle()

        assertTrue(state.nothingRead)
        assertNull(state.read)
      }

  @Test
  fun retake_afterNothingRead_deletesThePhoto_andShowsTheCameraAgain() =
      runTest(dispatcher) {
        recognizer.result = FakeTextRecognizer.NO_TEXT
        val photo = photoTaken()
        viewModel.usePhoto()
        advanceUntilIdle()

        viewModel.retake()

        assertFalse(photo.exists())
        assertFalse(state.nothingRead)
        assertTrue(state.canCapture)
      }

  @Test
  fun closedWhileReading_deletesThePhoto_andHandsNothingOver() =
      runTest(dispatcher) {
        val photo = photoTaken()
        recognizer.reading = CompletableDeferred()
        viewModel.usePhoto()
        advanceUntilIdle()

        viewModel.onClose()
        recognizer.reading!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(photo.exists())
        assertNull(state.read)
        assertFalse(state.isReading)
      }

  @Test
  fun noPhotoCanBeTaken_whileReadingOrOnceNothingWasRead() =
      runTest(dispatcher) {
        photoTaken()
        recognizer.reading = CompletableDeferred()
        viewModel.usePhoto()
        advanceUntilIdle()
        assertFalse(state.canCapture)

        recognizer.result = FakeTextRecognizer.NO_TEXT
        recognizer.reading!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(state.nothingRead)
        assertFalse(state.canCapture)
      }

  @Test
  fun afterNothingRead_aRetakenPhotoIsReadAndHanded() =
      runTest(dispatcher) {
        recognizer.result = FakeTextRecognizer.NO_TEXT
        val first = photoTaken()
        viewModel.usePhoto()
        advanceUntilIdle()
        viewModel.retake()

        recognizer.result = ScannedSheets.sheet(3)
        viewModel.capture(writePhoto)
        advanceUntilIdle()
        val second = photosOnDisk.single()
        viewModel.usePhoto()
        advanceUntilIdle()

        assertFalse(first.exists())
        assertEquals(second.absoluteFile, state.read?.photo?.absoluteFile)
      }

  @Test
  fun closedAfterNothingRead_deletesThePhoto() =
      runTest(dispatcher) {
        recognizer.result = FakeTextRecognizer.NO_TEXT
        val photo = photoTaken()
        viewModel.usePhoto()
        advanceUntilIdle()

        viewModel.onClose()

        assertFalse(photo.exists())
      }

  @Test
  fun usePhoto_withoutAPhoto_doesNothing() {
    cameraReady()

    viewModel.usePhoto()

    assertFalse(state.isReading)
    assertTrue(recognizer.photos.isEmpty())
  }

  @Test
  fun failedCaptureMessageShown_isForgotten() =
      runTest(dispatcher) {
        cameraReady()
        viewModel.capture { throw IOException("camera error") }
        advanceUntilIdle()

        viewModel.onMessageShown()

        assertFalse(state.captureFailed)
      }

  // ---- deleting the photo ----

  @Test
  fun closingTheScreen_deletesThePhoto() =
      runTest(dispatcher) {
        cameraReady()
        viewModel.capture(writePhoto)
        advanceUntilIdle()

        viewModel.onClose()

        assertTrue(photosOnDisk.isEmpty())
      }

  @Test
  fun closingWhileThePhotoIsBeingTaken_deletesItOnceFinished() =
      runTest(dispatcher) {
        cameraReady()
        val shutter = CompletableDeferred<Unit>()
        viewModel.capture {
          shutter.await()
          it.writeText("photo")
        }
        advanceUntilIdle()

        // Closed before the camera finished writing the photo
        viewModel.onClose()
        shutter.complete(Unit)
        advanceUntilIdle()

        assertTrue(photosOnDisk.isEmpty())
        assertNull(state.photo)
        assertFalse(state.isCapturing)
      }

  @Test
  fun screenDestroyed_deletesThePhoto() =
      runTest(dispatcher) {
        val store = ViewModelStore()
        val scoped =
            ViewModelProvider(
                store,
                viewModelFactory { initializer { ScanViewModel(ScanPhotoStore(cacheDir)) } },
            )[ScanViewModel::class.java]
        scoped.onPermissionGranted()
        scoped.onCameraReady()
        scoped.capture(writePhoto)
        advanceUntilIdle()
        assertEquals(1, photosOnDisk.size)

        store.clear()

        assertTrue(photosOnDisk.isEmpty())
      }

  @Test
  fun screenDestroyedWhileThePhotoIsBeingTaken_leavesNoFile() =
      runTest(dispatcher) {
        val store = ViewModelStore()
        val scoped =
            ViewModelProvider(
                store,
                viewModelFactory { initializer { ScanViewModel(ScanPhotoStore(cacheDir)) } },
            )[ScanViewModel::class.java]
        scoped.onPermissionGranted()
        scoped.onCameraReady()
        // The camera has started writing the photo, but hasn't finished
        scoped.capture {
          it.writeText("partial")
          awaitCancellation()
        }
        advanceUntilIdle()

        store.clear()
        advanceUntilIdle()

        assertTrue(photosOnDisk.isEmpty())
      }

  /** A scan view model cleared when [store] is, reading with the test's recognizer. */
  private fun scopedTo(store: ViewModelStore): ScanViewModel =
      ViewModelProvider(
          store,
          viewModelFactory {
            initializer {
              ScanViewModel(ScanPhotoStore(cacheDir), DraftPrescriptionFromScan(recognizer, clock))
            }
          },
      )[ScanViewModel::class.java]

  @Test
  fun screenDestroyedWhileReading_deletesThePhoto() =
      runTest(dispatcher) {
        val store = ViewModelStore()
        val scoped =
            scopedTo(store).apply {
              onPermissionGranted()
              onCameraReady()
            }
        scoped.capture(writePhoto)
        advanceUntilIdle()
        recognizer.reading = CompletableDeferred()
        scoped.usePhoto()
        advanceUntilIdle()

        store.clear()
        advanceUntilIdle()

        assertTrue(photosOnDisk.isEmpty())
      }

  @Test
  fun screenDestroyedBeforeTheReadPrescriptionWasOpened_deletesItsPhoto() =
      runTest(dispatcher) {
        val store = ViewModelStore()
        val scoped =
            scopedTo(store).apply {
              onPermissionGranted()
              onCameraReady()
            }
        scoped.capture(writePhoto)
        advanceUntilIdle()
        scoped.usePhoto()
        advanceUntilIdle()
        assertNotNull(scoped.uiState.value.read)

        store.clear()

        assertTrue(photosOnDisk.isEmpty())
      }

  @Test
  fun screenDestroyedAfterTheFormOpened_leavesThePhotoToTheForm() =
      runTest(dispatcher) {
        val store = ViewModelStore()
        val scoped =
            scopedTo(store).apply {
              onPermissionGranted()
              onCameraReady()
            }
        scoped.capture(writePhoto)
        advanceUntilIdle()
        scoped.usePhoto()
        advanceUntilIdle()
        val photo = scoped.uiState.value.read!!.photo
        scoped.onReadOpened()

        store.clear()

        assertTrue(photo.exists())
      }

  @Test
  fun capture_withoutTheCameraAllowed_doesNothing() =
      runTest(dispatcher) {
        viewModel.onCameraReady()
        var photosTaken = 0

        viewModel.capture { photosTaken++ }
        viewModel.onPermissionRefused()
        viewModel.capture { photosTaken++ }
        advanceUntilIdle()

        assertEquals(0, photosTaken)
      }

  @Test
  fun aNewCapture_forgetsTheMessageAboutThePreviousOne() =
      runTest(dispatcher) {
        cameraReady()
        viewModel.capture { throw IOException("camera error") }
        advanceUntilIdle()
        assertTrue(state.captureFailed)

        val shutter = CompletableDeferred<Unit>()
        viewModel.capture {
          shutter.await()
          it.writeText("photo")
        }
        advanceUntilIdle()

        // The old error isn't shown while the new photo is being taken
        assertFalse(state.captureFailed)
        assertNull(state.photo)
        shutter.complete(Unit)
        advanceUntilIdle()
        assertNotNull(state.photo)
      }

  @Test
  fun openingTheScreen_deletesPhotosLeftByAnEarlierScan() {
    val leftover = ScanPhotoStore(cacheDir).newPhotoFile().apply { writeText("old photo") }

    ScanViewModel(ScanPhotoStore(cacheDir))

    assertFalse(leftover.exists())
  }
}
