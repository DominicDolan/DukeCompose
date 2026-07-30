package com.dubulduke.layout

import com.dubulduke.reactivity.Computation
import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.Slot

/**
 * Build one axis by registering an expression for each of exactly two properties.
 *
 * Each registration becomes one graph node with exactly its own reads. That is
 * `layout-design.md` §4.4 — slot-level *nodes* are worthless without slot-level
 * *expressions*, because a lambda is atomic to the evaluator and so every slot written by one
 * lambda would share the union of that lambda's reads, which is enough on its own to
 * reintroduce the false cycles that slot granularity exists to remove.
 */
@LayoutDsl
class AxisBuilder internal constructor(
    private val graph: Graph,
    private val name: String?,
    private val startIsLow: Boolean,
    private val ownerTree: ElementTree? = null,
    private val ownerElement: Element? = null,
) {
    /**
     * The element this axis belongs to, for expressions that need to refer to themselves —
     * `size { contentHeight(element) }`.
     *
     * Present here as well as on [ElementBuilder] because `@LayoutDsl` blocks the outer receiver,
     * which is the point of the marker: without it, an identifier resolving silently to the
     * enclosing element is exactly the bug class it exists to prevent. `null` for a standalone
     * axis built through `Graph.axis`.
     */
    val element: Element get() = checkNotNull(ownerElement) {
        "${label()} is a standalone axis and has no element"
    }

    /** The tree, for `query`. Same reasoning as [element]. */
    val tree: ElementTree get() = checkNotNull(ownerTree) {
        "${label()} is a standalone axis and has no tree"
    }

    private var firstProperty: Property? = null
    private var firstSlot: Slot = Slot(-1)
    private var secondProperty: Property? = null
    private var secondSlot: Slot = Slot(-1)

    fun origin(body: Computation): AxisBuilder = register(Property.ORIGIN, body)
    fun size(body: Computation): AxisBuilder = register(Property.SIZE, body)
    fun end(body: Computation): AxisBuilder = register(Property.END, body)
    fun center(body: Computation): AxisBuilder = register(Property.CENTER, body)

    private fun register(property: Property, body: Computation): AxisBuilder {
        // Validate before creating the node, so a rejected axis leaves no orphan in the graph.
        validate(property)
        return adopt(property, graph.derive(slotName(property), body))
    }

    /**
     * Register [property] as a slot that something *else* computes: a plain input, created here
     * so it carries the right name, and handed back for a governor to claim.
     *
     * This is the seam between the two ways a slot can get its value. An ordinary property is a
     * derived node owning its own expression; a governed one is an input whose only writer is a
     * governor's solve, enforced by the graph rather than by convention
     * (`layout-design.md` §7.2). The axis cannot tell the difference, and neither can any
     * reader — which is the whole point of the firewall structure.
     */
    internal fun claimable(property: Property): Slot {
        validate(property)
        val slot = graph.input(0.0, slotName(property))
        adopt(property, slot)
        return slot
    }

    private fun validate(property: Property) {
        require(firstProperty != property && secondProperty != property) {
            "${label()} already has an expression for $property"
        }
        // Over-constrained. Worth a specific message: `left`/`right`/`width` all at once is one
        // of the classic sources of confusion in CSS, and there the resolution is silent. Here
        // it is a build error naming all three.
        require(firstProperty == null || secondProperty == null) {
            "${label()} is over-constrained: an axis has two degrees of freedom, but " +
                "expressions were given for $firstProperty, $secondProperty and $property"
        }
    }

    private fun adopt(property: Property, slot: Slot): AxisBuilder {
        if (firstProperty == null) {
            firstProperty = property
            firstSlot = slot
        } else {
            secondProperty = property
            secondSlot = slot
        }
        return this
    }

    private fun slotName(property: Property): String? =
        name?.let { "$it.${property.name.lowercase()}" }

    internal fun build(): Axis {
        val p = firstProperty
        val q = secondProperty
        // Deliberately an error rather than a default. Defaulting the missing property to zero
        // is what the old VaryingDimension did, and a silently zeroed origin produces a
        // plausible-looking layout with an element in the wrong place. Defaults are a DSL
        // policy — visible where they are chosen, not hidden in the slot layer.
        check(p != null && q != null) {
            "${label()} is under-constrained: an axis needs two of " +
                "${Property.entries}, got ${listOfNotNull(p, q)}"
        }

        val basis = Basis.of(p, q)
        // Order the slots to match the basis's declared order, so the read functions can index
        // them positionally without re-deriving which is which.
        val forFirst = if (p == basis.first) firstSlot else secondSlot
        val forSecond = if (p == basis.first) secondSlot else firstSlot
        return Axis(forFirst, forSecond, basis, startIsLow)
    }

    private fun label() = name ?: "axis"
}

/**
 * Define one axis of one element.
 *
 * ```kotlin
 * val panelH = g.axis("panel.h") {
 *     origin { 0.0 }
 *     size   { sizeOf(childH) }
 * }
 * ```
 *
 * Nothing evaluates here; the two expressions are lazy graph nodes like any other. [name] is
 * used to build per-slot node names (`panel.h.size`), which is what makes cycle diagnostics
 * legible, so it is worth passing outside of throwaway code.
 */
fun Graph.axis(
    name: String? = null,
    startIsLow: Boolean = true,
    build: AxisBuilder.() -> Unit,
): Axis = AxisBuilder(this, name, startIsLow).apply(build).build()

/** As [Graph.axis], but attached to an element so expressions can use `element` and `tree`. */
internal fun ElementTree.axisFor(
    owner: Element,
    name: String?,
    startIsLow: Boolean,
    build: AxisBuilder.() -> Unit,
): Axis = AxisBuilder(graph, name, startIsLow, this, owner).apply(build).build()
