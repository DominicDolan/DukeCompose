package com.dubulduke.layout

import kotlin.jvm.JvmField

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.Slot

/**
 * One of the four properties that describe an interval on an axis. Any two of them determine
 * the other two, which is the whole point: an axis has four names but two degrees of freedom.
 */
enum class Property { ORIGIN, SIZE, END, CENTER }

/**
 * Which two properties an axis was actually given expressions for — the pair that is
 * *written*, from which the other two are derived.
 *
 * An enum rather than a `Pair<Property, Property>` for two reasons: exactly six combinations
 * are valid and this makes the rest unrepresentable, and the derivations below become a
 * single dense `when` that the JIT compiles to a table switch.
 *
 * The old `VaryingDimension` discovered this pair at *runtime* by shifting priorities as
 * properties were assigned. Here it is fixed at composition time, because the pair is simply
 * which two expressions the DSL registered — a strict simplification, and one that lets an
 * over-constrained axis be an error at build time rather than a last-write-wins surprise.
 */
enum class Basis(@JvmField val first: Property, @JvmField val second: Property) {
    ORIGIN_SIZE(Property.ORIGIN, Property.SIZE),
    ORIGIN_END(Property.ORIGIN, Property.END),
    ORIGIN_CENTER(Property.ORIGIN, Property.CENTER),
    SIZE_END(Property.SIZE, Property.END),
    SIZE_CENTER(Property.SIZE, Property.CENTER),
    END_CENTER(Property.END, Property.CENTER),
    ;

    companion object {
        /** The basis for an unordered pair of distinct properties. */
        fun of(p: Property, q: Property): Basis {
            require(p != q) { "an axis needs two different properties, got $p twice" }
            return entries.first { (it.first == p && it.second == q) || (it.first == q && it.second == p) }
        }
    }
}

/**
 * One axis of one element: two graph nodes, and the [basis] that says what they mean.
 *
 * This is the whole of `layout-design.md` §4.1's "a node is a written slot". There are
 * exactly two nodes per axis no matter which two of the four properties were written, and no
 * node exists for the derived properties — reading `end` under an `(ORIGIN, SIZE)` axis is
 * arithmetic over the two slots performed at the *read site*, not a third node.
 *
 * Deliberately holds no [Graph] reference. An axis is data; the reads are functions. That
 * keeps it two ints and two enum references, which matters at 40k elements and keeps the
 * struct-of-arrays rewrite open.
 */
class Axis internal constructor(
    /** The node holding the value of [Basis.first]. */
    val firstSlot: Slot,
    /** The node holding the value of [Basis.second]. */
    val secondSlot: Slot,
    @JvmField val basis: Basis,
    /**
     * Whether this axis's start edge (left, or bottom) sits at the *lower* coordinate.
     * Derived from window/viewport orientation and orthogonal to everything else here —
     * `layout-design.md` §2.3.
     *
     * The slot layer only records it. Mapping it onto `left`/`right`/`top`/`bottom` needs to
     * know whether the axis is horizontal or vertical, which is the element layer's business:
     * `left` is meaningless on a bare axis.
     */
    @JvmField val startIsLow: Boolean,
) {
    /** The slot holding [property], or `null` if that property is derived rather than written. */
    fun slotFor(property: Property): Slot? = when (property) {
        basis.first -> firstSlot
        basis.second -> secondSlot
        else -> null
    }

    override fun toString(): String = "Axis($basis: $firstSlot, $secondSlot)"
}

// -------------------------------------------------------------------------------------
// Reads
//
// These 24 cases *are* the provenance table of `layout-design.md` §4.2, expressed as code
// rather than as data. Each one performs exactly the reads that property needs and no more,
// and because the reads happen inside the caller's tracking context, the graph records edges
// to exactly those slots.
//
// That precision is load-bearing, not an optimisation. §4.2: a shrink-wrap parent reading
// `child.size` under an `(ORIGIN, SIZE)` child touches only the child's SIZE slot, never its
// ORIGIN slot — which is the one pointing back at the parent. A conservative "depends on the
// whole axis" approximation reintroduces the false cycle that slot granularity exists to
// remove. There is a test for exactly that.
//
// Written as extension functions on Graph so they are callable unqualified inside a
// Computation, whose receiver is the graph.
// -------------------------------------------------------------------------------------

fun Graph.originOf(axis: Axis): Double {
    val a = axis.firstSlot
    val b = axis.secondSlot
    return when (axis.basis) {
        Basis.ORIGIN_SIZE -> read(a)
        Basis.ORIGIN_END -> read(a)
        Basis.ORIGIN_CENTER -> read(a)
        Basis.SIZE_END -> read(b) - read(a)
        Basis.SIZE_CENTER -> read(b) - read(a) / 2.0
        Basis.END_CENTER -> 2.0 * read(b) - read(a)
    }
}

fun Graph.sizeOf(axis: Axis): Double {
    val a = axis.firstSlot
    val b = axis.secondSlot
    return when (axis.basis) {
        Basis.ORIGIN_SIZE -> read(b)
        Basis.ORIGIN_END -> read(b) - read(a)
        Basis.ORIGIN_CENTER -> (read(b) - read(a)) * 2.0
        Basis.SIZE_END -> read(a)
        Basis.SIZE_CENTER -> read(a)
        Basis.END_CENTER -> (read(a) - read(b)) * 2.0
    }
}

fun Graph.endOf(axis: Axis): Double {
    val a = axis.firstSlot
    val b = axis.secondSlot
    return when (axis.basis) {
        Basis.ORIGIN_SIZE -> read(a) + read(b)
        Basis.ORIGIN_END -> read(b)
        Basis.ORIGIN_CENTER -> 2.0 * read(b) - read(a)
        Basis.SIZE_END -> read(b)
        Basis.SIZE_CENTER -> read(b) + read(a) / 2.0
        Basis.END_CENTER -> read(a)
    }
}

fun Graph.centerOf(axis: Axis): Double {
    val a = axis.firstSlot
    val b = axis.secondSlot
    return when (axis.basis) {
        Basis.ORIGIN_SIZE -> read(a) + read(b) / 2.0
        Basis.ORIGIN_END -> (read(a) + read(b)) / 2.0
        Basis.ORIGIN_CENTER -> read(b)
        Basis.SIZE_END -> read(b) - read(a) / 2.0
        Basis.SIZE_CENTER -> read(b)
        Basis.END_CENTER -> read(b)
    }
}

/** Read [property] off [axis], for the cases where the property is a variable. */
fun Graph.propertyOf(axis: Axis, property: Property): Double = when (property) {
    Property.ORIGIN -> originOf(axis)
    Property.SIZE -> sizeOf(axis)
    Property.END -> endOf(axis)
    Property.CENTER -> centerOf(axis)
}
