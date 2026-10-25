// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.theme.OncompanionTheme

/**
 * Displays the members of the signed-in user's care circle (US-13), following the "US-13 / Circle
 * members" as well as the people invited who haven't joined yet. Tapping a member opens their
 * details (US-14), and "Add member" opens the form adding someone (#79). @TODO: Invitations don't
 * open anything yet: the invite code screen comes in a later PR.
 */
@Composable
fun CareCircleScreen(
    navigationActions: NavigationActions,
    modifier: Modifier = Modifier,
    viewModel: CareCircleViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  // Back from another screen or app: an invitation may have expired in the meantime
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshExpiry()
    onPauseOrDispose {}
  }

  CareCircleContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onMemberClick = { member ->
        navigationActions.navigateTo(Screen.careCircleMember(member.uid))
      },
      onAddMember = { navigationActions.navigateTo(Screen.CARE_CIRCLE_ADD) },
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
        if (uiState is CareCircleUiState.CareCircleMembers) {
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
      is CareCircleUiState.CareCircleMembers ->
          CareCircleMemberList(uiState, onMemberClick, contentModifier)
    }
  }
}

@Composable
private fun LoadingState(modifier: Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    CircularProgressIndicator(modifier = Modifier.testTag(C.Tag.care_circle_loading))
  }
}

/**
 * The circle in two sections: "Members (n)", the people who can see the patient's data, then
 * "Waiting to join (m)", the invitations not used yet. An empty section is not shown.
 */
@Composable
private fun CareCircleMemberList(
    careCircle: CareCircleUiState.CareCircleMembers,
    onMemberClick: (CareCircleMember) -> Unit,
    modifier: Modifier,
) {
  LazyColumn(
      modifier = modifier.testTag(C.Tag.care_circle_member_list),
      // Room for the FAB, so it never hides the last member
      contentPadding = PaddingValues(bottom = 88.dp),
  ) {
    item { Intro() }
    // People who joined
    if (careCircle.joined.isNotEmpty()) {
      item {
        SectionHeader(
            text = stringResource(R.string.care_circle_members_count, careCircle.joined.size),
            tag = C.Tag.care_circle_members_count,
        )
      }
      items(careCircle.joined, key = { it.key }) { entry ->
        MemberItem(member = entry.member, onClick = { onMemberClick(entry.member) })
      }
    }
    // Invitations not used yet
    if (careCircle.pending.isNotEmpty()) {
      item {
        SectionHeader(
            text = stringResource(R.string.care_circle_pending_count, careCircle.pending.size),
            tag = C.Tag.care_circle_pending_section,
        )
      }
      items(careCircle.pending, key = { it.key }) { entry -> PendingItem(entry) }
    }
  }
}

@Composable
private fun SectionHeader(text: String, tag: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
  )
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

/**
 * One invitation, laid out like a member with a "Pending" label. Its supporting text says whether
 * the invite code can be used: pending (with what the person will see), expired, or not on the
 * server yet. Not clickable yet: the invite code screen comes in a later PR (#150).
 */
@Composable
private fun PendingItem(entry: CareCircleEntry.Pending) {
  val invite = entry.invite
  ListItem(
      headlineContent = { Text(entry.details.fullName) },
      supportingContent = {
        when {
          entry.expired ->
              Text(
                  stringResource(R.string.care_circle_invite_expired),
                  color = MaterialTheme.colorScheme.error,
              )
          !invite.isSynced -> Text(stringResource(R.string.care_circle_invite_not_synced))
          else ->
              Text(
                  stringResource(
                      R.string.care_circle_member_summary,
                      stringResource(R.string.care_circle_invite_pending),
                      accessSummary(entry.details.permissions),
                  )
              )
        }
      },
      leadingContent = { Monogram(entry.details.initial) },
      trailingContent = { PendingLabel() },
      modifier = Modifier.testTag(C.Tag.careCircleInvite(invite.inviteCode)),
  )
}

@Composable
private fun PendingLabel() {
  Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      shape = MaterialTheme.shapes.small,
      modifier = Modifier.testTag(C.Tag.care_circle_pending_label),
  ) {
    Text(
        text = stringResource(R.string.care_circle_pending_label),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

/** A round avatar with a person's [initial], shared with "Whose care?". */
@Composable
internal fun Monogram(initial: String) {
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
            CareCircleUiState.CareCircleMembers(
                joined =
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
                        )
                        .map { CareCircleEntry.Joined(it) },
                pending =
                    listOf(
                        CareCircleEntry.Pending(
                            CareCircleInvite(
                                inviteCode = "K7M2PQ9XRT",
                                ownerUid = "owner",
                                ownerFirstName = "Anne",
                                details =
                                    CareCircleMemberDetails(
                                        firstName = "Julie",
                                        relationship = Relationship.DAUGHTER,
                                        permissions =
                                            setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
                                    ),
                            ),
                            expired = false,
                        )
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
