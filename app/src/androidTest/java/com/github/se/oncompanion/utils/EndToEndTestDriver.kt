// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.navigation.NavHostController
import androidx.test.espresso.Espresso
import com.github.se.oncompanion.AppHostActivity
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.theme.OncompanionTheme

/**
 * Shared screen interactions for emulator journeys, with consistent waits and failure diagnostics.
 */
internal class EndToEndTestDriver(private val compose: AndroidComposeTestRule<*, AppHostActivity>) {
  private val redactions = mutableMapOf<String, String>()

  /** Keeps a journey's captured fake credentials out of timeout diagnostics. */
  fun redact(value: String, replacement: String) {
    require(value.isNotEmpty())
    redactions[value] = replacement
  }

  /**
   * Shows the real app with emulator credentials, including after rotation. An optional controller
   * exercises guarded routes.
   */
  fun startApp(credentials: GoogleCredentialProvider, navController: NavHostController? = null) {
    compose.runOnUiThread {
      AppHostActivity.content.value = {
        OncompanionTheme {
          AppNavHost(credentialProvider = credentials, navController = navController)
        }
      }
    }
  }

  /** Signs a fresh account in and completes onboarding with the selected role and first name. */
  fun signInAndOnboard(firstName: String, roleTag: String, destination: SemanticsMatcher) {
    open(C.Tag.google_sign_in_button, hasTestTag(roleTag)).performClick()
    replaceText(
        open(C.Tag.onboarding_continue_button, hasTestTag(C.Tag.onboarding_first_name_field)),
        firstName,
    )
    open(C.Tag.onboarding_save_button, destination)
  }

  /** Waits for a tagged control, including while Android is recreating the screen. */
  fun tag(value: String) = waitFor(hasTestTag(value))

  /**
   * Opens a destination from a tagged control using the same checks as matcher-based navigation.
   */
  fun open(from: String, destination: SemanticsMatcher) = open(hasTestTag(from), destination)

  /**
   * Waits for stable content and an enabled visible control, taps once, then checks the
   * destination.
   */
  fun open(from: SemanticsMatcher, destination: SemanticsMatcher): SemanticsNodeInteraction {
    waitFor(from)
    waitFor(loading, gone = true)
    compose.waitForIdle()
    reveal(waitFor(from)).assertIsDisplayed().assertIsEnabled().performClick()
    return waitFor(
        destination,
        failure = "Tapping ${from.description} didn't open the next screen.",
    )
  }

  /** Replaces a field's contents and waits for the keyboard to stop covering the next control. */
  fun replaceText(node: SemanticsNodeInteraction, value: String) = node.apply {
    performTextReplacement(value)
    closeKeyboard()
  }

  /** Appends field input and waits for the keyboard to close before the next interaction. */
  fun typeText(node: SemanticsNodeInteraction, value: String) = node.apply {
    performTextInput(value)
    closeKeyboard()
  }

  private fun closeKeyboard() {
    Espresso.closeSoftKeyboard()
    compose.waitForIdle()
  }

  /** Scrolls a control into view, ignoring only the absence of a scrolling parent. */
  fun reveal(node: SemanticsNodeInteraction) = node.apply {
    try {
      performScrollTo()
    } catch (error: AssertionError) {
      if (error.message?.contains("has no parent layout with a Scroll SemanticsAction") != true)
          throw error
    }
  }

  /** Searches merged or unmerged semantics; a temporarily absent Compose hierarchy has no nodes. */
  fun exists(matcher: SemanticsMatcher, unmerged: Boolean = false) =
      try {
        compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
      } catch (error: IllegalStateException) {
        if (error.message?.startsWith("No compose hierarchies found") != true) throw error
        false
      }

  /** Waits for presence or absence and reports the navigation step and screen when it times out. */
  fun waitFor(
      matcher: SemanticsMatcher,
      unmerged: Boolean = false,
      gone: Boolean = false,
      failure: String = "",
  ): SemanticsNodeInteraction {
    try {
      compose.waitUntil(TIMEOUT_MS) { exists(matcher, unmerged) != gone }
    } catch (error: ComposeTimeoutException) {
      val state = if (gone) "Still a node" else "No node"
      var diagnostic =
          "$failure $state ${matcher.description} after $TIMEOUT_MS ms.\n${screen()}".trim()
      for ((value, replacement) in redactions) diagnostic = diagnostic.replace(value, replacement)
      throw AssertionError(diagnostic, error)
    }
    return compose.onNode(matcher, useUnmergedTree = unmerged)
  }

  private fun screen() =
      try {
        compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)
      } catch (error: IllegalStateException) {
        if (error.message?.startsWith("No compose hierarchies found") != true) throw error
        "No Compose hierarchy available."
      }

  private companion object {
    const val TIMEOUT_MS = 15_000L
    val loading =
        SemanticsMatcher("a loading screen") {
          it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith("_loading") == true
        }
  }
}
