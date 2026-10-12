// Co-authored-by: Claude Code <noreply@anthropic.com>
package com.github.se.oncompanion.model.event

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.util.UUID
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

/** Tests firestore.rules for /events/{eventId} through the client SDK. */
@RunWith(AndroidJUnit4::class)
class EventSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var uid: String

  // Emulator data is shared between runs: each test uses its own event
  private val eventId = "event-${UUID.randomUUID()}"

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    uid = EmulatorTestData.createUser("sam") // stays signed in
    // Events are added from the Firebase console, never by the app: written bypassing the rules
    EmulatorTestData.createRawDocument(
        COLLECTION,
        eventId,
        """{"title": {"stringValue": "Gentle yoga"}}""",
    )
  }

  @After
  fun tearDown(): Unit = runBlocking {
    EmulatorTestData.deleteRawDocument("$COLLECTION/$eventId")
    FirebaseEmulator.auth.signOut()
  }

  private fun events(): CollectionReference = db.collection(COLLECTION)

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10_000) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- read ----

  @Test
  fun signedInUserCanRead(): Unit = runBlocking {
    assertTrue(allowed(events().document(eventId).get(Source.SERVER)).exists())
    assertTrue(allowed(events().get(Source.SERVER)).documents.any { it.id == eventId })
  }

  @Test
  fun signedOutCannotRead(): Unit = runBlocking {
    EmulatorTestData.signOutAndWaitForFirestore(uid)
    denied(events().document(eventId).get(Source.SERVER))
    denied(events().get(Source.SERVER))
  }

  // ---- nobody writes from the app ----

  @Test
  fun signedInUserCannotWrite(): Unit = runBlocking {
    val event = events().document(eventId)
    denied(events().document("new-$eventId").set(mapOf("title" to "Fake event")))
    denied(event.update("title", "Changed"))
    denied(event.delete())
  }

  private companion object {
    const val COLLECTION = "events"
  }
}
