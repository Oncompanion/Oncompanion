// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.ClickableReadOnlyField
import com.github.se.oncompanion.ui.common.DayPickerDialog
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.LakeFilterChip
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.LakeSnackbarHost
import com.github.se.oncompanion.ui.common.LakeTextField
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.common.PickerTestTags
import com.github.se.oncompanion.ui.common.SaveButton
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.common.TimePickerDialog
import com.github.se.oncompanion.ui.common.isCompactHeight
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The smallest touch target, for the row of the intensity value and Clear
private val TOUCH_TARGET = 48.dp

private const val MIN_INTENSITY = SymptomEntry.MIN_INTENSITY
private const val MAX_INTENSITY = SymptomEntry.MAX_INTENSITY

/**
 * How far the Material slider draws its first and last stops from its edges (half its track
 * height), to line the taps up with the stops. Checked against the rendered slider.
 */
private val SliderStopInset = 8.dp

/**
 * The gap around the handle, Material's "active handle leading space" (private in SliderDefaults).
 */
private val THUMB_TRACK_GAP = 6.dp

/** Which picker of the "When" field is open. */
private enum class WhenPicker {
  NONE,
  DATE,
  TIME,
}

/**
 * The add symptom screen (US-07 "Log a symptom", Figma: "US-04 / Log symptoms"): the user picks
 * what they feel and saves it, optionally with how bad, when and notes. With a ViewModel editing a
 * symptom, it is the edit screen instead (US-09, Figma: "US-9 / Edit symptom entry").
 *
 * @param onBack called when the user leaves without saving
 * @param onSaved called once the symptom is saved
 */
@Composable
fun AddSymptomScreen(
    viewModel: AddSymptomViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    caregiverOf: String? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Leaves the screen once the symptom is saved
  val currentOnSaved by rememberUpdatedState(onSaved)
  LaunchedEffect(uiState.save) { if (uiState.save == SavePhase.SAVED) currentOnSaved() }

  // Keeps the time shown (and the check that the chosen time isn't later) current while the form
  // is on screen: refreshes when it comes back, then each time a new minute starts
  val scope = rememberCoroutineScope()
  LifecycleResumeEffect(viewModel) {
    val ticker = scope.launch {
      while (true) {
        delay(viewModel.refreshNow().toMillis())
      }
    }
    onPauseOrDispose { ticker.cancel() }
  }

  AddSymptomContent(
      uiState = uiState,
      onBack = onBack,
      onToggleType = viewModel::toggleType,
      onIntensityChange = viewModel::onIntensityChange,
      onClearIntensity = viewModel::clearIntensity,
      onDateChange = viewModel::onDateChange,
      onTimeChange = viewModel::onTimeChange,
      onNotesChange = viewModel::onNotesChange,
      onSave = viewModel::save,
      onErrorShown = viewModel::clearError,
      onRetry = viewModel::load,
      modifier = modifier,
      caregiverOf = caregiverOf,
  )
}

