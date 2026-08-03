package com.dubulduke.layout

import com.dubulduke.reactivity.Computation
import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.GovernorHandle
import com.dubulduke.reactivity.Slot

/** Which of an element's two axes something refers to. */
enum class Direction { HORIZONTAL, VERTICAL }

/** The axis of [d] on this element. */
fun Element.axis(d: Direction): Axis = if (d == Direction.HORIZONTAL) h else v

/** This element's flex factor, or `0.0` if it does not grow. A tracked read. */
fun Graph.flexOf(e: Element): Double {
    val s = e.flexSlot ?: return 0.0
    return read(s)
}

/**
 * The two quantities a flex container hoists so that its growing children do not have to read
 * each other.
 *
 * `layout-design.md` §6.2 is the argument, and it is the design's central claim: flex-grow
 * written naively *is* a genuine cycle, and it dissolves entirely under one refactor. Naively,
 * each growing child computes
 *
 * ```
 * width = (parent.width - otherChildren.sumOf { it.width }) * myFlex / totalFlex
 * ```
 *
 * so `B.width` reads `C.width` reads `B.width`. A real strongly connected component, and the
 * hardest-looking case in the whole design.
 *
 * Hoisting [freeSpace] into a node the *container* owns breaks it, because `freeSpace` reads
 * only the **non-growing** children's sizes. `B.size` and `C.size` both read `freeSpace`;
 * neither reads the other. Plain DAG, one evaluation each, no iteration, no governor.
 *
 * > To resolve competition between siblings, hoist the contested quantity into a variable the
 * > parent owns and both siblings read.
 *
 * A governor is the generalisation of exactly this move, for the cases where the contested
 * quantity genuinely cannot be computed without the contestants — flex-*shrink* with clamps.
 */
class FlexRow internal constructor(
    @JvmField val container: Element,
    @JvmField val direction: Direction,
    /** Which mechanism resolves this row. Fixed at composition time. */
    @JvmField val mode: FlexMode,
    private val freeSpaceOrNull: Slot?,
    private val totalFlexOrNull: Slot?,
    private val governorOrNull: GovernorHandle?,
    private val contentOrNull: Slot? = null,
) {
    /**
     * The container's main-axis size minus the fixed items' sizes: the pool the growing items
     * share. Reads the fixed items only, which is the whole point.
     *
     * [FlexMode.HOIST] only. A governed row keeps the same quantity in a local variable inside
     * its solve, because the freeze loop *mutates* it — the pool after freezing an item is not
     * the pool before, so there is no single value for a node to hold.
     */
    val freeSpace: Slot
        get() = checkNotNull(freeSpaceOrNull) { "$this is governed; free space is internal to the solve" }

    /** Sum of the growing items' flex factors. [FlexMode.HOIST] only, for the same reason. */
    val totalFlex: Slot
        get() = checkNotNull(totalFlexOrNull) { "$this is governed; the flex total changes as items freeze" }

    /** The governor resolving this row. [FlexMode.GOVERN] only, and only if anything grows. */
    val governor: GovernorHandle
        get() = checkNotNull(governorOrNull) {
            if (mode == FlexMode.HOIST) "$this is hoisted; it has no governor, which is the point"
            else "$this has no growing items, so there was nothing to contest"
        }

    /** Whether a governor was actually installed. False for an all-fixed clamped row. */
    val isGoverned: Boolean get() = governorOrNull != null

    /**
     * The row's resolved main-axis size. [FlexMode.SHRINK] only.
     *
     * **This is the node that breaks the shrink-to-fit cycle**, and it is the same move as
     * [freeSpace] one level up. A clamped row reads its container's main size to work out the pool,
     * so a container sized *by* these children closes a loop: container ← children ← governor ←
     * container. Hoisting the answer into a node the governor *writes* and the container *reads*
     * cuts it — the solve now reads an outer bound supplied by the author, never the container.
     *
     * The container's size expression is an ordinary read of this. No claim on the container's own
     * slot, so its origin stays the author's to write and nested rows cannot collide over it.
     */
    val contentSize: Slot
        get() = checkNotNull(contentOrNull) { "$this is not a shrink row; it has no content size" }

    override fun toString(): String = "FlexRow(${container.path}, $direction, $mode)"
}

