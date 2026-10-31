// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.utils

import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** How long a security rules test waits for the Firestore emulator to answer one operation. */
val RULES_TIMEOUT = 10.seconds

/** Waits for [task], which the rules must allow, and returns its result. */
suspend fun <T> allowed(task: Task<T>): T = withTimeout(RULES_TIMEOUT) { task.await() }

/** Waits for [task] and fails unless the rules rejected it with PERMISSION_DENIED. */
suspend fun denied(task: Task<*>) {
  try {
    withTimeout(RULES_TIMEOUT) { task.await() }
    fail("Expected PERMISSION_DENIED but the operation succeeded")
  } catch (e: FirebaseFirestoreException) {
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
  }
}

/** The document saying that [caregiverUid] joined the care circle of [patientUid]. */
fun FirebaseFirestore.joined(caregiverUid: String, patientUid: String): DocumentReference =
    collection("users")
        .document(caregiverUid)
        .collection(CareCircleInviteRepositoryFirestore.PATIENTS_COLLECTION)
        .document(patientUid)
