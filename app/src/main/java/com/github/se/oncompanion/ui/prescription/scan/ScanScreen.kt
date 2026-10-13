// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme

/**
 * Scan a paper prescription with the camera (US-10, Figma "US-10 / Prescription scan"). The camera
 * permission is asked when the screen opens, the first time the user scans: asked at app start,
 * without context, it is refused more often. If it's refused, the screen explains why the camera is
 * needed and offers to enter the prescription manually instead.
 *
 * @param onEnterManually opens the manual entry form (US-24)
 * @param onBack leaves the scan screen
 */
@Composable
fun ScanScreen(
    onEnterManually: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel { ScanViewModel() },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // Android's permission dialog; its answer goes to the view model
  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionRefused()
      }

  // Shows the dialog once, when the camera isn't allowed yet
  LaunchedEffect(Unit) {
    if (!context.hasCameraPermission() && !viewModel.uiState.value.permissionRequested) {
      viewModel.onPermissionRequested()
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  // Each time the screen comes back (at start, or from the phone's settings): the camera may be
  // allowed by now
  LifecycleResumeEffect(Unit) {
    if (context.hasCameraPermission()) viewModel.onPermissionGranted()
    onPauseOrDispose {}
  }

  // The scan screen is always dark: light status bar icons while it's shown, then back as before
  val view = LocalView.current
  DisposableEffect(view) {
    val controller =
        view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
    val lightIconsBefore = controller?.isAppearanceLightStatusBars
    controller?.isAppearanceLightStatusBars = false
    onDispose {
      if (lightIconsBefore != null) controller.isAppearanceLightStatusBars = lightIconsBefore
    }
  }

  ScanContent(
      uiState = uiState,
      onBack = onBack,
      onEnterManually = onEnterManually,
      onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
      modifier = modifier,
  )
}

/**
 * Stateless content of the scan screen, so each state can be tested without a camera.
 *
 * @param onOpenSettings opens this app's page in the phone's settings, where the camera can be
 *   allowed after it was refused
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanContent(
    uiState: ScanUiState,
    onBack: () -> Unit,
    onEnterManually: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
  // A camera screen stays dark whatever the phone's theme, as in the mockup: the photo is easier to
  // judge on a dark background
  OncompanionTheme(darkTheme = true) {
    Scaffold(
        modifier = modifier.testTag(C.Tag.prescription_scan_screen),
        topBar = {
          TopAppBar(
              title = { Text(stringResource(R.string.prescription_scan_title)) },
              navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(C.Tag.prescription_scan_close_button),
                ) {
                  Icon(
                      Icons.Filled.Close,
                      contentDescription = stringResource(R.string.prescription_scan_close),
                  )
                }
              },
          )
        },
        bottomBar = {
          // Ways forward when the camera is refused
          if (uiState.permission == CameraPermission.REFUSED) {
            CameraOffActions(onEnterManually = onEnterManually, onOpenSettings = onOpenSettings)
          }
        },
    ) { padding ->
      val content = Modifier.fillMaxSize().padding(padding)
      when (uiState.permission) {
        // Android's dialog is on top: nothing to show behind it
        CameraPermission.UNKNOWN -> Box(content.testTag(C.Tag.prescription_scan_permission_pending))
        CameraPermission.GRANTED -> CameraStarting(content)
        CameraPermission.REFUSED -> CameraOff(content)
      }
    }
  }
}

/** The camera is allowed and starting: a dark viewfinder with the framing hint. */
@Composable
private fun CameraStarting(modifier: Modifier) {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(C.Tag.prescription_scan_viewfinder),
        contentAlignment = Alignment.Center,
    ) {
      CircularProgressIndicator(Modifier.testTag(C.Tag.prescription_scan_camera_starting))
    }
    Text(
        text = stringResource(R.string.prescription_scan_frame_hint),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
  }
}

/** The camera was refused: why the app needs it, and that the photo stays on the phone. */
@Composable
private fun CameraOff(modifier: Modifier) {
  Box(modifier = modifier.padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
    Column(
        modifier = Modifier.testTag(C.Tag.prescription_scan_camera_off),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
          painter = painterResource(R.drawable.ic_videocam_off),
          contentDescription = null,
          modifier = Modifier.size(48.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(16.dp))
      Text(
          text = stringResource(R.string.prescription_scan_camera_off_title),
          style = MaterialTheme.typography.titleLarge,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(12.dp))
      Text(
          text = stringResource(R.string.prescription_scan_camera_off_explanation),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
    }
  }
}

/** "Enter manually" and "Open settings", side by side at the bottom as in the mockup. */
@Composable
private fun CameraOffActions(onEnterManually: () -> Unit, onOpenSettings: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    FilledTonalButton(
        onClick = onEnterManually,
        modifier = Modifier.weight(1f).testTag(C.Tag.prescription_scan_enter_manually_button),
    ) {
      Icon(Icons.Filled.Edit, contentDescription = null)
      Text(
          stringResource(R.string.prescription_scan_enter_manually),
          Modifier.padding(start = 8.dp),
      )
    }
    FilledTonalButton(
        onClick = onOpenSettings,
        modifier = Modifier.weight(1f).testTag(C.Tag.prescription_scan_open_settings_button),
    ) {
      Icon(Icons.Filled.Settings, contentDescription = null)
      Text(stringResource(R.string.prescription_scan_open_settings), Modifier.padding(start = 8.dp))
    }
  }
}

/** The activity showing this context, if any (Compose may wrap it). */
private fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** This app's page in the phone's settings, where the camera can be allowed again. */
private fun appSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