/** How a [FlexRow] resolves its items' main-axis sizes. */
enum class FlexMode {
    /**
     * The pool is a node the container owns and every growing item reads
     * (`layout-design.md` §6.2). No governor, one evaluation per slot, no iteration. What
     * flex-*grow* needs, and the design's central claim.
     */
    HOIST,

    /**
     * The growing items' sizes are claimed by one governor that computes them jointly
     * (§7.3). What min/max clamps force: freezing a clamp-violating item returns space to the
     * pool, changing everyone else's share, possibly violating another clamp. No amount of
     * hoisting removes that loop — it is why the CSS flexbox spec has a freeze-and-loop step.
     */
    GOVERN,

    /**
     * **Shrink to fit.** The row is as large as its items want, up to an outer bound; past that
     * they shrink. The row's own main size is an *output* of the solve rather than an input to it.
     *
     * This is the case [GOVERN] cannot express. A clamped row reads `sizeOf(container)` to size its
     * pool, so it needs the container's size to already be known — which fails exactly when the
     * container is sized *by* these children, and that is the commonest shrink-wrapped layout there
     * is: a toolbar as wide as its buttons, that compresses them when the window narrows.
     *
     * The fix is the same one that made flex-grow acyclic, applied one level up. Rather than the
     * governor reading the container, it *writes* a [FlexRow.contentSize] node the container reads,
     * and takes its outer bound from an expression the author supplies. Nothing reads the
     * container's main size during the solve, so there is no loop to break.
     */
    SHRINK,
}

/**
 * Lay this element's children out along one axis, distributing leftover space among the
 * growing ones.
 *
 * The container owns the main axis of every item — position chains off the previous item, size
 * is either fixed or a share of [FlexRow.freeSpace] — and the caller owns each item's cross
 * axis.
 *
 * ```kotlin
 * child("row") {
 *     h { origin { 0.0 }; size { 300.0 } }
 *     v { origin { 0.0 }; size { 40.0 } }
 *     flexRow {
 *         fixed(mainSize = { 100.0 }) { v { origin { 0.0 }; size { 40.0 } } }
 *         grow(1.0)                   { v { origin { 0.0 }; size { 40.0 } } }
 *         grow(2.0)                   { v { origin { 0.0 }; size { 40.0 } } }
 *     }
 * }
 * ```
 *
 * One caveat worth stating: [FlexRow.freeSpace] reads the container's own main-axis size, so a
 * flex row inside a *shrink-to-fit* container — whose size comes from these very children — is
 * a genuine cycle rather than a false one. That is the `FlexGovernor` case (§7.3), not this one.
 */
fun ElementBuilder.flexRow(
    direction: Direction = Direction.HORIZONTAL,
    clamped: Boolean = false,
    /**
     * An outer bound on the row's main size, which switches it to [FlexMode.SHRINK].
     *
     * Supplying this makes the row's own size an *output*: it is as large as its items want, up to
     * this, and past it they compress. The bound has to be an expression from outside rather than
     * the container's own size, because computing that size is the point.
     */
    shrinkToFit: Computation? = null,
    build: FlexScope.() -> Unit,
): FlexRow {
    val g = tree.graph
    val container = element

    if (shrinkToFit != null) {
        require(!clamped) {
            "$container cannot be both clamped and shrink-to-fit: one reads the container's size " +
                "to divide a pool, the other computes that size, so together they are the cycle " +
                "shrink-to-fit exists to avoid"
        }
        return shrinkRow(g, container, direction, shrinkToFit, build)
    }

    if (clamped) return governedRow(g, container, direction, build)

    // Hoisted before the children are built, so their expressions can capture the handles.
    // Both read the *published child list*, so adding or removing an item invalidates them
    // (§8.7) rather than only a change in some existing item's numbers.
    val freeSpace = g.derive("${container.path}.freeSpace") {
        var used = 0.0
        for (c in childrenOf(container)) {
            // The fixed/growing split is a *structural* property — which of `fixed` and `grow`
            // built the item — so testing it costs a field load rather than a graph read. That
            // matters: reading each child's flex factor here would make `freeSpace` depend on
            // every flex factor, and changing one would re-run the pool calculation to produce
            // the same number. The consequence is that an item cannot cross between fixed and
            // growing without recomposition, which is the right side of the values-versus-
            // structure line (`dsl-and-engine.md` §5.2).
            if (c.flexSlot == null) used += sizeOf(c.axis(direction))
        }
        sizeOf(container.axis(direction)) - used
    }
    val totalFlex = g.derive("${container.path}.totalFlex") {
        var sum = 0.0
        for (c in childrenOf(container)) {
            val f = c.flexSlot
            if (f != null) sum += read(f)
        }
        sum
    }

    val row = FlexRow(container, direction, FlexMode.HOIST, freeSpace, totalFlex, null)
    FlexScope(this, row).apply(build)
    return row
}

