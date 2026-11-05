// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Installs the debug provider only when the independent proxy client first needs attestation. */
internal object HospitalProxyAppCheck {
  val instance: FirebaseAppCheck by lazy {
    FirebaseAppCheck.getInstance().apply {
      installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
  }
}
