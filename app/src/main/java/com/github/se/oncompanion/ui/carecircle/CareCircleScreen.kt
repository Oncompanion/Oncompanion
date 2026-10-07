package com.github.se.oncompanion.ui.carecircle

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.theme.OncompanionTheme

/**
 * The members of the signed-in user's care circle (US-13), following the "US-13 / Circle members"
 * Figma mockup. The empty state has no mockup yet. Tapping a member opens their details (US-15).
 *
 * Adding a member (US-14) comes in a later PR: for now it shows a toast.
 */
@Composable
fun CareCircleScreen(
    navigationActions: NavigationActions,
    modifier: Modifier = Modifier,
    viewModel: CareCircleViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val resources = LocalResources.current

  CareCircleContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onMemberClick = { member ->
        navigationActions.navigateTo(Screen.careCircleMember(member.uid))
      },
      onAddMember = {
        // TODO: open the add member screen (US-14) instead of this toast
        val message = resources.getString(R.string.care_circle_add_member_not_implemented)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      },
      onRetry = viewModel::retry,
      modifier = modifier,
  )
}

/** Stateless content of the care circle screen, so each state can be tested on its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareCircleContent(
    uiState: CareCircleUiState,
    onBack: () -> Unit,
    onMemberClick: (CareCircleMember) -> Unit,
    onAddMember: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.care_circle_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.care_circle_title)) },
            navigationIcon = {
              IconButton(
                  onClick = onBack,
                  modifier = Modifier.testTag(C.Tag.care_circle_back_button),
              ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.care_circle_back),
                )
              }
            },
        )
      },
      floatingActionButton = {
        // In the empty state, the add button is part of the content instead
        if (uiState is CareCircleUiState.Members) {
          ExtendedFloatingActionButton(
              text = { Text(stringResource(R.string.care_circle_add_member)) },
              icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
              onClick = onAddMember,
              modifier = Modifier.testTag(C.Tag.care_circle_add_member_fab),
          )
        }
      },
  ) { innerPadding ->
    val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
    when (uiState) {
      CareCircleUiState.Loading -> LoadingState(contentModifier)
      CareCircleUiState.Empty -> EmptyState(onAddMember, contentModifier)
      CareCircleUiState.Error -> ErrorState(onRetry, contentModifier)
      is CareCircleUiState.Members -> MemberList(uiState.members, onMemberClick, contentModifier)
    }
  }
}

@Composable
private fun LoadingState(modifier: Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    CircularProgressIndicator(modifier = Modifier.testTag(C.Tag.care_circle_loading))
  }
}

@Composable
private fun MemberList(
    members: List<CareCircleMember>,
    onMemberClick: (CareCircleMember) -> Unit,
    modifier: Modifier,
) {
  LazyColumn(
      modifier = modifier.testTag(C.Tag.care_circle_member_list),
      // Room for the FAB, so it never hides the last member
      contentPadding = PaddingValues(bottom = 88.dp),
  ) {
    item { Intro() }
    item {
      Text(
          text = stringResource(R.string.care_circle_members_count, members.size),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier =
              Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                  .testTag(C.Tag.care_circle_members_count),
      )
    }
    items(members, key = { it.uid }) { member ->
      MemberItem(member = member, onClick = { onMemberClick(member) })
    }
  }
}

@Composable
private fun Intro() {
  Text(
      text = stringResource(R.string.care_circle_intro),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier =
          Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
  )
}

/** One member: avatar with their initial, full name, and "relationship · what they can see". */
@Composable
private fun MemberItem(member: CareCircleMember, onClick: () -> Unit) {
  ListItem(
      headlineContent = { Text(member.fullName) },
      supportingContent = {
        Text(
            stringResource(
                R.string.care_circle_member_summary,
                stringResource(member.relationship.label),
                accessSummary(member.permissions),
            )
        )
      },
      leadingContent = { Monogram(member.initial) },
      modifier = Modifier.clickable(onClick = onClick).testTag(C.Tag.careCircleMember(member.uid)),
  )
}

