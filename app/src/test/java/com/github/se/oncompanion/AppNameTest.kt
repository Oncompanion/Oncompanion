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

  @Test
  fun noString_writesTheNameWithACapital() {
    val capitalised =
        R.string::class
            .java
            .fields
            .filter { context.getString(it.getInt(null)).contains("Oncompanion") }
            .map { it.name }

    assertEquals(emptyList<String>(), capitalised)
  }
}
