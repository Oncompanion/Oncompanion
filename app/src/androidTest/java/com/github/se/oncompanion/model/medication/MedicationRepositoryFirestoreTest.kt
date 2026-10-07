package com.github.se.oncompanion.model.medication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.Timestamp
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

  /** 29 September at midnight in Switzerland, as a Firestore REST value. */
  private val rawDay = """{"timestampValue": "2026-09-28T22:00:00Z"}"""

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

  private fun prescriptionDocument(id: String) =
      db.collection("users").document(aliceUid).collection("prescriptions").document(id)

  /** Reads the document of alice's prescription [id] from the server. */
  private suspend fun serverPrescription(id: String): DocumentSnapshot =
      withTimeout(10.seconds) { prescriptionDocument(id).get(Source.SERVER).await() }

  /** The medications stored in the document of prescription [id] on the server, in order. */
  @Suppress("UNCHECKED_CAST")
  private suspend fun serverMedications(id: String): List<Map<String, Any?>> =
      (serverPrescription(id).get("medications") as List<Map<String, Any?>>?).orEmpty()

  private suspend fun serverMedicationIds(id: String) = serverMedications(id).map { it["id"] }

  /**
   * The REST fields of a prescription document dated 29 September, holding one medication per (ID,
   * name) pair. A `null` name gives a medication without a name, which is malformed.
   */
  private fun rawPrescription(vararg medications: Pair<String, String?>): String =
      rawPrescriptionOf(*medications.map { (id, name) -> rawMedication(id, name) }.toTypedArray())

  /** Like [rawPrescription], from entries built with [rawMedication]. */
  private fun rawPrescriptionOf(vararg medications: String): String =
      """{"prescribedOn": $rawDay, "createdAt": $rawDay,
        "medications": {"arrayValue": {"values": [${medications.joinToString()}]}}}"""

  /**
   * The REST value of one medication of a prescription document, starting on 29 September.
   * [durationDays] is the REST value of its duration, e.g. `{"integerValue": "5"}`.
   */
  private fun rawMedication(id: String, name: String?, durationDays: String? = null): String {
    val nameField = name?.let { """"name": {"stringValue": "$it"},""" }.orEmpty()
    val durationField = durationDays?.let { """"durationDays": $it,""" }.orEmpty()
    return """{"mapValue": {"fields": {"id": {"stringValue": "$id"}, $nameField $durationField
        "startDate": $rawDay}}}"""
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

    assertFalse(serverPrescription("presc-1").exists())
  }

  @Test
  fun addWritesOneDocumentHoldingTheMedications(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    val prescriptionDoc = serverPrescription("presc-1")
    assertEquals("Dr. Martin · Oncology", prescriptionDoc.getString("prescribedBy"))
    assertNotNull(prescriptionDoc.getTimestamp("prescribedOn"))
    assertNotNull(prescriptionDoc.getTimestamp("createdAt"))

    // The medications are a list inside the document, in the order they were saved
    assertEquals(
        listOf("presc-1-ondansetron", "presc-1-dexamethasone"),
        serverMedicationIds("presc-1"),
    )
    assertEquals(
        mapOf(
            "id" to "presc-1-ondansetron",
            "name" to "Ondansetron 8 mg",
            "dosage" to "1 tablet",
            "frequency" to "Twice a day",
            "startDate" to Timestamp(Instant.parse("2026-09-30T22:00:00Z")),
            "durationDays" to 5L,
        ),
        serverMedications("presc-1").first(),
    )
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
        serverPrescription("presc-1").getTimestamp("prescribedOn")!!.toInstant(),
    )
    assertEquals(
        Timestamp(Instant.parse("2026-01-14T23:00:00Z")),
        serverMedications("presc-1").singleOrNull()?.get("startDate"),
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
    // A prescription without a date, one without medications, and one whose first medication has
    // no name
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "no-date",
        """{"prescribedBy": {"stringValue": "Dr. Nobody"}}""",
    )
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "no-medications",
        rawPrescription(),
    )
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "no-name",
        rawPrescription("no-name-med" to null, "named-med" to "Paracetamol 1 g"),
    )
    // A valid one written afterwards tells us the stream has seen the malformed documents too
    repository.addPrescription(aliceUid, singleMedication("after", LocalDate.of(2026, 9, 30)))
    awaitServerAck()

    val observed = repository.observePrescriptions(aliceUid).awaitFirst { it.size >= 3 }

    // Same day: presc-1 was saved after the creation time written in the raw document
    assertEquals(listOf("after", "presc-1", "no-name"), observed.map { it.id })
    assertEquals(listOf("named-med"), observed.last().medications.map { it.id })
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

  // ---- stored values the security rules don't check ----

  @Test
  fun medicationsWithAnInvalidIdOrNameAreLeftOut(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "mixed",
        rawPrescriptionOf(
            rawMedication("valid-1", "Paracetamol 1 g"),
            rawMedication("blank-name", "   "),
            rawMedication("long-name", "a".repeat(Medication.MAX_TEXT_LENGTH + 1)),
            rawMedication(" ", "Blank ID"),
            rawMedication("valid-2", "a".repeat(Medication.MAX_TEXT_LENGTH)),
        ),
    )

    val stored = repository.getPrescription(aliceUid, "mixed")

    assertEquals(listOf("valid-1", "valid-2"), stored?.medications?.map { it.id })
  }

  @Test
  fun medicationsWithAnInvalidDurationAreLeftOut(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "mixed",
        rawPrescriptionOf(
            rawMedication("one-day", "Paracetamol 1 g", """{"integerValue": "1"}"""),
            rawMedication("zero", "Paracetamol 1 g", """{"integerValue": "0"}"""),
            rawMedication("negative", "Paracetamol 1 g", """{"integerValue": "-3"}"""),
            rawMedication("too-long", "Paracetamol 1 g", """{"integerValue": "3651"}"""),
            rawMedication("decimal", "Paracetamol 1 g", """{"doubleValue": 1.5}"""),
            // 2^32 + 5: would read as 5 days if it were cut down to an Int
            rawMedication("too-big", "Paracetamol 1 g", """{"integerValue": "4294967301"}"""),
            rawMedication("text", "Paracetamol 1 g", """{"stringValue": "5"}"""),
            rawMedication("max", "Paracetamol 1 g", """{"integerValue": "3650"}"""),
            rawMedication("no-end", "Paracetamol 1 g"),
        ),
    )

    val stored = repository.getPrescription(aliceUid, "mixed")

    assertEquals(listOf("one-day", "max", "no-end"), stored?.medications?.map { it.id })
    assertEquals(listOf(1, 3650, null), stored?.medications?.map { it.durationDays })
  }

  @Test
  fun aMedicationIdRepeatedInAPrescriptionIsReadOnce(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "repeated",
        rawPrescription(
            "twice" to "Ondansetron 8 mg",
            "once" to "Dexamethasone 4 mg",
            "twice" to "Zolpidem",
        ),
    )

    val stored = repository.getPrescription(aliceUid, "repeated")

    // The first one is kept
    assertEquals(listOf("twice", "once"), stored?.medications?.map { it.id })
    assertEquals("Ondansetron 8 mg", stored?.medications?.first()?.name)
  }

  @Test
  fun aPrescriptionWithOnlyInvalidMedicationsIsLeftOut(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/prescriptions",
        "all-invalid",
        rawPrescriptionOf(
            rawMedication("blank-name", " "),
            rawMedication("zero", "Paracetamol 1 g", """{"integerValue": "0"}"""),
        ),
    )
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()

    assertNull(repository.getPrescription(aliceUid, "all-invalid"))
    val observed = repository.observeMedications(aliceUid).awaitFirst { it.isNotEmpty() }
    assertEquals(
        prescription().medications.map { it.id }.sorted(),
        observed.map { it.id }.sorted(),
    )
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

    assertEquals(listOf("presc-1-dexamethasone", "presc-1-new"), serverMedicationIds("presc-1"))
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
    assertFalse(serverPrescription("presc-1").exists())
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
    assertFalse(serverPrescription("presc-1").exists())
    // The other prescription is untouched
    assertSameContent(other, repository.getPrescription(aliceUid, "other"))
    assertEquals(
        listOf("other-med"),
        repository.observeMedications(aliceUid).awaitFirst { it.size == 1 }.map { it.id },
    )
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
  fun deleteOfUnknownPrescriptionDoesNothingOffline(): Unit = runBlocking {
    db.disableNetwork().await()

    withTimeout(5.seconds) { repository.deletePrescription(aliceUid, "presc-1") }

    assertTrue(repository.observePrescriptions(aliceUid).awaitFirst().isEmpty())
  }

  @Test
  fun offlineUpdateOfAPrescriptionThisDeviceNeverLoadedReplacesAllItsMedications(): Unit =
      runBlocking {
        // presc-1 exists on the server with two medications, but this device has never loaded it
        EmulatorTestData.createRawDocument(
            "users/$aliceUid/prescriptions",
            "presc-1",
            rawPrescription(
                "presc-1-dexamethasone" to "Dexamethasone 4 mg",
                "presc-1-never-seen" to "Zolpidem",
            ),
        )
        db.disableNetwork().await()

        withTimeout(5.seconds) { repository.updatePrescription(aliceUid, edited()) }
        db.enableNetwork().await()
        awaitServerAck()

        assertSameContent(edited(), repository.getPrescription(aliceUid, "presc-1"))
        assertEquals(listOf("presc-1-dexamethasone", "presc-1-new"), serverMedicationIds("presc-1"))
      }

  @Test
  fun offlineDeleteOfAPrescriptionThisDeviceNeverLoadedRemovesAllItsMedications(): Unit =
      runBlocking {
        EmulatorTestData.createRawDocument(
            "users/$aliceUid/prescriptions",
            "presc-1",
            rawPrescription(
                "presc-1-dexamethasone" to "Dexamethasone 4 mg",
                "presc-1-never-seen" to "Zolpidem",
            ),
        )
        db.disableNetwork().await()

        withTimeout(5.seconds) { repository.deletePrescription(aliceUid, "presc-1") }
        db.enableNetwork().await()
        awaitServerAck()

        assertFalse(serverPrescription("presc-1").exists())
        assertTrue(repository.observeMedications(aliceUid).awaitFirst().isEmpty())
      }

  @Test
  fun offlineUpdateFromAnOutdatedCopyLeavesNoMedicationAddedElsewhere(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    db.disableNetwork().await()
    // Another device adds a medication that this one, offline, never sees
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/prescriptions",
        "presc-1",
        rawPrescription(
            "presc-1-ondansetron" to "Ondansetron 8 mg",
            "presc-1-dexamethasone" to "Dexamethasone 4 mg",
            "presc-1-added-elsewhere" to "Zolpidem",
        ),
    )

    withTimeout(5.seconds) { repository.updatePrescription(aliceUid, edited()) }
    db.enableNetwork().await()
    awaitServerAck()

    assertEquals(listOf("presc-1-dexamethasone", "presc-1-new"), serverMedicationIds("presc-1"))
  }

  @Test
  fun twoDevicesReplacingTheMedicationsEndWithTheLastListOnly(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    db.disableNetwork().await()
    // Both devices replace the medications of presc-1 before seeing each other's change
    withTimeout(5.seconds) { repository.updatePrescription(aliceUid, edited()) }
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/prescriptions",
        "presc-1",
        rawPrescription("other-device-1" to "Zolpidem", "other-device-2" to "Amoxicillin"),
    )

    // This device's change reaches the server last
    db.enableNetwork().await()
    awaitServerAck()

    assertEquals(listOf("presc-1-dexamethasone", "presc-1-new"), serverMedicationIds("presc-1"))
    assertEquals(
        listOf("presc-1-dexamethasone", "presc-1-new"),
        repository.observeMedications(aliceUid).awaitFirst { it.size == 2 }.map { it.id }.sorted(),
    )
  }

  @Test
  fun offlineUpdateOfAPrescriptionThatNeverExistedIsRejectedByTheServer(): Unit = runBlocking {
    db.disableNetwork().await()

    // The device can't tell whether presc-1 exists: the update is kept for the server to decide
    withTimeout(5.seconds) { repository.updatePrescription(aliceUid, edited()) }
    assertTrue(repository.observePrescriptions(aliceUid).awaitFirst().isEmpty())

    db.enableNetwork().await()
    awaitServerAck()
    assertFalse(serverPrescription("presc-1").exists())
    assertTrue(repository.observeMedications(aliceUid).awaitFirst { it.isEmpty() }.isEmpty())
  }

  @Test
  fun offlineUpdateOfAPrescriptionKnownAsDeletedWritesNothing(): Unit = runBlocking {
    repository.addPrescription(aliceUid, prescription())
    awaitServerAck()
    repository.deletePrescription(aliceUid, "presc-1")
    awaitServerAck()
    db.disableNetwork().await()

    withTimeout(5.seconds) { repository.updatePrescription(aliceUid, edited()) }

    // Nothing was written, not even on the device while waiting for the server
    assertTrue(repository.observeMedications(aliceUid).awaitFirst().isEmpty())
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
