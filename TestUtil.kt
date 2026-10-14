package com.berkay.oyunceviri.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Gerçekten askıya alınmayan (sahte çevirmenlerle yazılmış) suspend kodunu çalıştırır.
 * kotlinx-coroutines-test bağımlılığı gerektirmez.
 */
internal fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return checkNotNull(result) { "suspend blok tamamlanmadı" }.getOrThrow()
}
