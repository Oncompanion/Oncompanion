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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonDefaults
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.LakeSnackbarHost
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.Spacing
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
    camera: ScanCamera = remember { ScanCameraProvider.newCamera() },
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
        snackbarHost = { LakeSnackbarHost(snackbarHostState) },
        topBar = {
          TopAppBar(
              title = {
                Text(
                    stringResource(
                        when {
                          uiState.isReading -> R.string.prescription_scan_reading_title
                          uiState.nothingRead == NothingRead.NO_TEXT ->
                              R.string.prescription_scan_nothing_read_title
                          uiState.nothingRead == NothingRead.NOT_A_PRESCRIPTION ->
                              R.string.prescription_scan_not_a_prescription_title
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
            PhotoPreview(
                uiState.photo,
                photoDispatcher,
                content,
                dimmed = uiState.isReading,
                message = {
                  when {
                    uiState.isReading -> Reading()
                    uiState.nothingRead != null -> NothingRead(uiState.nothingRead)
                    else -> CheckPhoto()
                  }
                },
                actions =
                    when {
                      uiState.isReading -> null
                      uiState.nothingRead != null -> {
                        { NothingReadActions(onEnterManually, onRetake) }
                      }
                      else -> {
                        { CheckPhotoActions(onUsePhoto, onRetake) }
                      }
                    },
            )
        else -> CameraView(uiState, onCapture, viewfinder, content)
      }
    }
  }
}

/**
 * The camera (Figma "US-10 / Prescription scan"): its preview on a darker panel, with the framing
 * guide over it and a spinner in the guide while the camera starts, then the framing hint and the
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
        modifier =
            Modifier.weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .testTag(C.Tag.prescription_scan_viewfinder),
        contentAlignment = Alignment.Center,
    ) {
      // Shown while the camera starts too: CameraX only reports it open once the preview has
      // somewhere to draw
      viewfinder(Modifier.fillMaxSize())
      // The guide shows where the page goes even before the camera is ready, as in the Figma
      FramingGuide(
          Modifier.fillMaxSize().padding(Spacing.xl).testTag(C.Tag.prescription_scan_framing_guide)
      )
      if (uiState.camera != CameraStatus.READY) {
        CircularProgressIndicator(Modifier.testTag(C.Tag.prescription_scan_camera_starting))
      }
    }
    Text(
        text = stringResource(R.string.prescription_scan_frame_hint),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(Spacing.lg),
    )
    Shutter(enabled = uiState.canCapture, isCapturing = uiState.isCapturing, onClick = onCapture)
    Spacer(Modifier.navigationBarsPadding().height(Spacing.xl))
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

/**
 * The shutter (Figma "Shutter"): a disc inside a ring, faded as a whole while a photo can't be
 * taken yet (camera starting), but not while one is being taken, when a spinner shows on the disc.
 */
@Composable
private fun Shutter(enabled: Boolean, isCapturing: Boolean, onClick: () -> Unit) {
  val description = stringResource(R.string.prescription_scan_take_photo)
  val color = MaterialTheme.colorScheme.onSurface
  Surface(
      onClick = onClick,
      enabled = enabled,
      shape = CircleShape,
      color = Color.Transparent,
      border = BorderStroke(SHUTTER_RING_WIDTH, color),
      // Screen readers announce the button, which has no visible text
      modifier =
          Modifier.size(SHUTTER_SIZE)
              .alpha(if (enabled || isCapturing) 1f else LakeButtonDefaults.DISABLED_ALPHA)
              .testTag(C.Tag.prescription_scan_capture_button)
              .semantics { contentDescription = description },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Box(Modifier.size(SHUTTER_DISC_SIZE).background(color, CircleShape))
      if (isCapturing) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(32.dp).testTag(C.Tag.prescription_scan_capturing),
        )
      }
    }
  }
}

// The Figma's shutter: a 56 dp disc in a ring 4 dp wide, 72 dp across
private val SHUTTER_SIZE = 72.dp
private val SHUTTER_DISC_SIZE = 56.dp
private val SHUTTER_RING_WIDTH = 4.dp

/**
 * The photo just taken (Figma "US-10 / Scan – Photo preview"): the photo at the top, [message]
 * under it, and [actions] at the bottom of the screen. On a short screen the photo shrinks first,
 * so the message and the buttons stay whole.
 */
@Composable
private fun PhotoPreview(
    photo: File,
    photoDispatcher: CoroutineDispatcher,
    modifier: Modifier,
    dimmed: Boolean,
    message: @Composable () -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
  Column(
      modifier = modifier.testTag(C.Tag.prescription_scan_photo_preview),
      // The photo and its message at the top, the buttons at the bottom
      verticalArrangement = Arrangement.SpaceBetween,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
        Modifier.weight(1f, fill = false).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
          Modifier.weight(1f, fill = false)
              .padding(start = PHOTO_INSET, end = PHOTO_INSET, top = Spacing.md),
          contentAlignment = Alignment.Center,
      ) {
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
                  // As large as the space allows, at the photo's own shape, so the message sits
                  // right under it. Dimmed while it is being read, as in the Figma, so the spinner
                  // below stands out
                  modifier =
                      Modifier.aspectRatio(image.bitmap.width.toFloat() / image.bitmap.height)
                          .alpha(if (dimmed) READING_ALPHA else 1f)
                          .testTag(C.Tag.prescription_scan_photo),
              )
        }
      }
      message()
    }
    actions?.invoke()
  }
}

