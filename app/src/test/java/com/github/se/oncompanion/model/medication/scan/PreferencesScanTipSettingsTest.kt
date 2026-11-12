// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** [PreferencesScanTipSettings] on Android's real preferences (Robolectric). */
@RunWith(AndroidJUnit4::class)
class PreferencesScanTipSettingsTest {

  private fun preferences() =
      ApplicationProvider.getApplicationContext<Context>()
          .getSharedPreferences("scan_settings_test", Context.MODE_PRIVATE)

  @Test
  fun theTipShowsUntilHidden_andStaysHiddenAfterTheAppRestarts() {
    val settings = PreferencesScanTipSettings(preferences(), "alice")
    assertTrue(settings.showUprightTip)

    settings.hideUprightTip()

    assertFalse(settings.showUprightTip)
    // As read again when the app is opened next time
    assertFalse(PreferencesScanTipSettings(preferences(), "alice").showUprightTip)
  }

  @Test
  fun oneUserHidingTheTip_doesntHideItForAnother_andAfterSigningBackIn() {
    PreferencesScanTipSettings(preferences(), "alice").hideUprightTip()

    // Bob signs in on the same phone, then Alice again
    assertTrue(PreferencesScanTipSettings(preferences(), "bob").showUprightTip)
    assertFalse(PreferencesScanTipSettings(preferences(), "alice").showUprightTip)
  }

  @Test
  fun withNobodySignedIn_theTipShows_andHidingItIsntKept() {
    val settings = PreferencesScanTipSettings(preferences(), null)

    settings.hideUprightTip()

    assertTrue(settings.showUprightTip)
    assertTrue(PreferencesScanTipSettings(preferences(), "alice").showUprightTip)
  }
}
