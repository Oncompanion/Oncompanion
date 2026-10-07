package com.github.se.oncompanion.ui.profile

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val original =
      UserProfile("uid-1", Role.PATIENT, "Alex", "Doe", "Lymphoma", Instant.EPOCH)
  private lateinit var auth: FakeAuthRepository
  private lateinit var profiles: FakeUserProfileRepository
  private val writes = mutableListOf<UserProfile>()
  private var gate: CompletableDeferred<Unit>? = null
  private var ignoreCancellation = false
  private lateinit var repository: UserProfileRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository { AuthUser(it) }
    profiles = FakeUserProfileRepository().apply { seed(original) }
    repository =
        object : UserProfileRepository by profiles {
          override suspend fun updateProfile(profile: UserProfile) {
            writes += profile
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            profiles.updateProfile(profile)
          }

          override suspend fun createProfile(profile: UserProfile) =
              error("Editing must not create a profile")
        }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun vm() = EditProfileViewModel(auth, repository)

  private suspend fun signIn(uid: String = original.uid) {
    auth.signInWithGoogle(uid)
  }

  @Test
  fun loadingThenPrefillsExistingValues() = runTest {
    signIn()
    val vm = vm()
    assertEquals(EditProfileStatus.LOADING, vm.uiState.value.status)
    advanceUntilIdle()
    assertEquals(
        EditProfileUiState(EditProfileStatus.READY, "Alex", "Doe", "Lymphoma"),
        vm.uiState.value,
    )
    assertTrue(vm.uiState.value.canSave)
  }

  @Test
  fun absentOptionalValuesPrefillAsEmpty() = runTest {
    signIn()
    profiles.seed(original.copy(familyName = null, cancerType = null))
    val vm = vm()
    advanceUntilIdle()
    assertEquals("", vm.uiState.value.familyName)
    assertEquals("", vm.uiState.value.cancerType)
  }

  @Test
  fun unavailableProfilesCannotBeEditedOrSaved() = runTest {
    val vm = vm()
    advanceUntilIdle()
    assertEquals(EditProfileStatus.SIGNED_OUT, vm.uiState.value.status)
    vm.onFirstNameChange("Other")
    vm.saveProfile()
    assertEquals("", vm.uiState.value.firstName)
    signIn("missing")
    advanceUntilIdle()
    assertEquals(EditProfileStatus.MISSING_PROFILE, vm.uiState.value.status)
    vm.saveProfile()
    assertFalse(vm.uiState.value.canSave)
    assertTrue(writes.isEmpty())
    profiles.seed(original.copy(uid = "missing"))
    advanceUntilIdle()
    assertEquals(EditProfileStatus.READY, vm.uiState.value.status)
  }

  @Test
  fun limitsAndBlankFirstNameMatchOnboarding() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange("a".repeat(51))
    vm.onFamilyNameChange("b".repeat(51))
    vm.onCancerTypeChange("c".repeat(101))
    assertEquals(50, vm.uiState.value.firstName.length)
    assertEquals(50, vm.uiState.value.familyName.length)
    assertEquals(100, vm.uiState.value.cancerType.length)
    vm.onFirstNameChange("  ")
    vm.saveProfile()
    advanceUntilIdle()
    assertFalse(vm.uiState.value.canSave)
    assertTrue(writes.isEmpty())
  }

  @Test
  fun saveTrimsUpdatesAndPreservesImmutableFields() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange("  Sam  ")
    vm.onFamilyNameChange("  Moreau  ")
    vm.onCancerTypeChange("  Free text  ")
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(
        original.copy(firstName = "Sam", familyName = "Moreau", cancerType = "Free text"),
        profiles.profiles[original.uid],
    )
    assertTrue(vm.uiState.value.isSaved)
    assertFalse(vm.uiState.value.canSave)
    vm.saveProfile()
    assertEquals(1, writes.size)
    vm.consumeSaved()
    assertFalse(vm.uiState.value.isSaved)
  }

  @Test
  fun optionalFieldsCanBeCleared() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFamilyNameChange("  ")
    vm.onCancerTypeChange("")
    vm.saveProfile()
    advanceUntilIdle()
    assertNull(profiles.profiles[original.uid]!!.familyName)
    assertNull(profiles.profiles[original.uid]!!.cancerType)
  }

  @Test
  fun observationDoesNotOverwriteDraft() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange("Draft")
    profiles.seed(original.copy(firstName = "Remote", createdAt = Instant.ofEpochSecond(42)))
    advanceUntilIdle()
    assertEquals("Draft", vm.uiState.value.firstName)
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(Instant.ofEpochSecond(42), writes.single().createdAt)
  }

  @Test
  fun familyNameEditSavesLatestRemoteCancerType() = runTest {
    signIn()
    profiles.seed(original.copy(cancerType = "A"))
    val vm = vm()
    advanceUntilIdle()
    vm.onFamilyNameChange("Edited")
    val remote = original.copy(firstName = "Remote", familyName = "Elsewhere", cancerType = "B")
    profiles.seed(remote)
    advanceUntilIdle()
    assertEquals("Remote", vm.uiState.value.firstName)
    assertEquals("Edited", vm.uiState.value.familyName)
    assertEquals("B", vm.uiState.value.cancerType)
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(remote.copy(familyName = "Edited"), writes.single())
  }

  @Test
  fun editedAndClearedFieldsSurviveMultipleSnapshots() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange("Draft")
    vm.onCancerTypeChange("")
    profiles.seed(original.copy(firstName = "Remote", familyName = "New", cancerType = "B"))
    advanceUntilIdle()
    profiles.seed(original.copy(firstName = "Other", familyName = null, cancerType = "C"))
    advanceUntilIdle()
    assertEquals("Draft", vm.uiState.value.firstName)
    assertEquals("", vm.uiState.value.familyName)
    assertEquals("", vm.uiState.value.cancerType)
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(
        original.copy(firstName = "Draft", familyName = null, cancerType = null),
        writes.single(),
    )
  }

  @Test
  fun unchangedInputCallbacksDoNotFreezeFields() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange(original.firstName)
    vm.onFamilyNameChange(original.familyName!!)
    vm.onCancerTypeChange(original.cancerType!!)
    val remote = original.copy(firstName = "New", familyName = "Remote", cancerType = "B")
    profiles.seed(remote)
    advanceUntilIdle()
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(remote, writes.single())
  }

  @Test
  fun retryAndAccountChangeResetEditedFields() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange("Draft")
    vm.onFamilyNameChange("Draft")
    vm.onCancerTypeChange("Draft")
    vm.retry()
    advanceUntilIdle()
    profiles.seed(original.copy(firstName = "New", familyName = "New", cancerType = "New"))
    advanceUntilIdle()
    assertEquals(EditProfileUiState(EditProfileStatus.READY, "New", "New", "New"), vm.uiState.value)
    vm.onFirstNameChange("Draft")
    vm.onFamilyNameChange("Draft")
    vm.onCancerTypeChange("Draft")
    signIn("uid-2")
    profiles.seed(original.copy(uid = "uid-2"))
    advanceUntilIdle()
    profiles.seed(
        original.copy(uid = "uid-2", firstName = "Other", familyName = null, cancerType = null)
    )
    advanceUntilIdle()
    assertEquals(EditProfileUiState(EditProfileStatus.READY, "Other"), vm.uiState.value)
  }

  @Test
  fun localCompletionDoesNotReportLaterRollbackAsSaveFailure() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFamilyNameChange("Edited")
    vm.saveProfile()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isSaved)
    profiles.seed(original)
    advanceUntilIdle()
    assertFalse(vm.uiState.value.saveFailed)
    assertTrue(vm.uiState.value.isSaved)
  }

  @Test
  fun pendingWriteIgnoresEditsAndDuplicateSaves() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    gate = CompletableDeferred()
    vm.saveProfile()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isSaving)
    vm.saveProfile()
    vm.onFirstNameChange("Ignored")
    vm.onFamilyNameChange("Ignored")
    vm.onCancerTypeChange("Ignored")
    assertEquals("Alex", vm.uiState.value.firstName)
    assertEquals("Doe", vm.uiState.value.familyName)
    assertEquals("Lymphoma", vm.uiState.value.cancerType)
    assertEquals(1, writes.size)
    gate!!.complete(Unit)
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isSaved)
  }

  @Test
  fun saveFailureRetainsDraftAndCanRetry() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange("Draft")
    profiles.writeError = IllegalStateException("failure")
    vm.saveProfile()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.saveFailed)
    assertFalse(vm.uiState.value.isSaving)
    assertEquals("Draft", vm.uiState.value.firstName)
    assertEquals(original, profiles.profiles[original.uid])
    vm.onFamilyNameChange("Changed")
    assertFalse(vm.uiState.value.saveFailed)
    vm.saveProfile()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.saveFailed)
    profiles.writeError = null
    vm.saveProfile()
    assertFalse(vm.uiState.value.saveFailed)
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isSaved)
  }

  @Test
  fun profileLoadErrorRetriesAndRecovers() = runTest {
    signIn()
    profiles.getProfileError = IllegalStateException("failure")
    val vm = vm()
    advanceUntilIdle()
    assertEquals(EditProfileStatus.ERROR, vm.uiState.value.status)
    profiles.getProfileError = null
    vm.retry()
    advanceUntilIdle()
    assertEquals(EditProfileStatus.READY, vm.uiState.value.status)
  }

  @Test
  fun authenticationObservationErrorIsRecoverable() = runTest {
    var fail = true
    val broken =
        object : AuthRepository by auth {
          override fun observeCurrentUser(): Flow<AuthUser?> =
              if (fail) flow { error("failure") } else auth.observeCurrentUser()
        }
    val vm = EditProfileViewModel(broken, repository)
    advanceUntilIdle()
    assertEquals(EditProfileStatus.ERROR, vm.uiState.value.status)
    fail = false
    signIn()
    vm.retry()
    advanceUntilIdle()
    assertEquals(EditProfileStatus.READY, vm.uiState.value.status)
  }

  @Test
  fun signingOutOrSwitchingAccountsDiscardsDraft() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    vm.onFirstNameChange("Draft")
    auth.signOut()
    advanceUntilIdle()
    assertEquals(EditProfileStatus.SIGNED_OUT, vm.uiState.value.status)
    assertEquals("", vm.uiState.value.firstName)
    profiles.seed(original.copy(uid = "uid-2", firstName = "Other"))
    signIn("uid-2")
    advanceUntilIdle()
    assertEquals("Other", vm.uiState.value.firstName)
  }

  @Test
  fun saveChecksCurrentAccountBeforeObservationCatchesUp() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    auth.signOut()
    vm.saveProfile()
    advanceUntilIdle()
    assertTrue(writes.isEmpty())
    assertEquals(EditProfileStatus.SIGNED_OUT, vm.uiState.value.status)
  }

  @Test
  fun staleSaveCompletionCannotCompleteAnotherAccountsScreen() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    gate = CompletableDeferred()
    ignoreCancellation = true
    vm.saveProfile()
    advanceUntilIdle()
    profiles.seed(original.copy(uid = "uid-2", firstName = "Other"))
    signIn("uid-2")
    advanceUntilIdle()
    assertEquals("Other", vm.uiState.value.firstName)
    gate!!.complete(Unit)
    advanceUntilIdle()
    assertFalse(vm.uiState.value.isSaved)
    assertFalse(vm.uiState.value.saveFailed)
    assertFalse(vm.uiState.value.isSaving)
    assertEquals("Other", vm.uiState.value.firstName)
  }

  @Test
  fun cancelledWriteIsNotReportedAsFailure() = runTest {
    signIn()
    val vm = vm()
    advanceUntilIdle()
    profiles.writeError = CancellationException("cancelled")
    vm.saveProfile()
    advanceUntilIdle()
    assertFalse(vm.uiState.value.saveFailed)
    assertFalse(vm.uiState.value.isSaved)
    assertFalse(vm.uiState.value.isSaving)
  }

  @Test
  fun cancelledProfileObservationIsNotReportedAsLoadError() = runTest {
    signIn()
    val cancelling =
        object : UserProfileRepository by repository {
          override fun observeProfile(uid: String): Flow<UserProfile?> = flow {
            throw CancellationException("cancelled")
          }
        }
    val vm = EditProfileViewModel(auth, cancelling)
    advanceUntilIdle()
    assertEquals(EditProfileStatus.LOADING, vm.uiState.value.status)
  }
}
