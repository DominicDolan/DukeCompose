package com.dubulduke.reactivity

internal expect object PlatformSupport {
    fun chainDepthBudget(): Int
    fun setChainDepthBudget(depth: Int)
    fun currentThread(): Any
    fun isCurrentThread(thread: Any): Boolean
    fun nodeNamesEnabled(): Boolean
}

/** Write a platform diagnostic to the standard error stream. */
expect fun writeDiagnostic(message: String)
