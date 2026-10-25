// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * "Whose care?" (US-20, Figma: "US-20 / Whose care?"): the patients a caregiver follows, with what
 * each one shares, to choose whose care to look at.
 *
 * @param onBack goes back, or `null` when the screen is the first one (no back arrow)
 * @param onPatientChosen called with the chosen patient
 * @param onJoinAnother opens the join screen, to follow one more patient
 * @param autoSelectSingle when the caregiver follows only one patient, choose them without showing
 *   the list (when opening the app), rather than when switching on purpose
 */
@Composable
fun WhoseCareScreen(
    viewModel: WhoseCareViewModel,
    onBack: (() -> Unit)?,
    onPatientChosen: (FollowedPatient) -> Unit,
    onJoinAnother: () -> Unit,
    autoSelectSingle: Boolean,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // Only once: coming back to the screen must not choose again
  var autoSelected by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(uiState) {
    val single = uiState.patients.singleOrNull()
    if (autoSelectSingle && !autoSelected && !uiState.isLoading && single != null) {
      autoSelected = true
      onPatientChosen(single)
    }
  }
  WhoseCareContent(
      uiState = uiState,
      onBack = onBack,
      onPatientClick = onPatientChosen,
      onJoinAnother = onJoinAnother,
      onRetry = viewModel::load,
  )
}

/** Stateless content of "Whose care?". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoseCareContent(
    uiState: WhoseCareUiState,
    onBack: (() -> Unit)?,
    onPatientClick: (FollowedPatient) -> Unit,
    onJoinAnother: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.whose_care_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.whose_care_title)) },
            navigationIcon = {
              if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag(C.Tag.whose_care_back)) {
                  Icon(
                      painter = painterResource(R.drawable.ic_arrow_back),
                      contentDescription = stringResource(R.string.whose_care_back),
                  )
                }
              }
            },
        )
      },
      floatingActionButton = {
        // Without any patient, joining is the main action of the content instead
        if (uiState.patients.isNotEmpty()) {
          ExtendedFloatingActionButton(
              text = { Text(stringResource(R.string.whose_care_join_another)) },
              icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
              onClick = onJoinAnother,
              modifier = Modifier.testTag(C.Tag.whose_care_join_another),
          )
        }
      },
  ) { innerPadding ->
    val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
    when {
      uiState.isLoading ->
          Box(contentModifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(C.Tag.whose_care_loading))
          }
      uiState.hasError ->
          Message(
              title = stringResource(R.string.whose_care_error),
              action = stringResource(R.string.whose_care_retry),
              onAction = onRetry,
              tag = C.Tag.whose_care_error,
              modifier = contentModifier,
          )
      uiState.patients.isEmpty() ->
          Message(
              title = stringResource(R.string.whose_care_empty_title),
              body = stringResource(R.string.whose_care_empty_body),
              action = stringResource(R.string.whose_care_empty_join),
              onAction = onJoinAnother,
              tag = C.Tag.whose_care_empty,
              modifier = contentModifier,
          )
      else -> PatientList(uiState.patients, onPatientClick, contentModifier)
    }
  }
}

/** The intro, "Care circles (n)", then one row per patient. */
@Composable
private fun PatientList(
    patients: List<FollowedPatient>,
    onPatientClick: (FollowedPatient) -> Unit,
    modifier: Modifier,
) {
  // Room at the bottom for the button, so it never hides the last patient
  LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 88.dp)) {
    item {
      Text(
          text = stringResource(R.string.whose_care_intro),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
    item {
      Text(
          text = stringResource(R.string.whose_care_count, patients.size),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
    items(patients, key = { it.uid }) { patient ->
      // Their initial, their first name and what they share, e.g. "Planning & symptoms"
      ListItem(
          headlineContent = { Text(patient.firstName) },
          supportingContent = { Text(accessSummary(patient.sharedSections)) },
          leadingContent = { Monogram(patient.firstName.take(1).uppercase()) },
          modifier =
              Modifier.clickable { onPatientClick(patient) }
                  .testTag(C.Tag.whoseCarePatient(patient.uid)),
      )
    }
  }
}

/** A centered message with one action: the error (Retry) and the empty state (Join). */
@Composable
private fun Message(
    title: String,
    action: String,
    onAction: () -> Unit,
    tag: String,
    modifier: Modifier,
    body: String? = null,
) {
  Column(
      modifier = modifier.padding(32.dp).testTag(tag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    if (body != null) {
      Text(
          body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
    }
    Button(onClick = onAction, modifier = Modifier.testTag(C.Tag.whose_care_action)) {
      Text(action)
    }
  }
}
