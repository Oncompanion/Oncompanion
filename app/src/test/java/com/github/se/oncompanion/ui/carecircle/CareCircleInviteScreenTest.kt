// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.content.Context
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
    var retry = 0
  }

  private fun setContent(uiState: CareCircleInviteUiState): Events {
    val events = Events()
    composeTestRule.setContent {
      CareCircleInviteContent(
          uiState = uiState,
          onBack = { events.back++ },
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
  fun expiredInvite_hidesTheInviteCode() {
    setContent(CareCircleInviteUiState.Invite(julie, expired = true))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_expired).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.care_circle_invite_expired_on, day(julie.expiresAt!!)))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_subtitle)
        .assertTextEquals("Sister · Invitation expired")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_code).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_invite_valid).assertDoesNotExist()
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

  /** Shows the screen of [invite] with its ViewModel and fakes. */
  private fun setScreen(invite: CareCircleInvite) {
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
    composeTestRule.setContent { CareCircleInviteScreen(onBack = {}, viewModel = viewModel) }
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
