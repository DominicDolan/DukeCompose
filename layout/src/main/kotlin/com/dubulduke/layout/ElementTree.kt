package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
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

    internal fun newElement(parent: Element?, index: Int, id: String?): Element {
        val name = id ?: if (parent == null) "root" else "[$index]"
        val path = if (parent == null) name else "${parent.path}.$name"
        val slot = graph.refInput(emptyList<Element>(), "$path.children")
        val e = Element(parent, index, id, path, slot)
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
    internal val tree: ElementTree,
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

    /** Override the reported [Element.kind]. Carried into the JSON dump, never interpreted. */
    fun kind(kind: String) {
        element.kind = kind
    }

    /** Append a child and build it. Returns it, for the cases that want to hold a reference. */
    fun child(id: String? = null, build: ElementBuilder.() -> Unit): Element {
        val e = tree.newElement(element, element.childList.size, id)
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
