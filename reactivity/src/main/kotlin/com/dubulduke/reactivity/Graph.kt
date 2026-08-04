package com.dubulduke.reactivity

/**
 * A pull-driven, memoising dependency graph over scalar `Double` values.
 *
 * The algorithm is a port of the reactive core of `@solidjs/signals` 2.0 (which in turn
 * takes its graph representation and dependency reconciliation from `alien-signals`), with
 * everything async-related removed and cycle detection added. See
 * `docs/reactivity-core.md` for the derivation and for the source correspondence.
 *
 * Four mechanisms carry the whole design:
 *
 *  1. **Intrusive doubly-linked edges** ([Link]) — forward and reverse edges in one object.
 *  2. **Dependency reconciliation** ([link], [trimStaleDeps]) — links are reused in place
 *     across recomputes, so a node whose dependency set did not change allocates nothing.
 *  3. **Two-colour marking** ([mark]) — a write marks its direct subscribers `DIRTY` and
 *     everything further downstream `CHECK` ("maybe"). Resolving a `CHECK` is a pull, and
 *     a node that recomputes to an unchanged value stops the propagation dead.
 *  4. **Pull evaluation** ([updateIfNecessary]) — the call stack is the topological order;
 *     it is never computed.
 *  5. **Governors** ([governor]) — one node that computes a whole set of slots at once, each
 *     independently observable. This is how a real cycle stops being a graph problem.
 *
 * Not thread-safe, and deliberately so: [context] and [evalStack] make the graph
 * single-threaded by construction. All entry points assert the owning thread.
 */
class Graph {

    private val nodes = ArrayList<Node>()

    /** Indices of disposed nodes, available for reuse. See [dispose]. */
    private val freeIndices = ArrayList<Int>()

    /**
     * Allocate a node, reusing a disposed index when one is available.
     *
     * Reuse is what keeps a churning UI bounded: without it a list that replaces its items
     * every second grows the node array forever, and `layout-design.md` Q10 becomes a memory
     * leak with extra steps. The generation tag on [Slot] is what makes reuse safe to expose.
     */
    private fun allocate(name: String?): Node {
        val n: Node
        if (freeIndices.isEmpty()) {
            check(nodes.size < Slot.MAX_NODES) { "graph is full: ${nodes.size} nodes" }
            n = Node(nodes.size)
            nodes += n
        } else {
            n = nodes[freeIndices.removeAt(freeIndices.size - 1)]
        }
        n.reset(name)
        return n
    }

    /**
     * Resolve a handle, rejecting one whose node has since been disposed.
     *
     * One compare on the hot read path, and worth it: without the check a recycled index makes
     * a stale handle read somebody else's value, which is a plausible wrong number rather than
     * a crash — the failure mode this whole design exists to rule out.
     */
    private fun node(g: GovernorHandle): Node = node(g.asSlot)

    private fun node(s: Slot): Node {
        val n = nodes[s.index]
        if (n.generation != s.generation) {
            throw IllegalStateException(
                "$s refers to a node that has been disposed" +
                    (if (n.disposed) "" else " and its index reused by $n") +
                    "; a handle must not outlive the element that owned it"
            )
        }
        return n
    }

    /** The node currently running its compute. Reads inside it become its dependencies. */
    private var context: Node? = null

    /**
     * Nodes currently on the evaluation stack, innermost last. Gives cycle membership.
     *
     * A raw array for the same reason [markStack] is one: it is pushed and popped once per
     * recompute — 180 000 times on a cold 40k scene — and it exists only so that a cycle can be
     * *named*, which is a diagnostic. Paying `ArrayList`'s bounds check and `removeAt` shuffle on
     * every recompute for that is the wrong trade.
     */
    private var evalStack = arrayOfNulls<Node>(64)
    private var evalTop = 0

    private val owningThread: Thread = Thread.currentThread()

    var diagnostics: DiagnosticSink = DiagnosticSink.Stderr

    val stats = Stats()

    /**
     * How deep a dependency chain this graph will evaluate before refusing, with
     * [ChainTooDeepException] rather than `StackOverflowError`. See [ChainDepth] for the whole
     * argument; the short version is that pull evaluation spends JVM stack proportional to chain
     * length, so the limit exists whatever this field says, and the only question is whether it is
     * a stated number or a crash.
     *
     * Initialised from the thread's declared budget ([layoutThread], [ChainDepth.declareBudget]),
     * which is [ChainDepth.DEFAULT_MAX_CHAIN_DEPTH] on a thread nobody sized. Raise it only
     * alongside the stack that has to carry it — [ChainDepth.stackForDepth] says how much that is.
     * Set it to [Int.MAX_VALUE] to opt out entirely and take the `StackOverflowError`, which is
     * what a benchmark measuring the real ceiling wants and nothing else does.
     */
    var maxChainDepth: Int = ChainDepth.budget.get()
        set(value) {
            require(value > 0) { "maxChainDepth must be positive, was $value" }
            field = value
        }

