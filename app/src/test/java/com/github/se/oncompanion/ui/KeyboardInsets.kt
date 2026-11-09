// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue

/** Robolectric qualifiers of a phone held sideways: too short to pin a top bar above a keyboard. */
const val LANDSCAPE_PHONE = "w915dp-h412dp-xhdpi"

/** The same for a small phone, where even less height is left above the keyboard. */
const val SMALL_LANDSCAPE_PHONE = "w640dp-h360dp-xhdpi"

/** Robolectric qualifiers of the narrowest phones held upright: long labels have the least room. */
const val SMALL_PHONE = "w320dp-h640dp-xhdpi"

/**
 * Tells the screen under test that the keyboard is open and covers [heightPx] at the bottom of the
 * window, as Android does with `adjustResize`. Robolectric has no keyboard of its own.
 */
fun ComposeTestRule.showKeyboard(heightPx: Int) {
  val view = (onRoot().fetchSemanticsNode().root as ViewRootForTest).view
  val insets =
      WindowInsetsCompat.Builder()
          .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
          .setVisible(WindowInsetsCompat.Type.ime(), true)
          .build()
  runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
  waitForIdle()
}

/** Checks that the node is entirely above a keyboard of [keyboardHeightPx]. */
fun SemanticsNodeInteraction.assertIsAboveKeyboard(keyboardHeightPx: Int) {
  val node = fetchSemanticsNode()
  val keyboardTop = node.root!!.semanticsOwner.rootSemanticsNode.size.height - keyboardHeightPx
  val bottom = node.boundsInRoot.bottom
  assertTrue("Bottom at $bottom px, keyboard starts at $keyboardTop px", bottom <= keyboardTop)
}

/**
 * Checks that the node has drawable area and none of it is cut off, e.g. by a scrolling area too
 * small to show it: a node can be "displayed" with only a sliver of it on screen.
 */
fun SemanticsNodeInteraction.assertIsFullyVisible() {
  val node = fetchSemanticsNode()
  val size = node.size
  assertTrue(
      "Node has no drawable area: ${size.width} x ${size.height} px",
      size.width > 0 && size.height > 0,
  )
  assertIsDisplayed()
  val visible = node.boundsInRoot
  assertTrue(
      "Only ${visible.width} px of ${size.width} px width are visible",
      visible.width >= size.width,
  )
  assertTrue(
      "Only ${visible.height} px of ${size.height} px height are visible",
      visible.height >= size.height,
  )
}
