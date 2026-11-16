// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests [CheckExpiryOnResume], the trigger the care circle screens share. */
@RunWith(AndroidJUnit4::class)
class CheckExpiryOnResumeTest {

  @get:Rule val compose = createComposeRule()

  /** Each resume checks again, with the latest callback, and a paused screen doesn't. */
  @Test
  fun checksAgainEachTimeTheScreenResumes() {
    val owner =
        object : LifecycleOwner {
          override val lifecycle = LifecycleRegistry(this)
        }
    var first = 0
    var second = 0
    var refresh: () -> Unit by mutableStateOf({ first++ })
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
    compose.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides owner) { CheckExpiryOnResume(refresh) }
    }
    compose.waitForIdle()
    assertEquals(0, first)

    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    compose.waitForIdle()
    assertEquals(1, first)

    // Paused (e.g. the email app is open), then back with a new callback
    compose.runOnIdle {
      owner.lifecycle.currentState = Lifecycle.State.STARTED
      refresh = { second++ }
    }
    compose.waitForIdle()
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    compose.waitForIdle()
    assertEquals(1, first)
    assertEquals(1, second)
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
  }
}
