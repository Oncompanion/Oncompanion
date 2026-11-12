// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryFirestore
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore
import com.github.se.oncompanion.model.medication.Prescription
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

/** Exercises prescription cache changes through the production Planning provider and screen. */
@RunWith(AndroidJUnit4::class)
class PlanningMedicationsTest {
  @get:Rule val compose = createComposeRule()

  /** Restores connectivity and authentication for the other emulator tests. */
  @After
  fun tearDown(): Unit = runBlocking {
    FirebaseEmulator.firestore.enableNetwork().await()
    EmulatorTestData.signOut()
  }

  /** Offline additions, edits and removals update the agenda before any server acknowledgement. */
  @Test
  fun prescriptionChangesReachPlanningOfflineAndRemainUniqueAfterSync() {
    val db = FirebaseEmulator.firestore
    val repository = MedicationRepositoryFirestore(db)
    val today = LocalDate.now()
    val a =
        Medication(
            "a",
            "p",
            "Recorded medication A",
            frequency = "Mondays and Thursdays",
            startDate = today,
            durationDays = 8,
        )
    val b = Medication("b", "p", "Recorded medication B", startDate = today, durationDays = 8)
    val prescription = Prescription("p", prescribedOn = today, medications = listOf(a, b))
    val uid = runBlocking {
      withTimeout(15_000) {
        db.enableNetwork().await()
        val patient = EmulatorTestData.createUser("planning-medications")
        repository.addPrescription(patient, prescription)
        AppointmentRepositoryFirestore(db)
            .addAppointment(
                patient,
                Appointment(
                    title = "Recorded consultation",
                    scheduledAt = today.atTime(14, 0).atZone(ZoneId.systemDefault()).toInstant(),
                    type = AppointmentType.CONSULTATION,
                ),
            )
        db.waitForPendingWrites().await()
        db.collection("users")
            .document(patient)
            .collection("prescriptions")
            .get(Source.SERVER)
            .await()
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
    awaitMedication(today, a.name)
    compose.onNodeWithText(a.name).assertIsDisplayed()
    compose.onNodeWithText("Frequency: Mondays and Thursdays").assertIsDisplayed()
    compose.onNodeWithText("Frequency not specified").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_no_set_time).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_scheduled).assertIsDisplayed()
    screenshot("planning-medications-mixed.png")
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    awaitMedication(today.plusWeeks(1), a.name)
    compose.onNodeWithText(a.name).assertIsDisplayed()
    screenshot("planning-medications-next-week.png")
    // Open the second medication offline, then its prescription, without replacing this week.
    val selectedDay =
        compose
            .onNodeWithTag(C.Tag.planning_heading)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
    // Rapid taps must open only one medication detail; Back below returns directly to Planning.
    compose.onNodeWithText(b.name).performTouchInput { doubleClick() }
    compose.waitUntil(10_000) {
      compose
          .onAllNodesWithTag(C.Tag.medication_detail_prescription)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    awaitDisplayed(hasText(b.name))
    compose.onNodeWithText(b.name).assertIsDisplayed()
    compose.onNodeWithText(a.name).assertDoesNotExist()
    screenshot("medication-detail-offline.png")
    compose.onNodeWithTag(C.Tag.medication_detail_prescription).performTouchInput { doubleClick() }
    compose.waitUntil(10_000) {
      compose
          .onAllNodesWithTag(C.Tag.prescription_detail_content)
          .fetchSemanticsNodes()
          .isNotEmpty() && compose.onAllNodesWithText(b.name).fetchSemanticsNodes().size == 1
    }
    compose.onNodeWithText(b.name).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_detail_back_button).performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag(C.Tag.medication_detail_content).fetchSemanticsNodes().isNotEmpty()
    }
    awaitDisplayed(hasText(b.name))
    compose.onNodeWithTag(C.Tag.medication_detail_back).performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag(C.Tag.planning_screen).fetchSemanticsNodes().isNotEmpty()
    }
    awaitMedication(today.plusWeeks(1), a.name)
    assertEquals(
        selectedDay,
        compose
            .onNodeWithTag(C.Tag.planning_heading)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text],
    )
    compose.onNodeWithTag(C.Tag.planning_previous).performClick()
    val edited =
        prescription.copy(
            medications = listOf(a.copy(name = "Edited medication", frequency = null))
        )
    runBlocking { withTimeout(10_000) { repository.updatePrescription(uid, edited) } }
    awaitMedication(today, "Edited medication")
    compose.onNodeWithText(a.name).assertDoesNotExist()
    compose.onNodeWithText(b.name).assertDoesNotExist()
    compose.onNodeWithText("Frequency not specified").assertIsDisplayed()
    screenshot("planning-medications-edited-offline.png")
    // Reopen the observed range offline; entries are derived, never appended to a second store.
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.onNodeWithTag(C.Tag.planning_previous).performClick()
    awaitMedication(today, "Edited medication")
    compose.onAllNodesWithText("Edited medication").assertCountEquals(1)
    runBlocking {
      withTimeout(15_000) {
        db.enableNetwork().await()
        db.waitForPendingWrites().await()
        val stored =
            db.collection("users")
                .document(uid)
                .collection("prescriptions")
                .document("p")
                .get(Source.SERVER)
                .await()
        assertEquals(1, (stored.get("medications") as List<*>).size)
      }
    }
    awaitMedication(today, "Edited medication")
    compose.onAllNodesWithText("Edited medication").assertCountEquals(1)
    runBlocking {
      withTimeout(10_000) {
        db.disableNetwork().await()
        repository.deletePrescription(uid, "p")
      }
    }
    compose.waitUntil("the deleted medication and its section to leave loaded Planning", 10_000) {
      planningIsReady(today) &&
          compose.onAllNodesWithText("Edited medication").fetchSemanticsNodes().isEmpty() &&
          compose.onAllNodesWithTag(C.Tag.planning_no_set_time).fetchSemanticsNodes().isEmpty()
    }
    compose.onNodeWithTag(C.Tag.planning_no_set_time).assertDoesNotExist()
    // Adding again while disconnected must restore exactly one row.
    runBlocking { withTimeout(10_000) { repository.addPrescription(uid, edited) } }
    awaitMedication(today, "Edited medication")
    runBlocking {
      withTimeout(15_000) {
        db.enableNetwork().await()
        db.waitForPendingWrites().await()
      }
    }
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.onNodeWithTag(C.Tag.planning_previous).performClick()
    awaitMedication(today, "Edited medication")
    compose.onAllNodesWithText("Edited medication").assertCountEquals(1)
  }

  // Date changes restart observation; neither a calendar heading nor an empty list proves
  // readiness.
  private fun planningIsReady(date: LocalDate): Boolean =
      compose
          .onAllNodes(
              hasTestTag(C.Tag.planning_heading) and
                  hasText(PlanningDates.format(date), substring = true)
          )
          .fetchSemanticsNodes()
          .size == 1 &&
          compose.onAllNodesWithTag(C.Tag.planning_loading).fetchSemanticsNodes().isEmpty() &&
          compose.onAllNodesWithTag(C.Tag.planning_error).fetchSemanticsNodes().isEmpty()

  private fun awaitMedication(date: LocalDate, name: String) {
    compose.waitUntil("one visible medication on the expected loaded Planning day", 10_000) {
      planningIsReady(date) &&
          compose.onAllNodesWithText(name).fetchSemanticsNodes().size == 1 &&
          compose.onNodeWithText(name).isDisplayed()
    }
  }

  private fun awaitDisplayed(matcher: SemanticsMatcher) {
    compose.waitUntil("one visible expected detail node", 10_000) {
      compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 &&
          compose.onNode(matcher).isDisplayed()
    }
  }

  private fun screenshot(name: String) {
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