    /**
     * Links of dependency chain currently being resolved, which is the recursion depth of
     * [updateIfNecessary] and therefore the JVM stack in use.
     *
     * Counted here rather than taken from [evalStack] because the two differ: a `CHECK` resolution
     * walk recurses the full length of a chain through [updateIfNecessary] alone, recomputing
     * nothing and pushing nothing, and that walk is exactly as capable of exhausting the stack.
     *
     * Restored by decrement rather than by `finally`. An exception escaping evaluation leaves this
     * too high, which the reset in [read] corrects on the next top-level pull — the cheaper trade,
     * since this sits on the hottest path in the system and the alternative pays a handler on every
     * link to tidy up after a frame that has already failed.
     */
    private var depth: Int = 0

    // ---------------------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------------------

    /** An input: a node with no compute, written from outside the graph via [setInput]. */
    fun input(initial: Double = 0.0, name: String? = null): Slot {
        checkThread()
        val n = allocate(name)
        n.value = initial
        return n.slot
    }

    /**
     * A derived node. [body] is not run now — it runs the first time the node's value is
     * demanded, and thereafter only when something it read has actually changed.
     */
    fun derive(name: String? = null, body: Computation): Slot {
        checkThread()
        val n = allocate(name)
        n.compute = body
        n.flags = Flags.DIRTY
        return n.slot
    }

    /**
     * A non-scalar input: child lists, text content, resolved fonts. Shares every code path
     * with [input]; see [RefEquality] for the one thing that differs.
     */
    fun refInput(
        initial: Any? = null,
        name: String? = null,
        equality: RefEquality = RefEquality.IDENTITY,
    ): Slot {
        checkThread()
        val n = allocate(name)
        n.isRef = true
        n.refEquality = equality
        n.ref = initial
        return n.slot
    }

    /** A derived non-scalar node. Lazy like [derive]. */
    fun deriveRef(
        name: String? = null,
        equality: RefEquality = RefEquality.IDENTITY,
        body: RefComputation,
    ): Slot {
        checkThread()
        val n = allocate(name)
        n.isRef = true
        n.refEquality = equality
        n.compute = body
        n.flags = Flags.DIRTY
        return n.slot
    }

    /**
     * A governor: one node that computes [claims] — a whole set of slots — in a single
     * [Solve]. The claimed slots must already exist and must be plain, unclaimed inputs.
     *
     * This is what makes a real cycle tractable. The graph cannot order a mutually
     * dependent set of slots, but a function often *can* compute them jointly, so a
     * governor moves the cycle out of the graph and into the body of one node, where an
     * ordinary loop deals with it. The strongly connected component vanishes because every
     * slot in it now resolves through the same node.
     *
     * Three properties, none of which cost anything extra:
     *
     *  - **Fine-grained outputs.** Readers link to the individual output they read, so a
     *    re-solve that produces the same value for one claim does not wake that claim's
     *    dependents. See the per-output equality test.
     *  - **Exclusive ownership.** A node has exactly one [Node.firewall] field, so
     *    overlapping claims are unrepresentable — hence an error, detected here by one null
     *    check (`layout-design.md` Q1). Nested flex containers are still fine: the inner
     *    container's own size is claimed by the outer governor, its children's sizes by the
     *    inner one, and those sets are disjoint.
     *  - **Totality.** Every claim must be written on every solve, checked after the fact.
     *    A governor that silently leaves an output at last frame's value is exactly the
     *    bug class this design exists to rule out.
     */
    fun governor(claims: List<Slot>, name: String? = null, body: Solve): GovernorHandle {
        checkThread()
        require(claims.isNotEmpty()) { "a governor must claim at least one slot" }

        // Validate before allocating, so a rejected governor leaves no partial state.
        require(claims.distinct().size == claims.size) { "duplicate claim in $claims" }
        for (s in claims) {
            val out = node(s)
            require(out.compute == null) {
                "$out has its own computation; it cannot also be governed"
            }
            // Deliberate: no use case has come up, and a ref claim would need a
            // writeRefOutput whose shape would be a guess. One line to lift when one does.
            require(!out.isRef) { "$out is a reference node; governors claim scalars" }
            val existing = out.firewall
            require(existing == null) { "$out is already claimed by governor $existing" }
        }

        val g = allocate(name)
        g.compute = body
        g.flags = Flags.DIRTY

        for (s in claims) {
            val out = node(s)
            out.firewall = g
            out.nextChild = g.firstChild
            g.firstChild = out
        }
        return GovernorHandle(g.slot.bits)
    }

    // ---------------------------------------------------------------------------------
    // Disposal
    // ---------------------------------------------------------------------------------

