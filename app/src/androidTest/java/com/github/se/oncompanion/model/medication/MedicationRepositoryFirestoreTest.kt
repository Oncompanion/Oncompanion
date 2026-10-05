package com.github.se.oncompanion.model.medication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MedicationRepositoryFirestoreTest {

  private lateinit var repository: MedicationRepositoryFirestore
  private lateinit var aliceUid: String

  private val db
    get() = FirebaseEmulator.firestore

  private val september29 = LocalDate.of(2026, 9, 29)

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    repository = MedicationRepositoryFirestore(db)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  /** A prescription of two medications, with every field filled in the first one. */
  private fun prescription(id: String = "presc-1") =
      Prescription(
          id = id,
          prescribedBy = "Dr. Martin · Oncology",
          prescribedOn = september29,
          medications =
              listOf(
                  Medication(
                      id = "$id-ondansetron",
                      prescriptionId = id,
                      name = "Ondansetron 8 mg",
                      dosage = "1 tablet",
                      frequency = "Twice a day",
                      startDate = LocalDate.of(2026, 10, 1),
                      durationDays = 5,
                  ),
                  Medication(
                      id = "$id-dexamethasone",
                      prescriptionId = id,
                      name = "Dexamethasone 4 mg",
                      startDate = september29,
                  ),
              ),
      )

  private fun assertSameContent(expected: Prescription, actual: Prescription?) {
    assertNotNull(actual)
    assertEquals(expected.copy(createdAt = null), actual!!.copy(createdAt = null))
  }

  /** Waits until every pending write was acknowledged or rejected by the server. */
  private suspend fun awaitServerAck() {
    withTimeout(10.seconds) { db.waitForPendingWrites().await() }
  }

  /** Reads a document of alice from the server, e.g. `prescriptions/presc-1`. */
  private suspend fun serverSnapshot(collection: String, id: String): DocumentSnapshot =
      withTimeout(10.seconds) {
        db.collection("users")
            .document(aliceUid)
            .collection(collection)
            .document(id)
            .get(Source.SERVER)
            .await()
      }

  // ---- new IDs ----

  @Test
  fun newIdsAreUniqueAndNotBlank() {
    val first = repository.newId()
    val second = repository.newId()

    assertTrue(first.isNotBlank())
    assertNotEquals(first, second)
  }

  // ---- add / get ----

  @Test
  fun getUnknownPrescriptionReturnsNull(): Unit = runBlocking {
    assertNull(repository.getPrescription(aliceUid, "nothing"))
  }

  @Test
  fun addThenGetRoundTrip(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())

    assertSameContent(prescription(), repository.getPrescription(aliceUid, "presc-1"))

    awaitServerAck()
    val stored = repository.getPrescription(aliceUid, "presc-1")
    assertSameContent(prescription(), stored)
    assertNotNull("createdAt is set once the server confirms", stored!!.createdAt)
  }

  @Test
  fun addWithOnlyRequiredFieldsRoundTrips(): Unit = runBlocking {
    val minimal =
        Prescription(
            id = "minimal",
            prescribedOn = september29,
            medications =
                listOf(
                    Medication(
                        id = "minimal-med",
                        prescriptionId = "minimal",
                        name = "Paracetamol 1 g",
                        startDate = september29,
                    )
                ),
        )
    repository.addPrescription(aliceUid, minimal)
    awaitServerAck()

    val stored = repository.getPrescription(aliceUid, "minimal")
    assertSameContent(minimal, stored)
    assertNull(stored!!.prescribedBy)
    assertNull(stored.medications.single().durationDays)
  }

  @Test
  fun medicationsAreReadBackInTheOrderTheyWereSaved(): Unit = runBlocking {
    // IDs chosen so that neither the ID nor the name gives the saved order
    val names = listOf("Zolpidem", "Amoxicillin", "Metoclopramide", "Bisoprolol")
    val ordered =
        prescription()
            .copy(
                medications =
                    names.mapIndexed { index, name ->
                      Medication(
                          id = "med-${names.size - index}",
                          prescriptionId = "presc-1",
                          name = name,
                          startDate = september29,
                      )
                    }
            )
    repository.addPrescription(aliceUid, ordered)
    awaitServerAck()

    assertEquals(
        names,
        repository.getPrescription(aliceUid, "presc-1")!!.medications.map { it.name },
    )
  }

  @Test
  fun addIgnoresClientCreatedAt(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription().copy(createdAt = Instant.EPOCH))
    awaitServerAck()

    val stored = repository.getPrescription(aliceUid, "presc-1")
    assertNotNull(stored!!.createdAt)
    assertTrue(stored.createdAt!!.isAfter(Instant.parse("2020-01-01T00:00:00Z")))
  }

  @Test
  fun addInvalidPrescriptionThrowsAndStoresNothing(): Unit = runBlocking {
    try {
      repository.addPrescription(aliceUid, prescription().copy(medications = emptyList()))
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
    awaitServerAck()

    assertFalse(serverSnapshot("prescriptions", "presc-1").exists())
  }

  @Test
  fun addWritesOneDocumentPerPrescriptionAndPerMedication(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    val prescriptionDoc = serverSnapshot("prescriptions", "presc-1")
    assertEquals("Dr. Martin · Oncology", prescriptionDoc.getString("prescribedBy"))
    assertNotNull(prescriptionDoc.getTimestamp("prescribedOn"))
    assertNotNull(prescriptionDoc.getTimestamp("createdAt"))

    val medicationDoc = serverSnapshot("medications", "presc-1-ondansetron")
    assertEquals("presc-1", medicationDoc.getString("prescriptionId"))
    assertEquals("Ondansetron 8 mg", medicationDoc.getString("name"))
    assertEquals("1 tablet", medicationDoc.getString("dosage"))
    assertEquals("Twice a day", medicationDoc.getString("frequency"))
    assertNotNull(medicationDoc.getTimestamp("startDate"))
    assertEquals(5L, medicationDoc.getLong("durationDays"))
    assertEquals(0L, medicationDoc.getLong("position"))

    assertEquals(1L, serverSnapshot("medications", "presc-1-dexamethasone").getLong("position"))
  }

  @Test
  fun datesAreStoredAsMidnightInSwitzerland(): Unit = runBlocking {
    // 29 September is in summer time (UTC+2), 15 January in winter time (UTC+1)
    val winter = LocalDate.of(2026, 1, 15)
    val summerAndWinter =
        prescription()
            .copy(
                medications =
                    listOf(
                        Medication(
                            id = "winter-med",
                            prescriptionId = "presc-1",
                            name = "Paracetamol 1 g",
                            startDate = winter,
                        )
                    )
            )
    repository.addPrescription(aliceUid, summerAndWinter)
    awaitServerAck()

    assertEquals(
        Instant.parse("2026-09-28T22:00:00Z"),
        serverSnapshot("prescriptions", "presc-1").getTimestamp("prescribedOn")!!.toInstant(),
    )
    assertEquals(
        Instant.parse("2026-01-14T23:00:00Z"),
        serverSnapshot("medications", "winter-med").getTimestamp("startDate")!!.toInstant(),
    )
    assertSameContent(summerAndWinter, repository.getPrescription(aliceUid, "presc-1"))
  }

  @Test
  fun prescriptionsOfAnotherUserAreNotReturned(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    val bobUid = EmulatorTestData.createUser("bob")

    assertNull(repository.getPrescription(bobUid, "presc-1"))
  }

  // ---- offline ----

  @Test
  fun addAndGetWorkOffline(): Unit = runBlocking {
    db.disableNetwork().await()

    withTimeout(5.seconds) { repository.addPrescription(aliceUid, prescription()) }

    val cached = withTimeout(5.seconds) { repository.getPrescription(aliceUid, "presc-1") }
    assertSameContent(prescription(), cached)
    assertNull("Not confirmed by the server yet", cached!!.createdAt)
  }
}
