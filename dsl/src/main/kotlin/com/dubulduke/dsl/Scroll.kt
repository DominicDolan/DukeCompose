package com.dubulduke.dsl

import com.dubulduke.layout.Element
import com.dubulduke.layout.Marker
import com.dubulduke.layout.Region
import com.dubulduke.layout.bottomOf
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.onReveal
import com.dubulduke.layout.topOf
import com.dubulduke.layout.widthOf
import com.dubulduke.reactivity.nodeName

/**
 * Scrolling: a clipped viewport over content that is taller than it is.
 *
 * ```kotlin
 * scrollArea(id = "inbox") {
 *     layout { fillWidth(12.0); top { 16.0 }; height { 400.0 } }
 *     style { color.set(PANEL) }
 *
 *     content(gap = 8.0) {
 *         forEach({ rows }, key = { it.id }) { item -> … }
 *     }
 * }
 * ```
 *
 * ### There is no scrolling mechanism
 *
 * That is the point worth stating before any of the detail. Scrolling is **two things this design
 * already had**: an element that clips its subtree, and a content origin that reads a signal. No
 * transform stack, no scroll-specific path through layout, and nothing in `:reactivity` or the
 * evaluator knows this file exists. A scrolled row's slots resolve to exactly the numbers they
 * would have without the scroll — offset by the signal, like any other expression reading any
 * other input.
 *
 * The reason that falls out rather than being arranged is `layout-design.md` §4.4: a slot holds an
 * *expression*, so "everything below here moves by −n" is an ordinary read rather than a property
 * of a container. Retained-mode UIs that store resolved rectangles have to add scrolling as a
 * traversal-time transform precisely because they cannot say that.
 *
 * ### The clamp is the only interesting part
 *
 * The scroll offset must not exceed `content height − viewport height`, and that quantity lives in
 * the graph. Writing the clamp *into the offset expression* is the obvious move and it is a genuine
 * cycle: content height depends on the children, whose positions depend on the content origin,
 * which would then depend on the content height.
 *
 * So the clamp happens **where the value is written**, reading the previous frame's geometry
 * untracked. That is the same resolution as previous-frame hit testing (`dsl-and-engine.md` §7.1)
 * and it is sound for the same reason: input is sampled at an instant that is already past, so
 * there is no "correct current content height" being given up.
 *
 * Content can also shrink without the wheel being touched — deleting the last row while scrolled to
 * the bottom — so the clamp additionally runs once per frame as a [Region]. Regions exist for
 * reconciliation, but the phase is exactly right: after input, before anything measures. It
 * registers *after* the body, so any `forEach` inside this area reconciles first and the clamp sees
 * the structure it produced rather than the previous frame's.
 */
fun <S : Any> ElementScope<S>.scrollArea(
    id: String? = null,
    speed: Double = 40.0,
    body: ScrollScope<S>.() -> Unit,
): Element = child(id) {
    clip()
    // So the wheel works over the area's empty background, not only over its content. `onScroll`
    // alone would not: with nothing interactive under the pointer, the hit test returns null and
    // there is no element to bubble up from.
    interactive()

    val scope = ScrollScope(this, graph.signal(0.0, nodeName { "${element.path}.scroll" }), speed)
    onScroll { delta -> scope.scrollBy(-delta * speed) }
    // Tab into a long list and the ring would otherwise land somewhere off-screen, which looks
    // exactly like focus being broken. Focus walks up asking every ancestor that can; this is a
    // scroll area saying it can.
    onReveal { target -> scope.reveal(target) }
    scope.body()
    scope.finish()
}

/**
 * The authoring surface for a [scrollArea]: the viewport's own configuration, plus [content].
 *
 * Two elements are involved and they want different things said about them — the viewport is
 * positioned by its container and clips; the content is positioned by the scroll offset and is as
 * tall as its children. Rather than expose both scopes and let the wrong one be configured by
 * accident, `layout` and `style` here mean the *viewport*, which is the one the caller placed, and
 * everything about the content goes inside [content].
 */
