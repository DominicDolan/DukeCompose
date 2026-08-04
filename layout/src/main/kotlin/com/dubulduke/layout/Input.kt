package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.Slot
import com.dubulduke.reactivity.nodeName

/**
 * Pointer state and event dispatch. `dsl-and-engine.md` §7.
 *
 * ### Why this is not a cycle
 *
 * Hit testing needs resolved geometry, so it follows layout. But hover can *affect* layout — a
 * button that grows, a tooltip that appears — which reads as `layout → hit test → hover → layout`,
 * with a threshold discontinuity in it.
 *
 * The resolution is to hit-test against the **previous frame's** geometry, and that is not a
 * compromise: one frame of lag is already inherent, because input is sampled at some instant and
 * by the time anything is drawn the pointer has moved. There is no "correct current hover"
 * available to be given up. With that, the frame is acyclic:
 *
 * ```
 * 1. sample input                                   [platform]
 * 2. hit test against the last frame's geometry  → hover / press
 * 3. dispatch handlers                           → mutates application state
 * 4. compose                                     (or not — see MechanicaUI)
 * 5. layout                                      (reads hover from step 2)
 * 6. draw
 * ```
 *
 * Step 5 reads step 2's output; step 2 read the previous frame's step 6. No cycle anywhere.
 *
 * ### Hover is an input, not a computation
 *
 * The consequence worth stating plainly: hover and press are **graph inputs**, written once per
 * frame, invalidating whatever reads them — exactly like viewport size or text content. They are
 * therefore not a cycle the evaluator has to resolve. **No governor is involved**, and events need
 * no special case in `:reactivity` at all.
 */

/**
 * The per-element interaction state, allocated only for elements that ask for it.
 *
 * Two scalar inputs rather than one enum-ish node, because they are independently observable: a
 * style reading only `hovered` must not invalidate when the button is pressed.
 */
class Interaction internal constructor(
    // No @JvmField: a value-class-typed property cannot have one, and Slot is a value class over
    // an Int. Same reason as FlexItem's four slots.
    internal val hoveredSlot: Slot,
    internal val pressedSlot: Slot,
)

/**
 * Make this element hit-testable, and give it hover and press state.
 *
 * Elements are *not* interactive by default: a hit test walks the whole tree, and a UI is mostly
 * decoration that should never swallow a click meant for the thing behind it. Opting in is also
 * what keeps [hitTest] cheap to reason about — the topmost *interactive* element wins, so there is
 * no need for the notion of a "transparent" element.
 */
fun ElementBuilder.interactive(): Interaction {
    element.interaction?.let { return it }
    val g = tree.graph
    val i = Interaction(
        hoveredSlot = g.input(0.0, nodeName { "${element.path}.hovered" }),
        pressedSlot = g.input(0.0, nodeName { "${element.path}.pressed" }),
    )
    element.interaction = i
    return i
}

/**
 * Register a click handler, making this element interactive.
 *
 * **Retained, not polled.** Iteration B had `if (isHovering && mouse.MB1.hasBeenPressed) onClick()`
 * — good ergonomics, no listener storage — but a polled check only fires if the block containing
 * it runs, so polled events silently require per-frame composition. Under compose-once no click
 * would ever be noticed. A handler registered during composition is correct under either policy,
 * and costs one field write per element (`dsl-and-engine.md` §7.4).
 *
 * Fires on *release over the same element that was pressed*, which is what makes a press-then-drag-
 * away not a click.
 */
fun ElementBuilder.onClick(handler: () -> Unit) {
    interactive()
    element.onClick = handler
}

/**
 * Register a wheel handler. The argument is the wheel delta, positive for scrolling up.
 *
 * **Does not make the element interactive**, unlike [onClick], and the difference is the point.
 * Wheel events bubble (see [Element.onScroll]), so the handler fires for the pointer being over
 * anything *inside* this element — which is the normal case, since the pointer is over a row far
 * more often than over the list. A scroll container still usually wants [interactive] as well, so
 * that the wheel works over its empty background too, but that is its choice rather than implied.
 */
