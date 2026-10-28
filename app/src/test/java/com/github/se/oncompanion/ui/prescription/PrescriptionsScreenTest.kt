// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrescriptionsScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** Wednesday 30 September 2026. */
  private val today = LocalDate.of(2026, 9, 30)

  /** A prescription [id] of [prescribedOn] with one medication per name. */
  private fun prescription(
      id: String,
      prescribedOn: String,
      vararg names: String,
      prescribedBy: String? = "Dr. Martin",
  ) =
      LocalDate.parse(prescribedOn).let { date ->
        Prescription(
            id = id,
            prescribedBy = prescribedBy,
            prescribedOn = date,
            medications =
                names.mapIndexed { index, name ->
                  Medication(
                      id = "$id-$index",
                      prescriptionId = id,
                      name = name,
                      dosage = "1 tablet",
                      startDate = date,
                  )
                },
        )
      }

  private val chemo = prescription("chemo", "2026-09-29", "Ondansetron", "Dexamethasone")
  private val fever = prescription("fever", "2026-09-01", "Paracetamol 1 g", prescribedBy = null)
  private val nausea =
      prescription("nausea", "2026-08-20", "Metoclopramide 10 mg", prescribedBy = "Dr. Dubois")

  private val loading = PrescriptionsUiState(today = today)
  private val loaded = loading.copy(isLoading = false)

  /** Two prescriptions of this month, one of the month before. */
  private val filled = loaded.copy(prescriptions = listOf(chemo, fever, nausea))

  private fun setContent(
      state: PrescriptionsUiState,
      onBack: () -> Unit = {},
      onAdd: () -> Unit = {},
      onScan: () -> Unit = {},
      onRetry: () -> Unit = {},
      onOpen: (String) -> Unit = {},
  ) {
    composeTestRule.setContent {
      PrescriptionsContent(
          uiState = state,
          onBack = onBack,
          onAdd = onAdd,
          onScan = onScan,
          onRetry = onRetry,
          onOpen = onOpen,
      )
    }
  }

  private fun boundsOf(tag: String) =
      composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

  private fun assertTextInside(text: String, parentTag: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        .assertExists()
  }

  private fun yOf(tag: String) =
      composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

  private fun signedIn(uid: String) =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  // ----- States -----

  @Test
  fun everyState_showsTheScreenItsTitleAndBothButtons() {
    var adds = 0
    var scans = 0
    val states = listOf(loading, loaded.copy(hasError = true), loaded, filled)
    var state by mutableStateOf(states.first())
    composeTestRule.setContent {
      PrescriptionsContent(
          uiState = state,
          onBack = {},
          onAdd = { adds++ },
          onScan = { scans++ },
          onRetry = {},
          onOpen = {},
      )
    }

    states.forEachIndexed { index, next ->
      composeTestRule.runOnIdle { state = next }

      composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertIsDisplayed()
      composeTestRule
          .onNodeWithText(context.getString(R.string.prescriptions_list_title))
          .assertIsDisplayed()
      // Saving works whatever the list could load
      composeTestRule
          .onNodeWithTag(C.Tag.prescriptions_enter_manually_button)
          .assertIsDisplayed()
          // Material hides the button's text from screen readers: its icon carries the label
          .assertContentDescriptionEquals(context.getString(R.string.prescriptions_enter_manually))
          .performClick()
      composeTestRule.runOnIdle { assertEquals(index + 1 to index, adds to scans) }
      composeTestRule
          .onNodeWithTag(C.Tag.prescriptions_scan_button)
          .assertIsDisplayed()
          .assertContentDescriptionEquals(context.getString(R.string.prescriptions_scan))
          .performClick()
      composeTestRule.runOnIdle { assertEquals(index + 1 to index + 1, adds to scans) }
    }
  }

  @Test
  fun buttons_areSideBySide_enterManuallyFirst_sharingTheWidth() {
    setContent(filled)

    val enter = boundsOf(C.Tag.prescriptions_enter_manually_button)
    val scan = boundsOf(C.Tag.prescriptions_scan_button)
    val screen = boundsOf(C.Tag.prescriptions_screen)

    assertEquals(enter.top, scan.top)
    assertEquals(enter.height, scan.height)
    assertEquals(enter.width, scan.width, 1f)
    assertTrue("Enter manually is on the left of Scan", enter.right < scan.left)
    // Same margin on both sides; the two buttons take the rest of the width but their gap
    assertEquals(enter.left - screen.left, screen.right - scan.right, 1f)
    assertTrue(enter.width + scan.width > 0.8f * screen.width)
  }

  @Test
  fun buttons_showTheirLabels() {
    setContent(loaded)

    assertTextInside("Enter manually", C.Tag.prescriptions_enter_manually_button)
    assertTextInside("Scan", C.Tag.prescriptions_scan_button)
  }

  @Test
  fun lastPrescription_isNotHiddenBehindTheButtons() {
    val many =
        (1..30).map { prescription("p$it", today.minusDays(it.toLong()).toString(), "Med $it") }
    setContent(loaded.copy(prescriptions = many))

    composeTestRule
        .onNodeWithTag(C.Tag.prescriptions_list)
        .performScrollToNode(hasTestTag(C.Tag.prescriptionsItem("p30")))
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_list).performTouchInput { swipeUp() }

    val last = boundsOf(C.Tag.prescriptionsItem("p30"))
    assertTrue(
        "The last prescription ends above the buttons",
        last.bottom <= boundsOf(C.Tag.prescriptions_scan_button).top,
    )
  }

  @Test
  fun emptyAndErrorBlocks_stayAboveTheButtons() {
    var state by mutableStateOf(loaded)
    composeTestRule.setContent {
      PrescriptionsContent(
          uiState = state,
          onBack = {},
          onAdd = {},
          onScan = {},
          onRetry = {},
          onOpen = {},
      )
    }

    listOf(loaded, loaded.copy(hasError = true)).forEach { next ->
      composeTestRule.runOnIdle { state = next }
      val block = if (next.hasError) C.Tag.prescriptions_error else C.Tag.prescriptions_empty
      // The lowest text or button of the block
      val lowest =
          composeTestRule
              .onAllNodes(hasAnyAncestor(hasTestTag(block)), useUnmergedTree = true)
              .fetchSemanticsNodes()
              .maxOf { it.boundsInRoot.bottom }
      assertTrue(lowest <= boundsOf(C.Tag.prescriptions_scan_button).top)
    }
  }

  @Test
  fun title_isMyPrescriptions() {
    setContent(loaded)

    composeTestRule.onNodeWithText("My prescriptions").assertIsDisplayed()
  }

  @Test
  fun loading_showsOnlyTheProgress() {
    setContent(loading)

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_list).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_empty).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_error).assertDoesNotExist()
  }

  @Test
  fun empty_showsTheEmptyState() {
    setContent(loaded)

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_empty).assertIsDisplayed()
    assertTextInside("No prescriptions yet", C.Tag.prescriptions_empty)
    assertTextInside(
        context.getString(R.string.prescriptions_empty_hint),
        C.Tag.prescriptions_empty,
    )
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_retry_button).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_list).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_error).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_loading).assertDoesNotExist()
  }

  @Test
  fun empty_saysBothWaysToAddAPrescription() {
    setContent(loaded)

    assertTextInside(
        "Scan a prescription or enter it manually to keep it here.",
        C.Tag.prescriptions_empty,
    )
  }

  @Test
  fun error_showsRetry_whichCallsBack() {
    var retries = 0
    setContent(loaded.copy(hasError = true), onRetry = { retries++ })

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_error).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.prescriptions_error_title),
        C.Tag.prescriptions_error,
    )
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_list).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_empty).assertDoesNotExist()

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_retry_button).performClick()
    assertEquals(1, retries)
  }

  @Test
  fun error_neverBlamesTheConnection() {
    // Offline, Firestore serves its cache: an error is not a connection problem
    setContent(loaded.copy(hasError = true))

    composeTestRule
        .onNode(
            hasText("connection", substring = true, ignoreCase = true) or
                hasText("internet", substring = true, ignoreCase = true) or
                hasText("offline", substring = true, ignoreCase = true),
            useUnmergedTree = true,
        )
        .assertDoesNotExist()
  }

  // ----- The list -----

  @Test
  fun filled_showsEachPrescription_withItsMedicationNamesPrescriberAndDate() {
    setContent(filled)

    val chemoTag = C.Tag.prescriptionsItem("chemo")
    composeTestRule.onNodeWithTag(chemoTag).assertIsDisplayed()
    // Every medication of the prescription is named, in the order it was saved
    assertTextInside("Ondansetron, Dexamethasone", chemoTag)
    assertTextInside("Dr. Martin · 29 Sep 2026", chemoTag)

    val nauseaTag = C.Tag.prescriptionsItem("nausea")
    assertTextInside("Metoclopramide 10 mg", nauseaTag)
    assertTextInside("Dr. Dubois · 20 Aug 2026", nauseaTag)

    composeTestRule.onAllNodesWithTag(chemoTag).assertCountEquals(1)
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_empty).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_error).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_loading).assertDoesNotExist()
  }

  @Test
  fun withoutPrescriber_theDateIsShownAlone() {
    setContent(filled)

    val feverTag = C.Tag.prescriptionsItem("fever")
    assertTextInside("Paracetamol 1 g", feverTag)
    assertTextInside("1 Sep 2026", feverTag)
    composeTestRule
        .onNode(
            hasText("·", substring = true) and hasAnyAncestor(hasTestTag(feverTag)),
            useUnmergedTree = true,
        )
        .assertDoesNotExist()
  }

  @Test
  fun blankPrescriber_isTreatedAsMissing() {
    setContent(loaded.copy(prescriptions = listOf(fever.copy(prescribedBy = "  "))))

    assertTextInside("1 Sep 2026", C.Tag.prescriptionsItem("fever"))
  }

  @Test
  fun filled_groupsThisMonthThenEarlier_inTheGivenOrder() {
    setContent(filled)

    composeTestRule
        .onNodeWithTag(C.Tag.prescriptions_this_month_header)
        .assertTextEquals("This month")
        .assert(isHeading())
    composeTestRule
        .onNodeWithTag(C.Tag.prescriptions_earlier_header)
        .assertTextEquals("Earlier")
        .assert(isHeading())

    val positions =
        listOf(
                C.Tag.prescriptions_this_month_header,
                C.Tag.prescriptionsItem("chemo"),
                C.Tag.prescriptionsItem("fever"),
                C.Tag.prescriptions_earlier_header,
                C.Tag.prescriptionsItem("nausea"),
            )
            .map(::yOf)
    assertEquals(positions.sorted(), positions)
    assertEquals(positions.size, positions.distinct().size)
  }

  @Test
  fun onlyThisMonth_showsNoEarlierHeader() {
    setContent(loaded.copy(prescriptions = listOf(chemo, fever)))

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_this_month_header).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_earlier_header).assertDoesNotExist()
  }

  @Test
  fun onlyEarlier_showsNoThisMonthHeader() {
    setContent(loaded.copy(prescriptions = listOf(nausea)))

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_earlier_header).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_this_month_header).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem("nausea")).assertIsDisplayed()
  }

  @Test
  fun headline_takesAtMostTwoLines_thenAnEllipsis() {
    val names = (1..20).map { "Medication with a long name $it" }
    val long = prescription("long", "2026-09-29", *names.toTypedArray())
    setContent(loaded.copy(prescriptions = listOf(long)))

    // The whole text stays in the semantics (screen readers); only what is drawn is cut
    val layout = mutableListOf<TextLayoutResult>()
    composeTestRule
        .onNode(
            hasText(names.joinToString(", ")) and
                hasAnyAncestor(hasTestTag(C.Tag.prescriptionsItem("long"))),
            useUnmergedTree = true,
        )
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsActions.GetTextLayoutResult)
        ?.action
        ?.invoke(layout)

    // Robolectric doesn't measure text like a device, so check how the text is laid out
    assertEquals(2, layout.single().layoutInput.maxLines)
    assertEquals(TextOverflow.Ellipsis, layout.single().layoutInput.overflow)
  }

  @Test
  fun tappingAnItem_opensThatPrescription() {
    val opened = mutableListOf<String>()
    setContent(filled, onOpen = { opened += it })

    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem("fever")).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem("chemo")).performClick()

    assertEquals(listOf("fever", "chemo"), opened)
  }

  @Test
  fun longList_canBeScrolledToItsLastPrescription() {
    val many =
        (1..30).map { prescription("p$it", today.minusDays(it.toLong()).toString(), "Med $it") }
    setContent(loaded.copy(prescriptions = many))

    composeTestRule
        .onNodeWithTag(C.Tag.prescriptions_list)
        .performScrollToNode(hasTestTag(C.Tag.prescriptionsItem("p30")))

    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem("p30")).assertIsDisplayed()
    assertTextInside("Med 30", C.Tag.prescriptionsItem("p30"))
    // The button stays above the list
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_enter_manually_button).assertIsDisplayed()
  }

  @Test
  fun backButton_callsBack() {
    var backs = 0
    setContent(loaded, onBack = { backs++ })

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_back_button).performClick()

    assertEquals(1, backs)
  }

  // ----- With the ViewModel -----

  @Test
  fun screen_showsTheSignedInUsersPrescriptions_andPassesItsEvents() {
    val repository =
        FakeMedicationRepository().apply {
          seed("alice", chemo)
          seed("alice", nausea)
          seed("bob", fever)
        }
    val clock = Clock.fixed(today.atTime(9, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    val viewModel = PrescriptionsViewModel(signedIn("alice"), repository, clock)
    var backs = 0
    var adds = 0
    var scans = 0
    val opened = mutableListOf<String>()
    composeTestRule.setContent {
      PrescriptionsScreen(
          viewModel = viewModel,
          onBack = { backs++ },
          onAdd = { adds++ },
          onScan = { scans++ },
          onOpen = { opened += it },
      )
    }

    // Grouped with the ViewModel's date
    val positions =
        listOf(
                C.Tag.prescriptions_this_month_header,
                C.Tag.prescriptionsItem("chemo"),
                C.Tag.prescriptions_earlier_header,
                C.Tag.prescriptionsItem("nausea"),
            )
            .map(::yOf)
    assertEquals(positions.sorted(), positions)
    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem("fever")).assertDoesNotExist()

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_enter_manually_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_scan_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_back_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem("nausea")).performClick()

    composeTestRule.runOnIdle {
      assertEquals(1, adds)
      assertEquals(1, scans)
      assertEquals(1, backs)
      assertEquals(listOf("nausea"), opened)
    }
  }

  @Test
  fun screen_retryAfterError_showsThePrescriptions() {
    val repository =
        FakeMedicationRepository().apply {
          seed("alice", chemo)
          readError = IllegalStateException("no cache")
        }
    val viewModel = PrescriptionsViewModel(signedIn("alice"), repository)
    composeTestRule.setContent {
      PrescriptionsScreen(viewModel = viewModel, onBack = {}, onAdd = {}, onScan = {}, onOpen = {})
    }
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_error).assertIsDisplayed()

    repository.readError = null
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_retry_button).performClick()

    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem("chemo")).assertIsDisplayed()
  }
}
