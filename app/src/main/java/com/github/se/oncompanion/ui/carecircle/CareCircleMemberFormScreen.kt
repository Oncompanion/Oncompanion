// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.SaveButton
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.common.isCompactHeight
import com.github.se.oncompanion.ui.theme.OncompanionTheme

/**
 * Adds someone to the care circle (#79), following the Figma "US-13 / Add member": their name,
 * relationship, an optional email and what they can see, all off by default. Saving creates their
 * invitation, then calls [onSaved] with its invite code. An email that already has an invitation
 * can't be invited again: [onOpenInvite] opens that invitation instead, with its invite code.
 *
 * With a member to edit (#75, Figma "US-14 / Edit member"), the same form shows their details and
 * account email, which can't be changed; saving updates them, then calls [onSaved] with null.
 */
@Composable
fun CareCircleMemberFormScreen(
    onBack: () -> Unit,
    onSaved: (inviteCode: String?) -> Unit,
    onOpenInvite: (inviteCode: String) -> Unit,
    viewModel: CareCircleMemberFormViewModel = viewModel { CareCircleMemberFormViewModel() },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // An invitation with the typed email may have expired while the phone slept
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshExpiry()
    onPauseOrDispose {}
  }
  // Leaves once per save, even if the screen recomposes before it is gone
  LaunchedEffect(uiState.save) {
    if (uiState.save == SavePhase.SAVED) {
      val inviteCode = uiState.savedInviteCode
      viewModel.consumeSaved()
      onSaved(inviteCode)
    }
  }
  CareCircleMemberFormContent(
      uiState = uiState,
      onFirstNameChange = viewModel::onFirstNameChange,
      onFamilyNameChange = viewModel::onFamilyNameChange,
      onRelationshipChange = viewModel::onRelationshipChange,
      onEmailChange = viewModel::onEmailChange,
      onPermissionChange = viewModel::onPermissionChange,
      onSave = viewModel::save,
      onBack = onBack,
      onCanAddChange = viewModel::onCanAddChange,
      onOpenInvite = onOpenInvite,
  )
}

/** Stateless content of the member form, so each state can be tested without Firebase. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareCircleMemberFormContent(
    uiState: CareCircleMemberFormUiState,
    onFirstNameChange: (String) -> Unit,
    onFamilyNameChange: (String) -> Unit,
    onRelationshipChange: (Relationship) -> Unit,
    onEmailChange: (String) -> Unit,
    onPermissionChange: (CarePermission, Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCanAddChange: (CarePermission, Boolean) -> Unit = { _, _ -> },
    onOpenInvite: (inviteCode: String) -> Unit = {},
) {
  // A short screen (a phone held sideways) lets the top bar scroll with the form: with the
  // keyboard open, a fixed one would leave no room for the field being typed. The loading and
  // missing-member states have no text field, so they keep a fixed one
  val topBarScrolls = isCompactHeight() && uiState.showsForm
  Scaffold(
      // Ends the screen at the keyboard (the activity is adjustResize): Send can be scrolled to
      modifier = modifier.testTag(C.Tag.care_circle_form_screen).imePadding(),
      topBar = { if (!topBarScrolls) FormTopBar(uiState.isEditing, onBack) },
  ) { padding ->
    // Editing: the member's details are loading, or the member can't be edited
    if (!uiState.showsForm) {
      FormMessage(uiState, Modifier.fillMaxSize().padding(padding))
      return@Scaffold
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
    ) {
      // The Scaffold already keeps the content clear of the status bar
      if (topBarScrolls) FormTopBar(uiState.isEditing, onBack, WindowInsets(0))
      Column(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        NameRow(uiState, onFirstNameChange, onFamilyNameChange)
        RelationshipField(uiState.relationship, uiState.canEdit, onRelationshipChange)
        EmailField(uiState, onEmailChange, onOpenInvite)
      }
      PermissionSwitches(uiState, onPermissionChange, onCanAddChange)
      SaveSection(uiState, onSave)
    }
  }
}

/** "Add a member", or "Edit member" when editing, with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormTopBar(
    isEditing: Boolean,
    onBack: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
  TopAppBar(
      title = {
        Text(
            stringResource(
                if (isEditing) R.string.care_circle_form_edit_title
                else R.string.care_circle_form_add_title
            )
        )
      },
      navigationIcon = {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag(C.Tag.care_circle_form_back_button),
        ) {
          Icon(
              painter = painterResource(R.drawable.ic_arrow_back),
              contentDescription = stringResource(R.string.care_circle_form_back),
          )
        }
      },
      windowInsets = windowInsets,
  )
}

/** In place of the form while the member to edit loads, or when they can't be edited. */
@Composable
private fun FormMessage(uiState: CareCircleMemberFormUiState, modifier: Modifier) {
  Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
    when {
      uiState.isLoading ->
          CircularProgressIndicator(modifier = Modifier.testTag(C.Tag.care_circle_form_loading))
      uiState.notFound ->
          Text(
              text = stringResource(R.string.care_circle_member_not_found),
              textAlign = TextAlign.Center,
              modifier = Modifier.testTag(C.Tag.care_circle_form_not_found),
          )
      else ->
          Text(
              text = stringResource(R.string.care_circle_member_error),
              textAlign = TextAlign.Center,
              modifier = Modifier.testTag(C.Tag.care_circle_form_load_error),
          )
    }
  }
}

