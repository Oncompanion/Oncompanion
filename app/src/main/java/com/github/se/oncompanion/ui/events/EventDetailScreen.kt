// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.events

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.webLinkOrNull
import com.github.se.oncompanion.resources.C
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** "Sat 3 Oct · 10:00", as in the mockup. The app's UI is in English. */
private val startFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** "11:30", for an event that ends on the day it starts. */
private val endTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * The detail of one of the Ligue's events (Figma: "US-03 / Event details"), opened from the Events
 * list. The patient can open the organizer's website and confirm here that they registered there
 * (Figma: "US-25 / Event detail").
 */
@Composable
fun EventDetailScreen(
    eventId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventDetailViewModel = viewModel(key = eventId) { EventDetailViewModel(eventId) },
) {
  val uiState by viewModel.uiState.collectAsState()
  // The event may have started while the patient was away: recompute what can be confirmed
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshStatus()
    onPauseOrDispose {}
  }
  EventDetailContent(
      uiState = uiState,
      onBack = onBack,
      onRetry = viewModel::loadEvent,
      onConfirmRegistration = viewModel::confirmRegistration,
      onRequestRemoval = viewModel::requestRemoval,
      onCancelRemoval = viewModel::cancelRemoval,
      onRemoveRegistration = viewModel::removeRegistration,
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
    onConfirmRegistration: () -> Unit,
    onRequestRemoval: () -> Unit,
    onCancelRemoval: () -> Unit,
    onRemoveRegistration: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
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
      snackbarHost = { SnackbarHost(snackbarHostState) },
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
      event == null -> MissingEvent(onBack, contentModifier)
      else -> {
        EventDetails(event, uiState.registration, contentModifier) {
          RegistrationActions(
              link = webLinkOrNull(event.websiteUrl),
              registration = uiState.registration,
              snackbarHostState = snackbarHostState,
              onConfirm = onConfirmRegistration,
              onRequestRemoval = onRequestRemoval,
              onRetry = onRetry,
          )
        }
        if (uiState.isConfirmingRemoval) {
          RemoveDialog(onConfirm = onRemoveRegistration, onCancel = onCancelRemoval)
        }
      }
    }
  }
}

@Composable
private fun EventDetails(
    event: Event,
    registration: RegistrationStatus,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    if (registration == RegistrationStatus.CONFIRMED) ConfirmedBanner()
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
            Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
                .testTag(C.Tag.event_detail_description),
    )
    actions()
  }
}

