@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.dubulduke.reactivity

import kotlin.native.concurrent.ThreadLocal
import platform.posix.fprintf
import platform.posix.pthread_equal
import platform.posix.pthread_self
import platform.posix.stderr

@ThreadLocal
private object ThreadBudget {
    var value = ChainDepth.DEFAULT_MAX_CHAIN_DEPTH
}

internal actual object PlatformSupport {
    actual fun chainDepthBudget(): Int = ThreadBudget.value
    actual fun setChainDepthBudget(depth: Int) { ThreadBudget.value = depth }
    actual fun currentThread(): Any = pthread_self()
    actual fun isCurrentThread(thread: Any): Boolean = pthread_equal(pthread_self(), thread as ULong) != 0
    actual fun nodeNamesEnabled(): Boolean = true
}

actual fun writeDiagnostic(message: String) {
    fprintf(stderr, "%s\n", message)
}
