// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scan a paper prescription with the camera (US-10, Figma "US-10 / Prescription scan"). The camera
 * permission is asked when the screen opens, the first time the user scans: asked at app start,
 * without context, it is refused more often. If it's refused, the screen explains why the camera is
 * needed and offers to enter the prescription manually instead.
 *
 * @param onEnterManually opens the manual entry form (US-24)
 * @param onBack leaves the scan screen
 * @param onRead opens the form with the prescription read on the photo, which is now its own
 * @param camera the camera; replaced by a fake in tests
 */
@Composable
fun ScanScreen(
    onEnterManually: () -> Unit,
    onBack: () -> Unit,
    onRead: (ReadScan) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = scanViewModel(),
    camera: ScanCamera = remember { CameraXScanCamera() },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // A prescription was read: the form opens with it, once
  LaunchedEffect(uiState.read) {
    uiState.read?.let {
      onRead(it)
      viewModel.onReadOpened()
    }
  }

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

  // Turns the camera on once it's allowed and passes on what it reports; leaving the screen stops
  // the collection, which turns the camera off. A new camera (e.g. after a rotation) starts again
  // from "starting", so no photo can be taken before it's open
  if (uiState.permission == CameraPermission.GRANTED) {
    LaunchedEffect(camera) {
      try {
        camera.open(context, lifecycleOwner).collect { status ->
          when (status) {
            CameraStatus.STARTING -> viewModel.onCameraStarting()
            CameraStatus.READY -> viewModel.onCameraReady()
            CameraStatus.UNAVAILABLE -> viewModel.onCameraUnavailable()
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't turn the camera on", e)
        viewModel.onCameraUnavailable()
      }
    }
  }

  ScanContent(
      uiState = uiState,
      onBack = {
        viewModel.onClose()
        onBack()
      },
      onEnterManually = {
        viewModel.onClose()
        onEnterManually()
      },
      onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
      onCapture = { viewModel.capture { file -> camera.takePhoto(context, file) } },
      onMessageShown = viewModel::onMessageShown,
      onRetake = viewModel::retake,
      onUsePhoto = viewModel::usePhoto,
      viewfinder = { camera.Viewfinder(it) },
      modifier = modifier,
  )
}

/** The scan view model, with its photos kept in the app's cache. */
@Composable
private fun scanViewModel(): ScanViewModel {
  val cacheDir = LocalContext.current.cacheDir
  return viewModel { ScanViewModel(ScanPhotoStore(cacheDir)) }
}

/**
 * Stateless content of the scan screen, so each state can be tested without a camera.
 *
 * @param onOpenSettings opens this app's page in the phone's settings, where the camera can be
 *   allowed after it was refused
 * @param onCapture takes a photo
 * @param onMessageShown called once the message about the failed photo is gone
 * @param onRetake deletes the photo being checked and shows the camera again
 * @param onUsePhoto uses the photo being checked
 * @param viewfinder what the camera sees
 * @param photoDispatcher where the photo is read for the preview, off the main thread; tests pass
 *   their own to control when it's read
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanContent(
    uiState: ScanUiState,
    onBack: () -> Unit,
    onEnterManually: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onCapture: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    onRetake: () -> Unit = {},
    onUsePhoto: () -> Unit = {},
    viewfinder: @Composable (Modifier) -> Unit = {},
    photoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  // The message about the failed photo, then the view model forgets it
  val snackbarHostState = remember { SnackbarHostState() }
  val captureFailed = stringResource(R.string.prescription_scan_capture_failed)
  LaunchedEffect(uiState.captureFailed) {
    if (!uiState.captureFailed) return@LaunchedEffect
    snackbarHostState.showSnackbar(captureFailed)
    onMessageShown()
  }

  val cameraOff =
      uiState.permission == CameraPermission.REFUSED ||
          (uiState.permission == CameraPermission.GRANTED &&
              uiState.camera == CameraStatus.UNAVAILABLE)

  // A camera screen stays dark whatever the phone's theme, as in the mockup: the photo is easier to
  // judge on a dark background
  OncompanionTheme(darkTheme = true) {
    Scaffold(
        modifier = modifier.testTag(C.Tag.prescription_scan_screen),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
          TopAppBar(
              title = {
                Text(
                    stringResource(
                        when {
                          uiState.isReading -> R.string.prescription_scan_reading_title
                          uiState.nothingRead -> R.string.prescription_scan_nothing_read_title
                          uiState.photo != null -> R.string.prescription_scan_check_title
                          else -> R.string.prescription_scan_title
                        }
                    )
                )
              },
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
          // Ways forward when the camera can't be used; "Open settings" only helps after a refusal
          if (cameraOff) {
            CameraOffActions(
                onEnterManually = onEnterManually,
                onOpenSettings =
                    onOpenSettings.takeIf { uiState.permission == CameraPermission.REFUSED },
            )
          }
        },
    ) { padding ->
      val content = Modifier.fillMaxSize().padding(padding)
      when {
        // Android's dialog is on top: nothing to show behind it
        uiState.permission == CameraPermission.UNKNOWN ->
            Box(content.testTag(C.Tag.prescription_scan_permission_pending))
        uiState.permission == CameraPermission.REFUSED ->
            CameraOff(
                content,
                R.string.prescription_scan_camera_off_title,
                R.string.prescription_scan_camera_off_explanation,
            )
        uiState.camera == CameraStatus.UNAVAILABLE ->
            CameraOff(
                content,
                R.string.prescription_scan_camera_unavailable_title,
                R.string.prescription_scan_camera_unavailable_explanation,
            )
        uiState.photo != null ->
            PhotoPreview(uiState.photo, photoDispatcher, content) {
              when {
                uiState.isReading -> Reading()
                uiState.nothingRead -> NothingRead(onEnterManually, onRetake)
                else -> CheckPhoto(onUsePhoto, onRetake)
              }
            }
        else -> CameraView(uiState, onCapture, viewfinder, content)
      }
    }
  }
}

/**
 * The camera: its preview (with a spinner over it while it starts), the framing hint and the
 * shutter.
 */
@Composable
private fun CameraView(
    uiState: ScanUiState,
    onCapture: () -> Unit,
    viewfinder: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(C.Tag.prescription_scan_viewfinder),
        contentAlignment = Alignment.Center,
    ) {
      // Shown while the camera starts too: CameraX only reports it open once the preview has
      // somewhere to draw
      viewfinder(Modifier.fillMaxSize())
      if (uiState.camera == CameraStatus.READY) {
        FramingGuide(
            Modifier.fillMaxSize().padding(24.dp).testTag(C.Tag.prescription_scan_framing_guide)
        )
      } else {
        CircularProgressIndicator(Modifier.testTag(C.Tag.prescription_scan_camera_starting))
      }
    }
    Text(
        text = stringResource(R.string.prescription_scan_frame_hint),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
    Shutter(enabled = uiState.canCapture, isCapturing = uiState.isCapturing, onClick = onCapture)
    Spacer(Modifier.navigationBarsPadding().height(24.dp))
  }
}

/**
 * The four corners of an A4 page in portrait, as in the mockup, so the user fills the photo with
 * the prescription: the bigger the text, the easier it is to read.
 */
@Composable
private fun FramingGuide(modifier: Modifier) {
  val color = MaterialTheme.colorScheme.primary
  Canvas(modifier) {
    // The largest A4 rectangle (1 : 1.41) that fits, centred
    val pageWidth = minOf(size.width, size.height / A4_RATIO)
    val pageHeight = pageWidth * A4_RATIO
    val left = (size.width - pageWidth) / 2
    val top = (size.height - pageHeight) / 2
    val corner = pageWidth * 0.12f
    val stroke = 4.dp.toPx()
    for ((x, dx) in listOf(left to 1f, left + pageWidth to -1f)) {
      for ((y, dy) in listOf(top to 1f, top + pageHeight to -1f)) {
        drawLine(color, Offset(x, y), Offset(x + dx * corner, y), stroke, StrokeCap.Round)
        drawLine(color, Offset(x, y), Offset(x, y + dy * corner), stroke, StrokeCap.Round)
      }
    }
  }
}

/** Height / width of an A4 page. */
private const val A4_RATIO = 1.414f

/** The round shutter button; a spinner replaces it while the photo is being taken. */
@Composable
private fun Shutter(enabled: Boolean, isCapturing: Boolean, onClick: () -> Unit) {
  val description = stringResource(R.string.prescription_scan_take_photo)
  Surface(
      onClick = onClick,
      enabled = enabled,
      shape = CircleShape,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
      border = BorderStroke(4.dp, MaterialTheme.colorScheme.surface),
      // Screen readers announce the button, which has no visible text
      modifier =
          Modifier.size(76.dp).testTag(C.Tag.prescription_scan_capture_button).semantics {
            contentDescription = description
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      if (isCapturing) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(32.dp).testTag(C.Tag.prescription_scan_capturing),
        )
      }
    }
  }
}

