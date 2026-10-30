package com.dubulduke.utils

import kotlin.jvm.JvmField

import com.dubulduke.dsl.Ambient
import com.dubulduke.dsl.DukeDsl
import com.dubulduke.dsl.ElementScope
import com.dubulduke.dsl.Layout
import com.dubulduke.dsl.LayoutExpression
import com.dubulduke.dsl.LayoutScope
import com.dubulduke.dsl.buildChild
import com.dubulduke.layout.Element
import com.dubulduke.layout.Marker

/**
 * A CSS-grid-shaped container: named tracks on both axes, and children placed into cells.
 *
 * ```kotlin
 * grid(
 *     columns = tracks(track(48.0), fr(1.0), fr(2.0)),
 *     rows = tracks(3, track(40.0)),
 *     gap = 8.0,
 * ) {
 *     cell(column = 0, row = 0) { style { colour = ICON } }
 *     cell(column = 1, row = 0, columnSpan = 2) { label("title", font) }
 *     item { label("one", font) }                      // auto-placed, row-major
 *     item(rowSpan = 2, align = Placement.CENTER) { … }
 * }
 * ```
 *
 * ### Why this one needs no machinery at all
 *
 * Flex was hard because the contested quantity — the pool the growing items share — could only be
 * computed from the items themselves, which is `layout-design.md` §6.2's whole subject. A grid has
 * no such quantity. **A track's size depends on the container and on the track list, and on nothing
 * any child does**, so every cell boundary is a pure function of `parent.width` and a list fixed at
 * composition. No hoisted node, no governor, no claim on anyone's slot: a cell is two ordinary slot
 * expressions per axis, which is why this file contains arithmetic and no graph plumbing.
 *
 * That is also the reason `fr` behaves predictably here where CSS's does not always: there is no
 * content-sized track to interact with.
 *
 * ### What is deliberately missing
 *
 * `auto` tracks (sized to their content), `minmax`, named lines and areas, dense packing, and
 * subgrid. Each one needs the track sizes to depend on the children, which is the step that turns
 * this into the flex problem — a real cycle needing a real governor. The simplification is the
 * point; reach for [flexRow] when a track has to measure its contents.
 *
 * An item placed outside the declared tracks is a **composition-time** error rather than an
 * implicit track, for the same reason: an implicit track's size has no principled answer here.
 * When the row count comes from the data, compute it — `tracks((items.size + 2) / 3, track(40.0))`
 * — or use [stackGrid], whose rows are implied by the child count.
 *
 * ### The standing caveat
 *
 * Every cell reads the grid's own size, so a grid that sizes itself from its children —
 * [wrapChildren] — is a genuine cycle rather than a false one, exactly as a flex row inside a
 * shrink-to-fit container is. Give the grid a size that does not depend on its cells.
 */
fun <S : Any> ElementScope<S>.grid(
    columns: List<Track>,
    rows: List<Track>,
    gap: Double = 0.0,
    columnGap: Double = gap,
    rowGap: Double = gap,
    id: String? = null,
    body: GridScope<S>.() -> Unit,
): Element = child(id) {
    require(columns.isNotEmpty()) { "$element is a grid with no columns" }
    require(rows.isNotEmpty()) { "$element is a grid with no rows" }
    GridScope(this, columns, rows, columnGap, rowGap).body()
}

/**
 * Lay *this* element's children out in a grid of [columns], in rows of [rowHeight], filling
 * row-major. The `stackDown`/`stackAcross` half of [grid], for children you did not place by hand.
 *
 * ```kotlin
 * column(id = "palette") {
 *     layout { fill(8.0) }
 *     stackGrid(columns = tracks(4, fr(1.0)), rowHeight = 32.0, columnGap = 6.0, rowGap = 6.0)
 *     forEach(swatches) { swatch -> box { style { colour = swatch.rgb } } }
 * }
 * ```
 *
 * Rows are **implied by the child count** rather than declared, which is what makes this the form
 * to use with `forEach`: there is no row list to keep in step with the data, and no way to overflow
 * the grid. The price is that every row is [rowHeight] tall — a row track list can say more, and
 * [grid] takes one.
 *
 * Call it before adding children: defaults are read when a child is created.
 */
fun <S : Any> ElementScope<S>.stackGrid(
    columns: List<Track>,
    rowHeight: Double,
    columnGap: Double = 0.0,
    rowGap: Double = columnGap,
    justify: Placement = Placement.STRETCH,
    align: Placement = Placement.STRETCH,
) {
    require(columns.isNotEmpty()) { "$element is a grid with no columns" }
    val n = columns.size
    childDefaults = {
        placeAxis(
            horizontal = true, fit = justify,
            start = { trackStart(columns, cellIndex() % n, parent.width, columnGap) },
            end = { trackEnd(columns, cellIndex() % n, parent.width, columnGap) },
        )
        placeAxis(
            horizontal = false, fit = align,
            start = { (cellIndex() / n) * (rowHeight + rowGap) },
            end = { (cellIndex() / n) * (rowHeight + rowGap) + rowHeight },
        )
    }
}

