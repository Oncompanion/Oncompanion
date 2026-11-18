// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.appointment

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.AppHostActivity
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.EndToEndTestDriver
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
  // AppHostActivity sets its content again when Android relaunches it (e.g. on a freshly started
  // emulator), so the screen comes back instead of staying blank, as in the end-to-end tests
  @get:Rule val compose = createAndroidComposeRule<AppHostActivity>()

  // The screens read Firestore's cache on its own threads, which Compose's idling doesn't wait for:
  // the driver waits for each screen before acting on it, as in the end-to-end tests
  private val ui = EndToEndTestDriver(compose)

  /** Restores the shared emulator connection and session for later tests. */
  @After
  fun teardown(): Unit = runBlocking {
    FirebaseEmulator.firestore.enableNetwork().await()
    AppHostActivity.content.value = {}
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
    compose.runOnUiThread {
      AppHostActivity.content.value = {
        OncompanionTheme { AppNavHost(startRoute = Route.PLANNING) }
      }
    }
    val planningDay = C.Tag.planningDay(day.toString())
    if (
        day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) !=
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    )
        ui.open(C.Tag.planning_next, hasTestTag(planningDay))
    ui.open(planningDay, hasText("Oncology follow-up"))
    ui.open(hasText("Oncology follow-up"), hasTestTag(C.Tag.appointment_detail_edit_button))
    // The form fills in once the appointment has been read
    ui.open(
        C.Tag.appointment_detail_edit_button,
        hasTestTag(C.Tag.appointment_title) and hasText("Oncology follow-up"),
    )
    compose
        .onNodeWithTag(C.Tag.appointment_notes)
        .performScrollTo()
        .assertTextContains("Bring appointment documents")
    compose.onNodeWithTag(C.Tag.appointment_title).performScrollTo()
    capture("appointment-edit-form.png")
    compose.onNodeWithTag(C.Tag.appointment_title).performTextClearance()
    ui.typeText(compose.onNodeWithTag(C.Tag.appointment_title), "Updated oncology follow-up")
    ui.open(C.Tag.appointment_save, hasTestTag(C.Tag.appointment_detail_screen)).assertIsDisplayed()
    ui.waitFor(hasText("Updated oncology follow-up")).assertIsDisplayed()
    capture("appointment-edit-saved.png")
    ui.open(C.Tag.appointment_back, hasTestTag(C.Tag.planning_screen))
    ui.waitFor(hasText("Updated oncology follow-up")).assertIsDisplayed()
    // The Overview shows its card once the day's planning has been read
    ui.open(
        C.Tag.bottom_navigation_tab_overview,
        hasTestTag(C.Tag.overview_next_up) and
            hasText("Updated oncology follow-up", substring = true),
    )
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
