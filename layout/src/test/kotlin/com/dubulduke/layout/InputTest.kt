package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pointer state and event dispatch. `dsl-and-engine.md` §7.
 *
 * Two claims carry the design and are asserted here rather than assumed: **hover is an ordinary
 * graph input**, so it invalidates what reads it and nothing else and needs no governor; and
 * **hit testing is in reverse paint order**, so what you click is what you see.
 */
class InputTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g)
    private val pointer = Pointer(tree)

    /** Layout coordinates straight through, so window and layout coordinates are the same. */
    private object Identity : DukeHost {
        override val window = Window(0.0, 0.0, 100.0, 100.0)
        override val viewport = Viewport(0.0, 0.0, 100.0, 100.0)
        override val textMetrics = HeadlessTextMetrics()
    }

    /** Mechanica's arrangement: a y-up window against y-down layout. */
    private object Flipped : DukeHost {
        override val window = Window(-50.0, -50.0, 100.0, 100.0)
        override val viewport = Viewport(0.0, 0.0, 100.0, -100.0)
        override val textMetrics = HeadlessTextMetrics()
    }

    private fun ElementBuilder.box(left: Double, top: Double, wide: Double, tall: Double) {
        h { origin { left }; size { wide } }
        v { origin { top }; size { tall } }
    }

    /** Root 0,0,100,100 with two overlapping interactive children. */
    private fun overlapping(): Pair<Element, Element> {
        var lower: Element? = null
        var upper: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            lower = child("lower") { interactive(); box(0.0, 0.0, 60.0, 60.0) }
            upper = child("upper") { interactive(); box(40.0, 40.0, 60.0, 60.0) }
        }
        return lower!! to upper!!
    }

    // -------------------------------------------------------------------------------
    // Hit testing
    // -------------------------------------------------------------------------------

    @Test
    fun `the later sibling wins where two overlap`() {
        val (lower, upper) = overlapping()
        assertSame(lower, tree.hitTest(10.0, 10.0))
        assertSame(upper, tree.hitTest(90.0, 90.0))
        assertSame(upper, tree.hitTest(50.0, 50.0), "the overlap should hit what is painted last")
    }

    @Test
    fun `a child wins over its parent`() {
        var parent: Element? = null
        var kid: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            parent = child("panel") {
                interactive()
                box(0.0, 0.0, 80.0, 80.0)
                kid = child("button") { interactive(); box(10.0, 10.0, 20.0, 20.0) }
            }
        }
        assertSame(kid, tree.hitTest(15.0, 15.0))
        assertSame(parent, tree.hitTest(50.0, 50.0))
    }

    @Test
    fun `non-interactive elements are transparent to the pointer`() {
        var behind: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            behind = child("behind") { interactive(); box(0.0, 0.0, 100.0, 100.0) }
            // Painted on top, but never opted in — so it must not swallow the click.
            child("decoration") { box(0.0, 0.0, 100.0, 100.0) }
        }
        assertSame(behind, tree.hitTest(50.0, 50.0))
    }

    @Test
    fun `a miss is null, not the root`() {
        overlapping()
        assertNull(tree.hitTest(70.0, 10.0), "a gap between the two boxes")
        assertNull(tree.hitTest(500.0, 500.0))
    }

    @Test
    fun `a child outside its parent's box is still hit`() {
        // No early rejection on parent bounds: nothing constrains a child to its parent, so a
        // dropdown hanging out of its container is ordinary rather than pathological.
        var kid: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            child("panel") {
                box(0.0, 0.0, 20.0, 20.0)
                kid = child("overflow") { interactive(); box(200.0, 200.0, 40.0, 40.0) }
            }
        }
        assertSame(kid, tree.hitTest(210.0, 210.0))
    }

    // -------------------------------------------------------------------------------
    // Hover as a graph input
    // -------------------------------------------------------------------------------

    @Test
    fun `hover is a tracked read that invalidates only what reads it`() {
        val (lower, _) = overlapping()
        var styleRuns = 0
        val tint = g.derive("tint") { styleRuns++; if (isHovered(lower)) 1.0 else 0.0 }

        assertEquals(0.0, g.read(tint))
        assertEquals(1, styleRuns)

        pointer.update(10.0, 10.0, down = false)
        assertEquals(1.0, g.read(tint))
        assertEquals(2, styleRuns)

        // Moving within the same element writes the same value: free, and no recompute.
        pointer.update(12.0, 12.0, down = false)
        assertEquals(1.0, g.read(tint))
        assertEquals(2, styleRuns, "an unchanged hover invalidated something")
    }

    @Test
    fun `moving off clears the previous element's hover`() {
        val (lower, upper) = overlapping()
        pointer.update(10.0, 10.0, down = false)
        assertTrue(g.isHovered(lower))
        assertFalse(g.isHovered(upper))

        pointer.update(90.0, 90.0, down = false)
        assertFalse(g.isHovered(lower), "hover was left set on the element the pointer left")
        assertTrue(g.isHovered(upper))

        pointer.update(70.0, 10.0, down = false)
        assertFalse(g.isHovered(lower))
        assertFalse(g.isHovered(upper))
        assertNull(pointer.hovered)
    }

    @Test
    fun `hover on a non-interactive element is false rather than an error`() {
        tree.root("app") { box(0.0, 0.0, 10.0, 10.0) }
        assertFalse(g.isHovered(tree.root))
        assertFalse(g.isPressed(tree.root))
    }

    // -------------------------------------------------------------------------------
    // Clicks
    // -------------------------------------------------------------------------------

    @Test
    fun `a press and release on the same element is a click`() {
        var clicks = 0
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            child("button") { onClick { clicks++ }; box(0.0, 0.0, 50.0, 50.0) }
        }

        pointer.update(10.0, 10.0, down = true)
        assertEquals(0, clicks, "a click fires on release, not on press")
        pointer.update(10.0, 10.0, down = false)
        assertEquals(1, clicks)
    }

    @Test
    fun `press is observable in the graph while held`() {
        var button: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            button = child("button") { interactive(); box(0.0, 0.0, 50.0, 50.0) }
        }
        pointer.update(10.0, 10.0, down = true)
        assertTrue(g.isPressed(button!!))
        pointer.update(10.0, 10.0, down = false)
        assertFalse(g.isPressed(button!!))
    }

    @Test
    fun `pressing and dragging away is not a click`() {
        var clicks = 0
        var button: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            button = child("button") { onClick { clicks++ }; box(0.0, 0.0, 50.0, 50.0) }
            child("other") { interactive(); box(60.0, 60.0, 30.0, 30.0) }
        }

        pointer.update(10.0, 10.0, down = true)
        pointer.update(70.0, 70.0, down = true)
        pointer.update(70.0, 70.0, down = false)

        assertEquals(0, clicks, "releasing over a different element fired a click")
        assertFalse(g.isPressed(button!!), "press was left set after releasing elsewhere")
    }

    @Test
    fun `a release with no press does nothing`() {
        var clicks = 0
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            child("button") { onClick { clicks++ }; box(0.0, 0.0, 50.0, 50.0) }
        }
        pointer.update(10.0, 10.0, down = false)
        assertEquals(0, clicks)
    }

    @Test
    fun `onClick implies interactive`() {
        var button: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            button = child("button") { onClick { }; box(0.0, 0.0, 50.0, 50.0) }
        }
        assertSame(button, tree.hitTest(10.0, 10.0))
    }

    @Test
    fun `clear forgets hover and press without dispatching`() {
        var clicks = 0
        var button: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            button = child("button") { onClick { clicks++ }; box(0.0, 0.0, 50.0, 50.0) }
        }
        pointer.update(10.0, 10.0, down = true)
        pointer.clear()

        assertFalse(g.isHovered(button!!))
        assertFalse(g.isPressed(button!!))
        pointer.update(10.0, 10.0, down = false)
        assertEquals(0, clicks, "a release after clear resurrected the press")
    }

    // -------------------------------------------------------------------------------
    // Window coordinates
    // -------------------------------------------------------------------------------

    @Test
    fun `the window overload inverts the draw transform, flip included`() {
        val (lower, upper) = overlapping()

        // Identity host: window and layout coordinates coincide.
        pointer.update(Identity, 10.0, 10.0, down = false)
        assertSame(lower, pointer.hovered)

        // Flipped host: layout (10,10) — near the top-left — is window (-40, +40), because the
        // window's y runs up while layout's runs down.
        pointer.update(Flipped, -40.0, 40.0, down = false)
        assertSame(lower, pointer.hovered)

        // And layout (90,90) is window (+40, -40).
        pointer.update(Flipped, 40.0, -40.0, down = false)
        assertSame(upper, pointer.hovered)
    }

    @Test
    fun `hit testing agrees with what the draw walk reports`() {
        // The two transforms are written separately, so this pins them together: a point taken
        // from a drawn NodeView must hit that same element.
        val (_, upper) = overlapping()
        var centre: DoubleArray? = null
        val probe = object : DukeBackend<Unit, Unit> {
            override fun drawBox(node: NodeView<Unit>, renderer: Unit) {
                if (node.element.id == "upper") centre = doubleArrayOf(node.centerX, node.centerY)
            }
            override fun drawText(node: NodeView<Unit>, text: String, renderer: Unit) {}
        }
        tree.draw(Flipped, probe, Unit)

        pointer.update(Flipped, centre!![0], centre!![1], down = false)
        assertSame(upper, pointer.hovered, "the centre of the drawn box did not hit it")
    }

    // -------------------------------------------------------------------------------
    // The oscillation guard
    // -------------------------------------------------------------------------------

    @Test
    fun `an element that shrinks out from under the pointer is reported once`() {
        // §7.3's shrink-on-hover limit cycle, built deliberately: 30 tall normally, 20 when
        // hovered, pointer at y=25. Hover → shrink → miss → grow → hover, at frame rate.
        var button: Element? = null
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            button = child("button") {
                val self = element
                interactive()
                h { origin { 0.0 }; size { 100.0 } }
                v { origin { 0.0 }; size { if (isHovered(self)) 20.0 else 30.0 } }
            }
        }

        val warnings = ArrayList<String>()
        pointer.onOscillation = { warnings += it }

        repeat(60) { pointer.update(50.0, 25.0, down = false) }

        assertEquals(1, warnings.size, "the warning should fire once, not every frame")
        assertTrue(warnings[0].contains("app.button"), warnings[0])
        assertTrue(warnings[0].contains("nothing"), "the other end of the flip should be named")
        assertTrue(warnings[0].contains("§7.3"), warnings[0])
    }

    @Test
    fun `a stable hover never warns`() {
        overlapping()
        val warnings = ArrayList<String>()
        pointer.onOscillation = { warnings += it }
        repeat(100) { pointer.update(10.0, 10.0, down = false) }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `an element that grows on hover is bistable, not oscillating`() {
        // The mirror image, and the reason the guard cannot simply forbid reading hover from a
        // size: grow-on-hover has *two stable fixed points* at y=25 — 20 tall and unhovered, or
        // 30 tall and hovered — and history decides which. That is hysteresis for free, and it is
        // the wanted behaviour rather than a bug.
        tree.root("app") {
            box(0.0, 0.0, 100.0, 100.0)
            child("button") {
                val self = element
                interactive()
                h { origin { 0.0 }; size { 100.0 } }
                v { origin { 0.0 }; size { if (isHovered(self)) 30.0 else 20.0 } }
            }
        }
        val warnings = ArrayList<String>()
        pointer.onOscillation = { warnings += it }

        // Arriving at y=25 from outside: the box is 20 tall, so it is missed, and it stays missed.
        repeat(50) { pointer.update(50.0, 25.0, down = false) }
        assertNull(pointer.hovered, "the unhovered fixed point")

        // Arriving via y=15, which is inside the small box: it hovers, grows to 30, and y=25 is
        // now inside — so it stays hovered. Same pointer position, opposite stable state.
        pointer.update(50.0, 15.0, down = false)
        repeat(50) { pointer.update(50.0, 25.0, down = false) }
        assertEquals("app.button", pointer.hovered?.path, "the hovered fixed point")

        assertTrue(warnings.isEmpty(), "grow-on-hover was reported as oscillating")
    }
}
