// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.DeleteState
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SymptomDetailScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val fatigue =
      SymptomEntry(
          id = "s1",
          types = listOf(SymptomType.FATIGUE),
          intensity = 7,
          occurredAt = Instant.parse("2026-10-05T14:30:00Z"),
          notes = "After the walk",
      )

  private val loaded = SymptomDetailUiState(isLoading = false)

  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    var backCount = 0
    val opened = mutableListOf<String>()

    override fun goBack() {
      backCount++
    }

    override fun navigateTo(screen: String) {
      opened += screen
    }
  }

  private fun setContent(
      state: SymptomDetailUiState,
      onBack: () -> Unit = {},
      onRetry: () -> Unit = {},
      onDelete: () -> Unit = {},
      onConfirmDelete: () -> Unit = {},
      onCancelDelete: () -> Unit = {},
      onEdit: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      SymptomDetailContent(
          uiState = state,
          onBack = onBack,
          onRetry = onRetry,
          onDelete = onDelete,
          onConfirmDelete = onConfirmDelete,
          onCancelDelete = onCancelDelete,
          zone = ZoneOffset.UTC,
          onEdit = onEdit,
      )
    }
  }

  private fun assertTextInside(text: String, parentTag: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        .assertExists()
  }

  private fun signedInAsAlice() =
      FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  // ----- States -----

  @Test
  fun loading_showsOnlyTheProgress() {
    setContent(SymptomDetailUiState())

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_content).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_error).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_not_found).assertDoesNotExist()
    assertTextInside(context.getString(R.string.symptom_detail_title), C.Tag.symptom_detail_screen)
  }

  @Test
  fun error_showsRetry_whichCallsBack() {
    var retries = 0
    setContent(loaded.copy(hasError = true), onRetry = { retries++ })

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_error).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.symptom_detail_error_title),
        C.Tag.symptom_detail_error,
    )
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_retry).performClick()
    assertEquals(1, retries)
  }

  @Test
  fun notFound_explainsIt_withoutRetry() {
    setContent(loaded)

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_not_found).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.symptom_detail_not_found_title),
        C.Tag.symptom_detail_not_found,
    )
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_retry).assertDoesNotExist()
  }

  @Test
  fun entry_showsTitleDateIntensityAndNotes() {
    setContent(loaded.copy(entry = fatigue))

    assertTextInside("Fatigue", C.Tag.symptom_detail_screen)
    assertTextInside("Monday 5 October 2026, 14:30", C.Tag.symptom_detail_when)
    assertTextInside("7/10", C.Tag.symptom_detail_intensity)
    composeTestRule
        .onNode(
            hasContentDescription("Intensity 7 out of 10") and
                hasAnyAncestor(hasTestTag(C.Tag.symptom_detail_intensity))
        )
        .assertExists()
    assertTextInside("After the walk", C.Tag.symptom_detail_notes)
  }

  @Test
  fun severalSymptoms_showAllTheirLabelsInTheTitle() {
    setContent(
        loaded.copy(entry = fatigue.copy(types = listOf(SymptomType.FATIGUE, SymptomType.HEADACHE)))
    )

    assertTextInside("Fatigue, Headache", C.Tag.symptom_detail_screen)
  }

  @Test
  fun otherEntry_showsTheNotesSayingWhichSymptom() {
    setContent(
        loaded.copy(entry = fatigue.copy(types = listOf(SymptomType.OTHER), notes = "Hiccups"))
    )

    assertTextInside("Other", C.Tag.symptom_detail_screen)
    assertTextInside("Hiccups", C.Tag.symptom_detail_notes)
  }

  @Test
  fun entryWithoutIntensityOrNotes_showsOnlyTheDate() {
    setContent(loaded.copy(entry = fatigue.copy(intensity = null, notes = null)))

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_content).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_when).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_intensity).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_notes).assertDoesNotExist()
  }

  @Test
  fun blankNotes_areNotShown() {
    setContent(loaded.copy(entry = fatigue.copy(notes = "  ")))

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_notes).assertDoesNotExist()
  }

  @Test
  fun backButton_callsBack() {
    var backs = 0
    setContent(loaded.copy(entry = fatigue), onBack = { backs++ })

    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()

    assertEquals(1, backs)
  }

  // ----- Delete -----

  @Test
  fun entry_hasTheDeleteButton_whichAsksToConfirm() {
    var deletes = 0
    setContent(loaded.copy(entry = fatigue), onDelete = { deletes++ })

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).performClick()

    assertEquals(1, deletes)
  }

  @Test
  fun notFound_hasNoDeleteButton() {
    setContent(loaded)

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).assertDoesNotExist()
  }

  @Test
  fun entry_hasTheEditButton_aboveDelete_whichCallsBack() {
    var edits = 0
    setContent(loaded.copy(entry = fatigue), onEdit = { edits++ })

    val edit = composeTestRule.onNodeWithTag(C.Tag.symptom_detail_edit)
    edit.assertIsDisplayed().assert(hasText(context.getString(R.string.symptom_detail_edit)))
    val editBottom = edit.getUnclippedBoundsInRoot().bottom
    val deleteTop =
        composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).getUnclippedBoundsInRoot().top
    assertTrue("Edit is above Delete", editBottom <= deleteTop)
    edit.performClick()

    assertEquals(1, edits)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun shortWindow_actionsComeAfterLongNotes_soTheEntryKeepsTheWholeHeight() {
    val longNotes = (1..40).joinToString("\n") { "Line $it" }
    setContent(loaded.copy(entry = fatigue.copy(notes = longNotes)))

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_edit).assertIsNotDisplayed()

    composeTestRule
        .onNodeWithTag(C.Tag.symptom_detail_content)
        .performScrollToNode(hasTestTag(C.Tag.symptom_detail_delete))

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).assertIsDisplayed()
  }

  @Test
  fun noEditButton_whileLoading_afterAnError_whenNotFound_orWhileDeleting() {
    var state by mutableStateOf(SymptomDetailUiState())
    composeTestRule.setContent {
      SymptomDetailContent(
          uiState = state,
          onBack = {},
          onRetry = {},
          onDelete = {},
          onConfirmDelete = {},
          onCancelDelete = {},
      )
    }

    for (hidden in
        listOf(
            SymptomDetailUiState(),
            loaded.copy(hasError = true),
            loaded,
            loaded.copy(
                entry = fatigue,
                delete = DeleteState(isConfirming = true, isDeleting = true),
            ),
        )) {
      state = hidden
      composeTestRule.onNodeWithTag(C.Tag.symptom_detail_edit).assertDoesNotExist()
    }
  }

  @Test
  fun confirmation_explainsIt_andConfirmOrCancelCallBack() {
    var confirms = 0
    var cancels = 0
    setContent(
        loaded.copy(entry = fatigue, delete = DeleteState(isConfirming = true)),
        onConfirmDelete = { confirms++ },
        onCancelDelete = { cancels++ },
    )

    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_dialog).assertIsDisplayed()
    assertTextInside(context.getString(R.string.symptom_delete_title), C.Tag.symptom_delete_dialog)
    assertTextInside(
        context.getString(R.string.symptom_delete_message),
        C.Tag.symptom_delete_dialog,
    )
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_error).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_confirm).assertIsEnabled().performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_cancel).assertIsEnabled().performClick()

    assertEquals(1, confirms)
    assertEquals(1, cancels)
  }

  @Test
  fun confirmingWithoutAnEntry_showsNoDialog() {
    setContent(loaded.copy(delete = DeleteState(isConfirming = true)))

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_not_found).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_dialog).assertDoesNotExist()
  }

  @Test
  fun backInTheDialog_cancelsTheDeletion() {
    var cancels = 0
    setContent(
        loaded.copy(entry = fatigue, delete = DeleteState(isConfirming = true)),
        onCancelDelete = { cancels++ },
    )
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_dialog).assertIsDisplayed()

    Espresso.pressBack()

    composeTestRule.runOnIdle { assertEquals(1, cancels) }
  }

  @Test
  fun backWhileDeleting_doesNotCancel() {
    var cancels = 0
    setContent(
        loaded.copy(entry = fatigue, delete = DeleteState(isConfirming = true, isDeleting = true)),
        onCancelDelete = { cancels++ },
    )
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_dialog).assertIsDisplayed()

    Espresso.pressBack()

    composeTestRule.runOnIdle { assertEquals(0, cancels) }
  }

  @Test
  fun whileDeleting_theDialogButtonsAreDisabled() {
    setContent(
        loaded.copy(entry = fatigue, delete = DeleteState(isConfirming = true, isDeleting = true))
    )

    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_confirm).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_cancel).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).assertDoesNotExist()
  }

  @Test
  fun deleteError_isShownInTheDialog() {
    setContent(
        loaded.copy(entry = fatigue, delete = DeleteState(isConfirming = true, hasError = true))
    )

    composeTestRule
        .onNode(
            hasTestTag(C.Tag.symptom_delete_error) and
                hasText(context.getString(R.string.symptom_delete_error))
        )
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_confirm).assertIsEnabled()
  }

  // ----- With the ViewModel -----

  @Test
  fun screen_showsTheEntry_andGoesBack() {
    val repository = FakeSymptomRepository().apply { seed("alice", fatigue) }
    val viewModel = SymptomDetailViewModel("s1", signedInAsAlice(), repository)
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      SymptomDetailScreen(navigationActions = navigationActions, viewModel = viewModel)
    }

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_content).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()

    composeTestRule.runOnIdle { assertEquals(1, navigationActions.backCount) }
  }

  @Test
  fun screen_edit_opensTheEditScreenOfThatSymptom() {
    val repository = FakeSymptomRepository().apply { seed("alice", fatigue) }
    val viewModel = SymptomDetailViewModel("s1", signedInAsAlice(), repository)
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      SymptomDetailScreen(navigationActions = navigationActions, viewModel = viewModel)
    }

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_edit).performClick()

    composeTestRule.runOnIdle {
      assertEquals(listOf(Screen.symptomEdit("s1")), navigationActions.opened)
    }
  }

  @Test
  fun screen_retryAfterError_showsTheEntry() {
    val repository =
        FakeSymptomRepository().apply {
          seed("alice", fatigue)
          observeError = IllegalStateException("no cache")
        }
    val viewModel = SymptomDetailViewModel("s1", signedInAsAlice(), repository)
    composeTestRule.setContent {
      SymptomDetailScreen(
          navigationActions = NavigationActions(TestNavHostController(LocalContext.current)),
          viewModel = viewModel,
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_error).assertIsDisplayed()

    repository.observeError = null
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_retry).performClick()

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_content).assertIsDisplayed()
  }

  @Test
  fun screen_confirmedDelete_deletesTheEntry_andGoesBackToTheJournal() {
    val repository = FakeSymptomRepository().apply { seed("alice", fatigue) }
    val viewModel = SymptomDetailViewModel("s1", signedInAsAlice(), repository)
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      SymptomDetailScreen(navigationActions = navigationActions, viewModel = viewModel)
    }

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_confirm).performClick()

    composeTestRule.runOnIdle {
      assertTrue(repository.symptoms("alice").isEmpty())
      assertEquals(1, navigationActions.backCount)
    }
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_not_found).assertDoesNotExist()
  }

  @Test
  fun screen_cancelledDelete_keepsTheEntry_andStays() {
    val repository = FakeSymptomRepository().apply { seed("alice", fatigue) }
    val viewModel = SymptomDetailViewModel("s1", signedInAsAlice(), repository)
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      SymptomDetailScreen(navigationActions = navigationActions, viewModel = viewModel)
    }

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_cancel).performClick()

    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_content).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(listOf(fatigue), repository.symptoms("alice"))
      assertEquals(0, navigationActions.backCount)
    }
  }
}
