package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RecordingSink
import com.dubulduke.reactivity.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Plan step 4: **flex-grow must need no governor.** `layout-design.md` §6.2, §8.4.
 *
 * If it did, level 2 hoisting would not work, governors would become mandatory for ordinary
 * layouts, and the design would lose most of its appeal. So these tests care as much about the
 * *shape* of the dependency graph as about the numbers.
 */
class FlexTest {

    private val g = Graph()
    private val tree = ElementTree(g)
    private val sink = RecordingSink().also { g.diagnostics = it }

    /** The cross axis of a horizontal row: an ElementBuilder block, as `fixed`/`grow` want. */
    private fun ElementBuilder.crossFixed() {
        v { origin { 0.0 }; size { 40.0 } }
    }

    private fun row(
        width: Double = 300.0,
        build: FlexScope.() -> Unit,
    ): Pair<Element, FlexRow> {
        var made: FlexRow? = null
        val root = tree.root("row") {
            h { origin { 0.0 }; size { width } }
            v { origin { 0.0 }; size { 40.0 } }
            made = flexRow(build = build)
        }
        return root to made!!
    }

    private val Element.mainSize: Slot get() = h.slotFor(Property.SIZE)!!
    private val Element.mainOrigin: Slot get() = h.slotFor(Property.ORIGIN)!!

    // -------------------------------------------------------------------------------
    // The headline claim
    // -------------------------------------------------------------------------------

    @Test
    fun `two growing items split the space by flex factor, with no governor`() {
        val (root, _) = row(width = 300.0) {
            grow(1.0, "a") { v { origin { 0.0 }; size { 40.0 } } }
            grow(2.0, "b") { v { origin { 0.0 }; size { 40.0 } } }
        }
        val (a, b) = root.children

        assertEquals(100.0, g.widthOf(a))
        assertEquals(200.0, g.widthOf(b))
        assertEquals(0.0, g.leftOf(a))
        assertEquals(100.0, g.leftOf(b))
        assertEquals(300.0, g.rightOf(b))

        assertTrue(sink.cycles.isEmpty(), "flex-grow must be a false cycle: ${sink.cycles}")
    }

    @Test
    fun `no growing item's size depends on another growing item's size`() {
        // This is the actual content of "the cycle is gone". Both read freeSpace; neither reads
        // the other. Asserting the numbers alone would pass even under a governor.
        val (root, flex) = row(width = 300.0) {
            grow(1.0, "a") { crossFixed() }
            grow(1.0, "b") { crossFixed() }
            grow(1.0, "c") { crossFixed() }
        }
        root.children.forEach { g.widthOf(it) }

        for (item in root.children) {
            val deps = g.dependenciesOf(item.mainSize).toSet()
            assertEquals(
                setOf(flex.totalFlex, flex.freeSpace, item.flexSlot!!),
                deps,
                "${item.id} should read the hoisted pool and its own factor, nothing else",
            )
            for (other in root.children) {
                if (other !== item) {
                    assertTrue(
                        other.mainSize !in deps,
                        "${item.id}.size must not depend on ${other.id}.size",
                    )
                }
            }
        }
    }

    @Test
    fun `freeSpace reads the fixed items only`() {
        val (root, flex) = row(width = 300.0) {
            fixed(mainSize = { 60.0 }, id = "fix") { crossFixed() }
            grow(1.0, "a") { crossFixed() }
            grow(3.0, "b") { crossFixed() }
        }
        val (fix, a, b) = root.children

        assertEquals(240.0, g.read(flex.freeSpace))
        assertEquals(60.0, g.widthOf(fix))
        assertEquals(60.0, g.widthOf(a))
        assertEquals(180.0, g.widthOf(b))

        val deps = g.dependenciesOf(flex.freeSpace).toSet()
        assertTrue(fix.mainSize in deps, "the fixed item's size is part of the pool calculation")
        assertTrue(a.mainSize !in deps, "a growing item's size must not be — that is the cycle")
        assertTrue(b.mainSize !in deps)
        assertTrue(root.childrenSlot in deps, "and the child list, so adding an item invalidates")
    }

    @Test
    fun `every slot evaluates exactly once`() {
        val (root, flex) = row(width = 400.0) {
            fixed(mainSize = { 100.0 }, id = "fix") { crossFixed() }
            grow(1.0, "a") { crossFixed() }
            grow(1.0, "b") { crossFixed() }
        }

        // One pull of the last item's right edge settles the whole row.
        assertEquals(400.0, g.rightOf(root.children.last()))

        assertEquals(1, g.recomputeCount(flex.freeSpace))
        assertEquals(1, g.recomputeCount(flex.totalFlex))
        for (item in root.children) {
            assertEquals(1, g.recomputeCount(item.mainSize), "${item.id} size")
            assertEquals(1, g.recomputeCount(item.mainOrigin), "${item.id} origin")
        }

        // And settling left nothing dirty: a second pull is free.
        g.rightOf(root.children.last())
        assertEquals(1, g.recomputeCount(flex.freeSpace))
    }

