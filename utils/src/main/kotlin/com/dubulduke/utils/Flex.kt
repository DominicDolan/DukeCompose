package com.dubulduke.utils

import com.dubulduke.dsl.Ambient
import com.dubulduke.dsl.DukeDsl
import com.dubulduke.dsl.ElementScope
import com.dubulduke.dsl.Layout
import com.dubulduke.dsl.LayoutExpression
import com.dubulduke.dsl.LayoutScope
import com.dubulduke.dsl.buildChild
import com.dubulduke.layout.Direction
import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementBuilder
import com.dubulduke.layout.Marker
import com.dubulduke.reactivity.Computation
import com.dubulduke.layout.FlexScope as LayoutFlexScope
import com.dubulduke.layout.flexRow as layoutFlexRow

/**
 * A row (or column) that distributes its leftover space among its growing children.
 * `dsl-and-engine.md` §3.3.
 *
 * ```kotlin
 * flexRow(gap = 8.0) {
 *     fixed({ 48.0 }) { style { colour = ICON } }     // an icon, its own width
 *     grow(1.0)       { label("name", font) }         // one share of what is left
 *     grow(2.0)       { label("detail", font) }       // two shares
 *     fixed({ 60.0 }) { style { colour = BADGE } }
 * }
 * ```
 *
 * `:layout` already resolves the row — [com.dubulduke.layout.flexRow] — and the mechanism there is
 * the design's central claim (`layout-design.md` §6.2): the contested quantity is hoisted into a
 * node the *container* owns, so growing children read the pool rather than each other, and what
 * looks like a strongly connected component collapses into a plain DAG. This is only the authoring
 * surface over it.
 *
 * It lives in `:utils` rather than `:dsl` for the reason §3.3 gives for containers generally: it is
 * not privileged. Everything it needs from `:dsl` — [buildChild], [LayoutScope.reserveSize] — is
 * public, so an application's own container mechanism is written exactly the same way, against the
 * same seam, with no access this one has and it does not.
 *
 * ### An item's main axis is not writable
 *
 * Both the position and the size of an item along the main axis belong to the row — position chains
 * off the previous item, size is a share of the pool. So an item's `layout { }` block supplies the
 * **cross** axis only, and writing `width` inside a horizontal row's item is an error naming the
 * row rather than `:layout`'s lower-level complaint about an axis that already has a size.
 *
 * That is a third precedence level above [LayoutScope]'s two, and a different kind: not "the
 * container's wish yields to the author's" but "the author has no say here". Container defaults
 * aimed at a reserved axis are dropped silently, so a `flexRow` nests inside a `column` without the
 * column's `left`/`right` stamping fighting the row.
 *
 * ### Caveats worth knowing before reaching for this
 *
 * [gap] is charged to the pool as fixed-size spacer *elements*, not as an offset. The row owns each
 * item's main-axis origin and chains it off the previous item's end, so there is nowhere else for a
 * gap to live.
 *
 * [clamped] switches to the governed solve, which `min`/`max` on an item require. It is an explicit
 * argument rather than something inferred from whether any item declared a clamp, because a
 * governed item's size slot has to be created as a claimable input *while the item is being built*,
 * and whether any clamp exists is not known until every item has been.
 *
 * And the standing one: the pool reads the container's own main-axis size, so a flex row inside a
 * **shrink-to-fit** container — one whose size comes from these very children — is a real cycle
 * rather than a false one. Give the row a main-axis size that does not depend on its items.
 */
