// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile

/**
 * A [SpeechRecognizer] the test speaks through: each [listen] starts a session, and the test sends
 * what the phone would hear with [send]. Like the real one, a session ends after its
 * [SpeechEvent.FinalText] or [SpeechEvent.Failed].
 *
 * @param available what [isAvailable] answers, as a phone with or without a recognizer
 * @param offline what [offlineSupport] answers, for every language
 */
class FakeSpeechRecognizer(
    var available: Boolean = true,
    var offline: OfflineSpeech = OfflineSpeech.UNKNOWN,
) : SpeechRecognizer {

  /** The languages the phone was asked to download, in order. */
  val downloads = mutableListOf<SpeechLanguage>()

  /** The languages each session listened in, in order. */
  val languages = mutableListOf<SpeechLanguage>()

  /** How many times [stop] was called. */
  var stops = 0
    private set

  /** Whether a session is being collected right now. */
  var isListening = false
    private set

  private var session = Channel<SpeechEvent>(Channel.UNLIMITED)

  override fun isAvailable(): Boolean = available

  override fun listen(language: SpeechLanguage): Flow<SpeechEvent> = flow {
    languages += language
    val events = Channel<SpeechEvent>(Channel.UNLIMITED).also { session = it }
    isListening = true
    try {
      emitAll(
          events.receiveAsFlow().transformWhile {
            emit(it)
            it is SpeechEvent.Listening || it is SpeechEvent.PartialText || it is SpeechEvent.Level
          }
      )
    } finally {
      isListening = false
    }
  }

  override fun stop() {
    stops++
  }

  /** Whether [offlineSupport] never answers, as a phone whose speech service is stuck. */
  var offlineHangs = false

  override suspend fun offlineSupport(language: SpeechLanguage): OfflineSpeech {
    if (offlineHangs) awaitCancellation()
    return offline
  }

  override fun downloadForOffline(language: SpeechLanguage) {
    downloads += language
  }

  /** What the phone hears next in the session being listened to. */
  fun send(vararg events: SpeechEvent) {
    events.forEach { session.trySend(it) }
  }
}
