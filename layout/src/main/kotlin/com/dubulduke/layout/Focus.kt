package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.Slot

/**
 * Keyboard focus. `dsl-and-engine.md` §7, the keyboard half.
 *
 * ### Why this is not part of [Pointer]
 *
 * Focus **routes without hit testing**, and that is the whole difference in kind. The pointer's
 * question is "what is under this coordinate?", answered by geometry every frame. Focus's question
 * is "what did the user last select?", which is *retained application state* that changes only when
 * something asks it to. There is no coordinate involved, nothing to sample, and no per-frame work
 * at all — a UI nobody is tabbing through costs zero.
 *
 * That is also why focus survives things hover does not. Moving the mouse away removes hover
 * immediately; moving it away does not remove focus, because focus was never about where the
 * pointer is.
 *
 * ### Focus is per-element inputs, not one "who is focused" node
 *
 * The obvious design is a single ref node holding the focused element, and it is wrong for the same
 * reason hover is not built that way: every style reading focus would depend on that one node, so
 * *any* focus change would invalidate *all* of them. Per-element slots means exactly two nodes
 * invalidate per change — the one losing it and the one gaining it — however many focusable
 * elements exist.
 *
 * ### Two navigation policies over one set
 *
 * [next] / [previous] walk **tree order**, which is composition order and therefore the order the
 * author can see on the screen and in the file. [move] navigates **geometrically**, for a d-pad or
 * arrow keys. They deliberately share [focusables] so the two can never disagree about what is
 * focusable, only about what comes next.
 *
 * No `tabIndex`. Explicit global ordering numbers are the part of the web platform that reliably
 * rots, because nothing keeps them consistent as a UI is refactored. If tree order is wrong the
 * honest fix is to move the element.
 */

/** Which way [Focus.move] is going. */
enum class FocusDirection { UP, DOWN, LEFT, RIGHT }

/**
 * A command from the platform, already interpreted.
 *
 * `:layout` deliberately knows nothing about key codes — the same way [Pointer] takes coordinates
 * rather than mouse buttons. Which key means "next" is a platform question, and one with real
 * answers to disagree about: tab on a keyboard, a shoulder button on a pad, both at once on a
 * handheld.
 */
enum class FocusCommand {
    NEXT,
    PREVIOUS,
    UP,
    DOWN,
    LEFT,
    RIGHT,

    /** Enter, space, the south face button — whatever this platform calls "do the thing". */
    ACTIVATE,

    /** Escape or back. Drops focus; an application wanting more can watch [Focus.focused]. */
    CANCEL,
}

/** Per-element focus state, allocated only for elements that opt in. See [focusable]. */
class Focus internal constructor(
    // No @JvmField: a value-class-typed property cannot have one. Same as Interaction's slots.
    internal val focusedSlot: Slot,
)

/**
 * Make this element focusable, and give it focus state.
 *
 * Opt-in for the same reason [interactive] is, and more so: a tab stop the user did not expect is
 * worse than a click target they did not expect, because they cannot see where it went.
 */
fun ElementBuilder.focusable(): Focus {
    element.focus?.let { return it }
    val f = Focus(focusedSlot = tree.graph.input(0.0, "${element.path}.focused"))
    element.focus = f
    return f
}

/**
 * What activating this element does. Implies [focusable].
 *
 * Unset, activation falls through to `onClick`, so a focusable button needs nothing extra.
 */
fun ElementBuilder.onActivate(handler: () -> Unit) {
    focusable()
    element.onActivate = handler
}

/** Override where [FocusDirection] navigation goes from this element. Implies [focusable]. */
fun ElementBuilder.focusNeighbour(direction: FocusDirection, target: () -> Element?) {
    focusable()
    val slots = element.focusNeighbours
        ?: arrayOfNulls<(() -> Element?)?>(FocusDirection.entries.size).also {
            element.focusNeighbours = it
        }
    slots[direction.ordinal] = target
}

/** Let this element scroll a descendant into view. Set by scroll containers. */
fun ElementBuilder.onReveal(handler: (Element) -> Unit) {
    element.onReveal = handler
}

/** True while this element has keyboard focus. A tracked read — use it in layout or style. */
fun Graph.isFocused(e: Element): Boolean =
    e.focus?.let { read(it.focusedSlot) != 0.0 } ?: false

private fun ElementTree.overlaps(e: Element, clipper: Element): Boolean {
    val x0 = maxOf(minOf(graph.leftOf(e), graph.rightOf(e)), minOf(graph.leftOf(clipper), graph.rightOf(clipper)))
    val x1 = minOf(maxOf(graph.leftOf(e), graph.rightOf(e)), maxOf(graph.leftOf(clipper), graph.rightOf(clipper)))
    val y0 = maxOf(minOf(graph.topOf(e), graph.bottomOf(e)), minOf(graph.topOf(clipper), graph.bottomOf(clipper)))
    val y1 = minOf(maxOf(graph.topOf(e), graph.bottomOf(e)), maxOf(graph.topOf(clipper), graph.bottomOf(clipper)))
    return x0 < x1 && y0 < y1
}