/**
 * A sentence under the photo, as wide as the Figma's (32 dp from the edges), [top] under what is
 * above it.
 */
@Composable
private fun PhotoMessage(text: String, modifier: Modifier = Modifier, top: Dp = Spacing.lg) {
  // The space around the text is outside it, so the text's own node is only the text
  Box(Modifier.padding(start = Spacing.xxl, end = Spacing.xxl, top = top)) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
  }
}

/**
 * Under the photo just taken: the user checks it is sharp and complete before it's used (Figma
 * "US-10 / Scan – Photo preview").
 */
@Composable
private fun CheckPhoto() {
  PhotoMessage(stringResource(R.string.prescription_scan_check_hint))
}

/** "Use this photo" first, as in the Figma: it's the expected next step. */
@Composable
private fun CheckPhotoActions(onUsePhoto: () -> Unit, onRetake: () -> Unit) {
  PhotoActions(
      primary = R.string.prescription_scan_use_photo,
      onPrimary = onUsePhoto,
      primaryTag = C.Tag.prescription_scan_use_photo_button,
      secondary = R.string.prescription_scan_retake,
      onSecondary = onRetake,
      secondaryTag = C.Tag.prescription_scan_retake_button,
  )
}

/**
 * Under the photo while the prescription is read on it (Figma "US-10 / Scan – Reading the
 * prescription"): a spinner, then what is happening.
 */
@Composable
private fun Reading() {
  Column(
      modifier = Modifier.padding(top = READING_GAP).testTag(C.Tag.prescription_scan_reading),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator(Modifier.size(READING_SPINNER_SIZE))
    PhotoMessage(stringResource(R.string.prescription_scan_reading), top = Spacing.md)
  }
}

/**
 * Under the photo when no prescription could be read on it, saying [why] (Figma "US-10 / Scan –
 * Nothing read" and "Not a prescription").
 */
@Composable
private fun NothingRead(why: NothingRead) {
  PhotoMessage(
      stringResource(
          when (why) {
            NothingRead.NO_TEXT -> R.string.prescription_scan_nothing_read
            NothingRead.NOT_A_PRESCRIPTION -> R.string.prescription_scan_not_a_prescription
          }
      ),
      Modifier.testTag(C.Tag.prescription_scan_nothing_read),
  )
}

/** Entering it manually comes first, since a retake rarely helps with handwriting. */
@Composable
private fun NothingReadActions(onEnterManually: () -> Unit, onRetake: () -> Unit) {
  PhotoActions(
      primary = R.string.prescription_scan_enter_it_manually,
      onPrimary = onEnterManually,
      primaryTag = C.Tag.prescription_scan_nothing_read_enter_manually_button,
      secondary = R.string.prescription_scan_retake_photo,
      onSecondary = onRetake,
      secondaryTag = C.Tag.prescription_scan_nothing_read_retake_button,
  )
}

/** The two buttons at the bottom: the expected next step first, then the other way. */
@Composable
private fun PhotoActions(
    primary: Int,
    onPrimary: () -> Unit,
    primaryTag: String,
    secondary: Int,
    onSecondary: () -> Unit,
    secondaryTag: String,
) {
  BottomActions {
    LakeButton(
        text = stringResource(primary),
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().testTag(primaryTag),
    )
    LakeButton(
        text = stringResource(secondary),
        onClick = onSecondary,
        style = LakeButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth().testTag(secondaryTag),
    )
  }
}

/** Buttons stacked at the bottom of the screen, 8 dp apart, clear of the navigation bar. */
@Composable
private fun BottomActions(buttons: @Composable ColumnScope.() -> Unit) {
  Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      content = buttons,
  )
}

// The Figma's photo: 300 dp wide on a 390 dp screen, 45 dp from each edge
private val PHOTO_INSET = 45.dp

// The Figma's reading state: a 48 dp spinner, 52 dp under the photo
private val READING_SPINNER_SIZE = 48.dp
private val READING_GAP = 52.dp

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

// How much of the photo shows while it is being read (Figma: 50%)
private const val READING_ALPHA = 0.5f

/** Longest side of the photo on screen, in pixels: enough to check it, light enough to load. */
private const val MAX_PREVIEW_SIZE = 2048f

/**
 * The camera can't be used (refused, or no camera): why, and that the photo stays on the phone, as
 * the shared message state (Figma "US-10 / Scan – Camera access refused").
 */
@Composable
private fun CameraOff(modifier: Modifier, title: Int, explanation: Int) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    LakeMessageState(
        title = stringResource(title),
        message = stringResource(explanation),
        modifier = Modifier.testTag(C.Tag.prescription_scan_camera_off),
    )
  }
}

/**
 * The ways forward, stacked at the bottom: after a refusal "Open settings" first, since it brings
 * the camera back, then "Enter manually" (Figma); without a camera, "Enter manually" alone, as the
 * main button.
 */
@Composable
private fun CameraOffActions(onEnterManually: () -> Unit, onOpenSettings: (() -> Unit)?) {
  BottomActions {
    if (onOpenSettings != null) {
      LakeButton(
          text = stringResource(R.string.prescription_scan_open_settings),
          onClick = onOpenSettings,
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_scan_open_settings_button),
      )
    }
    LakeButton(
        text = stringResource(R.string.prescription_scan_enter_manually),
        onClick = onEnterManually,
        style = if (onOpenSettings != null) LakeButtonStyle.Tonal else LakeButtonStyle.Filled,
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_scan_enter_manually_button),
    )
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