fun <S : Any> ElementScope<S>.flexRow(
    direction: Direction = Direction.HORIZONTAL,
    gap: Double = 0.0,
    clamped: Boolean = false,
    /**
     * An outer bound on the row's main size, which makes the row **shrink to fit**.
     *
     * Supplying it flips the direction of the whole solve: the row becomes as large as its items
     * want, up to this, and past it they compress — so its main-axis size is written for you and
     * `width { }` on the row is an error. Items are declared with [FlexRowScope.shrinkable]; `grow`
     * has no meaning, since there is no leftover pool to take a share of.
     */
    shrinkToFit: LayoutExpression? = null,
    id: String? = null,
    body: FlexRowScope<S>.() -> Unit,
): Element = child(id) {
    // Hoisted because @DukeDsl — correctly — refuses to resolve an ElementScope member against the
    // ElementBuilder receiver inside `layoutFlexRow`.
    val container = this
    val scope = container.layoutScope()
    val bound = shrinkToFit?.let { e -> Computation { with(e) { scope.evaluate() } } }

    // Before the body, so an author writing `width { }` on the row gets the message rather than a
    // silent override — the size belongs to the solve from here on.
    if (bound != null) scope.reserveSize(direction == Direction.HORIZONTAL)

    val row = builder.layoutFlexRow(direction, clamped, bound) {
        FlexRowScope(container, this, direction, gap).body()
    }

    // The row's own main size is the solve's output, so it is written here rather than left to the
    // author — and the axis is reserved so that writing it too is a named error rather than a
    // silent second expression on a slot the governor already owns.
    if (bound != null) {
        val content = row.contentSize
        // Written past the reservation, which is what the reservation is guarding *against* the
        // author doing. `setReserved` says so at the call site rather than leaving a reader to
        // wonder why this one assignment is allowed.
        scope.setReservedSize(direction == Direction.HORIZONTAL) { graph.read(content) }
    }
}

/** A vertical [flexRow]. Present because `flexRow(Direction.VERTICAL)` reads badly. */
fun <S : Any> ElementScope<S>.flexColumn(
    gap: Double = 0.0,
    clamped: Boolean = false,
    shrinkToFit: LayoutExpression? = null,
    id: String? = null,
    body: FlexRowScope<S>.() -> Unit,
): Element = flexRow(Direction.VERTICAL, gap, clamped, shrinkToFit, id, body)

/**
 * The row itself, and its items.
 *
 * The row is an ordinary element — it needs its own position and size like any other — so the
 * self-configuration half of [ElementScope] is here too, and a row reads the same as a `column`:
 *
 * ```kotlin
 * flexRow(gap = 8.0) {
 *     layout { fillWidth(12.0); height { 40.0 } }
 *     style { colour = PANEL }
 *
 *     fixed({ 48.0 })
 *     grow(1.0) { label("name", font) }
 * }
 * ```
 *
 * Only the *items* are special, and only along the main axis.
 */