/**
 * First name (required) and last name (optional), side by side. The first name only turns red once
 * the patient has typed in it, so a new form doesn't start out all red.
 */
@Composable
private fun NameRow(
    uiState: CareCircleMemberFormUiState,
    onFirstNameChange: (String) -> Unit,
    onFamilyNameChange: (String) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
        value = uiState.firstName,
        onValueChange = onFirstNameChange,
        enabled = uiState.canEdit,
        label = { Text(stringResource(R.string.care_circle_form_first_name)) },
        supportingText = { Text(stringResource(R.string.care_circle_form_required)) },
        isError = uiState.firstNameTouched && uiState.missingFirstName,
        singleLine = true,
        keyboardOptions = nameKeyboard,
        modifier = Modifier.weight(1f).testTag(C.Tag.care_circle_form_first_name),
    )
    OutlinedTextField(
        value = uiState.familyName,
        onValueChange = onFamilyNameChange,
        enabled = uiState.canEdit,
        label = { Text(stringResource(R.string.care_circle_form_family_name)) },
        singleLine = true,
        keyboardOptions = nameKeyboard,
        modifier = Modifier.weight(1f).testTag(C.Tag.care_circle_form_family_name),
    )
  }
}

private val nameKeyboard =
    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)

/**
 * The relationship, picked from [Relationship.entries]. Nothing is picked at first, so a missing
 * relationship can be shown: "Other" is a real choice, not a default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelationshipField(
    relationship: Relationship?,
    enabled: Boolean,
    onRelationshipChange: (Relationship) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
      expanded = expanded && enabled,
      onExpandedChange = { expanded = it },
  ) {
    OutlinedTextField(
        value = relationship?.let { stringResource(it.label) }.orEmpty(),
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        label = { Text(stringResource(R.string.care_circle_form_relationship)) },
        supportingText = { Text(stringResource(R.string.care_circle_form_required)) },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .testTag(C.Tag.care_circle_form_relationship),
    )
    ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
      Relationship.entries.forEach { option ->
        DropdownMenuItem(
            text = { Text(stringResource(option.label)) },
            onClick = {
              onRelationshipChange(option)
              expanded = false
            },
            modifier = Modifier.testTag(C.Tag.careCircleFormRelationshipOption(option.name)),
        )
      }
    }
  }
}

/**
 * The optional email the invitation is sent to, with an error once it doesn't look like one or
 * someone in the circle already has it; under it, a link to their invitation if they have one. When
 * editing, the member's account email, which can't be changed (hidden if their account has none).
 */
