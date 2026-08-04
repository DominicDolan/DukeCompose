package com.dubulduke.dsl

import com.dubulduke.utils.*

import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.SimpleFont
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.topOf
import com.dubulduke.layout.widthOf
import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Keyed reconciliation. `layout-design.md` Q10.
 *
 * The claims that matter are not "the right number of boxes appears" but the three that make this
 * worth having over re-composing every frame: **a surviving key is not rebuilt**, **a removed key
 * gives its graph nodes back**, and **a frame where nothing changed costs nothing**.
 */
class ReconcileTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
    private val font = SimpleFont("Test", 10.0)

    class AppStyle { var colour: Int = 0 }

    private val styles = StyleFactory { AppStyle() }

    private data class Row(val id: String, val title: String)

    private var items: List<Row> = emptyList()

    /** A 300-wide root with a keyed column of 20-tall rows. */
    private fun composeList(body: ElementScope<AppStyle>.(Item<Row>) -> Unit = { }) =
        tree.compose(styles) {
            layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 400.0 } }
            column(gap = 4.0, id = "list") {
                layout { fillWidth(0.0); top { 0.0 }; height { 400.0 } }
                forEach({ items }, key = { it.id }) { item ->
                    layout { height { 20.0 } }
                    body(item)
                }
            }
        }

    private fun keysOnScreen(): List<String> =
        tree.query("list").children.map { it.key as String }

    @Test
    fun `items appear, and are laid out by the container's defaults`() {
        items = listOf(Row("a", "A"), Row("b", "B"))
        composeList()

        assertContentEquals(listOf("a", "b"), keysOnScreen())
        val list = tree.query("list")
        assertEquals(0.0, g.topOf(list.children[0]))
        assertEquals(24.0, g.topOf(list.children[1]))
        assertEquals(300.0, g.widthOf(list.children[0]))
    }

    @Test
    fun `appending an item leaves the existing ones alone`() {
        items = listOf(Row("a", "A"), Row("b", "B"))
        composeList()
        val first = tree.query("list").children[0]

        items = items + Row("c", "C")
        assertEquals(1, tree.reconcile())

        assertContentEquals(listOf("a", "b", "c"), keysOnScreen())
        // The identity claim: a survivor is the *same element*, not an equal one rebuilt.
        assertSame(first, tree.query("list").children[0])
    }

    @Test
    fun `inserting at the head does not re-associate the others`() {
        items = listOf(Row("a", "A"), Row("b", "B"))
        composeList()
        val a = tree.query("list").children[0]
        val b = tree.query("list").children[1]

        items = listOf(Row("z", "Z")) + items
        tree.reconcile()

        assertContentEquals(listOf("z", "a", "b"), keysOnScreen())
        // Positional matching would have made `a` the element that used to be `z`'s, throwing
        // away everything memoised below it. This is the whole reason keys exist.
        assertSame(a, tree.query("list").children[1])
        assertSame(b, tree.query("list").children[2])
        assertEquals(1, a.index - 0)
        assertEquals(2, b.index)
    }

    @Test
    fun `reordering moves elements rather than rebuilding them`() {
        items = listOf(Row("a", "A"), Row("b", "B"), Row("c", "C"))
        composeList()
        val a = tree.query("list").children[0]
        val c = tree.query("list").children[2]

        items = listOf(Row("c", "C"), Row("b", "B"), Row("a", "A"))
        tree.reconcile()

        assertContentEquals(listOf("c", "b", "a"), keysOnScreen())
        assertSame(c, tree.query("list").children[0])
        assertSame(a, tree.query("list").children[2])
        // Position follows the new order, from the column's `prev` default.
        assertEquals(48.0, g.topOf(a))
    }

    @Test
    fun `a removed item gives its graph nodes back`() {
        items = listOf(Row("a", "A"), Row("b", "B"), Row("c", "C"))
        composeList { item -> label({ item.value.title }, font) { layout { fillWidth(0.0); top { 0.0 } } } }
        // Settle everything so every lazy node has run and linked.
        tree.walk { e, _ -> g.widthOf(e); g.heightOf(e) }

        val withThree = g.nodeCount
        items = items.filter { it.id != "b" }
        tree.reconcile()
        tree.walk { e, _ -> g.widthOf(e); g.heightOf(e) }

        assertContentEquals(listOf("a", "c"), keysOnScreen())
        assertTrue(g.nodeCount < withThree, "removal freed no nodes: still ${g.nodeCount}")
        assertTrue(g.stats.disposed > 0)
    }

    @Test
    fun `adding and removing the same item repeatedly does not grow the graph`() {
        items = listOf(Row("a", "A"))
        composeList { item -> label({ item.value.title }, font) { layout { fillWidth(0.0); top { 0.0 } } } }
        tree.walk { e, _ -> g.widthOf(e); g.heightOf(e) }
        val baseline = g.nodeCount

        repeat(20) { i ->
            items = listOf(Row("a", "A"), Row("tmp$i", "T"))
            tree.reconcile()
            tree.walk { e, _ -> g.widthOf(e); g.heightOf(e) }
            items = listOf(Row("a", "A"))
            tree.reconcile()
            tree.walk { e, _ -> g.widthOf(e); g.heightOf(e) }
        }

        // The acceptance test for disposal: churn must be bounded, not merely correct.
        assertEquals(baseline, g.nodeCount)
    }

    @Test
    fun `a frame where nothing changed costs nothing`() {
        items = listOf(Row("a", "A"), Row("b", "B"))
        composeList { item -> label({ item.value.title }, font) { layout { fillWidth(0.0); top { 0.0 } } } }
        tree.walk { e, _ -> g.widthOf(e); g.heightOf(e) }

        g.stats.reset()
        repeat(100) { tree.reconcile() }

        // `layout-design.md` Q10's acceptance test, in its keyed form: N identical reconciles
        // produce no recomputes and no marks at all.
        assertEquals(0L, g.stats.recomputed)
        assertEquals(0L, g.stats.marked)
        assertEquals(0L, g.stats.disposed)
    }

    @Test
    fun `a survivor's data updates through the item signal`() {
        items = listOf(Row("a", "first"))
        composeList { item -> label({ item.value.title }, font) { layout { fillWidth(0.0); top { 0.0 } } } }
        val list = tree.query("list")
        val labelElement = list.children[0].children[0]
        val before = g.heightOf(labelElement)

        items = listOf(Row("a", "a much longer title than before, long enough to wrap several times over")) 
        tree.reconcile()

        // Same element — not rebuilt — but its text tracked the change, so it re-measures.
        assertSame(list.children[0], tree.query("list").children[0])
        assertEquals(
            "a much longer title than before, long enough to wrap several times over",
            g.readRef(labelElement.content!!.textSlot),
        )
        assertTrue(g.heightOf(labelElement) > before)
    }

    @Test
    fun `text captured in the body freezes, and that is the documented trap`() {
        items = listOf(Row("a", "first"))
        composeList { item -> label(item.value.title, font) { layout { fillWidth(0.0); top { 0.0 } } } }
        val labelElement = tree.query("list").children[0].children[0]

        items = listOf(Row("a", "second"))
        tree.reconcile()

        // Captured at build time, so it does not follow. The `label({ … })` overload is the fix.
        assertEquals("first", g.readRef(labelElement.content!!.textSlot))
    }

    @Test
    fun `emptying the list is not a special case`() {
        items = listOf(Row("a", "A"), Row("b", "B"))
        composeList()
        items = emptyList()
        tree.reconcile()
        assertTrue(keysOnScreen().isEmpty())

        items = listOf(Row("c", "C"))
        tree.reconcile()
        assertContentEquals(listOf("c"), keysOnScreen())
        assertEquals(20.0, g.heightOf(tree.query("list").children[0]))
    }

    @Test
    fun `a forEach item can itself be a row container`() {
        // The shape the sample actually uses, and the one the first eight tests here missed:
        // the item *is* the row, so it has to become a container itself — `row(gap) { }` would
        // wrap it in a second element. Without this its children get no `left` at all.
        items = listOf(Row("a", "A"))
        tree.compose(styles) {
            layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 400.0 } }
            column(id = "list") {
                layout { fillWidth(0.0); top { 0.0 }; height { 400.0 } }
                forEach({ items }, key = { it.id }) { item ->
                    stackAcross(gap = 4.0)
                    layout { height { 20.0 } }
                    box { layout { width { 10.0 } } }
                    label({ item.value.title }, font) { layout { right { parent.right } } }
                }
            }
        }

        val row = tree.query("list").children[0]
        assertEquals(0.0, g.leftOf(row.children[0]))
        // The label's `left` comes from the container default: prev.right + gap.
        assertEquals(14.0, g.leftOf(row.children[1]))
        assertEquals(20.0, g.heightOf(row.children[0]))
    }

    @Test
    fun `duplicate keys are rejected`() {
        items = listOf(Row("a", "A"), Row("a", "B"))
        val e = assertFailsWith<IllegalArgumentException> { composeList() }
        assertTrue("duplicate key" in e.message!!, e.message!!)
    }

    @Test
    fun `forEach cannot share an element with static children`() {
        items = listOf(Row("a", "A"))
        val e = assertFailsWith<IllegalStateException> {
            tree.compose(styles) {
                layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 400.0 } }
                column(id = "list") {
                    layout { fillWidth(0.0); top { 0.0 }; height { 400.0 } }
                    box { layout { height { 10.0 } } }
                    forEach({ items }, key = { it.id }) { layout { height { 20.0 } } }
                }
            }
        }
        assertTrue("not both" in e.message!!, e.message!!)
    }

    @Test
    fun `a region inside a removed subtree is dropped`() {
        items = listOf(Row("a", "A"))
        var inner: List<Row> = listOf(Row("x", "X"))
        tree.compose(styles) {
            layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 400.0 } }
            column(id = "list") {
                layout { fillWidth(0.0); top { 0.0 }; height { 400.0 } }
                forEach({ items }, key = { it.id }) {
                    layout { height { 40.0 } }
                    column(gap = 0.0) {
                        layout { fillWidth(0.0); top { 0.0 }; height { 40.0 } }
                        forEach({ inner }, key = { it.id }) { layout { height { 20.0 } } }
                    }
                }
            }
        }
        assertEquals(2, tree.regionCount)

        items = emptyList()
        tree.reconcile()
        tree.reconcile()
        assertEquals(1, tree.regionCount)
    }
}
