package com.github.se.oncompanion.ui.overview

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A section the patient can open from the Overview with a single tap. */
enum class OverviewShortcut(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val testTag: String,
) {
  SYMPTOMS(
      Route.SYMPTOMS,
      R.string.symptoms_title,
      R.drawable.ic_symptoms,
      C.Tag.overview_shortcut_symptoms,
  ),
  PRESCRIPTIONS(
      Route.PRESCRIPTIONS,
      R.string.prescriptions_title,
      R.drawable.ic_prescriptions,
      C.Tag.overview_shortcut_prescriptions,
  ),
  CARE_CIRCLE(
      Route.CARE_CIRCLE,
      R.string.care_circle_title,
      R.drawable.ic_care_circle,
      C.Tag.overview_shortcut_care_circle,
  ),
  /** Shown as the account icon in the top bar rather than as a tile. */
  PROFILE(
      Route.PROFILE,
      R.string.profile_title,
      R.drawable.ic_profile,
      C.Tag.overview_shortcut_profile,
  ),
}

/** The tiles under "How do you feel today?", in display order. */
private val tileShortcuts =
    listOf(OverviewShortcut.SYMPTOMS, OverviewShortcut.PRESCRIPTIONS, OverviewShortcut.CARE_CIRCLE)

/** "Saturday 3 October", as in the mockup. The app's UI is in English. */
private val todayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/**
 * The home screen after signing in (US-23 "See my day at a glance"), following the Figma "US-23 ·
 * Overview dashboard (proposal)": a greeting, the next appointment, today's items and the shortcuts
 * to each section.
 */
@Composable
fun OverviewScreen(
    navigationActions: NavigationActions,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = viewModel { OverviewViewModel() },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  OverviewContent(
      uiState = uiState,
      onShortcutClick = { shortcut -> navigationActions.navigateTo(shortcut.route) },
      onOpenPlanning = { navigationActions.navigateTo(Route.PLANNING) },
      onRetry = viewModel::loadDay,
      modifier = modifier,
  )
}

/**
 * Stateless content of the Overview, so each state can be tested without a ViewModel.
 *
 * @param onOpenPlanning opens Planning, from the appointment card or "See planning"
 * @param onRetry reloads the day after an error
 * @param bottomBar the bottom navigation bar, empty by default
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewContent(
    uiState: OverviewUiState,
    onShortcutClick: (OverviewShortcut) -> Unit,
    onOpenPlanning: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.overview_screen),
      topBar = {
        MediumTopAppBar(
            title = { Greeting(uiState.firstName, uiState.now) },
            actions = {
              val profile = OverviewShortcut.PROFILE
              IconButton(
                  onClick = { onShortcutClick(profile) },
                  modifier = Modifier.testTag(profile.testTag),
              ) {
                Icon(
                    painter = painterResource(profile.icon),
                    contentDescription = stringResource(profile.label),
                )
              }
            },
        )
      },
      bottomBar = bottomBar,
  ) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      when {
        uiState.isLoading -> LoadingDay()
        uiState.hasError -> DayError(onRetry)
        else -> {
          NextAppointmentCard(uiState.nextAppointment, uiState.now.toLocalDate(), onOpenPlanning)
          TodaySection(uiState.todayEntries, onOpenPlanning)
        }
      }
      // The shortcuts stay available whatever happens to the day's data
      Shortcuts(onShortcutClick)
    }
  }
}

/** "Good morning, Alex" and today's date, in the top bar. */
@Composable
private fun Greeting(firstName: String?, now: LocalDateTime) {
  val name = firstName?.takeIf { it.isNotBlank() }
  val greeting =
      when (now.hour) {
        in 0..11 ->
            if (name == null) stringResource(R.string.overview_greeting_morning)
            else stringResource(R.string.overview_greeting_morning_name, name)
        in 12..17 ->
            if (name == null) stringResource(R.string.overview_greeting_afternoon)
            else stringResource(R.string.overview_greeting_afternoon_name, name)
        else ->
            if (name == null) stringResource(R.string.overview_greeting_evening)
            else stringResource(R.string.overview_greeting_evening_name, name)
      }
  Column {
    Text(text = greeting, modifier = Modifier.testTag(C.Tag.overview_greeting))
    Text(
        text = now.format(todayFormatter),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag(C.Tag.overview_date),
    )
  }
}

@Composable
private fun LoadingDay() {
  Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.testTag(C.Tag.overview_loading))
  }
}

/** Shown when the appointment and today's items couldn't be loaded. */
@Composable
private fun DayError(onRetry: () -> Unit) {
  Surface(
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.overview_error),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
          text = stringResource(R.string.overview_error_title),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
      )
      Text(
          text = stringResource(R.string.overview_error_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.overview_retry)) {
        Text(stringResource(R.string.overview_retry))
      }
    }
  }
}

/** "How do you feel today?" and one large tile per main section. */
@Composable
private fun Shortcuts(onShortcutClick: (OverviewShortcut) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = stringResource(R.string.overview_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Large tiles: easy to read and to tap on a low-energy day
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      tileShortcuts.forEach { shortcut ->
        ShortcutTile(
            shortcut = shortcut,
            onClick = { onShortcutClick(shortcut) },
            modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun ShortcutTile(
    shortcut: OverviewShortcut,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(
      onClick = onClick,
      modifier = modifier.heightIn(min = 104.dp).testTag(shortcut.testTag),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.secondaryContainer,
  ) {
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
      // The label below already describes the tile, so the icon is decorative
      Icon(
          painter = painterResource(shortcut.icon),
          contentDescription = null,
          modifier = Modifier.size(32.dp),
      )
      Text(
          text = stringResource(shortcut.label),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
      )
    }
  }
}
