// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.theme.Spacing

/** Opens the device preferences; unfinished categories remain hidden. */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  SettingsContent(onBack, modifier)
}

/** Settings before the first preference category is available. */
@Composable
fun SettingsContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
  SettingsLayout(
      stringResource(R.string.settings_title),
      onBack,
      modifier.testTag(C.Tag.settings_screen),
  ) {
    Text(
        stringResource(R.string.settings_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(C.Tag.settings_empty),
    )
  }
}

/** Shares the lake header and scrollable body between settings destinations. */
@Composable
internal fun SettingsLayout(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
      modifier = modifier,
      topBar = {
        LakeTopAppBar(
            title,
            navigationIcon = {
              IconButton(onClick = onBack, modifier = Modifier.testTag(C.Tag.settings_back)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back))
              }
            },
        )
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        content = content,
    )
  }
}
