// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.carecircle.label
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [NotSharedMessage] as the Figma "Message state" (Brand & Theme). */
@RunWith(AndroidJUnit4::class)
class NotSharedMessageTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val section = CarePermission.PLANNING
  private val title =
      context.getString(R.string.not_shared_title, "Anne", context.getString(section.label))
  private val body = context.getString(R.string.not_shared_body)

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography

  @Before
  fun showTheMessage() {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        NotSharedMessage(patientName = "Anne", section = section)
      }
    }
  }

  @Test
  fun startsWithA64dpIconTile_24dpAboveTheTitle() {
    val tile = composeTestRule.onNodeWithTag(C.Tag.not_shared_icon)
    tile.assertWidthIsEqualTo(64.dp).assertHeightIsEqualTo(64.dp)

    val tileBottom = tile.getUnclippedBoundsInRoot().bottom
    val titleTop =
        composeTestRule.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot().top
    assertEquals(24.dp, titleTop - tileBottom)
  }

  @Test
  fun titleAndLine_useTheFigmaStyles() {
    val cases =
        listOf(
            Triple(title, typography.titleLarge, colors.onSurface),
            Triple(body, typography.bodyMedium, colors.onSurfaceVariant),
        )
    for ((text, expected, color) in cases) {
      composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertTextStyle(expected, color)
    }
  }
}