// -------------------------------------------------------------------------------------
// Tracks
// -------------------------------------------------------------------------------------

/**
 * One column or row: an absolute part, a share of the container, and a share of what is left.
 *
 * The three combine rather than being alternatives, so `track(20.0) + fr(1.0)` is CSS's
 * `calc(20px + 1fr)` without needing a `calc` — the arithmetic is the same in every case and the
 * class has no modes to branch on.
 */
class Track internal constructor(
    /** An absolute size in layout units. */
    @JvmField internal val basis: Double,
    /** A share of the container's size along this axis, before gaps. */
    @JvmField internal val fraction: Double,
    /** A share of what is left once the other two and the gaps are taken. */
    @JvmField internal val flex: Double,
) {
    /** Combine two tracks' parts — `track(20.0) + fr(1.0)`. */
    operator fun plus(other: Track): Track =
        Track(basis + other.basis, fraction + other.fraction, flex + other.flex)

    override fun toString(): String = buildString {
        if (basis != 0.0) append(basis)
        if (fraction != 0.0) { if (isNotEmpty()) append(" + "); append("${fraction * 100}%") }
        if (flex != 0.0) { if (isNotEmpty()) append(" + "); append("${flex}fr") }
        if (isEmpty()) append("0")
    }
}

/** A track of a fixed size. */
fun track(size: Double): Track = Track(size, 0.0, 0.0)

/** A track taking [weight] shares of whatever the fixed tracks and gaps leave. CSS's `fr`. */
fun fr(weight: Double = 1.0): Track {
    require(weight > 0.0) { "a flexible track needs a positive weight, got $weight" }
    return Track(0.0, 0.0, weight)
}

/** A track taking a fraction of the grid's whole size along its axis — gaps come out of it. */
fun percent(fraction: Double): Track = Track(0.0, fraction, 0.0)

/** A track list, written out. */
fun tracks(vararg tracks: Track): List<Track> = tracks.asList()

/** [count] copies of one track. CSS's `repeat(count, …)`, under a name that is not `repeat`. */
fun tracks(count: Int, of: Track): List<Track> {
    require(count > 0) { "a grid needs at least one track, got $count" }
    return List(count) { of }
}

/** Where a cell's content sits when it does not fill the cell. CSS's `justify-self`/`align-self`. */
enum class Placement {
    /** Both edges from the cell, so the child fills it. The default, and the one needing no size. */
    STRETCH,

    /** The cell's leading edge — left, or top. The child states its own size. */
    START,

    /** Centred in the cell. The child states its own size. */
    CENTER,

    /** The cell's trailing edge — right, or bottom. The child states its own size. */
    END,
}

// -------------------------------------------------------------------------------------
// The scope
// -------------------------------------------------------------------------------------

/**
 * The grid itself, and its cells.
 *
 * Mirrors [FlexRowScope]: the grid is an ordinary element needing its own position and size, so the
 * self-configuration half of [ElementScope] is here, and only the children are special.
 */
