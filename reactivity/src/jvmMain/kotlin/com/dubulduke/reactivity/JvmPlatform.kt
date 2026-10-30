package com.dubulduke.reactivity

internal actual object PlatformSupport {
    private val budget = ThreadLocal.withInitial { ChainDepth.DEFAULT_MAX_CHAIN_DEPTH }
    actual fun chainDepthBudget(): Int = budget.get()
    actual fun setChainDepthBudget(depth: Int) = budget.set(depth)
    actual fun currentThread(): Any = Thread.currentThread()
    actual fun isCurrentThread(thread: Any): Boolean = Thread.currentThread() === thread
    actual fun nodeNamesEnabled(): Boolean = System.getProperty(NodeNames.PROPERTY)?.lowercase() != "false"
}

/** Write a platform diagnostic to the standard error stream. */
actual fun writeDiagnostic(message: String) = System.err.println(message)

/**
 * Start a thread sized for a known worst-case chain depth, on which graphs get the matching budget.
 *
 * ```kotlin
 * layoutThread(maxChainDepth = 64_000) {
 *     val app = MyUI()          // its Graph adopts the 64 000 budget
 *     app.compose { … }
 *     while (running) { app.update(dt); app.draw(renderer) }
 * }.join()
 * ```
 *
 * This is the fix `layout-design.md` §11.2 called legitimate rather than a workaround, and the
 * reason it is legitimate is [ChainDepth.depthForStack] being linear: the stack is *computed* from
 * a requirement, not tuned until the crashes stop. The default 64 000 links is about 32 000 chained
 * rows and reserves 37 MB of address space, which a thread that exists once per process does not
 * need to justify.
 */
fun layoutThread(
    name: String = "duke-layout",
    maxChainDepth: Int = 64_000,
    body: () -> Unit,
): Thread {
    val stack = ChainDepth.stackForDepth(maxChainDepth)
    val t = Thread(null, {
        ChainDepth.declareBudget(maxChainDepth)
        body()
    }, name, stack)
    t.start()
    return t
}

