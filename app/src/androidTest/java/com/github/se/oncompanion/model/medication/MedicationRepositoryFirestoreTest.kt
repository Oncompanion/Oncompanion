package com.github.se.oncompanion.model.medication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

  /** Waits for the first value of the stream that satisfies [predicate]. */
  private suspend fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean = { true }): T =
      withTimeout(10.seconds) { first { predicate(it) } }

  private fun singleMedication(
      id: String,
      prescribedOn: LocalDate,
      name: String = "Paracetamol 1 g",
      startDate: LocalDate = prescribedOn,
  ) =
      Prescription(
          id = id,
          prescribedOn = prescribedOn,
          medications =
              listOf(
                  Medication(
                      id = "$id-med",
                      prescriptionId = id,
                      name = name,
                      startDate = startDate,
                  )
              ),
      )

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

  // ---- observe prescriptions ----

  @Test
  fun observePrescriptionsEmitsAnEmptyListWhenThereAreNone(): Unit = runBlocking {
    assertEquals(emptyList<Prescription>(), repository.observePrescriptions(aliceUid).awaitFirst())
  }

  @Test
  fun observePrescriptionsEmitsPrescriptionsWithTheirMedications(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    val observed = repository.observePrescriptions(aliceUid).awaitFirst { it.isNotEmpty() }

    assertSameContent(prescription(), observed.single())
    assertNotNull(observed.single().createdAt)
  }

  @Test
  fun observePrescriptionsEmitsAgainWhenOneIsAdded(): Unit = runBlocking {
    val stream = repository.observePrescriptions(aliceUid)
    assertTrue(stream.awaitFirst().isEmpty())

    repository.addPrescription(aliceUid, prescription())

    assertEquals(listOf("presc-1"), stream.awaitFirst { it.isNotEmpty() }.map { it.id })
  }

  @Test
  fun observePrescriptionsNeverEmitsAPrescriptionWithoutItsMedications(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())

    val observed = repository.observePrescriptions(aliceUid).awaitFirst { it.isNotEmpty() }

    assertEquals(2, observed.single().medications.size)
  }

  @Test
  fun observePrescriptionsOrdersMostRecentlyPrescribedFirst(): Unit = runBlocking {
    repository.addPrescription(aliceUid, singleMedication("august", LocalDate.of(2026, 8, 20)))
    repository.addPrescription(aliceUid, singleMedication("september", september29))
    repository.addPrescription(aliceUid, singleMedication("mid", LocalDate.of(2026, 9, 12)))
    awaitServerAck()

    val observed = repository.observePrescriptions(aliceUid).awaitFirst { it.size == 3 }

    assertEquals(listOf("september", "mid", "august"), observed.map { it.id })
  }

  @Test
  fun observePrescriptionsOrdersSameDayByMostRecentlySaved(): Unit = runBlocking {
    repository.addPrescription(aliceUid, singleMedication("saved-first", september29))
    awaitServerAck()
    repository.addPrescription(aliceUid, singleMedication("saved-second", september29))
    awaitServerAck()
    // Saved offline: not confirmed by the server yet, so it counts as the most recent
    db.disableNetwork().await()
    repository.addPrescription(aliceUid, singleMedication("pending", september29))

    val observed = repository.observePrescriptions(aliceUid).awaitFirst { it.size == 3 }

    assertEquals(listOf("pending", "saved-second", "saved-first"), observed.map { it.id })
  }

  @Test
  fun observePrescriptionsSkipsMalformedDocuments(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    // A prescription without a date, and a medication of presc-1 without a name
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "no-date",
        """{"prescribedBy": {"stringValue": "Dr. Nobody"}}""",
    )
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/medications",
        "no-name",
        """{"prescriptionId": {"stringValue": "presc-1"}, "position": {"integerValue": "2"}}""",
    )
    // A valid one written afterwards tells us the stream has seen the malformed documents too
    repository.addPrescription(aliceUid, singleMedication("after", LocalDate.of(2026, 9, 30)))
    awaitServerAck()

    val observed = repository.observePrescriptions(aliceUid).awaitFirst { it.size >= 2 }

    assertEquals(listOf("after", "presc-1"), observed.map { it.id })
    assertEquals(2, observed.last().medications.size)
  }

  @Test
  fun observePrescriptionsOfAnotherUserEndsWithPermissionDenied(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    EmulatorTestData.createUser("bob")

    try {
      // Collected until it fails: the stream may first emit what this device has cached
      withTimeout(10.seconds) { repository.observePrescriptions(aliceUid).collect {} }
      fail("Expected FirebaseFirestoreException")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- observe medications ----

  @Test
  fun observeMedicationsEmitsAnEmptyListWhenThereAreNone(): Unit = runBlocking {
    assertEquals(emptyList<Medication>(), repository.observeMedications(aliceUid).awaitFirst())
  }

  @Test
  fun observeMedicationsEmitsEveryMedicationEarliestStartFirstThenByName(): Unit = runBlocking {
    // presc-1: Ondansetron starts on 1 Oct, Dexamethasone on 29 Sep
    repository.addPrescription(aliceUid, prescription())
    repository.addPrescription(
        aliceUid,
        singleMedication("other", september29, name = "Amoxicillin", startDate = september29),
    )
    repository.addPrescription(
        aliceUid,
        singleMedication("old", LocalDate.of(2026, 9, 12), name = "Zolpidem"),
    )
    awaitServerAck()

    val observed = repository.observeMedications(aliceUid).awaitFirst { it.size == 4 }

    assertEquals(
        listOf("Zolpidem", "Amoxicillin", "Dexamethasone 4 mg", "Ondansetron 8 mg"),
        observed.map { it.name },
    )
    assertEquals(prescription().medications.first(), observed.last())
  }

  @Test
  fun observeMedicationsEmitsAgainWhenAPrescriptionIsAdded(): Unit = runBlocking {
    val stream = repository.observeMedications(aliceUid)
    assertTrue(stream.awaitFirst().isEmpty())

    repository.addPrescription(aliceUid, prescription())

    assertEquals(2, stream.awaitFirst { it.isNotEmpty() }.size)
  }

  // ---- update ----

  /** presc-1 edited: new doctor and date, Dexamethasone changed and now first, Ondansetron gone. */
  private fun edited() =
      prescription()
          .copy(
              prescribedBy = "Dr. Leroy",
              prescribedOn = LocalDate.of(2026, 9, 30),
              medications =
                  listOf(
                      Medication(
                          id = "presc-1-dexamethasone",
                          prescriptionId = "presc-1",
                          name = "Dexamethasone 8 mg",
                          dosage = "2 tablets",
                          startDate = LocalDate.of(2026, 10, 2),
                          durationDays = 3,
                      ),
                      Medication(
                          id = "presc-1-new",
                          prescriptionId = "presc-1",
                          name = "Metoclopramide 10 mg",
                          startDate = september29,
                      ),
                  ),
          )

  @Test
  fun updateReplacesTheFieldsAndTheMedications(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    repository.updatePrescription(aliceUid, edited())

    assertSameContent(edited(), repository.getPrescription(aliceUid, "presc-1"))
    awaitServerAck()
    assertSameContent(edited(), repository.getPrescription(aliceUid, "presc-1"))
  }

  @Test
  fun updateDeletesTheMedicationsLeftOut(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    repository.updatePrescription(aliceUid, edited())
    awaitServerAck()

    assertFalse(serverSnapshot("medications", "presc-1-ondansetron").exists())
    assertEquals(0L, serverSnapshot("medications", "presc-1-dexamethasone").getLong("position"))
    assertEquals(1L, serverSnapshot("medications", "presc-1-new").getLong("position"))
  }

  @Test
  fun updateKeepsCreatedAt(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    val createdAt = repository.getPrescription(aliceUid, "presc-1")!!.createdAt
    assertNotNull(createdAt)

    repository.updatePrescription(aliceUid, edited().copy(createdAt = Instant.EPOCH))
    awaitServerAck()

    assertEquals(createdAt, repository.getPrescription(aliceUid, "presc-1")!!.createdAt)
  }

  @Test
  fun updateLeavesOtherPrescriptionsUntouched(): Unit = runBlocking {
    val other = singleMedication("other", LocalDate.of(2026, 9, 12))
    repository.addPrescription(aliceUid, prescription())
    repository.addPrescription(aliceUid, other)
    awaitServerAck()

    repository.updatePrescription(aliceUid, edited())
    awaitServerAck()

    assertSameContent(other, repository.getPrescription(aliceUid, "other"))
  }

  @Test
  fun updateUnknownPrescriptionDoesNothing(): Unit = runBlocking {
    repository.updatePrescription(aliceUid, edited())
    awaitServerAck()

    assertNull(repository.getPrescription(aliceUid, "presc-1"))
    assertFalse(serverSnapshot("prescriptions", "presc-1").exists())
    assertFalse(serverSnapshot("medications", "presc-1-new").exists())
  }

  @Test
  fun updateInvalidPrescriptionThrowsAndKeepsTheStoredOne(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    try {
      repository.updatePrescription(aliceUid, edited().copy(medications = emptyList()))
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
    awaitServerAck()

    assertSameContent(prescription(), repository.getPrescription(aliceUid, "presc-1"))
  }

  @Test
  fun observePrescriptionsEmitsTheUpdate(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    val stream = repository.observePrescriptions(aliceUid)
    stream.awaitFirst { it.isNotEmpty() }

    repository.updatePrescription(aliceUid, edited())

    val updated = stream.awaitFirst { list ->
      list.singleOrNull()?.let {
        it.prescribedBy == "Dr. Leroy" && it.medications.map { m -> m.id }.last() == "presc-1-new"
      } == true
    }
    assertSameContent(edited(), updated.single())
  }

  // ---- delete ----

  @Test
  fun deleteRemovesThePrescriptionAndItsMedications(): Unit = runBlocking {
    val other = singleMedication("other", LocalDate.of(2026, 9, 12))
    repository.addPrescription(aliceUid, prescription())
    repository.addPrescription(aliceUid, other)
    awaitServerAck()

    repository.deletePrescription(aliceUid, "presc-1")

    assertNull(repository.getPrescription(aliceUid, "presc-1"))
    awaitServerAck()
    assertFalse(serverSnapshot("prescriptions", "presc-1").exists())
    assertFalse(serverSnapshot("medications", "presc-1-ondansetron").exists())
    assertFalse(serverSnapshot("medications", "presc-1-dexamethasone").exists())
    // The other prescription is untouched
    assertSameContent(other, repository.getPrescription(aliceUid, "other"))
    assertTrue(serverSnapshot("medications", "other-med").exists())
  }

  @Test
  fun deleteUnknownPrescriptionDoesNothing(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    repository.deletePrescription(aliceUid, "nothing")
    awaitServerAck()

    assertSameContent(prescription(), repository.getPrescription(aliceUid, "presc-1"))
  }

  @Test
  fun streamsEmitTheDeletion(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    val prescriptions = repository.observePrescriptions(aliceUid)
    val medications = repository.observeMedications(aliceUid)
    prescriptions.awaitFirst { it.isNotEmpty() }

    repository.deletePrescription(aliceUid, "presc-1")

    assertTrue(prescriptions.awaitFirst { it.isEmpty() }.isEmpty())
    assertTrue(medications.awaitFirst { it.isEmpty() }.isEmpty())
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

  @Test
  fun getUnknownPrescriptionOfflineReturnsNull(): Unit = runBlocking {
    db.disableNetwork().await()

    assertNull(withTimeout(5.seconds) { repository.getPrescription(aliceUid, "nothing") })
  }

  @Test
  fun updateAndDeleteWorkOffline(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    db.disableNetwork().await()

    withTimeout(5.seconds) { repository.updatePrescription(aliceUid, edited()) }
    assertSameContent(
        edited(),
        withTimeout(5.seconds) { repository.getPrescription(aliceUid, "presc-1") },
    )

    withTimeout(5.seconds) { repository.deletePrescription(aliceUid, "presc-1") }
    assertNull(withTimeout(5.seconds) { repository.getPrescription(aliceUid, "presc-1") })
  }

  @Test
  fun updateAndDeleteOfUnknownPrescriptionDoNothingOffline(): Unit = runBlocking {
    db.disableNetwork().await()

    withTimeout(5.seconds) { repository.updatePrescription(aliceUid, edited()) }
    withTimeout(5.seconds) { repository.deletePrescription(aliceUid, "presc-1") }

    assertTrue(repository.observePrescriptions(aliceUid).awaitFirst().isEmpty())
  }

  @Test
  fun streamsEmitOfflineWrites(): Unit = runBlocking {
    db.disableNetwork().await()

    repository.addPrescription(aliceUid, prescription())

    val prescriptions = repository.observePrescriptions(aliceUid).awaitFirst { it.isNotEmpty() }
    assertSameContent(prescription(), prescriptions.single())
    assertEquals(2, repository.observeMedications(aliceUid).awaitFirst { it.isNotEmpty() }.size)
  }
}
