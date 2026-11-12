// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import android.content.Context
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider

/** Replaces only Google's account picker with a token accepted by the Auth emulator. */
internal class EmulatorGoogleSignIn(private val idToken: String) : GoogleCredentialProvider {
  override suspend fun getGoogleIdToken(context: Context): String = idToken

  override suspend fun clearCredentialState(context: Context) = Unit
}
