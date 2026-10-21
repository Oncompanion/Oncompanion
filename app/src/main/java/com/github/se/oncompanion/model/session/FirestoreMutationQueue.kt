// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.net.URLEncoder

/**
 * Records persisted mutation owners before the SDK acquires its exclusive SQLite lock.
 *
 * The public acknowledgement API only covers the active user. This read-only compatibility check
 * uses the SDK's SQLite format (schema versions 16–18); unknown formats fail closed. Revalidate
 * this helper and its emulator tests when updating Firebase. Never edit the SDK database here.
 */
internal object FirestoreMutationQueue {
  /**
   * Resolves the default database used by Oncompanion, without opening another Firebase session.
   */
  fun owners(db: FirebaseFirestore): Set<String> {
    val app = db.app
    val name =
        listOf(app.name, checkNotNull(app.options.projectId), "(default)").joinToString(
            ".",
            prefix = "firestore.",
        ) {
          URLEncoder.encode(it, "UTF-8")
        }
    return owners(app.applicationContext.getDatabasePath(name))
  }

  /**
   * Reads owners from a closed cache; unknown or unreadable formats cannot certify safe cleanup.
   */
  fun owners(file: File): Set<String> {
    // Read only before SDK startup: a fresh installation has no disk queue.
    if (!file.exists()) return emptySet()
    check(file.isFile) { "Firestore persistent mutation queue could not be inspected" }
    // Android's default corruption handler can delete files even for a read-only open.
    val database =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) {
          throw SQLiteDatabaseCorruptException("Firestore cache could not be inspected safely")
        }
    return database.use {
      check(it.version in 16..18) { "Unrecognized Firestore cache schema" }
      // Do not filter by uid: another account's queued writes are invisible to the public barrier.
      it.rawQuery("SELECT DISTINCT uid FROM mutations", null).use { cursor ->
        buildSet {
          while (cursor.moveToNext()) {
            check(!cursor.isNull(0)) { "Unrecognized mutation owner" }
            add(cursor.getString(0))
          }
        }
      }
    }
  }
}
