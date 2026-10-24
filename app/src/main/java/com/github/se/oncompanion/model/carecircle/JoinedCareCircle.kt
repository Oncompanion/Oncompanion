// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * A care circle the caregiver joined, stored at `/users/{caregiverUid}/patients/{patientUid}` when
 * they redeem an invite code. It lets the caregiver list their patients without reading their
 * profiles, which only the patients can read.
 *
 * @property patientUid the patient whose circle they joined, also the document ID
 * @property patientFirstName the patient's first name, copied from the invite when joining
 * @property joinedAt when the server received the join, or null until it has
 */
data class JoinedCareCircle(
    val patientUid: String,
    val patientFirstName: String,
    val joinedAt: Instant? = null,
)

/**
 * Whether [caregiverUid] joined at least one care circle, so the app can open the join screen for a
 * caregiver who hasn't yet. Answers from the local cache when offline.
 */
suspend fun CareCircleInviteRepository.hasJoinedCircle(caregiverUid: String): Boolean =
    observeJoinedCircles(caregiverUid).first().isNotEmpty()