@Composable
private fun Monogram(initial: String) {
  Box(
      modifier =
          Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = initial,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}

@Composable
private fun EmptyState(onAddMember: () -> Unit, modifier: Modifier) {
  Column(modifier = modifier.verticalScroll(rememberScrollState())) {
    Intro()
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp).testTag(C.Tag.care_circle_empty),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Icon(
          painter = painterResource(R.drawable.ic_care_circle),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(48.dp),
      )
      Text(
          text = stringResource(R.string.care_circle_empty_title),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
      )
      Text(
          text = stringResource(R.string.care_circle_empty_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      Button(
          onClick = onAddMember,
          modifier = Modifier.testTag(C.Tag.care_circle_empty_add_button),
      ) {
        Text(stringResource(R.string.care_circle_empty_add_member))
      }
    }
  }
}

@Composable
private fun ErrorState(onRetry: () -> Unit, modifier: Modifier) {
  Column(
      modifier = modifier.padding(32.dp).testTag(C.Tag.care_circle_error),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text(
        text = stringResource(R.string.care_circle_error),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.care_circle_retry_button)) {
      Text(stringResource(R.string.care_circle_retry))
    }
  }
}

/**
 * What a member can see, in display order: "Full access", "No access", "Planning", "Planning &
 * events" or "Planning, events & symptoms".
 */
@Composable
fun accessSummary(permissions: Set<CarePermission>): String {
  val granted = CarePermission.entries.filter { it in permissions }
  if (granted.size == CarePermission.entries.size) {
    return stringResource(R.string.care_circle_access_full)
  }
  if (granted.isEmpty()) return stringResource(R.string.care_circle_access_none)
  // Only the first section is capitalized: "Planning & events"
  val labels = granted.mapIndexed { index, permission ->
    val label = stringResource(permission.label)
    if (index == 0) label else label.lowercase()
  }
  if (labels.size == 1) return labels.single()
  return stringResource(
      R.string.care_circle_access_and,
      labels.dropLast(1).joinToString(", "),
      labels.last(),
  )
}

/** The section's name as shown to the user, e.g. "Planning". */
@get:StringRes
internal val CarePermission.label: Int
  get() =
      when (this) {
        CarePermission.PLANNING -> R.string.permission_planning
        CarePermission.EVENTS -> R.string.permission_events
        CarePermission.SYMPTOMS -> R.string.permission_symptoms
        CarePermission.PRESCRIPTIONS -> R.string.permission_prescriptions
      }

/** The relationship as shown to the user, e.g. "Home nurse". */
@get:StringRes
internal val Relationship.label: Int
  get() =
      when (this) {
        Relationship.WIFE -> R.string.relationship_wife
        Relationship.HUSBAND -> R.string.relationship_husband
        Relationship.PARTNER -> R.string.relationship_partner
        Relationship.MOTHER -> R.string.relationship_mother
        Relationship.FATHER -> R.string.relationship_father
        Relationship.DAUGHTER -> R.string.relationship_daughter
        Relationship.SON -> R.string.relationship_son
        Relationship.SISTER -> R.string.relationship_sister
        Relationship.BROTHER -> R.string.relationship_brother
        Relationship.FRIEND -> R.string.relationship_friend
        Relationship.HOME_NURSE -> R.string.relationship_home_nurse
        Relationship.OTHER -> R.string.relationship_other
      }

@Preview(showBackground = true)
@Composable
fun CareCircleScreenPreview() {
  OncompanionTheme {
    CareCircleContent(
        uiState =
            CareCircleUiState.Members(
                members =
                    listOf(
                        CareCircleMember(
                            uid = "1",
                            firstName = "Sophie",
                            familyName = "Dubois",
                            relationship = Relationship.WIFE,
                            permissions = CarePermission.entries.toSet(),
                        ),
                        CareCircleMember(
                            uid = "2",
                            firstName = "Jean",
                            familyName = "Dupont",
                            relationship = Relationship.FRIEND,
                            permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
                        ),
                    ),
            ),
        onBack = {},
        onMemberClick = {},
        onAddMember = {},
        onRetry = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
fun CareCircleScreenEmptyPreview() {
  OncompanionTheme {
    CareCircleContent(
        uiState = CareCircleUiState.Empty,
        onBack = {},
        onMemberClick = {},
        onAddMember = {},
        onRetry = {},
    )
  }
}
