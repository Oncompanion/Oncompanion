// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.prescription

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.medication.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The M2 detail uses shared controls and shows only recorded information, in every state. */
@RunWith(AndroidJUnit4::class)
class MedicationDetailScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val day = LocalDate.of(2026, 11, 10)
  private val medication = Medication("m", "p", "Medication A", "1 tablet", "As recorded", day, 5)
  private val prescription = Prescription("p", "Dr. Example", day, listOf(medication))
  private val loaded = MedicationDetailUiState.Loaded(medication, prescription)

  /** The correct prescription ID is passed, and fields use the same labels as the form. */
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun loaded_showsRecordedFieldsAndNavigation() {
    var opened = ""
    var backs = 0
    compose.setContent {
      OncompanionTheme { MedicationDetailContent(loaded, { backs++ }, {}, { opened = it }) }
    }
    screenshot("medication-detail-light")
    for (text in
        listOf(
            "Medication A",
            "1 tablet",
            "As recorded",
            "5 days",
            "Dr. Example",
            "Dosage",
            "Frequency",
            "Start date",
            "Duration",
            "Prescription date",
        )) {
      compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    compose.onAllNodesWithText("10 Nov 2026").assertCountEquals(2)
    compose.onNodeWithTag(C.Tag.medication_detail_prescription).performClick()
    assertEquals("p", opened)
    compose.onNodeWithTag(C.Tag.medication_detail_back).performClick()
    assertEquals(1, backs)
  }

  /** Blank and missing optional values produce no empty rows or inferred values. */
  @Test
  fun optionalFields_areOmitted_andPendingAttributionIsClear() {
    val current = mutableStateOf(loaded)
    compose.setContent { OncompanionTheme { MedicationDetailContent(current.value, {}, {}, {}) } }
    for (missing in listOf<String?>(null, "", "   ")) {
      compose.runOnIdle {
        current.value =
            MedicationDetailUiState.Loaded(
                medication.copy(dosage = missing, frequency = missing, durationDays = null),
                prescription.copy(prescribedBy = missing),
            )
      }
      for (label in listOf("Dosage", "Frequency", "Duration", "Prescribed by")) compose
          .onNodeWithText(label)
          .assertDoesNotExist()
      compose.onNodeWithText("Start date").assertExists()
    }
    compose.runOnIdle {
      current.value =
          loaded.copy(
              prescription =
                  prescription.copy(
                      addedBy = "c",
                      addedByName = "Sam",
                      confirmation = PrescriptionConfirmation.PENDING,
                  )
          )
    }
    compose.onNodeWithText("Added by Sam").assertExists()
    compose.onNodeWithText("Not confirmed").assertExists()
    compose.runOnIdle { current.value = loaded }
    compose.onNodeWithText("Not confirmed").assertDoesNotExist()
  }

  /** No failure state can open a stale prescription; only a read error offers Retry. */
  @Test
  fun nonLoadedStates_showFeedbackAndOnlyErrorsRetry() {
    val current = mutableStateOf<MedicationDetailUiState>(MedicationDetailUiState.Loading)
    var retries = 0
    compose.setContent {
      OncompanionTheme {
        MedicationDetailContent(current.value, {}, { retries++ }, {}, caregiverOf = "Anne")
      }
    }
    compose.onNodeWithTag(C.Tag.medication_detail_loading).assertIsDisplayed()
    for (state in
        listOf(
            MedicationDetailUiState.Error,
            MedicationDetailUiState.Unavailable,
            MedicationDetailUiState.NotShared,
        )) {
      compose.runOnIdle { current.value = state }
      compose.onNodeWithTag(C.Tag.medication_detail_prescription).assertDoesNotExist()
      compose.onNodeWithTag(C.Tag.medication_detail_loading).assertDoesNotExist()
      if (state == MedicationDetailUiState.Error)
          compose.onNodeWithTag(C.Tag.medication_detail_retry).performClick()
      else compose.onNodeWithTag(C.Tag.medication_detail_retry).assertDoesNotExist()
    }
    assertEquals(1, retries)
    compose.onNodeWithText("Anne", substring = true).assertExists()
  }

  /** Shared adaptive content keeps long recorded text and its action reachable at large font. */
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeAtLargeFont_keepsTheActionFullyVisibleAfterScrolling() {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
        OncompanionTheme {
          MedicationDetailContent(
              loaded.copy(
                  medication =
                      medication.copy(
                          name = "A long recorded medication name that wraps across lines",
                          frequency =
                              "A long frequency recorded by the patient without interpreting intake times",
                      )
              ),
              {},
              {},
              {},
          )
        }
      }
    }
    compose
        .onNodeWithTag(C.Tag.medication_detail_prescription)
        .performScrollTo()
        .assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.medication_detail_back).assertIsDisplayed()
    screenshot("medication-detail-landscape")
  }

  /** Recreating the activity keeps the same observed medication, including subsequent edits. */
  @Test
  fun screenWithViewModel_survivesRotationAndReflectsUpdates() {
    val auth = FakeAuthRepository { AuthUser(it) }
    runBlocking { auth.signInWithGoogle("patient") }
    val repository = FakeMedicationRepository().apply { seed("patient", prescription) }
    val vm =
        MedicationDetailViewModel(
            "p",
            "m",
            auth,
            repository,
            careCircleRepository = FakeCareCircleRepository(),
        )
    compose.setContent { OncompanionTheme { MedicationDetailScreen(vm, {}, {}) } }
    compose.onNodeWithText("Medication A").assertExists()
    compose.activityRule.scenario.recreate()
    compose.activity.setContent { OncompanionTheme { MedicationDetailScreen(vm, {}, {}) } }
    compose.onNodeWithText("Medication A").assertExists()
    compose.runOnIdle {
      repository.seed(
          "patient",
          prescription.copy(medications = listOf(medication.copy(name = "Corrected"))),
      )
    }
    compose.onNodeWithText("Corrected").assertExists()
  }

  /** Shared tokens apply the same dark palette as appointment details. */
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun darkTheme_keepsRecordedContentReadable() {
    compose.setContent {
      OncompanionTheme(darkTheme = true) { MedicationDetailContent(loaded, {}, {}, {}) }
    }
    compose.onNodeWithText("Medication A").assertIsDisplayed()
    screenshot("medication-detail-dark")
  }

  private fun screenshot(name: String) {
    val file = File("build/outputs/screenshots/$name.png")
    file.parentFile.mkdirs()
    file.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
