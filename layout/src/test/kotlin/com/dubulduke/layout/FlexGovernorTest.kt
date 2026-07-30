package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RecordingSink
import com.dubulduke.reactivity.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Plan step 5, finished: a **real container** driving a governor, rather than slots built by hand.
 *
 * `layout-design.md` §7.3, §8.4. Flex-shrink with min/max clamps is the case hoisting cannot
 * reach, because freezing a clamp-violating item returns space to the pool and changes everyone
 * else's share. The interesting assertions here are not the numbers — they are that the row is
 * governed only when clamps make it necessary, that the claim set is the minimum one, and that
 * the freeze loop is a *bounded* loop rather than relaxation.
 */
class FlexGovernorTest {

    private val g = Graph()
    private val tree = ElementTree(g)
    private val sink = RecordingSink().also { g.diagnostics = it }

    private fun row(
        width: Double = 300.0,
        clamped: Boolean = true,
        build: FlexScope.() -> Unit,
    ): Pair<Element, FlexRow> {
        var made: FlexRow? = null
        val root = tree.root("row") {
            h { origin { 0.0 }; size { width } }
            v { origin { 0.0 }; size { 40.0 } }
            made = flexRow(clamped = clamped, build = build)
        }
        return root to made!!
    }

    private fun ElementBuilder.cross() {
        v { origin { 0.0 }; size { 40.0 } }
    }

    private val Element.mainSize: Slot get() = h.slotFor(Property.SIZE)!!

    // -------------------------------------------------------------------------------
    // Mode is a composition-time decision, and visible
    // -------------------------------------------------------------------------------

    @Test
    fun `an unclamped row is hoisted and has no governor at all`() {
        val (root, row) = row(clamped = false) {
            grow(1.0, "a") { cross() }
            grow(1.0, "b") { cross() }
        }
        assertEquals(FlexMode.HOIST, row.mode)
        assertTrue(!row.isGoverned)
        for (c in root.children) assertNull(g.governorOf(c.mainSize))

        // The step-4 result is untouched by the governor's existence: still a plain DAG.
        assertEquals(150.0, g.widthOf(root.children[0]))
        assertEquals(150.0, g.widthOf(root.children[1]))
    }

    @Test
    fun `a clamped row governs its growing items and nothing else`() {
        val (root, row) = row {
            fixed(mainSize = { 100.0 }, id = "pad") { cross() }
            grow(1.0, "a", max = { 20.0 }) { cross() }
            grow(1.0, "b") { cross() }
        }
        assertEquals(FlexMode.GOVERN, row.mode)
        val (pad, a, b) = root.children

        // The minimum claim set. §7.3's sketch claims every child's size; a fixed item's size is
        // not contested, so claiming it would mean the governor deciding something it does not
        // decide — and would collide sooner with a nested container's claims.
        assertEquals(setOf(a.mainSize, b.mainSize), g.claimsOf(row.governor).toSet())
        assertNull(g.governorOf(pad.mainSize))
        assertEquals(row.governor, g.governorOf(a.mainSize))
        assertEquals(row.governor, g.governorOf(b.mainSize))
    }

    @Test
    fun `the hoisted pool nodes do not exist on a governed row`() {
        val (_, row) = row {
            grow(1.0, "a", max = { 20.0 }) { cross() }
        }
        // Not merely unused — absent. The freeze loop mutates the pool as items freeze, so there
        // is no single value for a node to hold, which is exactly why hoisting cannot do this job.
        assertFailsWith<IllegalStateException> { row.freeSpace }
        assertFailsWith<IllegalStateException> { row.totalFlex }
    }

    @Test
    fun `clamps without clamped = true are rejected at composition`() {
        val e = assertFailsWith<IllegalArgumentException> {
            row(clamped = false) { grow(1.0, "a", max = { 20.0 }) { cross() } }
        }
        assertTrue("clamped = true" in e.message!!, e.message!!)
    }

    // -------------------------------------------------------------------------------
    // The freeze loop
    // -------------------------------------------------------------------------------

    @Test
    fun `one clamped item freezes and returns its space to the others`() {
        // 300 wide, three equal-flex items. Naively 100 each, but `a` caps at 20, so the 80 it
        // gives back is redistributed: b and c take 140 each. One freeze pass, then settle.
        val (root, row) = row(width = 300.0) {
            grow(1.0, "a", max = { 20.0 }) { cross() }
            grow(1.0, "b") { cross() }
            grow(1.0, "c") { cross() }
        }
        val (a, b, c) = root.children

        assertEquals(20.0, g.widthOf(a))
        assertEquals(140.0, g.widthOf(b))
        assertEquals(140.0, g.widthOf(c))
        assertEquals(300.0, g.widthOf(a) + g.widthOf(b) + g.widthOf(c))
        assertEquals(1, g.solveCount(row.governor), "two freeze passes, but one solve")
    }

