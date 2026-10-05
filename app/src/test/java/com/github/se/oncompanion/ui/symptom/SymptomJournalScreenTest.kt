package com.github.se.oncompanion.ui.symptom

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.NavigationActions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SymptomJournalScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val fatigue =
      SymptomEntry(
          id = "fatigue",
          type = SymptomType.FATIGUE,
          intensity = 7,
          occurredAt = Instant.parse("2026-10-05T14:30:00Z"),
      )
  private val hiccups =
      SymptomEntry(
          id = "hiccups",
          type = SymptomType.OTHER,
          otherLabel = "Hiccups",
          intensity = 2,
          occurredAt = Instant.parse("2026-10-03T08:05:00Z"),
      )

  /** Monday 5 October 2026. */
  private val today = LocalDate.of(2026, 10, 5)

  private val loaded = SymptomJournalUiState(today = today, isLoading = false)

  private fun day(date: LocalDate, vararg entries: Pair<SymptomEntry, String>) =
      JournalDay(date, entries.map { (entry, time) -> JournalEntry(entry, LocalTime.parse(time)) })

  /** Fatigue today at 14:30, hiccups on Saturday at 08:05. */
  private val filled =
      loaded.copy(
          days =
              listOf(
                  day(today, fatigue to "14:30"),
                  day(LocalDate.of(2026, 10, 3), hiccups to "08:05"),
              )
      )

  /** Records Back instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    var backCount = 0

    override fun goBack() {
      backCount++
    }
  }

  private fun setContent(
      state: SymptomJournalUiState,
      onBack: () -> Unit = {},
      onRetry: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      SymptomJournalContent(
          uiState = state,
          onBack = onBack,
          onRetry = onRetry,
      )
    }
  }

  private fun assertTextInside(text: String, parentTag: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        .assertExists()
  }

  // ----- States -----

  @Test
  fun loading_showsOnlyTheProgress() {
    setContent(SymptomJournalUiState(today = today))

    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_list).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_empty).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_error).assertDoesNotExist()
  }

  @Test
  fun empty_showsTheEmptyState() {
    setContent(loaded)

    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_empty).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.symptom_journal_empty_title),
        C.Tag.symptom_journal_empty,
    )
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_retry).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_list).assertDoesNotExist()
  }

  @Test
  fun error_showsRetry_whichCallsBack() {
    var retries = 0
    setContent(loaded.copy(hasError = true), onRetry = { retries++ })

    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_error).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.symptom_journal_error_title),
        C.Tag.symptom_journal_error,
    )
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_retry).performClick()
    assertEquals(1, retries)
  }

  @Test
  fun filled_showsEachEntry_withTypeSeverityAndTime() {
    setContent(filled)

    val fatigueTag = C.Tag.symptomJournalItem("fatigue")
    composeTestRule.onNodeWithTag(fatigueTag).assertIsDisplayed()
    assertTextInside("Fatigue", fatigueTag)
    assertTextInside("14:30", fatigueTag)
    assertTextInside("Severe", fatigueTag)
    composeTestRule
        .onNode(hasText("7/10") and hasAnyAncestor(hasTestTag(fatigueTag)), useUnmergedTree = true)
        .assertDoesNotExist()

    val hiccupsTag = C.Tag.symptomJournalItem("hiccups")
    assertTextInside("Hiccups", hiccupsTag)
    assertTextInside("08:05", hiccupsTag)
    assertTextInside("Mild", hiccupsTag)

    composeTestRule.onAllNodesWithTag(fatigueTag).assertCountEquals(1)
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_empty).assertDoesNotExist()
  }

  @Test
  fun days_areTodayYesterdayOrTheirDate_inTheGivenOrder() {
    val entries = (1..5).map { fatigue.copy(id = "s$it") }
    setContent(
        loaded.copy(
            days =
                listOf(
                    day(today, entries[0] to "14:30"),
                    day(today.minusDays(1), entries[1] to "19:10", entries[2] to "13:45"),
                    day(LocalDate.of(2026, 9, 28), entries[3] to "21:30"),
                    day(LocalDate.of(2025, 12, 31), entries[4] to "02:15"),
                )
        )
    )

    val headers =
        listOf(
            "2026-10-05" to "Today",
            "2026-10-04" to "Yesterday",
            "2026-09-28" to "Mon 28 Sep",
            "2025-12-31" to "Wed 31 Dec 2025",
        )
    headers.forEach { (date, text) ->
      composeTestRule
          .onNodeWithTag(C.Tag.symptomJournalDay(date))
          .performScrollTo()
          .assertTextEquals(text)
          .assert(isHeading())
    }
    // Headers come before their entries, newest day first (all fit on the screen: no scrolling)
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_list).performScrollToIndex(0)
    val positions =
        listOf(
                C.Tag.symptomJournalDay("2026-10-05"),
                C.Tag.symptomJournalItem("s1"),
                C.Tag.symptomJournalDay("2026-10-04"),
                C.Tag.symptomJournalItem("s2"),
                C.Tag.symptomJournalItem("s3"),
            )
            .map { composeTestRule.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
    assertEquals(positions.sorted(), positions)
  }

  @Test
  fun backButton_callsBack() {
    var backs = 0
    setContent(loaded, onBack = { backs++ })

    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()

    assertEquals(1, backs)
  }

  // ----- With the ViewModel -----

  @Test
  fun screen_showsTheJournal_andGoesBack() {
    val repository = FakeSymptomRepository().apply { seed("alice", fatigue) }
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val viewModel = SymptomJournalViewModel(auth, repository)
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      SymptomJournalScreen(navigationActions = navigationActions, viewModel = viewModel)
    }

    composeTestRule.onNodeWithTag(C.Tag.symptomJournalItem("fatigue")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()

    composeTestRule.runOnIdle { assertEquals(1, navigationActions.backCount) }
  }

  @Test
  fun screen_retryAfterError_showsTheJournal() {
    val repository =
        FakeSymptomRepository().apply {
          seed("alice", fatigue)
          observeError = IllegalStateException("no cache")
        }
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val viewModel = SymptomJournalViewModel(auth, repository)
    composeTestRule.setContent {
      SymptomJournalScreen(
          navigationActions = NavigationActions(TestNavHostController(LocalContext.current)),
          viewModel = viewModel,
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_error).assertIsDisplayed()

    repository.observeError = null
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_retry).performClick()

    composeTestRule.onNodeWithTag(C.Tag.symptomJournalItem("fatigue")).assertIsDisplayed()
  }
}
