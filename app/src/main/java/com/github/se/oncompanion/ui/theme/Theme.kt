// Co-authored-by: ChatGPT <noreply@openai.com>

// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.settings.AppearancePreference

private val LightColorScheme =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight,
        onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight,
        onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        background = SurfaceLight,
        onBackground = OnSurfaceLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceContainerHighestLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        inverseSurface = InverseSurfaceLight,
        inverseOnSurface = InverseOnSurfaceLight,
        inversePrimary = InversePrimaryLight,
        surfaceContainerLowest = SurfaceContainerLowestLight,
        surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight,
        surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        background = SurfaceDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceContainerHighestDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        inverseSurface = InverseSurfaceDark,
        inverseOnSurface = InverseOnSurfaceDark,
        inversePrimary = InversePrimaryDark,
        surfaceContainerLowest = SurfaceContainerLowestDark,
        surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
    )

/**
 * Oncompanion theme: the "Morning on the lake" colors of DESIGN.md, in light and dark, with the
 * extra colors in [MaterialTheme.lakeColors], the Figtree type scale and the corner radii. Spacing
 * is in [Spacing]. Dynamic (wallpaper) colors are intentionally not used, so the app looks the same
 * on every device.
 */
@Composable
fun OncompanionTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  CompositionLocalProvider(
      LocalAppDarkTheme provides darkTheme,
      LocalLakeColors provides if (darkTheme) DarkLakeColors else LightLakeColors,
  ) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
  }
}

/** Effective app appearance, including explicit overrides, for branded controls. */
internal val LocalAppDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/** Applies one persisted preference to the existing palettes without replacing screen state. */
@Composable
fun AppAppearanceTheme(preference: AppearancePreference, content: @Composable () -> Unit) {
  AppAppearanceTheme(darkTheme = preference.usesDarkTheme(isSystemInDarkTheme()), content = content)
}

/** Keeps Android drawable and string resources in the same appearance as the Compose palette. */
@Composable
internal fun AppAppearanceTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
  val configuration = LocalConfiguration.current
  val context = LocalContext.current
  // An override wrapper preserves the Activity in the context chain for scanning and sign-in.
  val themed =
      remember(configuration, context, darkTheme) {
        val night =
            if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config =
            Configuration(configuration).apply {
              uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
        // Compose caches vectors by Theme equality, which ignores the resource configuration.
        val overlay =
            if (darkTheme) R.style.OncompanionResourcesDark else R.style.OncompanionResourcesLight
        config to ContextThemeWrapper(context, overlay).apply { applyOverrideConfiguration(config) }
      }
  CompositionLocalProvider(
      LocalConfiguration provides themed.first,
      LocalContext provides themed.second,
  ) {
    OncompanionTheme(darkTheme, content)
  }
}
