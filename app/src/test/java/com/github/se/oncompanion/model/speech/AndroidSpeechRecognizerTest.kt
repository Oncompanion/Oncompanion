// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * Listening with the phone's recognizer, simulated by Robolectric: what the user hears back in each
 * case, and that Android's recognizer is always released at the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidSpeechRecognizerTest {
  private val context: Application = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    ShadowSpeechRecognizer.reset()
    shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
    installRecognitionService()
  }

  @Test
  fun whatTheUserSaysComesBackAsTheySpeakThenInFull() = runTest {
    val session = startListening(SpeechLanguage.FRENCH)

    android.triggerOnReadyForSpeech(Bundle())
    android.triggerOnPartialResults(results("J'ai"))
    android.triggerOnPartialResults(results("J'ai mal à la tête"))
    android.triggerOnResults(results(" J'ai mal à la tête depuis ce matin "))
    runCurrent()

    assertEquals(
        listOf(
            SpeechEvent.Listening,
            SpeechEvent.PartialText("J'ai"),
            SpeechEvent.PartialText("J'ai mal à la tête"),
            SpeechEvent.FinalText("J'ai mal à la tête depuis ce matin"),
        ),
        session.events,
    )
    assertTrue(session.job.isCompleted)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun theRecognizerIsAskedForFreeSpeechInTheLanguageOnThePhoneIfPossible() = runTest {
    startListening(SpeechLanguage.FRENCH)

    val intent = android.lastRecognizerIntent
    assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
    assertEquals("fr-FR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    assertEquals(
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
    )
    assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
    assertEquals(context.packageName, intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
  }

  @Test
  fun blankTextIsNeverSentAndSilenceEndsWithNoSpeech() = runTest {
    val session = startListening()

    android.triggerOnPartialResults(results("   "))
    android.triggerOnPartialResults(Bundle())
    android.triggerOnResults(results(""))
    runCurrent()

    assertEquals(listOf(SpeechEvent.Failed(SpeechError.NO_SPEECH)), session.events)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun anErrorEndsListeningAndReleasesTheRecognizer() = runTest {
    val session = startListening()

    android.triggerOnReadyForSpeech(Bundle())
    android.triggerOnError(AndroidRecognizer.ERROR_NETWORK)
    runCurrent()

    assertEquals(
        listOf(SpeechEvent.Listening, SpeechEvent.Failed(SpeechError.NEEDS_CONNECTION)),
        session.events,
    )
    assertTrue(session.job.isCompleted)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun whatAndroidSendsAfterStopStillEndsTheSession() = runTest {
    // Robolectric doesn't record stopListening(), so this can't check that stop() reaches
    // Android: only that the text Android then sends still comes back and ends the session
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val session = startListening(recognizer = recognizer)
    android.triggerOnPartialResults(results("tired"))

    recognizer.stop()
    android.triggerOnResults(results("tired since this morning"))
    runCurrent()

    assertEquals(
        listOf(
            SpeechEvent.PartialText("tired"),
            SpeechEvent.FinalText("tired since this morning"),
        ),
        session.events,
    )
    assertTrue(session.job.isCompleted)
  }

  @Test
  fun stoppingWhenNotListeningDoesNotCrash() = runTest {
    // The same recognizer, as the app has one: e.g. the user taps Stop twice, or after leaving.
    // Only checks that nothing crashes: Robolectric doesn't record stopListening()
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    recognizer.stop()

    // After a session that ended
    val ended = startListening(recognizer = recognizer)
    android.triggerOnResults(results("tired"))
    runCurrent()
    recognizer.stop()

    // After a session that was cancelled
    val cancelled = startListening(recognizer = recognizer)
    cancelled.job.cancel()
    runCurrent()
    recognizer.stop()

    assertEquals(listOf(SpeechEvent.FinalText("tired")), ended.events)
    assertTrue(cancelled.events.isEmpty())
  }

  @Test
  fun cancellingStopsListeningAndDropsWhatWasSaid() = runTest {
    val session = startListening()
    android.triggerOnPartialResults(results("nausea"))

    session.job.cancel()
    runCurrent()
    android.triggerOnResults(results("nausea all day"))
    runCurrent()

    assertEquals(listOf(SpeechEvent.PartialText("nausea")), session.events)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun withoutTheMicrophoneNothingIsStarted() = runTest {
    shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)

    val session = startListening()

    assertEquals(listOf(SpeechEvent.Failed(SpeechError.NO_PERMISSION)), session.events)
    assertTrue(session.job.isCompleted)
    assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
  }

  @Test
  fun onAPhoneWithoutARecognizerNothingIsStarted() = runTest {
    shadowOf(context.packageManager).removeService(RECOGNITION_SERVICE)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))

    assertFalse(recognizer.isAvailable())
    val session = startListening(recognizer = recognizer)
    assertEquals(listOf(SpeechEvent.Failed(SpeechError.UNAVAILABLE)), session.events)
    assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
  }

  @Test
  fun aPhoneWithARecognizerCanListen() {
    assertTrue(AndroidSpeechRecognizer(context).isAvailable())
  }

  @Test
  fun onlyTheAppsContextIsKeptNeverTheScreens() = runTest {
    // A screen's context that fails the test if anything but its app's context is used: keeping
    // the screen would leak it when the phone is rotated while listening
    val screen =
        object : ContextWrapper(context) {
          override fun getApplicationContext(): Context = context

          override fun getPackageManager(): PackageManager = screenUsed()

          override fun getPackageName(): String = screenUsed()

          override fun checkPermission(permission: String, pid: Int, uid: Int): Int = screenUsed()

          override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
              screenUsed()

          override fun getMainLooper(): Looper = screenUsed()
        }

    val recognizer = AndroidSpeechRecognizer(screen, UnconfinedTestDispatcher(testScheduler))
    assertTrue(recognizer.isAvailable())
    val session = startListening(recognizer = recognizer)
    android.triggerOnResults(results("tired"))
    runCurrent()

    assertEquals(listOf(SpeechEvent.FinalText("tired")), session.events)
  }

  @Test
  fun androidErrorsAreGroupedByWhatTheUserCanDo() {
    val cases =
        mapOf(
            AndroidRecognizer.ERROR_NO_MATCH to SpeechError.NO_SPEECH,
            AndroidRecognizer.ERROR_SPEECH_TIMEOUT to SpeechError.NO_SPEECH,
            AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to SpeechError.NO_PERMISSION,
            AndroidRecognizer.ERROR_RECOGNIZER_BUSY to SpeechError.BUSY,
            AndroidRecognizer.ERROR_TOO_MANY_REQUESTS to SpeechError.BUSY,
            AndroidRecognizer.ERROR_NETWORK to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_NETWORK_TIMEOUT to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_SERVER to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_SERVER_DISCONNECTED to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SpeechError.LANGUAGE_UNAVAILABLE,
            AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SpeechError.LANGUAGE_UNAVAILABLE,
            AndroidRecognizer.ERROR_AUDIO to SpeechError.FAILED,
            AndroidRecognizer.ERROR_CLIENT to SpeechError.FAILED,
            999 to SpeechError.FAILED,
        )
    for ((code, expected) in cases) {
      assertEquals("error $code", expected, speechErrorOf(code))
    }
  }

  /** What one listening session sent so far, and the coroutine collecting it. */
  private class Session(val events: List<SpeechEvent>, val job: Job)

  /** Android's recognizer created by the last [startListening]. */
  private val android
    get() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

  /** Starts listening, and lets Android's recognizer receive its listener and intent. */
  private fun TestScope.startListening(
      language: SpeechLanguage = SpeechLanguage.ENGLISH,
      recognizer: AndroidSpeechRecognizer =
          AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler)),
  ): Session {
    val events = mutableListOf<SpeechEvent>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          recognizer.listen(language).toList(events)
        }
    shadowOf(Looper.getMainLooper()).idle()
    return Session(events, job)
  }

  /** Fails the test: the screen's context was used instead of the app's. */
  private fun screenUsed(): Nothing = throw AssertionError("The screen's context was used")

  /** Android's results holding [text] as the most likely one. */
  private fun results(text: String) =
      Bundle().apply {
        putStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION, arrayListOf(text, "other"))
      }

  /** Makes the phone look like it has a speech recognizer, as most Android phones do. */
  private fun installRecognitionService() {
    val packageManager = shadowOf(context.packageManager)
    packageManager.addServiceIfNotPresent(RECOGNITION_SERVICE)
    packageManager.addIntentFilterForService(
        RECOGNITION_SERVICE,
        IntentFilter(RecognitionService.SERVICE_INTERFACE),
    )
  }

  private companion object {
    val RECOGNITION_SERVICE = ComponentName("com.example.speech", "com.example.speech.Service")
  }
}
