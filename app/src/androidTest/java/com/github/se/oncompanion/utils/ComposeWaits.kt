// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Waits for exactly one matching node to be displayed before interacting with loaded content. */
fun ComposeTestRule.awaitDisplayed(matcher: SemanticsMatcher) {
  // A screen container can exist before its asynchronous content has been laid out.
  waitUntil("one visible expected content node", 10_000) {
    onAllNodes(matcher).fetchSemanticsNodes().size == 1 && onNode(matcher).isDisplayed()
  }
}
