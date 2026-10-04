package com.github.se.oncompanion.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R

/**
 * "Continue with Google" button following Google's Sign in with Google branding guidelines (pill
 * shape, multicolor "G", light or dark theme). The Figma "Google sign-in button" component
 * describes the same values.
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
  val colors = if (darkTheme) GoogleButtonColors.Dark else GoogleButtonColors.Light
  OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      // At least 40 dp (branding), taller with large font sizes so the label is never clipped
      modifier = modifier.fillMaxWidth().heightIn(min = 40.dp),
      shape = RoundedCornerShape(20.dp),
      border = BorderStroke(1.dp, colors.border.copy(alpha = if (enabled) 1f else 0.12f)),
      colors =
          ButtonDefaults.outlinedButtonColors(
              containerColor = colors.container,
              contentColor = colors.content,
              disabledContainerColor = colors.container.copy(alpha = 0.38f),
              disabledContentColor = colors.content.copy(alpha = 0.38f),
          ),
      // Vertical padding keeps space around the label when large fonts make the button grow
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
  ) {
    // The "G" keeps its own colors (no tint), as the branding guidelines require
    Icon(
        painter = painterResource(R.drawable.ic_google_logo),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.width(10.dp))
    Text(stringResource(R.string.sign_in_with_google), style = MaterialTheme.typography.labelLarge)
  }
}

/** Colors from Google's branding guidelines. */
private enum class GoogleButtonColors(val container: Color, val border: Color, val content: Color) {
  Light(container = Color(0xFFFFFFFF), border = Color(0xFF747775), content = Color(0xFF1F1F1F)),
  Dark(container = Color(0xFF131314), border = Color(0xFF8E918F), content = Color(0xFFE3E3E3)),
}
