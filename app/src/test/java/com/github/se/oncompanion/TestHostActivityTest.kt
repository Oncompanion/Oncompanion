// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen end-to-end tests show the app on: when Android relaunches it, as on a freshly started
 * emulator, the content comes back, with its saved state, like in [MainActivity].
 */
@RunWith(AndroidJUnit4::class)
class TestHostActivityTest {

  @get:Rule val compose = createAndroidComposeRule<TestHostActivity>()

  @After
  fun tearDown() {
    TestHostActivity.content.value = {}
  }

  private fun show(content: @Composable () -> Unit) {
    compose.runOnUiThread { TestHostActivity.content.value = content }
    compose.waitForIdle()
  }

  @Test
  fun showsTheContentItIsGiven() {
    show { Text("The app") }

    compose.onNodeWithText("The app").assertExists()
  }

  @Test
  fun aRelaunchedScreenShowsTheContentAgainWithItsSavedState() {
    show {
      // Like the app's navigation, which is saved the same way
      var taps by rememberSaveable { mutableIntStateOf(0) }
      Button(onClick = { taps++ }) { Text("Tapped $taps times") }
    }
    compose.onNodeWithText("Tapped 0 times").performClick()
    val before = compose.activity

    compose.activityRule.scenario.recreate()
    compose.waitForIdle()

    assertNotSame(before, compose.activity)
    compose.onNodeWithText("Tapped 1 times").assertExists()
  }
}
