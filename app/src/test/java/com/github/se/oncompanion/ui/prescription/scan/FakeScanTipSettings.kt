// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import com.github.se.oncompanion.model.medication.scan.ScanTipSettings

/**
 * [ScanTipSettings] in memory. By default the tip is already hidden, so a test of the camera starts
 * as a returning user's scan does; pass `showUprightTip = true` to see the tip.
 */
class FakeScanTipSettings(override var showUprightTip: Boolean = false) : ScanTipSettings {
  override fun hideUprightTip() {
    showUprightTip = false
  }
}
