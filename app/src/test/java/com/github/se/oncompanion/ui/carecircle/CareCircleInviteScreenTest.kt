// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.Screen
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class CareCircleInviteScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val createdAt = Instant.parse("2026-10-12T09:30:00Z")

  private val julie =
      CareCircleInvite(
          inviteCode = "K7M2PQ9XRT",
          ownerUid = "owner",
          ownerFirstName = "Anne",
          details =
              CareCircleMemberDetails(
                  firstName = "Julie",
                  familyName = "Martin",
                  relationship = Relationship.SISTER,
                  permissions = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
              ),
          email = "julie@example.com",
          createdAt = createdAt,
      )

  /** Records the events of [CareCircleInviteContent]. */
  private class Events {
    var back = 0
    val emails = mutableListOf<CareCircleInvite>()
    val shares = mutableListOf<CareCircleInvite>()
    val copies = mutableListOf<CareCircleInvite>()
    var renew = 0
    var cancel = 0
    var confirmCancel = 0
    var dismissCancel = 0
    var retry = 0
  }

  private fun setContent(uiState: CareCircleInviteUiState): Events {
    val events = Events()
    composeTestRule.setContent {
      CareCircleInviteContent(
          uiState = uiState,
          onBack = { events.back++ },
          onSendEmail = { events.emails += it },
          onShare = { events.shares += it },
          onCopy = { events.copies += it },
          onRenew = { events.renew++ },
          onCancel = { events.cancel++ },
          onConfirmCancel = { events.confirmCancel++ },
          onDismissCancel = { events.dismissCancel++ },
          onRetry = { events.retry++ },
      )
    }
    return events
  }

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  /** "19 Oct 2026" in the phone's time zone, like the screen. */
  private fun day(instant: Instant) =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
          .format(instant.atZone(ZoneId.systemDefault()))

  // ---- simple states ----

  @Test
  fun loading_showsASpinner_andBackWorks() {
    val events = setContent(CareCircleInviteUiState.Loading)
    composeTestRule.onNodeWithText(string(R.string.care_circle_invite_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_back_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.back) }
  }

  @Test
  fun cancelledAndRenewed_showASpinnerWhileLeaving() {
    setContent(CareCircleInviteUiState.Renewed("NNNNN23456"))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_code).assertDoesNotExist()
  }

  @Test
  fun notFound_doesNotClaimItWasCancelled_andGoesBackToTheCircle() {
    val events = setContent(CareCircleInviteUiState.NotFound)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_not_found).assertIsDisplayed()
    composeTestRule.onNodeWithText("This invitation is no longer pending.").assertIsDisplayed()
    // The person may have just joined, so the screen points to the circle instead of guessing
    composeTestRule
        .onNodeWithText(string(R.string.care_circle_invite_not_found_body))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_back_to_circle_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.back) }
  }

  @Test
  fun error_offersRetry() {
    val events = setContent(CareCircleInviteUiState.Error)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_error).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_retry_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.retry) }
  }

  @Test
  fun joined_saysWhoJoined_andWhatTheyCanSee() {
    val member =
        CareCircleMember(
            uid = "caregiver",
            firstName = "Julie",
            familyName = "Martin",
            permissions = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
        )
    val events = setContent(CareCircleInviteUiState.Joined(member))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_joined).assertIsDisplayed()
    composeTestRule.onNodeWithText("Julie has joined your circle").assertIsDisplayed()
    composeTestRule
        .onNodeWithText("Julie Martin can now see: Planning & symptoms.", substring = true)
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_back_to_circle_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.back) }
  }

  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  @Test
  fun joined_staysReachable_sideways_withLargeText_andALongName() {
    val member =
        CareCircleMember(
            uid = "caregiver",
            firstName = "Marie-Christine",
            familyName = "Fontaine-Delacroix de Montmorency",
            permissions = CarePermission.entries.toSet(),
        )
    composeTestRule.setContent {
      DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
        CareCircleInviteContent(
            uiState = CareCircleInviteUiState.Joined(member),
            onBack = {},
            onRetry = {},
        )
      }
    }
    // The message scrolls, so its button can still be reached
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_invite_back_to_circle_button)
        .performScrollTo()
        .assertIsDisplayed()
  }

  // ---- pending invitation ----

  @Test
  fun pendingInvite_showsThePerson_theInviteCode_andUntilWhenItWorks() {
    setContent(CareCircleInviteUiState.Invite(julie, expired = false))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_name).assertTextEquals("Julie Martin")
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_subtitle)
        .assertTextEquals("Sister · Invitation pending")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_code).assertTextEquals("K7M2P-Q9XRT")
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_invite_valid)
        .assertTextEquals(string(R.string.care_circle_invite_valid_until, day(julie.expiresAt!!)))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_sync_warning).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_renew_button).assertDoesNotExist()
  }

  @Test
  fun pendingInvite_showsWhatThePersonWillSee() {
    setContent(CareCircleInviteUiState.Invite(julie, expired = false))
    composeTestRule
        .onNodeWithTag(C.Tag.careCircleMemberPermission(CarePermission.PLANNING.name))
        .performScrollTo()
        .assertIsOn()
    composeTestRule
        .onNodeWithTag(C.Tag.careCircleMemberPermission(CarePermission.EVENTS.name))
        .performScrollTo()
        .assertIsOff()
  }

  @Test
  fun actions_sendEmailShareAndCopyTheInvite() {
    val events = setContent(CareCircleInviteUiState.Invite(julie, expired = false))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_email_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_share_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_copy_button).performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf(julie), events.emails)
      assertEquals(listOf(julie), events.shares)
      assertEquals(listOf(julie), events.copies)
    }
  }

  @Test
  fun inviteWithoutEmail_hasNoEmailButton() {
    setContent(CareCircleInviteUiState.Invite(julie.copy(email = null), expired = false))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_email_button).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_share_button).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_copy_button).assertIsDisplayed()
  }

  @Test
  fun unsyncedInvite_saysItIsWaitingToSync_withoutClaimingOffline() {
    setContent(
        CareCircleInviteUiState.Invite(
            julie.copy(createdAt = null, isSynced = false),
            expired = false,
        )
    )
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_sync_warning).assertIsDisplayed()
    composeTestRule
        .onNodeWithText("Waiting to sync. This code will work once the invitation has synced.")
        .assertIsDisplayed()
    // Waiting for the server also happens online
    composeTestRule
        .onNodeWithText("offline", substring = true, ignoreCase = true)
        .assertDoesNotExist()
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_invite_valid)
        .assertTextEquals(string(R.string.care_circle_invite_valid_once_sent))
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_subtitle)
        .assertTextEquals("Sister · " + string(R.string.care_circle_invite_not_synced))
    // The invite code can already be shared: it works once the invitation has synced
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_code).assertIsDisplayed()
  }

  // ---- expired invitation ----

  @Test
  fun expiredInvite_hidesTheInviteCode_andOffersANewOne() {
    val events = setContent(CareCircleInviteUiState.Invite(julie, expired = true))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_expired).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.care_circle_invite_expired_on, day(julie.expiresAt!!)))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_subtitle)
        .assertTextEquals("Sister · Invitation expired")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_code).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_email_button).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_share_button).assertDoesNotExist()

    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_renew_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.renew) }
  }

  @Test
  fun renewing_disablesTheButtons() {
    setContent(CareCircleInviteUiState.Invite(julie, expired = true, isWorking = true))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_renew_button).assertIsNotEnabled()
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_invite_cancel_button)
        .performScrollTo()
        .assertIsNotEnabled()
  }

  @Test
  fun failedAction_saysSo() {
    setContent(CareCircleInviteUiState.Invite(julie, expired = true, actionFailed = true))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_action_error).assertIsDisplayed()
  }

  // ---- cancelling ----

  @Test
  fun cancelButton_asksToCancel() {
    val events = setContent(CareCircleInviteUiState.Invite(julie, expired = false))
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_invite_cancel_button)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.cancel) }
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_cancel_dialog).assertDoesNotExist()
  }

  @Test
  fun cancelDialog_namesThePerson_andConfirmsOrKeeps() {
    val events =
        setContent(CareCircleInviteUiState.Invite(julie, expired = false, confirmingCancel = true))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_cancel_dialog).assertIsDisplayed()
    composeTestRule.onNodeWithText("Cancel Julie's invitation?").assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.care_circle_invite_cancel_body))
        .assertIsDisplayed()

    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_cancel_dismiss).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_cancel_confirm).performClick()
    composeTestRule.runOnIdle {
      assertEquals(1, events.dismissCancel)
      assertEquals(1, events.confirmCancel)
    }
  }

  // ---- preview ----

  @Test
  fun preview_showsAPendingInvite() {
    composeTestRule.setContent { CareCircleInviteScreenPreview() }
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_name).assertTextEquals("Julie Martin")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_code).assertTextEquals("K7M2P-Q9XRT")
  }

  // ---- screen wired to its ViewModel ----

  private lateinit var invites: FakeCareCircleInviteRepository
  private lateinit var circle: FakeCareCircleRepository

  /** Shows the screen of [julie] with its ViewModel; returns the back and renewed calls. */
  private fun setScreen(
      // The ViewModel uses the real clock: created now, so the invite code still works
      invite: CareCircleInvite = julie.copy(createdAt = Instant.now())
  ): Pair<MutableList<Unit>, MutableList<String>> {
    circle = FakeCareCircleRepository()
    invites = FakeCareCircleInviteRepository(circle).apply { setInvites(listOf(invite)) }
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    val viewModel =
        CareCircleInviteViewModel(
            SavedStateHandle(mapOf(Screen.CARE_CIRCLE_INVITE_ARG to invite.inviteCode)),
            invites,
            circle,
            auth,
        )
    val backs = mutableListOf<Unit>()
    val renewed = mutableListOf<String>()
    composeTestRule.setContent {
      CareCircleInviteScreen(
          onBack = { backs += Unit },
          onRenewed = { renewed += it },
          viewModel = viewModel,
      )
    }
    return backs to renewed
  }

  private fun nextStartedActivity(): Intent? =
      shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

  @Test
  fun screen_sendByEmail_opensTheEmailAppWithTheInvitation() {
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_email_button).performClick()
    composeTestRule.runOnIdle {
      val intent = checkNotNull(nextStartedActivity())
      assertEquals(Intent.ACTION_SENDTO, intent.action)
      assertEquals("mailto", intent.data?.scheme)
      assertArrayEquals(
          arrayOf("julie@example.com"),
          intent.getStringArrayExtra(Intent.EXTRA_EMAIL),
      )
      assertEquals(
          string(R.string.care_circle_invite_email_subject),
          intent.getStringExtra(Intent.EXTRA_SUBJECT),
      )
      val text = checkNotNull(intent.getStringExtra(Intent.EXTRA_TEXT))
      assert("K7M2P-Q9XRT" in text) { text }
      assert(text.startsWith("Hi Julie")) { text }
      assert(text.endsWith("Anne")) { text }
    }
  }

  /** The text of the email opened for [invite]. */
  private fun sharedMessage(invite: CareCircleInvite): String {
    setScreen(invite)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_email_button).performClick()
    return composeTestRule.runOnIdle {
      checkNotNull(nextStartedActivity()?.getStringExtra(Intent.EXTRA_TEXT))
    }
  }

  @Test
  fun screen_sharedMessage_saysUntilWhenTheCodeWorks() {
    // Shared 5 days after it reached the server: only 2 days are left, not 7
    val invite = julie.copy(createdAt = Instant.now().minus(Duration.ofDays(5)))
    val text = sharedMessage(invite)
    assert(
        string(R.string.care_circle_invite_message_valid_until, day(invite.expiresAt!!)) in text
    ) {
      text
    }
    assert("7 days" !in text) { text }
  }

  @Test
  fun screen_sharedMessage_beforeSync_saysItsSevenDaysStartOnceSent() {
    val text = sharedMessage(julie.copy(createdAt = null, isSynced = false))
    assert(string(R.string.care_circle_invite_message_valid_once_sent) in text) { text }
  }

  @Test
  fun screen_share_opensTheShareSheetWithTheInvitation() {
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_share_button).performClick()
    composeTestRule.runOnIdle {
      val chooser = checkNotNull(nextStartedActivity())
      assertEquals(Intent.ACTION_CHOOSER, chooser.action)
      @Suppress("DEPRECATION")
      val shared = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
      assertEquals(Intent.ACTION_SEND, shared.action)
      assertEquals("text/plain", shared.type)
      assert("K7M2P-Q9XRT" in shared.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
    }
  }

  @Test
  fun screen_noAppToSendWith_saysToCopyInstead() {
    shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_email_button).performClick()
    composeTestRule.onNodeWithText(string(R.string.care_circle_invite_no_app)).assertIsDisplayed()
  }

  @Test
  fun screen_copy_putsTheInviteCodeInTheClipboard_labelledAndSensitive() {
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_copy_button).performClick()
    composeTestRule.waitForIdle()
    val clip = checkNotNull(copiedClip())
    assertEquals("K7M2P-Q9XRT", clip.getItemAt(0).text.toString())
    // Describes the clip instead of repeating the code, and hides it from the clipboard preview
    assertEquals("Invite code", clip.description.label.toString())
    assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    // Android 13+ confirms the copy itself
    composeTestRule.onNodeWithText(string(R.string.care_circle_invite_copied)).assertDoesNotExist()
  }

  @Config(sdk = [32])
  @Test
  fun screen_copy_confirmsItBelowAndroid13_andStillMarksItSensitive() {
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_copy_button).performClick()
    composeTestRule.onNodeWithText(string(R.string.care_circle_invite_copied)).assertIsDisplayed()
    // The constant only exists from Android 13; its key is the same string
    val extras = checkNotNull(copiedClip()).description.extras
    assertTrue(extras.getBoolean("android.content.extra.IS_SENSITIVE"))
  }

  /** What the screen last put in the clipboard. */
  private fun copiedClip(): ClipData? =
      context.getSystemService(ClipboardManager::class.java).primaryClip

  @Test
  fun screen_confirmingCancel_deletesTheInvite_andGoesBack() {
    val (backs, _) = setScreen()
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_invite_cancel_button)
        .performScrollTo()
        .performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_cancel_confirm).performClick()
    composeTestRule.runOnIdle {
      assertNull(invites.invite(julie.inviteCode))
      assertEquals(1, backs.size)
    }
  }

  @Test
  fun screen_renewingAnExpiredInvite_opensTheNewOne() {
    val (_, renewed) = setScreen(julie.copy(createdAt = Instant.parse("2020-01-01T00:00:00Z")))
    invites.nextInviteCode = { "NNNNN23456" }
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_renew_button).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("NNNNN23456"), renewed) }
  }

  @Test
  fun screen_showsWhenThePersonJoins() {
    // Not on the server's clock yet, so the fake's redeem doesn't find it expired
    setScreen(julie.copy(createdAt = null))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_code).assertIsDisplayed()
    runBlocking { invites.redeem(julie.inviteCode, "caregiver", "julie.d@gmail.com") }
    composeTestRule.onNodeWithText("Julie has joined your circle").assertIsDisplayed()
  }
}
