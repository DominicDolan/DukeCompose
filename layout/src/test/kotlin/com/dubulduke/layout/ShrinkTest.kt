package com.dubulduke.layout

import com.dubulduke.reactivity.Computation
import com.dubulduke.reactivity.CycleMember
import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Flex-shrink inside a shrink-to-fit container. `layout-design.md` §7.3's remaining case.
 *
 * The claim under test is structural, not numeric: a row **sized by its own items** while those
 * items are sized by the row is the one flex case the clamped path cannot express, because its
 * solve reads `sizeOf(container)` and so needs the container's size to be known already. Hoisting
 * the answer into a node the governor *writes* and the container *reads* removes the loop, which is
 * the same move that made flex-grow acyclic one level down.
 *
 * So the tests that matter are the ones asserting no cycle is reported and that the naive
 * formulation genuinely does cycle — the numbers are the easy part.
 */
class ShrinkTest {

    private val reported = ArrayList<List<CycleMember>>()
    private val g = Graph().apply {
        diagnostics = DiagnosticSink { members -> reported += members }
    }
    private val tree = ElementTree(g)

    /** A row of three items with the given bases, bounded by [limit]. */
    private fun row(
        limit: Double,
        vararg bases: Double,
        shrink: Double = 1.0,
        min: Double? = null,
    ): Element = tree.root("root") {
        h { origin { 0.0 }; size { 1000.0 } }
        v { origin { 0.0 }; size { 100.0 } }
        child("row") {
            // The row's own size comes *out* of the solve. Nothing else about it changes: the
            // origin is still an ordinary expression the author writes.
            h {
                origin { 0.0 }
                size { read(rowOf().contentSize) }
            }
            v { origin { 0.0 }; size { 50.0 } }
            rowHolder = flexRow(shrinkToFit = { limit }) {
                bases.forEachIndexed { i, basis ->
                    shrinkable(
                        basis = { basis },
                        shrink = shrink,
                        id = "i$i",
                        min = min?.let { m -> Computation { m } },
                    ) { v { origin { 0.0 }; size { 20.0 } } }
                }
            }
        }
    }

    private var rowHolder: FlexRow? = null
    private fun rowOf(): FlexRow = checkNotNull(rowHolder) { "the row is built inside the axis" }

    private fun sizeOf(id: String) = g.sizeOf(tree.query(id).h)

    @Test
    fun `everything fits, so nothing shrinks and the row is the sum`() {
        row(limit = 300.0, 100.0, 60.0, 40.0)

        assertEquals(100.0, sizeOf("i0"))
        assertEquals(60.0, sizeOf("i1"))
        assertEquals(40.0, sizeOf("i2"))
        assertEquals(200.0, g.sizeOf(tree.query("row").h), "as wide as its items wanted")
    }

    @Test
    fun `overflow is absorbed in proportion to size, not equally`() {
        // 200 wanted into 150: 50 to lose, weights 100:60:40 — so 25:15:10.
        row(limit = 150.0, 100.0, 60.0, 40.0)

        assertEquals(75.0, sizeOf("i0"))
        assertEquals(45.0, sizeOf("i1"))
        assertEquals(30.0, sizeOf("i2"))
        assertEquals(150.0, g.sizeOf(tree.query("row").h), "exactly the bound")
    }

    @Test
    fun `the shrink factor is scaled by the basis, as the spec has it`() {
        // The alternative — weighting by the factor alone — takes the same *absolute* amount from
        // each, so the narrow item hits zero while the wide one is barely touched, and the result
        // looks nothing like proportional compression.
        row(limit = 100.0, 180.0, 20.0)

        // Weights 180:20, so of the 100 to lose, 90 and 10.
        assertEquals(90.0, sizeOf("i0"))
        assertEquals(10.0, sizeOf("i1"))
    }

