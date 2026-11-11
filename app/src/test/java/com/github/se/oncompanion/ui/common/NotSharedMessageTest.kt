// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.carecircle.label
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [NotSharedMessage]; its look is the Message state's, checked by `LakeMessageStateTest`. */
@RunWith(AndroidJUnit4::class)
class NotSharedMessageTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun namesThePatientAndTheSection_andSaysOnlyThePatientCanShareIt() {
    val section = CarePermission.PLANNING
    composeTestRule.setContent {
      OncompanionTheme { NotSharedMessage(patientName = "Anne", section = section) }
    }

    val title =
        context.getString(R.string.not_shared_title, "Anne", context.getString(section.label))
    for (text in listOf(title, context.getString(R.string.not_shared_body))) {
      composeTestRule
          .onNode(hasText(text) and hasAnyAncestor(hasTestTag(C.Tag.not_shared)), true)
          .assertIsDisplayed()
    }
  }
}
