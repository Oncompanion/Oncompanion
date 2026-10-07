package com.github.se.oncompanion.model.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException

/**
 * Whether this error from [AuthRepository] (or a repository read right after) means there is no
 * connection, so ViewModels can show the right message without depending on Firebase exception
 * types.
 */
fun Throwable.isNetworkError(): Boolean =
    this is FirebaseNetworkException ||
        this is IOException ||
        // Firestore reports "offline" this way (e.g. reading a profile that isn't cached)
        (this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE)