    /**
     * Destroy a node, recycle its index, and wake everything that was reading it.
     *
     * This is what `layout-design.md` Q10 was waiting on. Composition can create nodes but had
     * no way to destroy them, so structural change — a list that loses an item, a panel that
     * closes — could only ever grow the graph.
     *
     * Three things happen, and the order matters:
     *
     *  1. **Subscribers are marked `DIRTY`.** A node that read this one has an answer derived
     *     from something that no longer exists; it must re-run. Marking before unlinking is
     *     what makes the propagation reach them at all.
     *  2. **Every edge is cut**, in both directions. Leaving a subscriber's dependency link in
     *     place would let a *recycled* node's later write mark a node that has nothing to do
     *     with it — invalidation noise that is nearly impossible to trace back here.
     *  3. **The index goes on the free list** and the generation is bumped, so the outstanding
     *     handles that named this node now fail loudly instead of addressing its successor.
     *
     * Disposing a governor releases its claims; disposing a *claimed output* is refused,
     * because a governor that lost one of its outputs would fail its totality check on the
     * next solve with no indication of why. Dispose the governor first.
     *
     * Not callable during evaluation, for the same reason [setInput] is not: the graph would
     * be mutating structure underneath a walk of that structure.
     */
    fun dispose(slot: Slot) {
        checkThread()
        check(context == null) { "nodes may not be disposed from inside a computation" }
        val n = node(slot)
        val fw = n.firewall
        require(fw == null) {
            "$n is claimed by governor $fw and cannot be disposed on its own; " +
                "dispose the governor first, which releases every claim"
        }

        // A governor: release its outputs, which become ordinary unclaimed inputs again.
        var c = n.firstChild
        while (c != null) {
            val next = c.nextChild
            c.firewall = null
            c.nextChild = null
            c = next
        }
        n.firstChild = null

        // Wake and detach every reader.
        var l = n.subs
        while (l != null) {
            val next = l.nextSub
            mark(l.sub, Flags.DIRTY)
            removeDep(l.sub, l)
            l = next
        }
        n.subs = null
        n.subsTail = null

        // Drop our own dependencies — the same walk [trimStaleDeps] does with the cursor rewound.
        n.depsTail = null
        trimStaleDeps(n)

        n.reset(null)
        n.generation = (n.generation + 1) and Slot.GENERATION_MASK
        n.disposed = true
        freeIndices += n.index
        stats.disposed++
    }

    /** Dispose a governor, releasing every slot it claimed. */
    fun dispose(governor: GovernorHandle) {
        dispose(governor.asSlot)
    }

    /** Whether this handle still names a live node. */
    fun isAlive(slot: Slot): Boolean {
        val i = slot.index
        return i < nodes.size && nodes[i].generation == slot.generation
    }

    /**
     * Remove one edge from its subscriber's dependency list.
     *
     * O(deps) because that list is singly linked — the price of [Link] carrying one `nextDep`
     * rather than a back pointer, which is the right trade when linking happens on every read
     * and this happens only on disposal.
     */
    private fun removeDep(sub: Node, target: Link) {
        var prev: Link? = null
        var d = sub.deps
        while (d != null) {
            if (d === target) {
                val next = d.nextDep
                if (prev != null) prev.nextDep = next else sub.deps = next
                if (sub.depsTail === d) sub.depsTail = prev
                stats.linksTrimmed++
                return
            }
            prev = d
            d = d.nextDep
        }
    }

    /**
     * Comparison tolerance for this node's value. `0.0` (the default) means exact
     * comparison, which is the right default: epsilon equality is not transitive, so a
     * slowly drifting value can read as "unchanged" at every step while moving arbitrarily
     * far. Set it only where that is wanted — animation outputs, relaxation governors.
     */
    fun setEpsilon(slot: Slot, epsilon: Double) {
        checkThread()
        require(epsilon >= 0.0) { "epsilon must be non-negative" }
        val n = node(slot)
        require(!n.isRef) { "$n is a reference node; its comparison is a RefEquality" }
        n.epsilon = epsilon
    }

    // ---------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------

    /**
     * Read [slot], recomputing it and anything it transitively depends on if necessary.
     *
     * Called from inside a [Computation], this records a dependency edge. Called from
     * outside, it is a plain memoised pull.
     */
    fun read(slot: Slot): Double {
        checkThread()
        val dep = node(slot)
        require(!dep.isRef) { "$dep holds a reference; use readRef()" }
        if (context == null) depth = 0   // a top-level pull; see [depth]
        settle(dep)
        return dep.value
    }

    /**
     * Read a non-scalar node. Identical machinery to [read] — same flags, same marking, same
     * links, same governor resolution — differing only in which field the value lives in and
     * in how equality is decided ([RefEquality]).
     */
    fun readRef(slot: Slot): Any? {
        checkThread()
        val dep = node(slot)
        require(dep.isRef) { "$dep holds a scalar; use read()" }
        if (context == null) depth = 0
        settle(dep)
        return dep.ref
    }

