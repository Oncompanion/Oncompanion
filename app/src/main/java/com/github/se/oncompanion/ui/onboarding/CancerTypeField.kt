package com.github.se.oncompanion.ui.onboarding

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.cancer.CancerTypes
import com.github.se.oncompanion.resources.C

/**
 * Text field for the cancer type, with suggestions while typing (see [CancerTypes.suggest]).
 * Tapping a suggestion fills the field; otherwise the typed text is kept as-is.
 *
 * @param value the current text
 * @param onValueChange called when the user types or picks a suggestion
 * @param suggestions what to suggest for [value]; defaults to [CancerTypes.suggest]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancerTypeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: List<String> = CancerTypes.suggest(value),
) {
  // Whether the user wants the list open; it's only shown when there is something to suggest
  var open by remember { mutableStateOf(false) }
  val expanded = open && suggestions.isNotEmpty()

  ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = { open = it },
      modifier = modifier,
  ) {
    OutlinedTextField(
        value = value,
        onValueChange = {
          onValueChange(it)
          open = true
        },
        label = { Text(stringResource(R.string.cancer_type_label)) },
        supportingText = { Text(stringResource(R.string.cancer_type_hint)) },
        singleLine = true,
        modifier =
            Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .testTag(C.Tag.cancer_type_field),
    )
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { open = false },
        modifier = Modifier.testTag(C.Tag.cancer_type_suggestions),
    ) {
      suggestions.forEach { suggestion ->
        DropdownMenuItem(
            text = { Text(highlightMatch(suggestion, value)) },
            onClick = {
              onValueChange(suggestion)
              open = false
            },
            modifier = Modifier.testTag(C.Tag.cancer_type_suggestion),
        )
      }
    }
  }
}

/** [suggestion] with the part matching [query] in bold. */
private fun highlightMatch(suggestion: String, query: String): AnnotatedString {
  val range = CancerTypes.matchRange(suggestion, query) ?: return AnnotatedString(suggestion)
  return buildAnnotatedString {
    append(suggestion.substring(0, range.first))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
      append(suggestion.substring(range.first, range.last + 1))
    }
    append(suggestion.substring(range.last + 1))
  }
}
