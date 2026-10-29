// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.utils

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.model.session.LocalSessionRepository
import com.github.se.oncompanion.model.session.LocalSessionRepositoryFirestore
import com.github.se.oncompanion.model.session.PreferencesCleanupJournal
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * Instrumented test runner (set in `app/build.gradle.kts`). It connects Firebase to the local
 * emulators before any test runs, so no instrumented test can reach the production project by
 * accident, e.g. a UI test whose screen uses a repository with the default Firebase instances.
 */
class OncompanionTestRunner : AndroidJUnitRunner() {
  override fun callApplicationOnCreate(app: Application) {
    super.callApplicationOnCreate(app)
    FirebaseEmulator.ensureConnected()
    // Tests that host AppNavHost directly still need MainActivity's process-scoped startup setup.
    runBlocking {
      val previous = FirebaseEmulator.firestore
      previous.terminate().await()
      previous.clearPersistence().await()
    }
    fun emulatorDatabase(): FirebaseFirestore =
        FirebaseFirestore.getInstance().apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
        }
    emulatorDatabase()
    val lifecycle = LocalSessionRepositoryFirestore()
    val local =
        object : LocalSessionRepository by lifecycle {
          override fun reopen() {
            // A newly created SDK must be wired before production repositories can resolve it.
            emulatorDatabase()
            lifecycle.reopen()
          }
        }
    val preferences = app.getSharedPreferences("session_cleanup", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    SignOutProvider.signOut =
        SignOut(local = local).apply { initialize(PreferencesCleanupJournal(preferences)) }
  }
}