    /**
     * Bring [dep] up to date and record the dependency edge. The value itself is then read
     * straight out of the node by the caller, which is also why the cycle-breaking policy
     * needs no shadow storage: a node that is mid-evaluation has not committed, so the field
     * still holds last frame's converged result.
     */
    private fun settle(dep: Node) {
        val c = context

        // A governor reading back one of its own claims. Legitimate, and load-bearing: a
        // freeze loop reads the sizes it has already written this pass. No edge is recorded
        // — a governor must not subscribe to its own output, or every write would mark it
        // dirty again.
        val fw = dep.firewall
        if (fw != null && fw === c) return

        // Staleness for a governor output resolves through the governor (§2.6 rule 1),
        // while the dependency edge below still records the *output*. That split is where
        // the fine granularity comes from.
        val owner = fw ?: dep

        if (owner.compute != null) {
            val f = owner.flags
            if (f and Flags.RECOMPUTING != 0) {
                // Re-entry: `owner` is already on the evaluation stack, so this read closes
                // a cycle. Record the edge anyway — it is real, and future invalidation
                // needs it — then break the cycle.
                if (c != null) link(dep, c)
                reportCycle(owner)
                return
            }
            // Uncoloured means settled, and [updateIfNecessary] would do nothing but say so.
            // Worth testing here rather than there because this is the read walk's whole
            // steady-state cost: §11.2 measured 5.3 ms at 40 000 elements *with nothing changed*,
            // which is four reads per element that each reach exactly this branch. A call, a
            // depth check and two more flag tests, times 160 000.
            if (f and Flags.COLOUR != 0) updateIfNecessary(owner)
        }

        if (c != null) {
            link(dep, c)
            // A reader sits strictly above every derived node it reads — and above the
            // *governor* of any output it reads, since that is what has to run first.
            // Inputs are height-invisible. Note this is driven purely by read edges: unlike
            // Solid, node ownership must never constrain height, because in a layout graph
            // a parent's size legitimately depends on its children's.
            if (owner.compute != null && owner.height >= c.height) c.height = owner.height + 1
        }
    }

    /**
     * Resolve a `CHECK` ("maybe stale") node by walking its dependencies in order, forcing
     * each to settle. The moment one of them actually changes value it will have set this
     * node's `DIRTY` flag, at which point there is no point asking the rest.
     *
     * This is the whole diamond-problem solution on the pull side, and the reason a node
     * whose inputs churned but whose values did not is free.
     */
    private fun updateIfNecessary(n: Node) {
        // Already on the evaluation stack. Its flags belong to the in-flight recompute, and
        // clearing them here would destroy the RECOMPUTING bit that cycle detection reads —
        // turning a reported cycle into a silently truncated one. Reachable through the
        // dependency walk below, and through a governor whose solve reads a node that reads
        // one of its claims.
        if (n.flags and Flags.RECOMPUTING != 0) return

        // One link of chain, and the only place both ways of descending one meet: the dependency
        // walk below recurses here directly, and a recompute recurses here through its body's
        // reads. Checked before descending, so the throw happens with stack left to build the
        // message on.
        if (++depth > maxChainDepth) {
            depth--
            throw chainTooDeep(n)
        }

        if (n.flags and Flags.CHECK != 0) {
            // Consume CHECK up front rather than clearing flags at the end. A colour raised
            // after [recompute] has returned is a genuine new signal, and a blanket reset here
            // would silently swallow it — which is exactly what a cycle produces: the node's own
            // commit propagates back around to it, and losing that mark freezes the relaxation
            // on frame 1 at a stable, plausible, wrong answer.
            n.flags = n.flags and Flags.CHECK.inv()
            var d = n.deps
            while (d != null) {
                val dep = d.dep.firewall ?: d.dep     // settle the governor, not the output
                // Same test as in [settle], and it is worth more here: this is the walk §11.2
                // called "the CHECK resolution pass visiting far more nodes than actually run".
                // A resize at 40 000 marks 90 000 nodes and runs 50 000 of them, so the walk
                // spends most of its time confirming that a dependency is fine — and an uncoloured
                // dependency is fine by inspection, without a call and without descending.
                if (dep.compute != null && dep.flags and Flags.COLOUR != 0) updateIfNecessary(dep)
                if (n.flags and Flags.DIRTY != 0) break
                d = d.nextDep
            }
        }
        // DIRTY is consumed by recompute, which overwrites the colour field on entry.
        if (n.flags and Flags.DIRTY != 0) recompute(n)
        depth--
    }

