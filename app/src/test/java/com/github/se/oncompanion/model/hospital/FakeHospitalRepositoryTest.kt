// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies configurable search outcomes and controllable cancellation for consumer tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeHospitalRepositoryTest {
  @Test
  fun repositorySupportsSuspendLambdaImplementations() = runTest {
    val hospital = Hospital("way/1", "Hospital", "Geneva", 46.2, 6.1)
    val repository = HospitalRepository { query ->
      if (query == "Geneva") HospitalSearchResult.Success(listOf(hospital))
      else HospitalSearchResult.Success(emptyList())
    }
    assertEquals(
        HospitalSearchResult.Success(listOf(hospital)),
        repository.searchHospitals("Geneva"),
    )
    assertEquals(HospitalSearchResult.Success(emptyList()), repository.searchHospitals("Unknown"))
  }

  @Test
  fun suppliesSuccessfulEmptyAndErrorOutcomesForConsumers() = runTest {
    val fake = FakeHospitalRepository()
    assertEquals(HospitalSearchResult.Success(emptyList()), fake.searchHospitals("Geneva"))
    val hospitals = listOf(Hospital("way/1", "Hospital", "Geneva", 46.2, 6.1))
    fake.result = HospitalSearchResult.Success(hospitals)
    assertEquals(HospitalSearchResult.Success(hospitals), fake.searchHospitals(" Lausanne "))
    fake.result = HospitalSearchResult.Error(HospitalSearchError.NETWORK)
    assertEquals(fake.result, fake.searchHospitals("Bern"))
    assertEquals(listOf("Geneva", "Lausanne", "Bern"), fake.queries)
  }

  @Test
  fun pendingResponseWaitsForExplicitCompletion() = runTest {
    val fake = FakeHospitalRepository()
    val pending = CompletableDeferred<HospitalSearchResult>()
    fake.pendingResult = pending
    var response: HospitalSearchResult? = null
    launch(StandardTestDispatcher(testScheduler)) { response = fake.searchHospitals("Geneva") }
    runCurrent()
    assertEquals(listOf("Geneva"), fake.queries)
    assertTrue(fake.completedQueries.isEmpty())
    val expected = HospitalSearchResult.Error(HospitalSearchError.NETWORK)
    pending.complete(expected)
    advanceUntilIdle()
    assertEquals(expected, response)
    assertEquals(listOf("Geneva"), fake.completedQueries)
  }

  @Test
  fun cooperativePendingResponseRecordsCancellation() = runTest {
    val fake = FakeHospitalRepository()
    fake.pendingResult = CompletableDeferred()
    val search = launch(StandardTestDispatcher(testScheduler)) { fake.searchHospitals("Geneva") }
    runCurrent()
    search.cancel()
    advanceUntilIdle()
    assertEquals(listOf("Geneva"), fake.cancelledQueries)
    assertTrue(fake.completedQueries.isEmpty())
  }

  @Test
  fun uncancellableResponseCompletesAfterCallerCancellation() = runTest {
    val fake = FakeHospitalRepository()
    val pending = CompletableDeferred<HospitalSearchResult>()
    fake.pendingResult = pending
    fake.ignoreCancellation = true
    val search = launch(StandardTestDispatcher(testScheduler)) { fake.searchHospitals("Geneva") }
    runCurrent()
    search.cancel()
    pending.complete(HospitalSearchResult.Success(emptyList()))
    advanceUntilIdle()
    assertEquals(listOf("Geneva"), fake.completedQueries)
    assertTrue(fake.cancelledQueries.isEmpty())
  }

  @Test
  fun blankQueryIsEmptyEvenWhenFailureIsConfigured() = runTest {
    val fake = FakeHospitalRepository()
    fake.result = HospitalSearchResult.Error(HospitalSearchError.SERVICE)
    assertEquals(HospitalSearchResult.Success(emptyList()), fake.searchHospitals("  "))
    assertTrue(fake.queries.isEmpty())
  }
}
