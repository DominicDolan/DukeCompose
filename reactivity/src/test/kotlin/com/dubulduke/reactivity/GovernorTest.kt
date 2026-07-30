package com.dubulduke.reactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Governors: one node computing a whole set of slots at once. See `layout-design.md` §7 and
 * `reactivity-core.md` §2.6.
 */
class GovernorTest {

    private val g = Graph()

    // -------------------------------------------------------------------------------
    // Basics
    // -------------------------------------------------------------------------------

    @Test
    fun `a governor computes all of its claims in one solve`() {
        val total = g.input(100.0, "total")
        val a = g.input(name = "a")
        val b = g.input(name = "b")
        val gov = g.governor(listOf(a, b), "split") {
            val half = read(total) / 2.0
            writeOutput(a, half)
            writeOutput(b, half)
        }

        assertEquals(0, g.solveCount(gov), "must not solve before an output is demanded")
        assertEquals(50.0, g.read(a))
        assertEquals(1, g.solveCount(gov))
        assertEquals(50.0, g.read(b))
        assertEquals(1, g.solveCount(gov), "the second output was already settled")
    }

    @Test
    fun `writing an input the governor read re-solves it once`() {
        val total = g.input(100.0)
        val a = g.input()
        val b = g.input()
        val gov = g.governor(listOf(a, b)) {
            writeOutput(a, read(total) * 0.25)
            writeOutput(b, read(total) * 0.75)
        }
        g.read(a)
        g.setInput(total, 200.0)

        assertEquals(50.0, g.read(a))
        assertEquals(150.0, g.read(b))
        assertEquals(2, g.solveCount(gov))
    }

    @Test
    fun `dependents link to the individual output, not to the governor`() {
        val a = g.input(name = "a")
        val b = g.input(name = "b")
        g.governor(listOf(a, b)) { writeOutput(a, 1.0); writeOutput(b, 2.0) }
        val onA = g.derive { read(a) }
        g.read(onA)

        assertEquals(listOf(a), g.dependenciesOf(onA), "the edge records the output")
        assertEquals(listOf(onA), g.subscribersOf(a))
        assertTrue(g.subscribersOf(b).isEmpty())
    }

    // -------------------------------------------------------------------------------
    // Per-output equality — the reason a governor is not just "one big node"
    // -------------------------------------------------------------------------------

    @Test
    fun `an output that does not change does not wake its own dependents`() {
        val total = g.input(100.0, "total")
        val fixed = g.input(name = "fixed")
        val rest = g.input(name = "rest")
        val gov = g.governor(listOf(fixed, rest), "gov") {
            writeOutput(fixed, 10.0)
            writeOutput(rest, read(total) - 10.0)
        }
        val onFixed = g.derive("onFixed") { read(fixed) * 2.0 }
        val onRest = g.derive("onRest") { read(rest) * 2.0 }

        assertEquals(20.0, g.read(onFixed))
        assertEquals(180.0, g.read(onRest))

        g.setInput(total, 300.0)
        assertEquals(20.0, g.read(onFixed))
        assertEquals(580.0, g.read(onRest))

        assertEquals(2, g.solveCount(gov), "the governor as a whole is coarse-grained")
        assertEquals(1, g.recomputeCount(onFixed), "but its outputs are not")
        assertEquals(2, g.recomputeCount(onRest))
    }

    // -------------------------------------------------------------------------------
    // Self-reads — what makes a freeze loop expressible
    // -------------------------------------------------------------------------------

    @Test
    fun `a governor may read back its own output without subscribing to it`() {
        val a = g.input(name = "a")
        val seen = ArrayList<Double>()
        val gov = g.governor(listOf(a), "gov") {
            seen += read(a)                  // the previous solve's value
            writeOutput(a, 7.0)
            seen += read(a)                  // what this solve just wrote
        }

        assertEquals(7.0, g.read(a))
        assertEquals(listOf(0.0, 7.0), seen)
        assertTrue(g.dependenciesOf(gov).isEmpty(), "no self-edge, or every write would re-dirty it")
        assertEquals(1, g.solveCount(gov))
    }

    // -------------------------------------------------------------------------------
    // Ownership is exclusive — layout-design Q1
    // -------------------------------------------------------------------------------

    @Test
    fun `two governors cannot claim the same slot`() {
        val shared = g.input(name = "shared")
        g.governor(listOf(shared), "first") { writeOutput(shared, 1.0) }

        val e = assertFailsWith<IllegalArgumentException> {
            g.governor(listOf(shared), "second") { writeOutput(shared, 2.0) }
        }
        assertTrue("already claimed" in e.message!!, e.message!!)
    }