@DukeDsl
class GridScope<S : Any> internal constructor(
    private val container: ElementScope<S>,
    private val columns: List<Track>,
    private val rows: List<Track>,
    private val columnGap: Double,
    private val rowGap: Double,
) {
    /** The grid itself, for reading its geometry. */
    val self: Layout get() = container.self

    val tree get() = container.tree
    val graph get() = container.graph

    /** Position and size **the grid**. Its cells' placement is the grid's business, not this block's. */
    fun layout(block: LayoutScope.() -> Unit) = container.layout(block)

    /** Style the grid. */
    fun style(block: S.() -> Unit) = container.style(block)

    fun classes(vararg names: String) = container.classes(*names)
    fun kind(kind: String) = container.kind(kind)
    fun interactive() = container.interactive()
    fun onClick(handler: () -> Unit) = container.onClick(handler)
    fun marker(marker: Marker) = container.marker(marker)
    fun hasAncestor(marker: Marker): Boolean = container.hasAncestor(marker)
    fun <T> ambient(key: Ambient<T>): T = container.ambient(key)

    /** How many columns and rows the grid was declared with. */
    val columnCount: Int get() = columns.size
    val rowCount: Int get() = rows.size

    /**
     * Which cells are taken, so [item] can find a free one and a double-booking can be named.
     *
     * Occupancy is composition-time bookkeeping, not layout: two children may legitimately overlap
     * if the author places them so, and this only decides where an *auto-placed* item goes.
     */
    private val occupied = BooleanArray(columns.size * rows.size)
    private var cursor = 0

    /**
     * A child in the cell at [column], [row], optionally spanning further.
     *
     * The placement is stamped as a **container default**, so it composes the way a column's does:
     * a child stating its own `height` keeps the cell's horizontal placement and its top, and the
     * grid's now-third vertical assignment is the one dropped. That is `justify-self` and
     * `align-self` arriving for free — though [justify] and [align] say it more directly, and are
     * the only way to say it when the child would otherwise have no size at all.
     */
    fun cell(
        column: Int,
        row: Int,
        columnSpan: Int = 1,
        rowSpan: Int = 1,
        justify: Placement = Placement.STRETCH,
        align: Placement = Placement.STRETCH,
        id: String? = null,
        body: ElementScope<S>.() -> Unit = {},
    ): Element {
        require(columnSpan >= 1 && rowSpan >= 1) {
            "a cell spans at least one track, got ${columnSpan}×$rowSpan"
        }
        require(column >= 0 && column + columnSpan <= columns.size) {
            "${container.element} has ${columns.size} columns, so a cell at column $column " +
                "spanning $columnSpan does not fit. Declare more columns, or place it inside them."
        }
        require(row >= 0 && row + rowSpan <= rows.size) {
            "${container.element} has ${rows.size} rows, so a cell at row $row spanning $rowSpan " +
                "does not fit. Rows are explicit here — declare the ones you need, or use " +
                "stackGrid, whose rows follow the child count."
        }
        take(column, row, columnSpan, rowSpan)

        // Hoisted: the expressions below run against the *child's* scope, and reading them off the
        // grid's own fields inside a lambda would resolve against the wrong receiver.
        val cols = columns
        val rws = rows
        val cGap = columnGap
        val rGap = rowGap
        val placement: LayoutScope.() -> Unit = {
            placeAxis(
                horizontal = true, fit = justify,
                start = { trackStart(cols, column, parent.width, cGap) },
                end = { trackEnd(cols, column + columnSpan - 1, parent.width, cGap) },
            )
            placeAxis(
                horizontal = false, fit = align,
                start = { trackStart(rws, row, parent.height, rGap) },
                end = { trackEnd(rws, row + rowSpan - 1, parent.height, rGap) },
            )
        }

        val owner = container
        return owner.builder.child(id) { owner.buildChild(this, placement) { body() } }
    }

    /**
     * A child in the next free cell, filling row-major.
     *
     * Sparse packing, as CSS's default flow is: the cursor only moves forwards, so an item that
     * does not fit beside what came before starts further on and leaves a hole rather than
     * backfilling. Dense packing is one of the parts deliberately missing.
     */
    fun item(
        columnSpan: Int = 1,
        rowSpan: Int = 1,
        justify: Placement = Placement.STRETCH,
        align: Placement = Placement.STRETCH,
        id: String? = null,
        body: ElementScope<S>.() -> Unit = {},
    ): Element {
        require(columnSpan in 1..columns.size) {
            "${container.element} has ${columns.size} columns; an item cannot span $columnSpan"
        }
        val at = nextFree(columnSpan, rowSpan)
        check(at >= 0) {
            "${container.element} has no free ${columnSpan}×$rowSpan cell left in its " +
                "${columns.size}×${rows.size} grid. Declare more rows, or place this one with " +
                "cell(column, row)."
        }
        return cell(at % columns.size, at / columns.size, columnSpan, rowSpan, justify, align, id, body)
    }

    /** Empty space in the flow — a cell skipped rather than drawn. */
    fun skip(count: Int = 1) {
        repeat(count) {
            val at = nextFree(1, 1)
            check(at >= 0) { "${container.element} has no cell left to skip" }
            take(at % columns.size, at / columns.size, 1, 1)
        }
    }

    private fun take(column: Int, row: Int, columnSpan: Int, rowSpan: Int) {
        for (r in row until row + rowSpan) {
            for (c in column until column + columnSpan) occupied[r * columns.size + c] = true
        }
        val last = (row + rowSpan - 1) * columns.size + (column + columnSpan - 1)
        if (last >= cursor) cursor = last + 1
    }

    /** The first index at or after the cursor where a [columnSpan]×[rowSpan] block fits, or -1. */
    private fun nextFree(columnSpan: Int, rowSpan: Int): Int {
        for (at in cursor until occupied.size) {
            val c = at % columns.size
            val r = at / columns.size
            if (c + columnSpan > columns.size || r + rowSpan > rows.size) continue
            if (isFree(c, r, columnSpan, rowSpan)) return at
        }
        return -1
    }

    private fun isFree(column: Int, row: Int, columnSpan: Int, rowSpan: Int): Boolean {
        for (r in row until row + rowSpan) {
            for (c in column until column + columnSpan) {
                if (occupied[r * columns.size + c]) return false
            }
        }
        return true
    }
}

