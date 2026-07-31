package com.dubulduke.reactivity

/**
 * Handle to a node in a [Graph]. A value class over an `Int`, so the public API stays
 * handle-based and a later struct-of-arrays rewrite of the internals changes nothing here.
 *
 * The `Int` is an index *and* a generation tag, packed. Disposal recycles indices — without
 * that, a UI whose list churns grows the node array forever — and recycling makes a stale
 * handle silently address somebody else's node, which is the worst failure this codebase could
 * have: not a crash but a plausible wrong number. The generation turns it into a named error
 * ([Graph.dispose]). It wraps at 256 reuses of one index, so the check is overwhelmingly
 * likely rather than certain; that is worth an eight-bit field and one compare on the read path.
 */
@JvmInline
value class Slot(val bits: Int) {
    internal val index: Int get() = bits and INDEX_MASK
    internal val generation: Int get() = (bits ushr INDEX_BITS) and GENERATION_MASK

    override fun toString(): String = "Slot($index)"

    companion object {
        internal const val INDEX_BITS = 24
        internal const val INDEX_MASK = (1 shl INDEX_BITS) - 1
        internal const val GENERATION_MASK = 0xFF

        /** The largest number of live nodes one graph can hold. */
        const val MAX_NODES: Int = INDEX_MASK

        internal fun of(index: Int, generation: Int): Slot =
            Slot(index or ((generation and GENERATION_MASK) shl INDEX_BITS))
    }
}

/**
 * Handle to a governor — a node that computes a whole set of slots at once. Deliberately
 * *not* a [Slot]: a governor has no value of its own, so making it unreadable by type is
 * cheaper and more honest than a runtime check on the read path. See [Graph.governor].
 */
@JvmInline
value class GovernorHandle(val bits: Int) {
    internal val index: Int get() = bits and Slot.INDEX_MASK
    internal val generation: Int get() = (bits ushr Slot.INDEX_BITS) and Slot.GENERATION_MASK

    /** The governor as a slot handle, for the disposal and introspection paths. */
    internal val asSlot: Slot get() = Slot(bits)

    override fun toString(): String = "Governor($index)"
}

/**
 * Node state. Deliberately a flags word rather than an enum: [CHECK] and [DIRTY] form a
 * two-bit ordered field, and [RECOMPUTING] is orthogonal to both.
 *
 * `CHECK == 1` and `DIRTY == 2` are load-bearing — [Graph.mark] compares the masked field
 * numerically to implement "never downgrade, and terminate on revisit" in one test.
 */
internal object Flags {
    const val NONE = 0
    const val CHECK = 1 shl 0        // a dependency may have changed; resolve by pulling
    const val DIRTY = 1 shl 1        // definitely stale; must recompute
    const val RECOMPUTING = 1 shl 2  // currently inside its own compute — also the cycle flag
    const val COLOUR = CHECK or DIRTY
}

/**
 * One dependency edge, simultaneously a member of two lists:
 *  - the subscriber's dependency list, singly linked through [nextDep];
 *  - the dependency's subscriber list, doubly linked through [prevSub] / [nextSub].
 *
 * One allocation covers both directions of the edge, which is why reverse-edge storage is
 * free rather than a second data structure.
 */
internal class Link(
    @JvmField var dep: Node,
    @JvmField var sub: Node,
    @JvmField var nextDep: Link?,
    @JvmField var prevSub: Link?,
    @JvmField var nextSub: Link?,
    /**
     * [Node.depGen] of [sub] at the moment this link was created or last revalidated
     * in order. A link stamped with the subscriber's current generation is known to sit in
     * the already-validated prefix of the dependency list — an O(1) membership test that
     * replaces scanning the list.
     */
    @JvmField var gen: Int,
)

/**
 * A graph node. Inputs, derived nodes, governors and governor outputs all share one shape.
 *
 * Three cases, distinguished by two fields:
 *
 * | | [compute] | [firewall] |
 * |---|---|---|
 * | input | `null` | `null` |
 * | derived | [Computation] | `null` |
 * | governor | [Solve] | `null` (has [firstChild]) |
 * | governor output | `null` | the governor |
 */
internal class Node(@JvmField val index: Int) {
    @JvmField var name: String? = null

    /**
     * Bumped on every disposal, and packed into the [Slot] handed out at allocation. A handle
     * whose generation no longer matches names a node that has been disposed.
     */
    @JvmField var generation: Int = 0

    /** True between [Graph.dispose] and the next reuse of this index. Diagnostics only. */
    @JvmField var disposed: Boolean = false

    val slot: Slot get() = Slot.of(index, generation)

    @JvmField var value: Double = 0.0

    /** Non-scalar payload. Meaningful only when [isRef]; [value] is unused in that case. */
    @JvmField var ref: Any? = null
    @JvmField var isRef: Boolean = false
    @JvmField var refEquality: RefEquality = RefEquality.IDENTITY

    @JvmField var deps: Link? = null      // head of the dependency list
    @JvmField var depsTail: Link? = null  // reconciliation cursor during a recompute
    @JvmField var subs: Link? = null      // head of the subscriber list
    @JvmField var subsTail: Link? = null

    @JvmField var flags: Int = Flags.NONE

