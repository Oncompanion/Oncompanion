// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the camera permission stands on the scan screen. */
enum class CameraPermission {
  /** Not known yet: being checked, or Android's permission dialog is open. */
  UNKNOWN,
  /** The camera can be used. */
  GRANTED,
  /** The user refused: the screen explains why the camera is needed and offers manual entry. */
  REFUSED,
}

/** Whether the camera itself is on, once the permission is granted. */
enum class CameraStatus {
  /** Being turned on. */
  STARTING,
  /** On: the preview shows and a photo can be taken. */
  READY,
  /** The phone has no camera that can be used: manual entry is offered instead. */
  UNAVAILABLE,
}

/**
 * What the scan screen shows.
 *
 * @property permission whether the camera can be used
 * @property permissionRequested Android's permission dialog was already shown on this screen, so it
 *   isn't shown again (e.g. after the phone is rotated)
 * @property camera whether the camera is on
 * @property isCapturing a photo is being taken
 * @property photo the photo just taken, shown so the user checks it before it's used; `null` while
 *   the camera is shown
 * @property captureFailed the last photo couldn't be taken (message to show once)
 * @property readingComingSoon the user chose to use the photo, but reading prescriptions doesn't
 *   exist yet (message to show once, with a way to enter the prescription manually)
 */
data class ScanUiState(
    val permission: CameraPermission = CameraPermission.UNKNOWN,
    val permissionRequested: Boolean = false,
    val camera: CameraStatus = CameraStatus.STARTING,
    val isCapturing: Boolean = false,
    val photo: File? = null,
    val captureFailed: Boolean = false,
    val readingComingSoon: Boolean = false,
) {
  /**
   * A photo can be taken: the camera is allowed and on, no photo is being taken, and the last one
   * isn't being checked.
   */
  val canCapture: Boolean
    get() =
        permission == CameraPermission.GRANTED &&
            camera == CameraStatus.READY &&
            !isCapturing &&
            photo == null
}

/**
 * State of the prescription scan screen (US-10, #61). It has no Android `Context`: the screen
 * checks the permission and drives the camera, and reports here.
 *
 * The photo of a prescription is a medical document: there is at most one, in [photoStore], shown
 * for the user to check, and it is deleted on Retake and when the screen is closed or destroyed.
 * Photos left by an earlier scan (e.g. the app was killed) are deleted when the screen opens.
 */
class ScanViewModel(private val photoStore: ScanPhotoStore) : ViewModel() {

  private val _uiState = MutableStateFlow(ScanUiState())
  val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

  /** The user closed the screen: a photo that finishes after that must not be kept. */
  private var closed = false

  init {
    photoStore.clear()
  }

  /** Android's permission dialog is about to be shown. */
  fun onPermissionRequested() {
    _uiState.update { it.copy(permissionRequested = true) }
  }

  /** The camera can be used: already allowed, allowed in the dialog or in the phone's settings. */
  fun onPermissionGranted() {
    _uiState.update { it.copy(permission = CameraPermission.GRANTED) }
  }

  /** The user refused the camera in Android's dialog. */
  fun onPermissionRefused() {
    _uiState.update { it.copy(permission = CameraPermission.REFUSED) }
  }

  /**
   * The camera is being turned on, e.g. again after the phone was rotated: no photo can be taken
   * until it's open.
   */
  fun onCameraStarting() {
    _uiState.update { it.copy(camera = CameraStatus.STARTING) }
  }

  /** The camera is open. */
  fun onCameraReady() {
    _uiState.update { it.copy(camera = CameraStatus.READY) }
  }

  /** The camera can't be used: it couldn't be turned on, or it failed. */
  fun onCameraUnavailable() {
    _uiState.update { it.copy(camera = CameraStatus.UNAVAILABLE) }
  }

  /**
   * Takes a photo with [takePhoto], which writes it into the file it's given. Does nothing unless
   * [ScanUiState.canCapture]. If it fails, the unfinished file is deleted.
   */
  fun capture(takePhoto: suspend (File) -> Unit) {
    if (!_uiState.value.canCapture) return
    val file = photoStore.newPhotoFile()
    _uiState.update { it.copy(isCapturing = true, captureFailed = false) }
    viewModelScope.launch {
      try {
        takePhoto(file)
        if (closed) {
          // Closed while the photo was being taken: it's not needed any more
          photoStore.delete(file)
          _uiState.update { it.copy(isCapturing = false) }
          return@launch
        }
        // Shown to the user to check before it's used
        _uiState.update { it.copy(isCapturing = false, photo = file) }
      } catch (e: CancellationException) {
        photoStore.delete(file)
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't take the photo", e)
        photoStore.delete(file)
        _uiState.update { it.copy(isCapturing = false, captureFailed = true) }
      }
    }
  }

  /** The message about the last photo has been shown. */
  fun onMessageShown() {
    _uiState.update { it.copy(captureFailed = false, readingComingSoon = false) }
  }

  /**
   * The user wants to use the photo. Reading the prescription (#62) doesn't exist yet: until then,
   * the photo is deleted, the camera is shown again and the user is told it's coming soon, with
   * manual entry as the way forward.
   */
  fun usePhoto() {
    if (_uiState.value.photo == null) return
    deletePhoto()
    _uiState.update { it.copy(readingComingSoon = true) }
  }

  /** The photo isn't good enough: it's deleted and the camera is shown again. */
  fun retake() {
    deletePhoto()
  }

  /**
   * The user closes the scan screen: the photo is deleted, and so is a photo still being taken once
   * it's finished.
   */
  fun onClose() {
    closed = true
    deletePhoto()
  }

  // The screen is gone for good (e.g. Back, or Android cleared it): no photo must stay behind
  override fun onCleared() {
    deletePhoto()
  }

  // There is at most one photo: the one being checked
  private fun deletePhoto() {
    _uiState.value.photo?.let(photoStore::delete)
    _uiState.update { it.copy(photo = null) }
  }

  private companion object {
    const val TAG = "ScanViewModel"
  }
}