/**
 * Stateless content of the add symptom screen, so each state can be tested without a ViewModel.
 *
 * @param onErrorShown called once the error snackbar is gone
 * @param onRetry loads the symptom to edit again after an error
 * @param caregiverOf in caregiver mode, the first name of the patient whose journal gets the
 *   symptom, said in the title so the caregiver knows where it goes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSymptomContent(
    uiState: AddSymptomUiState,
    onBack: () -> Unit,
    onToggleType: (SymptomType) -> Unit,
    onIntensityChange: (Int) -> Unit,
    onClearIntensity: () -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    caregiverOf: String? = null,
) {
  // Not while saving or once saved: the screen then leaves by itself, and going back as well would
  // also leave the journal
  BackHandler(enabled = uiState.save != SavePhase.EDITING) {}

  // Shows each saving error once in a snackbar, then tells the view model it was shown
  val snackbarHostState = remember { SnackbarHostState() }
  val errorMessage = uiState.error?.let { stringResource(it.messageRes()) }
  val currentOnErrorShown by rememberUpdatedState(onErrorShown)
  LaunchedEffect(uiState.error) {
    if (errorMessage != null) {
      snackbarHostState.showSnackbar(errorMessage)
      currentOnErrorShown()
    }
  }

  // A short screen (a phone held sideways) keeps nothing fixed: with the keyboard open, a fixed top
  // bar and Save would leave no room for the notes being typed
  val scrollsEverything = isCompactHeight()

  Scaffold(
      // Ends the screen at the keyboard (the activity is adjustResize): Save sits just above it
      modifier = modifier.testTag(C.Tag.symptom_add_screen).imePadding(),
      // Without the form there is no keyboard, so the top bar can stay fixed
      topBar = {
        if (!scrollsEverything || !uiState.isFormShown) FormTopBar(uiState, caregiverOf, onBack)
      },
      snackbarHost = { LakeSnackbarHost(snackbarHostState) },
      // Save stays at the bottom of the screen while the fields scroll
      bottomBar = {
        if (!scrollsEverything && uiState.isFormShown) FormSaveButton(uiState, onSave)
      },
  ) { innerPadding ->
    // When editing, the symptom is loaded before the form can show it
    when {
      uiState.isLoading ->
          Box(Modifier.fillMaxSize().padding(innerPadding)) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.symptom_edit_loading)
            )
          }
      uiState.hasLoadError ->
          CenteredMessage(Modifier.padding(innerPadding)) {
            LakeMessageState(
                title = stringResource(R.string.symptom_edit_error_title),
                message = stringResource(R.string.symptom_edit_error_hint),
                actionLabel = stringResource(R.string.symptom_retry),
                onAction = onRetry,
                actionTestTag = C.Tag.symptom_edit_retry,
                modifier = Modifier.testTag(C.Tag.symptom_edit_error),
            )
          }
      uiState.isNotFound ->
          CenteredMessage(Modifier.padding(innerPadding)) {
            LakeMessageState(
                title = stringResource(R.string.symptom_detail_not_found_title),
                message = stringResource(R.string.symptom_detail_not_found_hint),
                modifier = Modifier.testTag(C.Tag.symptom_edit_not_found),
            )
          }
      else ->
          SymptomFields(
              uiState = uiState,
              onToggleType = onToggleType,
              onIntensityChange = onIntensityChange,
              onClearIntensity = onClearIntensity,
              onDateChange = onDateChange,
              onTimeChange = onTimeChange,
              onNotesChange = onNotesChange,
              scrollsEverything = scrollsEverything,
              caregiverOf = caregiverOf,
              onBack = onBack,
              onSave = onSave,
              modifier = Modifier.padding(innerPadding),
          )
    }
  }
}

/**
 * Centers a message in the space under the top bar, and lets it scroll when it is taller than that
 * space (a phone held sideways under the lake band), so its button can still be reached.
 */
@Composable
private fun CenteredMessage(modifier: Modifier, content: @Composable () -> Unit) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    Box(
        Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = maxHeight),
        contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

/**
 * The title on the Paper lake band, with the button that leaves the form without saving.
 * [caregiverOf] is the patient a caregiver logs the symptom for, `null` for the user's own journal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormTopBar(
    uiState: AddSymptomUiState,
    caregiverOf: String?,
    onBack: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
  LakeTopAppBar(
      title =
          when {
            // A caregiver sees whose journal gets the symptom, or whose entry they edit (US-46)
            uiState.isEditing && caregiverOf != null ->
                stringResource(R.string.symptom_edit_title_for, caregiverOf)
            uiState.isEditing -> stringResource(R.string.symptom_edit_title)
            caregiverOf != null -> stringResource(R.string.symptom_add_title_for, caregiverOf)
            else -> stringResource(R.string.symptom_add_title)
          },
      navigationIcon = {
        // Not while saving or once saved: the screen then leaves by itself, and a second way out
        // would go back twice
        IconButton(
            onClick = onBack,
            enabled = uiState.save == SavePhase.EDITING,
            modifier = Modifier.testTag(C.Tag.symptom_add_close),
        ) {
          Icon(painterResource(R.drawable.ic_close), stringResource(R.string.symptom_add_close))
        }
      },
      windowInsets = windowInsets,
  )
}

/** The button that saves the symptom, worded for logging or for editing. */
@Composable
private fun FormSaveButton(uiState: AddSymptomUiState, onSave: () -> Unit) {
  SaveButton(
      text =
          stringResource(
              if (uiState.isEditing) R.string.symptom_edit_save else R.string.symptom_add_save
          ),
      enabled = uiState.canSave,
      isSaving = uiState.save == SavePhase.SAVING,
      onClick = onSave,
      testTag = C.Tag.symptom_add_save,
      savingTestTag = C.Tag.symptom_add_saving,
  )
}

/**
 * The fields of the form, which scroll between the top bar and Save, or with them when
 * [scrollsEverything] is set.
 */