/**
 * The clamped path: build the items with their main-axis sizes as unwritten inputs, then hand
 * the whole set to one governor.
 *
 * The order matters and is the reason `clamped` is an explicit argument rather than something
 * inferred from whether any item declared a clamp. A governed item's size slot has to be created
 * as a plain input *while the item is being built*, and whether any clamp exists is not known
 * until every item has been built. Deciding after the fact would mean either two passes over the
 * item list — which would have to defer the child elements themselves, so `fixed`/`grow` could
 * no longer return one — or discovering the governor at layout time, which §7.3 is explicitly
 * about avoiding. An argument costs one word at the call site and keeps the decision where §7.3
 * wants it: taken by the container, at composition, deterministically.
 */
private fun ElementBuilder.governedRow(
    g: Graph,
    container: Element,
    direction: Direction,
    build: FlexScope.() -> Unit,
): FlexRow {
    val row = FlexRow(container, direction, FlexMode.GOVERN, null, null, null)
    val scope = FlexScope(this, row).apply(build)
    val items = scope.governed

    // Nothing is contested, so there is nothing for a governor to decide. Installing one that
    // claims no slots is not possible anyway — the graph rejects an empty claim set — and this is
    // the honest reading of the situation rather than a workaround for that.
    if (items.isEmpty()) return row

    val gov = g.governor(items.map { it.sizeSlot }, "${container.path}.flex") {
        // The outer part of the problem, resolved by the evaluator in the ordinary way: the
        // container's own main-axis size may come from its parent, and the fixed items' sizes are
        // their own expressions. Only the *inner* knot — how the remainder splits between
        // competing growing items — belongs to the governor (§7.3).
        var free = sizeOf(container.axis(direction))
        for (c in childrenOf(container)) {
            if (c.flexSlot == null) free -= sizeOf(c.axis(direction))
        }

        // Freeze-and-loop, straight out of the flexbox spec. Each pass either freezes at least
        // one item — so the pool strictly shrinks — or settles every remaining item and stops,
        // which bounds the whole thing at `items.size + 1` passes with no convergence test and
        // no iteration cap. That is worth contrasting with §7.4's `RelaxGovernor`: this loop is
        // not relaxation, it terminates exactly, and it is the reason declared governors are the
        // intended path rather than the fallback.
        var pool = items
        while (true) {
            var total = 0.0
            for (k in pool) total += read(k.flexSlot)

            // No flex left to divide among the survivors. Not NaN: a node holding NaN compares
            // unequal to itself under IEEE rules, and while the graph's raw-bits comparison
            // stops that re-dirtying anything forever, it is still not a value to propagate.
            if (total <= 0.0) {
                for (k in pool) writeOutput(k.sizeSlot, 0.0)
                return@governor
            }

            var frozen = 0.0
            val survivors = ArrayList<FlexItem>(pool.size)
            for (k in pool) {
                val want = free * read(k.flexSlot) / total
                val lo = k.minSlot?.let { read(it) } ?: 0.0
                val hi = k.maxSlot?.let { read(it) } ?: Double.POSITIVE_INFINITY
                // Applied in this order so that `min` wins a contradictory pair rather than
                // `coerceIn` throwing. CSS resolves min-width against max-width the same way,
                // and a layout that dies on a bad clamp pair is worse than one that picks.
                val got = if (want > hi) hi else want
                val clamped = if (got < lo) lo else got
                if (clamped != want) {
                    writeOutput(k.sizeSlot, clamped)
                    frozen += clamped
                } else {
                    survivors += k
                }
            }

            if (survivors.size == pool.size) {
                // Nobody violated a clamp, so this distribution stands.
                for (k in pool) writeOutput(k.sizeSlot, free * read(k.flexSlot) / total)
                return@governor
            }

            free -= frozen
            pool = survivors
            if (pool.isEmpty()) return@governor
        }
    }

    // Held on the element so subtree disposal can release the claims before it frees the
    // children whose size slots they are.
    container.governor = gov
    return FlexRow(container, direction, FlexMode.GOVERN, null, null, gov)
}