    /**
     * Bumped once per recompute; stamped onto each [Link] validated in that pass.
     *
     * On a *governor output* this field is borrowed for a different purpose: it holds the
     * owning governor's `depGen` at the moment the output was last written, which is how
     * [Graph.writeOutput] and its post-solve check know whether every claim was satisfied.
     * An output has no dependencies of its own, so the two uses cannot collide.
     */
    @JvmField var depGen: Int = 0

    /**
     * Strictly greater than the height of every derived node this one reads. Maintained
     * incrementally at read time. Not used by the pull evaluator, which needs no ordering —
     * it is kept because it is nearly free and it is the enabling structure for ordered
     * eager execution and for parallel evaluation (equal-height nodes are independent).
     */
    @JvmField var height: Int = 0

    /** `0.0` means exact comparison. See [Graph.setEpsilon]. */
    @JvmField var epsilon: Double = 0.0

    @JvmField var compute: NodeBody? = null

    /**
     * If this node is a governor output: the governor that owns it. Exactly one field, so
     * two governors claiming the same slot is *unrepresentable* rather than merely
     * forbidden — which is what settles `layout-design.md` Q1.
     */
    @JvmField var firewall: Node? = null

    /** If this node is a governor: the head of its claim list, linked by [nextChild]. */
    @JvmField var firstChild: Node? = null
    @JvmField var nextChild: Node? = null

    /** Instrumentation: how many times this node's [compute] has run. */
    @JvmField var recomputes: Int = 0

    /** Return this node to its just-allocated state, for reuse of a recycled index. */
    fun reset(name: String?) {
        this.name = name
        disposed = false
        value = 0.0
        ref = null
        isRef = false
        refEquality = RefEquality.IDENTITY
        deps = null
        depsTail = null
        subs = null
        subsTail = null
        flags = Flags.NONE
        depGen = 0
        height = 0
        epsilon = 0.0
        compute = null
        firewall = null
        firstChild = null
        nextChild = null
        recomputes = 0
    }

    override fun toString(): String = name?.let { "$it${slot}" } ?: slot.toString()
}

/**
 * How a non-scalar node decides whether its value changed — the ref counterpart of
 * [Graph.setEpsilon], and load-bearing for the same reason: the comparison is what stops
 * propagation, so the wrong choice is either a missed invalidation or unbounded redundant
 * work.
 *
 * The policy only matters for nodes that have readers inside the graph. A pure sink — a
 * styler, say — can use any of them, because no verdict it produces reaches anybody.
 */
enum class RefEquality {
    /** `===`. Right for child lists and interned handles — *if* they are never mutated in place. */
    IDENTITY,

    /**
     * `==`. Right for text content. Strings arrive freshly allocated constantly
     * (interpolation, formatting, a label rebuilt during composition), and under [IDENTITY]
     * such a node reports "changed" every composition and re-triggers text measurement — the
     * most expensive input the layout graph has.
     */
    EQUALS,

    /**
     * Never equal; every evaluation propagates. The escape hatch for a payload mutated in
     * place, where neither other test can see the change. A recycled-and-mutated child list
     * is the case to watch for.
     */
    ALWAYS,
}

/**
 * What a non-input node does when it runs. Three kinds: a [Computation] returns one scalar,
 * a [RefComputation] returns one reference, and a [Solve] writes a whole claimed set.
 */
sealed interface NodeBody

/**
 * The body of a derived node. [Graph] is the receiver so that reads inside it are tracked:
 * every `read(...)` performed here becomes a dependency edge.
 *
 * **Do not replace this with `Graph.() -> Double`.** A Kotlin function type compiles to
 * `Function1<Graph, Double>`, whose erased `Object` return boxes a `java.lang.Double` on
 * every recompute. Declared as its own interface, the return type stays concrete and maps
 * to a JVM primitive:
 *
 * ```
 * public abstract double evaluate(Graph)          // interface
 * invokedynamic evaluate:(I)Computation           // call site — no Function1 wrapper
 * invokeinterface Computation.evaluate:(Graph)D   // invocation — no boxing
 * ```
 *
 * If this signature changes, re-check it with `javap -c`. The failure mode is silent and
 * surfaces only as GC pressure under load.
 */
fun interface Computation : NodeBody {
    fun Graph.evaluate(): Double
}

/**
 * The body of a derived non-scalar node: child lists, resolved fonts, formatted text.
 *
 * Boxing is not a concern here — the payload is already a reference — so unlike
 * [Computation] this could have been a `Graph.() -> Any?`. It is an interface for symmetry,
 * so that [NodeBody] has one shape and `recompute` has one `when`.
 */
fun interface RefComputation : NodeBody {
    fun Graph.evaluateRef(): Any?
}

/**
 * The body of a governor: it reads whatever it likes and writes *every* slot it claimed,
 * via [Graph.writeOutput]. Not writing a claim is an error, caught after the solve returns.
 *
 * The point of a governor is not arbitration. It is that a mutually dependent set of slots
 * is often computable *jointly* by an ordinary function, at which point the cycle stops
 * being a graph problem — it becomes internal to this body, where a plain `while` loop can
 * handle it. Flex-shrink with min/max clamps is the canonical case: freezing a
 * clamp-violating item returns space to the pool, which changes everyone else's share.
 * See `layout-design.md` §7.
 */
fun interface Solve : NodeBody {
    fun Graph.solve()
}
