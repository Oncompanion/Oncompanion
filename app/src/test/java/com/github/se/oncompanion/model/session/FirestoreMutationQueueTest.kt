// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Unknown cache formats must never authorize silent destruction of pending legacy writes. */
@RunWith(AndroidJUnit4::class)
class FirestoreMutationQueueTest {
  private val file: File =
      ApplicationProvider.getApplicationContext<Context>()
          .getDatabasePath("mutation-check-${UUID.randomUUID()}")

  @After
  fun cleanUp() {
    SQLiteDatabase.deleteDatabase(file)
  }

  @Test
  fun freshInstallationHasNoLegacyQueues() {
    assertEquals(emptySet<String>(), FirestoreMutationQueue.owners(file))
  }

  @Test
  fun unknownSchemaCannotAuthorizeCleanupEvenWithAnEmptyMutationTable() {
    SQLiteDatabase.openOrCreateDatabase(file, null).use {
      it.version = 19
      it.execSQL("CREATE TABLE mutations (uid TEXT, batch_id INTEGER, mutations BLOB)")
    }
    assertThrows(IllegalStateException::class.java) { FirestoreMutationQueue.owners(file) }
  }

  @Test
  fun missingMutationTableFailsClosed() {
    SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 18 }
    assertThrows(android.database.sqlite.SQLiteException::class.java) {
      FirestoreMutationQueue.owners(file)
    }
  }

  @Test
  fun corruptCacheInspectionCannotDeleteOrChangeTheOriginalFile() {
    file.parentFile!!.mkdirs()
    val original = "unrecognized database contents".toByteArray()
    file.writeBytes(original)
    assertThrows(android.database.sqlite.SQLiteException::class.java) {
      FirestoreMutationQueue.owners(file)
    }
    assertArrayEquals(original, file.readBytes())
  }

  @Test
  fun unrecognizedMutationOwnerFailsClosed() {
    SQLiteDatabase.openOrCreateDatabase(file, null).use {
      it.version = 18
      it.execSQL("CREATE TABLE mutations (uid TEXT, batch_id INTEGER, mutations BLOB)")
      it.execSQL("INSERT INTO mutations VALUES (NULL, 1, X'00')")
    }
    assertThrows(IllegalStateException::class.java) { FirestoreMutationQueue.owners(file) }
  }

  @Test
  fun anyUsersMutationIncludingADeleteNeedsConsent() {
    SQLiteDatabase.openOrCreateDatabase(file, null).use {
      it.version = 18
      it.execSQL("CREATE TABLE mutations (uid TEXT, batch_id INTEGER, mutations BLOB)")
      it.execSQL("INSERT INTO mutations VALUES ('previous-user', 1, X'00')")
    }
    assertEquals(setOf("previous-user"), FirestoreMutationQueue.owners(file))
  }

  @Test
  fun emptyRecognizedQueueAllowsCleanupWithoutMigrationMetadata() {
    SQLiteDatabase.openOrCreateDatabase(file, null).use {
      it.version = 18
      it.execSQL("CREATE TABLE mutations (uid TEXT, batch_id INTEGER, mutations BLOB)")
    }
    assertEquals(emptySet<String>(), FirestoreMutationQueue.owners(file))
  }
}
