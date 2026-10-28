// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** "19 Oct 2026", how long an invite code works. */
private val validityFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * One invitation to the care circle (#150), following the Figma "US-15 / Invite code" frames: its
 * invite code to send by email, share or copy, what the person will see, and "Cancel invitation".
 * An expired invitation offers a new invite code instead. When the person joins while the screen is
 * open, it says so.
 *
 * @param onBack leaves the screen (back arrow, "Back to circle", or after cancelling)
 * @param onRenewed opens the invitation [inviteCode] that replaced this expired one
 */
@Composable
fun CareCircleInviteScreen(
    onBack: () -> Unit,
    onRenewed: (inviteCode: String) -> Unit,
    // Inside the nav graph, the SavedStateHandle holds the route's invite code
    viewModel: CareCircleInviteViewModel = viewModel {
      CareCircleInviteViewModel(createSavedStateHandle())
    },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // Strings are read from it, so they follow a change of language or configuration
  val resources = LocalResources.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  // Back from another app (e.g. the email app): the invite code may have expired in the meantime
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshExpiry()
    onPauseOrDispose {}
  }
  // Leaves once the invitation is cancelled or replaced
  LaunchedEffect(uiState) {
    when (val state = uiState) {
      CareCircleInviteUiState.Cancelled -> onBack()
      is CareCircleInviteUiState.Renewed -> onRenewed(state.inviteCode)
      else -> {}
    }
  }

  // Another app sends the invitation; if none can, the patient is told to copy the code instead
  fun start(intent: Intent) {
    try {
      context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      val message = resources.getString(R.string.care_circle_invite_no_app)
      scope.launch { snackbarHostState.showSnackbar(message) }
    }
  }

  CareCircleInviteContent(
      uiState = uiState,
      onBack = onBack,
      onSendEmail = { invite -> start(emailIntent(resources, invite)) },
      onShare = { invite -> start(shareIntent(resources, invite)) },
      onCopy = { invite ->
        scope.launch {
          clipboard.setClipEntry(
              ClipEntry(ClipData.newPlainText(invite.displayInviteCode, invite.displayInviteCode))
          )
          // Android 13+ confirms the copy itself; showing both is discouraged
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            snackbarHostState.showSnackbar(resources.getString(R.string.care_circle_invite_copied))
          }
        }
      },
      onRenew = viewModel::renew,
      onCancel = viewModel::askCancel,
      onConfirmCancel = viewModel::confirmCancel,
      onDismissCancel = viewModel::dismissCancel,
      onRetry = viewModel::retry,
      snackbarHostState = snackbarHostState,
  )
}

/** Opens the patient's email app with the invitation to [invite]'s email, ready to send. */
private fun emailIntent(resources: Resources, invite: CareCircleInvite): Intent =
    Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        .putExtra(Intent.EXTRA_EMAIL, listOfNotNull(invite.email).toTypedArray())
        .putExtra(
            Intent.EXTRA_SUBJECT,
            resources.getString(R.string.care_circle_invite_email_subject),
        )
        .putExtra(Intent.EXTRA_TEXT, invitationMessage(resources, invite))

/** Opens the share sheet (messages, WhatsApp...) with the invitation. */
private fun shareIntent(resources: Resources, invite: CareCircleInvite): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(
                Intent.EXTRA_SUBJECT,
                resources.getString(R.string.care_circle_invite_email_subject),
            )
            .putExtra(Intent.EXTRA_TEXT, invitationMessage(resources, invite)),
        null,
    )

/**
 * The invitation text: the invite code, how to use it and until when it works, signed with the
 * patient's name. A code shared days after it was created works for less than 7 days, so the date
 * comes from [CareCircleInvite.expiresAt]; until the server has the invite, its 7 days haven't
 * started.
 */
private fun invitationMessage(resources: Resources, invite: CareCircleInvite): String =
    resources.getString(
        R.string.care_circle_invite_message,
        invite.details.firstName,
        invite.displayInviteCode,
        invite.ownerFirstName,
        invite.expiresAt?.let {
          resources.getString(R.string.care_circle_invite_message_valid_until, formatDay(it))
        } ?: resources.getString(R.string.care_circle_invite_message_valid_once_sent),
    )

