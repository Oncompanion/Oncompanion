// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that a Planning source of one section is only read when that section is shared, also once
 * merged with the others and made optional, as the default Planning builds it.
 */
class SectionPlanningSourceTest {

  private val monday = LocalDate.of(2026, 10, 19)
  private val week = PlanningRange(monday, monday.plusWeeks(1), ZoneId.of("Europe/Zurich"))
  private val yoga =
      PlanningItem(
          PlanningSource.Event("yoga"),
          PlanningTiming.Timed(week.startInclusive.plusSeconds(3600)),
          "Gentle yoga",
      )

  private val events = FakePlanningRepository().apply { items.value = listOf(yoga) }

  /** Events as the default Planning has them: optional, merged with the other sources. */
  private val planning =
      ObservePlanning(SectionPlanningSource(CarePermission.EVENTS, OptionalPlanningSource(events)))

  @Test
  fun sectionShared_givesItsEntries_andPassesTheSectionsOn() = runTest {
    val shared = setOf(CarePermission.PLANNING, CarePermission.EVENTS)

    assertEquals(listOf(yoga), planning.observeItems("alice", week, shared).first())
    assertEquals(listOf<Set<CarePermission>?>(shared), events.sections)
  }

  @Test
  fun sectionNotShared_givesNoEntries_andIsNotRead() = runTest {
    val items = planning.observeItems("alice", week, setOf(CarePermission.PLANNING)).first()

    assertTrue(items.isEmpty())
    assertTrue(events.owners.isEmpty())
  }

  @Test
  fun withoutSections_givesEveryEntry() = runTest {
    assertEquals(listOf(yoga), planning.observeItems("alice", week).first())
  }
}
