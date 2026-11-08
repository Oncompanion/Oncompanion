// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Listens with the phone's own speech recognizer (usually Google's), so the app needs no speech
 * model or server of its own. Recognition on the phone is preferred, so it works offline when the
 * language is installed on the phone; otherwise the phone's recognizer may go online.
 *
 * Nothing is recorded by the app: Android only hands back the text.
 *
 * @param context any context of the app; only its application context is kept, so a screen given
 *   here is never kept alive after it closes
 * @param mainDispatcher where Android's recognizer is created and driven; it only works on the main
 *   thread
 */
class AndroidSpeechRecognizer(
    context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SpeechRecognizer {

  private val appContext: Context = context.applicationContext

  /** The recognizer currently listening, so [stop] can reach it; `null` when not listening. */
  @Volatile private var listening: AndroidRecognizer? = null

  /** Whether the user asked the current session to [stop]. */
  @Volatile private var stopRequested = false

  override fun isAvailable(): Boolean = AndroidRecognizer.isRecognitionAvailable(appContext)

  override fun listen(language: SpeechLanguage): Flow<SpeechEvent> = flow {
    // Checked when listening starts, not when the flow is built: the user may have
    // granted the microphone in between
    when {
      !hasMicrophone() -> emit(SpeechEvent.Failed(SpeechError.NO_PERMISSION))
      !isAvailable() -> emit(SpeechEvent.Failed(SpeechError.UNAVAILABLE))
      else -> emitAll(recognition(language))
    }
  }
      .flowOn(mainDispatcher)

  override fun stop() {
    val recognizer = listening ?: return
    stopRequested = true
    recognizer.stopListening()
  }

  /** One listening session with Android's recognizer, destroyed when the flow ends. */
  private fun recognition(language: SpeechLanguage): Flow<SpeechEvent> = callbackFlow {
    val recognizer = AndroidRecognizer.createSpeechRecognizer(appContext)
    listening = recognizer
    stopRequested = false
    recognizer.setRecognitionListener(
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {
            trySend(SpeechEvent.Listening)
          }

          override fun onPartialResults(partialResults: Bundle?) {
            bestText(partialResults)?.let { trySend(SpeechEvent.PartialText(it)) }
          }

          override fun onResults(results: Bundle?) {
            val text = bestText(results)
            trySend(
                if (text == null) SpeechEvent.Failed(SpeechError.NO_SPEECH)
                else SpeechEvent.FinalText(text)
            )
            close()
          }

          override fun onError(error: Int) {
            // Some phones answer a stop before any speech with a client error: the user stopped
            // without saying anything, which is not a failure
            val reason =
                if (error == AndroidRecognizer.ERROR_CLIENT && stopRequested) SpeechError.NO_SPEECH
                else speechErrorOf(error)
            trySend(SpeechEvent.Failed(reason))
            close()
          }

          // Sound levels and the raw audio aren't used: the app keeps nothing of the recording
          override fun onBeginningOfSpeech() = Unit

          override fun onRmsChanged(rmsdB: Float) = Unit

          override fun onBufferReceived(buffer: ByteArray?) = Unit

          override fun onEndOfSpeech() = Unit

          override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    )
    recognizer.startListening(recognizerIntent(language, appContext.packageName))
    awaitClose {
      if (listening === recognizer) listening = null
      // Also cancels listening if the user left before the end
      recognizer.destroy()
    }
  }
      // A session is short: keep every event, so a slow screen never loses the final one
      .buffer(Channel.UNLIMITED)

  private fun hasMicrophone(): Boolean =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
}

/** What Android's recognizer is asked: free speech in [language], with text as it comes. */
internal fun recognizerIntent(language: SpeechLanguage, packageName: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

/** The most likely text in Android's [results], without surrounding spaces; `null` if blank. */
internal fun bestText(results: Bundle?): String? =
    results
        ?.getStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * The [SpeechError] for one of Android's `ERROR_*` codes. Codes added after Android 9 are plain
 * numbers at compile time, so older phones just never send them.
 */
@SuppressLint("InlinedApi")
internal fun speechErrorOf(code: Int): SpeechError =
    when (code) {
      AndroidRecognizer.ERROR_NO_MATCH,
      AndroidRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
      AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
      AndroidRecognizer.ERROR_RECOGNIZER_BUSY,
      AndroidRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.BUSY
      AndroidRecognizer.ERROR_NETWORK,
      AndroidRecognizer.ERROR_NETWORK_TIMEOUT,
      AndroidRecognizer.ERROR_SERVER,
      AndroidRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechError.NEEDS_CONNECTION
      AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
      AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.LANGUAGE_UNAVAILABLE
      else -> SpeechError.FAILED
    }