    /**
     * The report [ChainTooDeepException] carries. Built here rather than in the exception so it
     * can see [evalStack], which is the only record of what the walk was in the middle of.
     */
    private fun chainTooDeep(n: Node): ChainTooDeepException {
        val chain = ArrayList<CycleMember>(evalTop)
        for (i in 0 until evalTop) evalStack[i]!!.let { chain += CycleMember(it.slot, it.name) }
        val tail = chain.takeLast(8).joinToString("\n  ← ")
        return ChainTooDeepException(
            "Dependency chain deeper than maxChainDepth ($maxChainDepth) at $n.\n" +
                (if (tail.isEmpty()) "" else "Innermost recomputes:\n  $tail\n") +
                "This is the pull evaluator's stack ceiling, reached before the JVM's. Either " +
                "shorten the chain — a list positioned by index rather than from its previous " +
                "row is one link deep instead of one per row — or size the layout thread for the " +
                "depth you need: layoutThread(maxChainDepth = N) wants " +
                "${ChainDepth.BYTES_PER_LINK * ChainDepth.HEADROOM} bytes of stack per link.",
            n.slot,
            depth + 1,
            chain,
        )
    }

    private fun recompute(n: Node) {
        val prevContext = context
        context = n
        n.depsTail = null            // rewind the reconciliation cursor
        n.depGen++                   // new pass generation
        n.flags = Flags.RECOMPUTING  // also consumes CHECK/DIRTY: this run answers them
        if (evalTop == evalStack.size) evalStack = evalStack.copyOf(evalTop * 2)
        evalStack[evalTop++] = n
        n.recomputes++
        stats.recomputed++

        val oldHeight = n.height
        val body = n.compute
        var next = 0.0
        var nextRef: Any? = null
        var produced = false
        try {
            when (body) {
                is Computation -> next = with(body) { evaluate() }
                is RefComputation -> nextRef = with(body) { evaluateRef() }
                is Solve -> with(body) { solve() }   // writes its claims via writeOutput
                null -> error("recompute on an input node: $n")
            }
            produced = true
        } finally {
            evalStack[--evalTop] = null
            // A colour raised *while this node was running* is discarded, and that is correct
            // rather than merely convenient: it can only have come from a dependency this node
            // read during this very pass, whose fresh value is therefore already folded into
            // `next`. Keeping it would make every node in a chain whose dependencies are all
            // dirty recompute a third time — walk dep A, A changes, break early, recompute,
            // read dep B, B changes, marked dirty again.
            //
            // The mark that must *not* be lost arrives after this point: a node's own `commit`
            // propagating back around a cycle. That happens below, once RECOMPUTING is already
            // clear, which is why [updateIfNecessary] must not reset flags on its way out.
            //
            // Unless the body *threw*, in which case this run answered nothing and the node must
            // stay DIRTY. Clearing it would leave a node that never committed looking settled, so
            // every later read would return the value it had before — for a node that has never
            // run, zero. One failed expression would go on quietly reporting a wrong number for
            // the life of the graph, which is precisely the failure mode this design exists to
            // rule out. `commit` and `trimStaleDeps` below are skipped for the same reason; the
            // next recompute rewinds the reconciliation cursor and redoes both.
            n.flags = if (produced) Flags.NONE else Flags.DIRTY
            context = prevContext
        }

        trimStaleDeps(n)
        when (body) {
            is Computation -> commit(n, next)
            is RefComputation -> commitRef(n, nextRef)
            is Solve -> checkClaimsWritten(n)
            null -> {}
        }

        if (n.height != oldHeight) {
            raiseSubsAbove(n, n.height)
            // A governor's dependents subscribe to its outputs, so the fixup has to hop
            // across the claim list to reach them.
            var c = n.firstChild
            while (c != null) {
                raiseSubsAbove(c, n.height)
                c = c.nextChild
            }
        }
    }

    private fun raiseSubsAbove(n: Node, height: Int) {
        var l = n.subs
        while (l != null) {
            if (l.sub.height <= height) l.sub.height = height + 1
            l = l.nextSub
        }
    }

    /**
     * Every claim must be written on every solve. Enforced rather than tolerated: an output
     * left at its previous value looks exactly like a converged one, so a governor with a
     * missing branch would produce a stale layout that no test distinguishes from a correct
     * one until it is on screen.
     */
    private fun checkClaimsWritten(g: Node) {
        var c = g.firstChild
        while (c != null) {
            check(c.depGen == g.depGen) { "governor $g did not write its claimed output $c" }
            c = c.nextChild
        }
    }

    private fun commit(n: Node, next: Double) {
        if (valueEquals(n, n.value, next)) return   // the payoff: no notification at all
        n.value = next
        notifySubs(n)
    }

    private fun commitRef(n: Node, next: Any?) {
        if (refEquals(n, n.ref, next)) return
        n.ref = next
        notifySubs(n)
    }

    // ---------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------