    // -------------------------------------------------------------------------------
    // …and the naive version really is a cycle
    // -------------------------------------------------------------------------------

    @Test
    fun `written without hoisting, flex-grow is a genuine cycle`() = withNodeNames {
        // §6.2's starting point, so the claim that hoisting fixes something is demonstrated
        // rather than asserted. Each child computes the free pool itself, which means reading
        // every sibling's width — including the other growing ones.
        val root = tree.root("naive") {
            h { origin { 0.0 }; size { 300.0 } }
            v { origin { 0.0 }; size { 40.0 } }
            repeat(2) { i ->
                child("n$i") {
                    val self = element
                    h {
                        origin { 0.0 }
                        size {
                            val used = siblingsOf(self)
                                .filter { it !== self }
                                .sumOf { widthOf(it) }
                            (widthOf(self.parent!!) - used) / 2.0
                        }
                    }
                    v { origin { 0.0 }; size { 40.0 } }
                }
            }
        }

        g.widthOf(root.children[0])

        assertTrue(sink.cycles.isNotEmpty(), "the naive formulation must cycle")
        assertEquals(
            listOf("naive.n0.h.size", "naive.n1.h.size"),
            sink.cycles.first().map { it.name },
            "and the report should name the two competing slots",
        )
    }

    // -------------------------------------------------------------------------------
    // Reacting to change
    // -------------------------------------------------------------------------------

    @Test
    fun `resizing the container re-splits the space`() {
        val width = g.input(300.0, "width")
        var flex: FlexRow? = null
        val root = tree.root("row") {
            h { origin { 0.0 }; size { read(width) } }
            v { origin { 0.0 }; size { 40.0 } }
            flex = flexRow {
                grow(1.0, "a") { crossFixed() }
                grow(1.0, "b") { crossFixed() }
            }
        }
        val (a, b) = root.children

        assertEquals(150.0, g.widthOf(a))
        g.setInput(width, 900.0)
        assertEquals(450.0, g.widthOf(a))
        assertEquals(450.0, g.widthOf(b))
        assertEquals(450.0, g.leftOf(b))
        assertEquals(2, g.recomputeCount(flex!!.freeSpace))
        assertEquals(1, g.recomputeCount(flex.totalFlex), "flex factors did not move")
    }

    @Test
    fun `changing a flex factor re-splits without touching the container`() {
        // The reason a flex factor is a graph node rather than a plain field (Q2).
        val (root, flex) = row(width = 300.0) {
            grow(1.0, "a") { crossFixed() }
            grow(1.0, "b") { crossFixed() }
        }
        val (a, b) = root.children
        assertEquals(150.0, g.widthOf(a))

        g.setInput(a.flexSlot!!, 2.0)
        assertEquals(200.0, g.widthOf(a))
        assertEquals(100.0, g.widthOf(b))
        assertEquals(2, g.recomputeCount(flex.totalFlex))
        assertEquals(1, g.recomputeCount(flex.freeSpace), "the pool is unchanged")
    }

    @Test
    fun `a fixed item growing shrinks the pool`() {
        val fixWidth = g.input(100.0, "fixWidth")
        var flex: FlexRow? = null
        val root = tree.root("row") {
            h { origin { 0.0 }; size { 300.0 } }
            v { origin { 0.0 }; size { 40.0 } }
            flex = flexRow {
                fixed(mainSize = { read(fixWidth) }, id = "fix") { crossFixed() }
                grow(1.0, "a") { crossFixed() }
            }
        }
        assertEquals(200.0, g.widthOf(root.children[1]))

        g.setInput(fixWidth, 250.0)
        assertEquals(50.0, g.widthOf(root.children[1]))
        assertEquals(250.0, g.leftOf(root.children[1]))
        assertTrue(sink.cycles.isEmpty())
    }

    @Test
    fun `adding an item invalidates the pool through the child list`() {
        val (root, flex) = row(width = 300.0) {
            grow(1.0, "a") { crossFixed() }
        }
        assertEquals(300.0, g.widthOf(root.children[0]))
        assertEquals(1.0, g.read(flex.totalFlex))

        // What incremental composition will do: append, re-publish, re-read.
        val extra = tree.newElement(root, 1, "b")
        ElementBuilder(tree, extra).apply {
            element.flexSlot = g.input(1.0, "b.flex")
            h { origin { 150.0 }; size { read(element.flexSlot!!) * 150.0 } }
            crossFixed()
        }.finish()
        root.childList += extra
        root.snapshot = root.childList.toList()
        g.setRefInput(root.childrenSlot, root.snapshot)

        assertEquals(2.0, g.read(flex.totalFlex), "the new factor joined the total")
        assertEquals(150.0, g.widthOf(root.children[0]), "and the first item gave up half")
    }

    // -------------------------------------------------------------------------------
    // Composition and edge cases
    // -------------------------------------------------------------------------------

