package com.dubulduke.dsl

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
}