    /** Write an input. Marks dependents; recomputes nothing until something is read. */
    fun setInput(slot: Slot, value: Double) {
        checkThread()
        val n = node(slot)
        require(!n.isRef) { "$n holds a reference; use setRefInput()" }
        checkWritableInput(n)
        if (valueEquals(n, n.value, value)) return
        n.value = value
        notifySubs(n)
    }

    /** Write a non-scalar input. Marks dependents; recomputes nothing until something is read. */
    fun setRefInput(slot: Slot, value: Any?) {
        checkThread()
        val n = node(slot)
        require(n.isRef) { "$n holds a scalar; use setInput()" }
        checkWritableInput(n)
        if (refEquals(n, n.ref, value)) return
        n.ref = value
        notifySubs(n)
    }

    private fun checkWritableInput(n: Node) {
        require(n.compute == null) { "$n is derived, not an input" }
        val fw = n.firewall
        require(fw == null) { "$n is claimed by governor $fw; only its solve may write it" }
        check(context == null) { "inputs may not be written from inside a computation" }
    }

    /**
     * A governor writing one of its claimed outputs. Only callable from inside that
     * governor's own [Solve] — `context` is what authorises the write, so there is no way to
     * reach a governed slot from outside.
     *
     * Each output carries its own comparison, so this is where fine-grained governor
     * outputs come from: a re-solve that lands on the same value for one claim notifies
     * nobody about that claim.
     */
    fun writeOutput(slot: Slot, value: Double) {
        checkThread()
        val n = node(slot)
        val g = context
        require(g != null && n.firewall === g) {
            "$n is not claimed by the running governor" +
                (if (g == null) " (no governor is solving)" else " ($g)")
        }
        n.depGen = g.depGen        // "written in this pass"; see checkClaimsWritten
        n.flags = Flags.NONE
        commit(n, value)
    }

    private fun notifySubs(n: Node) {
        var l = n.subs
        while (l != null) {
            mark(l.sub, Flags.DIRTY)
            l = l.nextSub
        }
    }

    /**
     * Worklist for [mark]. One instance, reused and never shrunk, so marking allocates nothing
     * once a graph has seen its worst frame.
     *
     * A raw array rather than an `ArrayList` because this is the whole of the marking walk's
     * bookkeeping and the list's bounds checks and `removeAt` shuffle were showing up next to the
     * two pointer loads that are the actual work.
     *
     * Safe to share because marking runs to completion without calling anything that could mark
     * again: [mark] touches only flags and link lists, never a compute body. Nested recomputes do
     * not overlap here either — an inner recompute commits, drains this stack and returns before
     * the outer one commits.
     */
    private var markStack = arrayOfNulls<Node>(64)
    private var markTop = 0

    private fun pushMark(n: Node) {
        if (markTop == markStack.size) markStack = markStack.copyOf(markTop * 2)
        markStack[markTop++] = n
    }

    /**
     * Raise a node's colour and propagate `CHECK` downstream.
     *
     * The `>=` on the masked field is simultaneously the visited check and the "never
     * downgrade DIRTY to CHECK" rule, which is why [Flags.CHECK] must be 1 and
     * [Flags.DIRTY] must be 2.
     *
     * **Iterative** rather than recursive, and for the same reason [Graph.maxChainDepth] exists:
     * marking descends the subscriber graph, so its depth is also the longest chain, and a write
     * at the head of a long list would blow the stack in the *write* rather than in the read. That
     * one is worth removing outright instead of budgeting, because unlike pull evaluation nothing
     * here needs the call stack — the walk carries no state between levels beyond "raise CHECK",
     * so a worklist is a straight substitution rather than a rewrite of the algorithm.
     *
     * **Colours are raised when a node is pushed, not when it is popped**, which is what keeps the
     * stack proportional to the nodes marked rather than to the edges traversed. A layout graph is
     * full of small diamonds — `width` reads `left` and `right`, both of which read the parent — so
     * a node is typically reachable by two to four paths and would otherwise be pushed once per
     * path and discarded on all but the first. The set of nodes raised is identical either way, and
     * so is `stats.marked`; what changes is how much of the walk is wasted motion.
     */
    private fun mark(start: Node, newState: Int) {
        if (!raise(start, newState)) return

        // Tried and rejected here: returning early when `start` was *already* coloured, on the
        // argument that a `CHECK`-to-`DIRTY` raise tells subscribers nothing the walk that
        // coloured it has not already said. The argument holds — it rests on "coloured implies
        // every transitive subscriber is coloured", which survives because a node only loses its
        // colour by settling, and settling settles its dependencies first — and it should have
        // paid, since a 40k resize raises `CHECK` to `DIRTY` tens of thousands of times through
        // `notifySubs`. It measured as **no change at all**, because raise-at-push has already made
        // the redundant walk nearly free: every subscriber it reaches is rejected by one flags test
        // against a `Node` that resolution has just pulled into cache. So the shortcut bought
        // nothing and cost a non-local invariant, which is a bad trade at any price.
        pushSubscribers(start)
        while (markTop > 0) {
            val n = markStack[--markTop]!!
            markStack[markTop] = null   // so a long-dead node is not held live by the scratch stack
            pushSubscribers(n)
        }
    }

