// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.symptom.SymptomDraft
import com.github.se.oncompanion.model.speech.SpeechError
import com.github.se.oncompanion.model.speech.SpeechLanguage
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeBanner
import com.github.se.oncompanion.ui.common.LakeBannerTone
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.isCompactHeight
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalDate

/** The Figma lake's proportions (390 × 270). */
private const val LAKE_RATIO = 390f / 270f

/**
 * "Speak your symptoms" (US-44, Figma: Voice logging): the patient dictates the symptom, sees what
 * is picked up, then goes back to the form to check it. Shown by the add symptom screen instead of
 * its form while [voice] isn't null; stateless, so each state can be tested on its own.
 *
 * @param today the day it is, for when a symptom was felt ("Yesterday · 21:00")
 * @param onClose goes back to the form without what was said (close, Type instead, system Back)
 * @param onAllowMicrophone asks Android for the microphone, after the app said why
 * @param onOpenSettings opens this app's page in the phone's settings, to allow the microphone
 * @param onDone fills the form with what was heard
 * @param onStartOver drops what was heard and listens again
 * @param onDownloadForOffline downloads the language for use without a connection, then listens
 * @param onNotNow listens without downloading the language
 */
@Composable
fun VoiceSymptomContent(
    voice: VoiceUiState,
    today: LocalDate,
    onClose: () -> Unit,
    onAllowMicrophone: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    onStartOver: () -> Unit,
    onDownloadForOffline: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val actions =
      VoiceActionHandlers(
          onClose,
          onAllowMicrophone,
          onOpenSettings,
          onDone,
          onStartOver,
          onDownloadForOffline,
          onNotNow,
      )
  val sun = voice.sun()
  val level = (voice as? VoiceUiState.Listening)?.level ?: 0f
  Surface(modifier.fillMaxSize().testTag(C.Tag.symptom_voice_screen)) {
    // Held sideways, the lake takes the left half and the words and buttons the right
    if (isCompactHeight()) {
      Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
          VoiceLake(sun, level, Modifier.fillMaxSize().testTag(C.Tag.symptom_voice_lake))
          VoiceTopBar(onClose)
        }
        Column(
            Modifier.weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
          VoiceMessage(voice, today)
          VoiceActions(voice, actions)
        }
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        Box {
          VoiceLake(
              sun,
              level,
              Modifier.fillMaxWidth().aspectRatio(LAKE_RATIO).testTag(C.Tag.symptom_voice_lake),
          )
          VoiceTopBar(onClose)
        }
        Column(
            Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
          VoiceMessage(voice, today)
        }
        // The buttons stay at the bottom while the words scroll
        Column(
            Modifier.navigationBarsPadding()
                .padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.md,
                    bottom = Spacing.xl,
                )
        ) {
          VoiceActions(voice, actions)
        }
      }
    }
  }
}

/** What the voice screen's buttons do, passed down as one. */
private class VoiceActionHandlers(
    val onClose: () -> Unit,
    val onAllowMicrophone: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onDone: () -> Unit,
    val onStartOver: () -> Unit,
    val onDownloadForOffline: () -> Unit,
    val onNotNow: () -> Unit,
)

/** The language's name, as the offer and the downloading note say it. */
private val SpeechLanguage.label: Int
  get() =
      when (this) {
        SpeechLanguage.ENGLISH -> R.string.symptom_voice_language_english
        SpeechLanguage.FRENCH -> R.string.symptom_voice_language_french
      }

/** Where the sun is for each state: it is the microphone, so it shows only what can listen. */
private fun VoiceUiState.sun(): VoiceSun =
    when (this) {
      // Before listening, the sun waits low behind the Salève
      VoiceUiState.ExplainingMicrophone,
      is VoiceUiState.OfferingOffline,
      VoiceUiState.Preparing -> VoiceSun.SETTLED
      is VoiceUiState.Listening -> if (isFinishing) VoiceSun.SETTLED else VoiceSun.LISTENING
      is VoiceUiState.Heard -> VoiceSun.RESTING
      VoiceUiState.MicrophoneRefused,
      is VoiceUiState.Failed -> VoiceSun.HIDDEN
    }

/** The title on the sky, with the button back to the form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceTopBar(onClose: () -> Unit) {
  val onHorizon = MaterialTheme.lakeColors.onHorizon
  // Transparent, so the sky shows behind the title
  TopAppBar(
      title = { Text(stringResource(R.string.symptom_voice_title)) },
      navigationIcon = {
        IconButton(onClick = onClose, modifier = Modifier.testTag(C.Tag.symptom_voice_close)) {
          Icon(painterResource(R.drawable.ic_close), stringResource(R.string.symptom_voice_close))
        }
      },
      colors =
          TopAppBarDefaults.topAppBarColors(
              containerColor = Color.Transparent,
              titleContentColor = onHorizon,
              navigationIconContentColor = onHorizon,
          ),
  )
}

/**
 * The wording of a state that has only a message: a title and what to do, naming [language] when
 * the text is about one.
 */