/**
 * The photo just taken, with [below] it what can be done with it: check it, see it being read, or
 * see that nothing could be read on it.
 */
@Composable
private fun PhotoPreview(
    photo: File,
    photoDispatcher: CoroutineDispatcher,
    modifier: Modifier,
    below: @Composable ColumnScope.() -> Unit,
) {
  Column(
      modifier = modifier.testTag(C.Tag.prescription_scan_photo_preview),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
      when (val image = rememberPhoto(photo, photoDispatcher)) {
        PhotoImage.Loading -> CircularProgressIndicator()
        PhotoImage.Unreadable ->
            Text(
                text = stringResource(R.string.prescription_scan_photo_unreadable),
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(C.Tag.prescription_scan_photo_unreadable),
            )
        is PhotoImage.Loaded ->
            Image(
                bitmap = image.bitmap,
                contentDescription = stringResource(R.string.prescription_scan_photo_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag(C.Tag.prescription_scan_photo),
            )
      }
    }
    below()
  }
}

/**
 * Below the photo just taken: the user checks it is sharp and complete before it's used (Figma
 * "US-10 / Scan – Photo preview").
 */
@Composable
private fun CheckPhoto(onUsePhoto: () -> Unit, onRetake: () -> Unit) {
  Text(
      text = stringResource(R.string.prescription_scan_check_hint),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(horizontal = 16.dp),
  )
  // "Use this photo" first, as in the mockup: it's the expected next step
  PhotoActions(
      primary = R.string.prescription_scan_use_photo,
      onPrimary = onUsePhoto,
      primaryTag = C.Tag.prescription_scan_use_photo_button,
      secondary = R.string.prescription_scan_retake,
      onSecondary = onRetake,
      secondaryTag = C.Tag.prescription_scan_retake_button,
  )
}

