// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.symptom.label
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.elevation1
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The check-in's tiles and their Figma icons, in the Figma's order (M3 page, Overview → Patient →
 * Home): five symptoms in one tap, then "More…" (`null`) for the others.
 */
private val checkInTiles =
    listOf(
        SymptomType.FATIGUE to R.drawable.ic_symptom_fatigue,
        SymptomType.NAUSEA to R.drawable.ic_symptom_nausea,
        SymptomType.PAIN to R.drawable.ic_symptom_pain,
        SymptomType.HEADACHE to R.drawable.ic_symptom_headache,
        SymptomType.FEVER to R.drawable.ic_symptom_fever,
        null to R.drawable.ic_symptom_more,
    )

/**
 * "How do you feel?" (Overview UX Refactor): the patient logs a symptom in one tap, as often as
 * they like during the day. A tile opens the symptom form with that symptom picked, "More…" opens
 * it empty, and "Journal ›" opens the journal.
 *
 * @param onLogSymptom opens the symptom form with that symptom picked, or empty for `null`
 */
@Composable
internal fun CheckInDock(
    onLogSymptom: (SymptomType?) -> Unit,
    onOpenJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val shape = MaterialTheme.shapes.extraLarge
  Surface(
      modifier = modifier.fillMaxWidth().elevation1(shape).testTag(C.Tag.overview_check_in),
      shape = shape,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        CardBadge(R.drawable.ic_heart, iconSize = HEART_SIZE)
        Text(
            stringResource(R.string.overview_check_in_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        JournalLink(onOpenJournal)
      }
      val labels = checkInTiles.map { (type, _) ->
        stringResource(type?.label ?: R.string.overview_check_in_more)
      }
      CheckInTiles(labels, onLogSymptom)
    }
  }
}

/** "Journal ›", a 48 dp target opening the symptom journal. */
@Composable
private fun JournalLink(onOpenJournal: () -> Unit) {
  Row(
      Modifier.heightIn(min = 48.dp)
          .clip(MaterialTheme.shapes.medium)
          .clickable(role = Role.Button, onClick = onOpenJournal)
          .padding(start = Spacing.xs)
          .testTag(C.Tag.overview_journal_link),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Text(
        stringResource(R.string.overview_journal_link),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.lakeColors.primaryText,
    )
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.lakeColors.primaryText,
        modifier = Modifier.size(18.dp),
    )
  }
}

/**
 * The tiles in rows of the same width: three per row as in the Figma, all six in one row when the
 * screen is wide (landscape), and fewer per row when a label wouldn't fit (small screen or large
 * font), so a word is never broken.
 */
@Composable
private fun CheckInTiles(labels: List<String>, onLogSymptom: (SymptomType?) -> Unit) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Measured again only when the labels, their style or the font size change
    val widestLabel =
        remember(labels, style, density) {
          with(density) { labels.maxOf { measurer.measure(it, style).size.width }.toDp() }
        }
    val fits = { perRow: Int ->
      (maxWidth - TILE_SPACING * (perRow - 1)) / perRow - TILE_PADDING * 2 >= widestLabel
    }
    val candidates = if (maxWidth >= WIDE) listOf(labels.size, 3, 2) else listOf(3, 2)
    val perRow = candidates.firstOrNull(fits) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
      checkInTiles.zip(labels).chunked(perRow).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
          row.forEach { (tile, label) ->
            val (type, icon) = tile
            SymptomTile(type, label, icon, { onLogSymptom(type) }, Modifier.weight(1f))
          }
          // A shorter last row keeps the tiles the same width as above
          repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

/**
 * A symptom tile ("More…" for a `null` [type]): its icon above its label. Every symptom looks the
 * same on purpose, so none seems more serious (Figma "Symptom tile").
 */
@Composable
private fun SymptomTile(
    type: SymptomType?,
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
  Surface(
      onClick = onClick,
      modifier =
          modifier
              .heightIn(min = TILE_HEIGHT)
              .testTag(
                  type?.let { C.Tag.overviewCheckIn(it.name) } ?: C.Tag.overview_check_in_more
              ),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(
        Modifier.padding(horizontal = TILE_PADDING, vertical = TILE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
    ) {
      Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
      Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
  }
}

/** The Figma's heart in the dock's badge, a little larger than the other cards' icons. */
private val HEART_SIZE = 20.dp

/** The Figma's tile: 80 dp tall at least (it grows with a large font), 8 dp apart. */
private val TILE_HEIGHT = 80.dp
private val TILE_SPACING = Spacing.sm
private val TILE_PADDING = Spacing.xs

/** From this width (a phone held sideways), the six tiles fit in one row. */
private val WIDE = 600.dp
