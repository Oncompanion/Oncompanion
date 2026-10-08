package com.github.se.oncompanion.ui.planning

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Preview-only fixtures; never installed as the application's repository. */
@Composable
private fun PlanningPreviewContent(
    empty: Boolean = false,
    loading: Boolean = false,
    error: Boolean = false,
) {
  val today = LocalDate.of(2026, 10, 2)
  val zone = ZoneId.of("Europe/Zurich")
  var selected by remember { mutableStateOf(today) }
  val entries =
      if (empty) emptyList()
      else
          listOf(
              PlanningItem(
                  PlanningSource.Medication("prescription", "medication-a"),
                  PlanningTiming.DateOnly(today),
                  "Medication A",
                  frequency = "Twice a day",
              ),
              PlanningItem(
                  PlanningSource.Medication("prescription", "medication-b"),
                  PlanningTiming.DateOnly(today),
                  "Medication B",
                  frequency = "Once a day",
              ),
              PlanningItem(
                  PlanningSource.Appointment("consultation"),
                  PlanningTiming.Timed(today.atTime(9, 0).atZone(zone).toInstant()),
                  "Doctor consultation",
                  "Hospital, Room 3",
              ),
              PlanningItem(
                  PlanningSource.Event("support-workshop"),
                  PlanningTiming.Timed(today.atTime(14, 30).atZone(zone).toInstant()),
                  "Support workshop",
                  "Ligue · Geneva",
              ),
              PlanningItem(
                  PlanningSource.Appointment("next-week"),
                  PlanningTiming.Timed(today.plusWeeks(1).atTime(10, 0).atZone(zone).toInstant()),
                  "Doctor consultation",
                  "Clinic",
              ),
          )
  OncompanionTheme {
    PlanningContent(
        PlanningUiState(
            selected,
            selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            untimedItems =
                entries.filter {
                  it.timing.dateIn(zone) == selected && it.timing is PlanningTiming.DateOnly
                },
            scheduledItems =
                entries.filter {
                  it.timing.dateIn(zone) == selected && it.timing is PlanningTiming.Timed
                },
            isLoading = loading,
            hasError = error,
        ),
        today,
        zone,
        actions =
            PlanningActions(
                onDateSelected = { selected = it },
                onPreviousWeek = { selected = selected.minusWeeks(1) },
                onNextWeek = { selected = selected.plusWeeks(1) },
                onToday = { selected = today },
                onRetry = {},
            ),
    )
  }
}

@Preview(
    name = "Planning — mixed fake entries",
    showBackground = true,
    widthDp = 412,
    heightDp = 820,
)
@Composable
fun PlanningFilledPreview() = PlanningPreviewContent()

@Preview(name = "Planning — empty", showBackground = true, widthDp = 412, heightDp = 820)
@Composable
fun PlanningEmptyPreview() = PlanningPreviewContent(empty = true)

@Preview(name = "Planning — loading", showBackground = true, widthDp = 412, heightDp = 820)
@Composable
fun PlanningLoadingPreview() = PlanningPreviewContent(empty = true, loading = true)

@Preview(name = "Planning — error", showBackground = true, widthDp = 412, heightDp = 820)
@Composable
fun PlanningErrorPreview() = PlanningPreviewContent(empty = true, error = true)

@Preview(
    name = "Planning — large text",
    showBackground = true,
    widthDp = 412,
    heightDp = 820,
    fontScale = 1.5f,
)
@Composable
fun PlanningLargeTextPreview() = PlanningPreviewContent()
