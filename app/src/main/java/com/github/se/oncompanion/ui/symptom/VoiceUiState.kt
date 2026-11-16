// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import com.github.se.oncompanion.domain.symptom.SymptomDraft
import com.github.se.oncompanion.model.speech.SpeechError

/**
 * Where dictating a symptom is (US-44), shown instead of the form while it lasts. The form comes
 * back when the patient uses what was heard or cancels.
 */
sealed interface VoiceUiState {
  /** Why the app needs the microphone, said before Android asks for it. */
  data object ExplainingMicrophone : VoiceUiState

  /** The microphone isn't allowed: how to allow it, and that typing still works. */
  data object MicrophoneRefused : VoiceUiState

  /** Listening, with the [text] understood so far (empty until the first words). */
  data class Listening(val text: String = "") : VoiceUiState

  /**
   * Listening ended with a [transcript], and the [draft] of the form drafted from it, for the
   * patient to use or say again.
   */
  data class Heard(val transcript: String, val draft: SymptomDraft) : VoiceUiState

  /** Listening ended without a text, because of [error]; the patient can try again. */
  data class Failed(val error: SpeechError) : VoiceUiState
}
