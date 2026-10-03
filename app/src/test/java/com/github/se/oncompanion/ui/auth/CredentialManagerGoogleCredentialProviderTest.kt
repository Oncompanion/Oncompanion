package com.github.se.oncompanion.ui.auth

import android.content.Context
import android.os.Bundle
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialManagerGoogleCredentialProviderTest {

  private companion object {
    const val SERVER_CLIENT_ID = "1234-test.apps.googleusercontent.com"

    /** googleid 1.2.1 checks that the ID token is JWT-shaped, so a plain string is rejected. */
    fun fakeJwt(sub: String): String {
      val enc = Base64.getUrlEncoder().withoutPadding()
      val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
      val payload =
          enc.encodeToString("""{"sub":"$sub","email":"alice@example.com"}""".toByteArray())
      return "$header.$payload.c2lnbmF0dXJl"
    }

    val TOKEN_123 = fakeJwt("token-123")
    val TOKEN_FROM_BUNDLE = fakeJwt("token-from-bundle")
  }

  private lateinit var context: Context
  private lateinit var fake: FakeCredentialManager
  private val factoryContexts = mutableListOf<Context>()
  private lateinit var provider: CredentialManagerGoogleCredentialProvider

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    fake = FakeCredentialManager()
    factoryContexts.clear()
    provider =
        CredentialManagerGoogleCredentialProvider(SERVER_CLIENT_ID) { ctx ->
          factoryContexts += ctx
          fake
        }
  }

  private fun googleCredential(idToken: String = TOKEN_123): Credential =
      GoogleIdTokenCredential.Builder().setId("alice@example.com").setIdToken(idToken).build()

  private suspend fun expectFailure(): GoogleSignInException {
    try {
      provider.getGoogleIdToken(context)
    } catch (e: GoogleSignInException) {
      return e
    }
    fail("Expected GoogleSignInException")
    throw AssertionError()
  }

  private suspend fun assertGetErrorMapsTo(
      error: GetCredentialException,
      expected: GoogleSignInException.Reason,
  ) {
    fake.getError = error
    val e = expectFailure()
    assertEquals(expected, e.reason)
    assertSame(error, e.cause)
  }

  // ---------------- getGoogleIdToken: success ----------------

  @Test
  fun getGoogleIdToken_returnsIdTokenOfGoogleCredential() = runTest {
    fake.credential = googleCredential(TOKEN_123)
    assertEquals(TOKEN_123, provider.getGoogleIdToken(context))
  }

  @Test
  fun getGoogleIdToken_requestHasSingleSignInWithGoogleOptionWithServerClientId() = runTest {
    fake.credential = googleCredential()
    provider.getGoogleIdToken(context)

    assertEquals(1, fake.getRequests.size)
    val options = fake.getRequests.single().credentialOptions
    assertEquals(1, options.size)
    val option = options.single()
    assertTrue(
        "Expected GetSignInWithGoogleOption but was ${option::class.java.name}",
        option is GetSignInWithGoogleOption,
    )
    assertEquals(SERVER_CLIENT_ID, (option as GetSignInWithGoogleOption).serverClientId)
  }

  @Test
  fun getGoogleIdToken_passesContextToFactoryAndCredentialManager() = runTest {
    fake.credential = googleCredential()
    provider.getGoogleIdToken(context)

    assertTrue(factoryContexts.isNotEmpty())
    factoryContexts.forEach { assertSame(context, it) }
    assertSame(context, fake.getContexts.single())
  }

  @Test
  fun getGoogleIdToken_parsesCustomCredentialWithGoogleType() = runTest {
    // Simulates what Credential Manager actually returns: a plain CustomCredential.
    val data = googleCredential(TOKEN_FROM_BUNDLE).data
    fake.credential =
        CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, data)
    assertEquals(TOKEN_FROM_BUNDLE, provider.getGoogleIdToken(context))
  }

  // ---------------- getGoogleIdToken: exceptions ----------------

  @Test
  fun getGoogleIdToken_cancellationMapsToCancelled() = runTest {
    assertGetErrorMapsTo(
        GetCredentialCancellationException("user cancelled"),
        GoogleSignInException.Reason.CANCELLED,
    )
  }

  @Test
  fun getGoogleIdToken_noCredentialMapsToNoAccount() = runTest {
    assertGetErrorMapsTo(
        NoCredentialException("no account"),
        GoogleSignInException.Reason.NO_ACCOUNT,
    )
  }

  @Test
  fun getGoogleIdToken_unknownErrorMapsToFailed() = runTest {
    assertGetErrorMapsTo(
        GetCredentialUnknownException("unknown"),
        GoogleSignInException.Reason.FAILED,
    )
  }

  @Test
  fun getGoogleIdToken_interruptedErrorMapsToFailed() = runTest {
    assertGetErrorMapsTo(
        GetCredentialInterruptedException("interrupted"),
        GoogleSignInException.Reason.FAILED,
    )
  }

  // ---------------- getGoogleIdToken: wrong credential ----------------

  @Test
  fun getGoogleIdToken_passwordCredentialFails() = runTest {
    fake.credential = PasswordCredential("id", "pw")
    assertEquals(GoogleSignInException.Reason.FAILED, expectFailure().reason)
  }

  @Test
  fun getGoogleIdToken_customCredentialOfOtherTypeFails() = runTest {
    fake.credential = CustomCredential("com.example.OTHER_TYPE", Bundle())
    assertEquals(GoogleSignInException.Reason.FAILED, expectFailure().reason)
  }

  @Test
  fun getGoogleIdToken_unparsableGoogleCredentialFails() = runTest {
    fake.credential =
        CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
    assertEquals(GoogleSignInException.Reason.FAILED, expectFailure().reason)
  }

  // ---------------- clearCredentialState ----------------

  @Test
  fun clearCredentialState_callsCredentialManagerWithDefaultRequest() = runTest {
    provider.clearCredentialState(context)

    assertEquals(1, fake.clearRequests.size)
    assertEquals(
        ClearCredentialStateRequest.TYPE_CLEAR_CREDENTIAL_STATE,
        fake.clearRequests.single().requestType,
    )
    assertTrue(factoryContexts.isNotEmpty())
    factoryContexts.forEach { assertSame(context, it) }
  }

  @Test
  fun clearCredentialState_swallowsClearCredentialException() = runTest {
    fake.clearError = ClearCredentialUnknownException("boom")
    provider.clearCredentialState(context) // must not throw
    assertEquals(1, fake.clearRequests.size)
  }

  @Test
  fun defaultFactoryCanBeConstructed() {
    // Only checks that the default factory parameter (CredentialManager::create) is usable.
    val defaultProvider = CredentialManagerGoogleCredentialProvider(SERVER_CLIENT_ID)
    assertTrue(defaultProvider is GoogleCredentialProvider)
  }
}
