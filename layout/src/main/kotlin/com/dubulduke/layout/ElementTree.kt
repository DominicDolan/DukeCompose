package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RefComputation
import com.dubulduke.reactivity.RefEquality
import com.dubulduke.reactivity.Slot

/**
 * The element tree, and the id index that `query` resolves against.
 *
 * Composition (phase 1) builds the tree; layout (phase 2) evaluates expressions against it.
 * The separation is what makes forward references work: an expression registered while the
 * tree is half-built does not run until the whole tree exists, so it can refer to a following
 * sibling or to an id defined later without any late-binding machinery
 * (`dsl-and-engine.md` §2.6, §3.5).
 */
class ElementTree(
    @JvmField val graph: Graph,
    /**
     * Text measurement. Defaults to the deterministic stub, which is what `:layout`'s own tests
     * want and what golden files require; a real host substitutes its backend's.
     */
    @JvmField val textMetrics: TextMetrics = HeadlessTextMetrics(),
) {

    private var _root: Element? = null

    /** `null` until [root] has been called. */
    val rootOrNull: Element? get() = _root

    val root: Element get() = checkNotNull(_root) { "the tree has no root yet" }

    /**
     * id → a ref node holding the element with that id.
     *
     * The *binding* is a graph node, not just a map entry, so that if an id points at element A
     * on one frame and element B on the next, everything that queried it invalidates correctly
     * (`dsl-and-engine.md` §3.5).
     */
    private val ids = HashMap<String, Slot>()

    /** Build the root. May be called once. */
    fun root(id: String? = null, build: ElementBuilder.() -> Unit): Element {
        check(_root == null) { "the tree already has a root: ${_root}" }
        val e = newElement(parent = null, index = 0, id = id)
        _root = e
        ElementBuilder(this, e).apply(build).finish()
        return e
    }

    internal fun newElement(parent: Element?, index: Int, id: String?, key: Any? = null): Element {
        // The key names the element in preference to the index, so a diagnostic survives a
        // reorder: `list.{drafts}.label` still points at the same row after an insertion above
        // it, where `list.[3].label` would by then mean something else.
        val name = id ?: key?.let { "{$it}" } ?: if (parent == null) "root" else "[$index]"
        val path = if (parent == null) name else "${parent.path}.$name"
        val slot = graph.refInput(emptyList<Element>(), "$path.children")
        val e = Element(parent, index, id, key, path, slot)
        if (id != null) bind(id, e)
        return e
    }

    private fun bind(id: String, e: Element) {
        // Duplicate ids are a composition-time error, because the whole map is in hand before
        // layout begins — §3.5. Asymmetric with missing ids, which cannot be caught until an
        // expression actually asks.
        //
        // When composition becomes incremental (`layout-design.md` Q10) this becomes a rebind
        // rather than an error for the case where the same id reappears in a new composition:
        // `graph.setRefInput(ids[id]!!, e)`, which is exactly why the binding is a node.
        val existing = ids[id]
        check(existing == null) { "duplicate id '$id': ${graph.readRef(existing!!)} and $e" }
        ids[id] = graph.refInput(e, "#$id")
    }

    /**
     * Resolve an id, recording a dependency on the binding.
     *
     * A plain lookup — no late binding, no proxy — because phase 1 is complete by the time an
     * expression runs. A missing id is a layout-time error naming both the id and the slot that
     * asked, which is the best that can be done: which ids get queried is not knowable until
     * the expressions run.
     */
    fun query(id: String): Element {
        val slot = ids[id]
        if (slot == null) {
            val asker = graph.evaluatingLabel()
            throw IllegalStateException(
                "no element with id '$id'" + (asker?.let { ", queried from $it" } ?: "")
            )
        }
        return graph.readRef(slot) as Element
    }

    /** Every id currently bound. */
    val boundIds: Set<String> get() = ids.keys.toSet()

    /**
     * Depth-first walk, untracked. For the draw phase and for debugging — both of which sit
     * outside the graph by construction. A layout expression must not use this.
     */
    fun walk(from: Element = root, visit: (Element, Int) -> Unit) {
        fun rec(e: Element, depth: Int) {
            visit(e, depth)
            for (c in e.children) rec(c, depth + 1)
        }
        rec(from, 0)
    }

    /** Number of elements in the tree. */
    val size: Int get() = if (_root == null) 0 else { var n = 0; walk { _, _ -> n++ }; n }

    // ---------------------------------------------------------------------------------
    // Structural change — `layout-design.md` Q10
    // ---------------------------------------------------------------------------------

    private val regions = ArrayList<Region>()

    /** Register a region that may rebuild its children between frames. See [reconcile]. */
    fun register(region: Region) {
        regions += region
    }

    /**
     * Give every registered region a chance to rebuild, and report how many did.
     *
     * A phase of its own, between input and layout, and that placement is the whole design:
     * building an element allocates graph nodes, which cannot happen during evaluation, and
     * disposing one invalidates its readers, which must happen before anything reads them.
     * Composition, reconciliation and layout are therefore three separate passes over three
     * separate concerns rather than one interleaved walk.
     *
     * Cost is O(regions) when nothing changed — each region compares its key list and stops.
     * A region whose keys did change costs O(its items), never O(tree): untouched subtrees are
     * not visited, not re-run, and keep every memoised value and every dependency edge. That
     * is the difference between this and re-running composition each frame, which the design's
     * O(changed) premise rules out (`layout-design.md` Q10).
     */
    fun reconcile(): Int {
        var changed = 0
        var i = 0
        while (i < regions.size) {
            val r = regions[i]
            if (r.container.disposed) {
                // A region inside a subtree that has itself been removed. Dropped here rather
                // than at disposal time so that nested regions need no back-references.
                regions.removeAt(i)
                continue
            }
            if (r.reconcile()) changed++
            i++
        }
        return changed
    }

    /** Registered regions that are still live. Diagnostics and tests. */
    val regionCount: Int get() = regions.count { !it.container.disposed }

    /**
     * Destroy [e] and everything under it: its graph nodes, its id bindings, its place in its
     * parent's child list.
     *
     * The parent's child list is republished, which is what wakes the siblings — `prev`, `next`
     * and `children.maxOf { }` all read that node, so a removal invalidates exactly the
     * expressions that navigated past the removed element and nothing else.
     */
    fun dispose(e: Element) {
        check(e !== _root) { "the root cannot be disposed; discard the tree instead" }
        check(!e.disposed) { "$e is already disposed" }
        val parent = checkNotNull(e.parent) { "$e has no parent but is not the root" }
        parent.childList.remove(e)
        disposeSubtree(e)
        reindexAndPublish(parent)
    }

    /**
     * Free every node the subtree owns, deepest concern first.
     *
     * The governor goes before the children, because it claims their size slots and a governor
     * that lost an output fails its totality check on the next solve — naming the output rather
     * than the disposal that actually caused it.
     */
    internal fun disposeSubtree(e: Element) {
        e.governor?.let {
            graph.dispose(it)
            e.governor = null
        }
        for (c in e.childList) disposeSubtree(c)
        e.childList.clear()
        e.snapshot = emptyList()

        e.id?.let { id -> ids.remove(id)?.let { graph.dispose(it) } }
        e.content?.let { graph.dispose(it.textSlot); graph.dispose(it.fontSlot) }
        e.styleNode?.let { graph.dispose(it.slot) }
        e.interaction?.let { graph.dispose(it.hoveredSlot); graph.dispose(it.pressedSlot) }
        e.flexSlot?.let { graph.dispose(it) }
        e.hOrNull?.let { graph.dispose(it.firstSlot); graph.dispose(it.secondSlot) }
        e.vOrNull?.let { graph.dispose(it.firstSlot); graph.dispose(it.secondSlot) }
        graph.dispose(e.childrenSlot)

        e.disposed = true
    }

    internal fun reindexAndPublish(parent: Element) {
        for ((i, c) in parent.childList.withIndex()) c.index = i
        parent.snapshot = parent.childList.toList()
        graph.setRefInput(parent.childrenSlot, parent.snapshot)
    }

    /**
     * Rebuild one element's children in place.
     *
     * Deliberately does not decide *what* the new children are — keyed matching is an authoring
     * concern and lives in `:dsl`. This layer supplies only the three primitives that cannot be
     * written from outside: build a detached child, destroy one, and install an ordered list.
     */
    fun editChildren(container: Element, edit: ChildEdit.() -> Unit) {
        check(!container.disposed) { "$container has been disposed" }
        ChildEdit(this, container).edit()
    }
}

