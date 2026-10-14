// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures device-rendered form states for UI review without patient data or Firebase. */
@RunWith(AndroidJUnit4::class)
class AppointmentFormScreenshotsTest {
  @get:Rule val compose = createComposeRule()

  /** Reviews empty, valid, saving, error and repeated-time presentations. */
  @Test
  fun formStatesRemainReadableAndHaveReviewScreenshots() {
    val empty = AppointmentFormUiState(LocalDate.of(2026, 10, 15), ZoneId.of("Europe/Zurich"))
    val valid =
        empty.copy(
            title = "Consultation",
            time = LocalTime.of(10, 30),
            location = "HUG, room 12",
            notes = "Bring appointment documents",
        )
    val state = mutableStateOf(empty)
    compose.setContent {
      OncompanionTheme {
        AppointmentFormContent(
            state = state.value,
            onTitleChange = {},
            onDateChange = {},
            onTimeChange = {},
            onLocationChange = {},
            onTypeChange = {},
            onNotesChange = {},
            onOffsetChange = {},
            onSave = {},
            onErrorShown = {},
            onBack = {},
        )
      }
    }
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsNotEnabled()
    capture("empty")
    compose.runOnIdle { state.value = valid }
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsEnabled()
    capture("filled")
    compose.runOnIdle { state.value = valid.copy(isSaving = true) }
    compose.onNodeWithTag(C.Tag.appointment_saving).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_title).assertIsNotEnabled()
    capture("saving", scrollToTitle = false)
    compose.runOnIdle { state.value = valid.copy(error = AppointmentFormError.SAVE_FAILED) }
    compose.onNodeWithText("Couldn't save your appointment. Please try again.").assertIsDisplayed()
    capture("error")
    compose.runOnIdle {
      state.value = valid.copy(date = LocalDate.of(2026, 10, 25), time = LocalTime.of(2, 30))
    }
    compose.onNodeWithText("UTC +02:00").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("UTC +01:00").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsNotEnabled()
    capture("repeated-time")
  }

  private fun capture(name: String, scrollToTitle: Boolean = true) {
    if (scrollToTitle) compose.onNodeWithTag(C.Tag.appointment_title).performScrollTo()
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.cacheDir, "appointment-form-$name.png").outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