/** Stateless content of the invite code screen, so each state can be tested on its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareCircleInviteContent(
    uiState: CareCircleInviteUiState,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    onSendEmail: (CareCircleInvite) -> Unit = {},
    onShare: (CareCircleInvite) -> Unit = {},
    onCopy: (CareCircleInvite) -> Unit = {},
    onRenew: () -> Unit = {},
    onCancel: () -> Unit = {},
    onConfirmCancel: () -> Unit = {},
    onDismissCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
  // No text field on this screen, so no imePadding()
  Scaffold(
      modifier = modifier.testTag(C.Tag.care_circle_invite_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.care_circle_invite_title)) },
            navigationIcon = {
              IconButton(
                  onClick = onBack,
                  modifier = Modifier.testTag(C.Tag.care_circle_invite_back_button),
              ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.care_circle_invite_back),
                )
              }
            },
        )
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
    when (uiState) {
      // Cancelled and renewed only show while the screen is leaving
      CareCircleInviteUiState.Loading,
      CareCircleInviteUiState.Cancelled,
      is CareCircleInviteUiState.Renewed ->
          Box(contentModifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(C.Tag.care_circle_invite_loading))
          }
      CareCircleInviteUiState.NotFound ->
          // Neutral wording: a used invitation can vanish just before its new member arrives
          CenteredMessage(
              title = stringResource(R.string.care_circle_invite_not_found),
              body = stringResource(R.string.care_circle_invite_not_found_body),
              modifier = contentModifier.testTag(C.Tag.care_circle_invite_not_found),
              action = { BackToCircleButton(onBack) },
          )
      CareCircleInviteUiState.Error ->
          CenteredMessage(
              title = stringResource(R.string.care_circle_invite_error),
              modifier = contentModifier.testTag(C.Tag.care_circle_invite_error),
          ) {
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag(C.Tag.care_circle_invite_retry_button),
            ) {
              Text(stringResource(R.string.care_circle_retry))
            }
          }
      is CareCircleInviteUiState.Joined -> {
        val member = uiState.member
        CenteredMessage(
            title = stringResource(R.string.care_circle_invite_joined_title, member.firstName),
            body =
                stringResource(
                    R.string.care_circle_invite_joined_body,
                    member.fullName,
                    accessSummary(member.permissions),
                ),
            icon = {
              Icon(
                  painterResource(R.drawable.ic_check_circle),
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(48.dp),
              )
            },
            modifier = contentModifier.testTag(C.Tag.care_circle_invite_joined),
            action = { BackToCircleButton(onBack) },
        )
      }
      is CareCircleInviteUiState.Invite -> {
        InviteDetails(
            state = uiState,
            onSendEmail = { onSendEmail(uiState.invite) },
            onShare = { onShare(uiState.invite) },
            onCopy = { onCopy(uiState.invite) },
            onRenew = onRenew,
            onCancel = onCancel,
            modifier = contentModifier,
        )
        if (uiState.confirmingCancel) {
          CancelDialog(uiState.invite.details.firstName, onConfirmCancel, onDismissCancel)
        }
      }
    }
  }
}

/**
 * A centered message, e.g. "Julie has joined your circle", with an optional icon and action. It
 * scrolls when it doesn't fit (a phone held sideways, large text, a long name), so the action stays
 * reachable.
 */
@Composable
private fun CenteredMessage(
    title: String,
    modifier: Modifier,
    body: String? = null,
    icon: @Composable () -> Unit = {},
    action: @Composable () -> Unit = {},
) {
  BoxWithConstraints(modifier) {
    // At least as tall as the screen, so a short message stays vertically centered
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
      icon()
      Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
      body?.let {
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
}

@Composable
private fun BackToCircleButton(onBack: () -> Unit) {
  Button(
      onClick = onBack,
      modifier =
          Modifier.heightIn(min = 56.dp).testTag(C.Tag.care_circle_invite_back_to_circle_button),
  ) {
    Text(stringResource(R.string.care_circle_invite_back_to_circle))
  }
}

/** The person, the invite code and its actions, what they will see, and "Cancel invitation". */
@Composable
private fun InviteDetails(
    state: CareCircleInviteUiState.Invite,
    onSendEmail: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onRenew: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
  val invite = state.invite
  Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
    // "Sister · Invitation pending", or why the invite code doesn't work (yet)
    val relationship = stringResource(invite.details.relationship.label)
    val status =
        when {
          state.expired -> R.string.care_circle_invite_expired
          !invite.isSynced -> R.string.care_circle_invite_not_synced
          else -> R.string.care_circle_invite_pending
        }
    MemberHeader(
        details = invite.details,
        subtitle =
            stringResource(
                R.string.care_circle_member_summary,
                relationship,
                stringResource(status),
            ),
        subtitleColor =
            if (state.expired) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
    )

    InviteCodeCard(invite, state.expired)
    if (!invite.isSynced && !state.expired) SyncWarning()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (state.expired) {
        RenewButton(isWorking = state.isWorking, onRenew = onRenew)
      } else {
        InviteActions(invite, enabled = !state.isWorking, onSendEmail, onShare, onCopy)
      }
      if (state.actionFailed) {
        Text(
            text = stringResource(R.string.care_circle_invite_action_error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(C.Tag.care_circle_invite_action_error),
        )
      }
    }

    // What the person will see once they join
    Subheader(stringResource(R.string.care_circle_member_can_see))
    CarePermission.entries.forEach { permission ->
      PermissionItem(permission = permission, granted = permission in invite.details.permissions)
    }

    TextButton(
        onClick = onCancel,
        enabled = !state.isWorking,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier =
            Modifier.align(Alignment.CenterHorizontally)
                .heightIn(min = 56.dp)
                .testTag(C.Tag.care_circle_invite_cancel_button),
    ) {
      Text(stringResource(R.string.care_circle_invite_cancel))
    }
  }
}

/**
 * The invite code, large and monospaced so it can be read aloud, and until when it works. An
 * expired invite code is hidden: it can no longer be used.
 */
@Composable
private fun InviteCodeCard(invite: CareCircleInvite, expired: Boolean) {
  Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      val expiresAt = invite.expiresAt?.let { formatDay(it) }
      if (expired) {
        Text(
            text = stringResource(R.string.care_circle_invite_expired),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(C.Tag.care_circle_invite_expired),
        )
        expiresAt?.let {
          Text(
              text = stringResource(R.string.care_circle_invite_expired_on, it),
              style = MaterialTheme.typography.bodySmall,
          )
        }
      } else {
        Text(
            text = stringResource(R.string.care_circle_invite_code_label),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = invite.displayInviteCode,
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag(C.Tag.care_circle_invite_code),
        )
        Text(
            text =
                expiresAt?.let { stringResource(R.string.care_circle_invite_valid_until, it) }
                    ?: stringResource(R.string.care_circle_invite_valid_once_sent),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(C.Tag.care_circle_invite_valid),
        )
      }
    }
  }
}