// -------------------------------------------------------------------------------------
// The arithmetic
//
// All of it is per-evaluation rather than hoisted into graph nodes, and that is a considered
// choice rather than a shortcut. What a hoisted node buys is *sharing* — one evaluation instead
// of one per reader — and it costs a node per track boundary that nothing disposes when the grid
// goes away. Here the shared quantity is a handful of additions over a list fixed at composition,
// while the dependency each cell takes is the same either way: the grid's size. Sharing arithmetic
// this cheap is not worth owning nodes for. (Flex is the opposite case, and hoists.)
// -------------------------------------------------------------------------------------

/**
 * The size of one flexible share: what is left of [available] once the fixed parts, the
 * proportional parts and the gaps are taken.
 *
 * Floored at zero, as CSS floors a flex fraction: tracks that already overflow the grid should push
 * the flexible ones to nothing rather than to a negative size, which would place later tracks
 * *before* earlier ones and turn an overflowing grid into an unreadable one.
 */
private fun flexUnit(tracks: List<Track>, available: Double, gap: Double): Double {
    var used = gap * (tracks.size - 1)
    var flex = 0.0
    for (i in tracks.indices) {
        val t = tracks[i]
        used += t.basis + t.fraction * available
        flex += t.flex
    }
    if (flex <= 0.0) return 0.0
    val free = available - used
    return if (free > 0.0) free / flex else 0.0
}

private fun trackSize(t: Track, available: Double, unit: Double): Double =
    t.basis + t.fraction * available + t.flex * unit

/** The offset of track [i]'s leading edge from the grid's own leading edge. */
private fun trackStart(tracks: List<Track>, i: Int, available: Double, gap: Double): Double {
    val unit = flexUnit(tracks, available, gap)
    var x = 0.0
    for (j in 0 until i) x += trackSize(tracks[j], available, unit) + gap
    return x
}

/** The offset of track [i]'s trailing edge. Recomputes [trackStart]; both are O(tracks). */
private fun trackEnd(tracks: List<Track>, i: Int, available: Double, gap: Double): Double {
    val unit = flexUnit(tracks, available, gap)
    var x = 0.0
    for (j in 0 until i) x += trackSize(tracks[j], available, unit) + gap
    return x + trackSize(tracks[i], available, unit)
}

/**
 * Write one axis of a cell: [start] and [end] are offsets from the grid's leading edge, and [fit]
 * decides which of them the child is actually pinned to.
 *
 * Both are [LayoutExpression] rather than `LayoutScope.() -> Double` for §2.2's reason: a Kotlin
 * function type's erased return **boxes a `java.lang.Double` on every evaluation**, and a grid
 * evaluates these four times per cell.
 */
private fun LayoutScope.placeAxis(
    horizontal: Boolean,
    fit: Placement,
    start: LayoutExpression,
    end: LayoutExpression,
) {
    if (horizontal) {
        when (fit) {
            Placement.STRETCH -> {
                left { parent.left + with(start) { evaluate() } }
                right { parent.left + with(end) { evaluate() } }
            }
            Placement.START -> left { parent.left + with(start) { evaluate() } }
            Placement.END -> right { parent.left + with(end) { evaluate() } }
            Placement.CENTER -> centerX {
                parent.left + (with(start) { evaluate() } + with(end) { evaluate() }) / 2.0
            }
        }
    } else {
        when (fit) {
            Placement.STRETCH -> {
                top { parent.top + with(start) { evaluate() } }
                bottom { parent.top + with(end) { evaluate() } }
            }
            Placement.START -> top { parent.top + with(start) { evaluate() } }
            Placement.END -> bottom { parent.top + with(end) { evaluate() } }
            Placement.CENTER -> centerY {
                parent.top + (with(start) { evaluate() } + with(end) { evaluate() }) / 2.0
            }
        }
    }
}

/**
 * This element's position among its siblings, read so that it stays correct.
 *
 * `index` is a plain field on the element, refreshed by the tree immediately before it republishes
 * the child list. Reading it alone records **no dependency**, so a cell computed from it would be
 * right until a sibling was inserted and then silently wrong — the geometry fresh and the cell
 * stale, which is the worse kind. Reading `siblings` first takes the edge on the published list,
 * and any insertion, removal or reorder invalidates it.
 */
private fun LayoutScope.cellIndex(): Int {
    @Suppress("UNUSED_EXPRESSION")
    siblings
    return index
}
