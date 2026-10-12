package com.github.se.oncompanion.model.appointment

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks appointment permissions and raw document validation independently of Kotlin validation.
 */
@RunWith(AndroidJUnit4::class)
class AppointmentSecurityRulesTest {
  private lateinit var ownerUid: String
  private lateinit var otherUid: String

  private val db
    get() = FirebaseEmulator.firestore

  /** Uses fresh emulator accounts so earlier tests cannot grant or cache access. */
  @Before
  fun setUp(): Unit = runBlocking {
    db.enableNetwork().await()
    otherUid = EmulatorTestData.createUser("appointment-other")
    ownerUid = EmulatorTestData.createUser("appointment-owner")
  }

  /** Restores networking and signs out without deleting shared emulator data. */
  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    EmulatorTestData.signOut()
  }

  private fun appointments(uid: String = ownerUid) =
      db.collection("users").document(uid).collection("appointments")

  private fun validFields(): MutableMap<String, Any?> =
      mutableMapOf(
          "title" to "Oncology consultation",
          "scheduledAt" to Timestamp(Instant.parse("2026-10-15T08:00:00Z")),
          "type" to "CONSULTATION",
          "location" to "HUG",
          "notes" to "Bring documents",
          "createdAt" to FieldValue.serverTimestamp(),
      )

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10_000) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000) { task.await() }
      fail("Expected PERMISSION_DENIED")
    } catch (error: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, error.code)
    }
  }

  private suspend fun deniedCreate(fields: Map<String, Any?>) {
    denied(appointments().document().set(fields))
  }

  /** Allows only the owner's valid create and server-backed document/list reads. */
  @Test
  fun ownerCanCreateAndRead(): Unit = runBlocking {
    val document = appointments().document()
    allowed(document.set(validFields()))
    assertTrue(allowed(document.get(Source.SERVER)).exists())
    assertEquals(1, allowed(appointments().get(Source.SERVER)).size())
  }

  /** Supports every appointment category and both ways of leaving optional text unset. */
  @Test
  fun allTypesAndOptionalFieldRepresentationsAreAllowed(): Unit = runBlocking {
    for (type in listOf("TREATMENT", "CONSULTATION", "OTHER")) {
      allowed(
          appointments()
              .document()
              .set(
                  validFields().apply {
                    put("type", type)
                    remove("location")
                    remove("notes")
                  }
              )
      )
    }
    allowed(
        appointments()
            .document()
            .set(
                validFields().apply {
                  put("location", null)
                  put("notes", null)
                }
            )
    )
    allowed(
        appointments()
            .document()
            .set(
                validFields().apply {
                  put("location", "")
                  put("notes", "")
                }
            )
    )
  }

  /** Accepts the same text boundaries as Appointment validation. */
  @Test
  fun maximumTextLengthsAreAllowed(): Unit = runBlocking {
    allowed(
        appointments()
            .document()
            .set(
                validFields().apply {
                  put("title", "t".repeat(100))
                  put("location", "l".repeat(200))
                  put("notes", "n".repeat(1000))
                }
            )
    )
  }

  /** Accepts ordinary international text without making location search mandatory. */
  @Test
  fun unicodePatientTextIsAllowed(): Unit = runBlocking {
    allowed(
        appointments()
            .document()
            .set(
                validFields().apply {
                  put("title", "Contrôle 🗓")
                  put("location", "Genève")
                  put("notes", "持参する書類")
                }
            )
    )
  }

  /** Permits historical and upcoming appointments without comparing them to request time. */
  @Test
  fun pastAndFutureScheduledTimesAreAllowed(): Unit = runBlocking {
    for (time in listOf("2000-01-01T00:00:00Z", "2100-01-01T00:00:00Z")) {
      allowed(
          appointments()
              .document()
              .set(validFields().apply { put("scheduledAt", Timestamp(Instant.parse(time))) })
      )
    }
  }

  /** Denies another signed-in user all access to the owner's appointments. */
  @Test
  fun otherUserCannotReadCreateUpdateOrDelete(): Unit = runBlocking {
    val document = appointments().document()
    allowed(document.set(validFields()))
    EmulatorTestData.signIn("appointment-other")
    denied(document.get(Source.SERVER))
    denied(appointments().get(Source.SERVER))
    denied(appointments().document().set(validFields()))
    denied(document.update("title", "Changed"))
    denied(document.delete())
  }

  /** Prevents an owner from creating appointments under a different patient's uid. */
  @Test
  fun cannotCreateForAnotherUser(): Unit = runBlocking {
    denied(appointments(otherUid).document().set(validFields()))
  }

  /** Requires authentication for all appointment reads and writes. */
  @Test
  fun signedOutRequestsAreDenied(): Unit = runBlocking {
    val document = appointments().document()
    allowed(document.set(validFields()))
    EmulatorTestData.signOutAndWaitForFirestore(ownerUid)
    denied(document.get(Source.SERVER))
    denied(appointments().get(Source.SERVER))
    denied(appointments().document().set(validFields()))
    denied(document.update("title", "Changed"))
    denied(document.delete())
  }

  /** Rejects omitted required keys and fields not present in the appointment schema. */
  @Test
  fun missingRequiredAndUnexpectedFieldsAreDenied(): Unit = runBlocking {
    for (key in listOf("title", "scheduledAt", "type", "createdAt")) {
      deniedCreate(validFields().apply { remove(key) })
    }
    for (key in listOf("uid", "id", "isAdmin", "anything")) {
      deniedCreate(validFields().apply { put(key, "unexpected") })
    }
    deniedCreate(emptyMap())
  }

  /** Rejects unusable titles, wrong types and over-limit text. */
  @Test
  fun invalidTitlesAreDenied(): Unit = runBlocking {
    for (title in
        listOf(
            "",
            " ",
            "\t\n",
            "\u00a0",
            "\u2003",
            "\u202f",
            "\u3000",
            "t".repeat(101),
            null,
            42,
        )) {
      deniedCreate(validFields().apply { put("title", title) })
    }
  }

  /** Enforces the recorded enum values rather than arbitrary client-supplied categories. */
  @Test
  fun invalidAppointmentTypesAreDenied(): Unit = runBlocking {
    for (type in listOf("consultation", "UNKNOWN", null, 42)) {
      deniedCreate(validFields().apply { put("type", type) })
    }
  }

  /** Enforces optional text types and their separate storage limits. */
  @Test
  fun invalidLocationAndNotesAreDenied(): Unit = runBlocking {
    deniedCreate(validFields().apply { put("location", "l".repeat(201)) })
    deniedCreate(validFields().apply { put("notes", "n".repeat(1001)) })
    for (key in listOf("location", "notes")) {
      deniedCreate(validFields().apply { put(key, 42) })
      deniedCreate(validFields().apply { put(key, listOf("text")) })
    }
  }

  /** Requires real scheduled timestamps and server-assigned creation timestamps. */
  @Test
  fun invalidOrForgedTimestampsAreDenied(): Unit = runBlocking {
    for (time in listOf(null, "2026-10-15", 42)) {
      deniedCreate(validFields().apply { put("scheduledAt", time) })
    }
    for (time in
        listOf(
            null,
            "2026-10-10",
            Timestamp(Instant.EPOCH),
            Timestamp(Instant.parse("2100-01-01T00:00:00Z")),
        )) {
      deniedCreate(validFields().apply { put("createdAt", time) })
    }
  }

  /** Leaves every update path and deletion denied until those features are implemented. */
  @Test
  fun ownerCannotUpdateReplaceMergeOrDelete(): Unit = runBlocking {
    val document = appointments().document()
    allowed(document.set(validFields()))
    denied(document.update("title", "Changed"))
    denied(document.set(validFields()))
    denied(document.set(mapOf("title" to "Changed"), SetOptions.merge()))
    denied(document.update("createdAt", Timestamp(Instant.EPOCH)))
    denied(document.delete())
    assertEquals("Oncology consultation", allowed(document.get(Source.SERVER)).getString("title"))
  }

  /** Does not accidentally grant access to unrecognized subcollections under appointments. */
  @Test
  fun nestedCollectionsStayDenied(): Unit = runBlocking {
    val nested = appointments().document("appointment").collection("unexpected")
    denied(nested.document("entry").set(mapOf("anything" to true)))
    denied(nested.get(Source.SERVER))
  }
}
