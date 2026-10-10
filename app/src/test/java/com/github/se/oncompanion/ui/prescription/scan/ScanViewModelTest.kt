// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanViewModelTest {

  private val viewModel = ScanViewModel()

  private val state
    get() = viewModel.uiState.value

  @Test
  fun startsWithThePermissionUnknown_andNotRequested() {
    assertEquals(CameraPermission.UNKNOWN, state.permission)
    assertFalse(state.permissionRequested)
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
}