fun ElementBuilder.onScroll(handler: (Double) -> Unit) {
    element.onScroll = handler
}

/**
 * Register a drag handler, making this element interactive and **capturing the pointer** while it
 * is held. The arguments are the pointer position in layout coordinates.
 *
 * Capture is what separates a drag from a sequence of clicks, and it is not a nicety: a slider
 * thumb is a small box, the pointer leaves it almost immediately once you start moving, and
 * without capture the drag stops the moment it does. So while the button is down, this element
 * receives the pointer wherever it goes — outside its own box, over other elements, outside the
 * window — and no other element sees hover.
 *
 * Fires every frame the button is held, starting with the frame of the press, and stops on release.
 * It does **not** suppress [onClick]: a press and release without movement is still a click, which
 * is what lets one element be both draggable and clickable without the two fighting.
 */
fun ElementBuilder.onDrag(handler: (x: Double, y: Double) -> Unit) {
    interactive()
    element.onDrag = handler
}

/** True while the pointer is over this element. A tracked read — use it in layout or style. */
fun Graph.isHovered(e: Element): Boolean =
    e.interaction?.let { read(it.hoveredSlot) != 0.0 } ?: false

/** True while the pointer is held down on this element. A tracked read. */
fun Graph.isPressed(e: Element): Boolean =
    e.interaction?.let { read(it.pressedSlot) != 0.0 } ?: false

/**
 * The topmost interactive element containing a point, in **layout** coordinates, or `null`.
 *
 * Reverse painter's order — later siblings above earlier, children above parents — so the first
 * hit is the visually topmost, which is the reverse of the order [draw] walks in.
 *
 * No early rejection on parent bounds, deliberately. It is the obvious optimisation and it would
 * be wrong here: nothing constrains a child to its parent's box, so a child positioned outside one
 * is ordinary rather than pathological. O(n) per frame, and n is the element count, not the node
 * count — at the sizes where that matters the read walk costs far more.
 *
 * A **clipping** element is the one case where that rejection is sound, because its subtree cannot
 * escape it by construction. Nested clips then need no rectangle threaded through the walk: one
 * early return per clipping ancestor is exactly the intersection of all of them, which is the same
 * observation [draw] makes and a cheaper way to spend it.
 *
 * Untracked, like every other read outside the graph.
 */
fun ElementTree.hitTest(x: Double, y: Double, from: Element = root): Element? {
    // Outside a clip, nothing beneath it is visible, so nothing beneath it is clickable. Without
    // this a scrolled-away row still answers the pointer — clicks landing on rows that are not on
    // screen, which is the bug that makes clipping feel broken rather than merely unclipped.
    if (from.clips && !contains(from, x, y)) return null

    val children = from.children
    for (i in children.indices.reversed()) {
        hitTest(x, y, children[i])?.let { return it }
    }
    if (from.interaction != null && contains(from, x, y)) return from
    return null
}

/** Whether a point in layout coordinates falls inside this element's box. */
fun ElementTree.contains(e: Element, x: Double, y: Double): Boolean {
    val x0 = graph.originOf(e.h)
    val x1 = x0 + graph.sizeOf(e.h)
    val y0 = graph.originOf(e.v)
    val y1 = y0 + graph.sizeOf(e.v)
    return x >= minOf(x0, x1) && x <= maxOf(x0, x1) &&
        y >= minOf(y0, y1) && y <= maxOf(y0, y1)
}

/**
 * Drives [Interaction] state from a pointer, once per frame.
 *
 * Holds the small amount of state that events genuinely need to be *retained* rather than derived:
 * which element is hovered, and which was pressed. Everything else is in the graph.
 */
class Pointer(private val tree: ElementTree) {