    /** Raise one node's colour. Returns whether it moved — i.e. whether to walk on past it. */
    private fun raise(n: Node, newState: Int): Boolean {
        val f = n.flags
        if (f and Flags.COLOUR >= newState) return false
        n.flags = (f and Flags.COLOUR.inv()) or newState
        stats.marked++
        return true
    }

    private fun pushSubscribers(n: Node) {
        var l = n.subs
        while (l != null) {
            val s = l.sub
            if (raise(s, Flags.CHECK)) pushMark(s)
            l = l.nextSub
        }

        // If `n` is a governor, its dependents subscribe to its outputs rather than to it,
        // so propagation has to hop across the claim list (§2.6 rule 3). The outputs are not
        // themselves coloured: only the governor's flags decide whether the solve re-runs,
        // and only the per-output comparison in writeOutput decides who hears about it.
        var c = n.firstChild
        while (c != null) {
            var cl = c.subs
            while (cl != null) {
                val s = cl.sub
                if (raise(s, Flags.CHECK)) pushMark(s)
                cl = cl.nextSub
            }
            c = c.nextChild
        }
    }

    /**
     * Reproduces JavaScript `Object.is` semantics exactly: `NaN` equals `NaN`, and `-0.0`
     * does not equal `0.0`.
     *
     * The `NaN` case is not academic. A layout graph will produce `NaN` — a zero-width
     * container split into flex shares, a degenerate aspect ratio, an unresolved font
     * metric. Under IEEE comparison a node holding `NaN` reports "changed" on every single
     * evaluation, notifies its subscribers, and the graph never reaches a fixed point:
     * a permanently dirty region, re-evaluated every frame, silently.
     */
    private fun valueEquals(n: Node, a: Double, b: Double): Boolean =
        if (n.epsilon == 0.0) a.toRawBits() == b.toRawBits()
        else Math.abs(a - b) <= n.epsilon

    private fun refEquals(n: Node, a: Any?, b: Any?): Boolean = when (n.refEquality) {
        RefEquality.IDENTITY -> a === b
        RefEquality.EQUALS -> a == b
        RefEquality.ALWAYS -> false
    }

    // ---------------------------------------------------------------------------------
    // Dependency reconciliation
    // ---------------------------------------------------------------------------------

    /**
     * Record that [sub] read [dep], reusing the existing link if possible.
     *
     * Three fast exits precede any allocation. In steady state — same dependency set, same
     * read order — every read hits exit 1 or 2 and the whole recompute allocates nothing.
     */
    private fun link(dep: Node, sub: Node) {
        val prevDep = sub.depsTail
        if (prevDep != null && prevDep.dep === dep) return          // 1. adjacent repeat read

        var nextDep: Link? = null
        val recomputing = sub.flags and Flags.RECOMPUTING != 0
        if (recomputing) {
            nextDep = if (prevDep != null) prevDep.nextDep else sub.deps
            if (nextDep != null && nextDep.dep === dep) {           // 2. in-order match
                nextDep.gen = sub.depGen
                sub.depsTail = nextDep
                return
            }
        }

        // 3. Out-of-order repeat: already linked earlier in this same pass. Common when a
        // compute re-reads an input it already touched — a governor's freeze loop, or
        // `children.maxOf { }` over a shared parent value. The generation stamp answers
        // this in O(1); scanning the dependency list would make it O(n²).
        val prevSub = dep.subsTail
        if (prevSub != null && prevSub.sub === sub &&
            (!recomputing || prevSub.gen == sub.depGen)
        ) return

        val fresh = Link(dep, sub, nextDep, prevSub, null, sub.depGen)
        stats.linksAllocated++
        sub.depsTail = fresh
        dep.subsTail = fresh
        if (prevDep != null) prevDep.nextDep = fresh else sub.deps = fresh
        if (prevSub != null) prevSub.nextSub = fresh else dep.subs = fresh
    }

    /** Unlink one edge from its dependency's subscriber list; returns the next dependency. */
    private fun unlinkSub(l: Link): Link? {
        val dep = l.dep
        val nextSub = l.nextSub
        val prevSub = l.prevSub
        if (nextSub != null) nextSub.prevSub = prevSub else dep.subsTail = prevSub
        if (prevSub != null) prevSub.nextSub = nextSub else dep.subs = nextSub
        stats.linksTrimmed++
        return l.nextDep
    }