    @Test
    fun `cascading clamps converge, each pass freezing at least one item`() {
        // 300 wide. Pass 1: 75 each; a caps at 20 → freeze, 280 left over three.
        // Pass 2: 93.33 each; b caps at 50 → freeze, 230 left over two.
        // Pass 3: 115 each, both within their clamps → settle. Three passes, one solve.
        val (root, row) = row(width = 300.0) {
            grow(1.0, "a", max = { 20.0 }) { cross() }
            grow(1.0, "b", max = { 50.0 }) { cross() }
            grow(1.0, "c") { cross() }
            grow(1.0, "d") { cross() }
        }
        val (a, b, c, d) = root.children

        assertEquals(20.0, g.widthOf(a))
        assertEquals(50.0, g.widthOf(b))
        assertEquals(115.0, g.widthOf(c))
        assertEquals(115.0, g.widthOf(d))
        assertEquals(300.0, g.widthOf(a) + g.widthOf(b) + g.widthOf(c) + g.widthOf(d))
        assertEquals(1, g.solveCount(row.governor))
    }

    @Test
    fun `a min clamp grows an item past its share, taking space from the rest`() {
        // Flex-shrink's direction: only 100 to divide, but `a` insists on 80.
        val (root, _) = row(width = 100.0) {
            grow(1.0, "a", min = { 80.0 }) { cross() }
            grow(1.0, "b") { cross() }
        }
        val (a, b) = root.children
        assertEquals(80.0, g.widthOf(a))
        assertEquals(20.0, g.widthOf(b))
    }

    @Test
    fun `min wins a contradictory clamp pair instead of throwing`() {
        // `coerceIn(80.0, 20.0)` throws. A layout that dies on a bad clamp pair is worse than one
        // that picks, and CSS resolves min against max the same way.
        val (root, _) = row(width = 300.0) {
            grow(1.0, "a", min = { 80.0 }, max = { 20.0 }) { cross() }
            grow(1.0, "b") { cross() }
        }
        assertEquals(80.0, g.widthOf(root.children[0]))
    }

    @Test
    fun `every item clamped leaves the row over or under its container, honestly`() {
        // Both cap at 20, so the row uses 40 of 300. The governor does not invent a filler: it
        // reports what the clamps say. Silently stretching one to absorb the remainder is the
        // class of fudge this design exists to avoid.
        val (root, _) = row(width = 300.0) {
            grow(1.0, "a", max = { 20.0 }) { cross() }
            grow(1.0, "b", max = { 20.0 }) { cross() }
        }
        val (a, b) = root.children
        assertEquals(20.0, g.widthOf(a))
        assertEquals(20.0, g.widthOf(b))
        assertEquals(40.0, g.rightOf(b))
    }

    @Test
    fun `the freeze loop is bounded by the item count, not by a convergence test`() {
        // 64 items, every one clamped tighter than its share, so each pass freezes exactly one.
        // The loop must still terminate in a single solve — this is not relaxation, and there is
        // no iteration cap to hit (contrast §7.4's RelaxGovernor).
        val n = 64
        val (root, row) = row(width = 10_000.0) {
            for (i in 0 until n) {
                val cap = 1.0 + i
                grow(1.0, "i$i", max = { cap }) { cross() }
            }
        }
        assertEquals(n, root.children.size)
        assertEquals(0, g.solveCount(row.governor), "nothing demanded a size yet")
        // The last item's cap is 64 and its unclamped share would be far larger, so all freeze.
        for ((i, c) in root.children.withIndex()) assertEquals(1.0 + i, g.widthOf(c))
        assertEquals(1, g.solveCount(row.governor), "64 freeze passes, still one solve")
    }

    // -------------------------------------------------------------------------------
    // Governed outputs behave like ordinary slots to everything downstream
    // -------------------------------------------------------------------------------

    @Test
    fun `positions still chain through prev across governed sizes`() {
        val (root, _) = row(width = 300.0) {
            grow(1.0, "a", max = { 20.0 }) { cross() }
            grow(1.0, "b") { cross() }
            grow(1.0, "c") { cross() }
        }
        val (a, b, c) = root.children
        assertEquals(0.0, g.leftOf(a))
        assertEquals(20.0, g.leftOf(b))
        assertEquals(160.0, g.leftOf(c))
        assertEquals(300.0, g.rightOf(c))
    }

