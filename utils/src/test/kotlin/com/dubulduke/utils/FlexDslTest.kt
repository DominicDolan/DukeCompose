package com.dubulduke.utils

import com.dubulduke.dsl.*

import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.topOf
import com.dubulduke.layout.widthOf
import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The flex authoring surface. `dsl-and-engine.md` §3.3.
 *
 * `:layout`'s FlexTest already covers the resolution itself — the hoisted pool, the governed
 * freeze-and-loop. What is new here is the wrapper: that an item's cross axis is defaulted, that
 * its main axis is *reserved* rather than merely defaulted, and that gaps come out of the pool.
 */
class FlexDslTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))

    class AppStyle { var colour: Int = 0 }

    private val styles = StyleFactory { AppStyle() }

    /** A 300 × 40 root, so the numbers below are all arithmetic on 300 and 40. */
    private fun compose(body: ElementScope<AppStyle>.() -> Unit) =
        tree.compose(styles) {
            layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 40.0 } }
            body()
        }

    private fun width(id: String) = g.widthOf(tree.query(id))
    private fun height(id: String) = g.heightOf(tree.query(id))
    private fun left(id: String) = g.leftOf(tree.query(id))

    /** The row filling its parent — what every test here wants and none is about. */
    private fun FlexRowScope<AppStyle>.fill() = layout {
        left { parent.left }; right { parent.right }
        top { parent.top }; bottom { parent.bottom }
    }

    @Test
    fun `growing items split the free space in proportion`() {
        compose {
            flexRow(id = "row") {
                fill()
                fixed({ 100.0 }, id = "fixed")
                grow(1.0, id = "one")
                grow(2.0, id = "two")
            }
        }
        assertEquals(300.0, width("row"))
        assertEquals(100.0, width("fixed"))
        // 200 left over, split 1:2
        assertEquals(200.0 / 3.0, width("one"), 1e-9)
        assertEquals(400.0 / 3.0, width("two"), 1e-9)
    }

    @Test
    fun `items chain along the main axis`() {
        compose {
            flexRow {
                fill()
                fixed({ 100.0 }, id = "a")
                fixed({ 50.0 }, id = "b")
                grow(1.0, id = "c")
            }
        }
        assertEquals(0.0, left("a"))
        assertEquals(100.0, left("b"))
        assertEquals(150.0, left("c"))
        assertEquals(150.0, width("c"))
    }

    @Test
    fun `an item fills the cross axis without saying anything`() {
        compose { flexRow(id = "row") { fill(); grow(1.0, id = "item") } }
        assertEquals(0.0, g.topOf(tree.query("item")))
        assertEquals(40.0, height("item"))
    }

    @Test
    fun `an item may still state its cross axis`() {
        compose {
            flexRow(id = "row") {
                fill()
                grow(1.0, id = "item") { layout { top { 8.0 }; height { 20.0 } } }
            }
        }
        assertEquals(8.0, g.topOf(tree.query("item")))
        assertEquals(20.0, height("item"))
    }

    @Test
    fun `writing the main axis of an item names the row`() {
        val e = assertFailsWith<IllegalStateException> {
            compose {
                flexRow(id = "row") {
                    fill()
                    grow(1.0, id = "item") { layout { width { 50.0 } } }
                }
            }
        }
        assertTrue("owned by the flex row" in e.message!!, e.message!!)
    }

    @Test
    fun `gaps come out of the pool`() {
        compose {
            flexRow(gap = 10.0, id = "row") {
                fill()
                grow(1.0, id = "a")
                grow(1.0, id = "b")
                grow(1.0, id = "c")
            }
        }
        // 300 - two gaps of 10 = 280, split three ways
        assertEquals(280.0 / 3.0, width("a"), 1e-9)
        assertEquals(280.0 / 3.0 + 10.0, left("b"), 1e-9)
    }

    @Test
    fun `a vertical row grows along the vertical axis`() {
        compose {
            flexColumn(id = "col") {
                fill()
                fixed({ 10.0 }, id = "a")
                grow(1.0, id = "b")
            }
        }
        assertEquals(10.0, height("a"))
        assertEquals(30.0, height("b"))
        // and the horizontal axis is the one defaulted now
        assertEquals(300.0, width("b"))
    }

    @Test
    fun `a clamped row respects a max`() {
        compose {
            flexRow(clamped = true, id = "row") {
                fill()
                grow(1.0, id = "a", max = { 50.0 })
                grow(1.0, id = "b")
            }
        }
        assertEquals(50.0, width("a"))
        assertEquals(250.0, width("b"))
    }

    @Test
    fun `a clamp needs the governed row`() {
        val e = assertFailsWith<IllegalArgumentException> {
            compose {
                flexRow(id = "row") { grow(1.0, id = "a", max = { 50.0 }); grow(1.0, id = "b") }
            }
        }
        assertTrue("clamped = true" in e.message!!, e.message!!)
    }

    @Test
    fun `a flex row nests inside a column without the column's defaults fighting it`() {
        compose {
            column(id = "list") {
                layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 40.0 } }
                // No fill(): the column's own defaults must reach the row, which is the point.
                flexRow(id = "row") {
                    layout { height { 40.0 } }
                    grow(1.0, id = "a")
                    grow(1.0, id = "b")
                }
            }
        }
        assertEquals(150.0, width("a"))
    }

    @Test
    fun `an item's size expression can read the graph`() {
        val w = g.signal(80.0, "w")
        compose {
            flexRow(id = "row") {
                fill()
                fixed({ w.value }, id = "a")
                grow(1.0, id = "b")
            }
        }
        assertEquals(80.0, width("a"))
        assertEquals(220.0, width("b"))

        w.value = 120.0
        assertEquals(120.0, width("a"))
        assertEquals(180.0, width("b"))
    }
    @Test
    fun `a shrink-to-fit row sizes itself and compresses its items`() {
        val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
        tree.compose(styles) {
            layout { left { 0.0 }; width { 400.0 }; top { 0.0 }; height { 200.0 } }
            // A toolbar as wide as its buttons, that compresses them once the window narrows —
            // the standing shrink-wrapped layout, and the one `clamped = true` cannot express
            // because its solve reads the container's size to divide the pool.
            flexRow(gap = 0.0, shrinkToFit = { parent.width - 100.0 }, id = "bar") {
                layout { left { parent.left }; top { 0.0 }; height { 40.0 } }
                shrinkable({ 120.0 }, id = "a")
                shrinkable({ 80.0 }, id = "b")
            }
        }
        // 200 wanted into 300 available: it all fits, so the row is exactly its content.
        assertEquals(120.0, g.widthOf(tree.query("a")))
        assertEquals(80.0, g.widthOf(tree.query("b")))
        assertEquals(200.0, g.widthOf(tree.query("bar")))
    }

    @Test
    fun `writing the main size of a shrink-to-fit row is an error`() {
        val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
        val e = assertFailsWith<IllegalStateException> {
            tree.compose(styles) {
                layout { left { 0.0 }; width { 400.0 }; top { 0.0 }; height { 200.0 } }
                flexRow(shrinkToFit = { 300.0 }, id = "bar") {
                    // The governor owns this slot. Setting it too would be a second expression on
                    // a governed output, which is the failure the reservation turns into a message.
                    layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 40.0 } }
                    shrinkable({ 120.0 }, id = "a")
                }
            }
        }
        assertTrue("width" in e.message!!.lowercase() || "already" in e.message!!.lowercase(), e.message!!)
    }

    @Test
    fun `grow inside a shrink-to-fit row is a named error`() {
        val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
        val e = assertFailsWith<IllegalArgumentException> {
            tree.compose(styles) {
                layout { left { 0.0 }; width { 400.0 }; top { 0.0 }; height { 200.0 } }
                flexRow(shrinkToFit = { 300.0 }, id = "bar") {
                    layout { left { parent.left }; top { 0.0 }; height { 40.0 } }
                    grow(1.0, id = "a")
                }
            }
        }
        assertTrue("shrinkable" in e.message!!, e.message!!)
    }
    /**
     * The sample's floating toolbar, at four window widths.
     *
     * Here because the sample cannot be run in this environment, and its proportions are exactly
     * the sort of thing that is plausible-looking and wrong. Asserting the numbers is the only way
     * to know the demo demonstrates what its comment claims.
     */
    @Test
    fun `the sample toolbar compresses, freezes and finally overflows honestly`() {
        fun toolbarAt(viewportWidth: Double): List<Double> {
            val g = Graph()
            val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
            tree.compose(styles) {
                layout { left { 0.0 }; width { viewportWidth }; top { 0.0 }; height { 720.0 } }
                box("sidebar") {
                    layout { left { 0.0 }; width { 160.0 }; top { 0.0 }; height { 720.0 } }
                }
                flexRow(
                    gap = 8.0,
                    shrinkToFit = { parent.right - query("sidebar").right - 48.0 },
                    id = "toolbar",
                ) {
                    layout {
                        left { query("sidebar").right + 24.0 }
                        bottom { parent.bottom - 20.0 }
                        height { 44.0 }
                    }
                    shrinkable({ 24.0 }, shrink = 0.0, id = "icon")
                    shrinkable({ 340.0 }, min = { 90.0 }, id = "add")
                    shrinkable({ 480.0 }, shrink = 2.0, min = { 110.0 }, id = "clear")
                    shrinkable({ 300.0 }, min = { 120.0 }, id = "deselect")
                }
            }
            return listOf("icon", "add", "clear", "deselect", "toolbar")
                .map { g.widthOf(tree.query(it)) }
        }

        // Wide: 1168 wanted, 1372 available. Nothing compresses and the row is its content.
        assertEquals(listOf(24.0, 340.0, 480.0, 300.0, 1168.0), toolbarAt(1580.0))

        // Typical: 96 to lose against weights 340 : 960 : 300, no floor reached.
        val typical = toolbarAt(1280.0)
        assertEquals(24.0, typical[0], "the icon is incompressible at every width")
        assertEquals(319.6, typical[1], absoluteTolerance = 0.01)
        assertEquals(422.4, typical[2], absoluteTolerance = 0.01, message = "gives most, being shrink 2")
        assertEquals(282.0, typical[3], absoluteTolerance = 0.01)
        assertEquals(1072.0, typical[4], absoluteTolerance = 0.01, message = "exactly the bound")

        // Narrower: `clear` reaches its floor first and its share passes to the other two.
        val narrow = toolbarAt(700.0)
        assertEquals(110.0, narrow[2], absoluteTolerance = 0.01, message = "frozen at its floor")
        assertEquals(177.4375, narrow[1], absoluteTolerance = 0.001)
        assertEquals(156.5625, narrow[3], absoluteTolerance = 0.001)
        assertEquals(492.0, narrow[4], absoluteTolerance = 0.01)

        // Narrower still: everything is at its floor, so the row reports the size it actually needs
        // rather than the bound it was given. A visibly too-wide row is a bug an author can see.
        val tiny = toolbarAt(480.0)
        assertEquals(listOf(90.0, 110.0, 120.0), tiny.subList(1, 4))
        assertEquals(368.0, tiny[4], "honest oversize against a bound of 272")
    }
    @Test
    fun `two gapped flex rows in one tree do not collide`() {
        val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
        // Gap spacers used to be named `gap1`, `gap2` per row, and an id is a *tree-wide* binding —
        // so a second gapped row anywhere in the scene was a duplicate-id error at composition
        // time. Nothing about either row is unusual, which is what made it worth a test.
        tree.compose(styles) {
            layout { left { 0.0 }; width { 400.0 }; top { 0.0 }; height { 200.0 } }
            flexRow(gap = 8.0, id = "first") {
                layout { left { 0.0 }; right { 400.0 }; top { 0.0 }; height { 40.0 } }
                fixed({ 20.0 }); fixed({ 20.0 }); fixed({ 20.0 })
            }
            flexRow(gap = 8.0, id = "second") {
                layout { left { 0.0 }; right { 400.0 }; top { 50.0 }; height { 40.0 } }
                fixed({ 20.0 }); fixed({ 20.0 }); fixed({ 20.0 })
            }
        }
        // Three items plus the two gaps between them, in each row.
        assertEquals(5, tree.query("first").children.size)
        assertEquals(5, tree.query("second").children.size)
        // Interleaved item, gap, item, gap, item — and the gaps are still 8 wide.
        val second = tree.query("second").children
        assertEquals(20.0, g.widthOf(second[0]))
        assertEquals(8.0, g.widthOf(second[1]))
        assertEquals(20.0, g.widthOf(second[2]))
    }
}