    /**
     * Drop every dependency past the reconciliation cursor — exactly the set that was read
     * on the previous pass and not on this one. This is what makes conditional dependencies
     * work: a branch that stops being taken stops being a dependency.
     */
    private fun trimStaleDeps(n: Node) {
        val tail = n.depsTail
        var toRemove = if (tail != null) tail.nextDep else n.deps
        if (toRemove != null) {
            do {
                toRemove = unlinkSub(toRemove!!)
            } while (toRemove != null)
            if (tail != null) tail.nextDep = null else n.deps = null
        }
    }

    // ---------------------------------------------------------------------------------
    // Cycles
    // ---------------------------------------------------------------------------------

    /**
     * Break a cycle using the previous frame's value.
     *
     * [owner] is mid-recompute, so it has not committed anything this frame and the node the
     * caller is about to read still holds last frame's converged result. No shadow storage is
     * needed; [settle] simply returns and the caller reads the stale field.
     *
     * The one exception is a governor output: a governor may already have written some of
     * its claims before the cyclic read arrives, so the observed value can come from this
     * pass rather than the last. That is strictly better information, not worse, but it does
     * mean a cycle through a governor is order-sensitive within the solve.
     *
     * This always terminates, costs O(1), and cannot oscillate within a frame. One frame of
     * lag is imperceptible; a solver that spikes the frame time or silently returns a wrong
     * answer is worse as a default.
     */
    private fun reportCycle(owner: Node) {
        var start = -1
        for (i in 0 until evalTop) if (evalStack[i] === owner) { start = i; break }
        if (start >= 0) {
            val members = ArrayList<CycleMember>(evalTop - start)
            for (i in start until evalTop) {
                val e = evalStack[i]!!
                members += CycleMember(e.slot, e.name)
            }
            diagnostics.cycle(members)
        }
    }

    // ---------------------------------------------------------------------------------
    // Introspection
    // ---------------------------------------------------------------------------------

    /** How many times this node's compute has run since it was created. */
    fun recomputeCount(slot: Slot): Int = node(slot).recomputes

    /** How many times this governor has solved. */
    fun solveCount(g: GovernorHandle): Int = node(g).recomputes

    /**
     * Strictly above every derived node it reads. Inputs are always 0. A governor output
     * reports its governor's height, since that is what its readers are ordered against.
     */
    fun graphHeightOf(slot: Slot): Int = node(slot).let { (it.firewall ?: it).height }

    fun graphHeightOf(g: GovernorHandle): Int = node(g).height

    /** The node's current dependencies, in the order they were last read. */
    fun dependenciesOf(slot: Slot): List<Slot> = depsOf(node(slot))

    /** What the governor read on its last solve, in read order. */
    fun dependenciesOf(g: GovernorHandle): List<Slot> = depsOf(node(g))

    private fun depsOf(n: Node): List<Slot> {
        val out = ArrayList<Slot>()
        var l = n.deps
        while (l != null) { out += l.dep.slot; l = l.nextDep }
        return out
    }

    /** The slots this governor computes. Order is unspecified. */
    fun claimsOf(g: GovernorHandle): List<Slot> {
        val out = ArrayList<Slot>()
        var c = node(g).firstChild
        while (c != null) { out += c.slot; c = c.nextChild }
        return out
    }

    /** The name this slot was created with, if any. */
    fun nameOf(slot: Slot): String? = node(slot).name

    /**
     * A label for the node currently being evaluated, or `null` outside a computation.
     *
     * For diagnostics only, and specifically for errors raised *by* an expression: a layer
     * built on top of the graph can say which slot's expression failed without threading that
     * context through its own API.
     */
    fun evaluatingLabel(): String? = context?.let { it.name ?: it.slot.toString() }

    /** Whether this slot holds a reference rather than a `Double`. */
    fun isRefNode(slot: Slot): Boolean = node(slot).isRef

    /** The governor that computes this slot, or `null` if it is not governed. */
    fun governorOf(slot: Slot): GovernorHandle? =
        node(slot).firewall?.let { GovernorHandle(it.slot.bits) }

    /** The nodes that currently read this one. Order is unspecified. */
    fun subscribersOf(slot: Slot): List<Slot> {
        val out = ArrayList<Slot>()
        var l = node(slot).subs
        while (l != null) { out += l.sub.slot; l = l.nextSub }
        return out
    }

    /**
     * Live nodes. Excludes disposed indices awaiting reuse, so a leak assertion — compose,
     * churn a list, compose the same thing again — compares two numbers that should match.
     */
    val nodeCount: Int get() = nodes.size - freeIndices.size

    /** Indices allocated, live or awaiting reuse. The high-water mark. */
    val nodeCapacity: Int get() = nodes.size

    private fun checkThread() {
        check(Thread.currentThread() === owningThread) {
            "Graph is single-threaded; created on $owningThread, used from ${Thread.currentThread()}"
        }
    }
}