    init {
        // Forget an element the instant it dies, rather than noticing next frame.
        //
        // All three of these are references the pointer holds *across* frames, and reconciliation
        // runs between them — a list row removed by its own handler is the standing case, and it is
        // the usual one rather than an edge case, because the thing you interact with is often the
        // thing that goes. Polling `disposed` at the top of `update` leaves a whole frame in which
        // these point at dead elements, and `clear()` could be called inside it: it writes to
        // `interaction` slots, so a stale handle would reach `setInput` and fail there instead of
        // here, naming a graph node rather than the removal that caused it.
        //
        // Nulling rather than dispatching anything. There is no "drag cancelled" event to send,
        // and inventing one would mean calling application code from the middle of a structural
        // edit, which is precisely what `onDispose` forbids.
        tree.onDispose { e ->
            if (hovered === e) hovered = null
            if (pressed === e) pressed = null
            if (captured === e) captured = null
        }
    }

    /** The element under the pointer as of the last [update], or `null`. */
    var hovered: Element? = null
        private set

    /** The element the pointer went down on and has not yet been released over. */
    var pressed: Element? = null
        private set

    /**
     * The element holding the pointer for the duration of a drag, or `null`.
     *
     * Set on press when the pressed element has an `onDrag` handler, cleared on release. While it
     * is non-null the hit test does not run and this element answers as though it were under the
     * cursor — see [update].
     */
    var captured: Element? = null
        private set

    /** Where the pointer went down, in layout coordinates. Meaningless while nothing is pressed. */
    var pressX: Double = 0.0
        private set

    /**
     * Where the pointer went down, in layout coordinates.
     *
     * Exposed because the grab offset is the one thing a drag handler cannot reconstruct: by the
     * time it wants to know where inside the thumb you grabbed, the thumb has already moved.
     */
    var pressY: Double = 0.0
        private set

    private var wasDown = false

    /**
     * Consecutive frames on which [hovered] changed. See [oscillationThreshold].
     *
     * `dsl-and-engine.md` §7.3: previous-frame hit testing breaks the within-frame cycle but not
     * an across-frame limit cycle. Grow-on-hover is *bistable* and desirable — two stable fixed
     * points, history picks one, which is hysteresis for free. Shrink-on-hover **oscillates**:
     * hover → shrink → miss → grow → hover, visible flicker at frame rate. It is a real bug class
     * and CSS has the same one, so it is worth naming rather than leaving to be discovered.
     */
    private var flips = 0
    private var warned = false

    /** Consecutive hover changes before [onOscillation] fires. Zero disables the check. */
    var oscillationThreshold: Int = 20

    /**
     * Where the oscillation warning goes.
     *
     * Deliberately not `Graph.diagnostics`: that sink reports dependency cycles and nothing else,
     * and this is not one — the graph is perfectly happy, it is the *pointer feedback* across
     * frames that is unstable. Widening the reactivity sink for a layout-level concern would put
     * `:layout` vocabulary in a module that does not know what an element is.
     */
    var onOscillation: (String) -> Unit = { System.err.println(it) }

    /**
     * Hit-test in **window** coordinates, update the graph, and dispatch.
     *
     * The point is converted into layout space once, rather than every element's box being
     * converted into window space, which is both cheaper and keeps the y-flip in exactly one place
     * per direction.
     */
    fun update(
        host: DukeHost,
        windowX: Double,
        windowY: Double,
        down: Boolean,
        wheel: Double = 0.0,
    ) {
        val w = host.window
        val v = host.viewport
        require(v.width != 0.0 && v.height != 0.0) { "the viewport has a zero extent ($v)" }
        val widthScale = w.width / v.width
        val heightScale = w.height / v.height
        val xAdjust = w.x - widthScale * v.x + if (widthScale < 0.0) w.width else 0.0
        val yAdjust = w.y - heightScale * v.y + if (heightScale < 0.0) w.height else 0.0
        update((windowX - xAdjust) / widthScale, (windowY - yAdjust) / heightScale, down, wheel)
    }

