package com.github.se.oncompanion.model.hospital

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
  fun blankQueryIsEmptyEvenWhenFailureIsConfigured() = runTest {
    val fake = FakeHospitalRepository()
    fake.result = HospitalSearchResult.Error(HospitalSearchError.SERVICE)
    assertEquals(HospitalSearchResult.Success(emptyList()), fake.searchHospitals("  "))
    assertTrue(fake.queries.isEmpty())
  }
}
