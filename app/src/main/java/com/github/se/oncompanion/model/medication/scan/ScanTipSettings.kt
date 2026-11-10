// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Whether the scan still shows its tip to hold the phone upright (#356): a prescription
 * photographed sideways is read less well. A setting of this phone, not of the account.
 */
interface ScanTipSettings {
  /** False once the user asked not to see the tip again. */
  val showUprightTip: Boolean

  /** The tip never shows again on this phone. */
  fun hideUprightTip()
}

/** [ScanTipSettings] kept in the app's [preferences], so they survive the app being closed. */
class PreferencesScanTipSettings(private val preferences: SharedPreferences) : ScanTipSettings {
  override val showUprightTip: Boolean
    get() = preferences.getBoolean(SHOW_UPRIGHT_TIP, true)

  override fun hideUprightTip() {
    preferences.edit { putBoolean(SHOW_UPRIGHT_TIP, false) }
  }

  private companion object {
    const val SHOW_UPRIGHT_TIP = "show_upright_tip"
  }
}