@Composable
private fun EmailField(
    uiState: CareCircleMemberFormUiState,
    onEmailChange: (String) -> Unit,
    onOpenInvite: (inviteCode: String) -> Unit,
) {
  if (uiState.isEditing && uiState.email.isBlank()) return
  val duplicate = uiState.duplicate
  OutlinedTextField(
      value = uiState.email,
      onValueChange = onEmailChange,
      enabled = uiState.canEdit && !uiState.isEditing,
      label = { Text(stringResource(R.string.care_circle_form_email)) },
      trailingIcon =
          if (uiState.isEditing) {
            { Icon(Icons.Outlined.Email, contentDescription = null) }
          } else null,
      supportingText = {
        Text(
            when {
              uiState.isEditing -> stringResource(R.string.care_circle_form_account_email)
              uiState.invalidEmail -> stringResource(R.string.care_circle_form_invalid_email)
              duplicate == null -> stringResource(R.string.care_circle_form_email_hint)
              duplicate is EmailAlreadyInCircle.ExpiredInvite ->
                  stringResource(R.string.care_circle_form_duplicate_expired, duplicate.firstName)
              else -> stringResource(R.string.care_circle_form_duplicate, duplicate.firstName)
            }
        )
      },
      isError = uiState.invalidEmail || duplicate != null,
      singleLine = true,
      keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.care_circle_form_email),
  )
  // Their invitation already holds the access to give and the code to send, or to renew
  if (duplicate is EmailAlreadyInCircle.Invite && uiState.canEdit) {
    TextButton(
        onClick = { onOpenInvite(duplicate.inviteCode) },
        modifier = Modifier.testTag(C.Tag.care_circle_form_open_invite),
    ) {
      Text(stringResource(R.string.care_circle_form_open_invite))
    }
  }
}

/**
 * "What can Julie see?" and one switch per section. Driven by [CarePermission.entries], so a new
 * section only needs an enum value. The whole row toggles, a large enough touch target.
 */
@Composable
private fun PermissionSwitches(
    uiState: CareCircleMemberFormUiState,
    onPermissionChange: (CarePermission, Boolean) -> Unit,
    onCanAddChange: (CarePermission, Boolean) -> Unit,
) {
  val firstName = uiState.firstName.trim()
  Text(
      text =
          if (firstName.isEmpty()) stringResource(R.string.care_circle_form_can_see_unnamed)
          else stringResource(R.string.care_circle_form_can_see, firstName),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier =
          Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
              .testTag(C.Tag.care_circle_form_can_see),
  )
  CarePermission.entries.forEach { permission ->
    val granted = permission in uiState.permissions
    ListItem(
        headlineContent = { Text(stringResource(permission.label)) },
        supportingContent = { Text(stringResource(permission.description)) },
        // The row handles the tap, so the switch only shows the state
        trailingContent = { Switch(checked = granted, onCheckedChange = null) },
        modifier =
            Modifier.heightIn(min = 56.dp)
                .toggleable(
                    value = granted,
                    enabled = uiState.canEdit,
                    role = Role.Switch,
                    onValueChange = { onPermissionChange(permission, it) },
                )
                .testTag(C.Tag.careCircleFormPermission(permission.name)),
    )
    if (permission in CareCircleMemberDetails.ADDABLE_SECTIONS) {
      CanAddSwitch(permission, uiState, granted, onCanAddChange)
    }
  }
}

/**
 * Lets the member add to [section] (e.g. "Allow adding symptoms"), indented under the section it
 * depends on: only for someone who sees the section, so it is greyed out while the section is off.
 * Nothing for a section nobody can add to.
 */
