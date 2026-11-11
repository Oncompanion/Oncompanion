// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.hospital

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the debug resource selects the verified proxy without requesting tokens or HTTP. */
@RunWith(AndroidJUnit4::class)
class HospitalSearchConfigurationTest {
  private val original = HospitalRepositoryProvider.repository

  /** Keeps the configured adapter local to this test. */
  @After
  fun restoreProvider() {
    HospitalRepositoryProvider.repository = original
  }

  @Test
  fun debugEndpointInstallsTheAuthenticatedProxyWithoutSearching() {
    val endpoint =
        ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.hospital_proxy_endpoint)
    assertEquals(
        "https://europe-west1-oncompanion-c4144.cloudfunctions.net/hospitalSearch",
        endpoint,
    )
    HospitalRepositoryProvider.disable()
    HospitalRepositoryProvider.configureAtStartup(endpoint)
    assertTrue(HospitalRepositoryProvider.isConfigured)
    assertTrue(HospitalRepositoryProvider.repository is HospitalRepositoryProxy)
  }
}
