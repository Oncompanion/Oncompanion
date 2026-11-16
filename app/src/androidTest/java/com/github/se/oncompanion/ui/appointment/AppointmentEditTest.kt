// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

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
        await(hasTestTag(C.Tag.planning_next)).performClick()
    await(hasTestTag(C.Tag.planningDay(day.toString()))).performClick()
    await(hasText("Oncology follow-up")).performClick()
    await(hasTestTag(C.Tag.appointment_detail_edit_button)).performClick()
    // The form fills in once the appointment has been read
    await(hasTestTag(C.Tag.appointment_title) and hasText("Oncology follow-up"))
    compose
        .onNodeWithTag(C.Tag.appointment_notes)
        .performScrollTo()
        .assertTextContains("Bring appointment documents")
    compose.onNodeWithTag(C.Tag.appointment_title).performScrollTo()
    capture("appointment-edit-form.png")
    compose.onNodeWithTag(C.Tag.appointment_title).performTextClearance()
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Updated oncology follow-up")
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    await(hasTestTag(C.Tag.appointment_detail_screen)).assertIsDisplayed()
    await(hasText("Updated oncology follow-up")).assertIsDisplayed()
    capture("appointment-edit-saved.png")
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    await(hasTestTag(C.Tag.planning_screen))
    await(hasText("Updated oncology follow-up")).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.bottom_navigation_tab_overview).performClick()
    // The Overview shows its card once the day's planning has been read
    await(
        hasTestTag(C.Tag.overview_next_appointment) and
            hasText("Updated oncology follow-up", substring = true)
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

  /**
   * The node matching [matcher], once it is on screen. The screens read Firestore's cache on its
   * own threads, which Compose's idling doesn't wait for: on a freshly started emulator a screen
   * can still be loading when the previous action returns. Before the first screen is drawn there
   * is no Compose hierarchy yet, which counts as "not there yet" (as in EndToEndTestDriver.exists).
   */
  private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    compose.waitUntil(TIMEOUT_MS) {
      try {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
      } catch (error: IllegalStateException) {
        if (error.message?.startsWith("No compose hierarchies found") != true) throw error
        false
      }
    }
    return compose.onNode(matcher)
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

  private companion object {
    /** As long as the end-to-end tests wait for a screen (EndToEndTestDriver). */
    const val TIMEOUT_MS = 15_000L
  }
}
