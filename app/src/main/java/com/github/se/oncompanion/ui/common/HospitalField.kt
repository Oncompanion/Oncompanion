// Co-authored-by: Codex
package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.hospital.HospitalRepository
import com.github.se.oncompanion.resources.C

/**
 * Reusable location field with optional, explicitly requested hospital search. The form owns
 * [value]; only typing or selecting a result calls [onValueChange]. Supply a separate lifecycle
 * scoped [viewModel] per field, using a stable repository instance to retain cached results. Search
 * feedback is cleared when the text changes, the field is disabled or it leaves composition.
 */
@Composable
fun HospitalField(
    value: String,
    onValueChange: (String) -> Unit,
    viewModel: HospitalFieldViewModel,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // External form changes invalidate suggestions too, without triggering a new search.
  LaunchedEffect(value, enabled, viewModel) { viewModel.clearSearch() }
  DisposableEffect(viewModel) { onDispose { viewModel.clearSearch() } }
  HospitalFieldContent(
      value = value,
      onValueChange = {
        viewModel.clearSearch()
        onValueChange(it)
      },
      uiState = uiState,
      onSearch = { viewModel.search(value) },
      modifier = modifier,
      enabled = enabled,
  )
}

/** Stateless field and search feedback, reusable in forms and testable without any service. */
@Composable
fun HospitalFieldContent(
    value: String,
    onValueChange: (String) -> Unit,
    uiState: HospitalFieldUiState,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.hospital_field_label)) },
        supportingText = { Text(stringResource(R.string.hospital_field_hint)) },
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.hospital_field),
    )
    Button(
        onClick = onSearch,
        enabled = enabled && value.isNotBlank() && uiState != HospitalFieldUiState.Loading,
        modifier = Modifier.testTag(C.Tag.hospital_search),
    ) {
      Text(stringResource(R.string.hospital_search))
    }
    if (enabled) HospitalSearchFeedback(uiState, onValueChange)
  }
}

@Composable
private fun HospitalSearchFeedback(uiState: HospitalFieldUiState, onValueChange: (String) -> Unit) {
  when (uiState) {
    HospitalFieldUiState.Idle -> Unit
    HospitalFieldUiState.Loading ->
        CircularProgressIndicator(Modifier.testTag(C.Tag.hospital_loading))
    HospitalFieldUiState.Error ->
        Text(
            stringResource(R.string.hospital_search_error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(C.Tag.hospital_error),
        )
    is HospitalFieldUiState.Results -> {
      if (uiState.hospitals.isEmpty()) {
        Text(stringResource(R.string.hospital_search_empty), Modifier.testTag(C.Tag.hospital_empty))
      } else {
        // Bound the list so it can fit inside either a scrolling form or a dialog.
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .testTag(C.Tag.hospital_results)
        ) {
          uiState.hospitals.forEach { hospital ->
            TextButton(
                onClick = { onValueChange(hospital.name) },
                modifier = Modifier.fillMaxWidth().testTag(C.Tag.hospital_result),
            ) {
              Column(Modifier.fillMaxWidth()) {
                Text(hospital.name)
                Text(hospital.address, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
      // Credit accompanies successful lookups, including empty or cached responses.
      val uriHandler = LocalUriHandler.current
      TextButton(
          onClick = { uriHandler.openUri(HospitalRepository.ATTRIBUTION_URL) },
          modifier = Modifier.testTag(C.Tag.hospital_attribution),
      ) {
        Text(stringResource(R.string.hospital_attribution))
      }
    }
  }
}
