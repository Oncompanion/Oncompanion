// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.resources.C
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "29 Sep 2026", as in the prescription form. The app's UI is in English. */
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * The prescriptions list (US-11 "See my saved prescriptions", Figma: "US-11 / Prescription list"):
 * the user's prescriptions, most recently prescribed first, each with the names of its medications,
 * who prescribed it and its date. It is the entry point of the Prescriptions section from the
 * Overview.
 *
 * @param onAdd opens the prescription form
 * @param onScan starts scanning a prescription
 */
@Composable
fun PrescriptionsScreen(
    viewModel: PrescriptionsViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // The ViewModel outlives navigation and backgrounding: keep "This month" right
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshToday()
    onPauseOrDispose {}
  }
  PrescriptionsContent(
      uiState = uiState,
      onBack = onBack,
      onAdd = onAdd,
      onScan = onScan,
      onRetry = viewModel::load,
      modifier = modifier,
  )
}

/**
 * Stateless content of the prescriptions list, so each state can be tested without a ViewModel.
 *
 * @param onAdd opens the prescription form
 * @param onScan starts scanning a prescription
 * @param onRetry reloads the prescriptions after an error
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrescriptionsContent(
    uiState: PrescriptionsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.prescriptions_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.prescriptions_list_title)) },
            navigationIcon = {
              IconButton(
                  onClick = onBack,
                  modifier = Modifier.testTag(C.Tag.prescriptions_back_button),
              ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.prescriptions_back),
                )
              }
            },
        )
      },
      // In every state: saving a prescription works whatever the list could load
      floatingActionButton = { AddButtons(onAdd = onAdd, onScan = onScan) },
      floatingActionButtonPosition = FabPosition.Center,
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      when {
        uiState.isLoading ->
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.prescriptions_loading)
            )
        uiState.hasError -> ErrorState(onRetry)
        uiState.prescriptions.isEmpty() -> EmptyState()
        else -> PrescriptionList(uiState.thisMonth, uiState.earlier)
      }
    }
  }
}

/**
 * The two ways to add a prescription, side by side and sharing the width (Figma: "Enter manually
 * FAB" and "Scan FAB").
 */
@Composable
private fun AddButtons(onAdd: () -> Unit, onScan: () -> Unit) {
  Row(
      // With the Scaffold's own spacing, the buttons sit 24dp above the bottom as in the mockup
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    AddButton(
        label = stringResource(R.string.prescriptions_enter_manually),
        icon = rememberVectorPainter(Icons.Filled.Edit),
        onClick = onAdd,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.weight(1f).testTag(C.Tag.prescriptions_enter_manually_button),
    )
    AddButton(
        label = stringResource(R.string.prescriptions_scan),
        icon = painterResource(R.drawable.ic_document_scanner),
        onClick = onScan,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.weight(1f).testTag(C.Tag.prescriptions_scan_button),
    )
  }
}

@Composable
private fun AddButton(
    label: String,
    icon: Painter,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
  ExtendedFloatingActionButton(
      text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      // Material hides the text from screen readers, so the icon carries the label
      icon = { Icon(icon, contentDescription = label) },
      onClick = onClick,
      containerColor = containerColor,
      contentColor = contentColor,
      modifier = modifier,
  )
}

/** The two groups, each under its subheader; a group with nothing in it shows no subheader. */
@Composable
private fun PrescriptionList(thisMonth: List<Prescription>, earlier: List<Prescription>) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(C.Tag.prescriptions_list),
      // Room for the buttons, so they never hide the last prescription
      contentPadding = PaddingValues(bottom = BUTTON_ROOM),
  ) {
    if (thisMonth.isNotEmpty()) {
      item(key = C.Tag.prescriptions_this_month_header) {
        Subheader(
            stringResource(R.string.prescriptions_this_month),
            C.Tag.prescriptions_this_month_header,
        )
      }
      items(thisMonth, key = { it.id }) { PrescriptionItem(it) }
    }
    if (earlier.isNotEmpty()) {
      item(key = C.Tag.prescriptions_earlier_header) {
        Subheader(
            stringResource(R.string.prescriptions_earlier),
            C.Tag.prescriptions_earlier_header,
        )
      }
      items(earlier, key = { it.id }) { PrescriptionItem(it) }
    }
  }
}

@Composable
private fun Subheader(text: String, testTag: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp)
              .semantics { heading() }
              .testTag(testTag),
  )
}

/**
 * One prescription: the names of its medications, then who prescribed it and when ("Dr. Martin · 29
 * Sep 2026"), or the date alone when the prescriber wasn't entered.
 */
@Composable
private fun PrescriptionItem(prescription: Prescription) {
  val date = prescription.prescribedOn.format(dateFormatter)
  val prescribedBy = prescription.prescribedBy?.trim().orEmpty()
  ListItem(
      headlineContent = {
        Text(
            text = prescription.medications.joinToString(", ") { it.name },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
      },
      supportingContent = {
        Text(
            if (prescribedBy.isEmpty()) date
            else stringResource(R.string.prescriptions_item_supporting, prescribedBy, date)
        )
      },
      modifier = Modifier.testTag(C.Tag.prescriptionsItem(prescription.id)),
  )
}

/** Figma: "US-11 / Prescription list – Empty". */
@Composable
private fun EmptyState() {
  CenteredMessage(testTag = C.Tag.prescriptions_empty) {
    Icon(
        painter = painterResource(R.drawable.ic_prescriptions),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(48.dp),
    )
    MessageTexts(
        title = stringResource(R.string.prescriptions_empty_title),
        hint = stringResource(R.string.prescriptions_empty_hint),
    )
  }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
  CenteredMessage(testTag = C.Tag.prescriptions_error) {
    MessageTexts(
        title = stringResource(R.string.prescriptions_error_title),
        hint = stringResource(R.string.prescriptions_error_hint),
    )
    Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.prescriptions_retry_button)) {
      Text(stringResource(R.string.prescriptions_retry))
    }
  }
}

/** Centers [content] in the space above the buttons. */
@Composable
private fun CenteredMessage(testTag: String, content: @Composable () -> Unit) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .padding(start = 32.dp, end = 32.dp, bottom = BUTTON_ROOM)
              .testTag(testTag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
  ) {
    content()
  }
}

/** Height kept free at the bottom for the floating buttons (56dp high, 24dp above the bottom). */
private val BUTTON_ROOM = 88.dp