/** "Registration confirmed", above the event once the patient confirmed it (Figma). */
@Composable
private fun ConfirmedBanner() {
  Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      shape = RoundedCornerShape(12.dp),
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp)
              .testTag(C.Tag.event_detail_registration_confirmed),
  ) {
    Row(
        modifier = Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      // The title next to it says what it means
      Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null)
      Column {
        Text(
            text = stringResource(R.string.event_detail_registration_confirmed),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.event_detail_registration_confirmed_supporting),
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

/**
 * The buttons below the event: the organizer's website, if any, and confirming or removing the
 * registration, depending on [registration].
 */
@Composable
private fun RegistrationActions(
    link: String?,
    registration: RegistrationStatus,
    snackbarHostState: SnackbarHostState,
    onConfirm: () -> Unit,
    onRequestRemoval: () -> Unit,
    onRetry: () -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  val scope = rememberCoroutineScope()
  val websiteError =
      stringResource(
          if (registration == RegistrationStatus.CAN_CONFIRM) {
            R.string.event_detail_website_error_can_confirm
          } else {
            R.string.event_detail_website_error
          }
      )
  Column(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Opens the organizer's site in the browser; it confirms nothing by itself
    if (link != null) {
      val openWebsite = {
        try {
          uriHandler.openUri(link)
        } catch (e: IllegalArgumentException) {
          // No app can open web pages (Compose wraps ActivityNotFoundException in this)
          scope.launch { snackbarHostState.showSnackbar(websiteError) }
        }
        Unit
      }
      val websiteModifier = Modifier.fillMaxWidth().testTag(C.Tag.event_detail_visit_website)
      val websiteText = @Composable { Text(stringResource(R.string.event_detail_visit_website)) }
      if (registration == RegistrationStatus.CONFIRMED) {
        OutlinedButton(onClick = openWebsite, modifier = websiteModifier) { websiteText() }
      } else {
        Button(onClick = openWebsite, modifier = websiteModifier) { websiteText() }
      }
    }
    when (registration) {
      RegistrationStatus.LOADING -> {
        // Nothing to offer until the registration status is known
      }
      RegistrationStatus.CAN_CONFIRM,
      RegistrationStatus.CLOSED -> {
        val canConfirm = registration == RegistrationStatus.CAN_CONFIRM
        val confirmModifier = Modifier.fillMaxWidth().testTag(C.Tag.event_detail_confirm)
        val confirmText = @Composable { Text(stringResource(R.string.event_detail_registered)) }
        // The main action when there is no website to visit first (Figma: "No website link")
        if (link == null) {
          Button(onClick = onConfirm, enabled = canConfirm, modifier = confirmModifier) {
            confirmText()
          }
        } else {
          OutlinedButton(onClick = onConfirm, enabled = canConfirm, modifier = confirmModifier) {
            confirmText()
          }
        }
        Note(
            text =
                stringResource(
                    if (canConfirm) R.string.event_detail_registration_note
                    else R.string.event_detail_registration_closed
                ),
            testTag =
                if (canConfirm) C.Tag.event_detail_registration_note
                else C.Tag.event_detail_registration_closed,
        )
      }
      RegistrationStatus.CONFIRMED ->
          TextButton(
              onClick = onRequestRemoval,
              modifier = Modifier.testTag(C.Tag.event_detail_remove),
          ) {
            Text(stringResource(R.string.event_detail_remove))
          }
      RegistrationStatus.ERROR -> {
        Text(
            text = stringResource(R.string.event_detail_registration_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(C.Tag.event_detail_registration_error),
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.testTag(C.Tag.event_detail_registration_retry),
        ) {
          Text(stringResource(R.string.events_retry))
        }
      }
    }
  }
}

@Composable
private fun Note(text: String, testTag: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.testTag(testTag),
  )
}

/** Asks to confirm the removal, saying it doesn't cancel anything with the organizer. */
@Composable
private fun RemoveDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
  AlertDialog(
      onDismissRequest = onCancel,
      modifier = Modifier.testTag(C.Tag.event_detail_remove_dialog),
      title = { Text(stringResource(R.string.event_detail_remove_title)) },
      text = { Text(stringResource(R.string.event_detail_remove_message)) },
      confirmButton = {
        TextButton(
            onClick = onConfirm,
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag(C.Tag.event_detail_remove_confirm),
        ) {
          Text(stringResource(R.string.event_detail_remove_confirm))
        }
      },
      dismissButton = {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag(C.Tag.event_detail_remove_cancel),
        ) {
          Text(stringResource(R.string.event_detail_remove_cancel))
        }
      },
  )
}

/** The event was deleted, e.g. cancelled by the organizer (Figma: "Event no longer exists"). */
@Composable
private fun MissingEvent(onBack: () -> Unit, modifier: Modifier = Modifier) {
  DetailMessage(
      text = stringResource(R.string.event_detail_not_found),
      modifier = modifier.testTag(C.Tag.event_detail_not_found),
      icon = R.drawable.ic_event,
      supportingText = stringResource(R.string.event_detail_not_found_hint),
  ) {
    Button(onClick = onBack, modifier = Modifier.testTag(C.Tag.event_detail_back_to_events)) {
      Text(stringResource(R.string.event_detail_back_to_events))
    }
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
    icon: Int? = null,
    supportingText: String? = null,
    action: @Composable () -> Unit = {},
) {
  Column(
      modifier = modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    // Decorative: the text below says what happened
    icon?.let { Icon(painterResource(it), contentDescription = null, Modifier.size(40.dp)) }
    Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    supportingText?.let {
      Text(
          text = it,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
    }
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
