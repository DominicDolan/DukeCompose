package com.dubulduke.dsl

import com.dubulduke.layout.Axis
import com.dubulduke.layout.AxisBuilder
import com.dubulduke.layout.Content
import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.Property
import com.dubulduke.layout.bottomOf
import com.dubulduke.layout.centerOf
import com.dubulduke.layout.centerXOf
import com.dubulduke.layout.centerYOf
import com.dubulduke.layout.childrenOf
import com.dubulduke.layout.contentHeight
import com.dubulduke.layout.countOf
import com.dubulduke.layout.endOf
import com.dubulduke.layout.heightAt
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.intrinsicWidthOf
import com.dubulduke.layout.isHovered
import com.dubulduke.layout.isPressed
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.nextOf
import com.dubulduke.layout.originOf
import com.dubulduke.layout.prevOf
import com.dubulduke.layout.rightOf
import com.dubulduke.layout.sizeOf
import com.dubulduke.layout.topOf
import com.dubulduke.layout.widthOf
import com.dubulduke.reactivity.Computation
import com.dubulduke.reactivity.Graph

/** `dsl-and-engine.md` §2.5. Mandatory — without it a nested block can capture the wrong receiver. */
@DslMarker
annotation class DukeDsl

/**
 * A reference to some element's resolved geometry.
 *
 * A value class over [Element], so `parent.left` allocates nothing. The reads live on
 * [LayoutScope] as member extensions rather than on this type, because they need the graph to
 * read from and the tracking context to record an edge into — which is also what makes
 * `dsl-and-engine.md` §2.6's pooled-flyweight problem disappear rather than be solved.
 */
@JvmInline
value class Layout(val element: Element)

/** An indexable set of elements — `siblings`, `children`. */
@JvmInline
value class LayoutList(private val elements: List<Element>) {
    val size: Int get() = elements.size
    val isEmpty: Boolean get() = elements.isEmpty()
    operator fun get(i: Int): Layout = Layout(elements[i])
    val first: Layout get() = Layout(elements.first())
    val last: Layout get() = Layout(elements.last())
    fun getOrNull(i: Int): Layout? = elements.getOrNull(i)?.let { Layout(it) }

    inline fun sumOf(f: (Layout) -> Double): Double {
        var total = 0.0
        for (i in 0 until size) total += f(get(i))
        return total
    }

    inline fun maxOf(f: (Layout) -> Double): Double {
        var best = Double.NEGATIVE_INFINITY
        for (i in 0 until size) {
            val v = f(get(i))
            if (v > best) best = v
        }
        return if (best == Double.NEGATIVE_INFINITY) 0.0 else best
    }
}

/**
 * The body of one slot expression.
 *
 * A hand-written `fun interface` rather than `LayoutScope.() -> Double`, for the same reason
 * `:reactivity`'s `Computation` is: a Kotlin function type compiles to `Function1<LayoutScope,
 * Double>`, whose erased `Object` return boxes a `java.lang.Double` on **every recompute**. The
 * failure mode is silent and shows up only as GC pressure under load.
 */
fun interface LayoutExpression {
    fun LayoutScope.evaluate(): Double
}

/** The eight names a layout block can assign. Two per axis, out of four, is the whole rule. */
enum class Edge(internal val horizontal: Boolean, internal val property: Property) {
    LEFT(true, Property.ORIGIN),
    RIGHT(true, Property.END),
    WIDTH(true, Property.SIZE),
    CENTER_X(true, Property.CENTER),
    TOP(false, Property.ORIGIN),
    BOTTOM(false, Property.END),
    HEIGHT(false, Property.SIZE),
    CENTER_Y(false, Property.CENTER),
}

/**
 * Writes an element's geometry, and reads everything else's. `dsl-and-engine.md` §2.5.
 *
 * ### One flat scope, on purpose
 *
 * §2.5 works out why this is a single interface rather than a nest of builders: `@DslMarker`
 * blocks legitimate access to outer receivers as well as illegitimate access, and expressions
 * routinely need context — `query`, `content`, `index` — that would otherwise live outside. Flat
 * is not merely nicer to read; it is what makes the marker free.
 *
 * ### Assignments are buffered, not applied
 *
 * A block records expressions into a slot table and commits at the end of the element. That is
 * what makes container defaults work at all (§2.4): the column stamps `left`, `right` and `top`,
 * the child overrides `top`, and **last write wins within a single slot** with no strength system
 * and no ambiguity. Committing eagerly would instead hit `:layout`'s already-assigned error, since
 * that layer deliberately refuses to let a property be written twice.
 *
 * The 2-degrees-of-freedom rule is therefore checked here, after defaults have been folded in, and
 * the error names all the conflicting edges at once rather than whichever happened to arrive third.
 */