@DukeDsl
class FlexRowScope<S : Any> internal constructor(
    private val container: ElementScope<S>,
    private val items: LayoutFlexScope,
    private val direction: Direction,
    private val gap: Double,
) {
    /** The row itself, for reading its geometry. */
    val self: Layout get() = container.self

    val tree get() = container.tree
    val graph get() = container.graph

    /** Position and size **the row**. Its items' main axis is the row's business, not this block's. */
    fun layout(block: LayoutScope.() -> Unit) = container.layout(block)

    /** Style the row. */
    fun style(block: S.() -> Unit) = container.style(block)

    fun classes(vararg names: String) = container.classes(*names)
    fun kind(kind: String) = container.kind(kind)
    fun interactive() = container.interactive()
    fun onClick(handler: () -> Unit) = container.onClick(handler)

    /**
     * How many items have been added, so [gapBefore] knows not to put a gap before the first.
     *
     * Spacers are deliberately **unnamed**. An id is a tree-wide binding for `query`, so numbering
     * them per row — `gap1`, `gap2` — made two gapped rows in one tree a duplicate-id error, which
     * is a real failure produced by nothing more than having two toolbars. Nothing queries a gap,
     * and the element's path already identifies it in a dump.
     */
    private var count = 0

    /**
     * Every item's cross axis, so an item that says nothing still fills the row.
     *
     * The main axis is untouched — it is the row's — so this stamps two of the four slots an
     * ordinary container default would, which is what makes the common item `grow(1.0) { }` with an
     * empty block.
     */
    private val crossDefaults: LayoutScope.() -> Unit = when (direction) {
        Direction.HORIZONTAL -> {
            { top { parent.top }; bottom { parent.bottom } }
        }
        Direction.VERTICAL -> {
            { left { parent.left }; right { parent.right } }
        }
    }


    /** Read an ambient in the row's own context. See [ElementScope.ambient]. */
    fun <T> ambient(key: Ambient<T>): T = container.ambient(key)

    /** Tag the row itself, for descendants' ancestor queries. */
    fun marker(marker: Marker) = container.marker(marker)

    /** Whether any ancestor of the row carries [marker]. */
    fun hasAncestor(marker: Marker): Boolean = container.hasAncestor(marker)

    /** An item at a size of its own choosing. Subtracted from the pool the growing items share. */
    fun fixed(
        size: LayoutExpression,
        id: String? = null,
        body: ElementScope<S>.() -> Unit = {},
    ): Element {
        gapBefore()
        val item = Item()
        return items.fixed(item.mainSize(size), id, item.build(body))
    }

    /**
     * An item taking `flex / totalFlex` of the free space.
     *
     * [min] and [max] need `flexRow(clamped = true)`; a clamp is what makes the row iterative and so
     * what makes the governor necessary. They are expressions rather than constants, so a clamp can
     * change without recomposition and re-solves the row like any other dependency.
     */
    fun grow(
        flex: Double = 1.0,
        id: String? = null,
        min: LayoutExpression? = null,
        max: LayoutExpression? = null,
        body: ElementScope<S>.() -> Unit = {},
    ): Element {
        gapBefore()
        val item = Item()
        return items.grow(flex, id, min?.let { item.mainSize(it) }, max?.let { item.mainSize(it) }, item.build(body))
    }

    /**
     * An item at its preferred size, which it gives up under pressure. Needs `shrinkToFit`.
     *
     * [basis] is what it would like to be. [shrink] is how willingly it yields — weighted by the
     * basis, as the flexbox spec does, so a wide item and a narrow one with equal factors do not
     * lose the same *absolute* amount. `shrink = 0.0` makes it incompressible; [min] is a floor it
     * freezes at, its share of the overflow passing to whatever can still give.
     */
    fun shrinkable(
        basis: LayoutExpression,
        shrink: Double = 1.0,
        id: String? = null,
        min: LayoutExpression? = null,
        body: ElementScope<S>.() -> Unit = {},
    ): Element {
        gapBefore()
        val item = Item()
        return items.shrinkable(
            item.mainSize(basis), shrink, id, min?.let { item.mainSize(it) }, item.build(body),
        )
    }

    /** Explicit empty space along the main axis, for what [gap] does not cover. */
    fun space(size: LayoutExpression): Element = fixed(size)

    private fun gapBefore() {
        if (gap > 0.0 && count > 0) {
            val g = gap
            val spacer = Item()
            items.fixed(spacer.mainSize { g }, id = null, build = spacer.build {})
        }
        count++
    }

    /**
     * One item under construction.
     *
     * `:layout` needs the main-axis size as a `Computation` *before* the child element exists, but a
     * [LayoutExpression] can only be evaluated against a [LayoutScope], which needs the element.
     * Holding the scope in a field closes the loop: nothing evaluates until layout runs, by which
     * point [build]'s block has filled it in.
     */
    private inner class Item {
        private var scope: LayoutScope? = null

        fun mainSize(e: LayoutExpression): Computation = Computation {
            val s = checkNotNull(scope) { "a flex item's size was evaluated before it was built" }
            with(e) { s.evaluate() }
        }

        fun build(body: ElementScope<S>.() -> Unit): ElementBuilder.() -> Unit = {
            scope = container.buildChild(this, crossDefaults, direction) { body() }
        }
    }
}
