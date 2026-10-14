// Co-authored-by: Codex
// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.hospital.*
import com.github.se.oncompanion.resources.C
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HospitalFieldTest {
  @get:Rule val compose = createComposeRule()
  private val fake = FakeHospitalRepository()
  private val hospitals =
      listOf(
          Hospital("way/1", "Geneva Hospital", "Rue Alpha, Geneva", 46.2, 6.1),
          Hospital("way/2", "Other Hospital", "Rue Beta, Geneva", 46.3, 6.2),
      )
  private var value by mutableStateOf("")
  private var enabled by mutableStateOf(true)
  private var searchEnabled by mutableStateOf(true)
  private var visible by mutableStateOf(true)
  private val changes = mutableListOf<String>()
  private val selections = mutableListOf<Hospital?>()
  private var selected: Hospital? = null

  private fun field(
      repository: HospitalRepository = fake,
      initial: String = "",
  ): HospitalFieldViewModel {
    value = initial
    val vm = HospitalFieldViewModel(repository)
    compose.setContent {
      if (visible)
          HospitalField(
              value,
              {
                value = it
                changes.add(it)
              },
              vm,
              onHospitalSelected = {
                selected = it
                selections.add(it)
              },
              enabled = enabled,
              searchEnabled = searchEnabled,
          )
    }
    return vm
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun search() {
    node(C.Tag.hospital_search).performClick()
    compose.waitForIdle()
  }

  @Test
  fun typingAnyLocationPreservesWhitespaceAndNeverSearchesAutomatically() {
    field()
    node(C.Tag.hospital_search).assertIsNotEnabled()
    node(C.Tag.hospital_field).performTextInput("  Home, room 2  ")
    node(C.Tag.hospital_field).assertTextContains("  Home, room 2  ")
    compose.runOnIdle {
      assertEquals("  Home, room 2  ", value)
      assertTrue(fake.queries.isEmpty())
    }
    node(C.Tag.hospital_results).assertDoesNotExist()
    node(C.Tag.hospital_attribution).assertDoesNotExist()
  }

  @Test
  fun explicitSearchShowsNamesAndAddressesWithoutReplacingText() {
    fake.result = HospitalSearchResult.Success(hospitals)
    field(initial = "Geneva")
    search()
    compose.onAllNodesWithTag(C.Tag.hospital_result).assertCountEquals(2)
    compose.onNodeWithText("Rue Alpha, Geneva").assertIsDisplayed()
    node(C.Tag.hospital_attribution).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(listOf("Geneva"), fake.queries)
      assertEquals("Geneva", value)
      assertTrue(changes.isEmpty())
    }
  }

  @Test
  fun selectingSecondHospitalFillsTextAndClearsResults() {
    fake.result = HospitalSearchResult.Success(hospitals)
    field(initial = "Geneva")
    search()
    compose.onAllNodesWithTag(C.Tag.hospital_result)[1].performClick()
    node(C.Tag.hospital_field).assertTextContains("Other Hospital")
    node(C.Tag.hospital_results).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(listOf("Other Hospital"), changes)
      assertEquals(listOf(hospitals[1]), selections)
      assertSame(hospitals[1], selected)
      assertEquals(1, fake.queries.size)
    }
  }

  @Test
  fun manualEditAfterSelectionInvalidatesHospitalIncludingEmptyInput() {
    fake.result = HospitalSearchResult.Success(hospitals)
    field(initial = "Geneva")
    search()
    compose.onAllNodesWithTag(C.Tag.hospital_result)[0].performClick()
    compose.runOnIdle { assertSame(hospitals[0], selected) }
    node(C.Tag.hospital_field).performTextReplacement("Home")
    compose.runOnIdle {
      assertNull(selected)
      assertEquals(listOf(hospitals[0], null), selections)
      assertEquals("Home", value)
    }
    node(C.Tag.hospital_field).performTextClearance()
    compose.runOnIdle {
      assertNull(selected)
      assertEquals("", value)
      assertEquals(listOf("Geneva"), fake.queries)
    }
  }

  @Test
  fun disabledFieldRejectsLateSearchResultsEvenAfterReenabled() = runTest {
    val pending = CompletableDeferred<HospitalSearchResult>()
    fake.pendingResult = pending
    fake.ignoreCancellation = true
    val vm = field(initial = "Geneva")
    search()
    node(C.Tag.hospital_loading).assertIsDisplayed()
    compose.runOnIdle { enabled = false }
    compose.waitForIdle()
    pending.complete(HospitalSearchResult.Success(hospitals))
    advanceUntilIdle()
    node(C.Tag.hospital_field).assertIsNotEnabled().assertTextContains("Geneva")
    assertNoLateFeedback(vm)
    compose.runOnIdle { enabled = true }
    assertNoLateFeedback(vm)
  }

  @Test
  fun removedFieldRejectsLateSearchResultsAndCallbacks() = runTest {
    val pending = CompletableDeferred<HospitalSearchResult>()
    fake.pendingResult = pending
    fake.ignoreCancellation = true
    val vm = field(initial = "Geneva")
    search()
    node(C.Tag.hospital_loading).assertIsDisplayed()
    compose.runOnIdle { visible = false }
    compose.waitForIdle()
    pending.complete(HospitalSearchResult.Success(hospitals))
    advanceUntilIdle()
    node(C.Tag.hospital_field).assertDoesNotExist()
    assertNoLateFeedback(vm)
    compose.runOnIdle { visible = true }
    node(C.Tag.hospital_field).assertTextContains("Geneva")
    assertNoLateFeedback(vm)
  }

  @Test
  fun manualEditRejectsLateResultsWithoutOverwritingTextOrSelectingHospital() = runTest {
    val pending = CompletableDeferred<HospitalSearchResult>()
    fake.pendingResult = pending
    fake.ignoreCancellation = true
    val vm = field(initial = "Geneva")
    search()
    node(C.Tag.hospital_field).performTextReplacement("Home")
    pending.complete(HospitalSearchResult.Success(hospitals))
    advanceUntilIdle()
    node(C.Tag.hospital_field).assertTextContains("Home")
    node(C.Tag.hospital_results).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(HospitalFieldUiState.Idle, vm.uiState.value)
      assertEquals(listOf("Geneva"), fake.completedQueries)
      assertEquals(listOf("Home"), changes)
      assertEquals(listOf<Hospital?>(null), selections)
    }
  }

  @Test
  fun disablingFieldCancelsCooperativePendingSearch() = runTest {
    val pending = CompletableDeferred<HospitalSearchResult>()
    fake.pendingResult = pending
    val vm = field(initial = "Geneva")
    search()
    compose.runOnIdle { enabled = false }
    compose.waitForIdle()
    pending.complete(HospitalSearchResult.Success(hospitals))
    advanceUntilIdle()
    assertNoLateFeedback(vm)
    compose.runOnIdle {
      assertEquals(listOf("Geneva"), fake.cancelledQueries)
      assertTrue(fake.completedQueries.isEmpty())
    }
  }

  private fun assertNoLateFeedback(vm: HospitalFieldViewModel) {
    node(C.Tag.hospital_results).assertDoesNotExist()
    node(C.Tag.hospital_loading).assertDoesNotExist()
    node(C.Tag.hospital_attribution).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(listOf("Geneva"), fake.queries)
      if (fake.ignoreCancellation) assertEquals(listOf("Geneva"), fake.completedQueries)
      assertEquals(HospitalFieldUiState.Idle, vm.uiState.value)
      assertEquals("Geneva", value)
      assertTrue(changes.isEmpty())
      assertTrue(selections.isEmpty())
    }
  }

  @Test
  fun emptyResultsKeepTextAndShowFeedbackAndAttribution() {
    field(initial = "Home")
    search()
    node(C.Tag.hospital_empty).assertIsDisplayed()
    node(C.Tag.hospital_attribution).assertIsDisplayed()
    node(C.Tag.hospital_field).assertTextContains("Home")
    compose.runOnIdle { assertTrue(changes.isEmpty()) }
  }

  @Test
  fun offlineFailureKeepsTextEditableAndRetryCanSucceed() {
    fake.result = HospitalSearchResult.Error(HospitalSearchError.NETWORK)
    field(initial = "My location")
    search()
    node(C.Tag.hospital_error).assertIsDisplayed()
    node(C.Tag.hospital_field).assertTextContains("My location").performTextReplacement("Geneva")
    node(C.Tag.hospital_error).assertDoesNotExist()
    compose.runOnIdle { fake.result = HospitalSearchResult.Success(hospitals) }
    search()
    node(C.Tag.hospital_results).assertIsDisplayed()
    node(C.Tag.hospital_field).assertTextContains("Geneva")
  }

  @Test
  fun editingOrExternalChangesClearStaleResultsWithoutSearching() {
    fake.result = HospitalSearchResult.Success(hospitals)
    field(initial = "Geneva")
    search()
    node(C.Tag.hospital_field).performTextReplacement("Lausanne")
    node(C.Tag.hospital_results).assertDoesNotExist()
    search()
    compose.runOnIdle { value = "External draft" }
    node(C.Tag.hospital_results).assertDoesNotExist()
    node(C.Tag.hospital_field).assertTextContains("External draft")
    compose.runOnIdle { assertEquals(listOf("Geneva", "Lausanne"), fake.queries) }
  }

  @Test
  fun disablingAndRemovingFieldClearFeedbackWithoutChangingText() {
    fake.result = HospitalSearchResult.Success(hospitals)
    val vm = field(initial = "Geneva")
    search()
    compose.runOnIdle { enabled = false }
    node(C.Tag.hospital_field).assertIsNotEnabled()
    node(C.Tag.hospital_search).assertIsNotEnabled()
    node(C.Tag.hospital_results).assertDoesNotExist()
    compose.runOnIdle { enabled = true }
    search()
    compose.runOnIdle { visible = false }
    compose.runOnIdle {
      assertEquals(HospitalFieldUiState.Idle, vm.uiState.value)
      assertEquals("Geneva", value)
    }
  }

  @Test
  fun loadingDisablesOnlySearchAndAllowsManualEdits() {
    val dispatcher = StandardTestDispatcher()
    val repo =
        HospitalRepositoryNominatim(
            "https://example.invalid/search",
            HospitalHttpTransport { error("Must not run") },
            dispatcher,
            HospitalRequestGate(),
        )
    val vm = field(repo, "Geneva")
    node(C.Tag.hospital_search).performClick()
    node(C.Tag.hospital_loading).assertIsDisplayed()
    node(C.Tag.hospital_search).assertIsNotEnabled()
    node(C.Tag.hospital_field).assertIsEnabled().performTextReplacement("Home")
    node(C.Tag.hospital_loading).assertDoesNotExist()
    compose.runOnIdle {
      vm.clearSearch()
      assertEquals("Home", value)
    }
    dispatcher.scheduler.advanceUntilIdle()
  }

  @Test
  fun cachedRepositoryResultsRemainAccessibleAfterTransportGoesOffline() {
    var offline = false
    var requests = 0
    val repo =
        HospitalRepositoryNominatim(
            "https://example.invalid/search",
            HospitalHttpTransport {
              requests++
              if (offline) throw IOException("Offline")
              HospitalHttpResponse(
                  200,
                  """[{"osm_type":"way","osm_id":1,"category":"amenity","type":"hospital","name":"Geneva Hospital","display_name":"Geneva Hospital, Geneva","lat":"46.2","lon":"6.1"}]""",
              )
            },
            kotlinx.coroutines.Dispatchers.IO,
            HospitalRequestGate(),
        )
    field(repo, "Geneva")
    search()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(C.Tag.hospital_result).fetchSemanticsNodes().size == 1
    }
    node(C.Tag.hospital_field).performTextReplacement("Home")
    compose.runOnIdle { offline = true }
    node(C.Tag.hospital_field).performTextReplacement("Geneva")
    search()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(C.Tag.hospital_result).fetchSemanticsNodes().size == 1
    }
    node(C.Tag.hospital_attribution).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(1, requests)
      assertEquals("Geneva", value)
    }
  }

  @Test
  fun attributionOpensTheRequiredCopyrightUrl() {
    var opened: String? = null
    compose.setContent {
      CompositionLocalProvider(
          LocalUriHandler provides
              object : UriHandler {
                override fun openUri(uri: String) {
                  opened = uri
                }
              }
      ) {
        HospitalFieldContent("Geneva", {}, HospitalFieldUiState.Results(hospitals), {}, {})
      }
    }
    node(C.Tag.hospital_attribution)
        .assertTextContains(HospitalRepository.ATTRIBUTION)
        .performClick()
    compose.runOnIdle { assertEquals(HospitalRepository.ATTRIBUTION_URL, opened) }
  }

  @Test
  fun statelessContentRendersLoadingEmptyErrorAndIdle() {
    var state by mutableStateOf<HospitalFieldUiState>(HospitalFieldUiState.Loading)
    compose.setContent { HospitalFieldContent("Draft", {}, state, {}, {}) }
    node(C.Tag.hospital_loading).assertIsDisplayed()
    node(C.Tag.hospital_search).assertIsNotEnabled()
    compose.runOnIdle { state = HospitalFieldUiState.Results(emptyList()) }
    node(C.Tag.hospital_empty).assertIsDisplayed()
    compose.runOnIdle { state = HospitalFieldUiState.Error }
    node(C.Tag.hospital_error).assertIsDisplayed()
    node(C.Tag.hospital_field).assertTextContains("Draft").assertIsEnabled()
    compose.runOnIdle { state = HospitalFieldUiState.Idle }
    node(C.Tag.hospital_error).assertDoesNotExist()
    node(C.Tag.hospital_empty).assertDoesNotExist()
    node(C.Tag.hospital_loading).assertDoesNotExist()
  }

  /** Disabling Search keeps manual entry usable and prevents all repository calls. */
  @Test
  fun searchDisabledKeepsButtonVisibleAndTypingEnabledWithoutRepositoryCalls() {
    searchEnabled = false
    val model = field(initial = "Manual hospital")
    node(C.Tag.hospital_search).assertIsDisplayed().assertIsNotEnabled().performTouchInput {
      click()
    }
    node(C.Tag.hospital_field).assertIsEnabled().performTextReplacement("Home")
    compose.runOnIdle {
      assertEquals("Home", value)
      assertTrue(fake.queries.isEmpty())
      assertEquals(HospitalFieldUiState.Idle, model.uiState.value)
      assertEquals(listOf<Hospital?>(null), selections)
    }
  }

  /** A manual-only consumer cannot dispatch a search action even through the button semantics. */
  @Test
  fun statelessSearchDisabledNeverDispatchesSearchAction() {
    var searches = 0
    compose.setContent {
      HospitalFieldContent(
          "Hospital",
          {},
          HospitalFieldUiState.Idle,
          { searches++ },
          {},
          searchEnabled = false,
      )
    }
    node(C.Tag.hospital_search).assertIsNotEnabled().performClick().performTouchInput { click() }
    compose.runOnIdle { assertEquals(0, searches) }
  }

  /** Switching to manual-only mode cancels pending search and rejects its late response. */
  @Test
  fun disablingSearchDuringPendingRequestDiscardsLateResults() = runTest {
    val pending = CompletableDeferred<HospitalSearchResult>()
    fake.pendingResult = pending
    fake.ignoreCancellation = true
    val model = field(initial = "Geneva")
    search()
    compose.runOnIdle { searchEnabled = false }
    compose.waitForIdle()
    pending.complete(HospitalSearchResult.Success(hospitals))
    advanceUntilIdle()
    node(C.Tag.hospital_field).assertIsEnabled().assertTextContains("Geneva")
    node(C.Tag.hospital_search).assertIsNotEnabled()
    assertNoLateFeedback(model)
  }
}
