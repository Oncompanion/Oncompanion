package com.github.se.oncompanion.model.auth

import com.google.firebase.FirebaseNetworkException
import java.io.IOException

/**
 * Whether this error from [AuthRepository] means there is no connection, so ViewModels can show the
 * right message without depending on Firebase exception types.
 */
fun Throwable.isNetworkError(): Boolean = this is FirebaseNetworkException || this is IOException