@Composable
private fun SymptomFields(
    uiState: AddSymptomUiState,
    onToggleType: (SymptomType) -> Unit,
    onIntensityChange: (Int) -> Unit,
    onClearIntensity: () -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onNotesChange: (String) -> Unit,
    scrollsEverything: Boolean,
    caregiverOf: String?,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    // The Scaffold already keeps the content clear of the status bar
    if (scrollsEverything) FormTopBar(uiState, caregiverOf, onBack, WindowInsets(0))
    // The form groups, 24 dp apart; each is its question over its control
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
      FormGroup(R.string.symptom_add_types) { TypeChips(uiState.types, onToggleType) }
      FormGroup(R.string.symptom_add_intensity) {
        IntensitySlider(uiState.intensity, onIntensityChange, onClearIntensity)
      }
      FormGroup(R.string.symptom_add_when) { WhenField(uiState, onDateChange, onTimeChange) }
      FormGroup(R.string.symptom_add_notes_title) { NotesField(uiState, onNotesChange) }
    }
    if (scrollsEverything) FormSaveButton(uiState, onSave)
  }
}

/** A question as a heading, with its control right under it. */
@Composable
private fun FormGroup(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    content()
  }
}

/** One chip per symptom type, checked when picked; several can be picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(picked: List<SymptomType>, onToggleType: (SymptomType) -> Unit) {
  FlowRow(
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    SymptomType.entries.forEach { type ->
      val selected = type in picked
      LakeFilterChip(
          selected = selected,
          onClick = { onToggleType(type) },
          label = stringResource(type.label),
          modifier = Modifier.testTag(C.Tag.symptomAddType(type.name)),
      )
    }
  }
}

/**
 * The optional intensity: "Not set" until the user moves the slider, then the value and its
 * severity in words with Clear to go back to "Not set". It only restates what the user picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntensitySlider(
    intensity: Int?,
    onIntensityChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
  val valueText =
      if (intensity == null) stringResource(R.string.symptom_add_intensity_not_set)
      else
          stringResource(
              R.string.symptom_add_intensity_value,
              intensity,
              stringResource(Severity.of(intensity).label),
          )

  // The value in words, and Clear once there is one
  Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = valueText,
        style = MaterialTheme.typography.titleLarge,
        color =
            if (intensity == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).testTag(C.Tag.symptom_add_intensity_value),
    )
    if (intensity != null) {
      LakeButton(
          text = stringResource(R.string.symptom_add_intensity_clear),
          onClick = onClear,
          style = LakeButtonStyle.Text,
          modifier = Modifier.testTag(C.Tag.symptom_add_intensity_clear),
      )
    }
  }

  // The track is light teal where it isn't picked; "Not set" shows all of it so, without a handle
  // or the gap around it
  val inactive = MaterialTheme.colorScheme.primaryContainer
  val colors =
      if (intensity == null)
          SliderDefaults.colors(
              thumbColor = Color.Transparent,
              activeTrackColor = inactive,
              inactiveTrackColor = inactive,
          )
      else SliderDefaults.colors(inactiveTrackColor = inactive)
  // While "Not set" the slider sits on 1 and ignores a touch there, as nothing changes for it: a
  // touch closest to 1 sets it here. The touch isn't taken away, so the slider still follows a
  // drag,
  // and it handles every other touch itself
  val currentIntensity by rememberUpdatedState(intensity)
  val currentOnIntensityChange by rememberUpdatedState(onIntensityChange)
  Box(
      Modifier.pointerInput(Unit) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
              val touched = intensityAt(down.position.x, size.width, SliderStopInset.toPx())
              if (currentIntensity == null && touched == MIN_INTENSITY) {
                currentOnIntensityChange(MIN_INTENSITY)
              }
            }
          }
          .testTag(C.Tag.symptom_add_intensity_track)
  ) {
    Slider(
        value = (intensity ?: MIN_INTENSITY).toFloat(),
        onValueChange = { onIntensityChange(it.roundToInt()) },
        valueRange = MIN_INTENSITY.toFloat()..MAX_INTENSITY.toFloat(),
        steps = MAX_INTENSITY - MIN_INTENSITY - 1,
        colors = colors,
        // No dots on the track: the value above says what is picked
        track = { state ->
          SliderDefaults.Track(
              sliderState = state,
              colors = colors,
              drawStopIndicator = null,
              drawTick = { _, _ -> },
              thumbTrackGapSize = if (intensity == null) 0.dp else THUMB_TRACK_GAP,
          )
        },
        modifier =
            Modifier.fillMaxWidth()
                .semantics {
                  stateDescription = valueText
                  // Screen readers can set 1 too, which the slider would ignore while "Not set"
                  if (intensity == null) {
                    setProgress { target ->
                      onIntensityChange(target.roundToInt().coerceIn(MIN_INTENSITY, MAX_INTENSITY))
                      true
                    }
                  }
                }
                .testTag(C.Tag.symptom_add_intensity),
    )
  }

  // The words at the start, middle and end of the track, each in its own third so they never
  // overlap (a word too long for it wraps); the slider already says the value
  Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
    ScaleLabel(Severity.MILD, Alignment.Start, TextAlign.Start)
    ScaleLabel(Severity.MODERATE, Alignment.CenterHorizontally, TextAlign.Center)
    ScaleLabel(Severity.SEVERE, Alignment.End, TextAlign.End)
  }
}

/** A word of the scale under the slider, put at [alignment] in its third of the width. */
@Composable
private fun RowScope.ScaleLabel(
    severity: Severity,
    alignment: Alignment.Horizontal,
    textAlign: TextAlign,
) {
  Text(
      text = stringResource(severity.label),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = textAlign,
      modifier = Modifier.weight(1f).wrapContentWidth(alignment),
  )
}