    @Test
    fun `a vertical flex column works the same way`() {
        var flex: FlexRow? = null
        val root = tree.root("col") {
            h { origin { 0.0 }; size { 100.0 } }
            v { origin { 0.0 }; size { 200.0 } }
            flex = flexRow(Direction.VERTICAL) {
                fixed(mainSize = { 50.0 }, id = "header") { h { origin { 0.0 }; size { 100.0 } } }
                grow(1.0, "body") { h { origin { 0.0 }; size { 100.0 } } }
            }
        }
        val (header, body) = root.children

        assertEquals(50.0, g.heightOf(header))
        assertEquals(150.0, g.heightOf(body))
        assertEquals(50.0, g.bottomOf(body))
        assertEquals(200.0, g.topOf(body))
        assertEquals(100.0, g.widthOf(body), "the cross axis is the caller's")
        assertEquals(150.0, g.read(flex!!.freeSpace))
    }

    @Test
    fun `a flex row nested inside a flex row is still cycle-free`() {
        var outer: FlexRow? = null
        var inner: FlexRow? = null
        val root = tree.root("outer") {
            h { origin { 0.0 }; size { 600.0 } }
            v { origin { 0.0 }; size { 40.0 } }
            outer = flexRow {
                fixed(mainSize = { 100.0 }, id = "sidebar") { crossFixed() }
                grow(1.0, "main") { crossFixed() }
            }
        }
        // Fill the growing child with its own flex row.
        val main = tree.query("main")
        ElementBuilder(tree, main).apply {
            inner = flexRow(Direction.VERTICAL) {
                fixed(mainSize = { 10.0 }, id = "toolbar") { h { origin { 0.0 }; size { 30.0 } } }
                grow(1.0, "content") { h { origin { 0.0 }; size { 30.0 } } }
            }
        }
        main.snapshot = main.childList.toList()
        g.setRefInput(main.childrenSlot, main.snapshot)

        assertEquals(500.0, g.widthOf(main))
        assertEquals(30.0, g.heightOf(tree.query("content")), "40 tall minus a 10 toolbar")
        assertEquals(500.0, g.read(outer!!.freeSpace))
        assertEquals(30.0, g.read(inner!!.freeSpace))
        assertTrue(sink.cycles.isEmpty(), "nesting must not introduce one: ${sink.cycles}")
    }

    @Test
    fun `a row of only fixed items leaves the pool alone and divides nothing`() {
        val (root, flex) = row(width = 300.0) {
            fixed(mainSize = { 40.0 }, id = "a") { crossFixed() }
            fixed(mainSize = { 60.0 }, id = "b") { crossFixed() }
        }
        assertEquals(0.0, g.read(flex.totalFlex))
        assertEquals(200.0, g.read(flex.freeSpace))
        assertEquals(40.0, g.widthOf(root.children[0]))
        assertEquals(40.0, g.leftOf(root.children[1]))
    }

    @Test
    fun `an empty flex row is harmless`() {
        val (_, flex) = row(width = 300.0) { }
        assertEquals(300.0, g.read(flex.freeSpace))
        assertEquals(0.0, g.read(flex.totalFlex))
    }

    @Test
    fun `a zero flex factor is rejected rather than silently becoming fixed`() {
        assertFailsWith<IllegalArgumentException> {
            row(width = 300.0) { grow(0.0, "a") { crossFixed() } }
        }
    }

    @Test
    fun `a growing item never produces NaN, even with nothing to divide`() {
        // Reachable if a flex factor is driven to zero at runtime. A NaN here would be the worst
        // kind of bug: the graph's Object.is comparison stops it re-dirtying forever, but it
        // would still silently poison every downstream size.
        val (root, flex) = row(width = 300.0) {
            grow(1.0, "a") { crossFixed() }
        }
        assertEquals(300.0, g.widthOf(root.children[0]))

        g.setInput(root.children[0].flexSlot!!, 0.0)
        assertEquals(0.0, g.read(flex.totalFlex))
        assertEquals(0.0, g.widthOf(root.children[0]))
        assertTrue(!g.widthOf(root.children[0]).isNaN())
    }

    @Test
    fun `flex slots are only allocated for items that grow`() {
        val (root, _) = row(width = 300.0) {
            fixed(mainSize = { 100.0 }, id = "fix") { crossFixed() }
            grow(1.0, "a") { crossFixed() }
        }
        assertEquals(null, root.children[0].flexSlot, "a fixed item pays nothing")
        assertTrue(root.children[1].flexSlot != null)
        assertEquals(0.0, g.flexOf(root.children[0]))
        assertEquals(1.0, g.flexOf(root.children[1]))
    }

    @Test
    fun `hoisted nodes are named after their container`() = withNodeNames {
        g.diagnostics = DiagnosticSink.Throwing
        val (_, flex) = row(width = 300.0) { grow(1.0, "a") { crossFixed() } }
        assertEquals("row.freeSpace", g.nameOf(flex.freeSpace))
        assertEquals("row.totalFlex", g.nameOf(flex.totalFlex))
        assertEquals("row.a.flex", g.nameOf(tree.query("a").flexSlot!!))
    }
}
