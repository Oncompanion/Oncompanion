// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
import com.github.se.oncompanion.ui.common.PickerTestTags
import com.github.se.oncompanion.ui.common.SaveButton
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_INTENSITY = SymptomEntry.MIN_INTENSITY
private const val MAX_INTENSITY = SymptomEntry.MAX_INTENSITY

/**
 * How far the Material slider draws its first and last stops from its edges (half its track
 * height), to line the labels and taps up with the stops. Checked against the rendered slider.
 */
private val SliderStopInset = 8.dp

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
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Leaves the screen once the symptom is saved
  val currentOnSaved by rememberUpdatedState(onSaved)
  LaunchedEffect(uiState.isSaved) { if (uiState.isSaved) currentOnSaved() }

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
  )
}

/**
 * Stateless content of the add symptom screen, so each state can be tested without a ViewModel.
 *
 * @param onErrorShown called once the error snackbar is gone
 * @param onRetry loads the symptom to edit again after an error
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
) {
  // Not while saving or once saved: the screen then leaves by itself, and going back as well would
  // also leave the journal
  BackHandler(enabled = uiState.isSaving || uiState.isSaved) {}

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

  Scaffold(
      modifier = modifier.testTag(C.Tag.symptom_add_screen),
      topBar = {
        TopAppBar(
            title = {
              Text(
                  stringResource(
                      if (uiState.isEditing) R.string.symptom_edit_title
                      else R.string.symptom_add_title
                  )
              )
            },
            navigationIcon = {
              // Not while saving or once saved: the screen then leaves by itself, and a second way
              // out would go back twice
              IconButton(
                  onClick = onBack,
                  enabled = !uiState.isSaving && !uiState.isSaved,
                  modifier = Modifier.testTag(C.Tag.symptom_add_close),
              ) {
                Icon(Icons.Default.Close, stringResource(R.string.symptom_add_close))
              }
            },
        )
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
      // Save stays at the bottom of the screen while the fields scroll
      bottomBar = {
        if (uiState.isFormShown) {
          SaveButton(
              text =
                  stringResource(
                      if (uiState.isEditing) R.string.symptom_edit_save
                      else R.string.symptom_add_save
                  ),
              enabled = uiState.canSave,
              isSaving = uiState.isSaving,
              onClick = onSave,
              testTag = C.Tag.symptom_add_save,
              savingTestTag = C.Tag.symptom_add_saving,
          )
        }
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
          Box(Modifier.padding(innerPadding)) {
            SymptomMessage(
                title = stringResource(R.string.symptom_edit_error_title),
                hint = stringResource(R.string.symptom_edit_error_hint),
                testTag = C.Tag.symptom_edit_error,
                onRetry = onRetry,
                retryTestTag = C.Tag.symptom_edit_retry,
            )
          }
      uiState.isNotFound ->
          Box(Modifier.padding(innerPadding)) {
            SymptomMessage(
                title = stringResource(R.string.symptom_detail_not_found_title),
                hint = stringResource(R.string.symptom_detail_not_found_hint),
                testTag = C.Tag.symptom_edit_not_found,
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
              modifier = Modifier.padding(innerPadding),
          )
    }
  }
}

/** The fields of the form, which scroll between the top bar and Save. */
@Composable
private fun SymptomFields(
    uiState: AddSymptomUiState,
    onToggleType: (SymptomType) -> Unit,
    onIntensityChange: (Int) -> Unit,
    onClearIntensity: () -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onNotesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier =
          modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionTitle(stringResource(R.string.symptom_add_types))
    TypeChips(uiState.types, onToggleType)

    SectionTitle(stringResource(R.string.symptom_add_intensity))
    IntensitySlider(uiState.intensity, onIntensityChange, onClearIntensity)

    SectionTitle(stringResource(R.string.symptom_add_when))
    WhenField(uiState, onDateChange, onTimeChange)

    SectionTitle(stringResource(R.string.symptom_add_notes_title))
    NotesField(uiState, onNotesChange)
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(top = 4.dp).semantics { heading() },
  )
}