    @Test
    fun `a governor cannot claim a slot twice`() {
        val a = g.input()
        assertFailsWith<IllegalArgumentException> {
            g.governor(listOf(a, a)) { writeOutput(a, 1.0) }
        }
    }

    @Test
    fun `a governor cannot claim a derived slot`() {
        val src = g.input(1.0)
        val derived = g.derive { read(src) }
        val e = assertFailsWith<IllegalArgumentException> {
            g.governor(listOf(derived)) { writeOutput(derived, 1.0) }
        }
        assertTrue("its own computation" in e.message!!, e.message!!)
    }

    @Test
    fun `a rejected governor leaves no trace`() {
        val ok = g.input(name = "ok")
        val taken = g.input(name = "taken")
        g.governor(listOf(taken), "first") { writeOutput(taken, 1.0) }
        val before = g.nodeCount

        assertFailsWith<IllegalArgumentException> {
            g.governor(listOf(ok, taken)) { }
        }
        assertEquals(before, g.nodeCount)
        assertNull(g.governorOf(ok), "the valid claim must not have been half-attached")
    }

    // -------------------------------------------------------------------------------
    // Writes are authorised by the running solve, nothing else
    // -------------------------------------------------------------------------------

    @Test
    fun `a governed slot cannot be written as an input`() {
        val a = g.input(name = "a")
        g.governor(listOf(a), "gov") { writeOutput(a, 1.0) }
        val e = assertFailsWith<IllegalArgumentException> { g.setInput(a, 5.0) }
        assertTrue("only its solve may write it" in e.message!!, e.message!!)
    }

    @Test
    fun `writeOutput outside a solve is rejected`() {
        val a = g.input(name = "a")
        g.governor(listOf(a), "gov") { writeOutput(a, 1.0) }
        val e = assertFailsWith<IllegalArgumentException> { g.writeOutput(a, 5.0) }
        assertTrue("no governor is solving" in e.message!!, e.message!!)
    }

    @Test
    fun `a governor cannot write a slot it did not claim`() {
        val mine = g.input(name = "mine")
        val other = g.input(name = "other")
        g.governor(listOf(mine), "gov") {
            writeOutput(mine, 1.0)
            writeOutput(other, 2.0)
        }
        assertFailsWith<IllegalArgumentException> { g.read(mine) }
    }

    @Test
    fun `a derived node cannot write a governed slot`() {
        val a = g.input(name = "a")
        g.governor(listOf(a), "gov") { writeOutput(a, 1.0) }
        val sneaky = g.derive { writeOutput(a, 9.0); 0.0 }
        assertFailsWith<IllegalArgumentException> { g.read(sneaky) }
    }

    @Test
    fun `a solve that skips a claim is an error, not a stale value`() {
        val a = g.input(name = "a")
        val b = g.input(name = "b")
        g.governor(listOf(a, b), "lazy") { writeOutput(a, 1.0) }

        val e = assertFailsWith<IllegalStateException> { g.read(a) }
        assertTrue("did not write its claimed output" in e.message!!, e.message!!)
    }

    // -------------------------------------------------------------------------------
    // Ordering
    // -------------------------------------------------------------------------------

    @Test
    fun `a reader of an output sits above the governor`() {
        val src = g.input(1.0)
        val mid = g.derive("mid") { read(src) + 1.0 }
        val out = g.input(name = "out")
        val gov = g.governor(listOf(out), "gov") { writeOutput(out, read(mid) * 2.0) }
        val reader = g.derive("reader") { read(out) + 1.0 }

        assertEquals(5.0, g.read(reader))
        assertEquals(0, g.graphHeightOf(mid))
        assertEquals(1, g.graphHeightOf(gov))
        assertEquals(1, g.graphHeightOf(out), "an output reports its governor's height")
        assertEquals(2, g.graphHeightOf(reader))
    }

    @Test
    fun `nested governors are fine because their claims are disjoint`() {
        val viewport = g.input(200.0, "viewport")
        val innerSize = g.input(name = "inner.size")
        val outer = g.governor(listOf(innerSize), "outer") {
            writeOutput(innerSize, read(viewport) / 2.0)
        }
        val kid1 = g.input(name = "kid1")
        val kid2 = g.input(name = "kid2")
        val inner = g.governor(listOf(kid1, kid2), "inner") {
            val each = read(innerSize) / 2.0
            writeOutput(kid1, each)
            writeOutput(kid2, each)
        }

        assertEquals(50.0, g.read(kid1))
        g.setInput(viewport, 400.0)
        assertEquals(100.0, g.read(kid2))

        assertEquals(2, g.solveCount(outer))
        assertEquals(2, g.solveCount(inner))
        assertEquals(1, g.graphHeightOf(inner), "inner sits above outer")
    }

    // -------------------------------------------------------------------------------
    // Cycles that pass through a governor
    // -------------------------------------------------------------------------------