@Composable
private fun CanAddSwitch(
    section: CarePermission,
    uiState: CareCircleMemberFormUiState,
    sectionGranted: Boolean,
    onCanAddChange: (CarePermission, Boolean) -> Unit,
) {
  val label = section.addLabel ?: return
  val description = section.addDescription ?: return
  val allowed = section in uiState.canAdd
  val enabled = uiState.canEdit && sectionGranted
  ListItem(
      headlineContent = { Text(stringResource(label)) },
      supportingContent = { Text(stringResource(description)) },
      trailingContent = { Switch(checked = allowed, onCheckedChange = null, enabled = enabled) },
      modifier =
          Modifier.padding(start = 24.dp)
              .heightIn(min = 56.dp)
              .toggleable(
                  value = allowed,
                  enabled = enabled,
                  role = Role.Switch,
                  onValueChange = { onCanAddChange(section, it) },
              )
              .testTag(C.Tag.careCircleFormCanAdd(section.name)),
  )
}

/**
 * The save error if the last save failed, "Send invitation" (or "Save changes"), and under it
 * what's still missing: the button is disabled until the form is complete, so the patient must be
 * told why.
 */
@Composable
private fun SaveSection(uiState: CareCircleMemberFormUiState, onSave: () -> Unit) {
  if (uiState.saveFailed) {
    Text(
        text =
            stringResource(
                if (uiState.isEditing) R.string.care_circle_form_update_error
                else R.string.care_circle_form_save_error
            ),
        color = MaterialTheme.colorScheme.error,
        modifier =
            Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)
                .testTag(C.Tag.care_circle_form_save_error),
    )
  }
  SaveButton(
      text =
          stringResource(
              if (uiState.isEditing) R.string.care_circle_form_save_changes
              else R.string.care_circle_form_send_invitation
          ),
      enabled = uiState.canSave,
      isSaving = uiState.save == SavePhase.SAVING,
      onClick = onSave,
      testTag = C.Tag.care_circle_form_save_button,
      savingTestTag = C.Tag.care_circle_form_saving,
  )
  missingText(uiState)?.let {
    Text(
        text = stringResource(it),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                .testTag(C.Tag.care_circle_form_missing),
    )
  }
}

/** What the patient still has to fill in, or null when nothing required is missing. */
@StringRes
private fun missingText(uiState: CareCircleMemberFormUiState): Int? =
    when {
      !uiState.canEdit -> null
      // The email has its own message, and filling in the rest wouldn't allow sending
      uiState.duplicate != null -> null
      // A member always has a relationship: only the first name can be missing
      uiState.isEditing ->
          R.string.care_circle_form_missing_first_name_edit.takeIf { uiState.missingFirstName }
      uiState.missingFirstName && uiState.missingRelationship ->
          R.string.care_circle_form_missing_both
      uiState.missingFirstName -> R.string.care_circle_form_missing_first_name
      uiState.missingRelationship -> R.string.care_circle_form_missing_relationship
      else -> null
    }

@Preview(showBackground = true)
@Composable
fun CareCircleMemberFormScreenPreview() {
  OncompanionTheme {
    CareCircleMemberFormContent(
        uiState =
            CareCircleMemberFormUiState(
                firstName = "Julie",
                email = "julie@example.com",
                permissions = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
                firstNameTouched = true,
            ),
        onFirstNameChange = {},
        onFamilyNameChange = {},
        onRelationshipChange = {},
        onEmailChange = {},
        onPermissionChange = { _, _ -> },
        onSave = {},
        onBack = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
fun CareCircleMemberFormEditPreview() {
  OncompanionTheme {
    CareCircleMemberFormContent(
        uiState =
            CareCircleMemberFormUiState(
                firstName = "Marc",
                familyName = "Dubois",
                relationship = Relationship.SON,
                email = "marc.dubois@email.com",
                permissions =
                    setOf(CarePermission.PLANNING, CarePermission.EVENTS, CarePermission.SYMPTOMS),
                isEditing = true,
            ),
        onFirstNameChange = {},
        onFamilyNameChange = {},
        onRelationshipChange = {},
        onEmailChange = {},
        onPermissionChange = { _, _ -> },
        onSave = {},
        onBack = {},
    )
  }
}