    @Test
    fun `a container resize re-solves once and moves every item`() {
        val w = g.input(300.0, "viewport")
        var row: FlexRow? = null
        val root = tree.root("row") {
            h { origin { 0.0 }; size { read(w) } }
            v { origin { 0.0 }; size { 40.0 } }
            row = flexRow(clamped = true) {
                grow(1.0, "a", max = { 20.0 }) { cross() }
                grow(1.0, "b") { cross() }
            }
        }
        val (a, b) = root.children
        assertEquals(20.0, g.widthOf(a))
        assertEquals(280.0, g.widthOf(b))
        assertEquals(1, g.solveCount(row!!.governor))

        g.setInput(w, 500.0)
        assertEquals(20.0, g.widthOf(a))
        assertEquals(480.0, g.widthOf(b))
        assertEquals(2, g.solveCount(row!!.governor))
    }

    @Test
    fun `an unchanged output does not wake its readers`() {
        // Fine-grained outputs: `a` stays pinned at its cap across the resize, so anything reading
        // only `a` must not re-run. This is the property that makes one governor with many outputs
        // different from one node computing a tuple.
        val w = g.input(300.0, "viewport")
        var row: FlexRow? = null
        val root = tree.root("row") {
            h { origin { 0.0 }; size { read(w) } }
            v { origin { 0.0 }; size { 40.0 } }
            row = flexRow(clamped = true) {
                grow(1.0, "a", max = { 20.0 }) { cross() }
                grow(1.0, "b") { cross() }
            }
        }
        val (a, b) = root.children
        val watchA = g.derive("watchA") { widthOf(a) }
        val watchB = g.derive("watchB") { widthOf(b) }
        g.read(watchA); g.read(watchB)
        assertEquals(1, g.recomputeCount(watchA))
        assertEquals(1, g.recomputeCount(watchB))

        g.setInput(w, 500.0)
        g.read(watchA); g.read(watchB)
        assertEquals(1, g.recomputeCount(watchA), "a stayed clamped at 20, so its reader is idle")
        assertEquals(2, g.recomputeCount(watchB), "b absorbed the change")
        assertEquals(2, g.solveCount(row!!.governor))
    }

    @Test
    fun `a clamp is a dependency like any other`() {
        val cap = g.input(20.0, "cap")
        val (root, row) = row(width = 300.0) {
            grow(1.0, "a", max = { read(cap) }) { cross() }
            grow(1.0, "b") { cross() }
        }
        val (a, b) = root.children
        assertEquals(20.0, g.widthOf(a))
        assertEquals(280.0, g.widthOf(b))

        g.setInput(cap, 200.0)
        assertEquals(150.0, g.widthOf(a), "the cap no longer binds, so a takes its full share")
        assertEquals(150.0, g.widthOf(b))
        assertEquals(2, g.solveCount(row.governor))
    }

    @Test
    fun `a flex factor change re-solves the row`() {
        val (root, row) = row(width = 300.0) {
            grow(1.0, "a") { cross() }
            grow(2.0, "b") { cross() }
        }
        val (a, b) = root.children
        assertEquals(100.0, g.widthOf(a))
        assertEquals(200.0, g.widthOf(b))
        g.setInput(a.flexSlot!!, 5.0)
        assertEquals(counted(300.0 * 5 / 7), counted(g.widthOf(a)))
        assertEquals(2, g.solveCount(row.governor))
    }

    private fun counted(d: Double) = Math.round(d * 1e6) / 1e6

    // -------------------------------------------------------------------------------
    // Degenerate and structural cases
    // -------------------------------------------------------------------------------

    @Test
    fun `an all-fixed clamped row installs no governor`() {
        val (root, row) = row(width = 300.0) {
            fixed(mainSize = { 100.0 }, id = "a") { cross() }
            fixed(mainSize = { 50.0 }, id = "b") { cross() }
        }
        assertEquals(FlexMode.GOVERN, row.mode)
        assertTrue(!row.isGoverned, "nothing is contested, so there is nothing to govern")
        assertFailsWith<IllegalStateException> { row.governor }
        assertEquals(100.0, g.widthOf(root.children[0]))
        assertEquals(150.0, g.rightOf(root.children[1]))
    }