    @Test
    fun `an item at its floor freezes and the rest absorb its share`() {
        // 300 into 150: 150 to lose, but nothing may go below 60.
        row(limit = 150.0, 100.0, 100.0, 100.0, min = 60.0)

        assertEquals(60.0, sizeOf("i0"))
        assertEquals(60.0, sizeOf("i1"))
        assertEquals(60.0, sizeOf("i2"))
        // All three hit the floor, so the row overflows its bound — and says so, rather than
        // reporting the bound and leaving a visibly too-wide row unexplained.
        assertEquals(180.0, g.sizeOf(tree.query("row").h))
    }

    @Test
    fun `a mix of floors redistributes onto the items that can still give`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 1000.0 } }
            v { origin { 0.0 }; size { 100.0 } }
            child("row") {
                h { origin { 0.0 }; size { read(rowOf().contentSize) } }
                v { origin { 0.0 }; size { 50.0 } }
                rowHolder = flexRow(shrinkToFit = { 120.0 }) {
                    // Wants 100, will not go below 90 — so it can only give 10.
                    shrinkable({ 100.0 }, id = "stiff", min = Computation { 90.0 }) {
                        v { origin { 0.0 }; size { 20.0 } }
                    }
                    shrinkable({ 100.0 }, id = "soft") { v { origin { 0.0 }; size { 20.0 } } }
                }
            }
        }
        // 200 into 120: 80 to lose. Even split would be 40 each, but `stiff` freezes at 90 having
        // given 10, so `soft` absorbs the remaining 70.
        assertEquals(90.0, sizeOf("stiff"))
        assertEquals(30.0, sizeOf("soft"))
        assertEquals(120.0, g.sizeOf(root.children.single().h))
    }

    @Test
    fun `an incompressible item is left alone`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 1000.0 } }
            v { origin { 0.0 }; size { 100.0 } }
            child("row") {
                h { origin { 0.0 }; size { read(rowOf().contentSize) } }
                v { origin { 0.0 }; size { 50.0 } }
                rowHolder = flexRow(shrinkToFit = { 150.0 }) {
                    shrinkable({ 100.0 }, shrink = 0.0, id = "icon") {
                        v { origin { 0.0 }; size { 20.0 } }
                    }
                    shrinkable({ 100.0 }, id = "label") { v { origin { 0.0 }; size { 20.0 } } }
                }
            }
        }
        assertEquals(100.0, sizeOf("icon"), "shrink = 0 means it does not give")
        assertEquals(50.0, sizeOf("label"))
        assertEquals(150.0, g.sizeOf(root.children.single().h))
    }

    @Test
    fun `fixed items are charged against the bound without being claimed`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 1000.0 } }
            v { origin { 0.0 }; size { 100.0 } }
            child("row") {
                h { origin { 0.0 }; size { read(rowOf().contentSize) } }
                v { origin { 0.0 }; size { 50.0 } }
                rowHolder = flexRow(shrinkToFit = { 150.0 }) {
                    // Keeps its own expression, so the claim set stays minimal — the same
                    // reasoning as the clamped path's fixed items.
                    fixed({ 50.0 }, id = "gap") { v { origin { 0.0 }; size { 20.0 } } }
                    shrinkable({ 100.0 }, id = "a") { v { origin { 0.0 }; size { 20.0 } } }
                    shrinkable({ 100.0 }, id = "b") { v { origin { 0.0 }; size { 20.0 } } }
                }
            }
        }
        // 250 wanted into 150: the fixed 50 is not negotiable, so 200 of shrinkable becomes 100.
        assertEquals(50.0, sizeOf("gap"))
        assertEquals(50.0, sizeOf("a"))
        assertEquals(50.0, sizeOf("b"))
        assertEquals(150.0, g.sizeOf(root.children.single().h))
    }

    // -----------------------------------------------------------------------------------
    // The structural claim
    // -----------------------------------------------------------------------------------

    @Test
    fun `a row sized by its own shrinking items is acyclic`() {
        row(limit = 150.0, 100.0, 60.0, 40.0)
        g.sizeOf(tree.query("row").h)
        g.sizeOf(tree.query("i0").h)

        // The whole point. `row.width` reads `contentSize`, which the governor writes from the
        // items' *bases* and an outer bound — never from the items' resolved sizes, and never from
        // the row. No strongly connected component anywhere.
        assertTrue(reported.isEmpty(), "reported: $reported")
    }

    @Test
    fun `the naive formulation does cycle, so the hoist is fixing something`() {
        val cyclic = Graph().apply { diagnostics = DiagnosticSink.Throwing }
        val cyclicTree = ElementTree(cyclic)

        // The obvious way to write it: the row is the sum of its children, and each child divides
        // the row's size. That is `row ← child ← row`, and it is what `clamped = true` would hit
        // if its container were shrink-wrapped.
        val e = assertFailsWith<IllegalStateException> {
            cyclicTree.root("root") {
                h { origin { 0.0 }; size { 1000.0 } }
                v { origin { 0.0 }; size { 100.0 } }
                child("row") {
                    h {
                        origin { 0.0 }
                        size { childrenOf(element).sumOf { c -> sizeOf(c.h) } }
                    }
                    v { origin { 0.0 }; size { 50.0 } }
                    child("a") {
                        h {
                            origin { 0.0 }
                            size { minOf(sizeOf(element.parent!!.h) / 2.0, 100.0) }
                        }
                        v { origin { 0.0 }; size { 20.0 } }
                    }
                }
            }
            cyclic.sizeOf(cyclicTree.query("row").h)
        }
        assertTrue("cycle" in e.message!!.lowercase(), e.message!!)
    }

    @Test
    fun `clamped and shrink-to-fit together is a named error`() {
        val e = assertFailsWith<IllegalArgumentException> {
            tree.root("root") {
                h { origin { 0.0 }; size { 100.0 } }
                v { origin { 0.0 }; size { 100.0 } }
                child("row") {
                    h { origin { 0.0 }; size { 100.0 } }
                    v { origin { 0.0 }; size { 50.0 } }
                    flexRow(clamped = true, shrinkToFit = { 100.0 }) {}
                }
            }
        }
        assertTrue("cycle" in e.message!!, e.message!!)
    }

    @Test
    fun `changing the bound re-solves and nothing else`() {
        val limit = g.input(300.0, "limit")
        tree.root("root") {
            h { origin { 0.0 }; size { 1000.0 } }
            v { origin { 0.0 }; size { 100.0 } }
            child("row") {
                h { origin { 0.0 }; size { read(rowOf().contentSize) } }
                v { origin { 0.0 }; size { 50.0 } }
                rowHolder = flexRow(shrinkToFit = { read(limit) }) {
                    shrinkable({ 100.0 }, id = "a") { v { origin { 0.0 }; size { 20.0 } } }
                    shrinkable({ 100.0 }, id = "b") { v { origin { 0.0 }; size { 20.0 } } }
                }
            }
        }
        assertEquals(100.0, sizeOf("a"))
        val settled = g.stats.recomputed

        // Narrowing the window: an ordinary input write, re-solving one governor.
        g.setInput(limit, 120.0)
        assertEquals(60.0, sizeOf("a"))
        assertEquals(60.0, sizeOf("b"))
        assertTrue(g.stats.recomputed > settled)

        g.setInput(limit, 300.0)
        assertEquals(100.0, sizeOf("a"), "and back again")
    }

    @Test
    fun `an idle frame re-solves nothing`() {
        row(limit = 150.0, 100.0, 60.0, 40.0)
        // Everything, not just one item: a baseline taken before the row's own size has ever been
        // read would count that first evaluation as if it were repeat work.
        sizeOf("i0"); sizeOf("i1"); sizeOf("i2")
        g.sizeOf(tree.query("row").h)
        val settled = g.stats.recomputed

        repeat(20) {
            sizeOf("i0"); sizeOf("i1"); sizeOf("i2")
            g.sizeOf(tree.query("row").h)
        }
        assertEquals(settled, g.stats.recomputed, "a settled shrink row is free to read")
    }
}
