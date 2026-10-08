package com.github.se.oncompanion.ui.carecircle

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Aug 2026", like the profile's "Member since". */
private val addedAtFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/**
 * One member of the signed-in user's care circle (US-14), following the "US-15 / Member detail"
 * Figma mockup: who they are, how to reach them and what they can see.
 *
 * Editing and removing the member come in later PRs: for now both buttons show a toast.
 */
@Composable
fun CareCircleMemberScreen(
    navigationActions: NavigationActions,
    modifier: Modifier = Modifier,
    // Inside the nav graph, the SavedStateHandle holds the route's member uid
    viewModel: CareCircleMemberViewModel = viewModel {
      CareCircleMemberViewModel(createSavedStateHandle())
    },
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val resources = LocalResources.current

  CareCircleMemberContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onEdit = { member ->
        // TODO: open the edit member screen (US-14) instead of this toast
        val message =
            resources.getString(R.string.care_circle_member_edit_not_implemented, member.firstName)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      },
      onRemove = { member ->
        // TODO: ask for confirmation, then remove the member (US-14) instead of this toast
        val message =
            resources.getString(
                R.string.care_circle_member_remove_not_implemented,
                member.firstName,
            )
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      },
      onRetry = viewModel::retry,
      modifier = modifier,
  )
}

/** Stateless content of the care circle member screen, so each state can be tested on its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareCircleMemberContent(
    uiState: CareCircleMemberUiState,
    onBack: () -> Unit,
    onEdit: (CareCircleMember) -> Unit,
    onRemove: (CareCircleMember) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.care_circle_member_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.care_circle_member_title)) },
            navigationIcon = {
              IconButton(
                  onClick = onBack,
                  modifier = Modifier.testTag(C.Tag.care_circle_member_back_button),
              ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.care_circle_back),
                )
              }
            },
        )
      },
  ) { innerPadding ->
    val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
    when (uiState) {
      CareCircleMemberUiState.Loading -> LoadingState(contentModifier)
      CareCircleMemberUiState.NotFound ->
          MessageState(
              message = stringResource(R.string.care_circle_member_not_found),
              modifier = contentModifier.testTag(C.Tag.care_circle_member_not_found),
          )
      CareCircleMemberUiState.Error ->
          MessageState(
              message = stringResource(R.string.care_circle_member_error),
              modifier = contentModifier.testTag(C.Tag.care_circle_member_error),
          ) {
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag(C.Tag.care_circle_member_retry_button),
            ) {
              Text(stringResource(R.string.care_circle_retry))
            }
          }
      is CareCircleMemberUiState.Member ->
          MemberDetails(
              member = uiState.member,
              onEdit = { onEdit(uiState.member) },
              onRemove = { onRemove(uiState.member) },
              modifier = contentModifier,
          )
    }
  }
}

@Composable
private fun LoadingState(modifier: Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    CircularProgressIndicator(modifier = Modifier.testTag(C.Tag.care_circle_member_loading))
  }
}

/** A centered message, with an optional action below it (e.g. Retry). */
@Composable
private fun MessageState(
    message: String,
    modifier: Modifier,
    action: @Composable () -> Unit = {},
) {
  Column(
      modifier = modifier.padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text(text = message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    action()
  }
}

/** The details scroll, while the actions stay at the bottom like in the mockup. */
@Composable
private fun MemberDetails(
    member: CareCircleMember,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
  Column(modifier = modifier.padding(bottom = 24.dp)) {
    Column(
        modifier =
            Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag(C.Tag.care_circle_member_details)
    ) {
      MemberHeader(member)

      // Contact: hidden when the patient didn't give an email
      member.email
          ?.takeIf { it.isNotBlank() }
          ?.let { email ->
            Subheader(stringResource(R.string.care_circle_member_contact))
            ListItem(
                headlineContent = { Text(email) },
                supportingContent = { Text(stringResource(R.string.care_circle_member_email)) },
                leadingContent = { Icon(Icons.Outlined.Email, contentDescription = null) },
                modifier = Modifier.testTag(C.Tag.care_circle_member_email),
            )
          }

      // What the member can see, every section in display order
      Subheader(stringResource(R.string.care_circle_member_can_see))
      CarePermission.entries.forEach { permission ->
        PermissionItem(permission = permission, granted = permission in member.permissions)
      }
    }

    MemberActions(onEdit = onEdit, onRemove = onRemove)
  }
}

/** Avatar, full name, and "relationship · Member since <month>". */
@Composable
private fun MemberHeader(member: CareCircleMember) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
        modifier =
            Modifier.size(80.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .testTag(C.Tag.care_circle_member_initial),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          text = member.initial,
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
    Text(
        text = member.fullName,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp).testTag(C.Tag.care_circle_member_name),
    )
    val relationship = stringResource(member.relationship.label)
    Text(
        text =
            member.addedAt?.let { addedAt ->
              stringResource(
                  R.string.care_circle_member_since,
                  relationship,
                  addedAtFormatter.format(addedAt.atZone(ZoneId.systemDefault())),
              )
            } ?: relationship,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(C.Tag.care_circle_member_subtitle),
    )
  }
}

