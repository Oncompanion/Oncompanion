// Co-authored-by: Codex
package com.github.se.oncompanion.ui.common

import com.github.se.oncompanion.model.hospital.FakeHospitalRepository
import com.github.se.oncompanion.model.hospital.Hospital
import com.github.se.oncompanion.model.hospital.HospitalRepository
import com.github.se.oncompanion.model.hospital.HospitalSearchError
import com.github.se.oncompanion.model.hospital.HospitalSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HospitalFieldViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val fake = FakeHospitalRepository()
  private val hospital = Hospital("way/1", "Hospital", "Geneva", 46.2, 6.1)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun constructionAndBlankSubmissionNeverSearch() = runTest {
    val vm = HospitalFieldViewModel(fake)
    vm.search(" \n ")
    advanceUntilIdle()
    assertEquals(HospitalFieldUiState.Idle, vm.uiState.value)
    assertTrue(fake.queries.isEmpty())
  }

  @Test
  fun explicitSearchLoadsMatchesAndIgnoresDuplicateSubmissionWhileLoading() = runTest {
    fake.result = HospitalSearchResult.Success(listOf(hospital))
    val vm = HospitalFieldViewModel(fake)
    vm.search(" Geneva ")
    assertEquals(HospitalFieldUiState.Loading, vm.uiState.value)
    vm.search("Geneva")
    advanceUntilIdle()
    assertEquals(listOf("Geneva"), fake.queries)
    assertEquals(HospitalFieldUiState.Results(listOf(hospital)), vm.uiState.value)
  }

  @Test
  fun emptyResponseIsDistinctFromIdleAndErrorsCanRetry() = runTest {
    val vm = HospitalFieldViewModel(fake)
    HospitalSearchError.entries.forEach { error ->
      fake.result = HospitalSearchResult.Error(error)
      vm.search("Geneva")
      advanceUntilIdle()
      assertEquals(HospitalFieldUiState.Error, vm.uiState.value)
    }
    fake.result = HospitalSearchResult.Success(emptyList())
    vm.search("Geneva")
    advanceUntilIdle()
    assertEquals(HospitalFieldUiState.Results(emptyList()), vm.uiState.value)
  }

  @Test
  fun unexpectedRepositoryFailureIsRecoverable() = runTest {
    val vm =
        HospitalFieldViewModel(
            object : HospitalRepository by fake {
              override suspend fun searchHospitals(searchText: String): HospitalSearchResult =
                  error("Failure")
            }
        )
    vm.search("Geneva")
    advanceUntilIdle()
    assertEquals(HospitalFieldUiState.Error, vm.uiState.value)
  }

  @Test
  fun repositoryCancellationDoesNotBecomeAnError() = runTest {
    val vm =
        HospitalFieldViewModel(
            object : HospitalRepository by fake {
              override suspend fun searchHospitals(searchText: String): HospitalSearchResult =
                  throw CancellationException()
            }
        )
    vm.search("Geneva")
    advanceUntilIdle()
    assertEquals(HospitalFieldUiState.Idle, vm.uiState.value)
  }

  @Test
  fun clearingCancelsPendingSearchWithoutAnError() = runTest {
    val pending = CompletableDeferred<Unit>()
    val vm =
        HospitalFieldViewModel(
            object : HospitalRepository by fake {
              override suspend fun searchHospitals(searchText: String): HospitalSearchResult {
                pending.await()
                return fake.searchHospitals(searchText)
              }
            }
        )
    vm.search("Geneva")
    runCurrent()
    vm.clearSearch()
    advanceUntilIdle()
    assertEquals(HospitalFieldUiState.Idle, vm.uiState.value)
    assertTrue(fake.queries.isEmpty())
  }

  @Test
  fun lateSuccessCannotReplaceNewerSearchFeedback() = runTest {
    val pending = CompletableDeferred<Unit>()
    val vm =
        HospitalFieldViewModel(
            object : HospitalRepository by fake {
              override suspend fun searchHospitals(searchText: String): HospitalSearchResult {
                if (searchText == "old") {
                  withContext(NonCancellable) { pending.await() }
                  return HospitalSearchResult.Success(listOf(hospital))
                }
                return fake.searchHospitals(searchText)
              }
            }
        )
    vm.search("old")
    runCurrent()
    vm.clearSearch()
    vm.search("new")
    runCurrent()
    pending.complete(Unit)
    advanceUntilIdle()
    assertEquals(HospitalFieldUiState.Results(emptyList()), vm.uiState.value)
  }

  @Test
  fun lateFailureCannotReplaceNewerSearchFeedback() = runTest {
    val pending = CompletableDeferred<Unit>()
    val vm =
        HospitalFieldViewModel(
            object : HospitalRepository by fake {
              override suspend fun searchHospitals(searchText: String): HospitalSearchResult {
                withContext(NonCancellable) { pending.await() }
                error("Late failure")
              }
            }
        )
    vm.search("old")
    runCurrent()
    vm.clearSearch()
    pending.complete(Unit)
    advanceUntilIdle()
    assertEquals(HospitalFieldUiState.Idle, vm.uiState.value)
  }
}