/**
 * The shrink-to-fit path: items state a preferred size, the row states an outer bound, and the
 * governor writes both the items' sizes and the row's.
 *
 * [available] is the bound, supplied by the author — typically `parent.width` less some padding.
 * It has to come from outside because the whole point is that the container's own size is being
 * computed here; asking it what size it is would be the cycle this mode exists to avoid.
 */
private fun ElementBuilder.shrinkRow(
    g: Graph,
    container: Element,
    direction: Direction,
    available: Computation,
    build: FlexScope.() -> Unit,
): FlexRow {
    val contentSlot = g.input(0.0, "${container.path}.content")
    val row = FlexRow(container, direction, FlexMode.SHRINK, null, null, null, contentSlot)
    val scope = FlexScope(this, row).apply(build)
    val items = scope.shrinking

    // Always installed, even with nothing shrinkable: the content size still has to be computed
    // from the fixed items, and an input with no writer would sit at zero forever. Unlike the
    // governed path there is no "nothing is contested" shortcut, because the row's own size is
    // contested by construction.
    val gov = g.governor(items.map { it.sizeSlot } + contentSlot, "${container.path}.shrink") {
        val limit = available.run { evaluate() }

        // Fixed items keep ordinary expressions, so the claim set stays minimal and they are read
        // here like any other dependency.
        var fixed = 0.0
        for (c in childrenOf(container)) {
            if (items.none { it.element === c }) fixed += sizeOf(c.axis(direction))
        }

        var wanted = fixed
        for (k in items) wanted += read(k.basisSlot)

        if (wanted <= limit || items.isEmpty()) {
            // It all fits: everyone gets what they asked for and the row is exactly that wide.
            for (k in items) writeOutput(k.sizeSlot, read(k.basisSlot))
            writeOutput(contentSlot, wanted)
            return@governor
        }

        var overflow = wanted - limit
        var pool = items.filter { read(it.shrinkSlot) > 0.0 }
        // Nothing willing to give way, so the row simply overflows its bound. Reporting the
        // honest size rather than the bound matters: the container is then visibly too big, which
        // is a layout bug the author can see, where silently clipping to the bound would hide it.
        if (pool.isEmpty()) {
            for (k in items) writeOutput(k.sizeSlot, read(k.basisSlot))
            writeOutput(contentSlot, wanted)
            return@governor
        }

        val frozen = HashMap<Element, Double>()
        while (true) {
            // The **scaled** shrink factor, as the flexbox spec has it: weight is `shrink × basis`,
            // not `shrink` alone. Without the scaling a wide item and a narrow one with equal
            // factors lose the same absolute amount, so the narrow one hits zero first and the
            // result looks nothing like proportional compression.
            var weight = 0.0
            for (k in pool) weight += read(k.shrinkSlot) * read(k.basisSlot)
            if (weight <= 0.0) {
                for (k in pool) frozen[k.element] = read(k.basisSlot)
                break
            }

            var violated = false
            var recovered = 0.0
            val survivors = ArrayList<ShrinkItem>(pool.size)
            for (k in pool) {
                val basis = read(k.basisSlot)
                val want = basis - overflow * (read(k.shrinkSlot) * basis) / weight
                val lo = k.minSlot?.let { read(it) } ?: 0.0
                if (want < lo) {
                    // Frozen at its floor, and the space it refused to give up goes back into the
                    // overflow for everyone else to absorb — the same freeze-and-loop shape as the
                    // clamped grow path, and it terminates for the same reason.
                    frozen[k.element] = lo
                    recovered += basis - lo
                    violated = true
                } else {
                    survivors += k
                }
            }

            if (!violated) {
                for (k in pool) {
                    val basis = read(k.basisSlot)
                    frozen[k.element] = basis - overflow * (read(k.shrinkSlot) * basis) / weight
                }
                break
            }

            overflow -= recovered
            pool = survivors
            if (pool.isEmpty()) break
            if (overflow <= 0.0) {
                for (k in pool) frozen[k.element] = read(k.basisSlot)
                break
            }
        }

        var content = fixed
        for (k in items) {
            val size = frozen[k.element] ?: read(k.basisSlot)
            writeOutput(k.sizeSlot, size)
            content += size
        }
        writeOutput(contentSlot, content)
    }

    container.governor = gov
    return FlexRow(container, direction, FlexMode.SHRINK, null, null, gov, contentSlot)
}

