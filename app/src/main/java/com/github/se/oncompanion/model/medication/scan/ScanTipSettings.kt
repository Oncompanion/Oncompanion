// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Whether the scan still shows its tip to hold the phone upright (#356): a prescription
 * photographed sideways is read less well. Kept on this phone, for each user who signs in on it.
 */
interface ScanTipSettings {
  /** False once the user asked not to see the tip again. */
  val showUprightTip: Boolean

  /** The tip never shows again to this user on this phone. */
  fun hideUprightTip()
}

/**
 * [ScanTipSettings] kept in the app's [preferences], so they survive the app being closed. Each
 * user has their own: one who hides the tip doesn't hide it for another signed in on the same
 * phone.
 *
 * @param uid the signed-in user; without one the tip shows and hiding it isn't kept
 */
class PreferencesScanTipSettings(
    private val preferences: SharedPreferences,
    private val uid: String?,
) : ScanTipSettings {
  private val key = uid?.let { "${SHOW_UPRIGHT_TIP}_$it" }

  override val showUprightTip: Boolean
    get() = key == null || preferences.getBoolean(key, true)

  override fun hideUprightTip() {
    key?.let { preferences.edit { putBoolean(it, false) } }
  }

  private companion object {
    const val SHOW_UPRIGHT_TIP = "show_upright_tip"
  }
}
