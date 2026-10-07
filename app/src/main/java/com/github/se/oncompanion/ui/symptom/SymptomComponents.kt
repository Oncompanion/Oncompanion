package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/** The top bar's back arrow, shared by the symptom screens. */
@Composable
internal fun BackButton(onBack: () -> Unit) {
  IconButton(onClick = onBack, modifier = Modifier.testTag(C.Tag.symptom_back_button)) {
    Icon(
        painter = painterResource(R.drawable.ic_arrow_back),
        contentDescription = stringResource(R.string.symptom_back),
    )
  }
}

/**
 * A centered title and hint (empty, error or not found state), with a retry button tagged
 * [retryTestTag] when [onRetry] is given.
 */
@Composable
internal fun SymptomMessage(
    title: String,
    hint: String,
    testTag: String,
    onRetry: (() -> Unit)? = null,
    retryTestTag: String = "",
) {
  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp).testTag(testTag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
  ) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (onRetry != null) {
      Button(onClick = onRetry, modifier = Modifier.testTag(retryTestTag)) {
        Text(stringResource(R.string.symptom_retry))
      }
    }
  }
}