private data class Message(val title: Int, val text: Int, val language: SpeechLanguage? = null)

/** The message of [this] state, or `null` when it shows what was said instead. */
private fun VoiceUiState.message(): Message? =
    when (this) {
      VoiceUiState.ExplainingMicrophone ->
          Message(R.string.symptom_voice_why_title, R.string.symptom_voice_why_message)
      is VoiceUiState.OfferingOffline ->
          Message(
              R.string.symptom_voice_offline_offer_title,
              R.string.symptom_voice_offline_offer_message,
              language,
          )
      VoiceUiState.MicrophoneRefused ->
          Message(R.string.symptom_voice_refused_title, R.string.symptom_voice_refused_message)
      is VoiceUiState.Failed ->
          when (error.kind()) {
            FailureKind.NOTHING_HEARD ->
                Message(
                    R.string.symptom_voice_no_speech_title,
                    R.string.symptom_voice_no_speech_message,
                )
            FailureKind.FAILED ->
                Message(R.string.symptom_voice_failed_title, R.string.symptom_voice_failed_message)
            FailureKind.OFFLINE ->
                Message(
                    R.string.symptom_voice_offline_title,
                    R.string.symptom_voice_offline_message,
                )
            FailureKind.UNAVAILABLE ->
                Message(
                    R.string.symptom_voice_unavailable_title,
                    R.string.symptom_voice_unavailable_message,
                )
          }
      VoiceUiState.Preparing,
      is VoiceUiState.Listening,
      is VoiceUiState.Heard -> null
    }

/** What a failure leaves the patient to do, which decides its message and buttons. */
private enum class FailureKind {
  /** Nothing was heard: try again. */
  NOTHING_HEARD,
  /** The recognizer failed, though the patient may have spoken: try again. */
  FAILED,
  /** The phone needs a connection for this language: try again once connected. */
  OFFLINE,
  /** The phone can't listen here: typing is the only way. */
  UNAVAILABLE,
}

private fun SpeechError.kind(): FailureKind =
    when (this) {
      SpeechError.NEEDS_CONNECTION -> FailureKind.OFFLINE
      SpeechError.LANGUAGE_UNAVAILABLE,
      SpeechError.UNAVAILABLE -> FailureKind.UNAVAILABLE
      SpeechError.NO_SPEECH -> FailureKind.NOTHING_HEARD
      // A refused microphone has its own state; listed so every error has a message
      SpeechError.NO_PERMISSION,
      SpeechError.BUSY,
      SpeechError.FAILED -> FailureKind.FAILED
    }

/** The headline, then what was said and picked up, or a message. */
@Composable
private fun VoiceMessage(voice: VoiceUiState, today: LocalDate) {
  val message = voice.message()
  // While the phone downloads the language, a note says voice still needs a connection
  val downloading = (voice as? VoiceUiState.Listening)?.downloading
  if (downloading != null) {
    LakeBanner(
        message =
            stringResource(R.string.symptom_voice_downloading, stringResource(downloading.label)),
        tone = LakeBannerTone.Waiting,
        modifier = Modifier.testTag(C.Tag.symptom_voice_downloading),
    )
  }
  val headline =
      when {
        message != null -> stringResource(message.title)
        voice == VoiceUiState.Preparing -> stringResource(R.string.symptom_voice_preparing)
        voice is VoiceUiState.Heard -> stringResource(R.string.symptom_voice_heard)
        (voice as VoiceUiState.Listening).isFinishing ->
            stringResource(R.string.symptom_voice_finishing)
        else -> stringResource(R.string.symptom_voice_listening)
      }
  Text(
      text = headline,
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
      modifier =
          Modifier.fillMaxWidth().semantics { heading() }.testTag(C.Tag.symptom_voice_headline),
  )
  val (text, draft) =
      when (voice) {
        is VoiceUiState.Listening -> voice.text to voice.draft
        is VoiceUiState.Heard -> voice.transcript to voice.draft
        else -> "" to SymptomDraft()
      }
  when {
    // Nothing is heard yet: no hint, so the patient doesn't start speaking too early
    voice == VoiceUiState.Preparing -> Unit
    message != null ->
        CenteredText(
            message.language?.let { stringResource(message.text, stringResource(it.label)) }
                ?: stringResource(message.text)
        )
    // No words yet: what to say
    text.isEmpty() -> CenteredText(stringResource(R.string.symptom_voice_hint))
    else -> {
      Transcript(text)
      PickedUp(draft, today)
    }
  }
}

