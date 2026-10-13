// Co-authored-by: Codex
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
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
  private var visible by mutableStateOf(true)
  private val changes = mutableListOf<String>()

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
              enabled = enabled,
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
      assertEquals(1, fake.queries.size)
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
        HospitalFieldContent("Geneva", {}, HospitalFieldUiState.Results(hospitals), {})
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
    compose.setContent { HospitalFieldContent("Draft", {}, state, {}) }
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
}