    @Test
    fun `a cycle through a governor is broken with the previous value and relaxes`() {
        val sink = RecordingSink()
        g.diagnostics = sink

        // Shrink-wrap: the container's size depends on its child, and the child's size is
        // governed by a solve that reads the container. §7.3's "the container's own width may
        // depend on these very children".
        val slack = g.input(10.0, "slack")
        var child = Slot(-1)
        val container = g.derive("container") { read(child) + read(slack) }
        child = g.input(name = "child")
        g.governor(listOf(child), "gov") { writeOutput(child, read(container) / 2.0) }

        val trace = List(5) { g.read(container) }

        assertTrue(sink.cycles.isNotEmpty(), "the cycle must be reported, not silently absorbed")
        assertEquals(
            listOf("container", "gov"),
            sink.cycles.first().map { it.name },
            "membership is the stack slice, in order",
        )
        // Each read advances one relaxation step towards the fixed point (20.0) rather than
        // spinning or overflowing.
        assertEquals(trace.sorted(), trace, "monotone")
        assertTrue(trace.last() > trace.first())
        assertTrue(trace.all { it <= 20.0 })
    }

    // -------------------------------------------------------------------------------
    // The validation case: flex-shrink with min/max clamps (layout-design §8.4)
    // -------------------------------------------------------------------------------

    private class Item(
        val flex: Double,
        val size: Slot,
        val min: Slot,
        val max: Slot,
    )

    private fun item(name: String, flex: Double, min: Double, max: Double) = Item(
        flex = flex,
        size = g.input(name = "$name.size"),
        min = g.input(min, "$name.min"),
        max = g.input(max, "$name.max"),
    )

    /**
     * A transcription of `FlexGovernor` from `layout-design.md` §7.3. Genuinely iterative:
     * freezing a clamp-violating item returns space to the pool, which changes everyone
     * else's share and may violate another clamp.
     */
    private fun flexRow(container: Slot, items: List<Item>): GovernorHandle =
        g.governor(items.map { it.size }, "flexRow") {
            var free = read(container)
            var pool = items
            while (pool.isNotEmpty()) {
                val total = pool.sumOf { it.flex }
                val frozen = pool.filter { k ->
                    val want = free * k.flex / total
                    val got = want.coerceIn(read(k.min), read(k.max))
                    if (got != want) {
                        writeOutput(k.size, got)
                        true
                    } else false
                }
                if (frozen.isEmpty()) {
                    for (k in pool) writeOutput(k.size, free * k.flex / total)
                    pool = emptyList()
                } else {
                    for (k in frozen) free -= read(k.size)   // read back what was just frozen
                    pool = pool - frozen.toSet()
                }
            }
        }

    @Test
    fun `flex shrink with clamps converges, and only the changed shares propagate`() {
        val container = g.input(100.0, "container")
        val items = listOf(
            item("a", flex = 1.0, min = 0.0, max = 20.0),
            item("b", flex = 1.0, min = 0.0, max = 1e9),
            item("c", flex = 1.0, min = 0.0, max = 1e9),
        )
        val gov = flexRow(container, items)
        val watchers = items.mapIndexed { i, k -> g.derive("watch$i") { read(k.size) } }

        watchers.forEach { g.read(it) }
        assertEquals(listOf(20.0, 40.0, 40.0), items.map { g.read(it.size) })
        assertEquals(1, g.solveCount(gov), "two freeze iterations, one solve")

        g.stats.reset()
        g.setInput(container, 130.0)
        watchers.forEach { g.read(it) }

        assertEquals(listOf(20.0, 55.0, 55.0), items.map { g.read(it.size) })
        assertEquals(2, g.solveCount(gov))
        assertEquals(1, g.recomputeCount(watchers[0]), "a stayed clamped at its max")
        assertEquals(2, g.recomputeCount(watchers[1]))
        assertEquals(2, g.recomputeCount(watchers[2]))
        assertEquals(0L, g.stats.linksAllocated, "the freeze loop's repeated reads reuse links")
        assertEquals(0L, g.stats.linksTrimmed)
    }

    @Test
    fun `a clamp change re-solves without the container moving`() {
        val container = g.input(100.0, "container")
        val items = listOf(
            item("a", flex = 1.0, min = 0.0, max = 20.0),
            item("b", flex = 1.0, min = 0.0, max = 1e9),
        )
        val gov = flexRow(container, items)

        assertEquals(listOf(20.0, 80.0), items.map { g.read(it.size) })

        g.setInput(items[0].max, 60.0)
        assertEquals(listOf(50.0, 50.0), items.map { g.read(it.size) })
        assertEquals(2, g.solveCount(gov), "min and max are dependencies like any other")
    }
}