@Composable
private fun CenteredText(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
  )
}

/** What the patient said so far, on a card. */
@Composable
private fun Transcript(text: String) {
  Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptom_voice_transcript),
  ) {
    Column(
        Modifier.padding(Spacing.lgPlus),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Text(
          stringResource(R.string.symptom_voice_what_you_said),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          stringResource(R.string.symptom_voice_transcript, text),
          style = MaterialTheme.typography.bodyLarge,
      )
    }
  }
}

/** The fields [draft] fills, as dawn chips (drafted content, DESIGN.md); nothing if none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickedUp(draft: SymptomDraft, today: LocalDate) {
  val labels = buildList {
    draft.types.forEach { add(stringResource(it.label)) }
    draft.intensity?.let { add(stringResource(R.string.symptom_voice_intensity, it)) }
    if (draft.date != null || draft.time != null) {
      val day = dayLabel(draft.date ?: today, today)
      add(draft.time?.let { "$day · ${it.format(symptomTimeFormatter)}" } ?: day)
    }
  }
  if (labels.isEmpty()) return
  Column(
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
      modifier = Modifier.testTag(C.Tag.symptom_voice_picked_up),
  ) {
    Text(
        stringResource(R.string.symptom_voice_picked_up),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      labels.forEach { label ->
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Text(
              label,
              style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
          )
        }
      }
    }
  }
}

/** The buttons of each state: one filled main action, then the ways out. */
@Composable
private fun VoiceActions(voice: VoiceUiState, actions: VoiceActionHandlers) {
  val onClose = actions.onClose
  val onDone = actions.onDone
  val onStartOver = actions.onStartOver
  val typeInstead = stringResource(R.string.symptom_voice_type_instead)
  Column(
      Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    when (voice) {
      VoiceUiState.ExplainingMicrophone ->
          Main(
              stringResource(R.string.symptom_voice_allow),
              C.Tag.symptom_voice_allow,
              actions.onAllowMicrophone,
          )
      VoiceUiState.MicrophoneRefused ->
          Main(
              stringResource(R.string.symptom_voice_open_settings),
              C.Tag.symptom_voice_open_settings,
              actions.onOpenSettings,
          )
      is VoiceUiState.OfferingOffline ->
          Main(
              stringResource(R.string.symptom_voice_download, stringResource(voice.language.label)),
              C.Tag.symptom_voice_download,
              actions.onDownloadForOffline,
          )
      // Shown but off until the phone listens, so the buttons don't jump
      VoiceUiState.Preparing ->
          LakeButton(
              text = stringResource(R.string.symptom_voice_done),
              onClick = onDone,
              enabled = false,
              modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptom_voice_done),
          )
      is VoiceUiState.Listening ->
          LakeButton(
              text = stringResource(R.string.symptom_voice_done),
              onClick = onDone,
              isWorking = voice.isFinishing,
              modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptom_voice_done),
          )
      is VoiceUiState.Heard ->
          Main(stringResource(R.string.symptom_voice_done), C.Tag.symptom_voice_done, onDone)
      is VoiceUiState.Failed ->
          if (voice.error.kind() != FailureKind.UNAVAILABLE) {
            Main(
                stringResource(R.string.symptom_voice_try_again),
                C.Tag.symptom_voice_try_again,
                onStartOver,
            )
          }
    }
    // Typing is always a way out; it is the main action when the phone can't listen
    val onlyTyping = voice is VoiceUiState.Failed && voice.error.kind() == FailureKind.UNAVAILABLE
    if (voice is VoiceUiState.OfferingOffline) {
      // Declining still listens: the close button is the way back to typing
      Quiet(
          stringResource(R.string.symptom_voice_not_now),
          C.Tag.symptom_voice_not_now,
          actions.onNotNow,
      )
    } else if (voice is VoiceUiState.Heard) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Quiet(
            stringResource(R.string.symptom_voice_start_over),
            C.Tag.symptom_voice_start_over,
            onStartOver,
            Modifier.weight(1f),
        )
        Quiet(typeInstead, C.Tag.symptom_voice_type_instead, onClose, Modifier.weight(1f))
      }
    } else if (onlyTyping) {
      Main(typeInstead, C.Tag.symptom_voice_type_instead, onClose)
    } else {
      Quiet(typeInstead, C.Tag.symptom_voice_type_instead, onClose)
    }
  }
}

/** The screen's one filled button. */
@Composable
private fun Main(text: String, testTag: String, onClick: () -> Unit) {
  LakeButton(text = text, onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(testTag))
}

/** A text button, for the ways out. */
@Composable
private fun Quiet(
    text: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  LakeButton(
      text = text,
      onClick = onClick,
      style = LakeButtonStyle.Text,
      modifier = modifier.testTag(testTag),
  )
}
