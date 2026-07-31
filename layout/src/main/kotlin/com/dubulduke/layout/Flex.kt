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
    build: FlexScope.() -> Unit,
): FlexRow {
    val g = tree.graph
    val container = element

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

/** One growing item of a governed row: what its solve needs, captured at composition. */
internal class FlexItem(
    @JvmField val element: Element,
    // No @JvmField on these four: a value-class-typed property cannot have one.
    val flexSlot: Slot,
    val sizeSlot: Slot,
    val minSlot: Slot?,
    val maxSlot: Slot?,
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
        val freeSpace = if (governedRow) null else row.freeSpace
        val totalFlex = if (governedRow) null else row.totalFlex
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