/** One growing item of a governed row: what its solve needs, captured at composition. */
internal class FlexItem(
    @JvmField val element: Element,
    // No @JvmField on these four: a value-class-typed property cannot have one.
    val flexSlot: Slot,
    val sizeSlot: Slot,
    val minSlot: Slot?,
    val maxSlot: Slot?,
)

/**
 * One shrinkable item: a preferred size and how willingly it gives it up.
 *
 * Separate from [FlexItem] because the quantities genuinely differ — a growing item has a share of
 * a pool, a shrinking one has a size it starts from and a weight for losing it — and merging them
 * would mean a class where half the fields are meaningless in either mode.
 */
internal class ShrinkItem(
    @JvmField val element: Element,
    val basisSlot: Slot,
    val shrinkSlot: Slot,
    val sizeSlot: Slot,
    val minSlot: Slot?,
)

/** Adds items to a [flexRow]. */
@LayoutDsl
class FlexScope internal constructor(
    private val owner: ElementBuilder,
    private val row: FlexRow,
) {
    /**
     * An item at a size of its own choosing. Its size is subtracted from the free pool.
     *
     * [build] is an ordinary [ElementBuilder] block with the main axis already installed, so it
     * must supply the **cross** axis and may do anything else an element can — classes, text,
     * children, a nested flex row. Supplying the main axis again is an error.
     */
    fun fixed(
        mainSize: Computation,
        id: String? = null,
        build: ElementBuilder.() -> Unit,
    ): Element = add(id, flex = 0.0, fixedSize = mainSize, min = null, max = null, build = build)

    /**
     * An item that takes `flex / totalFlex` of the free space. [flex] must be positive; a
     * zero-flex growing item is a contradiction, and silently treating it as [fixed] would
     * leave it with no size expression at all.
     *
     * [min] and [max] need `flexRow(clamped = true)`, because a clamp is what makes the row
     * iterative and so what makes the governor necessary. They are expressions, not constants,
     * so a clamp can be computed and can change without recomposition — changing one re-solves
     * the row like any other dependency.
     */
    fun grow(
        flex: Double,
        id: String? = null,
        min: Computation? = null,
        max: Computation? = null,
        build: ElementBuilder.() -> Unit,
    ): Element {
        require(flex > 0.0) { "a growing item needs a positive flex factor, got $flex" }
        require(row.mode == FlexMode.GOVERN || (min == null && max == null)) {
            "$row cannot clamp an item: min/max make the row iterative, which needs " +
                "flexRow(clamped = true)"
        }
        return add(id, flex = flex, fixedSize = null, min = min, max = max, build = build)
    }

    /** The growing items, in order, for [governedRow] to hand to the governor. */
    internal val governed = ArrayList<FlexItem>()

    /** The shrinkable items, in order, for [shrinkRow] to hand to the governor. */
    internal val shrinking = ArrayList<ShrinkItem>()

    /**
     * An item at its preferred size, which it gives up under pressure. [FlexMode.SHRINK] only.
     *
     * [basis] is what it would like to be; [shrink] is how willingly it yields, weighted by the
     * basis as the flexbox spec does — a wide item and a narrow one with equal factors should not
     * lose the same *absolute* amount, or the narrow one reaches zero first and the result looks
     * nothing like proportional compression. `shrink = 0.0` makes it incompressible.
     *
     * [min] is a floor; an item that would be pushed below it freezes there and its share of the
     * overflow is redistributed.
     */
    fun shrinkable(
        basis: Computation,
        shrink: Double = 1.0,
        id: String? = null,
        min: Computation? = null,
        build: ElementBuilder.() -> Unit,
    ): Element {
        require(row.mode == FlexMode.SHRINK) {
            "shrinkable items need flexRow(shrinkToFit = …); ${row.container.path} is ${row.mode}"
        }
        require(shrink >= 0.0) { "shrink must not be negative, was $shrink" }

        val direction = row.direction
        val container = row.container
        val shrinking = this.shrinking

        return owner.child(id) {
            val self = element
            val g = tree.graph
            val basisSlot = g.derive("${self.path}.basis", basis)
            val shrinkSlot = g.input(shrink, "${self.path}.shrink")

            val main: AxisBuilder.() -> Unit = {
                origin {
                    val p = prevOf(self)
                    if (p == null) originOf(container.axis(direction))
                    else endOf(p.axis(direction))
                }
                shrinking += ShrinkItem(
                    element = self,
                    basisSlot = basisSlot,
                    shrinkSlot = shrinkSlot,
                    sizeSlot = claimable(Property.SIZE),
                    minSlot = min?.let { g.derive("${self.path}.min", it) },
                )
            }

            when (direction) {
                Direction.HORIZONTAL -> h(build = main)
                Direction.VERTICAL -> v(build = main)
            }
            build()
        }
    }

    private fun add(
        id: String?,
        flex: Double,
        fixedSize: Computation?,
        min: Computation?,
        max: Computation?,
        build: ElementBuilder.() -> Unit,
    ): Element {
        // Hoisted out of the block below because @LayoutDsl — correctly — refuses to resolve a
        // FlexScope member against an ElementBuilder receiver.
        val row = this.row
        val direction = row.direction
        val container = row.container
        val governedRow = row.mode == FlexMode.GOVERN
        require(row.mode != FlexMode.SHRINK || flex <= 0.0) {
            "${'$'}{row.container.path} is shrink-to-fit, so there is no free pool for a growing item " +
                "to take a share of; use shrinkable(basis, shrink) instead"
        }
        // Only a hoisted row has these. A governed row keeps the pool inside its solve, and a
        // shrink row has no pool at all — reaching for either would throw the "that is internal"
        // error from a call site that has done nothing wrong.
        val hoisted = row.mode == FlexMode.HOIST
        val freeSpace = if (hoisted) row.freeSpace else null
        val totalFlex = if (hoisted) row.totalFlex else null
        val governed = this.governed

        return owner.child(id) {
            val self = element

            val flexSlot = if (flex > 0.0) {
                tree.graph.input(flex, "${self.path}.flex").also { self.flexSlot = it }
            } else {
                null
            }

            // A governed item's size is not an expression at all: the axis gets an input that
            // only the governor may write. A fixed item is untouched by the mode, because a fixed
            // item's size is not contested — which makes the claim set the *minimum* one, smaller
            // than §7.3's sketch (it claims every child). Smaller claim sets also mean fewer
            // chances of an overlap conflict with a nested container.
            val mainSize: Computation? = when {
                flexSlot == null -> checkNotNull(fixedSize)
                governedRow -> null
                else -> Computation {
                    val total = read(totalFlex!!)
                    // Nothing to divide, and no NaN either: a node holding NaN would compare
                    // unequal to itself under IEEE rules and re-dirty the graph forever, which
                    // the graph's `Object.is` semantics prevent but which is still not a value
                    // worth propagating.
                    if (total <= 0.0) 0.0 else read(freeSpace!!) * read(flexSlot) / total
                }
            }

            val main: AxisBuilder.() -> Unit = {
                origin {
                    val p = prevOf(self)
                    if (p == null) originOf(container.axis(direction))
                    else endOf(p.axis(direction))
                }
                if (mainSize != null) {
                    size(mainSize)
                } else {
                    governed += FlexItem(
                        element = self,
                        flexSlot = flexSlot!!,
                        sizeSlot = claimable(Property.SIZE),
                        minSlot = min?.let { tree.graph.derive("${self.path}.min", it) },
                        maxSlot = max?.let { tree.graph.derive("${self.path}.max", it) },
                    )
                }
            }

            // The main axis first, so the item's own block can only supply the cross one — and
            // gets a clear "already has an axis" error rather than a silent override otherwise.
            when (direction) {
                Direction.HORIZONTAL -> h(build = main)
                Direction.VERTICAL -> v(build = main)
            }
            build()
        }
    }
}