@Composable
private fun Subheader(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
  )
}

/**
 * Whether the member can see [permission]. The row exposes the switch's on/off state, so screen
 * readers announce it even though the switch itself can't be toggled.
 */
@Composable
private fun PermissionItem(permission: CarePermission, granted: Boolean) {
  ListItem(
      headlineContent = { Text(stringResource(permission.label)) },
      supportingContent = { Text(stringResource(permission.description)) },
      // TODO: let the patient change access from the edit member screen (US-14). Until then the
      //  switch only shows the current access: onCheckedChange = null makes it read-only.
      trailingContent = { Switch(checked = granted, onCheckedChange = null) },
      modifier =
          Modifier.semantics(mergeDescendants = true) { toggleableState = ToggleableState(granted) }
              .testTag(C.Tag.careCircleMemberPermission(permission.name)),
  )
}

@Composable
private fun MemberActions(onEdit: () -> Unit, onRemove: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedButton(
        onClick = onEdit,
        modifier =
            Modifier.fillMaxWidth().height(56.dp).testTag(C.Tag.care_circle_member_edit_button),
    ) {
      Icon(
          Icons.Outlined.Edit,
          contentDescription = null,
          modifier = Modifier.size(ButtonDefaults.IconSize),
      )
      Text(
          text = stringResource(R.string.care_circle_member_edit),
          modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
      )
    }
    TextButton(
        onClick = onRemove,
        modifier = Modifier.height(56.dp).testTag(C.Tag.care_circle_member_remove_button),
    ) {
      Text(stringResource(R.string.care_circle_member_remove))
    }
  }
}

/** What the section shows a member, e.g. "Treatments & appointments" for Planning. */
@get:StringRes
internal val CarePermission.description: Int
  get() =
      when (this) {
        CarePermission.PLANNING -> R.string.permission_planning_description
        CarePermission.EVENTS -> R.string.permission_events_description
        CarePermission.SYMPTOMS -> R.string.permission_symptoms_description
        CarePermission.PRESCRIPTIONS -> R.string.permission_prescriptions_description
      }

@Preview(showBackground = true)
@Composable
fun CareCircleMemberScreenPreview() {
  OncompanionTheme {
    CareCircleMemberContent(
        uiState =
            CareCircleMemberUiState.Member(
                CareCircleMember(
                    uid = "marc",
                    firstName = "Marc",
                    familyName = "Dubois",
                    relationship = Relationship.SON,
                    permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
                    email = "marc.dubois@email.com",
                    addedAt = Instant.parse("2026-08-14T09:30:00Z"),
                )
            ),
        onBack = {},
        onEdit = {},
        onRemove = {},
        onRetry = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
fun CareCircleMemberScreenNotFoundPreview() {
  OncompanionTheme {
    CareCircleMemberContent(
        uiState = CareCircleMemberUiState.NotFound,
        onBack = {},
        onEdit = {},
        onRemove = {},
        onRetry = {},
    )
  }
}