/**
 * Whether this element is currently on screen through every clipping ancestor.
 *
 * Overlap rather than containment, so a row half-scrolled off the top still counts — it is visible,
 * and treating it as hidden would strand a user at the edge of a list.
 */
fun ElementTree.isVisible(e: Element): Boolean {
    var ancestor = e.parent
    while (ancestor != null) {
        if (ancestor.clips && !overlaps(e, ancestor)) return false
        ancestor = ancestor.parent
    }
    return true
}

/**
 * Whether focus can usefully land on this element — visible, **or** able to be made visible.
 *
 * The distinction between this and [isVisible] is the whole of how focus and scrolling get along,
 * and getting it wrong breaks one of two things:
 *
 * - Filter the tab order by [isVisible] and a list becomes untabbable past its first screenful.
 *   Tab from the last visible row and there is no next row *in the order*, so it wraps to the top
 *   and the list can never be entered.
 * - Do not filter at all and a subtree clipped away by something that cannot scroll becomes a set
 *   of invisible tab stops: the ring is real, it is simply somewhere nobody can see.
 *
 * So the test is not "is it on screen" but "**would focusing it put it on screen**". A clipping
 * ancestor that can [onReveal] is not an obstacle, because focus will ask it to scroll; one that
 * cannot is, because nothing will ever bring the element into view.
 */
fun ElementTree.isFocusReachable(e: Element): Boolean {
    var ancestor = e.parent
    while (ancestor != null) {
        if (ancestor.clips && ancestor.onReveal == null && !overlaps(e, ancestor)) return false
        ancestor = ancestor.parent
    }
    return true
}

/**
 * Drives keyboard focus for one tree.
 *
 * Holds a single retained reference — which element has focus — and nothing else. Everything about
 * *where* focus can go is recomputed from the tree when asked, because the tree is the authority
 * and caching an order would mean invalidating it on every structural change.
 */
class Focuser(private val tree: ElementTree) {

    init {
        // Same reasoning as Pointer's: focus is held across frames and reconciliation runs between
        // them, so a focused element removed by its own handler must not be left dangling. Moving
        // focus somewhere sensible instead is tempting and wrong — it would run application code
        // from inside a structural edit.
        tree.onDispose { e -> if (focused === e) focused = null }
    }

    /** The focused element, or `null`. Change it with [focus], [next], [previous] or [move]. */
    var focused: Element? = null
        private set

    /** Called after focus moves, with the newly focused element. For a caret, a sound, a log. */
    var onFocusChanged: (Element?) -> Unit = {}

    /**
     * Give focus to [e], or clear it with `null`.
     *
     * Reveals the target afterwards, so focus landing inside a scrolled list brings it into view.
     */
    fun focus(e: Element?) {
        require(e == null || e.focus != null) { "$e is not focusable; call focusable() on it" }
        if (e === focused) return

        val g = tree.graph
        focused?.focus?.let { g.setInput(it.focusedSlot, 0.0) }
        e?.focus?.let { g.setInput(it.focusedSlot, 1.0) }
        focused = e

        if (e != null) reveal(e)
        onFocusChanged(e)
    }

    /** Drop focus without moving it. */
    fun clear() = focus(null)

    /**
     * Ask every ancestor that can to bring [e] into view, innermost first.
     *
     * Innermost first because an outer container's idea of where [e] is only settles once the inner
     * one has finished moving it. That still leaves the outer working from geometry one scroll
     * stale, which resolves on the next frame — the same one-frame settle as everything else that
     * reads geometry from an event handler.
     */
    private fun reveal(e: Element) {
        var ancestor = e.parent
        while (ancestor != null) {
            ancestor.onReveal?.invoke(e)
            ancestor = ancestor.parent
        }
    }

    /**
     * Every focusable element focus could usefully land on, in tree order. See [isFocusReachable].
     *
     * Rebuilt on demand rather than maintained. Navigation happens on the frames a human presses a
     * key, so this is not a hot path, and the alternative — a cached order invalidated by every
     * structural change, every clip change and every scroll — would be a second source of truth
     * that could disagree with the tree.
     */
    fun focusables(into: MutableList<Element> = ArrayList()): MutableList<Element> {
        into.clear()
        val root = tree.rootOrNull ?: return into
        fun rec(e: Element) {
            // A clipping element that cannot scroll and is itself off screen takes its subtree with
            // it — nothing under it could be revealed either.
            if (e.clips && e.onReveal == null && !tree.isVisible(e)) return
            if (e.focus != null && tree.isFocusReachable(e)) into += e
            for (c in e.children) rec(c)
        }
        rec(root)
        return into
    }

    private val scratch = ArrayList<Element>()

    /** Focus the next focusable in tree order, wrapping. Returns whether anything is focusable. */
    fun next(): Boolean = step(1)

    /** Focus the previous focusable in tree order, wrapping. */
    fun previous(): Boolean = step(-1)

    private fun step(by: Int): Boolean {
        val order = focusables(scratch)
        if (order.isEmpty()) return false
        // From nothing, forward starts at the first and backward at the last — so a first press of
        // shift-tab lands somewhere useful rather than on whatever happens to be first.
        val current = focused?.let { order.indexOf(it) } ?: if (by > 0) -1 else 0
        val size = order.size
        val nextIndex = ((current + by) % size + size) % size
        focus(order[nextIndex])
        return true
    }