/**
 * A region of the tree whose children are rebuilt when something outside the graph changes.
 *
 * The interface is here rather than in `:dsl` so [ElementTree.reconcile] can drive regions
 * without knowing what a keyed list is. `:layout` supplies the mechanism; the policy — how keys
 * are matched, what a changed item means — belongs to the authoring layer.
 */
interface Region {
    /** The element whose children this region owns. A disposed one is dropped from the tree. */
    val container: Element

    /** Rebuild if needed. Returns whether anything actually changed. */
    fun reconcile(): Boolean
}

/** The structural primitives, handed to [ElementTree.editChildren]. */
class ChildEdit internal constructor(
    @JvmField val tree: ElementTree,
    @JvmField val container: Element,
) {
    /** The children currently installed, in order. */
    val current: List<Element> get() = container.childList

    /**
     * Build a child, **detached**: it exists and owns its nodes, but is not in the child list
     * until [install]. Two-step because the caller usually wants to build a whole new ordered
     * set and install it at once, rather than publish the list once per item.
     */
    fun create(key: Any?, id: String? = null, build: ElementBuilder.() -> Unit): Element {
        val e = tree.newElement(container, container.childList.size, id, key)
        ElementBuilder(tree, e).apply(build).finish()
        return e
    }

    /** Destroy a child and everything under it. Removes it from [current] too. */
    fun dispose(child: Element) {
        require(child.parent === container) { "$child is not a child of $container" }
        container.childList.remove(child)
        tree.disposeSubtree(child)
    }

    /** Install this exact ordered list, reindex it, and republish the child node. */
    fun install(children: List<Element>) {
        container.childList.clear()
        container.childList.addAll(children)
        tree.reindexAndPublish(container)
    }
}

