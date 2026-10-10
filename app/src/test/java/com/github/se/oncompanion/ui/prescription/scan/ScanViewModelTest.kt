// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
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

  private val state
    get() = viewModel.uiState.value

  /** The photos currently on the phone. */
  private val photosOnDisk: List<File>
    get() = File(cacheDir, ScanPhotoStore.DIRECTORY).listFiles()?.toList().orEmpty()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    cacheDir = temporaryFolder.newFolder("cache")
    viewModel = ScanViewModel(ScanPhotoStore(cacheDir))
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
