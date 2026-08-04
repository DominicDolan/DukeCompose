package com.dubulduke.utils

import com.dubulduke.dsl.ElementScope
import com.dubulduke.dsl.StyleFactory
import com.dubulduke.dsl.box
import com.dubulduke.dsl.column
import com.dubulduke.dsl.compose
import com.dubulduke.dsl.forEach
import com.dubulduke.dsl.signal
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.bottomOf
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.rightOf
import com.dubulduke.layout.topOf
import com.dubulduke.layout.widthOf
import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The grid.
 *
 * Two claims carry it, and the tests are grouped around them. **Track sizes are a function of the
 * container alone**, so a cell is ordinary slot expressions and a resize reflows without anything
 * being recomposed. And **a cell's placement is a container default**, so a child overriding one
 * slot of it behaves exactly as a child of a `column` does.
 */
class GridTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))

    class AppStyle { var colour: Int = 0 }

    private val styles = StyleFactory { AppStyle() }

    /** A 400×300 root. */
    private fun screen(body: ElementScope<AppStyle>.() -> Unit) =
        tree.compose(styles) {
            layout { left { 0.0 }; width { 400.0 }; top { 0.0 }; height { 300.0 } }
            body()
        }

    private fun e(id: String) = tree.query(id)

    // -------------------------------------------------------------------------------
    // Track sizing
    // -------------------------------------------------------------------------------

    @Test
    fun `fixed tracks take their size and flexible ones split what is left`() {
        // 400 wide, 48 fixed, two gaps of 10 → 332 free, split 1:2 into 110.67 and 221.33.
        screen {
            grid(
                columns = tracks(track(48.0), fr(1.0), fr(2.0)),
                rows = tracks(track(40.0)),
                columnGap = 10.0,
            ) {
                layout { fill() }
                cell(0, 0, id = "a")
                cell(1, 0, id = "b")
                cell(2, 0, id = "c")
            }
        }
        assertEquals(0.0, g.leftOf(e("a")))
        assertEquals(48.0, g.rightOf(e("a")))
        assertEquals(58.0, g.leftOf(e("b")), "48 plus the gap")
        assertEquals(168.667, g.rightOf(e("b")), 0.001)
        assertEquals(178.667, g.leftOf(e("c")), 0.001)
        assertEquals(400.0, g.rightOf(e("c")), 0.001, "the last track ends at the grid's edge")
    }

    @Test
    fun `a percent track is a share of the whole grid, and a track can combine parts`() {
        screen {
            grid(
                columns = tracks(percent(0.25), track(20.0) + fr(1.0)),
                rows = tracks(track(40.0)),
            ) {
                layout { fill() }
                cell(0, 0, id = "a")
                cell(1, 0, id = "b")
            }
        }
        assertEquals(100.0, g.widthOf(e("a")), "a quarter of 400")
        assertEquals(300.0, g.widthOf(e("b")), "20 fixed plus the 280 that is left")
    }

    @Test
    fun `an overflowing grid floors its flexible tracks rather than reversing them`() {
        screen {
            grid(
                columns = tracks(track(300.0), track(300.0), fr(1.0)),
                rows = tracks(track(40.0)),
            ) {
                layout { fill() }
                cell(2, 0, id = "c")
            }
        }
        // The fixed tracks already overflow, so the flexible one is zero-width — and, crucially,
        // still after them rather than before.
        assertEquals(600.0, g.leftOf(e("c")))
        assertEquals(0.0, g.widthOf(e("c")))
    }

    @Test
    fun `rows work the same way, off the grid's height`() {
        screen {
            grid(columns = tracks(fr(1.0)), rows = tracks(track(40.0), fr(1.0)), rowGap = 10.0) {
                layout { fill() }
                cell(0, 0, id = "head")
                cell(0, 1, id = "body")
            }
        }
        assertEquals(0.0, g.topOf(e("head")))
        assertEquals(40.0, g.bottomOf(e("head")))
        assertEquals(50.0, g.topOf(e("body")))
        assertEquals(300.0, g.bottomOf(e("body")))
    }

    @Test
    fun `a resize reflows every cell with no recomposition`() {
        val w = g.signal(400.0, "w")
        tree.compose(styles) {
            layout { left { 0.0 }; width { w.value }; top { 0.0 }; height { 300.0 } }
            grid(columns = tracks(2, fr(1.0)), rows = tracks(track(40.0)), columnGap = 20.0) {
                layout { fill() }
                cell(0, 0, id = "a")
                cell(1, 0, id = "b")
            }
        }
        assertEquals(190.0, g.widthOf(e("a")))
        assertEquals(210.0, g.leftOf(e("b")))

        w.value = 200.0
        assertEquals(90.0, g.widthOf(e("a")))
        assertEquals(110.0, g.leftOf(e("b")))
    }

    // -------------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------------

    @Test
    fun `a cell spans the tracks it is given, gaps included`() {
        screen {
            grid(columns = tracks(4, fr(1.0)), rows = tracks(2, track(40.0)), gap = 10.0) {
                layout { fill() }
                // Four columns of 92.5 with three 10 gaps: a two-column span is 195 wide.
                cell(1, 0, columnSpan = 2, rowSpan = 2, id = "wide")
            }
        }
        assertEquals(102.5, g.leftOf(e("wide")))
        assertEquals(297.5, g.rightOf(e("wide")))
        assertEquals(195.0, g.widthOf(e("wide")))
        assertEquals(0.0, g.topOf(e("wide")))
        assertEquals(90.0, g.bottomOf(e("wide")), "two 40 rows and the 10 between them")
    }

    @Test
    fun `justify and align place a child that states its own size`() {
        screen {
            grid(columns = tracks(track(100.0)), rows = tracks(track(100.0))) {
                layout { fill() }
                cell(0, 0, justify = Placement.CENTER, align = Placement.END, id = "a") {
                    layout { size(20.0, 10.0) }
                }
            }
        }
        assertEquals(40.0, g.leftOf(e("a")), "centred in a 100-wide cell")
        assertEquals(60.0, g.rightOf(e("a")))
        assertEquals(90.0, g.topOf(e("a")), "pinned to the cell's bottom")
        assertEquals(100.0, g.bottomOf(e("a")))
    }

    @Test
    fun `a stretched cell yields one slot to a child that states a size`() {
        screen {
            grid(columns = tracks(track(100.0)), rows = tracks(track(100.0))) {
                layout { fill() }
                // The grid stamps top and bottom; the child states a height. Three assignments on
                // the vertical axis, of which the later-declared default is dropped — so the child
                // keeps the cell's top, which is `align-self: start` without saying so.
                cell(0, 0, id = "a") { layout { fixedHeight(30.0) } }
            }
        }
        assertEquals(0.0, g.topOf(e("a")))
        assertEquals(30.0, g.bottomOf(e("a")))
        assertEquals(100.0, g.widthOf(e("a")), "the horizontal axis is untouched, so it still fills")
    }

    // -------------------------------------------------------------------------------
    // Auto-placement
    // -------------------------------------------------------------------------------

    @Test
    fun `items fill row-major, and a span pushes what follows`() {
        screen {
            grid(columns = tracks(3, track(100.0)), rows = tracks(3, track(50.0))) {
                layout { fill() }
                item(id = "a")
                item(id = "b", columnSpan = 2)
                item(id = "c")
                item(id = "d")
            }
        }
        assertEquals(0.0, g.leftOf(e("a")))
        assertEquals(0.0, g.topOf(e("a")))
        assertEquals(100.0, g.leftOf(e("b")))
        assertEquals(200.0, g.widthOf(e("b")))
        assertEquals(0.0, g.leftOf(e("c")), "the first row is full, so the second")
        assertEquals(50.0, g.topOf(e("c")))
        assertEquals(100.0, g.leftOf(e("d")))
    }

    @Test
    fun `a span that does not fit leaves a hole rather than backfilling`() {
        screen {
            grid(columns = tracks(3, track(100.0)), rows = tracks(3, track(50.0))) {
                layout { fill() }
                item(id = "a")
                // Three columns will not fit beside `a`, so `b` starts the next row and the two
                // cells left of the first row stay empty. The cursor only moves forwards, so `c`
                // does not backfill them — sparse packing, as CSS's default flow is.
                item(id = "b", columnSpan = 3)
                item(id = "c")
            }
        }
        assertEquals(0.0, g.topOf(e("a")))
        assertEquals(0.0, g.leftOf(e("b")))
        assertEquals(50.0, g.topOf(e("b")))
        assertEquals(300.0, g.widthOf(e("b")))
        assertEquals(0.0, g.leftOf(e("c")))
        assertEquals(100.0, g.topOf(e("c")), "the row after b, not the hole beside a")
    }

    @Test
    fun `skip leaves a cell empty, and explicit placement is honoured by the flow`() {
        screen {
            grid(columns = tracks(2, track(100.0)), rows = tracks(2, track(50.0))) {
                layout { fill() }
                cell(1, 0, id = "pinned")
                skip()
                item(id = "a")
            }
        }
        // `pinned` took (1,0) and moved the cursor past it; `skip` ate (0,1); `a` gets (1,1).
        assertEquals(100.0, g.leftOf(e("pinned")))
        assertEquals(100.0, g.leftOf(e("a")))
        assertEquals(50.0, g.topOf(e("a")))
    }

    // -------------------------------------------------------------------------------
    // Errors, at composition
    // -------------------------------------------------------------------------------

    @Test
    fun `a cell outside the declared tracks is a composition-time error`() {
        val err = assertFailsWith<IllegalArgumentException> {
            screen {
                grid(columns = tracks(2, fr(1.0)), rows = tracks(1, track(40.0))) {
                    layout { fill() }
                    cell(1, 0, columnSpan = 2)
                }
            }
        }
        assertTrue("2 columns" in err.message!!, err.message!!)
    }

    @Test
    fun `running out of cells names the grid rather than misplacing the item`() {
        val err = assertFailsWith<IllegalStateException> {
            screen {
                grid(columns = tracks(1, fr(1.0)), rows = tracks(1, track(40.0))) {
                    layout { fill() }
                    item()
                    item()
                }
            }
        }
        assertTrue("no free" in err.message!!, err.message!!)
    }

    // -------------------------------------------------------------------------------
    // stackGrid — the container form
    // -------------------------------------------------------------------------------

    @Test
    fun `stackGrid places children by index, in rows of its own making`() {
        screen {
            box("palette") {
                layout { fill() }
                stackGrid(columns = tracks(3, fr(1.0)), rowHeight = 30.0, columnGap = 10.0, rowGap = 5.0)
                box("a") {}
                box("b") {}
                box("c") {}
                box("d") {}
            }
        }
        // 400 wide, two 10 gaps → three tracks of 126.667.
        assertEquals(0.0, g.leftOf(e("a")))
        assertEquals(136.667, g.leftOf(e("b")), 0.001)
        assertEquals(273.333, g.leftOf(e("c")), 0.001)
        assertEquals(0.0, g.leftOf(e("d")), "the fourth wraps to the next row")
        assertEquals(35.0, g.topOf(e("d")), "one 30 row and the 5 gap")
        assertEquals(65.0, g.bottomOf(e("d")))
    }

    @Test
    fun `a stackGrid cell survives a sibling being inserted`() {
        // The claim `cellIndex` exists for. An expression reading `index` alone would record no
        // dependency, so inserting an item ahead of this one would leave it in the cell it had
        // when it was built — fresh geometry, stale cell.
        var items = listOf("b", "c")
        tree.compose(styles) {
            layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 300.0 } }
            column(id = "list") {
                layout { fill() }
                stackGrid(columns = tracks(2, fr(1.0)), rowHeight = 20.0)
                forEach({ items }, key = { it }) { }
            }
        }
        // Keyed, so these are the same elements before and after — which is the only way the test
        // means anything: a rebuilt element would get its cell from a fresh composition either way.
        fun item(key: String) = tree.query("list").children.first { it.key == key }
        val b = item("b")
        val c = item("c")
        assertEquals(0.0, g.leftOf(b))
        assertEquals(150.0, g.leftOf(c))

        items = listOf("a", "b", "c")
        tree.reconcile()

        assertEquals(0.0, g.leftOf(item("a")))
        assertEquals(150.0, g.leftOf(b), "pushed into the second column")
        assertEquals(0.0, g.leftOf(c), "and onto the second row")
        assertEquals(20.0, g.topOf(c))
    }

    @Test
    fun `a stackGrid can be shrink-wrapped by the rows it ends up needing`() {
        // The sample's card block, and the combination most at risk of being a cycle: the grid's
        // height comes from its children, and its children's placement comes from the grid. It is
        // not one, because a cell's *vertical* placement counts rows of a stated height and never
        // reads the height back — only the columns read the grid's size, and only its width.
        var items = listOf("a", "b", "c", "d")
        tree.compose(styles) {
            layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 300.0 } }
            box("cards") {
                stackGrid(columns = tracks(3, fr(1.0)), rowHeight = 30.0, columnGap = 5.0, rowGap = 5.0)
                layout { fillWidth(); top { 10.0 }; wrapChildrenHeight() }
                forEach({ items }, key = { it }) { }
            }
        }
        assertEquals(65.0, g.heightOf(e("cards")), "two rows of 30 and the 5 between them")

        items = listOf("a", "b", "c", "d", "e", "f", "g")
        tree.reconcile()
        assertEquals(100.0, g.heightOf(e("cards")), "three rows now, and nothing counted them")
    }

    @Test
    fun `stackGrid honours justify, so a child sizes itself inside its cell`() {
        screen {
            box("bar") {
                layout { fill() }
                stackGrid(
                    columns = tracks(2, fr(1.0)),
                    rowHeight = 40.0,
                    justify = Placement.CENTER,
                    align = Placement.CENTER,
                )
                box("a") { layout { size(20.0, 10.0) } }
            }
        }
        assertEquals(90.0, g.leftOf(e("a")), "centred in the first 200-wide track")
        assertEquals(110.0, g.rightOf(e("a")))
        assertEquals(15.0, g.topOf(e("a")))
        assertEquals(25.0, g.bottomOf(e("a")))
    }
}