/** The intensity of the stop closest to [x] on a slider [width] wide. */
private fun intensityAt(x: Float, width: Int, inset: Float): Int {
  val fraction = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
  return (MIN_INTENSITY + fraction * (MAX_INTENSITY - MIN_INTENSITY)).roundToInt()
}

/**
 * The day and time, "Today" and the current time until the user changes them. Tapping it picks a
 * day, then a time.
 */
@Composable
private fun WhenField(
    uiState: AddSymptomUiState,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
  var picker by rememberSaveable { mutableStateOf(WhenPicker.NONE) }
  val today = uiState.now.toLocalDate()
  val date = uiState.date ?: today
  val time = uiState.time ?: uiState.now.toLocalTime()

  ClickableReadOnlyField(
      value =
          stringResource(
              R.string.symptom_add_date_time_value,
              dayLabel(date, today),
              time.format(symptomTimeFormatter),
          ),
      label = stringResource(R.string.symptom_add_date_time),
      onClick = { picker = WhenPicker.DATE },
      onClickLabel = stringResource(R.string.symptom_add_change_date_time),
      trailingIcon = { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null) },
      // The error if the time is later than now, else the hint while the time is still "now"
      supportingText =
          when {
            uiState.isInFuture -> {
              { Text(stringResource(R.string.symptom_add_in_future)) }
            }
            uiState.isNow -> {
              { Text(stringResource(R.string.symptom_add_date_time_hint)) }
            }
            else -> null
          },
      isError = uiState.isInFuture,
      modifier = Modifier.testTag(C.Tag.symptom_add_when),
  )

  // The day first, then the time; cancelling the time keeps the confirmed day
  when (picker) {
    WhenPicker.DATE ->
        DayPickerDialog(
            date = date,
            onConfirm = {
              onDateChange(it)
              picker = WhenPicker.TIME
            },
            onDismiss = { picker = WhenPicker.NONE },
            confirmLabel = stringResource(R.string.symptom_add_picker_confirm),
            cancelLabel = stringResource(R.string.symptom_add_picker_cancel),
            testTags =
                PickerTestTags(
                    picker = C.Tag.symptom_add_date_picker,
                    confirm = C.Tag.symptom_add_picker_confirm,
                    cancel = C.Tag.symptom_add_picker_cancel,
                ),
            // A symptom can't be logged in advance
            latestDate = today,
        )
    WhenPicker.TIME ->
        TimePickerDialog(
            time = time,
            onConfirm = {
              onTimeChange(it)
              picker = WhenPicker.NONE
            },
            onDismiss = { picker = WhenPicker.NONE },
            confirmLabel = stringResource(R.string.symptom_add_picker_confirm),
            cancelLabel = stringResource(R.string.symptom_add_picker_cancel),
            // As the symptom screens show times (HH:mm), whatever the phone's setting
            is24Hour = true,
            testTags =
                PickerTestTags(
                    picker = C.Tag.symptom_add_time_picker,
                    confirm = C.Tag.symptom_add_picker_confirm,
                    cancel = C.Tag.symptom_add_picker_cancel,
                ),
        )
    WhenPicker.NONE -> {}
  }
}

/** Free text; with "Other" it asks which symptom it was, and is required. */
@Composable
private fun NotesField(uiState: AddSymptomUiState, onNotesChange: (String) -> Unit) {
  LakeTextField(
      value = uiState.notes,
      onValueChange = onNotesChange,
      label = { Text(stringResource(R.string.symptom_add_notes)) },
      placeholder =
          if (uiState.notesRequired) {
            { Text(stringResource(R.string.symptom_add_notes_other_placeholder)) }
          } else null,
      supportingText =
          if (uiState.notesRequired) {
            { Text(stringResource(R.string.symptom_add_notes_required)) }
          } else null,
      maxLines = 6,
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptom_add_notes),
  )
}

/** The message shown in the snackbar for each saving error. */
private fun AddSymptomError.messageRes(): Int =
    when (this) {
      AddSymptomError.NOT_SIGNED_IN -> R.string.symptom_add_error_not_signed_in
      AddSymptomError.SAVE_FAILED -> R.string.symptom_add_error_save_failed
    }
