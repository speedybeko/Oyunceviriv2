package com.berkay.oyunceviri

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google [Task] -> suspend fonksiyon (Adaptör deseni). ML Kit'in callback tabanlı API'sini
 * coroutine akışına bağlar; ek bağımlılık (play-services coroutines) gerekmez.
 */
suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
