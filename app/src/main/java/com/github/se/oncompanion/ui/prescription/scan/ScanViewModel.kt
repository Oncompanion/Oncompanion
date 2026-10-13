// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the camera permission stands on the scan screen. */
enum class CameraPermission {
  /** Not known yet: being checked, or Android's permission dialog is open. */
  UNKNOWN,
  /** The camera can be used. */
  GRANTED,
  /** The user refused: the screen explains why the camera is needed and offers manual entry. */
  REFUSED,
}

/**
 * What the scan screen shows.
 *
 * @property permission whether the camera can be used
 * @property permissionRequested Android's permission dialog was already shown on this screen, so it
 *   isn't shown again (e.g. after the phone is rotated)
 */
data class ScanUiState(
    val permission: CameraPermission = CameraPermission.UNKNOWN,
    val permissionRequested: Boolean = false,
)

/**
 * State of the prescription scan screen (US-10, #61). It has no Android `Context`: the screen
 * checks the permission with Android and reports the result here.
 */
class ScanViewModel : ViewModel() {

  private val _uiState = MutableStateFlow(ScanUiState())
  val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

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
}