@DukeDsl
class LayoutScope internal constructor(
    @JvmField val graph: Graph,
    @JvmField val tree: ElementTree,
    /** The element being laid out. Available because §2.5 requires it — see the flat-scope note. */
    @JvmField val element: Element,
) {
    private val slots = arrayOfNulls<LayoutExpression>(8)

    /**
     * Whether each slot came from the container rather than the element's own block, and in what
     * order the container declared it.
     *
     * This is the whole of the precedence system, and two levels is all it needs: **the author
     * outranks the container.** Without it, a `row` that stamps `top` and `bottom` on its children
     * collides with a child that states its own `height` — three assignments, of which only one was
     * actually written by the person laying the screen out.
     */
    private val fromDefault = BooleanArray(8)
    private val defaultOrder = IntArray(8)
    private var defaultsApplying = false
    private var defaultSeq = 0

    /** Whether a container stamped anything at all. Only used to make an error more specific. */
    private var anyDefaults = false

    // ---------------------------------------------------------------------------
    // Write
    // ---------------------------------------------------------------------------

    fun left(e: LayoutExpression) = set(Edge.LEFT, e)
    fun right(e: LayoutExpression) = set(Edge.RIGHT, e)
    fun width(e: LayoutExpression) = set(Edge.WIDTH, e)
    fun centerX(e: LayoutExpression) = set(Edge.CENTER_X, e)
    fun top(e: LayoutExpression) = set(Edge.TOP, e)
    fun bottom(e: LayoutExpression) = set(Edge.BOTTOM, e)
    fun height(e: LayoutExpression) = set(Edge.HEIGHT, e)
    fun centerY(e: LayoutExpression) = set(Edge.CENTER_Y, e)

    /**
     * An axis a container mechanism owns outright — a flex row's main axis, where both the position
     * and the size are the row's to decide (§3.3).
     *
     * This is a third precedence level above the two [fromDefault] describes, and it is a different
     * kind of thing: not "the container's wish yields to the author's" but "the author has no say
     * here at all". A flex item that could also state its own width would be writing a slot the row
     * already writes, which `:layout` rejects with a message about axes rather than about flex.
     */
    private var reservedHorizontal = false
    private var reservedVertical = false

    /**
     * Axes whose *size* alone belongs to something else — a shrink-to-fit flex row, whose main
     * size its own governor computes while its origin stays the author's to write.
     *
     * A narrower reservation than [reserve], and needed because the two cases genuinely differ: a
     * flex *item* surrenders both position and size to its row, while a shrink-to-fit *row*
     * surrenders only its size. Without the distinction the row could not be positioned at all.
     */
    private var sizeReservedHorizontal = false
    private var sizeReservedVertical = false

    internal fun reserve(horizontal: Boolean) {
        if (horizontal) reservedHorizontal = true else reservedVertical = true
    }

    /**
     * Write a size the author is forbidden to write — for whatever made the reservation.
     *
     * Public alongside [reserveSize] because the mechanism that makes a reservation is not
     * necessarily in this module: `:utils`'s shrink-to-fit flex row reserves its own main-axis
     * size, then writes it from the solve's output. Saying `setReserved` at the call site is the
     * point — it marks the one assignment the reservation is *not* guarding against.
     */
    fun setReservedSize(horizontal: Boolean, e: LayoutExpression) {
        if (horizontal) sizeReservedHorizontal = false else sizeReservedVertical = false
        set(if (horizontal) Edge.WIDTH else Edge.HEIGHT, e)
        if (horizontal) sizeReservedHorizontal = true else sizeReservedVertical = true
    }

    /**
     * Claim this element's size along one axis for a container mechanism, leaving its position to
     * the author. Writing that size afterwards is an error naming the mechanism rather than
     * `:layout`'s lower-level complaint about an axis that already has one.
     */
    fun reserveSize(horizontal: Boolean) {
        if (horizontal) sizeReservedHorizontal = true else sizeReservedVertical = true
    }

    private fun isSizeReserved(edge: Edge): Boolean =
        edge.property == Property.SIZE &&
            (if (edge.horizontal) sizeReservedHorizontal else sizeReservedVertical)

    internal fun isReserved(horizontal: Boolean): Boolean =
        if (horizontal) reservedHorizontal else reservedVertical

    private fun set(edge: Edge, e: LayoutExpression) {
        if (isSizeReserved(edge) && !defaultsApplying) {
            val axisName = if (edge.horizontal) "horizontal" else "vertical"
            throw IllegalStateException(
                "$element cannot set ${edge.name.lowercase()}: it is a shrink-to-fit flex row, so " +
                    "its $axisName size is what the row computes from its items and the bound you " +
                    "gave it. Position it with ${if (edge.horizontal) "left/right/centerX" else "top/bottom/centerY"}, " +
                    "or drop shrinkToFit if you meant to state the size yourself."
            )
        }
        if (isReserved(edge.horizontal)) {
            // A default landing on a reserved axis is dropped rather than fatal, for the same reason
            // defaults yield generally: a container stamping `left` on its children should not stop
            // one of them being a flex item. An *explicit* write is a mistake worth naming.
            if (defaultsApplying) return
            val axisName = if (edge.horizontal) "horizontal" else "vertical"
            throw IllegalStateException(
                "$element cannot set ${edge.name.lowercase()}: its $axisName axis is owned by the " +
                    "flex row containing it, which decides both position and size along that axis. " +
                    "Set the item's flex factor or fixed size on the row instead, and use this " +
                    "block for the cross axis."
            )
        }
        slots[edge.ordinal] = e
        fromDefault[edge.ordinal] = defaultsApplying
        if (defaultsApplying) {
            defaultOrder[edge.ordinal] = defaultSeq++
            anyDefaults = true
        }
    }

    /** Run the container's block, tagging everything it writes as yielding to the element's own. */
    internal fun applyDefaults(block: LayoutScope.() -> Unit) {
        defaultsApplying = true
        try {
            block()
        } finally {
            defaultsApplying = false
        }
    }

    internal fun isSet(edge: Edge) = slots[edge.ordinal] != null

    // ---------------------------------------------------------------------------
    // Read: this element's own geometry
    //
    // Reading a slot nothing determines is a self-cycle, which the evaluator detects and reports
    // (§2.5). Making self-reads syntactically ordinary is the readability win; the diagnostics
    // carry the weight that `self.` prefixing would have.
    // ---------------------------------------------------------------------------

    /** This element, as a [Layout] — for the reads that take one, such as `self.contentHeight`. */
    val self: Layout get() = Layout(element)

    val left: Double get() = graph.leftOf(element)
    val right: Double get() = graph.rightOf(element)
    val top: Double get() = graph.topOf(element)
    val bottom: Double get() = graph.bottomOf(element)
    val width: Double get() = graph.widthOf(element)
    val height: Double get() = graph.heightOf(element)
    val centerX: Double get() = graph.centerXOf(element)
    val centerY: Double get() = graph.centerYOf(element)

    // ---------------------------------------------------------------------------
    // Read: everything else. Member extensions, so `parent.left` allocates nothing.
    // ---------------------------------------------------------------------------

    val Layout.left: Double get() = graph.leftOf(element)
    val Layout.right: Double get() = graph.rightOf(element)
    val Layout.top: Double get() = graph.topOf(element)
    val Layout.bottom: Double get() = graph.bottomOf(element)
    val Layout.width: Double get() = graph.widthOf(element)
    val Layout.height: Double get() = graph.heightOf(element)
    val Layout.centerX: Double get() = graph.centerXOf(element)
    val Layout.centerY: Double get() = graph.centerYOf(element)

    /** Intrinsic width of that element's own text, unwrapped. */
    val Layout.contentWidth: Double get() = graph.intrinsicWidthOf(element)

    /** Height of that element's own text at its own current width. */
    val Layout.contentHeight: Double get() = graph.contentHeight(element)

    val Layout.isHovered: Boolean get() = graph.isHovered(element)
    val Layout.isPressed: Boolean get() = graph.isPressed(element)

    // ---------------------------------------------------------------------------
    // Navigation. All of these read the child-list node, so an expression that navigates the
    // tree depends on the tree's *shape* and not only on the values found there.
    // ---------------------------------------------------------------------------

    /** This element's parent. Chains: `parent.parent`. Reading it on the root is an error. */
    val parent: Layout
        get() = Layout(checkNotNull(element.parent) { "$element is the root and has no parent" })

    /** The preceding sibling, or `null` at index 0. */
    val prev: Layout? get() = graph.prevOf(element)?.let { Layout(it) }

    /** The following sibling, or `null` at the last index. */
    val next: Layout? get() = graph.nextOf(element)?.let { Layout(it) }

    /**
     * The preceding sibling if there is one, otherwise the parent.
     *
     * §2.5 asks for this by name: `prev` and `next` are nullable rather than degenerate, which is
     * more honest than iteration A's zero-size pinned sibling plus an `isFirst` flag, at the cost
     * of a `?:` in every chaining utility. This is that cost paid once.
     */
    val prevOrParent: Layout get() = prev ?: parent

    val siblings: LayoutList get() = LayoutList(graph.childrenOf(element.parent ?: element))
    val children: LayoutList get() = LayoutList(graph.childrenOf(element))

    val index: Int get() = element.index
    val count: Int get() = graph.countOf(element)

    /** This element's own text content, or `null`. */
    val content: Content? get() = element.content

    /** Height this element's own text needs at a given width. */
    fun contentHeightAt(maxWidth: Double): Double =
        graph.heightAt(checkNotNull(element.content) { "$element has no text" }, maxWidth)

    /** Resolve an id. A plain lookup — composition is complete before any expression runs. */
    fun query(id: String): Layout = Layout(tree.query(id))

    // ---------------------------------------------------------------------------
    // Wiring into :layout
    // ---------------------------------------------------------------------------

    /**
     * Hand the surviving pair per axis to `:layout`.
     *
     * Container defaults were applied when this scope was created, *before* the element's own
     * `layout { }` block ran, so the element overriding one is just the ordinary last-write-wins
     * on that one slot. No merge step, no strength system, and nothing to get subtly wrong —
     * which is the whole reason assignments are buffered rather than committed eagerly.
     */
    internal fun commitAxis(horizontal: Boolean, axis: AxisBuilder) {
        val present = ArrayList<Edge>(4)
        for (edge in EDGES) {
            if (edge.horizontal == horizontal && slots[edge.ordinal] != null) present += edge
        }

        // The author outranks the container. A `row` filling its children's height stamps `top`
        // and `bottom`; a child stating its own `height` needs one of them to yield, and which one
        // is not a matter of taste — dropping the *later-declared* default keeps the earlier,
        // stronger intent, so `top`+`bottom` yields `top` and the child aligns to the top of the
        // row rather than the bottom. Deterministic, and it matches what the container was mostly
        // trying to say.
        if (present.size > 2) {
            val droppable = present.filter { fromDefault[it.ordinal] }
                .sortedByDescending { defaultOrder[it.ordinal] }
            for (d in droppable) {
                if (present.size <= 2) break
                present -= d
                slots[d.ordinal] = null
            }
        }

        val axisName = if (horizontal) "horizontal" else "vertical"
        if (present.size > 2) {
            // Everything left is explicit, so there is no principled winner. `layout-design.md`
            // §2.1: DukeUI resolved this silently by last-two-writes-win, and turning that into a
            // build error is one of the two behaviours this design set out to fix.
            throw IllegalStateException(
                "$element over-constrains its $axisName axis: " +
                    present.joinToString(", ") { it.name.lowercase() } +
                    " are all set explicitly, but an axis has two degrees of freedom. Container " +
                    "defaults yield to an element's own block automatically; these do not, because " +
                    "nothing distinguishes them. Remove one."
            )
        }
        if (present.size < 2) {
            val set = if (present.isEmpty()) "nothing is" else "only ${present[0].name.lowercase()} is"
            // Naming the absence of defaults is worth the branch: the commonest cause of this is
            // an element whose parent was never made a container — `forEach` items and the root
            // are the two that have no `column`/`row` call to do it for them — and the difference
            // between "I forgot a slot" and "my parent stamps nothing" is not otherwise visible.
            val hint = if (anyDefaults) {
                ""
            } else {
                " No container defaults reached this element, so every slot has to come from its " +
                    "own block; if its parent was meant to lay it out, the parent needs " +
                    "stackDown/stackAcross (or to be a column/row)."
            }
            throw IllegalStateException(
                "$element under-constrains its $axisName axis: $set set, and an axis needs two of " +
                    "${if (horizontal) "left/right/width/centerX" else "top/bottom/height/centerY"}. " +
                    "A default of zero would place it somewhere plausible and wrong.$hint"
            )
        }
        write(axis, present[0])
        write(axis, present[1])
    }

    private fun write(axis: AxisBuilder, edge: Edge) {
        val body = slots[edge.ordinal]!!
        val computation = Computation { with(body) { evaluate() } }
        when (edge.property) {
            Property.ORIGIN -> axis.origin(computation)
            Property.END -> axis.end(computation)
            Property.SIZE -> axis.size(computation)
            Property.CENTER -> axis.center(computation)
        }
    }

    private companion object {
        val EDGES = Edge.entries.toTypedArray()
    }
}

/** Read an axis property directly, for utilities that work in `:layout` terms. */
fun LayoutScope.originOf(axis: Axis): Double = graph.originOf(axis)

/** @suppress internal plumbing */
internal fun LayoutScope.sizeOfAxis(axis: Axis): Double = graph.sizeOf(axis)

/** @suppress internal plumbing */
internal fun LayoutScope.endOfAxis(axis: Axis): Double = graph.endOf(axis)

/** @suppress internal plumbing */
internal fun LayoutScope.centerOfAxis(axis: Axis): Double = graph.centerOf(axis)
