// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryFirestore
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.Source
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks that production calendar wiring displays the appointment repository's offline cache. */
@RunWith(AndroidJUnit4::class)
class PlanningAppointmentsTest {
  @get:Rule val compose = createComposeRule()

  /** Restores the shared emulator connection after exercising offline observation. */
  @After
  fun tearDown(): Unit = runBlocking {
    FirebaseEmulator.firestore.enableNetwork().await()
    EmulatorTestData.signOut()
  }

  /** Displays a saved appointment without a Planning override or a network connection. */
  @Test
  fun cachedAppointmentAppearsThroughDefaultPlanningWiringOffline() {
    val db = FirebaseEmulator.firestore
    val repository = AppointmentRepositoryFirestore(db)
    runBlocking {
      withTimeout(10_000) {
        db.enableNetwork().await()
        val uid = EmulatorTestData.createUser("planning-appointment-owner")
        repository.addAppointment(
            uid,
            Appointment(
                title = "Offline consultation",
                type = AppointmentType.CONSULTATION,
                scheduledAt =
                    LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(),
                location = "HUG",
            ),
        )
        db.waitForPendingWrites().await()
        // Prime the same ordered query used by appointment observation before disconnecting.
        db.collection("users")
            .document(uid)
            .collection("appointments")
            .orderBy("scheduledAt")
            .get(Source.SERVER)
            .await()
        db.disableNetwork().await()
      }
    }
    // No provider is replaced: the app's own repositories read the emulator
    compose.setContent { OncompanionTheme { AppNavHost(startRoute = Route.PLANNING) } }
    compose.onNodeWithText("Offline consultation").assertIsDisplayed()
    compose.onNodeWithText("HUG").assertIsDisplayed()
    // Keep the populated offline agenda as a device-rendered artifact for the PR review.
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.cacheDir, "planning-appointments-offline.png").outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  /** Confirms the actual form enqueues offline and its new row appears before reconnecting. */
  @Test
  fun formCreatesAppointmentAndReturnsToPlanningOffline() {
    val db = FirebaseEmulator.firestore
    val uid = runBlocking {
      withTimeout(10_000) {
        db.enableNetwork().await()
        val patient = EmulatorTestData.createUser("appointment-form-owner")
        db.collection("users")
            .document(patient)
            .collection("appointments")
            .orderBy("scheduledAt")
            .get(Source.SERVER)
            .await()
        db.disableNetwork().await()
        patient
      }
    }
    compose.setContent { OncompanionTheme { AppNavHost(startRoute = Route.PLANNING) } }
    compose.onNodeWithTag(C.Tag.planning_add).performClick()
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Offline form visit")
    closeSoftKeyboard()
    compose.onNodeWithTag(C.Tag.appointment_time).performClick()
    compose.onNodeWithTag(C.Tag.appointment_confirm).performClick()
    compose.onNodeWithTag(C.Tag.appointment_location).performScrollTo().performTextInput("Room 12")
    closeSoftKeyboard()
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    compose.waitUntil(10_000) {
      compose
          .onAllNodes(androidx.compose.ui.test.hasText("Offline form visit"))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.onNodeWithText("Offline form visit").assertIsDisplayed()
    compose.onNodeWithText("Room 12").assertIsDisplayed()
    runBlocking {
      withTimeout(10_000) {
        val query = db.collection("users").document(uid).collection("appointments")
        val local = query.get(Source.CACHE).await().documents.single()
        assertEquals("Room 12", local.getString("location"))
        // Offline completion is already verified; now check synchronization to emulator only.
        db.enableNetwork().await()
        db.waitForPendingWrites().await()
        assertEquals(local.id, query.get(Source.SERVER).await().documents.single().id)
      }
    }
  }
}
