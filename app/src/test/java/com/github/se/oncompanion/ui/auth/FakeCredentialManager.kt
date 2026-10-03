package com.github.se.oncompanion.ui.auth

import android.app.PendingIntent
import android.content.Context
import android.os.CancellationSignal
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PrepareGetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import java.util.concurrent.Executor

/**
 * Hand-written fake [CredentialManager] for unit tests. Both the suspend and the callback variants
 * of getCredential / clearCredentialState are supported so the fake does not depend on which one
 * the code under test uses.
 */
class FakeCredentialManager(
    /** Credential returned by getCredential, unless [getError] is set. */
    var credential: Credential? = null,
    /** Exception thrown by getCredential. */
    var getError: GetCredentialException? = null,
    /** Exception thrown by clearCredentialState. */
    var clearError: ClearCredentialException? = null,
) : CredentialManager {

  val getRequests = mutableListOf<GetCredentialRequest>()
  val getContexts = mutableListOf<Context>()
  val clearRequests = mutableListOf<ClearCredentialStateRequest>()

  private fun handleGet(context: Context, request: GetCredentialRequest): GetCredentialResponse {
    getContexts += context
    getRequests += request
    getError?.let { throw it }
    return GetCredentialResponse(
        credential ?: throw IllegalStateException("FakeCredentialManager: no credential set")
    )
  }

  private fun handleClear(request: ClearCredentialStateRequest) {
    clearRequests += request
    clearError?.let { throw it }
  }

  override suspend fun getCredential(
      context: Context,
      request: GetCredentialRequest,
  ): GetCredentialResponse = handleGet(context, request)

  override suspend fun clearCredentialState(request: ClearCredentialStateRequest) =
      handleClear(request)

  override fun getCredentialAsync(
      context: Context,
      request: GetCredentialRequest,
      cancellationSignal: CancellationSignal?,
      executor: Executor,
      callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>,
  ) {
    try {
      val response = handleGet(context, request)
      executor.execute { callback.onResult(response) }
    } catch (e: GetCredentialException) {
      executor.execute { callback.onError(e) }
    }
  }

  override fun clearCredentialStateAsync(
      request: ClearCredentialStateRequest,
      cancellationSignal: CancellationSignal?,
      executor: Executor,
      callback: CredentialManagerCallback<Void?, ClearCredentialException>,
  ) {
    try {
      handleClear(request)
      executor.execute { callback.onResult(null) }
    } catch (e: ClearCredentialException) {
      executor.execute { callback.onError(e) }
    }
  }

  override fun getCredentialAsync(
      context: Context,
      pendingGetCredentialHandle: PrepareGetCredentialResponse.PendingGetCredentialHandle,
      cancellationSignal: CancellationSignal?,
      executor: Executor,
      callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>,
  ) = throw UnsupportedOperationException()

  override fun prepareGetCredentialAsync(
      request: GetCredentialRequest,
      cancellationSignal: CancellationSignal?,
      executor: Executor,
      callback: CredentialManagerCallback<PrepareGetCredentialResponse, GetCredentialException>,
  ) = throw UnsupportedOperationException()

  override fun createCredentialAsync(
      context: Context,
      request: CreateCredentialRequest,
      cancellationSignal: CancellationSignal?,
      executor: Executor,
      callback: CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>,
  ) = throw UnsupportedOperationException()

  override fun createSettingsPendingIntent(): PendingIntent = throw UnsupportedOperationException()
}
