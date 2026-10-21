// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.auth.SessionMemory
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.resources.C

/** Destroys active and saved authenticated ViewModels and blocks access until cleanup succeeds. */
@Composable
fun SessionBoundary(
    credentials: GoogleCredentialProvider,
    content: @Composable (afterCleanup: Boolean) -> Unit,
) {
  val signOut = SignOutProvider.signOut
  val phase by signOut.state.collectAsStateWithLifecycle()
  val generation by signOut.generation.collectAsStateWithLifecycle()
  val blocked = phase == SignOutState.CLEANING || phase == SignOutState.FAILED
  if (blocked) {
    val context = LocalContext.current
    BackHandler {}
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(C.Tag.auth_cleanup),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (phase == SignOutState.FAILED) {
        Text(stringResource(R.string.auth_cleanup_failed))
        Button(
            onClick = { signOut.retry { credentials.clearCredentialState(context) } },
            modifier = Modifier.testTag(C.Tag.auth_cleanup_retry),
        ) {
          Text(stringResource(R.string.profile_retry))
        }
      } else {
        CircularProgressIndicator()
        Text(stringResource(R.string.auth_cleanup_loading))
      }
    }
  } else {
    // The entire navigation store, including saved bottom tabs, belongs to this session alone.
    key(generation) {
      val owner = remember {
        object : ViewModelStoreOwner, SessionMemory {
              override val viewModelStore = ViewModelStore()

              override fun clear() {
                viewModelStore.clear()
              }
            }
            .also(signOut::register)
      }
      val afterCleanup = remember(owner) { phase == SignOutState.COMPLETE }
      DisposableEffect(owner) {
        onDispose {
          owner.clear()
          signOut.unregister(owner)
        }
      }
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content(afterCleanup) }
    }
  }
}