@DukeDsl
class ScrollScope<S : Any> internal constructor(
    private val viewport: ElementScope<S>,
    /**
     * How far the content is scrolled, in layout units, always `>= 0`.
     *
     * Public so the application can drive it — scroll-to-top on a filter change, or an animation
     * reading it. Written directly it is *not* clamped until the next frame's [Region] pass.
     */
    val scroll: Signal,
    private val speed: Double,
) {
    private var contentElement: Element? = null

    /** The viewport element — the clipped box, not the content inside it. */
    val element: Element get() = viewport.element

    val graph get() = viewport.graph
    val tree get() = viewport.tree

    /** Position and size the *viewport*. Its content is sized by [content]. */
    fun layout(block: LayoutScope.() -> Unit) = viewport.layout(block)

    /** Style the *viewport* — its background and border. */
    fun style(block: S.() -> Unit) = viewport.style(block)

    fun classes(vararg names: String) = viewport.classes(*names)

    fun kind(kind: String) = viewport.kind(kind)


    /** Read an ambient in the scroll area's own context. See [ElementScope.ambient]. */
    fun <T> ambient(key: Ambient<T>): T = viewport.ambient(key)

    /** Tag the viewport itself, for descendants' ancestor queries. */
    fun marker(marker: Marker) = viewport.marker(marker)

    /** Whether any ancestor of the viewport carries [marker]. */
    fun hasAncestor(marker: Marker): Boolean = viewport.hasAncestor(marker)

    /** Click the viewport's background. Rows inside register their own. */
    fun onClick(handler: () -> Unit) = viewport.onClick(handler)

    /**
     * The scrollable content: a column of children, offset by [scroll] and as tall as they are.
     *
     * Its height is where the shrink-wrap happens, so `maxScroll` has something to measure. A
     * caller that wants a different arrangement inside can still set `layout { }` on it — only
     * `top` and `height` are load-bearing here.
     */
    fun content(
        gap: Double = 0.0,
        id: String? = null,
        body: ElementScope<S>.() -> Unit,
    ): Element {
        check(contentElement == null) { "$element already has content; a scrollArea has one" }
        // Captured rather than reached through `this@ScrollScope`: inside `layout { }` the receiver
        // is a LayoutScope and @DukeDsl blocks the outer one, which is the marker earning its keep
        // rather than getting in the way.
        val offset = scroll
        return viewport.child(id) {
            stackDown(gap)
            layout {
                left { parent.left }
                right { parent.right }
                // The whole of scrolling, in one expression.
                top { parent.top - offset.value }
                height { if (children.isEmpty) 0.0 else children.last.bottom - top }
            }
            body()
        }.also { contentElement = it }
    }

    /**
     * How far this area can scroll: the content's overflow, or zero when it all fits.
     *
     * Reads geometry **untracked** — this is called from an event handler and from the per-frame
     * clamp, both of which sit outside the graph. Calling it inside a layout expression would
     * record the dependency that makes the clamp a cycle.
     */
    val maxScroll: Double
        get() {
            val c = contentElement ?: return 0.0
            if (c.disposed) return 0.0
            return maxOf(0.0, graph.heightOf(c) - graph.heightOf(viewport.element))
        }

    /** The visible fraction of the content, `1.0` when everything fits. For a scrollbar thumb. */
    val visibleFraction: Double
        get() {
            val c = contentElement ?: return 1.0
            val content = graph.heightOf(c)
            if (content <= 0.0) return 1.0
            return minOf(1.0, graph.heightOf(viewport.element) / content)
        }

    /** Scroll by [delta] layout units, clamped. Positive moves the content up — later items. */
    fun scrollBy(delta: Double) {
        scroll.value = (scroll.value + delta).coerceIn(0.0, maxScroll)
    }

    /**
     * Scroll the least amount that brings [target] fully into view. Does nothing if it already is.
     *
     * *Least* amount, and that is the behaviour worth being deliberate about: centring the target
     * instead would move the list under a user who pressed down once and expected one row of
     * movement. Scrolling only when something is actually off-screen is what makes keyboard
     * navigation through a list feel like the list is holding still.
     *
     * Reads geometry untracked, from an event handler, like [maxScroll] — see this file's header
     * for why that is sound rather than a shortcut.
     */
    fun reveal(target: Element) {
        if (target.disposed || contentElement == null) return
        val g = graph
        val viewTop = g.topOf(viewport.element)
        val viewBottom = g.bottomOf(viewport.element)
        val targetTop = g.topOf(target)
        val targetBottom = g.bottomOf(target)

        // A target taller than the viewport can only have one edge aligned; the top is the useful
        // one, since that is where a control's label is.
        val delta = when {
            targetTop < viewTop -> targetTop - viewTop
            targetBottom > viewBottom -> minOf(targetBottom - viewBottom, targetTop - viewTop)
            else -> return
        }
        scrollBy(delta)
    }

    fun scrollToTop() {
        scroll.value = 0.0
    }

    fun scrollToBottom() {
        scroll.value = maxScroll
    }

    internal fun finish() {
        check(contentElement != null) {
            "$element is a scrollArea with no content { } block; it would clip an empty box"
        }
        // Re-clamp once a frame, for the content shrinking under a scroll position that was legal
        // when it was set — deleting the last row while scrolled to the bottom is the standing
        // case. Returns false because nothing structural changed; `reconcile()` counts rebuilds.
        tree.register(object : Region {
            override val container: Element get() = viewport.element

            override fun reconcile(): Boolean {
                val max = maxScroll
                if (scroll.value > max) scroll.value = max
                return false
            }
        })
    }
}