    /**
     * Hit-test, update the graph, and dispatch. [x] and [y] are in **layout** coordinates.
     *
     * Writing an unchanged input is free — the graph compares before it marks — so the common case
     * of a still pointer over a still UI costs one hit test and no invalidation at all.
     */
    fun update(x: Double, y: Double, down: Boolean, wheel: Double = 0.0) {
        val g = tree.graph

        // Nothing to clear here: `hovered`, `pressed` and `captured` are dropped the moment the
        // element is disposed, by the listener registered in `init`.

        // While the pointer is captured there is no hit test — that is what capture *means*. The
        // captured element stands in for whatever is under the cursor, so hover does not wander
        // onto things the drag passes over and the release still counts as a click on the thing
        // that was grabbed. Skipping the walk is a consequence rather than the point, though it
        // does make a drag the cheapest frame the pointer has.
        val hit = if (captured != null) captured else tree.hitTest(x, y)

        if (hit !== hovered) {
            hovered?.interaction?.let { g.setInput(it.hoveredSlot, 0.0) }
            hit?.interaction?.let { g.setInput(it.hoveredSlot, 1.0) }
            val from = hovered
            hovered = hit
            noteFlip(from, hit)
        } else {
            flips = 0
        }

        if (down && !wasDown) {
            pressed = hit
            pressX = x
            pressY = y
            hit?.interaction?.let { g.setInput(it.pressedSlot, 1.0) }
            // Capture on press, not on first movement. Waiting for movement would drop the frame
            // the gesture started on, and a slider grabbed and nudged within one frame would jump
            // rather than track.
            if (hit?.onDrag != null) captured = hit
        } else if (!down && wasDown) {
            val target = pressed
            target?.interaction?.let { g.setInput(it.pressedSlot, 0.0) }
            pressed = null
            captured = null
            // A click is a press and a release on the *same* element, so dragging off a button
            // and letting go is not a click. Under capture `hit` *is* the captured element, so a
            // drag that ends outside the thumb still reads as a click on it — deliberate: it is
            // what makes an element able to be both draggable and clickable.
            if (target != null && target === hit) target.onClick?.invoke()
        }
        wasDown = down

        // After the press branch, so the frame of the press already drags. Before the wheel, for
        // no reason beyond determinism — nothing sensible does both at once.
        if (down) captured?.onDrag?.invoke(x, y)

        if (wheel != 0.0) dispatchWheel(hit, wheel)
    }

    /**
     * Walk up from the hit element to the first ancestor that handles the wheel.
     *
     * Stops at the first handler rather than notifying all of them, so a scroll area nested in
     * another scrolls the inner one and leaves the outer alone — which is what every scrolling UI
     * does, and the reason is that the alternative moves two things at once for one gesture.
     *
     * Starts from the hit rather than from [hovered] so that a wheel event arriving on the same
     * frame the pointer moved uses this frame's target. They are the same object here; naming the
     * local makes it not depend on the assignment order above.
     */
    private fun dispatchWheel(hit: Element?, wheel: Double) {
        var e = hit
        while (e != null) {
            val handler = e.onScroll
            if (handler != null) {
                handler(wheel)
                return
            }
            e = e.parent
        }
    }

    private fun noteFlip(from: Element?, to: Element?) {
        if (oscillationThreshold <= 0) return
        flips++
        if (flips >= oscillationThreshold && !warned) {
            warned = true
            // Name *both* ends. Half of an oscillation is "nothing is hovered", so reporting only
            // the current state names `null` half the time and never names the culprit.
            val a = from?.path ?: "nothing"
            val b = to?.path ?: "nothing"
            onOscillation(
                "hover has changed on $flips consecutive frames, alternating between $a and $b. " +
                    "This is the shrink-on-hover limit cycle of dsl-and-engine.md §7.3: an " +
                    "element that gets smaller when hovered moves out from under the pointer, " +
                    "then back, at frame rate. Add exit hysteresis, or stop deriving a size from " +
                    "hover. Reported once."
            )
        }
    }

    /**
     * Forget hover, press and capture without dispatching — for when the UI loses focus.
     *
     * Releasing capture here matters more than the rest: a window that loses focus mid-drag never
     * delivers the mouse-up, so without this the element would still be captured when focus
     * returned and the pointer would appear stuck to it.
     */
    fun clear() {
        val g = tree.graph
        hovered?.interaction?.let { g.setInput(it.hoveredSlot, 0.0) }
        pressed?.interaction?.let { g.setInput(it.pressedSlot, 0.0) }
        hovered = null
        pressed = null
        captured = null
        wasDown = false
        flips = 0
    }
}
