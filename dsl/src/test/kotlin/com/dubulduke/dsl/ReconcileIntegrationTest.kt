package com.dubulduke.dsl

import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.NodeView
import com.dubulduke.layout.SimpleFont
import com.dubulduke.layout.Viewport
import com.dubulduke.layout.Window
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.isHovered
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.topOf
import com.dubulduke.layout.isPressed
import com.dubulduke.layout.widthOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reconciliation driven the way Mechanica drives it: a real [DukeApp], real pointer dispatch, and
 * a list whose items are removed *by their own click handlers*.
 *
 * ReconcileTest exercises the mechanism directly; this exercises the composition of the mechanism
 * with everything around it — container defaults on a rebuilt item, and the pointer holding a
 * reference to an element that its own click has just destroyed. Both of those were bugs, and
 * neither was reachable from a test that called `tree.reconcile()` itself.
 */
class ReconcileIntegrationTest {
    class AppStyle { var colour: Int = 0 }
    private data class Row(val id: String, val title: String, val body: String)

    private class App : DukeApp<AppStyle, MutableList<String>>(
        StyleFactory { AppStyle() },
        HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0),
    ) {
        override val window get() = Window(0.0, 0.0, 800.0, 600.0)
        override val viewport get() = Viewport(0.0, 0.0, 800.0, -600.0)

        override val input = TestInput()
        override fun drawBox(node: NodeView<AppStyle>, renderer: MutableList<String>) {}
        override fun drawText(node: NodeView<AppStyle>, text: String, renderer: MutableList<String>) {}
    }

    private val ui = App()
    private val font = SimpleFont("Test", 10.0)
    private var rows = List(3) { Row("row$it", "T$it", "B$it") }
    private var added = 0
    private var useDefaults = true
    private val selected = ui.refSignal<Any?>(null, "selected")

    private fun build() = ui.compose {
        layout { left { 0.0 }; width { ui.viewportWidth }; top { 0.0 }; height { ui.viewportHeight } }

        column(gap = 8.0, id = "sidebar") {
            layout { left { 0.0 }; width { 160.0 }; top { 0.0 }; height { parent.height } }
            label("brand", font, id = "brand") { layout { top { 24.0 }; height { 40.0 } } }
            label("+ add a row", font, id = "add") {
                interactive()
                onClick { rows = rows + Row("added${added++}", "New", "Added.") }
                layout { fillWidth(12.0); below(16.0); height { 28.0 } }
            }
        }

        column(gap = 8.0, id = "panel") {
            layout {
                left { query("sidebar").right + 16.0 }
                right { parent.right - 16.0 }
                top { 16.0 }
                height { if (children.isEmpty) 0.0 else children.last.bottom - top + 12.0 }
            }

            column(gap = 8.0, id = "list") {
                layout {
                    fillWidth(0.0); top { parent.top }
                    height { if (children.isEmpty) 0.0 else children.last.bottom - top }
                }
                forEach({ rows }, key = { it.id }) { item ->
                    if (useDefaults) stackAcross(gap = 8.0)
                    onClick { selected.value = item.key }
                    style { colour = if (selected.value == item.key) 1 else if (graph.isPressed(element)) 2 else if (graph.isHovered(element)) 3 else 0 }
                    layout { fillWidth(12.0); below(8.0); height { children.maxOf { it.height } + 20.0 } }

                    box { layout { width { 28.0 }; height { 28.0 }; centerY { parent.centerY }; left { parent.left + 4.0 } } }
                    label({ "${item.value.title} - ${item.value.body}" }, font) {
                        layout { right { next!!.left - 12.0 }; centerY { parent.centerY } }
                    }
                    box {
                        interactive()
                        onClick { rows = rows.filter { r -> r.id != item.key } }
                        layout { width { 16.0 }; height { 16.0 }; right { parent.right - 12.0 }; centerY { parent.centerY } }
                    }
                }
            }

            flexRow(gap = 8.0, id = "flex") {
                layout { fillWidth(12.0); below(8.0); height { 48.0 } }
                fixed({ 28.0 }) { layout { height { 28.0 }; centerY { parent.centerY } } }
                grow(1.0) { text("one", font) }
                grow(2.0) { text("two", font) }
            }
        }
    }

    private fun keys() = ui.tree.query("list").children.map { it.key }

    private var px = 0.0
    private var py = 0.0

    /** What Mechanica does every frame: sample the pointer, reconcile, draw. */
    private fun frame(pressed: Boolean = false) {
        ui.frame(px, py, pressed)
        ui.draw(mutableListOf())
    }

    /** Click the badge of the row at [index], the way the pointer actually does it. */
    private fun clickBadge(index: Int) {
        val badge = ui.tree.query("list").children[index].children[2]
        val g = ui.graph
        // Window coords: y is flipped by the negative viewport extent.
        val cx = g.leftOf(badge) + g.widthOf(badge) / 2.0
        val cy = 600.0 - (g.topOf(badge) + g.heightOf(badge) / 2.0)
        px = cx; py = cy
        frame(pressed = true)
        frame(pressed = false)
    }

    private fun clickAdd() {
        val e = ui.tree.query("add")
        val g = ui.graph
        px = g.leftOf(e) + g.widthOf(e) / 2.0
        py = 600.0 - (g.topOf(e) + g.heightOf(e) / 2.0)
        frame(pressed = true)
        frame(pressed = false)
    }

    @Test
    fun `an item whose parent is not a container says so`() {
        useDefaults = false
        val e = kotlin.runCatching { build() }.exceptionOrNull()
        assertNotNull(e)
        // The under-constrained message alone does not distinguish "I forgot a slot" from "my
        // parent stamps nothing", and the second is by far the commoner cause inside a forEach.
        assertTrue("No container defaults reached this element" in e.message!!, e.message!!)
    }

    @Test
    fun `three hundred randomised add and remove clicks stay consistent`() {
        build()
        frame()
        val rand = java.util.Random(7)
        repeat(300) {
            val n = keys().size
            if (n == 0 || rand.nextInt(3) == 0) clickAdd() else clickBadge(rand.nextInt(n))
            frame()
        }
        // The element list and the model must still agree after all that.
        assertEquals(rows.map { it.id }, keys())
    }

    @Test
    fun `removing a row by clicking it keeps the rest laid out`() {
        build()
        frame()
        assertContentEquals(listOf("row0", "row1", "row2"), keys())

        clickBadge(1)
        frame()
        assertContentEquals(listOf("row0", "row2"), keys())
    }
}