    /**
     * Move focus geometrically. Returns whether it moved.
     *
     * An explicit [focusNeighbour] wins outright; otherwise the best candidate is chosen by score.
     * The scoring is the standard one and worth stating because it is the part people disagree
     * with: a candidate must lie in the given direction, and among those the one with the most
     * **perpendicular overlap** wins, ties broken by distance along the axis of travel.
     *
     * Overlap before distance is what makes a column of buttons behave. Pure nearest-centre picks
     * whatever is diagonally closest, so pressing down in a two-column form jumps sideways — right
     * by the distance metric, wrong by every user's expectation.
     */
    fun move(direction: FocusDirection): Boolean {
        val from = focused ?: return step(1)

        from.focusNeighbours?.get(direction.ordinal)?.let { override ->
            val target = override()
            if (target != null && target.focus != null) {
                focus(target)
                return true
            }
            // An override returning null is a deliberate wall — "nothing that way" — not a request
            // to fall back on geometry, which would make the override look intermittent.
            return false
        }

        val g = tree.graph
        val fx0 = minOf(g.leftOf(from), g.rightOf(from))
        val fx1 = maxOf(g.leftOf(from), g.rightOf(from))
        val fy0 = minOf(g.topOf(from), g.bottomOf(from))
        val fy1 = maxOf(g.topOf(from), g.bottomOf(from))

        var best: Element? = null
        var bestOverlap = -1.0
        var bestDistance = Double.MAX_VALUE

        for (candidate in focusables(scratch)) {
            if (candidate === from) continue
            val cx0 = minOf(g.leftOf(candidate), g.rightOf(candidate))
            val cx1 = maxOf(g.leftOf(candidate), g.rightOf(candidate))
            val cy0 = minOf(g.topOf(candidate), g.bottomOf(candidate))
            val cy1 = maxOf(g.topOf(candidate), g.bottomOf(candidate))

            val distance: Double
            val overlap: Double
            when (direction) {
                // Layout is y-down, so DOWN means increasing y.
                FocusDirection.DOWN -> {
                    if (cy0 < fy1 - EDGE_TOLERANCE) continue
                    distance = cy0 - fy1
                    overlap = minOf(fx1, cx1) - maxOf(fx0, cx0)
                }
                FocusDirection.UP -> {
                    if (cy1 > fy0 + EDGE_TOLERANCE) continue
                    distance = fy0 - cy1
                    overlap = minOf(fx1, cx1) - maxOf(fx0, cx0)
                }
                FocusDirection.RIGHT -> {
                    if (cx0 < fx1 - EDGE_TOLERANCE) continue
                    distance = cx0 - fx1
                    overlap = minOf(fy1, cy1) - maxOf(fy0, cy0)
                }
                FocusDirection.LEFT -> {
                    if (cx1 > fx0 + EDGE_TOLERANCE) continue
                    distance = fx0 - cx1
                    overlap = minOf(fy1, cy1) - maxOf(fy0, cy0)
                }
            }

            val better = when {
                // Any overlap beats none, however far away — a control in the same column is what
                // "down" means, even past a nearer one offset to the side.
                overlap > 0.0 && bestOverlap <= 0.0 -> true
                overlap > 0.0 && bestOverlap > 0.0 -> distance < bestDistance
                bestOverlap > 0.0 -> false
                // Neither overlaps: fall back to nearest along the direction of travel.
                else -> distance < bestDistance
            }
            if (better) {
                best = candidate
                bestOverlap = overlap
                bestDistance = distance
            }
        }

        if (best == null) return false
        focus(best)
        return true
    }

    /**
     * Activate the focused element — `onActivate`, or its click handler.
     *
     * Returns whether anything happened, so a caller can fall through to some other meaning for
     * the key when nothing is focused.
     */
    fun activate(): Boolean {
        val e = focused ?: return false
        val handler = e.onActivate ?: e.onClick ?: return false
        handler()
        return true
    }

    /** Apply a platform [FocusCommand]. Returns whether it did anything. */
    fun handle(command: FocusCommand): Boolean = when (command) {
        FocusCommand.NEXT -> next()
        FocusCommand.PREVIOUS -> previous()
        FocusCommand.UP -> move(FocusDirection.UP)
        FocusCommand.DOWN -> move(FocusDirection.DOWN)
        FocusCommand.LEFT -> move(FocusDirection.LEFT)
        FocusCommand.RIGHT -> move(FocusDirection.RIGHT)
        FocusCommand.ACTIVATE -> activate()
        FocusCommand.CANCEL -> (focused != null).also { clear() }
    }

    private companion object {
        /**
         * How much a candidate may overlap the current element and still count as "beyond" it.
         *
         * Without it, elements sharing an edge exactly — which is what `below(0.0)` produces, and
         * therefore extremely common — are rejected as not being in the direction of travel, and
         * navigation appears to stop for no visible reason.
         */
        const val EDGE_TOLERANCE = 0.5
    }
}