/** The day [instant] falls on in the phone's time zone, e.g. "19 Oct 2026". */
private fun formatDay(instant: Instant): String =
    validityFormatter.format(instant.atZone(ZoneId.systemDefault()))

/**
 * The invite code only works on another phone once the server has it (architecture.md). Waiting for
 * the server also happens online, so this doesn't say the phone is offline.
 */
@Composable
private fun SyncWarning() {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 24.dp, vertical = 4.dp)
              .testTag(C.Tag.care_circle_invite_sync_warning),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        Icons.Outlined.Warning,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.care_circle_invite_sync_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** "Send by email" when the patient gave an email, then "Share" and "Copy". */
@Composable
private fun InviteActions(
    invite: CareCircleInvite,
    enabled: Boolean,
    onSendEmail: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
  if (!invite.email.isNullOrBlank()) {
    Button(
        onClick = onSendEmail,
        enabled = enabled,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(C.Tag.care_circle_invite_email_button),
    ) {
      Text(stringResource(R.string.care_circle_invite_send_email))
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(
        onClick = onShare,
        enabled = enabled,
        modifier =
            Modifier.weight(1f)
                .heightIn(min = 56.dp)
                .testTag(C.Tag.care_circle_invite_share_button),
    ) {
      Text(stringResource(R.string.care_circle_invite_share))
    }
    OutlinedButton(
        onClick = onCopy,
        enabled = enabled,
        modifier =
            Modifier.weight(1f).heightIn(min = 56.dp).testTag(C.Tag.care_circle_invite_copy_button),
    ) {
      Text(stringResource(R.string.care_circle_invite_copy))
    }
  }
}

/** "Get a new code", with a spinner while the new invitation is being created. */
@Composable
private fun RenewButton(isWorking: Boolean, onRenew: () -> Unit) {
  Button(
      onClick = onRenew,
      enabled = !isWorking,
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 56.dp)
              .testTag(C.Tag.care_circle_invite_renew_button),
  ) {
    if (isWorking) {
      CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    } else {
      Text(stringResource(R.string.care_circle_invite_renew))
    }
  }
}

/** "Cancel Julie's invitation? The code will stop working." */
@Composable
private fun CancelDialog(firstName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.care_circle_invite_cancel_title, firstName)) },
      text = { Text(stringResource(R.string.care_circle_invite_cancel_body)) },
      confirmButton = {
        TextButton(
            onClick = onConfirm,
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag(C.Tag.care_circle_invite_cancel_confirm),
        ) {
          Text(stringResource(R.string.care_circle_invite_cancel))
        }
      },
      dismissButton = {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(C.Tag.care_circle_invite_cancel_dismiss),
        ) {
          Text(stringResource(R.string.care_circle_invite_cancel_keep))
        }
      },
      modifier = Modifier.testTag(C.Tag.care_circle_invite_cancel_dialog),
  )
}

@Preview(showBackground = true)
@Composable
fun CareCircleInviteScreenPreview() {
  OncompanionTheme {
    CareCircleInviteContent(
        uiState =
            CareCircleInviteUiState.Invite(
                invite =
                    CareCircleInvite(
                        inviteCode = "K7M2PQ9XRT",
                        ownerUid = "anne",
                        ownerFirstName = "Anne",
                        details =
                            CareCircleMemberDetails(
                                firstName = "Julie",
                                familyName = "Martin",
                                relationship = Relationship.SISTER,
                                permissions =
                                    setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
                            ),
                        email = "julie@example.com",
                        createdAt = Instant.parse("2026-10-12T09:30:00Z"),
                    ),
                expired = false,
            ),
        onBack = {},
        onSendEmail = {},
        onShare = {},
        onCopy = {},
        onRenew = {},
        onCancel = {},
        onConfirmCancel = {},
        onDismissCancel = {},
        onRetry = {},
    )
  }
}