/**
 * Builds one element: its two axes, and its children.
 *
 * Order inside the block does not matter. Expressions are lazy, so an axis registered before
 * the children exist can still read them, and a child registered before the parent's axes can
 * still read those. Nothing here evaluates.
 */
@LayoutDsl
class ElementBuilder internal constructor(
    /**
     * Public rather than internal because the DSL layer lives in another module and needs the
     * graph and the id index to build its scopes on. Nothing here is a supported authoring
     * surface — `:dsl` is.
     */
    @JvmField val tree: ElementTree,
    @JvmField val element: Element,
) {
    /** The horizontal axis. Exactly one call per element. */
    fun h(startIsLow: Boolean = true, build: AxisBuilder.() -> Unit) {
        element.setAxis(true, tree.axisFor(element, "${element.path}.h", startIsLow, build))
    }

    /** The vertical axis. Exactly one call per element. */
    fun v(startIsLow: Boolean = true, build: AxisBuilder.() -> Unit) {
        element.setAxis(false, tree.axisFor(element, "${element.path}.v", startIsLow, build))
    }

    fun classes(vararg names: String) {
        element.classes = names.toList()
    }

    /**
     * Give this element measurable text content, and mark it as a text element.
     *
     * Both arguments become graph inputs, so either can change without recomposition — that is
     * what makes `RefEquality.EQUALS` on the text node worth having.
     */
    fun text(text: String, font: FontRef): Content {
        check(element.content == null) { "$element already has content" }
        val g = tree.graph
        val c = Content(
            textSlot = g.refInput(text, "${element.path}.text", RefEquality.EQUALS),
            fontSlot = g.refInput(font, "${element.path}.font", RefEquality.IDENTITY),
            metrics = tree.textMetrics,
        )
        element.content = c
        element.kind = "text"
        return c
    }

    /**
     * Text computed from the graph rather than fixed at composition.
     *
     * `dsl-and-engine.md` §5.5's first row: under compose-once, `text("Score: $score")` freezes
     * the string at setup and never updates again — a failure that is invisible while
     * composition runs every frame and silent once it does not. A derived node fixes it, and
     * costs nothing when the inputs are still: [RefEquality.EQUALS] means an interpolation that
     * rebuilds an equal string re-triggers no measurement.
     */
    fun text(font: FontRef, text: RefComputation): Content {
        check(element.content == null) { "$element already has content" }
        val g = tree.graph
        val c = Content(
            textSlot = g.deriveRef("${element.path}.text", RefEquality.EQUALS, text),
            fontSlot = g.refInput(font, "${element.path}.font", RefEquality.IDENTITY),
            metrics = tree.textMetrics,
        )
        element.content = c
        element.kind = "text"
        return c
    }

    /** Override the reported [Element.kind]. Carried into the JSON dump, never interpreted. */
    fun kind(kind: String) {
        element.kind = kind
    }

    /**
     * Confine this element's subtree to its own box when drawing and hit testing.
     *
     * Affects no slot — see [Element.clips]. Costs nothing on elements that do not set it: the
     * draw walk carries a null clip until the first clipping element, and the hit test gains one
     * branch per element.
     */
    fun clip() {
        element.clips = true
    }

    /** Append a child and build it. Returns it, for the cases that want to hold a reference. */
    fun child(id: String? = null, key: Any? = null, build: ElementBuilder.() -> Unit): Element {
        val e = tree.newElement(element, element.childList.size, id, key)
        element.childList += e
        ElementBuilder(tree, e).apply(build).finish()
        return e
    }

    internal fun finish() {
        check(element.hasBothAxes()) {
            "$element needs both h { } and v { }; defaults are a DSL policy, not a slot-layer one"
        }
        // Publish the child list once, at the end of this element's composition. Snapshotting
        // rather than publishing the mutable list is what makes IDENTITY comparison correct on
        // the children node: an element whose structure never changes publishes exactly once,
        // and a recycled list mutated in place — invisible to IDENTITY — cannot arise here.
        element.snapshot = element.childList.toList()
        tree.graph.setRefInput(element.childrenSlot, element.snapshot)
    }
}
