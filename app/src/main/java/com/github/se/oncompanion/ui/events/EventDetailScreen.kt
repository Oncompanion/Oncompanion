package com.github.se.oncompanion.ui.events

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.resources.C
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Sat 3 Oct · 10:00", as in the mockup. The app's UI is in English. */
private val startFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** "11:30", for an event that ends on the day it starts. */
private val endTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * The detail of one of the Ligue's events (Figma: "US-03 / Event details"), opened from the Events
 * list. Registering to an event isn't part of this screen yet.
 */
@Composable
fun EventDetailScreen(
    eventId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventDetailViewModel = viewModel(key = eventId) { EventDetailViewModel(eventId) },
) {
  val uiState by viewModel.uiState.collectAsState()
  EventDetailContent(
      uiState = uiState,
      onBack = onBack,
      onRetry = viewModel::loadEvent,
      modifier = modifier,
  )
}

/**
 * Stateless content of the event detail screen, so each state can be tested without a ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailContent(
    uiState: EventDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.event_detail_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.event_detail_title)) },
            navigationIcon = {
              IconButton(onClick = onBack, modifier = Modifier.testTag(C.Tag.event_detail_back)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.event_detail_back),
                )
              }
            },
        )
      },
  ) { innerPadding ->
    val contentModifier = Modifier.padding(innerPadding)
    val event = uiState.event
    when {
      uiState.isLoading ->
          Box(contentModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(C.Tag.event_detail_loading))
          }
      uiState.hasError ->
          DetailMessage(
              text = stringResource(R.string.event_detail_error),
              modifier = contentModifier.testTag(C.Tag.event_detail_error),
          ) {
            Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.event_detail_retry)) {
              Text(stringResource(R.string.events_retry))
            }
          }
      event == null ->
          DetailMessage(
              text = stringResource(R.string.event_detail_not_found),
              modifier = contentModifier.testTag(C.Tag.event_detail_not_found),
          )
      else -> EventDetails(event, contentModifier)
    }
  }
}

@Composable
private fun EventDetails(event: Event, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    // Events have no picture yet: the mockup's placeholder image
    Image(
        painter = painterResource(R.drawable.event_image_placeholder),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier.fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
    Column(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      event.category?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag(C.Tag.event_detail_category),
        )
      }
      Text(
          text = event.title,
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.testTag(C.Tag.event_detail_event_title),
      )
    }
    InfoRow(
        icon = R.drawable.ic_schedule,
        text = event.dateAndTime(),
        label = stringResource(R.string.event_detail_date_time),
        testTag = C.Tag.event_detail_date_time,
    )
    InfoRow(
        icon = R.drawable.ic_location,
        text = event.place,
        label = stringResource(R.string.event_detail_location),
        testTag = C.Tag.event_detail_location,
    )
    Text(
        text = event.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp)
                .testTag(C.Tag.event_detail_description),
    )
  }
}

/** A two-line list item with an icon (Figma: "Date & time", "Location"). */
@Composable
private fun InfoRow(icon: Int, text: String, label: String, testTag: String) {
  ListItem(
      headlineContent = { Text(text) },
      supportingContent = { Text(label) },
      // The label below the text already says what it is, so the icon is decorative
      leadingContent = { Icon(painterResource(icon), contentDescription = null) },
      modifier = Modifier.testTag(testTag),
  )
}

@Composable
private fun DetailMessage(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
  Column(
      modifier = modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    action()
  }
}

/** "Sat 3 Oct · 10:00 – 11:30", or the start alone when the end isn't known. */
private fun Event.dateAndTime(): String {
  val start = startDateTime.format(startFormatter)
  val end = endDateTime ?: return start
  val endText =
      if (end.toLocalDate() == startDateTime.toLocalDate()) end.format(endTimeFormatter)
      else end.format(startFormatter)
  return "$start – $endText"
}
