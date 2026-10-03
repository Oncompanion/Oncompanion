package com.github.se.oncompanion.ui.overview

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route

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
      R.string.overview_scan_prescription,
      R.drawable.ic_prescriptions,
      C.Tag.overview_shortcut_prescriptions,
  ),
  CARE_CIRCLE(
      Route.CARE_CIRCLE,
      R.string.care_circle_title,
      R.drawable.ic_care_circle,
      C.Tag.overview_shortcut_care_circle,
  ),
  /** Shown as the account icon in the top bar rather than as a main button. */
  PROFILE(
      Route.PROFILE,
      R.string.profile_title,
      R.drawable.ic_profile,
      C.Tag.overview_shortcut_profile,
  ),
}

/** The large buttons in the middle of the screen, in display order. */
private val mainShortcuts =
    listOf(OverviewShortcut.SYMPTOMS, OverviewShortcut.PRESCRIPTIONS, OverviewShortcut.CARE_CIRCLE)

/**
 * The home screen after signing in (US-23), following the "US-17 / Overview" Figma mockup: a
 * welcome bar with the profile button, and one large button per main section.
 *
 * The user's first name, the next appointment and the bottom bar come in later PRs.
 */
@Composable
fun OverviewScreen(navigationActions: NavigationActions, modifier: Modifier = Modifier) {
  OverviewContent(
      firstName = null,
      onShortcutClick = { shortcut -> navigationActions.navigateTo(shortcut.route) },
      modifier = modifier,
  )
}

/**
 * Stateless content of the Overview, so it can be tested without navigation.
 *
 * @param firstName shown in the welcome title, or `null` for a plain "Welcome"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewContent(
    firstName: String?,
    onShortcutClick: (OverviewShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.overview_screen),
      topBar = {
        MediumTopAppBar(
            title = {
              Text(
                  if (firstName.isNullOrBlank()) stringResource(R.string.overview_welcome)
                  else stringResource(R.string.overview_welcome_name, firstName)
              )
            },
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
  ) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      Text(
          text = stringResource(R.string.overview_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // Large buttons: easy to read and to tap on a low-energy day
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        mainShortcuts.forEach { shortcut ->
          ShortcutButton(shortcut = shortcut, onClick = { onShortcutClick(shortcut) })
        }
      }
    }
  }
}

/** A large tonal button (M3 "Large" size: 96dp high, 32dp icon, headline-small label). */
@Composable
private fun ShortcutButton(shortcut: OverviewShortcut, onClick: () -> Unit) {
  FilledTonalButton(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth().height(96.dp).testTag(shortcut.testTag),
  ) {
    // The label next to it already describes the button, so the icon is decorative
    Icon(
        painter = painterResource(shortcut.icon),
        contentDescription = null,
        modifier = Modifier.size(32.dp),
    )
    Spacer(Modifier.width(12.dp))
    Text(text = stringResource(shortcut.label), style = MaterialTheme.typography.headlineSmall)
  }
}
