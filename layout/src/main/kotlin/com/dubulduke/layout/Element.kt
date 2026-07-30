package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.Slot

/**
 * A node of the element tree: two independent [Axis]es, a position among its siblings, and a
 * child list that is itself a graph node.
 *
 * The tree is *not* the dependency graph and bears no structural resemblance to it
 * (`layout-design.md` §4.3). It exists only so that layout expressions have something to
 * navigate — `parent`, `prev`, `next`, `children`, `query(id)` — and so that the draw walk has
 * something to walk. Nothing in the layout phase may assume tree shape.
 *
 * Deliberately holds no [Graph] reference. Like [Axis], an element is data and the reads are
 * functions on the graph, which is what lets `parent.left` be a field read plus arithmetic
 * with nothing allocated per read (`dsl-and-engine.md` §2.6).
 */
class Element internal constructor(
    /** `null` only for the root. */
    @JvmField val parent: Element?,
    /** Position in [parent]'s child list. Meaningless for the root, where it is 0. */
    @JvmField val index: Int,
    /** Stable identity and the handle for `query`. See `dsl-and-engine.md` §3.4. */
    @JvmField val id: String?,
    /**
     * Dotted path from the root, used to name this element's graph nodes. Diagnostics live or
     * die on this: a cycle report saying `panel.body.h.size` is actionable, one saying
     * `Slot(47)` is not.
     */
    @JvmField val path: String,
    /**
     * A ref node holding this element's children as an immutable snapshot.
     *
     * `layout-design.md` §8.7: `parent.width = children.maxOf { it.width }` must depend on the
     * child *list*, not merely on the current children's widths, or adding a child fails to
     * invalidate the parent. There is nothing scalar to hang that edge on, so the list is a
     * node. The same applies to `prev`, `next` and `siblings`, which are equally
     * structure-dependent — see [childrenOf] and friends.
     */
    val childrenSlot: Slot,
) {
    /**
     * Carried for the JSON dump of `dsl-and-engine.md` §8, which uses them to locate elements
     * in output. The layout algorithm never reads them; they are not selectors.
     */
    var classes: List<String> = emptyList()
        internal set

    /**
     * This element's flex factor, or `null` if it does not grow.
     *
     * A graph node rather than a plain `Double`, which is the more powerful answer to
     * `layout-design.md` Q2: a flex factor can itself be computed, and changing one invalidates
     * the container's hoisted totals correctly. Only allocated for elements that actually
     * participate in a flex container.
     */
    var flexSlot: Slot? = null
        internal set

    /** Measurable text content, or `null` for an element that has none. */
    var content: Content? = null
        internal set

    /**
     * This element's style, or `null` if it was composed without one.
     *
     * Carried, never interpreted — see [StyleNode]. The layout algorithm must not read it, and
     * that is a hard rule rather than an oversight: anything layout reads needs slot identity,
     * which an application-defined style object cannot have (`dsl-and-engine.md` §4.5). Font
     * selection and text content therefore live in [content], on the framework's side of the
     * line, while colours and shadows live here on the application's.
     */
    var styleNode: StyleNode? = null
        internal set

    /**
     * What this element is, for the JSON dump of `dsl-and-engine.md` §8. Like [classes], carried
     * rather than interpreted: the layout algorithm does not branch on it. The DSL is the source
     * of anything beyond `"box"` and `"text"`.
     */
    var kind: String = "box"
        internal set

    private var _h: Axis? = null
    private var _v: Axis? = null

    /** The horizontal axis. Independent of [v] — `layout-design.md` §2.2, do not merge them. */
    val h: Axis get() = checkNotNull(_h) { "$this has no horizontal axis" }

    /** The vertical axis. */
    val v: Axis get() = checkNotNull(_v) { "$this has no vertical axis" }

    internal fun setAxis(horizontal: Boolean, axis: Axis) {
        if (horizontal) {
            require(_h == null) { "$this already has a horizontal axis" }
            _h = axis
        } else {
            require(_v == null) { "$this already has a vertical axis" }
            _v = axis
        }
    }

    internal fun hasBothAxes() = _h != null && _v != null

    /**
     * The child snapshot, read **without** recording a dependency.
     *
     * For the draw walk and for tests — phases that are outside the graph by construction.
     * Inside a layout expression use [childrenOf] instead, or a structural change will fail to
     * invalidate. The two return the same list; only the edge differs.
     */
    val children: List<Element> get() = snapshot

    internal var snapshot: List<Element> = emptyList()
    internal val childList = ArrayList<Element>()

    override fun toString(): String = "Element($path)"
}

// -------------------------------------------------------------------------------------
// Navigation — tracked
//
// All of these read the parent's child-list node, so an expression that navigates the tree
// depends on the shape of the tree and not only on the values it finds there. `next` is the
// clearest case: inserting a sibling after this element changes the answer without changing
// any number anywhere.
//
// `dsl-and-engine.md` §2.6: these resolve when the expression *runs*, never when it is
// registered. That is what makes a forward reference such as `right { nextOf(this)!!.left }`
// work at all, and it is a constraint on the implementation rather than an accident.
// -------------------------------------------------------------------------------------

/** This element's children, recording a dependency on the child list. */
fun Graph.childrenOf(e: Element): List<Element> {
    @Suppress("UNCHECKED_CAST")
    return readRef(e.childrenSlot) as List<Element>
}

/** The preceding sibling, or `null` at index 0. */
fun Graph.prevOf(e: Element): Element? {
    val p = e.parent ?: return null
    return childrenOf(p).getOrNull(e.index - 1)
}

/** The following sibling, or `null` at the last index. */
fun Graph.nextOf(e: Element): Element? {
    val p = e.parent ?: return null
    return childrenOf(p).getOrNull(e.index + 1)
}

/** All elements at this level, including this one. A list of one for the root. */
fun Graph.siblingsOf(e: Element): List<Element> {
    val p = e.parent ?: return listOf(e)
    return childrenOf(p)
}

/** How many elements sit at this level, including this one. */
fun Graph.countOf(e: Element): Int = siblingsOf(e).size

/**
 * The preceding sibling if there is one, otherwise the parent — for the common chaining case
 * where the first item pins to the container instead. `dsl-and-engine.md` §2.5.
 */
fun Graph.prevOrParentOf(e: Element): Element? = prevOf(e) ?: e.parent

// -------------------------------------------------------------------------------------
// Geometry, per element and axis
// -------------------------------------------------------------------------------------

fun Graph.leftOf(e: Element): Double =
    if (e.h.startIsLow) originOf(e.h) else endOf(e.h)

fun Graph.rightOf(e: Element): Double =
    if (e.h.startIsLow) endOf(e.h) else originOf(e.h)

fun Graph.bottomOf(e: Element): Double =
    if (e.v.startIsLow) originOf(e.v) else endOf(e.v)

fun Graph.topOf(e: Element): Double =
    if (e.v.startIsLow) endOf(e.v) else originOf(e.v)

fun Graph.widthOf(e: Element): Double = sizeOf(e.h)
fun Graph.heightOf(e: Element): Double = sizeOf(e.v)
fun Graph.centerXOf(e: Element): Double = centerOf(e.h)
fun Graph.centerYOf(e: Element): Double = centerOf(e.v)