/** One chip per symptom type, checked when picked; several can be picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(picked: List<SymptomType>, onToggleType: (SymptomType) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    SymptomType.entries.forEach { type ->
      val selected = type in picked
      FilterChip(
          selected = selected,
          onClick = { onToggleType(type) },
          label = { Text(stringResource(type.label)) },
          leadingIcon =
              if (selected) {
                { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
              } else null,
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
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = valueText,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium,
        color =
            if (intensity == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).testTag(C.Tag.symptom_add_intensity_value),
    )
    if (intensity != null) {
      TextButton(
          onClick = onClear,
          modifier = Modifier.testTag(C.Tag.symptom_add_intensity_clear),
      ) {
        Text(stringResource(R.string.symptom_add_intensity_clear))
      }
    }
  }

  // "Not set" shows the whole track as inactive, without a handle or the gap around it
  val colors =
      if (intensity == null)
          SliderDefaults.colors(
              thumbColor = Color.Transparent,
              activeTrackColor = MaterialTheme.colorScheme.secondaryContainer,
              activeTickColor = MaterialTheme.colorScheme.onSecondaryContainer,
          )
      else SliderDefaults.colors()
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
        track = { state ->
          if (intensity == null) {
            SliderDefaults.Track(sliderState = state, colors = colors, thumbTrackGapSize = 0.dp)
          } else {
            SliderDefaults.Track(sliderState = state, colors = colors)
          }
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

  // The numbers under each stop and the severity bands; the slider already says the value
  Column(Modifier.clearAndSetSemantics {}) {
    ScaleLabels((MIN_INTENSITY..MAX_INTENSITY).map { it.toFloat() to it.toString() })
    ScaleLabels(
        listOf(
            midpoint(MIN_INTENSITY, Severity.MAX_MILD) to stringResource(Severity.MILD.label),
            midpoint(Severity.MAX_MILD + 1, Severity.MAX_MODERATE) to
                stringResource(Severity.MODERATE.label),
            midpoint(Severity.MAX_MODERATE + 1, MAX_INTENSITY) to
                stringResource(Severity.SEVERE.label),
        )
    )
  }
}

private fun midpoint(from: Int, to: Int) = (from + to) / 2f

/** The intensity of the stop closest to [x] on a slider [width] wide. */
private fun intensityAt(x: Float, width: Int, inset: Float): Int {
  val fraction = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
  return (MIN_INTENSITY + fraction * (MAX_INTENSITY - MIN_INTENSITY)).roundToInt()
}

/** Texts centered under the slider at the given intensities, e.g. `2f to "Mild"`. */
@Composable
private fun ScaleLabels(labels: List<Pair<Float, String>>) {
  Layout(
      content = {
        labels.forEach { (_, text) ->
          Text(
              text = text,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      modifier = Modifier.fillMaxWidth(),
  ) { measurables, constraints ->
    val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
    val width = constraints.maxWidth
    val inset = SliderStopInset.roundToPx()
    layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
      placeables.forEachIndexed { index, placeable ->
        val fraction = (labels[index].first - MIN_INTENSITY) / (MAX_INTENSITY - MIN_INTENSITY)
        val center = inset + fraction * (width - 2 * inset)
        val x = (center - placeable.width / 2f).roundToInt()
        placeable.placeRelative(x.coerceIn(0, (width - placeable.width).coerceAtLeast(0)), 0)
      }
    }
  }
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
        TimePickerDialogFor(
            time = time,
            onConfirm = {
              onTimeChange(it)
              picker = WhenPicker.NONE
            },
            onDismiss = { picker = WhenPicker.NONE },
        )
    WhenPicker.NONE -> {}
  }
}

/** Picks a time of day, in 24-hour format as shown everywhere in the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogFor(
    time: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
  val pickerState =
      rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
  AlertDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
            onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) },
            modifier = Modifier.testTag(C.Tag.symptom_add_picker_confirm),
        ) {
          Text(stringResource(R.string.symptom_add_picker_confirm))
        }
      },
      dismissButton = {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(C.Tag.symptom_add_picker_cancel),
        ) {
          Text(stringResource(R.string.symptom_add_picker_cancel))
        }
      },
      text = {
        TimePicker(state = pickerState, modifier = Modifier.testTag(C.Tag.symptom_add_time_picker))
      },
  )
}

/** Free text; with "Other" it asks which symptom it was, and is required. */
@Composable
private fun NotesField(uiState: AddSymptomUiState, onNotesChange: (String) -> Unit) {
  OutlinedTextField(
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
