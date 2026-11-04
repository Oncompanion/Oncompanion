// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.carecircle.CarePermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what a [CareAccess.Granted] lets a screen show and edit. */
class CareAccessTest {

  @Test
  fun ownDataShowsEverySectionAndIsEditable() {
    val access = CareAccess.Granted("alice", CarePermission.entries.toSet(), isOwn = true)

    CarePermission.entries.forEach { assertTrue(access.canSee(it)) }
    assertTrue(access.canEdit)
  }

  @Test
  fun caregiverSeesOnlySharedSectionsAndNeverEdits() {
    val access =
        CareAccess.Granted(
            "alice",
            setOf(CarePermission.SYMPTOMS, CarePermission.PLANNING),
            isOwn = false,
        )

    assertTrue(access.canSee(CarePermission.SYMPTOMS))
    assertTrue(access.canSee(CarePermission.PLANNING))
    assertFalse(access.canSee(CarePermission.EVENTS))
    assertFalse(access.canSee(CarePermission.PRESCRIPTIONS))
    assertFalse(access.canEdit)
  }
}
