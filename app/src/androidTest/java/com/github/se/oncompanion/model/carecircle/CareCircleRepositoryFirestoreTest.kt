package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_ADDED_AT
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_EMAIL
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_FAMILY_NAME
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_FIRST_NAME
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_PERMISSIONS
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_RELATIONSHIP
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [CareCircleRepositoryFirestore] against the Firestore emulator. Who can read the circle is
 * tested in [CareCircleSecurityRulesTest].
 */
@RunWith(AndroidJUnit4::class)
class CareCircleRepositoryFirestoreTest {

  private lateinit var repository: CareCircleRepositoryFirestore
  private lateinit var aliceUid: String

  private val db
    get() = FirebaseEmulator.firestore

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    repository = CareCircleRepositoryFirestore(db)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  /**
   * Writes a member to alice's circle, bypassing the rules: clients can't write to the circle until
   * adding a member exists.
   */
  private suspend fun addMember(
      uid: String,
      firstName: String?,
      familyName: String? = null,
      relationship: String? = null,
      permissions: Any? = null,
      email: Any? = null,
      addedAt: Any? = null,
  ) {
    val fields =
        mapOf(
                FIELD_FIRST_NAME to firstName,
                FIELD_FAMILY_NAME to familyName,
                FIELD_RELATIONSHIP to relationship,
                FIELD_PERMISSIONS to permissions,
                FIELD_EMAIL to email,
                FIELD_ADDED_AT to addedAt,
            )
            .filterValues { it != null }
    val json = JSONObject()
    fields.forEach { (key, value) -> json.put(key, restValue(value!!)) }
    withTimeout(10_000) {
      EmulatorTestData.createRawDocument("users/$aliceUid/$COLLECTION", uid, json.toString())
    }
  }

  /** [value] in the Firestore REST format, e.g. {"stringValue": "Sophie"}. */
  private fun restValue(value: Any): JSONObject =
      when (value) {
        is String -> JSONObject().put("stringValue", value)
        is Int -> JSONObject().put("integerValue", value.toString())
        is Instant -> JSONObject().put("timestampValue", value.toString())
        is List<*> ->
            JSONObject()
                .put(
                    "arrayValue",
                    JSONObject().put("values", JSONArray(value.map { restValue(it!!) })),
                )
        else -> error("Unsupported test value: $value")
      }

  private suspend fun firstMembers(): List<CareCircleMember> =
      withTimeout(10_000) { repository.observeMembers(aliceUid).first() }

  private suspend fun firstMember(uid: String): CareCircleMember? =
      withTimeout(10_000) { repository.observeMember(aliceUid, uid).first() }

  @Test
  fun constantsMatchTheFirestoreLayout() {
    assertEquals("circle", COLLECTION)
    assertEquals("firstName", FIELD_FIRST_NAME)
    assertEquals("familyName", FIELD_FAMILY_NAME)
    assertEquals("relationship", FIELD_RELATIONSHIP)
    assertEquals("permissions", FIELD_PERMISSIONS)
    assertEquals("email", FIELD_EMAIL)
    assertEquals("addedAt", FIELD_ADDED_AT)
  }

  @Test
  fun emptyCircle_emitsEmptyList(): Unit = runBlocking {
    assertEquals(emptyList<CareCircleMember>(), firstMembers())
  }

  @Test
  fun defaultConstructorUsesSharedInstance(): Unit = runBlocking {
    addMember("sophie", "Sophie")
    val members =
        withTimeout(10_000) { CareCircleRepositoryFirestore().observeMembers(aliceUid).first() }
    assertEquals(listOf("sophie"), members.map { it.uid })
  }

  @Test
  fun membersAreReadWithAllFields_andSortedByName(): Unit = runBlocking {
    addMember(
        "sophie",
        "Sophie",
        "Dubois",
        "WIFE",
        CarePermission.entries.map { it.name },
        email = SOPHIE_EMAIL,
        addedAt = ADDED_AT,
    )
    addMember("marc", "Marc", "Dubois", "SON", listOf("PLANNING", "EVENTS"))
    addMember("laura", "laura", null, "HOME_NURSE", listOf("SYMPTOMS"))

    assertEquals(
        listOf(
            CareCircleMember(
                "laura",
                "laura",
                null,
                Relationship.HOME_NURSE,
                setOf(CarePermission.SYMPTOMS),
            ),
            CareCircleMember(
                "marc",
                "Marc",
                "Dubois",
                Relationship.SON,
                setOf(CarePermission.PLANNING, CarePermission.EVENTS),
            ),
            CareCircleMember(
                "sophie",
                "Sophie",
                "Dubois",
                Relationship.WIFE,
                CarePermission.entries.toSet(),
                SOPHIE_EMAIL,
                ADDED_AT,
            ),
        ),
        firstMembers(),
    )
  }

