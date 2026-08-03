package com.dubulduke.dsl

import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.NodeView
import com.dubulduke.layout.Viewport
import com.dubulduke.layout.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pointer capture. `dsl-and-engine.md` §7, the drag half.
 *
 * The property under test is not "the handler fires" but "**the handler keeps firing after the
 * pointer leaves**", because that is the entire reason capture exists: a slider thumb is small,
 * the pointer leaves it in the first few pixels of any real drag, and without capture the gesture
 * dies there. Everything else here follows from that one behaviour.
 */
class DragTest {

    class Style {
        var colour: Int = 0
    }

    private class App : DukeApp<Style, MutableList<String>>(
        StyleFactory { Style() },
        HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0),
    ) {
        override val window get() = Window(0.0, 0.0, 200.0, 100.0)
        override val viewport get() = Viewport(0.0, 0.0, 200.0, 100.0)
        override val input = TestInput()
        override fun drawBox(node: NodeView<Style>, renderer: MutableList<String>) {}
        override fun drawText(node: NodeView<Style>, t: String, renderer: MutableList<String>) {}
    }

    /** A 40×20 thumb at (0,0), a same-sized neighbour beside it, and a record of every drag call. */
    private class Fixture {
        val app = App()
        val drags = ArrayList<Pair<Double, Double>>()
        var clicks = 0
        var neighbourClicks = 0

        init {
            app.compose {
                layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
                box("thumb") {
                    onDrag { x, y -> drags += x to y }
                    onClick { clicks++ }
                    layout { left { 0.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
                }
                box("neighbour") {
                    onClick { neighbourClicks++ }
                    layout { left { 60.0 }; width { 40.0 }; top { 0.0 }; height { 20.0 } }
                }
            }
        }
    }

    @Test
    fun `a drag keeps tracking after the pointer leaves the element`() {
        val f = Fixture()
        f.app.frame(10.0, 10.0, pressed = true)
        assertEquals(1, f.drags.size, "the press frame already drags")

        // Well outside the thumb, and over the neighbour. Without capture the hit test would find
        // the neighbour and the drag would simply stop — the failure this whole feature prevents.
        f.app.frame(80.0, 10.0, pressed = true)
        f.app.frame(500.0, -400.0, pressed = true)

        assertEquals(3, f.drags.size)
        assertEquals(80.0 to 10.0, f.drags[1])
        assertEquals(500.0 to -400.0, f.drags[2], "even far outside the window")
    }

    @Test
    fun `the captured element holds hover, so the drag does not light up what it passes over`() {
        val f = Fixture()
        val thumb = f.app.tree.query("thumb")
        f.app.frame(10.0, 10.0, pressed = true)
        f.app.frame(80.0, 10.0, pressed = true)

        assertSame(thumb, f.app.pointer.captured)
        assertSame(thumb, f.app.pointer.hovered, "not the neighbour underneath")
    }

    @Test
    fun `capture is released on the button coming up`() {
        val f = Fixture()
        f.app.frame(10.0, 10.0, pressed = true)
        f.app.frame(80.0, 10.0, pressed = true)
        f.app.frame(80.0, 10.0, pressed = false)

        assertNull(f.app.pointer.captured)
        val after = f.drags.size
        f.app.frame(80.0, 10.0, pressed = false)
        assertEquals(after, f.drags.size, "and nothing drags once it is released")

        // Hover goes back to whatever is actually under the pointer.
        assertEquals("ui.neighbour", f.app.pointer.hovered?.path)
    }

    @Test
    fun `a press and release without movement is still a click`() {
        val f = Fixture()
        f.app.frame(10.0, 10.0, pressed = true)
        f.app.frame(10.0, 10.0, pressed = false)

        assertEquals(1, f.clicks, "draggable and clickable are not exclusive")
    }

    @Test
    fun `releasing a drag outside the element still counts as a click on it`() {
        val f = Fixture()
        f.app.frame(10.0, 10.0, pressed = true)
        f.app.frame(500.0, 500.0, pressed = false)

        // Under capture `hit` is the captured element, so press and release agree. Deliberate: a
        // slider that also responds to a plain click should not lose the click because the finger
        // moved two pixels.
        assertEquals(1, f.clicks)
        assertEquals(0, f.neighbourClicks)
    }

    @Test
    fun `a drag never dispatches a click to something it passed over`() {
        val f = Fixture()
        f.app.frame(10.0, 10.0, pressed = true)
        f.app.frame(80.0, 10.0, pressed = true)
        f.app.frame(80.0, 10.0, pressed = false)

        assertEquals(0, f.neighbourClicks, "the neighbour was never pressed")
    }

    @Test
    fun `press position is recorded, so a handler can work out its grab offset`() {
        val f = Fixture()
        f.app.frame(30.0, 15.0, pressed = true)

        assertEquals(30.0, f.app.pointer.pressX)
        assertEquals(15.0, f.app.pointer.pressY)
    }

    @Test
    fun `an element with no drag handler does not capture`() {
        val f = Fixture()
        f.app.frame(70.0, 10.0, pressed = true)

        assertNull(f.app.pointer.captured, "the neighbour is clickable but not draggable")
        // So hover follows the pointer as usual.
        f.app.frame(10.0, 10.0, pressed = true)
        assertEquals("ui.thumb", f.app.pointer.hovered?.path)
    }

    @Test
    fun `losing focus mid-drag releases the capture`() {
        val f = Fixture()
        f.app.frame(10.0, 10.0, pressed = true)
        assertSame(f.app.tree.query("thumb"), f.app.pointer.captured)

        // A window that loses focus never delivers the mouse-up. Without `clear` releasing capture
        // the pointer would still be stuck to the thumb when focus came back.
        f.app.pointer.clear()
        assertNull(f.app.pointer.captured)

        val before = f.drags.size
        f.app.frame(80.0, 10.0, pressed = false)
        assertEquals(before, f.drags.size)
    }

    @Test
    fun `a captured element removed mid-drag does not take the pointer with it`() {
        val app = App()
        var rows = listOf("a", "b")
        val dragged = ArrayList<String>()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
            forEach({ rows }, key = { it }) { item ->
                onDrag { _, _ -> dragged += item.key.toString() }
                layout {
                    left { 0.0 }; width { 40.0 }
                    top { if (item.key == "a") 0.0 else 30.0 }; height { 20.0 }
                }
            }
        }
        app.frame(10.0, 10.0, pressed = true)
        assertTrue(dragged.isNotEmpty())

        // The row under the pointer goes away while it is still held — the same shape as a list
        // row deleted by its own handler, which is the bug that reached the user once already.
        rows = listOf("b")
        app.frame(10.0, 10.0, pressed = true)

        assertNull(app.pointer.captured)
    }
}
