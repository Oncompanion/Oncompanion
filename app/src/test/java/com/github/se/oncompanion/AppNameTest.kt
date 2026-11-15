// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app writes its name in lowercase, "oncompanion", in every text it shows (AGENTS.md). */
@RunWith(RobolectricTestRunner::class)
class AppNameTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** The name in any case: "Oncompanion", "OnCompanion", "ONCOMPANION"... */
  private val anyCase = Regex(APP_NAME, RegexOption.IGNORE_CASE)

  @Test
  fun noString_writesTheNameOtherThanInLowercase() {
    val wrongCase =
        R.string::class
            .java
            .fields
            .filter { field ->
              anyCase.findAll(context.getString(field.getInt(null))).any { it.value != APP_NAME }
            }
            .map { it.name }

    assertEquals(emptyList<String>(), wrongCase)
  }

  private companion object {
    const val APP_NAME = "oncompanion"
  }
}