  @Test
  fun membersWithTheSameName_areSortedByUid(): Unit = runBlocking {
    addMember("b", "Sophie", "Dubois")
    addMember("c", "SOPHIE", "dubois")
    addMember("a", "Sophie", "Dubois")

    assertEquals(listOf("a", "b", "c"), firstMembers().map { it.uid })
  }

  @Test
  fun malformedFields_fallBackToSafeDefaults(): Unit = runBlocking {
    addMember("a", "Ann", relationship = "COUSIN", permissions = listOf("EDIT", "EVENTS", 3))
    addMember("b", "Bob", permissions = "PLANNING", email = 3, addedAt = "yesterday")

    val members = firstMembers()
    assertEquals(Relationship.OTHER, members[0].relationship)
    assertEquals(setOf(CarePermission.EVENTS), members[0].permissions)
    assertEquals(Relationship.OTHER, members[1].relationship)
    assertTrue(members[1].permissions.isEmpty())
    assertNull(members[1].email)
    assertNull(members[1].addedAt)
  }

  @Test
  fun membersWithoutFirstName_areSkipped(): Unit = runBlocking {
    addMember("ok", "Sophie")
    addMember("missing", null, familyName = "Dubois")
    addMember("blank", "  ")

    assertEquals(listOf("ok"), firstMembers().map { it.uid })
  }

  @Test
  fun observeMembers_emitsChanges(): Unit = runBlocking {
    val emissions = Channel<List<CareCircleMember>>(Channel.UNLIMITED)
    val job =
        launch(Dispatchers.IO) { repository.observeMembers(aliceUid).collect(emissions::send) }
    try {
      withTimeout(10_000) {
        assertEquals(emptyList<CareCircleMember>(), emissions.receive())

        addMember("sophie", "Sophie")
        var current = emissions.receive()
        while (current.isEmpty()) current = emissions.receive()
        assertEquals(listOf("sophie"), current.map { it.uid })

        addMember("marc", "Marc")
        while (current.size < 2) current = emissions.receive()
        assertEquals(listOf("marc", "sophie"), current.map { it.uid })
      }
    } finally {
      job.cancel()
    }
  }

  @Test
  fun offline_readsTheCachedCircle(): Unit = runBlocking {
    addMember("sophie", "Sophie")
    assertEquals(listOf("sophie"), firstMembers().map { it.uid }) // loads it into the cache

    db.disableNetwork().await()
    try {
      assertEquals(listOf("sophie"), firstMembers().map { it.uid })
    } finally {
      db.enableNetwork().await()
    }
  }

  // ---- observeMember ----

  @Test
  fun observeMember_readsAllFields(): Unit = runBlocking {
    addMember("marc", "Marc", "Dubois", "SON", listOf("PLANNING", "EVENTS"), "marc@x.ch", ADDED_AT)

    assertEquals(
        CareCircleMember(
            "marc",
            "Marc",
            "Dubois",
            Relationship.SON,
            setOf(CarePermission.PLANNING, CarePermission.EVENTS),
            "marc@x.ch",
            ADDED_AT,
        ),
        firstMember("marc"),
    )
  }

  @Test
  fun observeMember_missingMember_emitsNull(): Unit = runBlocking {
    addMember("sophie", "Sophie")
    assertNull(firstMember("marc"))
  }

  @Test
  fun observeMember_withoutFirstName_emitsNull(): Unit = runBlocking {
    addMember("blank", "  ", familyName = "Dubois")
    assertNull(firstMember("blank"))
  }

  @Test
  fun observeMember_emitsWhenAddedThenRemoved(): Unit = runBlocking {
    val emissions = Channel<CareCircleMember?>(Channel.UNLIMITED)
    val job =
        launch(Dispatchers.IO) {
          repository.observeMember(aliceUid, "marc").collect(emissions::send)
        }
    try {
      withTimeout(10_000) {
        assertNull(emissions.receive())

        addMember("marc", "Marc")
        var current = emissions.receive()
        while (current == null) current = emissions.receive()
        assertEquals("Marc", current?.firstName)

        EmulatorTestData.deleteRawDocument("users/$aliceUid/$COLLECTION/marc")
        while (current != null) current = emissions.receive()
      }
    } finally {
      job.cancel()
    }
  }

  @Test
  fun observeMember_offline_readsTheCachedMember(): Unit = runBlocking {
    addMember("marc", "Marc")
    assertEquals("marc", firstMember("marc")?.uid) // loads it into the cache

    db.disableNetwork().await()
    try {
      assertEquals("marc", firstMember("marc")?.uid)
    } finally {
      db.enableNetwork().await()
    }
  }

  private companion object {
    const val SOPHIE_EMAIL = "sophie.dubois@example.com"
    val ADDED_AT: Instant = Instant.parse("2026-08-14T09:30:00Z")
  }
}