    @Test
    fun `a governed row reports no cycle`() {
        val (root, _) = row(width = 300.0) {
            grow(1.0, "a", max = { 20.0 }) { cross() }
            grow(1.0, "b") { cross() }
        }
        for (c in root.children) g.widthOf(c)
        assertEquals(emptyList(), sink.cycles, "the knot is inside the solve, not in the graph")
    }

    @Test
    fun `nested governed rows have disjoint claims and both resolve`() {
        // The inner container's own size is claimed by the outer governor; its children's sizes by
        // the inner one. Disjoint, so `Node.firewall` — one field, hence one owner — is enough
        // (`layout-design.md` Q1). Outer: 400 wide, pad 100 fixed, inner grows into the rest.
        var outer: FlexRow? = null
        var inner: FlexRow? = null
        val root = tree.root("outer") {
            h { origin { 0.0 }; size { 400.0 } }
            v { origin { 0.0 }; size { 40.0 } }
            outer = flexRow(clamped = true) {
                fixed(mainSize = { 100.0 }, id = "pad") { cross() }
                grow(1.0, "inner") {
                    cross()
                    inner = flexRow(clamped = true) {
                        grow(1.0, "x", max = { 60.0 }) { cross() }
                        grow(1.0, "y") { cross() }
                    }
                }
            }
        }
        val innerEl = tree.query("inner")
        val x = tree.query("x")
        val y = tree.query("y")

        assertEquals(300.0, g.widthOf(innerEl))
        assertEquals(60.0, g.widthOf(x), "x caps out")
        assertEquals(240.0, g.widthOf(y), "and y absorbs the remainder of the inner row")

        val outerClaims = g.claimsOf(outer!!.governor).toSet()
        val innerClaims = g.claimsOf(inner!!.governor).toSet()
        assertEquals(setOf(innerEl.mainSize), outerClaims)
        assertEquals(setOf(x.mainSize, y.mainSize), innerClaims)
        assertEquals(emptySet(), outerClaims intersect innerClaims)
        assertSame(root, innerEl.parent)
    }

    @Test
    fun `two rows cannot claim the same item`() {
        // Not "forbidden by a check we remembered to write" — a node has exactly one firewall
        // field, so an overlapping claim is unrepresentable and the constructor is where that
        // shows up.
        var a: Element? = null
        tree.root("row") {
            h { origin { 0.0 }; size { 300.0 } }
            v { origin { 0.0 }; size { 40.0 } }
            flexRow(clamped = true) { a = grow(1.0, "a", max = { 20.0 }) { cross() } }
        }
        val e = assertFailsWith<IllegalArgumentException> {
            g.governor(listOf(a!!.mainSize), "interloper") { writeOutput(a!!.mainSize, 0.0) }
        }
        assertTrue("already claimed" in e.message!!, e.message!!)
    }

    @Test
    fun `a vertical governed column works the same way`() {
        var row: FlexRow? = null
        val root = tree.root("col") {
            h { origin { 0.0 }; size { 100.0 } }
            v { origin { 0.0 }; size { 300.0 } }
            row = flexRow(Direction.VERTICAL, clamped = true) {
                grow(1.0, "a", max = { 20.0 }) { h { origin { 0.0 }; size { 100.0 } } }
                grow(1.0, "b") { h { origin { 0.0 }; size { 100.0 } } }
            }
        }
        val (a, b) = root.children
        assertEquals(20.0, g.heightOf(a))
        assertEquals(280.0, g.heightOf(b))
        assertEquals(20.0, g.bottomOf(b))
        assertEquals(row!!.governor, g.governorOf(b.v.slotFor(Property.SIZE)!!))
    }

    @Test
    fun `the governed row reads the container and the fixed items, and no growing size`() {
        val (root, row) = row(width = 300.0) {
            fixed(mainSize = { 100.0 }, id = "pad") { cross() }
            grow(1.0, "a", max = { 20.0 }) { cross() }
            grow(1.0, "b") { cross() }
        }
        val (pad, a, b) = root.children
        for (c in root.children) g.widthOf(c)

        val deps = g.dependenciesOf(row.governor).toSet()
        assertTrue(root.mainSize in deps, "the container's size is the outer part of the problem")
        assertTrue(pad.mainSize in deps, "a fixed item's size is subtracted from the pool")
        // Its own outputs are not dependencies: a governor that subscribed to what it writes
        // would mark itself dirty on every write.
        assertTrue(a.mainSize !in deps)
        assertTrue(b.mainSize !in deps)
    }
}
