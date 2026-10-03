package com.github.se.oncompanion.ui.events

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Tab
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Sat 3 Oct · 10:00", as in the mockup. The app's UI is in English. */
private val eventDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** The Events tab (Figma: "US-03 / Events"): the Ligue's upcoming events. */
@Composable
fun EventsScreen(
    navigationActions: NavigationActions,
    modifier: Modifier = Modifier,
    viewModel: EventsViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  EventsContent(
      uiState = uiState,
      onRetry = viewModel::loadEvents,
      modifier = modifier,
      bottomBar = {
        BottomNavigationBar(
            selectedTab = Tab.EVENTS,
            onTabSelected = { tab -> navigationActions.navigateToTab(tab.route) },
        )
      },
  )
}

/**
 * Stateless content of the Events tab, so each state can be tested without a ViewModel.
 *
 * @param onRetry called when the user asks to reload after an error
 * @param bottomBar the bottom navigation bar, empty by default
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsContent(
    uiState: EventsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.events_screen),
      topBar = {
        MediumTopAppBar(
            title = {
              Text(
                  text = stringResource(R.string.events_screen_title),
                  modifier = Modifier.testTag(C.Tag.events_title),
              )
            }
        )
      },
      bottomBar = bottomBar,
  ) { innerPadding ->
    val contentModifier = Modifier.padding(innerPadding)
    when {
      uiState.isLoading -> LoadingEvents(contentModifier)
      uiState.hasError -> EventsError(onRetry, contentModifier)
      uiState.events.isEmpty() -> EmptyEvents(contentModifier)
      else -> EventList(uiState.events, contentModifier)
    }
  }
}

@Composable
private fun EventList(events: List<Event>, modifier: Modifier = Modifier) {
  LazyColumn(
      modifier = modifier.fillMaxSize().testTag(C.Tag.events_list),
      contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(events, key = { it.id }) { event -> EventCard(event) }
  }
}

/** An event in the list (Figma: "Event card"): image, date and place, title and a short summary. */
@Composable
private fun EventCard(event: Event) {
  Surface(
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.eventCard(event.id)),
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Row(
        modifier = Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Events have no picture yet: every card shows the mockup's placeholder image
      Image(
          painter = painterResource(R.drawable.event_image_placeholder),
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier =
              Modifier.size(88.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = event.startDateTime.format(eventDateFormatter),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = event.place,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = event.title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = event.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun LoadingEvents(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.testTag(C.Tag.events_loading))
  }
}

/** Shown when there are no upcoming events. */
@Composable
private fun EmptyEvents(modifier: Modifier = Modifier) {
  CenteredMessage(
      title = stringResource(R.string.events_empty_title),
      supportingText = stringResource(R.string.events_empty_supporting),
      modifier = modifier.testTag(C.Tag.events_empty_state),
  )
}

/** Shown when the events couldn't be loaded, with a button to try again. */
@Composable
private fun EventsError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
  CenteredMessage(
      title = stringResource(R.string.events_error_title),
      supportingText = stringResource(R.string.events_error_supporting),
      modifier = modifier.testTag(C.Tag.events_error),
  ) {
    Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.events_retry)) {
      Text(stringResource(R.string.events_retry))
    }
  }
}

@Composable
private fun CenteredMessage(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
  Column(
      modifier = modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
  ) {
    // The text below says it all, so the icon is decorative
    Icon(
        painter = painterResource(R.drawable.ic_tab_events),
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Text(
        text = supportingText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    action()
  }
}