/** Below the photo while the prescription is read on it (Figma "US-10 / Scan – Reading"). */
@Composable
private fun Reading() {
  Column(
      modifier =
          Modifier.navigationBarsPadding().padding(24.dp).testTag(C.Tag.prescription_scan_reading),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator()
    Text(
        text = stringResource(R.string.prescription_scan_reading),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
  }
}

/**
 * Below the photo when no prescription could be read on it (Figma "US-10 / Scan – Nothing read"):
 * entering it manually comes first, since a retake rarely helps with handwriting.
 */
@Composable
private fun NothingRead(onEnterManually: () -> Unit, onRetake: () -> Unit) {
  Text(
      text = stringResource(R.string.prescription_scan_nothing_read),
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 16.dp).testTag(C.Tag.prescription_scan_nothing_read),
  )
  PhotoActions(
      primary = R.string.prescription_scan_enter_it_manually,
      onPrimary = onEnterManually,
      primaryTag = C.Tag.prescription_scan_nothing_read_enter_manually_button,
      secondary = R.string.prescription_scan_retake_photo,
      onSecondary = onRetake,
      secondaryTag = C.Tag.prescription_scan_nothing_read_retake_button,
  )
}

/** The two buttons below the photo: the expected next step first, then the other way. */
@Composable
private fun PhotoActions(
    primary: Int,
    onPrimary: () -> Unit,
    primaryTag: String,
    secondary: Int,
    onSecondary: () -> Unit,
    secondaryTag: String,
) {
  Button(
      onClick = onPrimary,
      modifier =
          Modifier.fillMaxWidth()
              .padding(start = 16.dp, end = 16.dp, top = 16.dp)
              .testTag(primaryTag),
  ) {
    Text(stringResource(primary))
  }
  OutlinedButton(
      onClick = onSecondary,
      modifier =
          Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag(secondaryTag),
  ) {
    Text(stringResource(secondary))
  }
}

/** The photo as shown on screen: being read, impossible to read, or ready. */
private sealed interface PhotoImage {
  data object Loading : PhotoImage

  data object Unreadable : PhotoImage

  data class Loaded(val bitmap: ImageBitmap) : PhotoImage
}

/**
 * Reads [photo] on [dispatcher], outside the main thread, so the screen doesn't freeze on a
 * 12-megapixel photo. `ImageDecoder` turns it the right way up (portrait photos are saved sideways
 * with an orientation tag) and shrinks it to the screen: the full-size photo isn't needed to check
 * it.
 */
@Composable
private fun rememberPhoto(photo: File, dispatcher: CoroutineDispatcher): PhotoImage =
    produceState<PhotoImage>(PhotoImage.Loading, photo, dispatcher) {
          value =
              withContext(dispatcher) {
                try {
                  val source = ImageDecoder.createSource(photo)
                  val bitmap =
                      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val scale = MAX_PREVIEW_SIZE / maxOf(info.size.width, info.size.height)
                        if (scale < 1f) {
                          decoder.setTargetSize(
                              (info.size.width * scale).toInt(),
                              (info.size.height * scale).toInt(),
                          )
                        }
                      }
                  PhotoImage.Loaded(bitmap.asImageBitmap())
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  Log.w(TAG, "Couldn't read the photo", e)
                  PhotoImage.Unreadable
                }
              }
        }
        .value

/** Longest side of the photo on screen, in pixels: enough to check it, light enough to load. */
private const val MAX_PREVIEW_SIZE = 2048f

/** The camera can't be used (refused, or no camera): why, and that the photo stays on the phone. */
@Composable
private fun CameraOff(modifier: Modifier, title: Int, explanation: Int) {
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
          text = stringResource(title),
          style = MaterialTheme.typography.titleLarge,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(12.dp))
      Text(
          text = stringResource(explanation),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
    }
  }
}

/** "Enter manually" and, after a refusal, "Open settings", side by side as in the mockup. */
@Composable
private fun CameraOffActions(onEnterManually: () -> Unit, onOpenSettings: (() -> Unit)?) {
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
    if (onOpenSettings != null) {
      FilledTonalButton(
          onClick = onOpenSettings,
          modifier = Modifier.weight(1f).testTag(C.Tag.prescription_scan_open_settings_button),
      ) {
        Icon(Icons.Filled.Settings, contentDescription = null)
        Text(
            stringResource(R.string.prescription_scan_open_settings),
            Modifier.padding(start = 8.dp),
        )
      }
    }
  }
}

private const val TAG = "ScanScreen"

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
