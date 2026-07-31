package com.dubulduke.dsl

import com.dubulduke.layout.Direction
import com.dubulduke.layout.Element
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
    id: String? = null,
    body: FlexRowScope<S>.() -> Unit,
): Element = child(id) {
    // Hoisted because @DukeDsl — correctly — refuses to resolve an ElementScope member against the
    // ElementBuilder receiver inside `layoutFlexRow`.
    val container = this
    builder.layoutFlexRow(direction, clamped) {
        FlexRowScope(container, this, direction, gap).body()
    }
}

/** A vertical [flexRow]. Present because `flexRow(Direction.VERTICAL)` reads badly. */
fun <S : Any> ElementScope<S>.flexColumn(
    gap: Double = 0.0,
    clamped: Boolean = false,
    id: String? = null,
    body: FlexRowScope<S>.() -> Unit,
): Element = flexRow(Direction.VERTICAL, gap, clamped, id, body)

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
    private val styles: StyleFactory<S> get() = container.styles

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

    /** Explicit empty space along the main axis, for what [gap] does not cover. */
    fun space(size: LayoutExpression): Element = fixed(size, id = "space$count")

    private fun gapBefore() {
        if (gap > 0.0 && count > 0) {
            val g = gap
            val spacer = Item()
            items.fixed(spacer.mainSize { g }, "gap$count", spacer.build {})
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

        fun build(body: ElementScope<S>.() -> Unit): com.dubulduke.layout.ElementBuilder.() -> Unit = {
            val es = ElementScope(this, styles, crossDefaults, direction)
            scope = es.layoutScope()
            es.body()
            es.finish()
        }
    }
}
