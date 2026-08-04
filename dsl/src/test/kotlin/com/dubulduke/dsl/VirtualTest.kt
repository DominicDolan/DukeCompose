package com.dubulduke.dsl

import com.dubulduke.layout.ClipRect
import com.dubulduke.layout.Element
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.Property
import com.dubulduke.layout.NodeView
import com.dubulduke.layout.Viewport
import com.dubulduke.layout.Window
import com.dubulduke.layout.bottomOf
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.hitTest
import com.dubulduke.layout.topOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [virtualContent] — `deferred.md` P2, the read walk that was O(elements) whatever changed.
 *
 * The claim under test is not "scrolling works"; [ScrollTest] establishes that. It is that **the
 * cost of a settled frame stops depending on the length of the list**, which is a claim about what
 * *exists* rather than about what is drawn. So the assertions here are mostly counts — elements in
 * the tree, graph nodes, rebuilds — and the one that matters most is the pair at the bottom, where
 * the same measurements are taken over a list a hundred times longer and have to come out the same.
 */
class VirtualTest {

    class Style {
        var colour: Int = 0
    }

    private class Recorder : DukeApp<Style, MutableList<String>>(
        StyleFactory { Style() },
        HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0),
    ) {
        var screenWidth = 200.0
        var screenHeight = 100.0

        override val window: Window get() = Window(0.0, 0.0, screenWidth, screenHeight)
        override val viewport: Viewport get() = Viewport(0.0, 0.0, screenWidth, screenHeight)
        override val input = TestInput()

        override fun drawBox(node: NodeView<Style>, renderer: MutableList<String>) {
            renderer += node.element.path
        }

        override fun drawText(node: NodeView<Style>, text: String, renderer: MutableList<String>) {
            renderer += node.element.path
        }

        override fun pushClip(clip: ClipRect, renderer: MutableList<String>) {}
        override fun popClip(renderer: MutableList<String>) {}

        fun drawn(): List<String> {
            val out = ArrayList<String>()
            draw(out)
            return out
        }
    }

    private class Item(val id: Int, val title: String)

    /**
     * Rows 20 tall with no gap, in a 100-tall viewport: five rows visible, plus [overscan] each
     * side. Deliberately the same geometry as [ScrollTest], so the two can be compared directly.
     */
    private class Harness(count: Int, overscan: Int = 2, gap: Double = 0.0) {
        val app = Recorder()
        val data = MutableList(count) { Item(it, "row $it") }

        /**
         * What the region reads. Republished from [data] by [edit], so its *identity* changes
         * exactly when its contents do — which is the contract `items()` documents, and the
         * short-circuit the every-frame path depends on.
         */
        private var list: List<Item> = data.toList()

        lateinit var area: ScrollScope<Style>
            private set

        init {
            app.compose {
                layout {
                    left { 0.0 }; width { app.viewportWidth }
                    top { 0.0 }; height { app.viewportHeight }
                }
                scrollArea(id = "area", speed = 10.0) {
                    area = this
                    layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                    virtualContent(
                        { list }, key = { it.id },
                        rowHeight = 20.0, gap = gap, overscan = overscan,
                    ) { row ->
                        interactive()
                        style { colour = row.index }
                    }
                }
            }
        }

        fun frame() = app.frame()

        /** Mutate the backing data and republish it. */
        fun edit(change: MutableList<Item>.() -> Unit) {
            data.change()
            list = data.toList()
        }

        val content: Element get() = app.tree.query("area").children.single()

        /** Rows currently built, by their key. */
        fun rowKeys(): List<String> = content.children.map { it.path.substringAfterLast('.') }

        fun row(key: Int): Element = content.children.first { it.path.endsWith("{$key}") }
    }

    // -----------------------------------------------------------------------------------
    // What exists
    // -----------------------------------------------------------------------------------

    @Test
    fun `only the rows near the viewport are built`() {
        val h = Harness(count = 1_000)
        h.frame()

        // Five visible rows (100 / 20), plus two overscan below. Nothing above, since the list is
        // at the top — the window is clamped, not wrapped.
        assertEquals(
            listOf("{0}", "{1}", "{2}", "{3}", "{4}", "{5}", "{6}"),
            h.rowKeys(),
            "the window should be the visible rows plus overscan, and nothing else"
        )
    }

    @Test
    fun `the content is as tall as the whole list, not as tall as what is built`() {
        val h = Harness(count = 1_000)
        h.frame()

        assertEquals(20_000.0, h.app.graph.heightOf(h.content))

        // The circularity virtualisation would otherwise have: how far you can scroll depends on
        // the content height, and what to build depends on how far you have scrolled. Uniform pitch
        // is what breaks it — the height is arithmetic over the count, not a measurement.
        assertEquals(19_900.0, h.area.maxScroll)
    }

    @Test
    fun `an empty list builds nothing and cannot scroll`() {
        val h = Harness(count = 0)
        h.frame()
        assertEquals(emptyList(), h.rowKeys())
        assertEquals(0.0, h.area.maxScroll)
    }

    @Test
    fun `rows are positioned by index, so the chain is one link deep whatever the list`() {
        val h = Harness(count = 1_000)
        h.frame()

        val rows = h.content.children
        // Position is index x pitch, which is the *indexed* shape §11.2 measured at depth 1 —
        // rather than each row reading the previous one's bottom, which is depth O(rows) and is
        // what put a 2 000-row list into the chain-depth ceiling.
        for ((i, r) in rows.withIndex()) {
            assertEquals(i * 20.0, h.app.graph.topOf(r), "row $i")
            assertEquals(i * 20.0 + 20.0, h.app.graph.bottomOf(r), "row $i")
        }

        // Measured the way `ScaleTest` measures it: a row's origin sits one above the tallest
        // derived node it reads. A chained list would report a height proportional to the index.
        val deepest = rows.maxOf { h.app.graph.graphHeightOf(it.v.slotFor(Property.ORIGIN)!!) }
        assertTrue(deepest <= 2, "expected a shallow indexed shape, measured height $deepest")
    }

    // -----------------------------------------------------------------------------------
    // Scrolling the window
    // -----------------------------------------------------------------------------------

    @Test
    fun `scrolling moves the window and reuses the rows that stayed`() {
        val h = Harness(count = 1_000)
        h.frame()
        val kept = h.row(6)

        h.area.scroll.value = 100.0     // five rows down
        h.frame()

        assertEquals(
            listOf("{3}", "{4}", "{5}", "{6}", "{7}", "{8}", "{9}", "{10}", "{11}"),
            h.rowKeys(),
        )
        // The point of keying: a row that is still on screen is not rebuilt, so its subtree, its
        // memoised values and its dependency edges all survive the scroll.
        assertSame(kept, h.row(6))
    }

    @Test
    fun `a scroll that stays within one row rebuilds nothing`() {
        val h = Harness(count = 1_000, overscan = 4)
        h.area.scroll.value = 202.0
        h.frame()
        val before = h.content.children.toList()

        h.area.scroll.value = 208.0     // still inside row 10, and inside the same last row
        h.frame()

        // The window is a pair of integers, so most scrolling changes nothing structural at all —
        // it changes the content's origin, which is one input write and every row's position falls
        // out of it. Only crossing a row boundary can move the window, and [overscan] is what makes
        // that cheap rather than what makes it rare.
        assertEquals(before, h.content.children.toList())
        assertEquals(0, h.app.reconcile(), "the region reported a rebuild it did not do")
    }

    @Test
    fun `rows scrolled far away are destroyed, and their nodes with them`() {
        val h = Harness(count = 1_000)
        h.area.scroll.value = 5_000.0
        h.frame()
        val near = h.app.graph.nodeCount

        h.area.scroll.value = 10_000.0
        h.frame()
        val far = h.app.graph.nodeCount

        // Two interior positions, so the window is the same size at both — the ends are truncated
        // and would not compare. Equal node counts mean the rows left behind were genuinely
        // destroyed rather than merely dropped from the child list.
        assertEquals(
            near, far,
            "scrolling 250 rows changed the node count from $near to $far; rows are leaking"
        )
        assertEquals("{498}", h.rowKeys().first())
    }

    @Test
    fun `the window is clamped at the end of the list`() {
        val h = Harness(count = 10)
        h.frame()
        h.area.scroll.value = 1_000.0    // past the end
        h.frame()
        // The region clamps the offset itself rather than waiting for `scrollArea`'s own clamp,
        // which runs after it — otherwise the list would flicker blank for a frame at exactly the
        // moment it is scrolled to the bottom. 200 of content in a 100 viewport clamps to 100,
        // i.e. rows 5..9, less two of overscan above.
        assertEquals(listOf("{3}", "{4}", "{5}", "{6}", "{7}", "{8}", "{9}"), h.rowKeys())
    }

    // -----------------------------------------------------------------------------------
    // Data and structure
    // -----------------------------------------------------------------------------------

    @Test
    fun `a built row tracks its data and its index without being rebuilt`() {
        val h = Harness(count = 1_000)
        h.frame()
        val row5 = h.row(5)

        // Insert above it. Its key survives, so the element does — but it is now row 6, and the
        // position signal is what says so.
        h.edit { add(0, Item(-1, "new")) }
        h.frame()

        assertSame(row5, h.row(5))
        assertEquals(120.0, h.app.graph.topOf(row5), "the surviving row did not move with its index")
    }

    @Test
    fun `removing the item under the window shrinks the content and re-clamps the scroll`() {
        val h = Harness(count = 10)
        h.frame()
        h.area.scroll.value = h.area.maxScroll
        h.frame()
        assertEquals(100.0, h.area.scroll.value)

        h.edit { subList(5, 10).clear() }
        h.frame()

        // Content is now 100 tall in a 100-tall viewport, so there is nowhere to scroll. The clamp
        // is `scrollArea`'s per-frame Region, which runs after this one — see ScrollScope.finish.
        h.frame()
        assertEquals(0.0, h.area.maxScroll)
        assertEquals(0.0, h.area.scroll.value)
        assertEquals(listOf("{0}", "{1}", "{2}", "{3}", "{4}"), h.rowKeys())
    }

    @Test
    fun `a gap is pitch, not padding`() {
        val h = Harness(count = 100, gap = 5.0)
        h.frame()
        val rows = h.content.children
        assertEquals(0.0, h.app.graph.topOf(rows[0]))
        assertEquals(20.0, h.app.graph.bottomOf(rows[0]))
        assertEquals(25.0, h.app.graph.topOf(rows[1]))

        // The content's height is `count x pitch - gap`: 100 rows of 20 with 99 gaps of 5, and no
        // trailing gap after the last row.
        assertEquals(100 * 25.0 - 5.0, h.app.graph.heightOf(h.content))
    }

    // -----------------------------------------------------------------------------------
    // Only built rows are real
    // -----------------------------------------------------------------------------------

    @Test
    fun `an unbuilt row is not hit-testable`() {
        val h = Harness(count = 1_000)
        h.frame()
        // Row 2, on screen and built.
        assertNotNull(h.app.tree.hitTest(10.0, 50.0))
        // Row 40, which exists in the data and not in the tree. The scroll area itself is
        // `interactive()`, so what comes back is the viewport rather than nothing — and it is
        // certainly not a row.
        val far = h.app.tree.hitTest(10.0, 800.0)
        assertTrue(far == null || far === h.app.tree.query("area"), "hit an unbuilt row: $far")
    }

    @Test
    fun `only built rows are drawn`() {
        val h = Harness(count = 1_000)
        h.frame()
        val drawn = h.app.drawn().filter { it.contains("{") }
        // Fewer than are built, and that is the two mechanisms composing rather than one of them
        // failing: virtualisation decides what *exists* (seven rows here, five visible plus two of
        // overscan) and clipping decides what is *drawn* (the five on screen). Neither subsumes the
        // other — culling cannot stop an off-screen row being laid out, and overscan exists
        // precisely so that something is built before it is needed.
        assertEquals(7, h.rowKeys().size)
        assertEquals(
            listOf("ui.area.[0].{0}", "ui.area.[0].{1}", "ui.area.[0].{2}",
                   "ui.area.[0].{3}", "ui.area.[0].{4}"),
            drawn,
        )
    }

    // -----------------------------------------------------------------------------------
    // The claim
    // -----------------------------------------------------------------------------------

    @Test
    fun `cost is proportional to the viewport, not to the list`() {
        fun measure(count: Int): Triple<Int, Int, Long> {
            val h = Harness(count)
            h.frame()
            h.app.drawn()   // the first draw is what evaluates the scene; it is not a steady frame
            h.frame()
            h.app.graph.stats.reset()
            h.app.drawn()
            return Triple(h.app.tree.size, h.app.graph.nodeCount, h.app.graph.stats.recomputed)
        }

        val small = measure(100)
        val large = measure(10_000)

        println(
            "\n virtualContent, 100-item list vs 10 000-item list\n" +
                " ---------------------------------------------------\n" +
                " elements   ${small.first} / ${large.first}\n" +
                " graph nodes ${small.second} / ${large.second}\n" +
                " recomputes on a settled draw ${small.third} / ${large.third}\n"
        )

        // The whole point, stated three ways. A hundredfold more data must cost nothing extra,
        // because none of it exists.
        assertEquals(small.first, large.first, "element count grew with the list")
        assertEquals(small.second, large.second, "graph node count grew with the list")
        assertEquals(0L, large.third, "a settled frame recomputed something")
    }

    @Test
    fun `and the same list under forEach does not have that property`() {
        // The comparator, so the numbers above mean something. Same rows, same geometry, built with
        // `content { } + forEach` — which is the right tool when rows size themselves, and is
        // exactly what §11.2 measured at 5.3 ms of read walk at 40 000 elements.
        fun elements(count: Int): Int {
            val app = Recorder()
            val list = List(count) { Item(it, "row $it") }
            app.compose {
                layout {
                    left { 0.0 }; width { app.viewportWidth }
                    top { 0.0 }; height { app.viewportHeight }
                }
                scrollArea(id = "area") {
                    layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                    content {
                        forEach({ list }, key = { it.id }) { _ ->
                            layout { height { 20.0 } }
                        }
                    }
                }
            }
            app.frame()
            return app.tree.size
        }

        // 350 rather than a few thousand, because `content { }` chains each row from the previous
        // one and its height pulls the last of them — so a longer list here would be measuring the
        // chain-depth ceiling instead, which is the *other* half of what virtualisation fixes.
        assertTrue(
            elements(350) > 3 * elements(100),
            "forEach was expected to build the whole list; if this fails the comparison above is " +
                "no longer measuring anything"
        )
    }

    @Test
    fun `mixing virtualContent with a second content block is refused`() {
        val app = Recorder()
        val err = kotlin.runCatching {
            app.compose {
                layout {
                    left { 0.0 }; width { app.viewportWidth }
                    top { 0.0 }; height { app.viewportHeight }
                }
                scrollArea(id = "area") {
                    layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                    virtualContent({ listOf(Item(1, "a")) }, key = { it.id }, rowHeight = 20.0) {}
                    content { }
                }
            }
        }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err.message!!.contains("has content"), "was: ${err.message}")
    }

    @Test
    fun `a nonsensical row height is refused at composition`() {
        val app = Recorder()
        val err = kotlin.runCatching {
            app.compose {
                layout {
                    left { 0.0 }; width { app.viewportWidth }
                    top { 0.0 }; height { app.viewportHeight }
                }
                scrollArea(id = "area") {
                    layout { left { 0.0 }; right { 200.0 }; top { 0.0 }; height { 100.0 } }
                    virtualContent({ listOf(Item(1, "a")) }, key = { it.id }, rowHeight = 0.0) {}
                }
            }
        }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err.message!!.contains("rowHeight"), "was: ${err.message}")
        assertNull(null)
    }
}
