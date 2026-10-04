package com.github.se.oncompanion.utils

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumented test runner (set in `app/build.gradle.kts`). It connects Firebase to the local
 * emulators before any test runs, so no instrumented test can reach the production project by
 * accident, e.g. a UI test whose screen uses a repository with the default Firebase instances.
 */
class OncompanionTestRunner : AndroidJUnitRunner() {
  override fun callApplicationOnCreate(app: Application) {
    super.callApplicationOnCreate(app)
    FirebaseEmulator.ensureConnected()
  }
}
