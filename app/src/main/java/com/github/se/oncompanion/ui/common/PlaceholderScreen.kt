package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Temporary screen showing only a [title], used until a feature's real screen is implemented. */
@Composable
fun PlaceholderScreen(title: String, testTag: String, modifier: Modifier = Modifier) {
  Box(
      modifier = modifier.fillMaxSize().padding(24.dp).testTag(testTag),
      contentAlignment = Alignment.Center,
  ) {
    Text(text = title, style = MaterialTheme.typography.headlineMedium)
  }
}
