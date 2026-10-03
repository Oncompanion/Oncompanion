package com.github.se.oncompanion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun colorSchemeFor(darkTheme: Boolean): ColorScheme {
    lateinit var scheme: ColorScheme
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = darkTheme) { scheme = MaterialTheme.colorScheme }
    }
    composeTestRule.waitForIdle()
    return scheme
  }

  @Test
  fun lightTheme_usesBaselinePrimary() {
    assertEquals(Color(0xFF6750A4), colorSchemeFor(darkTheme = false).primary)
  }

  @Test
  fun lightTheme_usesBaselineSurface() {
    assertEquals(Color(0xFFFEF7FF), colorSchemeFor(darkTheme = false).surface)
  }

  @Test
  fun darkTheme_usesBaselinePrimary() {
    assertEquals(Color(0xFFD0BCFF), colorSchemeFor(darkTheme = true).primary)
  }
}
