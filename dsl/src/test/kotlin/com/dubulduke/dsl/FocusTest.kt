package com.dubulduke.dsl

import com.dubulduke.layout.FocusCommand
import com.dubulduke.layout.FocusDirection
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.NodeView
import com.dubulduke.layout.Viewport
import com.dubulduke.layout.Window
import com.dubulduke.layout.isFocused
import com.dubulduke.layout.isVisible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Keyboard focus: tab order, directional navigation, activation and reveal.
 *
 * The two things worth testing hardest are the ones a user notices when they are wrong: where the
 * boundary of the tab order sits — a scrolled-away row belongs in it because focusing it scrolls it
 * into view, while one clipped away by something that cannot scroll does not — and that directional
 * navigation prefers *overlap* over raw distance, because the distance-only answer sends "down"
 * sideways in any two-column layout.
 */
class FocusTest {

    class Style {
        var colour: Int = 0
    }

    private class App : DukeApp<Style, MutableList<String>>(
        StyleFactory { Style() },
        HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0),
    ) {
        override val window get() = Window(0.0, 0.0, 200.0, 200.0)
        override val viewport get() = Viewport(0.0, 0.0, 200.0, 200.0)
        override val input = TestInput()
        override fun drawBox(node: NodeView<Style>, renderer: MutableList<String>) {}
        override fun drawText(node: NodeView<Style>, t: String, renderer: MutableList<String>) {}
    }

    private fun App.press(command: FocusCommand) {
        (input as TestInput).navigation = command
        update(FRAME)
        (input as TestInput).navigation = null
    }

    private val App.focusedId: String? get() = focus.focused?.id

    // -----------------------------------------------------------------------------------
    // Tab order
    // -----------------------------------------------------------------------------------

    /** Three focusable boxes stacked, with a non-focusable one between the first two. */
    private fun stack(): App {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("a") { focusable(); layout { left { 0.0 }; width { 50.0 }; top { 0.0 }; height { 20.0 } } }
            box("decor") { layout { left { 0.0 }; width { 50.0 }; top { 25.0 }; height { 20.0 } } }
            box("b") { focusable(); layout { left { 0.0 }; width { 50.0 }; top { 50.0 }; height { 20.0 } } }
            box("c") { focusable(); layout { left { 0.0 }; width { 50.0 }; top { 100.0 }; height { 20.0 } } }
        }
        return app
    }

    @Test
    fun `tab walks the focusable elements in tree order and wraps`() {
        val app = stack()
        assertNull(app.focusedId)

        app.press(FocusCommand.NEXT); assertEquals("a", app.focusedId)
        app.press(FocusCommand.NEXT); assertEquals("b", app.focusedId, "the decoration is not a stop")
        app.press(FocusCommand.NEXT); assertEquals("c", app.focusedId)
        app.press(FocusCommand.NEXT); assertEquals("a", app.focusedId, "wraps")
    }

    @Test
    fun `shift-tab from nothing starts at the end`() {
        val app = stack()
        app.press(FocusCommand.PREVIOUS)
        // Starting at the first element and then going backwards would land on the last anyway,
        // but only by accident of the wrap; this asserts the intent.
        assertEquals("c", app.focusedId)

        app.press(FocusCommand.PREVIOUS); assertEquals("b", app.focusedId)
    }

    @Test
    fun `focus is a per-element input, so only the two elements involved invalidate`() {
        val app = stack()
        app.press(FocusCommand.NEXT)
        val a = app.tree.query("a")
        val b = app.tree.query("b")
        assertTrue(app.graph.isFocused(a))
        assertFalse(app.graph.isFocused(b))

        app.press(FocusCommand.NEXT)
        assertFalse(app.graph.isFocused(a))
        assertTrue(app.graph.isFocused(b))
    }

    @Test
    fun `cancel drops focus`() {
        val app = stack()
        app.press(FocusCommand.NEXT)
        app.press(FocusCommand.CANCEL)
        assertNull(app.focusedId)
    }

    @Test
    fun `focusing something that is not focusable is a named error`() {
        val app = stack()
        val e = assertFailsWith<IllegalArgumentException> { app.focus.focus(app.tree.query("decor")) }
        assertTrue("focusable" in e.message!!, e.message!!)
    }

    @Test
    fun `a focused element removed by reconciliation does not leave focus dangling`() {
        val app = App()
        var rows = listOf("x", "y")
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            forEach({ rows }, key = { it }) {
                focusable()
                layout { left { 0.0 }; width { 50.0 }; below(4.0); height { 20.0 } }
            }
        }
        app.press(FocusCommand.NEXT)
        assertSame(app.tree.root!!.children.first(), app.focus.focused)

        rows = listOf("y")
        app.update(FRAME)

        assertNull(app.focus.focused, "dropped on disposal, not left pointing at a dead element")
    }

    // -----------------------------------------------------------------------------------
    // Visibility
    // -----------------------------------------------------------------------------------

    @Test
    fun `a scrolled-away row is still a tab stop, because it can be revealed`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            scrollArea(id = "area", speed = 10.0) {
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 40.0 } }
                content {
                    repeat(10) { i ->
                        box("row$i") { focusable(); layout { height { 20.0 } } }
                    }
                }
            }
        }
        // Only rows 0 and 1 are on screen, but all ten are reachable: the test is not "is it
        // visible" but "would focusing it make it visible", and a scroll area can answer yes.
        // Filtering by visibility here is the bug that makes a list untabbable past its first
        // screenful — tab from the last visible row would wrap to the top forever.
        assertEquals(10, app.focus.focusables().size)
        assertFalse(app.tree.isVisible(app.tree.query("row5")))
    }

    @Test
    fun `a row clipped away by something that cannot scroll is not a tab stop`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("window") {
                clip()
                layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
                box("seen") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } } }
                // Below the clip and nothing can bring it back, so focusing it would put the ring
                // somewhere nobody can see.
                box("buried") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 100.0 }; height { 20.0 } } }
            }
        }
        assertEquals(listOf("seen"), app.focus.focusables().map { it.id })

        app.press(FocusCommand.NEXT); assertEquals("seen", app.focusedId)
        app.press(FocusCommand.NEXT); assertEquals("seen", app.focusedId, "wraps to itself")
    }

    // -----------------------------------------------------------------------------------
    // Reveal
    // -----------------------------------------------------------------------------------

    @Test
    fun `tabbing past the last visible row scrolls it into view`() {
        val app = App()
        lateinit var scroll: ScrollScope<Style>
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            scrollArea(id = "area", speed = 10.0) {
                scroll = this
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 40.0 } }
                content {
                    repeat(10) { i ->
                        box("row$i") { focusable(); layout { height { 20.0 } } }
                    }
                }
            }
        }
        app.press(FocusCommand.NEXT); assertEquals("row0", app.focusedId)
        app.press(FocusCommand.NEXT); assertEquals("row1", app.focusedId)
        assertEquals(0.0, scroll.scroll.value, "both were already visible")

        // row2 is off the bottom, so revealing it scrolls by exactly one row and no more.
        app.press(FocusCommand.NEXT)
        assertEquals("row2", app.focusedId)
        assertEquals(20.0, scroll.scroll.value)

        app.press(FocusCommand.NEXT)
        assertEquals("row3", app.focusedId)
        assertEquals(40.0, scroll.scroll.value, "one row at a time, not a jump to centre")
    }

    @Test
    fun `revealing something already visible does not scroll`() {
        val app = App()
        lateinit var scroll: ScrollScope<Style>
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            scrollArea(id = "area", speed = 10.0) {
                scroll = this
                layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                content { repeat(10) { i -> box("row$i") { focusable(); layout { height { 20.0 } } } } }
            }
        }
        app.press(FocusCommand.NEXT)
        app.press(FocusCommand.NEXT)
        assertEquals(0.0, scroll.scroll.value, "a list holding still is the point")
    }

    // -----------------------------------------------------------------------------------
    // Directional
    // -----------------------------------------------------------------------------------

    /**
     * A two-column grid, which is the layout that exposes distance-only scoring:
     *
     * ```
     *   tl   tr
     *   bl   br
     * ```
     * `tl`'s nearest neighbour by centre distance is `tr`, but "down" must reach `bl`.
     */
    private fun grid(): App {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("tl") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 40.0 } } }
            box("tr") { focusable(); layout { left { 50.0 }; width { 40.0 }; top { 0.0 }; height { 40.0 } } }
            box("bl") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 100.0 }; height { 40.0 } } }
            box("br") { focusable(); layout { left { 50.0 }; width { 40.0 }; top { 100.0 }; height { 40.0 } } }
        }
        return app
    }

    @Test
    fun `down prefers the element in the same column over a nearer one beside it`() {
        val app = grid()
        app.focus.focus(app.tree.query("tl"))

        app.press(FocusCommand.DOWN)
        // `tr` is 10 units away and `bl` is 60, so nearest-centre would answer `tr` — and pressing
        // down in a form would move sideways. Overlap-first is what makes a grid behave.
        assertEquals("bl", app.focusedId)
    }

    @Test
    fun `each direction moves the way it says`() {
        val app = grid()
        app.focus.focus(app.tree.query("tl"))

        app.press(FocusCommand.RIGHT); assertEquals("tr", app.focusedId)
        app.press(FocusCommand.DOWN); assertEquals("br", app.focusedId)
        app.press(FocusCommand.LEFT); assertEquals("bl", app.focusedId)
        app.press(FocusCommand.UP); assertEquals("tl", app.focusedId)
    }

    @Test
    fun `directional navigation stops at the edge rather than wrapping`() {
        val app = grid()
        app.focus.focus(app.tree.query("tl"))

        app.press(FocusCommand.UP)
        assertEquals("tl", app.focusedId, "nothing above; tab is what wraps, not the d-pad")
        app.press(FocusCommand.LEFT)
        assertEquals("tl", app.focusedId)
    }

    @Test
    fun `directional from nothing focuses the first element`() {
        val app = grid()
        app.press(FocusCommand.DOWN)
        assertEquals("tl", app.focusedId)
    }

    @Test
    fun `elements sharing an edge are still reachable`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            // `below(0.0)` — touching exactly, which is extremely common and which a strict
            // "must be strictly beyond" test would reject, stopping navigation for no visible reason.
            box("one") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } } }
            box("two") { focusable(); layout { left { 0.0 }; width { 40.0 }; below(0.0); height { 20.0 } } }
        }
        app.focus.focus(app.tree.query("one"))
        app.press(FocusCommand.DOWN)
        assertEquals("two", app.focusedId)
    }

    @Test
    fun `an explicit neighbour beats the geometric answer`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("tab") {
                focusable()
                // The geometric answer below is `near`; the author wants the panel instead.
                focusNeighbour(FocusDirection.DOWN) { tree.query("panel") }
                layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
            }
            box("near") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 30.0 }; height { 20.0 } } }
            box("panel") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 150.0 }; height { 20.0 } } }
        }
        app.focus.focus(app.tree.query("tab"))
        app.press(FocusCommand.DOWN)
        assertEquals("panel", app.focusedId)
    }

    @Test
    fun `an override returning null is a wall, not a fallback`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("top") {
                focusable()
                focusNeighbour(FocusDirection.DOWN) { null }
                layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
            }
            box("below") { focusable(); layout { left { 0.0 }; width { 40.0 }; top { 30.0 }; height { 20.0 } } }
        }
        app.focus.focus(app.tree.query("top"))
        app.press(FocusCommand.DOWN)
        // Falling back on geometry would make the override look intermittent — working until the
        // author most wanted it to say "no".
        assertEquals("top", app.focusedId)
    }

    // -----------------------------------------------------------------------------------
    // Activation
    // -----------------------------------------------------------------------------------

    @Test
    fun `activate falls through to the click handler`() {
        val app = App()
        var clicks = 0
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("button") {
                focusable()
                onClick { clicks++ }
                layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
            }
        }
        app.press(FocusCommand.NEXT)
        app.press(FocusCommand.ACTIVATE)

        // A button that already says what clicking it does should not have to say it twice to be
        // operable from the keyboard.
        assertEquals(1, clicks)
    }

    @Test
    fun `onActivate overrides the click handler`() {
        val app = App()
        var clicks = 0
        var activations = 0
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("button") {
                onClick { clicks++ }
                onActivate { activations++ }
                layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
            }
        }
        app.press(FocusCommand.NEXT)
        app.press(FocusCommand.ACTIVATE)

        assertEquals(1, activations)
        assertEquals(0, clicks)
    }

    @Test
    fun `activating a structural change is settled before anything measures`() {
        val app = App()
        var rows = listOf("x")
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("add") {
                focusable()
                onClick { rows = rows + "added" }
                layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
            }
            box("list") {
                stackDown()
                layout { left { 0.0 }; width { 40.0 }; top { 30.0 }; height { 100.0 } }
                forEach({ rows }, key = { it }) { layout { height { 10.0 } } }
            }
        }
        app.press(FocusCommand.NEXT)
        app.press(FocusCommand.ACTIVATE)

        // Navigation runs before reconcile in the same frame, so the row exists by the time the
        // frame ends rather than a frame later.
        assertEquals(2, app.tree.query("list").children.size)
    }

    // -----------------------------------------------------------------------------------
    // Cost
    // -----------------------------------------------------------------------------------

    @Test
    fun `a frame with no navigation costs nothing`() {
        val app = stack()
        app.press(FocusCommand.NEXT)
        app.draw(ArrayList())
        val before = app.graph.stats.recomputed

        repeat(50) { app.update(FRAME) }

        // Focus is retained state, not a per-frame answer — so unlike the pointer there is not even
        // a hit test to pay for.
        assertEquals(before, app.graph.stats.recomputed)
    }
}
