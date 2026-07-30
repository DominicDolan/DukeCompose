package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 3 — style and the draw walk. `dsl-and-engine.md` §4.5, §6.1.
 *
 * The point of these is not that rectangles come out; it is that the two claims the design rests
 * on hold. **Style is a sink**: no layout slot may depend on it, which is what licenses the whole
 * styler being one coarse node. And **the walk is not reactive**: it pulls, records no edges, and
 * allocates nothing per element, because a game loop runs it every frame.
 */
class RenderTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g)
    private val font = SimpleFont("Test", 10.0)

    /** An application's style type. The framework never sees this class. */
    private class GameStyle {
        var color: Int = 0
        var corner: Double = 0.0
    }

    /** The one-line alias §4.5 expects every application to write. */
    private fun ElementBuilder.styled(styler: GameStyle.() -> Unit) = style(GameStyle(), styler)

    /** Mechanica's arrangement: a y-up camera, so the viewport height is negative. */
    private class Camera(val w: Double, val h: Double) : DukeHost {
        override val window = Window(-w / 2.0, -h / 2.0, w, h)
        override val viewport = Viewport(0.0, 0.0, w, -h)
        override val textMetrics = HeadlessTextMetrics()
    }

    /** Layout coordinates straight through, for the tests that are not about the transform. */
    private object Identity : DukeHost {
        override val window = Window(0.0, 0.0, 100.0, 100.0)
        override val viewport = Viewport(0.0, 0.0, 100.0, 100.0)
        override val textMetrics = HeadlessTextMetrics()
    }

    /**
     * Records what it was asked to draw, and what the geometry looked like *at the time it was
     * asked*. Everything except [views] is snapshotted inside the callback, because the node view
     * is one reused instance and reading it after the walk reports the last element drawn.
     */
    private class Recorder : DukeBackend<GameStyle, StringBuilder> {
        val calls = ArrayList<String>()
        val paths = ArrayList<String>()
        val bounds = ArrayList<DoubleArray>()
        val views = ArrayList<NodeView<GameStyle>>()
        var readStyle = true

        private fun record(kind: String, n: NodeView<GameStyle>, extra: String = "") {
            views += n
            paths += n.element.path
            bounds += doubleArrayOf(n.minX, n.maxX, n.minY, n.maxY)
            val style = if (readStyle) n.styleOrNull?.let { " color=${it.color}" } ?: "" else ""
            calls += "$kind ${n.element.path} [${n.x}, ${n.y}, ${n.width}, ${n.height}]$extra$style"
        }

        override fun drawBox(node: NodeView<GameStyle>, renderer: StringBuilder) =
            record("box", node)

        override fun drawText(node: NodeView<GameStyle>, text: String, renderer: StringBuilder) =
            record("text", node, " \"$text\"")

        override fun drawCustom(node: NodeView<GameStyle>, kind: String, renderer: StringBuilder) =
            record("custom:$kind", node)
    }

    private fun box(b: ElementBuilder, left: Double, top: Double, wide: Double, tall: Double) =
        with(b) {
            h { origin { left }; size { wide } }
            v { origin { top }; size { tall } }
        }

    // -------------------------------------------------------------------------------
    // The transform
    // -------------------------------------------------------------------------------

    @Test
    fun `a y-up host gets layout's y-down coordinates flipped, with no flag in the layout module`() {
        tree.root("app") { box(this, 0.0, 0.0, 800.0, 600.0) }
        val r = Recorder()
        tree.draw(Camera(800.0, 600.0), r, StringBuilder())

        // x maps left-to-left; y maps layout-0 (the top of a y-down layout) to the top of a y-up
        // window, and the height comes back negative because the axes run opposite ways.
        assertEquals("box app [-400.0, 300.0, 800.0, -600.0]", r.calls.single())
    }

    @Test
    fun `an element halfway down the layout lands halfway down the window`() {
        tree.root("app") {
            box(this, 0.0, 0.0, 800.0, 600.0)
            child("mid") { box(this, 100.0, 300.0, 200.0, 60.0) }
        }
        val r = Recorder()
        tree.draw(Camera(800.0, 600.0), r, StringBuilder())

        // Layout y 300..360 (measuring down from the top) → window y 0..-60 (measuring up from
        // the middle). minY/maxY are the orientation-free way to ask.
        assertEquals("box app.mid [-300.0, 0.0, 200.0, -60.0]", r.calls[1])
        val (minX, maxX, minY, maxY) = r.bounds[1].toList()
        assertEquals(listOf(-300.0, -100.0, -60.0, 0.0), listOf(minX, maxX, minY, maxY))
    }

    @Test
    fun `contains works in window coordinates on a flipped axis`() {
        tree.root("app") {
            box(this, 0.0, 0.0, 800.0, 600.0)
            child("mid") { box(this, 100.0, 300.0, 200.0, 60.0) }
        }
        val hits = ArrayList<Boolean>()
        val probe = object : DukeBackend<GameStyle, Unit> {
            override fun drawBox(node: NodeView<GameStyle>, renderer: Unit) {
                if (node.element.id == "mid") {
                    hits += node.contains(-200.0, -30.0)   // the middle of the box
                    hits += node.contains(-200.0, 30.0)    // where the unflipped rect would be
                    hits += node.contains(0.0, -30.0)      // to the right of it
                }
            }
            override fun drawText(node: NodeView<GameStyle>, text: String, renderer: Unit) {}
        }
        tree.draw(Camera(800.0, 600.0), probe, Unit)

        assertEquals(listOf(true, false, false), hits)
    }

    @Test
    fun `a zero-extent viewport is rejected rather than collapsing every element to a point`() {
        tree.root("app") { box(this, 0.0, 0.0, 10.0, 10.0) }
        val bad = object : DukeHost {
            override val window = Window(0.0, 0.0, 100.0, 100.0)
            override val viewport = Viewport(0.0, 0.0, 100.0, 0.0)
            override val textMetrics = HeadlessTextMetrics()
        }
        val e = assertFailsWith<IllegalArgumentException> { tree.draw(bad, Recorder(), StringBuilder()) }
        assertTrue(e.message!!.contains("zero extent"), e.message!!)
    }

    // -------------------------------------------------------------------------------
    // Dispatch and paint order
    // -------------------------------------------------------------------------------

    @Test
    fun `paint order is composition order, parents before children`() {
        tree.root("app") {
            box(this, 0.0, 0.0, 100.0, 100.0)
            child("first") {
                box(this, 0.0, 0.0, 10.0, 10.0)
                child("nested") { box(this, 0.0, 0.0, 5.0, 5.0) }
            }
            child("second") { box(this, 0.0, 0.0, 10.0, 10.0) }
        }
        val r = Recorder()
        tree.draw(Identity, r, StringBuilder())

        assertEquals(listOf("app", "app.first", "app.first.nested", "app.second"), r.paths)
    }

    @Test
    fun `kind decides the callback, and text arrives resolved`() {
        tree.root("app") {
            box(this, 0.0, 0.0, 100.0, 100.0)
            child("label") {
                text("Hello", font)
                box(this, 0.0, 0.0, 50.0, 10.0)
            }
            child("gauge") {
                kind("gauge")
                box(this, 0.0, 0.0, 20.0, 20.0)
            }
        }
        val r = Recorder().also { it.readStyle = false }
        tree.draw(Identity, r, StringBuilder())

        assertEquals("box app [0.0, 0.0, 100.0, 100.0]", r.calls[0])
        assertEquals("text app.label [0.0, 0.0, 50.0, 10.0] \"Hello\"", r.calls[1])
        assertEquals("custom:gauge app.gauge [0.0, 0.0, 20.0, 20.0]", r.calls[2])
    }

    @Test
    fun `the node view is one reused instance, so a frame allocates nothing per element`() {
        tree.root("app") {
            box(this, 0.0, 0.0, 100.0, 100.0)
            repeat(4) { i -> child("c$i") { box(this, 0.0, 0.0, 10.0, 10.0) } }
        }
        val r = Recorder().also { it.readStyle = false }
        tree.draw(Identity, r, StringBuilder())

        assertEquals(5, r.calls.size)
        for (v in r.views) assertSame(r.views[0], v, "a view was allocated per element")
    }

    @Test
    fun `the renderer is a parameter, not engine state`() {
        // §6.1: iteration C carried a nullable `drawer` field and re-checked it on every element.
        tree.root("app") { box(this, 0.0, 0.0, 10.0, 10.0) }
        val sink = StringBuilder()
        val backend = object : DukeBackend<GameStyle, StringBuilder> {
            override fun drawBox(node: NodeView<GameStyle>, renderer: StringBuilder) {
                renderer.append(node.element.path)
            }
            override fun drawText(node: NodeView<GameStyle>, text: String, renderer: StringBuilder) {}
        }
        tree.draw(Identity, backend, sink)
        assertEquals("app", sink.toString())
    }

    // -------------------------------------------------------------------------------
    // Style
    // -------------------------------------------------------------------------------

    @Test
    fun `a styler does not run at composition time`() {
        var runs = 0
        tree.root("app") {
            styled { runs++; color = 0xFF }
            box(this, 0.0, 0.0, 10.0, 10.0)
        }
        assertEquals(0, runs, "composition ran a styler")

        tree.draw(Identity, Recorder(), StringBuilder())
        assertEquals(1, runs)
    }

    @Test
    fun `a styler re-runs only when one of its inputs changes`() {
        val theme = g.input(0xAA.toDouble(), "theme")
        var runs = 0
        tree.root("app") {
            styled { runs++; color = g.read(theme).toInt() }
            box(this, 0.0, 0.0, 10.0, 10.0)
        }

        val r = Recorder()
        tree.draw(Identity, r, StringBuilder())
        assertEquals(1, runs)
        assertEquals("box app [0.0, 0.0, 10.0, 10.0] color=170", r.calls[0])

        // Three more frames with nothing changed.
        repeat(3) { tree.draw(Identity, Recorder(), StringBuilder()) }
        assertEquals(1, runs, "a clean styler re-ran")

        g.setInput(theme, 0xBB.toDouble())
        val after = Recorder()
        tree.draw(Identity, after, StringBuilder())
        assertEquals(2, runs)
        assertEquals("box app [0.0, 0.0, 10.0, 10.0] color=187", after.calls[0])
    }

    @Test
    fun `the style object is reused across re-runs, never reallocated`() {
        val theme = g.input(1.0, "theme")
        tree.root("app") {
            styled { color = g.read(theme).toInt() }
            box(this, 0.0, 0.0, 10.0, 10.0)
        }
        val first = g.styleOf(tree.root)
        g.setInput(theme, 2.0)
        val second = g.styleOf(tree.root)

        assertSame(first, second, "the styler allocated a new style object")
        assertEquals(2, (second as GameStyle).color)
    }

    @Test
    fun `style is a sink - no layout slot depends on it`() {
        val theme = g.input(1.0, "theme")
        tree.root("app") {
            styled { color = g.read(theme).toInt() }
            box(this, 0.0, 0.0, 100.0, 50.0)
        }
        tree.draw(Identity, Recorder(), StringBuilder())

        // The claim that licenses the whole styler being one coarse node (§4.5): the style node
        // has readers of its own but is read by nothing in the graph, so its granularity cannot
        // produce a false cycle.
        val styleSlot = tree.root.styleNode!!.slot
        assertTrue(g.dependenciesOf(styleSlot).isNotEmpty(), "the styler's own inputs are tracked")
        assertTrue(g.subscribersOf(styleSlot).isEmpty(), "something in the graph reads style")

        for (axis in listOf(tree.root.h, tree.root.v)) {
            for (slot in listOf(axis.firstSlot, axis.secondSlot)) {
                assertTrue(styleSlot !in g.dependenciesOf(slot), "${g.nameOf(slot)} depends on style")
            }
        }
    }

    @Test
    fun `changing a style input recomputes no layout slot`() {
        val theme = g.input(1.0, "theme")
        tree.root("app") {
            styled { color = g.read(theme).toInt() }
            box(this, 0.0, 0.0, 100.0, 50.0)
        }
        tree.draw(Identity, Recorder(), StringBuilder())

        val before = g.stats.recomputed
        g.setInput(theme, 2.0)
        tree.draw(Identity, Recorder(), StringBuilder())
        assertEquals(before + 1, g.stats.recomputed, "a colour change touched layout")
    }

    @Test
    fun `an element may be composed without a style`() {
        tree.root("app") { box(this, 0.0, 0.0, 10.0, 10.0) }
        assertNull(g.styleOrNullOf(tree.root))

        val r = Recorder()
        tree.draw(Identity, r, StringBuilder())
        assertEquals("box app [0.0, 0.0, 10.0, 10.0]", r.calls.single())

        val e = assertFailsWith<IllegalStateException> { g.styleOf(tree.root) }
        assertTrue(e.message!!.contains("has no style"), e.message!!)
    }

    @Test
    fun `two styles on one element is a composition-time error`() {
        val e = assertFailsWith<IllegalStateException> {
            tree.root("app") {
                styled { color = 1 }
                styled { color = 2 }
                box(this, 0.0, 0.0, 10.0, 10.0)
            }
        }
        assertTrue(e.message!!.contains("already has a style"), e.message!!)
    }

    @Test
    fun `a styler may read geometry that does not exist yet when it is registered`() {
        // Same laziness as every layout expression: the block is registered before the element it
        // reads has been composed, and resolves at draw.
        tree.root("app") {
            styled { corner = g.widthOf(tree.query("pane")) / 10.0 }
            box(this, 0.0, 0.0, 100.0, 50.0)
            child("pane") { box(this, 0.0, 0.0, 60.0, 20.0) }
        }
        assertEquals(6.0, (g.styleOf(tree.root) as GameStyle).corner)
    }

    @Test
    fun `style survives a flex row, where the builder is a FlexScope block`() {
        tree.root("app") {
            box(this, 0.0, 0.0, 300.0, 40.0)
            flexRow {
                fixed(mainSize = { 100.0 }, id = "icon") {
                    styled { color = 0x01 }
                    v { origin { 0.0 }; size { 40.0 } }
                }
                grow(1.0, "body") {
                    styled { color = 0x02 }
                    v { origin { 0.0 }; size { 40.0 } }
                }
            }
        }
        val r = Recorder()
        tree.draw(Identity, r, StringBuilder())

        assertEquals("box app.icon [0.0, 0.0, 100.0, 40.0] color=1", r.calls[1])
        assertEquals("box app.body [100.0, 0.0, 200.0, 40.0] color=2", r.calls[2])
    }
}
