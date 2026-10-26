// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

/**
 * A screen for end-to-end tests (debug builds only) that shows [content]. Like [MainActivity], it
 * sets its content when it is created, so when Android relaunches it (e.g. on a freshly started
 * emulator, while Google Play services updates) the app comes back on the same screen. A test that
 * calls `setContent` once on an empty activity would be left with a blank screen instead.
 */
class TestHostActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { content.value() }
  }

  companion object {
    /** What the screen shows; set by the test, and kept across a relaunch. */
    val content = mutableStateOf<@Composable () -> Unit>({})
  }
}
