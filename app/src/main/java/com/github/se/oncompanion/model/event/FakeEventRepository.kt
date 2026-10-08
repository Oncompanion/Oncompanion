package com.github.se.oncompanion.model.event

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [EventRepository] backed by a fixed list, used until events are read from Firestore (#91). By
 * default it returns the events of the Figma mockup, scheduled in the coming days so the list is
 * never empty.
 *
 * @param now the current time, to leave out past events
 */
class FakeEventRepository(
    private val events: List<Event> = sampleEvents(LocalDate.now()),
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : EventRepository {

  override fun getUpcomingEvents(): Flow<List<Event>> {
    val from = now()
    return flowOf(
        events.filterNot { it.startDateTime.isBefore(from) }.sortedBy { it.startDateTime }
    )
  }

  override suspend fun getEvent(id: String): Event? = events.find { it.id == id }

  companion object {
    /** The events of the "US-03 / Events" Figma mockup, in the days following [today]. */
    fun sampleEvents(today: LocalDate): List<Event> {
      fun at(daysFromToday: Long, hour: Int, minute: Int = 0) =
          LocalDateTime.of(today.plusDays(daysFromToday), LocalTime.of(hour, minute))

      return listOf(
          Event(
              id = "gentle-yoga",
              title = "Gentle yoga for patients",
              place = "Wellness center, Building B",
              startDateTime = at(1, 10),
              endDateTime = at(1, 11, 30),
              category = "Wellbeing",
              summary =
                  "A relaxing session adapted to people in treatment. Free, all levels welcome.",
              description =
                  "A relaxing session adapted to people in treatment, led by a certified " +
                      "instructor. Mats are provided. Come in comfortable clothes — all levels " +
                      "welcome.",
          ),
          Event(
              id = "nutrition-workshop",
              title = "Nutrition workshop",
              place = "Ligue office, Room 2",
              startDateTime = at(4, 18, 30),
              endDateTime = at(4, 20),
              category = "Nutrition",
              summary = "Simple recipes to keep your energy up during chemotherapy.",
              description =
                  "A dietitian shares simple, gentle recipes to keep your energy up during " +
                      "chemotherapy, and answers your questions about eating when you have " +
                      "little appetite.",
          ),
          Event(
              id = "support-group",
              title = "Patient support group",
              place = "Ligue office, Meeting room",
              startDateTime = at(13, 14),
              endDateTime = at(13, 15, 30),
              category = "Support group",
              summary = "Share your experience with others in a safe and caring space.",
              description =
                  "Share your experience with other patients in a safe and caring space, " +
                      "guided by a psycho-oncology counsellor. No need to speak if you'd " +
                      "rather just listen.",
          ),
          Event(
              id = "pink-october-walk",
              title = "Pink October charity walk",
              place = "Parc des Bastions",
              startDateTime = at(22, 9, 30),
              category = "Fundraising",
              summary = "A 5 km walk to raise awareness and funds for cancer research.",
              description =
                  "A 5 km walk at your own pace to raise awareness and funds for cancer " +
                      "research. Relatives and friends are welcome too.",
          ),
      )
    }
  }
}
