package com.github.se.oncompanion.ui.onboarding

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [AuthRepository] whose current user is set directly by the test. */
private class StubAuthRepository(
    var user: AuthUser? = null,
    var currentUserError: Exception? = null,
) : AuthRepository {
  override val currentUser: AuthUser?
    get() {
      currentUserError?.let { throw it }
      return user
    }

  override fun observeCurrentUser(): Flow<AuthUser?> = flowOf(user)

  override suspend fun signInWithGoogle(idToken: String): AuthUser =
      throw UnsupportedOperationException()

  override fun signOut() {
    user = null
  }
}

/** Records every created profile and waits for [gate] (if set) before delegating. */
private class GatedProfileRepository(private val delegate: FakeUserProfileRepository) :
    UserProfileRepository by delegate {
  var gate: CompletableDeferred<Unit>? = null
  val created = mutableListOf<UserProfile>()

  override suspend fun createProfile(profile: UserProfile) {
    created += profile
    gate?.await()
    delegate.createProfile(profile)
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val user = AuthUser(uid = "uid-1", displayName = "Alex Doe", givenName = "Alexandra")

  private lateinit var auth: StubAuthRepository
  private lateinit var fakeProfiles: FakeUserProfileRepository
  private lateinit var profiles: GatedProfileRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = StubAuthRepository(user)
    fakeProfiles = FakeUserProfileRepository()
    profiles = GatedProfileRepository(fakeProfiles)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = OnboardingViewModel(auth, profiles)

  private fun state(vm: OnboardingViewModel) = vm.uiState.value

  // --- OnboardingUiState.canSave ---

  @Test
  fun canSave_requiresANonBlankFirstNameAndNoSaveInProgressOrDone() {
    assertFalse(OnboardingUiState().canSave)
    assertFalse(OnboardingUiState(firstName = "   ").canSave)
    assertTrue(OnboardingUiState(firstName = "Alex").canSave)
    assertFalse(OnboardingUiState(firstName = "Alex", isSaving = true).canSave)
    assertFalse(OnboardingUiState(firstName = "Alex", isSaved = true).canSave)
  }

  @Test
  fun defaultUiState_hasExpectedValues() {
    val s = OnboardingUiState()
    assertEquals(Role.PATIENT, s.role)
    assertEquals("", s.firstName)
    assertEquals("", s.familyName)
    assertEquals("", s.cancerType)
    assertFalse(s.isSaving)
    assertFalse(s.isSaved)
    assertNull(s.error)
  }

  // --- Initial state ---

  @Test
  fun initialState_prefillsFirstNameWithGivenName() {
    assertEquals(OnboardingUiState(firstName = "Alexandra"), state(viewModel()))
  }

  @Test
  fun initialState_usesFirstWordOfDisplayName_whenGivenNameIsBlank() {
    auth.user = AuthUser(uid = "uid-1", displayName = "Sam Smith", givenName = "  ")
    assertEquals("Sam", state(viewModel()).firstName)
  }

  @Test
  fun initialState_firstNameIsEmpty_whenNoNameIsKnown() {
    auth.user = AuthUser(uid = "uid-1")
    assertEquals(OnboardingUiState(), state(viewModel()))
  }

  @Test
  fun initialState_firstNameIsEmpty_whenNobodyIsSignedIn() {
    auth.user = null
    assertEquals(OnboardingUiState(), state(viewModel()))
  }

  @Test
  fun initialState_doesNotCrash_whenReadingTheCurrentUserThrows() {
    auth.currentUserError = IllegalStateException("Firebase not initialized")
    assertEquals(OnboardingUiState(), state(viewModel()))
  }

  @Test
  fun initialState_prefilledFirstNameIsCutToMaxNameLength() {
    auth.user = AuthUser(uid = "uid-1", givenName = "a".repeat(UserProfile.MAX_NAME_LENGTH + 20))
    assertEquals("a".repeat(UserProfile.MAX_NAME_LENGTH), state(viewModel()).firstName)
  }

  // --- Role ---

  @Test
  fun selectRole_patientKeepsPatient() {
    val vm = viewModel()
    vm.selectRole(Role.PATIENT)
    assertEquals(Role.PATIENT, state(vm).role)
  }

  @Test
  fun selectRole_caregiverIsIgnored() {
    val vm = viewModel()
    val before = state(vm)
    vm.selectRole(Role.CAREGIVER)
    assertEquals(before, state(vm))
  }

  // --- Fields ---

  @Test
  fun fieldChanges_areStored() {
    val vm = viewModel()
    vm.onFirstNameChange("Marie")
    vm.onFamilyNameChange("Curie")
    vm.onCancerTypeChange("Breast cancer")
    assertEquals(
        OnboardingUiState(firstName = "Marie", familyName = "Curie", cancerType = "Breast cancer"),
        state(vm),
    )
  }

  @Test
  fun fieldChanges_areStoredAsTyped_withoutTrimming() {
    val vm = viewModel()
    vm.onFirstNameChange(" Marie ")
    vm.onFamilyNameChange("")
    assertEquals(" Marie ", state(vm).firstName)
    assertEquals("", state(vm).familyName)
  }

  @Test
  fun fieldChanges_valuesAtTheLimitAreKept() {
    val vm = viewModel()
    val name = "n".repeat(UserProfile.MAX_NAME_LENGTH)
    val cancer = "c".repeat(UserProfile.MAX_CANCER_TYPE_LENGTH)
    vm.onFirstNameChange(name)
    vm.onFamilyNameChange(name)
    vm.onCancerTypeChange(cancer)
    assertEquals(name, state(vm).firstName)
    assertEquals(name, state(vm).familyName)
    assertEquals(cancer, state(vm).cancerType)
  }

  @Test
  fun fieldChanges_areCutToTheirMaximumLength() {
    val vm = viewModel()
    vm.onFirstNameChange("f".repeat(UserProfile.MAX_NAME_LENGTH + 1))
    vm.onFamilyNameChange("g".repeat(UserProfile.MAX_NAME_LENGTH + 30))
    vm.onCancerTypeChange("c".repeat(UserProfile.MAX_CANCER_TYPE_LENGTH + 1))
    assertEquals("f".repeat(UserProfile.MAX_NAME_LENGTH), state(vm).firstName)
    assertEquals("g".repeat(UserProfile.MAX_NAME_LENGTH), state(vm).familyName)
    assertEquals("c".repeat(UserProfile.MAX_CANCER_TYPE_LENGTH), state(vm).cancerType)
  }

  // --- saveProfile ---

  @Test
  fun saveProfile_success_createsTrimmedProfileAndMarksSaved() = runTest {
    val vm = viewModel()
    vm.onFirstNameChange("  Marie ")
    vm.onFamilyNameChange(" Curie  ")
    vm.onCancerTypeChange("  Breast cancer ")

    vm.saveProfile()
    advanceUntilIdle()

    val expected =
        UserProfile(
            uid = user.uid,
            role = Role.PATIENT,
            firstName = "Marie",
            familyName = "Curie",
            cancerType = "Breast cancer",
        )
    assertEquals(listOf(expected), profiles.created)
    assertEquals(expected, fakeProfiles.profiles[user.uid]?.copy(createdAt = null))
    val s = state(vm)
    assertFalse(s.isSaving)
    assertTrue(s.isSaved)
    assertNull(s.error)
    assertFalse(s.canSave)
  }

  @Test
  fun saveProfile_blankOptionalFields_areSavedAsNull() = runTest {
    val vm = viewModel()
    vm.onFamilyNameChange("   ")
    vm.onCancerTypeChange("")

    vm.saveProfile()
    advanceUntilIdle()

    assertEquals(
        listOf(UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alexandra")),
        profiles.created,
    )
    assertTrue(state(vm).isSaved)
  }

  @Test
  fun saveProfile_withPrefilledName_savesItWithoutEditing() = runTest {
    val vm = viewModel()
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals("Alexandra", profiles.created.single().firstName)
    assertTrue(state(vm).isSaved)
  }

  @Test
  fun saveProfile_blankFirstName_doesNothing() = runTest {
    val vm = viewModel()
    vm.onFirstNameChange("   ")
    val before = state(vm)

    vm.saveProfile()
    advanceUntilIdle()

    assertTrue(profiles.created.isEmpty())
    assertEquals(before, state(vm))
  }

  @Test
  fun saveProfile_notSignedIn_setsErrorAndWritesNothing() = runTest {
    auth.user = null
    val vm = viewModel()
    vm.onFirstNameChange("Marie")

    vm.saveProfile()
    advanceUntilIdle()

    assertTrue(profiles.created.isEmpty())
    assertTrue(fakeProfiles.profiles.isEmpty())
    val s = state(vm)
    assertEquals(OnboardingError.NOT_SIGNED_IN, s.error)
    assertFalse(s.isSaving)
    assertFalse(s.isSaved)
  }

  @Test
  fun saveProfile_isSavingWhileTheRepositoryWorks_andIgnoresASecondCall() = runTest {
    val gate = CompletableDeferred<Unit>()
    profiles.gate = gate
    val vm = viewModel()

    vm.saveProfile()
    advanceUntilIdle()
    var s = state(vm)
    assertTrue(s.isSaving)
    assertFalse(s.isSaved)
    assertNull(s.error)
    assertFalse(s.canSave)

    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(1, profiles.created.size)

    gate.complete(Unit)
    advanceUntilIdle()
    s = state(vm)
    assertFalse(s.isSaving)
    assertTrue(s.isSaved)
    assertEquals(1, profiles.created.size)
  }

  @Test
  fun saveProfile_afterSuccess_doesNotSaveAgain() = runTest {
    val vm = viewModel()
    vm.saveProfile()
    advanceUntilIdle()
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(1, profiles.created.size)
  }

  @Test
  fun saveProfile_failure_setsSaveFailed_andCanBeRetried() = runTest {
    fakeProfiles.writeError = IllegalStateException("disk full")
    val vm = viewModel()

    vm.saveProfile()
    advanceUntilIdle()

    var s = state(vm)
    assertEquals(OnboardingError.SAVE_FAILED, s.error)
    assertFalse(s.isSaving)
    assertFalse(s.isSaved)
    assertTrue(s.canSave)
    assertTrue(fakeProfiles.profiles.isEmpty())

    fakeProfiles.writeError = null
    vm.saveProfile()
    advanceUntilIdle()

    s = state(vm)
    assertNull(s.error)
    assertTrue(s.isSaved)
    assertEquals(2, profiles.created.size)
    assertTrue(fakeProfiles.profiles.containsKey(user.uid))
  }

  @Test
  fun saveProfile_clearsThePreviousErrorWhileSaving() = runTest {
    fakeProfiles.writeError = IllegalStateException("boom")
    val vm = viewModel()
    vm.saveProfile()
    advanceUntilIdle()
    assertEquals(OnboardingError.SAVE_FAILED, state(vm).error)

    fakeProfiles.writeError = null
    val gate = CompletableDeferred<Unit>()
    profiles.gate = gate
    vm.saveProfile()
    advanceUntilIdle()
    assertTrue(state(vm).isSaving)
    assertNull(state(vm).error)
    gate.complete(Unit)
    advanceUntilIdle()
  }

  @Test
  fun saveProfile_usesTheCurrentUserAtSaveTime() = runTest {
    auth.user = null
    val vm = viewModel()
    vm.onFirstNameChange("Marie")
    auth.user = AuthUser(uid = "late-uid")

    vm.saveProfile()
    advanceUntilIdle()

    assertEquals("late-uid", profiles.created.single().uid)
    assertTrue(state(vm).isSaved)
  }

  // --- clearError ---

  @Test
  fun clearError_onlyRemovesTheError() = runTest {
    auth.user = null
    val vm = viewModel()
    vm.onFirstNameChange("Marie")
    vm.onFamilyNameChange("Curie")
    vm.saveProfile()
    advanceUntilIdle()
    val withError = state(vm)
    assertEquals(OnboardingError.NOT_SIGNED_IN, withError.error)

    vm.clearError()

    assertEquals(withError.copy(error = null), state(vm))
  }

  @Test
  fun clearError_withoutError_changesNothing() {
    val vm = viewModel()
    val before = state(vm)
    vm.clearError()
    assertEquals(before, state(vm))
  }
}
