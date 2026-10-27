// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.Source
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Exercises the production edit flow against cached Firebase data and later synchronization. */
@RunWith(AndroidJUnit4::class)
class AppointmentEditTest {
  @get:Rule val compose = createComposeRule()

  /** Restores the shared emulator connection and session for later tests. */
  @After
  fun teardown(): Unit = runBlocking {
    FirebaseEmulator.firestore.enableNetwork().await()
    EmulatorTestData.signOut()
  }

  /** An offline edit updates the same appointment immediately in details, Planning and Overview. */
  @Test
  fun editCachedAppointmentOfflineAndSynchronize() {
    val db = FirebaseEmulator.firestore
    val repository = AppointmentRepositoryFirestore(db)
    val today = LocalDate.now()
    val day = today.plusDays(1)
    val original = runBlocking {
      withTimeout(10_000) {
        db.enableNetwork().await()
        val uid = EmulatorTestData.createUser("appointment-edit-owner")
        val id =
            repository.addAppointment(
                uid,
                Appointment(
                    title = "Oncology follow-up",
                    scheduledAt = day.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant(),
                    type = AppointmentType.CONSULTATION,
                    location = "Room 3",
                    notes = "Bring appointment documents",
                ),
            )
        db.waitForPendingWrites().await()
        val snapshot =
            db.collection("users")
                .document(uid)
                .collection("appointments")
                .document(id)
                .get(Source.SERVER)
                .await()
        db.collection("users")
            .document(uid)
            .collection("appointments")
            .orderBy("scheduledAt")
            .get(Source.SERVER)
            .await()
        db.disableNetwork().await()
        uid to snapshot
      }
    }
    compose.setContent { OncompanionTheme { AppNavHost(startRoute = Route.PLANNING) } }
    if (
        day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) !=
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    )
        compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.onNodeWithTag(C.Tag.planningDay(day.toString())).performClick()
    compose.onNodeWithText("Oncology follow-up").performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_edit_button).performClick()
    compose.onNodeWithTag(C.Tag.appointment_title).assertTextContains("Oncology follow-up")
    compose
        .onNodeWithTag(C.Tag.appointment_notes)
        .performScrollTo()
        .assertTextContains("Bring appointment documents")
    compose.onNodeWithTag(C.Tag.appointment_title).performScrollTo()
    capture("appointment-edit-form.png")
    compose.onNodeWithTag(C.Tag.appointment_title).performTextClearance()
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Updated oncology follow-up")
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_screen).assertIsDisplayed()
    compose.onNodeWithText("Updated oncology follow-up").assertIsDisplayed()
    capture("appointment-edit-saved.png")
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    compose.onNodeWithText("Updated oncology follow-up").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.bottom_navigation_tab_overview).performClick()
    compose
        .onNodeWithTag(C.Tag.overview_next_appointment)
        .assertTextContains("Updated oncology follow-up")
    runBlocking {
      withTimeout(10_000) {
        val collection = db.collection("users").document(original.first).collection("appointments")
        val local = collection.get(Source.CACHE).await().documents.single()
        assertEquals(original.second.id, local.id)
        assertEquals("Updated oncology follow-up", local.getString("title"))
        db.enableNetwork().await()
        db.waitForPendingWrites().await()
        val saved = collection.get(Source.SERVER).await().documents.single()
        assertEquals(original.second.id, saved.id)
        assertEquals("Updated oncology follow-up", saved.getString("title"))
        assertEquals(original.second.getTimestamp("createdAt"), saved.getTimestamp("createdAt"))
        assertEquals(original.second.getTimestamp("scheduledAt"), saved.getTimestamp("scheduledAt"))
      }
    }
  }

  private fun capture(name: String) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.cacheDir, name).outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
