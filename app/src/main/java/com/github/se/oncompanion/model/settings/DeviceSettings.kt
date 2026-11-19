// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

/** The device's appearance override; system appearance remains the default. */
enum class AppearancePreference {
  FOLLOW_DEVICE,
  LIGHT,
  DARK,
}

/** Preferences shared by accounts on this phone, independently of patient data. */
data class DeviceSettings(val appearance: AppearancePreference = AppearancePreference.FOLLOW_DEVICE)
