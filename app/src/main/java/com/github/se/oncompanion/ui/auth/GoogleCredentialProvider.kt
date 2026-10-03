package com.github.se.oncompanion.ui.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** Why getting a Google ID token failed, so the UI can show the right message. */
class GoogleSignInException(val reason: Reason, cause: Throwable? = null) :
    Exception("Google sign-in failed: $reason", cause) {
  enum class Reason {
    /** The user closed the Google account picker. Usually no error message is needed. */
    CANCELLED,
    /** No Google account on the device (or none usable). */
    NO_ACCOUNT,
    /** Anything else: no connection, Play Services problem, unexpected response. */
    FAILED,
  }
}

/** Shows the Google account picker and returns a Google ID token for Firebase. */
interface GoogleCredentialProvider {

  /**
   * Shows Google's "Sign in with Google" dialog and returns the ID token of the chosen account.
   *
   * @param context an Activity context (the dialog is attached to it)
   * @throws GoogleSignInException if no token could be obtained
   */
  suspend fun getGoogleIdToken(context: Context): String

  /** Forgets the account chosen last time, so the next sign-in shows the picker again. */
  suspend fun clearCredentialState(context: Context)
}

/**
 * [GoogleCredentialProvider] using Android's Credential Manager.
 *
 * @param serverClientId the Firebase web client ID (`R.string.default_web_client_id`, generated
 *   from google-services.json)
 * @param credentialManagerFactory creates the [CredentialManager]; replaceable in tests
 */
class CredentialManagerGoogleCredentialProvider(
    private val serverClientId: String,
    private val credentialManagerFactory: (Context) -> CredentialManager =
        CredentialManager::create,
) : GoogleCredentialProvider {

  override suspend fun getGoogleIdToken(context: Context): String {
    val request =
        GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
    val credential =
        try {
          credentialManagerFactory(context).getCredential(context, request).credential
        } catch (e: GetCredentialCancellationException) {
          throw GoogleSignInException(GoogleSignInException.Reason.CANCELLED, e)
        } catch (e: NoCredentialException) {
          throw GoogleSignInException(GoogleSignInException.Reason.NO_ACCOUNT, e)
        } catch (e: GetCredentialException) {
          throw GoogleSignInException(GoogleSignInException.Reason.FAILED, e)
        }

    if (
        credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
      throw GoogleSignInException(GoogleSignInException.Reason.FAILED)
    }
    return try {
      GoogleIdTokenCredential.createFrom(credential.data).idToken
    } catch (e: GoogleIdTokenParsingException) {
      throw GoogleSignInException(GoogleSignInException.Reason.FAILED, e)
    }
  }

  override suspend fun clearCredentialState(context: Context) {
    try {
      credentialManagerFactory(context).clearCredentialState(ClearCredentialStateRequest())
    } catch (e: ClearCredentialException) {
      // Not critical: the user is signed out of Firebase anyway
      Log.w(TAG, "Couldn't clear the Google credential state", e)
    }
  }

  private companion object {
    const val TAG = "GoogleCredentialProvider"
  }
}
