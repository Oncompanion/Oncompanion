// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

// Paths of the care circle documents, shared by its rules tests. They use the repositories'
// constants: a stale path would only be denied, so the tests denying it would still pass.

/** The members of [ownerUid]'s care circle. */
fun FirebaseFirestore.circle(ownerUid: String): CollectionReference =
    collection("users").document(ownerUid).collection(CareCircleRepositoryFirestore.COLLECTION)

/** [memberUid] in [ownerUid]'s care circle. */
fun FirebaseFirestore.member(ownerUid: String, memberUid: String): DocumentReference =
    circle(ownerUid).document(memberUid)

/** The document saying that [caregiverUid] joined the care circle of [patientUid]. */
fun FirebaseFirestore.joined(caregiverUid: String, patientUid: String): DocumentReference =
    collection("users")
        .document(caregiverUid)
        .collection(CareCircleInviteRepositoryFirestore.PATIENTS_COLLECTION)
        .document(patientUid)
