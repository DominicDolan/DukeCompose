package com.dubulduke.reactivity

/**
 * The chain-depth ceiling, made explicit.
 *
 * Pull evaluation makes the call stack *be* the topological order, which is what solves the
 * diamond problem without a scheduler and without a sort — and the price is that JVM stack depth
 * is proportional to the longest dependency chain, at a measured ~128–145 bytes per link
 * (`:layout`'s `ChainDepthTest`, `layout-design.md` §11.2). A default thread stack settles
 * somewhere in the low thousands of links and then throws `StackOverflowError`, which for a
 * `prev`-chained list is about 2 000 rows.
 *
 * That is not the problem. The problem is *how it fails*: a `StackOverflowError` from inside a
 * layout expression names none of the things that would let anyone act on it, and it arrives at
 * whatever depth the JIT happened to leave the frames at, so the same list crashes on one run and
 * not the next. This file turns it into a **stated, checked budget** instead:
 *
 *  - [Graph.maxChainDepth] is a number the graph refuses to evaluate past, throwing
 *    [ChainTooDeepException], which names the chain and says what to do about it.
 *  - [depthForStack] converts a thread stack size into the depth it can carry, and
 *    [stackForDepth] converts back. Depth is *linear* in stack size, so a host that knows its
 *    worst-case chain length can compute the stack it needs rather than guess it.
 *  - [layoutThread] starts a thread with that stack, and a [Graph] created on it picks the
 *    matching budget up automatically.
 *
 * The default budget, for a graph created on a thread nobody sized, is deliberately *below* the
 * point where the stack actually runs out ([DEFAULT_MAX_CHAIN_DEPTH]). A budget above it would be
 * decoration: the crash would win the race and the diagnostic would never print.
 */
object ChainDepth {

    /**
     * JVM stack bytes one link in a dependency chain costs.
     *
     * Measured at 128–145 across stack sizes by `ChainDepthTest`; the upper end is used, since
     * the consequence of overestimating is a bigger stack and the consequence of underestimating
     * is the crash this exists to prevent. Interpreted frames are larger than compiled ones, which
     * is [HEADROOM]'s job rather than this constant's.
     */
    const val BYTES_PER_LINK: Long = 145L

    /**
     * How much of a sized stack the budget declines to spend.
     *
     * Not because the measurement is doubtful — it is reproducible to a few percent — but because
     * it describes the wrong run. [BYTES_PER_LINK] is measured on a *warm* chain of the evaluator's
     * own frames, and neither half of that survives contact with a real one:
     *
     *  - **Interpreted frames are several times the size of compiled ones**, and the deep frame is
     *    usually a cold one. `ChainDepthTest` sees the same default stack carry ~3 900 links warm
     *    and closer to 1 500 cold, which is a factor of 2.6 on its own.
     *  - **The chain carries the application's frames too.** A layout expression that calls three
     *    functions deep is three more frames per link, and the evaluator has no way to know.
     *
     * Four was arrived at by the second way anything is: a thread sized at two overflowed while
     * settling half its declared depth cold. The cost of being wrong in this direction is address
     * space on one thread that exists once per process; the cost of being wrong in the other is
     * the crash this whole file exists to replace.
     */
    const val HEADROOM: Long = 4L

    /**
     * The budget for a graph created on a thread whose stack nobody chose.
     *
     * A stated number rather than one derived from [depthForStack], because there is no stack size
     * to derive it from: a thread this graph did not start will not say how much stack it has, and
     * what a JVM default delivers is not a constant anyway. `ChainDepthTest` measures ~3 900 links
     * on a warm run and notes the same probe settling closer to 1 500 when the frames are still
     * interpreted, since an interpreted frame is several times the size of a compiled one.
     *
     * So the budget has to sit under the *low* end, or on exactly the runs where the depth matters
     * most — the first frames, before the JIT has been anywhere — the crash wins the race and the
     * diagnostic never prints.
     *
     * And under it by a real margin, because a link is not one frame. `ChainDepthTest` measures the
     * evaluator settling a chain of *its own* nodes; a chain of layout slots carries the DSL's
     * frames too — a `LayoutExpression`, a `LayoutScope` getter, an axis accessor — so the same
     * budget buys fewer links there. Measured through `:dsl`, a `prev`-chained `scrollArea` whose
     * content height pulls the last row overflows a default stack at roughly 1 400 links, which
     * 1 250 does not reliably beat. 800 does, and is still about 400 chained rows: more than any
     * list that should be un-virtualised (see `virtualContent`), and well under the floor.
     *
     * Raising it is a one-line, supported thing to do ([Graph.maxChainDepth]) — but it should be
     * done alongside the stack that carries it, which is what [layoutThread] is for.
     */
    const val DEFAULT_MAX_CHAIN_DEPTH: Int = 800

    /** Chain depth a thread with [stackBytes] of stack can carry, with [HEADROOM] left over. */
    fun depthForStack(stackBytes: Long): Int {
        require(stackBytes > 0) { "stack size must be positive, was $stackBytes" }
        val d = stackBytes / (BYTES_PER_LINK * HEADROOM)
        return d.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Stack a thread needs to carry a chain [depth] links long. The inverse of [depthForStack]. */
    fun stackForDepth(depth: Int): Long {
        require(depth > 0) { "depth must be positive, was $depth" }
        return depth.toLong() * BYTES_PER_LINK * HEADROOM
    }

    /**
     * The budget [Graph] adopts on construction, set by [layoutThread] for the thread it starts.
     *
     * A thread-local rather than a constructor argument because the graph is usually built by
     * something that does not own the thread policy — `DukeApp` allocates its `Graph` in a field
     * initialiser, long before any host has an opinion. Whoever *did* size the stack is the only
     * party that knows the answer, so the answer travels with the thread.
     */
    internal val budget = ThreadLocal.withInitial { DEFAULT_MAX_CHAIN_DEPTH }

    /**
     * Declare that the current thread's stack can carry chains [depth] links long.
     *
     * For a host that starts its own thread rather than using [layoutThread] — a game loop, a
     * platform's render thread. Affects graphs created *after* the call; an existing one takes
     * [Graph.maxChainDepth] directly.
     */
    fun declareBudget(depth: Int) {
        require(depth > 0) { "depth must be positive, was $depth" }
        budget.set(depth)
    }
}

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

/**
 * A dependency chain outran [Graph.maxChainDepth].
 *
 * Thrown *instead of* the `StackOverflowError` that would otherwise follow, and the difference is
 * everything a diagnostic is for: this one names the slot the walk stopped at, how deep the chain
 * already was, and the two ways out.
 *
 * The [chain] is the recomputes in flight, outermost first — which is the whole chain when the
 * evaluation is a cold one, and only its recomputing tail when a `CHECK` resolution walk is what
 * went deep. Either way its last entries are the part worth reading.
 */
class ChainTooDeepException internal constructor(
    message: String,
    /** The slot the walk stopped at. */
    val slot: Slot,
    /** Depth reached, which is [Graph.maxChainDepth] plus one. */
    val depth: Int,
    /** Recomputes in flight when the limit was hit, outermost first. */
    val chain: List<CycleMember>,
) : IllegalStateException(message)
