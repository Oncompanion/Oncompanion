// Co-authored-by: Claude Code <noreply@anthropic.com>
package com.github.se.oncompanion.model.event

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_CATEGORY
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_DESCRIPTION
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_END_AT
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_PLACE
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_START_AT
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_SUMMARY
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_TITLE
import com.github.se.oncompanion.model.event.EventRepositoryFirestore.Companion.FIELD_WEBSITE_URL
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [EventRepositoryFirestore] against the Firestore emulator. Who can read events is tested in
 * [EventSecurityRulesTest].
 *
 * `/events` is shared by every test run and emulator data is never wiped, so each test only looks
 * at the events it created (unique ids) and deletes them afterwards.
 */
@RunWith(AndroidJUnit4::class)
class EventRepositoryFirestoreTest {

  private val db
    get() = FirebaseEmulator.firestore

  // Whole seconds, so times read back from Firestore compare equal
  private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val repository = EventRepositoryFirestore(db, Clock.fixed(now, ZoneOffset.UTC), ::utc)
  private val runId = UUID.randomUUID().toString()
  private val created = mutableListOf<String>()

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    EmulatorTestData.createUser("sam") // events can only be read when signed in
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    created.forEach { EmulatorTestData.deleteRawDocument("$COLLECTION/$it") }
    FirebaseEmulator.auth.signOut()
  }

  /** A unique id for [name], so this run never sees another run's events. */
  private fun id(name: String) = "$name-$runId"

  /**
   * Writes an event bypassing the rules, like an event added from the Firebase console. A null
   * field is left out of the document.
   */
  private suspend fun addEvent(
      name: String,
      startAt: Any? = now.plus(Duration.ofDays(1)),
      endAt: Any? = null,
      title: Any? = "Gentle yoga",
      place: Any? = "Wellness center",
      category: Any? = null,
      summary: Any? = "Short summary",
      description: Any? = "Full description",
      websiteUrl: Any? = null,
  ) {
    val fields =
        mapOf(
                FIELD_TITLE to title,
                FIELD_PLACE to place,
                FIELD_START_AT to startAt,
                FIELD_END_AT to endAt,
                FIELD_CATEGORY to category,
                FIELD_SUMMARY to summary,
                FIELD_DESCRIPTION to description,
                FIELD_WEBSITE_URL to websiteUrl,
            )
            .filterValues { it != null }
    val json = JSONObject()
    fields.forEach { (key, value) -> json.put(key, restValue(value!!)) }
    withTimeout(10_000) {
      EmulatorTestData.createRawDocument(COLLECTION, id(name), json.toString())
    }
    created += id(name)
  }

  /** [value] in the Firestore REST format, e.g. {"stringValue": "Gentle yoga"}. */
  private fun restValue(value: Any): JSONObject =
      when (value) {
        is String -> JSONObject().put("stringValue", value)
        is Int -> JSONObject().put("integerValue", value.toString())
        is Instant -> JSONObject().put("timestampValue", value.toString())
        else -> error("Unsupported test value: $value")
      }

  /**
   * Waits until the upcoming events of this test are [expected], in this order. The first result of
   * a query can come from the local cache, which doesn't know yet the events written on the server
   * by [addEvent]: the server's answer follows right after.
   */
  private suspend fun assertUpcomingIds(
      expected: List<String>,
      repo: EventRepository = repository,
  ) {
    var last: List<String>? = null
    try {
      withTimeout(10_000) {
        repo
            .getUpcomingEvents()
            .map { events -> events.map { it.id }.filter { it in created } }
            .first { ids -> ids.also { last = it } == expected }
      }
    } catch (e: TimeoutCancellationException) {
      assertEquals("Upcoming events never matched", expected, last)
    }
  }

  /** Waits until the upcoming events include this test's event [name], and returns it. */
  private suspend fun awaitUpcomingEvent(name: String): Event =
      withTimeout(10_000) {
        repository
            .getUpcomingEvents()
            .map { events -> events.find { it.id == id(name) } }
            .first { it != null }!!
      }

  private fun at(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, utc())

  private fun utc(): ZoneId = ZoneOffset.UTC

  @Test
  fun constantsMatchTheFirestoreLayout() {
    assertEquals("events", COLLECTION)
    assertEquals("title", FIELD_TITLE)
    assertEquals("place", FIELD_PLACE)
    assertEquals("startAt", FIELD_START_AT)
    assertEquals("endAt", FIELD_END_AT)
    assertEquals("category", FIELD_CATEGORY)
    assertEquals("summary", FIELD_SUMMARY)
    assertEquals("description", FIELD_DESCRIPTION)
    assertEquals("websiteUrl", FIELD_WEBSITE_URL)
  }

  // ---- getUpcomingEvents ----

  @Test
  fun upcomingEvents_areSortedByDate_andPastOnesAreLeftOut(): Unit = runBlocking {
    addEvent("later", startAt = now.plus(Duration.ofDays(10)))
    addEvent("yesterday", startAt = now.minus(Duration.ofDays(1)))
    addEvent("tomorrow", startAt = now.plus(Duration.ofDays(1)))
    addEvent("right-now", startAt = now)

    assertUpcomingIds(listOf(id("right-now"), id("tomorrow"), id("later")))
  }

  @Test
  fun upcomingEvents_readAllFields_inTheGivenTimeZone(): Unit = runBlocking {
    val start = now.plus(Duration.ofDays(2))
    val end = start.plus(Duration.ofMinutes(90))
    addEvent(
        "yoga",
        startAt = start,
        endAt = end,
        title = "Gentle yoga for patients",
        place = "Wellness center, Building B",
        category = "Wellbeing",
        summary = "A relaxing session.",
        description = "A relaxing session adapted to people in treatment.",
        websiteUrl = "https://example.org/yoga",
    )

    val event = awaitUpcomingEvent("yoga")
    assertEquals(
        Event(
            id = id("yoga"),
            title = "Gentle yoga for patients",
            place = "Wellness center, Building B",
            startDateTime = at(start),
            endDateTime = at(end),
            category = "Wellbeing",
            summary = "A relaxing session.",
            description = "A relaxing session adapted to people in treatment.",
            websiteUrl = "https://example.org/yoga",
        ),
        event,
    )
  }

  @Test
  fun eventsWithoutOptionalFields_areRead(): Unit = runBlocking {
    addEvent("walk")

    val event = repository.getEvent(id("walk"))!!
    assertNull(event.endDateTime)
    assertNull(event.category)
    assertNull(event.websiteUrl)
  }

  @Test
  fun websiteLinksThatAreNotWebAddresses_areLeftOut(): Unit = runBlocking {
    addEvent("app-link", websiteUrl = "intent://scan/#Intent;end")
    addEvent("typo", websiteUrl = "example.org/yoga")

    val appLink = repository.getEvent(id("app-link"))!!
    val typo = repository.getEvent(id("typo"))!!
    assertNull(appLink.websiteUrl)
    assertNull(typo.websiteUrl)
  }

  @Test
  fun websiteLinkOfTheWrongType_isLeftOut_andTheEventKept(): Unit = runBlocking {
    addEvent("number-link", websiteUrl = 42)

    val event = repository.getEvent(id("number-link"))
    assertNotNull(event)
    assertNull(event!!.websiteUrl)
  }

  @Test
  fun malformedEvents_areSkipped(): Unit = runBlocking {
    addEvent("ok")
    addEvent("no-title", title = null)
    addEvent("number-title", title = 3)
    addEvent("no-summary", summary = null)
    addEvent("wrong-end", endAt = "tomorrow")

    assertUpcomingIds(listOf(id("ok")))
  }

  @Test
  fun upcomingEvents_emitsChanges(): Unit = runBlocking {
    val emissions = Channel<List<String>>(Channel.UNLIMITED)
    val job =
        launch(Dispatchers.IO) {
          repository.getUpcomingEvents().collect { events ->
            emissions.send(events.map { it.id }.filter { it.endsWith(runId) })
          }
        }
    try {
      withTimeout(10_000) {
        assertEquals(emptyList<String>(), emissions.receive())

        addEvent("support-group")
        var current = emissions.receive()
        while (current.isEmpty()) current = emissions.receive()
        assertEquals(listOf(id("support-group")), current)
      }
    } finally {
      job.cancel()
    }
  }

  @Test
  fun offline_readsTheCachedEvents(): Unit = runBlocking {
    addEvent("workshop")
    assertUpcomingIds(listOf(id("workshop"))) // loads it into the cache

    db.disableNetwork().await()
    assertEquals(
        listOf(id("workshop")),
        withTimeout(10_000) { repository.getUpcomingEvents().first() }
            .map { it.id }
            .filter { it in created },
    )
    assertEquals(id("workshop"), withTimeout(10_000) { repository.getEvent(id("workshop")) }?.id)
  }

  @Test
  fun defaultConstructor_usesTheSharedInstance(): Unit = runBlocking {
    addEvent("walk")
    assertUpcomingIds(listOf(id("walk")), EventRepositoryFirestore())
  }

  // ---- getEvent ----

  @Test
  fun getEvent_returnsThePastEventsToo(): Unit = runBlocking {
    // A past event can still be opened, e.g. from a link or a list loaded before it started
    addEvent("yesterday", startAt = now.minus(Duration.ofDays(1)))
    assertEquals(id("yesterday"), withTimeout(10_000) { repository.getEvent(id("yesterday")) }?.id)
  }

  @Test
  fun getEvent_returnsNull_forAMissingEvent(): Unit = runBlocking {
    assertNull(withTimeout(10_000) { repository.getEvent(id("missing")) })
  }

  @Test
  fun getEvent_returnsNull_forAMalformedEvent(): Unit = runBlocking {
    addEvent("no-start", startAt = null)
    assertNull(withTimeout(10_000) { repository.getEvent(id("no-start")) })
  }
}
