package com.whispercpp.whisper

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job

/** Completion is too late: a synchronous native child must be signalled on cancellation. */
@OptIn(InternalCoroutinesApi::class)
internal fun Job.abortNativeOnCancellation(abort: () -> Unit): DisposableHandle =
    invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) abort()
    }
