package com.dubulduke.layout

import com.dubulduke.reactivity.GovernorHandle
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
    index: Int,
    /** Stable identity and the handle for `query`. See `dsl-and-engine.md` §3.4. */
    @JvmField val id: String?,
    /**
     * Reconciliation key, or `null` for a statically composed element.
     *
     * What matches an element across a structural change. Positional matching alone is not
     * enough — inserting an item at the head of a list would re-associate every element with
     * the wrong data and throw away every memoised value below it (`dsl-and-engine.md` §5.7,
     * constraint 2). The key is also what names the element's graph nodes, so a diagnostic
     * survives a reorder.
     */
    @JvmField val key: Any?,
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
     * Position in [parent]'s child list. Meaningless for the root, where it is 0.
     *
     * Mutable, which is what reconciliation costs here: removing a sibling shifts everyone
     * after it, and `prev`, `next` and `index` all read this. The setter stays internal — an
     * author moving it would silently desynchronise it from the list it indexes into.
     */
    var index: Int = index
        internal set

    /**
     * Set by [ElementTree.dispose]. A disposed element's slots are gone, so reading its
     * geometry is an error from the graph rather than a plausible stale number.
     */
    var disposed: Boolean = false
        internal set

    /**
     * The governor resolving this element's children, if it is a clamped flex row.
     *
     * Held so disposal can release it *before* the children whose size slots it claims — a
     * governor that lost an output would fail its totality check on the next solve, naming the
     * output rather than the disposal that caused it.
     */
    var governor: GovernorHandle? = null
        internal set

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
     * Hover and press state, or `null` for an element that never opted in.
     *
     * Non-null is what makes an element hit-testable at all — see [interactive]. Allocated rather
     * than always present because it is two graph nodes per element, and a UI is mostly decoration
     * that should never intercept a click.
     */
    var interaction: Interaction? = null
        internal set

    /**
     * Whether this element confines its subtree to its own box when drawn and hit-tested.
     *
     * A plain flag rather than a graph node, and that is the point: clipping is not a layout
     * input. A clipped child's slots resolve to exactly the same numbers whether or not the
     * clip exists — it is out of the box, it just cannot be seen. Making clipping affect
     * layout is how CSS ended up with `overflow` participating in sizing.
     *
     * The consequence worth stating: **a clip is not a scroll**. Offsetting the content is
     * ordinary layout — a signal read by the content's origin — and this flag is only what
     * stops the overflow being drawn. See `:dsl`'s `scrollArea`, which is the two together.
     */
    var clips: Boolean = false
        internal set

    /**
     * Retained wheel handler, or `null`. Dispatched by [Pointer], **bubbling** from the hovered
     * element up through its ancestors.
     *
     * Bubbling here and nowhere else, deliberately. A click means the topmost thing under the
     * pointer and nothing above it — that is what makes a button inside a panel a button. A
     * wheel means the nearest thing that *can* scroll, because the pointer is almost always
     * over a row rather than over the list, and a row that swallowed the wheel because it
     * happened to be interactive would make scrolling stop wherever the content is densest.
     */
    var onScroll: ((Double) -> Unit)? = null
        internal set

    /**
     * Retained drag handler, or `null`. Its presence is what makes [Pointer] capture on press.
     *
     * Capture is the exception to previous-frame hit testing rather than a violation of it: while
     * captured, there *is* no hit test — the pointer belongs to this element until release, which
     * is precisely why a slider keeps tracking after the cursor leaves its box.
     */
    var onDrag: ((Double, Double) -> Unit)? = null
        internal set

    /**
     * Keyboard focus state, or `null` for an element that never opted in.
     *
     * Separate from [interaction] rather than a third slot on it, because **hit-testable and
     * focusable are different capabilities that mostly do not coincide**. A scroll area is
     * interactive — it takes the wheel and answers the pointer — and must never be a tab stop; a
     * decorative panel is neither; only controls are both. Folding focus into `Interaction` would
     * allocate a node per hoverable element to serve the few that are focusable.
     */
    var focus: Focus? = null
        internal set

    /**
     * What activating this element does — the keyboard's equivalent of a click.
     *
     * Falls back to [onClick] when unset, which is the behaviour worth having by default: a button
     * that already says what clicking it does should not have to say it twice to be operable from
     * the keyboard. Set it explicitly only where the two genuinely differ.
     */
    var onActivate: (() -> Unit)? = null
        internal set

    /**
     * Reveal a descendant — scroll it into view. Set by containers that can, `null` otherwise.
     *
     * The hook exists because focus can land somewhere off-screen and the element that has to do
     * something about it is an *ancestor*, which the focused element knows nothing about. Walking
     * up and asking is the only direction that composes: a control must not have to know it is
     * inside a scroll area, or it could not be reused outside one.
     */
    var onReveal: ((Element) -> Unit)? = null
        internal set

    /**
     * Explicit directional-navigation targets, indexed by [FocusDirection.ordinal], or `null`.
     *
     * Lazily allocated because almost nothing sets one. The geometric answer is right most of the
     * time and wrong often enough that every shipping game UI grows overrides — a tab bar whose
     * "down" should enter the panel it controls rather than the nearest box below it, say. Having
     * them from the start is cheaper than retrofitting them around a heuristic people have already
     * built layouts to satisfy.
     */
    internal var focusNeighbours: Array<(() -> Element?)?>? = null

    /**
     * Typed tags for ancestor queries. See [Marker]. Lazily allocated — almost nothing sets one.
     */
    internal var markers: ArrayList<Marker>? = null

    /**
     * Retained click handler, or `null`. Registered during composition and dispatched by [Pointer].
     *
     * Retained rather than polled, which is a coupling worth naming: a polled `if (isHovering &&
     * pressed)` only fires if the block containing it runs, so it silently requires per-frame
     * composition and would never fire under compose-once (`dsl-and-engine.md` §7.4).
     */
    var onClick: (() -> Unit)? = null
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

    internal val hOrNull: Axis? get() = _h
    internal val vOrNull: Axis? get() = _v

    /**
     * The child snapshot, read **without** recording a dependency.
     *
     * For the draw walk and for tests — phases that are outside the graph by construction.
     * Inside a layout expression use [childrenOf] instead, or a structural change will fail to
     * invalidate. The two return the same list; only the edge differs.
     */
    val children: List<Element> get() = snapshot

    /**
     * The child list as it stands *right now*, including children added since the last publish.
     *
     * Differs from [children] only during composition and inside a reconcile, which is exactly
     * when the difference matters: [children] is the published snapshot the graph sees, and this
     * is the list being assembled. Not for layout expressions — neither of these records an edge.
     */
    val currentChildren: List<Element> get() = childList

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
