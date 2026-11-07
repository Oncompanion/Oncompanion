// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks what [ObserveCareSection] emits for each access to a section, and that a read failure
 * never stops it from following the access (#204, #323). "caregiver" follows "patient".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCareSectionTest {

  private val journal = MutableStateFlow(listOf("Fatigue"))

  /** Whose data each read asked for, in order. */
  private val readers = mutableListOf<String>()

  /** When true, reading fails, as when the server refuses it. */
  private var refuseReads = false

  /** When true, the read fails as soon as it is asked for, before giving a flow. */
  private var refuseOnCall = false

  private fun read(ownerUid: String): Flow<List<String>> {
    readers += ownerUid
    if (refuseOnCall) error("Invalid path")
    return if (refuseReads) flow { error("PERMISSION_DENIED") } else journal
  }

  /** Collects the symptoms of [patientUid] in the background and returns what it emitted so far. */
  private fun TestScope.collect(
      access: ResolveSectionAccess,
      patientUid: String? = "patient",
  ): MutableList<CareSection<List<String>>> {
    val emissions = mutableListOf<CareSection<List<String>>>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      ObserveCareSection(access)(CarePermission.SYMPTOMS, patientUid, ::read).collect {
        emissions += it
      }
    }
    runCurrent()
    return emissions
  }

  @Test
  fun sharedSection_isShownReadOnly_fromLoading() = runTest {
    val emissions = collect(followedPatientAccess(CarePermission.SYMPTOMS))

    assertEquals(
        listOf(CareSection.Loading(), CareSection.Shown(listOf("Fatigue"), false, false)),
        emissions.distinct(),
    )
    assertEquals(listOf("patient"), readers)
  }

  @Test
  fun ownData_canBeEditedAndAddedTo() = runTest {
    val emissions = collect(followedPatientAccess(), patientUid = null)

    assertEquals(CareSection.Shown(listOf("Fatigue"), true, true), emissions.last())
    assertEquals(listOf("caregiver"), readers)
  }

  @Test
  fun withoutTheSection_saysWhy_andReadsNothing() = runTest {
    val cases =
        listOf(
            FollowedPatient(CarePermission.PLANNING) to CareSection.NotShared,
            FollowedPatient().apply { removesTheCaregiver() } to CareSection.NoAccess,
            FollowedPatient().apply { isUnconfirmed() } to CareSection.Loading(),
        )
    for ((patient, expected) in cases) {
      assertEquals(listOf<CareSection<List<String>>>(expected), collect(patient.sectionAccess))
    }
    assertTrue(readers.isEmpty())
  }

  @Test
  fun refusedRead_isFailed_andTheNextAccessChangeStillApplies() = runTest {
    // Refused by the server once listening (the patient stops sharing, and the refusal arrives
    // before the access change), or by the repository as soon as it is asked
    for (onCall in listOf(false, true)) {
      refuseReads = !onCall
      refuseOnCall = onCall
      val patient =
          FollowedPatient(CarePermission.SYMPTOMS, canAdd = setOf(CarePermission.SYMPTOMS))
      val emissions = collect(patient.sectionAccess)
      // A caregiver loses "Add": their access may be gone
      assertEquals(CareSection.Failed(canAdd = false), emissions.last())

      patient.shares(CarePermission.PLANNING)
      runCurrent()

      assertEquals("refused on call: $onCall", CareSection.NotShared, emissions.last())
    }
  }

  @Test
  fun ownReadFailure_stillLetsThePatientAdd() = runTest {
    refuseReads = true
    assertEquals(
        CareSection.Failed(canAdd = true),
        collect(followedPatientAccess(), patientUid = null).last(),
    )
  }

  @Test
  fun accessFailure_isFailed() = runTest {
    val failingAuth =
        object : AuthRepository by FakeAuthRepository({ AuthUser(it) }) {
          override fun observeCurrentUser(): Flow<AuthUser?> = flow { error("Auth broke") }
        }
    val access = ResolveSectionAccess(ResolveCareCircleAccess(auth = failingAuth), failingAuth)

    assertEquals(
        listOf<CareSection<List<String>>>(CareSection.Failed(canAdd = true)),
        collect(access, patientUid = null),
    )
    assertTrue(readers.isEmpty())
  }
}
