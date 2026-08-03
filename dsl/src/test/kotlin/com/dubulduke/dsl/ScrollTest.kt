package com.dubulduke.dsl

import com.dubulduke.layout.ClipRect
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.NodeView
import com.dubulduke.layout.SimpleFont
import com.dubulduke.layout.Viewport
import com.dubulduke.layout.Window
import com.dubulduke.layout.bottomOf
import com.dubulduke.layout.hitTest
import com.dubulduke.layout.topOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [scrollArea] — clipping and offset, through a real [DukeApp] with real pointer dispatch.
 *
 * Driven end-to-end rather than by poking the signal, because the two bugs this feature can
 * plausibly have are both in the composition of pieces rather than in any one of them: a wheel
 * event that never reaches the area because the pointer was over a row, and a row that is invisible
 * but still answers a click. Neither shows up in a test that calls `scrollBy` directly.
 */
class ScrollTest {

    class Style {
        var colour: Int = 0
    }

    /**
     * Records the clip stack as well as the draw calls, since "what got drawn" and "what got
     * clipped" are the two things a backend observes and they can disagree.
     */
    private class Recorder : DukeApp<Style, MutableList<String>>(
        StyleFactory { Style() },
        HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0),
    ) {
        var screenWidth = 200.0
        var screenHeight = 100.0

        override val window: Window get() = Window(0.0, 0.0, screenWidth, screenHeight)
        override val viewport: Viewport get() = Viewport(0.0, 0.0, screenWidth, screenHeight)

        override val input = TestInput()

        val clips = ArrayList<ClipRect>()
        var depth = 0
        var maxDepth = 0

        override fun drawBox(node: NodeView<Style>, renderer: MutableList<String>) {
            renderer += node.element.path
        }

        override fun drawText(node: NodeView<Style>, text: String, renderer: MutableList<String>) {
            renderer += node.element.path
        }

        override fun pushClip(clip: ClipRect, renderer: MutableList<String>) {
            clips += clip
            depth++
            maxDepth = maxOf(maxDepth, depth)
        }

        override fun popClip(renderer: MutableList<String>) {
            depth--
        }

        fun drawn(): List<String> {
            val out = ArrayList<String>()
            clips.clear()
            depth = 0
            maxDepth = 0
            draw(out)
            check(depth == 0) { "unbalanced clip stack: $depth" }
            return out
        }
    }

    private val font = SimpleFont("Test", 10.0)

    /** Twenty rows of 20 units in a 100-unit-tall area: 400 units of content, 300 of overflow. */
    private fun app(rowCount: Int = 20): Pair<Recorder, ScrollScope<Style>> {
        val app = Recorder()
        lateinit var scope: ScrollScope<Style>
        app.compose {
            layout {
                left { 0.0 }; width { app.viewportWidth }
                top { 0.0 }; height { app.viewportHeight }
            }
            scrollArea(id = "area", speed = 10.0) {
                scope = this
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                content {
                    repeat(rowCount) { i ->
                        box("row$i") {
                            interactive()
                            layout { height { 20.0 } }
                        }
                    }
                }
            }
        }
        return app to scope
    }

    /** A frame with the pointer somewhere harmless and no input. */
    private fun Recorder.idle() = frame()

    // -----------------------------------------------------------------------------------
    // Offset
    // -----------------------------------------------------------------------------------

    @Test
    fun `scrolling moves the content and nothing else`() {
        val (app, scroll) = app()
        val area = app.tree.query("area")
        val row5 = app.tree.query("row5")
        assertEquals(100.0, app.graph.topOf(row5))

        scroll.scrollBy(30.0)
        app.idle()

        assertEquals(70.0, app.graph.topOf(row5), "the row moved up by the scroll amount")
        // The viewport is positioned by its container, not by the scroll — the distinction the
        // whole two-element structure exists to make.
        assertEquals(0.0, app.graph.topOf(area))
    }

    @Test
    fun `max scroll is the overflow, and it clamps at both ends`() {
        val (app, scroll) = app()
        assertEquals(300.0, scroll.maxScroll, "400 of content in 100 of viewport")

        scroll.scrollBy(1000.0)
        assertEquals(300.0, scroll.scroll.value, "cannot scroll past the end")

        scroll.scrollBy(-1000.0)
        assertEquals(0.0, scroll.scroll.value, "cannot scroll above the top")
    }

    @Test
    fun `content that fits does not scroll at all`() {
        val (app, scroll) = app(rowCount = 3)
        assertEquals(0.0, scroll.maxScroll, "60 of content in 100 of viewport")

        scroll.scrollBy(50.0)
        assertEquals(0.0, scroll.scroll.value)
        app.idle()
    }

    // -----------------------------------------------------------------------------------
    // The wheel
    // -----------------------------------------------------------------------------------

    @Test
    fun `the wheel bubbles from a row to the area containing it`() {
        val (app, scroll) = app()
        // The pointer is over row1, which is interactive and has no wheel handler of its own. If
        // dispatch did not bubble, this would scroll nothing — and the pointer is over a row
        // essentially always, so that failure would look like "scrolling is broken".
        app.frameSettled(10.0, 30.0, wheel = -1.0)
        assertEquals(10.0, scroll.scroll.value, "one notch at speed 10")

        app.frameSettled(10.0, 30.0, wheel = -3.0)
        // A tolerance, because the easing delivers a notch as a sum of decaying steps rather than
        // as one number — the total is exact in intent and not in binary floating point.
        assertEquals(40.0, scroll.scroll.value, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the wheel scrolls over the area's empty background too`() {
        val (app, scroll) = app(rowCount = 1)
        // Below the single row, so the hit is the area itself. It only answers because scrollArea
        // opts into `interactive`; without that the hit test returns null and there is nothing to
        // bubble from.
        app.frameSettled(10.0, 80.0, wheel = -1.0)
        assertEquals(0.0, scroll.scroll.value, "one row fits, so there is nothing to scroll")
        assertSame(app.tree.query("area"), app.pointer.hovered)
    }

    @Test
    fun `a wheel outside the area does nothing`() {
        val (app, scroll) = app()
        app.frameSettled(-50.0, -50.0, wheel = -5.0)
        assertEquals(0.0, scroll.scroll.value)
    }

    @Test
    fun `the inner of two nested areas takes the wheel`() {
        val app = Recorder()
        lateinit var outer: ScrollScope<Style>
        lateinit var inner: ScrollScope<Style>
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            scrollArea(id = "outer", speed = 10.0) {
                outer = this
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                content {
                    scrollArea(id = "inner", speed = 10.0) {
                        inner = this
                        layout { left { parent.left }; right { parent.right }; top { parent.top }; height { 50.0 } }
                        content { repeat(10) { box("i$it") { layout { height { 20.0 } } } } }
                    }
                    box("tall") { layout { height { 400.0 } } }
                }
            }
        }
        app.frameSettled(10.0, 20.0, wheel = -1.0)

        // One gesture moves one thing. Notifying both would scroll the row out from under the
        // pointer at twice the expected rate, which reads as a jumpy list rather than as a bug.
        assertEquals(10.0, inner.scroll.value)
        assertEquals(0.0, outer.scroll.value)
    }

    // -----------------------------------------------------------------------------------
    // Wheel easing
    //
    // A notch is spread over a fraction of a second so it does not read as a stutter. The property
    // that matters is that easing changes *when* the movement arrives and never *how much* —
    // otherwise the list lands somewhere different depending on the frame rate, which is the worst
    // kind of bug to chase because it reproduces only on other people's machines.
    // -----------------------------------------------------------------------------------

    @Test
    fun `a notch is delivered over several frames, not all at once`() {
        val (app, scroll) = app()
        app.frame(10.0, 30.0, wheel = -1.0)

        val afterOne = scroll.scroll.value
        assertTrue(afterOne > 0.0, "some of it arrived immediately")
        assertTrue(afterOne < 10.0, "but not all of it: got $afterOne of 10")

        repeat(20) { app.frame(10.0, 30.0) }
        assertTrue(scroll.scroll.value > afterOne, "it kept arriving with no further input")
    }

    @Test
    fun `the total delivered is the notch, at any frame rate`() {
        // The overshoot this pins down was real and frame-rate dependent: taking a fixed fraction
        // of the *original* notch each frame stops on whichever frame first crosses the target, so
        // it overruns by up to a frame's worth — nothing at 60fps, a third of a notch at 10fps.
        // Taking the fraction of what is left converges instead.
        for (fps in listOf(144.0, 60.0, 30.0, 10.0, 5.0)) {
            val (app, scroll) = app()
            val delta = 1.0 / fps
            app.frame(10.0, 30.0, wheel = -1.0, delta = delta)
            repeat(200) { app.frame(10.0, 30.0, delta = delta) }

            assertEquals(10.0, scroll.scroll.value, absoluteTolerance = 1e-6, message = "at $fps fps")
        }
    }

    @Test
    fun `a frame longer than the whole animation delivers the notch and no more`() {
        val (app, scroll) = app()
        // The clamp on the fraction. Without it a long frame multiplies the notch by delta/time,
        // which at 5fps would deliver more than was asked for rather than merely all of it.
        app.frame(10.0, 30.0, wheel = -1.0, delta = 10.0)
        assertEquals(10.0, scroll.scroll.value, absoluteTolerance = 1e-9)
    }

    @Test
    fun `notches arriving mid-animation add up`() {
        val (app, scroll) = app()
        app.frame(10.0, 30.0, wheel = -1.0)
        repeat(3) { app.frame(10.0, 30.0) }
        // A second notch while the first is still playing out. The pending distance is one number,
        // so this is addition rather than two competing animations.
        app.frameSettled(10.0, 30.0, wheel = -2.0)

        assertEquals(30.0, scroll.scroll.value, absoluteTolerance = 1e-6)
    }

    @Test
    fun `the wheel stops dispatching once a notch is spent`() {
        val (app, scroll) = app()
        app.frameSettled(10.0, 30.0, wheel = -1.0)
        val settled = scroll.scroll.value
        val recomputes = app.graph.stats.recomputed

        // Exponential decay never actually reaches zero, so without the epsilon snap this would
        // keep dispatching ever-smaller wheel events forever, waking the hit test on a UI nobody
        // is touching.
        repeat(50) { app.frame(10.0, 30.0) }

        assertEquals(settled, scroll.scroll.value)
        assertEquals(recomputes, app.graph.stats.recomputed, "an idle wheel is free")
    }

    // -----------------------------------------------------------------------------------
    // Hit testing
    // -----------------------------------------------------------------------------------

    @Test
    fun `a scrolled-away row is not clickable`() {
        val (app, scroll) = app()

        // row0 occupies 0..20 while unscrolled, so a press at y=10 lands on it.
        app.frame(10.0, 10.0)
        assertEquals("ui.area.[0].row0", app.pointer.hovered?.path)

        scroll.scrollBy(300.0)
        app.idle()

        // Now row0 sits at -300..-280: outside the clip, so nothing is hovered there. Without the
        // clip check in hitTest it would still answer, and clicks would land on rows that are not
        // on screen — the failure that makes clipping feel broken rather than merely unclipped.
        assertEquals(-300.0, app.graph.topOf(app.tree.query("row0")))
        app.frame(10.0, 10.0)
        assertEquals("ui.area.[0].row15", app.pointer.hovered?.path, "row15 is what is there now")
    }

    @Test
    fun `a point outside the clipping element hits nothing beneath it`() {
        val (app, _) = app()
        // Below the area entirely. The content element extends to y=400, so without the clip this
        // would hit whichever row spans y=150.
        assertNull(app.tree.hitTest(10.0, 150.0))
        assertNotNull(app.tree.hitTest(10.0, 50.0), "inside the area, it still hits")
    }

    // -----------------------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------------------

    @Test
    fun `only the visible rows are drawn`() {
        val (app, scroll) = app()
        val visible = app.drawn().filter { it.contains("row") }
        // 100 units of viewport, 20 per row.
        assertEquals(5, visible.size, "drew $visible")
        assertEquals("ui.area.[0].row0", visible.first())
        assertEquals("ui.area.[0].row4", visible.last())

        scroll.scrollBy(200.0)
        app.idle()
        val after = app.drawn().filter { it.contains("row") }
        assertEquals(5, after.size, "drew $after")
        assertEquals("ui.area.[0].row10", after.first())
    }

    @Test
    fun `the clip pushed is the area's own box, and the stack balances`() {
        val (app, _) = app()
        app.drawn()
        assertEquals(1, app.clips.size)
        assertEquals(ClipRect(0.0, 0.0, 200.0, 100.0), app.clips.single())
        assertEquals(1, app.maxDepth)
    }

    @Test
    fun `nested clips intersect`() {
        val app = Recorder()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            box("outer") {
                clip()
                layout { left { 0.0 }; right { 100.0 }; top { 0.0 }; bottom { 100.0 } }
                box("inner") {
                    clip()
                    layout { left { 50.0 }; right { 300.0 }; top { 20.0 }; bottom { 200.0 } }
                    box("leaf") { layout { left { 0.0 }; right { 10.0 }; top { 0.0 }; height { 10.0 } } }
                }
            }
        }
        app.drawn()

        assertEquals(2, app.clips.size)
        assertEquals(ClipRect(0.0, 0.0, 100.0, 100.0), app.clips[0])
        // Not the inner element's own box (50..300, 20..200) — the overlap with its parent's.
        assertEquals(ClipRect(50.0, 20.0, 100.0, 100.0), app.clips[1])
        assertEquals(2, app.maxDepth)
    }

    @Test
    fun `a clipping element outside its parent's clip skips its whole subtree`() {
        val app = Recorder()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            box("outer") {
                clip()
                layout { left { 0.0 }; right { 100.0 }; top { 0.0 }; bottom { 100.0 } }
                box("gone") {
                    clip()
                    layout { left { 500.0 }; right { 600.0 }; top { 0.0 }; bottom { 100.0 } }
                    box("beneath") { layout { fill(); } }
                }
            }
        }
        val drawn = app.drawn()

        assertTrue(drawn.none { it.contains("gone") }, "drew $drawn")
        assertTrue(drawn.none { it.contains("beneath") }, "the subtree went with it")
        // Only the outer clip was pushed: the empty one short-circuits before it reaches the
        // backend, so a backend never sees a degenerate rectangle.
        assertEquals(1, app.clips.size)
    }

    @Test
    fun `an unclipped child outside its parent is still drawn`() {
        val app = Recorder()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            box("parent") {
                // No clip(). Nothing constrains a child to its parent's box, so culling the
                // subtree here would be wrong — the asymmetry `draw` documents.
                layout { left { 0.0 }; right { 10.0 }; top { 0.0 }; bottom { 10.0 } }
                box("escapee") {
                    layout { left { 50.0 }; right { 150.0 }; top { 50.0 }; bottom { 90.0 } }
                }
            }
        }
        val drawn = app.drawn()
        assertTrue(drawn.any { it.contains("escapee") }, "drew $drawn")
    }

    // -----------------------------------------------------------------------------------
    // Shrinking content
    // -----------------------------------------------------------------------------------

    @Test
    fun `removing rows re-clamps the scroll position on the next frame`() {
        val app = Recorder()
        var rows = (0 until 20).toList()
        lateinit var scroll: ScrollScope<Style>
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            scrollArea(id = "area", speed = 10.0) {
                scroll = this
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                content {
                    forEach({ rows }, key = { it }) { layout { height { 20.0 } } }
                }
            }
        }
        scroll.scrollBy(300.0)
        assertEquals(300.0, scroll.scroll.value)

        // Down to 6 rows: 120 of content, 20 of overflow. The stored 300 is now illegal, and
        // nothing touched the wheel to notice.
        rows = (0 until 6).toList()
        app.idle()

        assertEquals(20.0, scroll.maxScroll)
        assertEquals(20.0, scroll.scroll.value, "the per-frame clamp caught it")
    }

    @Test
    fun `the clamp sees this frame's rows, not last frame's`() {
        val app = Recorder()
        var rows = (0 until 20).toList()
        lateinit var scroll: ScrollScope<Style>
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            scrollArea(id = "area", speed = 10.0) {
                scroll = this
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                content {
                    forEach({ rows }, key = { it }) { layout { height { 20.0 } } }
                }
            }
        }
        scroll.scrollBy(300.0)
        rows = emptyList()

        // One frame, not two. The clamp region registers after the body, so the forEach inside it
        // reconciles first and the clamp measures what that produced.
        app.idle()
        assertEquals(0.0, scroll.scroll.value)
    }

    // -----------------------------------------------------------------------------------
    // Misuse
    // -----------------------------------------------------------------------------------

    @Test
    fun `a scrollArea without content is a named error`() {
        val app = Recorder()
        val e = assertFailsWith<IllegalStateException> {
            app.compose {
                layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
                scrollArea(id = "area") {
                    layout { fill() }
                }
            }
        }
        assertTrue("content" in e.message!!, e.message!!)
    }

    @Test
    fun `two content blocks are a named error`() {
        val app = Recorder()
        val e = assertFailsWith<IllegalStateException> {
            app.compose {
                layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
                scrollArea(id = "area") {
                    layout { fill() }
                    content { }
                    content { }
                }
            }
        }
        assertTrue("one" in e.message!!, e.message!!)
    }

    // -----------------------------------------------------------------------------------
    // Cost
    // -----------------------------------------------------------------------------------

    @Test
    fun `an idle frame over a scroll area recomputes nothing`() {
        val (app, _) = app()
        app.idle()
        app.drawn()
        val before = app.graph.stats.recomputed

        repeat(20) { app.idle(); app.drawn() }

        assertEquals(before, app.graph.stats.recomputed, "a still scroll area is free")
    }

    @Test
    fun `scrolling invalidates the content, not the viewport or its siblings`() {
        val app = Recorder()
        lateinit var scroll: ScrollScope<Style>
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            box("sibling") { layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 10.0 } } }
            scrollArea(id = "area", speed = 10.0) {
                scroll = this
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                content { repeat(20) { box("r$it") { layout { height { 20.0 } } } } }
            }
        }
        app.drawn()
        val sibling = app.tree.query("sibling")
        val before = app.graph.stats.recomputed

        scroll.scrollBy(20.0)
        app.idle()
        app.drawn()

        assertTrue(app.graph.stats.recomputed > before, "the content did move")
        // The sibling's geometry does not depend on the scroll, so re-reading it must be free.
        val settled = app.graph.stats.recomputed
        app.graph.bottomOf(sibling)
        assertEquals(settled, app.graph.stats.recomputed)
    }
}
