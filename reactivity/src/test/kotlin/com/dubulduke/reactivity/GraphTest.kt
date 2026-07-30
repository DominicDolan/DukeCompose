package com.dubulduke.reactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphTest {

    private val g = Graph()

    // -------------------------------------------------------------------------------
    // Basics
    // -------------------------------------------------------------------------------

    @Test
    fun `input reads back`() {
        val a = g.input(3.0)
        assertEquals(3.0, g.read(a))
        g.setInput(a, 4.0)
        assertEquals(4.0, g.read(a))
    }

    @Test
    fun `derived node computes lazily`() {
        val a = g.input(2.0)
        val b = g.derive { read(a) * 10.0 }
        assertEquals(0, g.recomputeCount(b), "must not run before it is demanded")
        assertEquals(20.0, g.read(b))
        assertEquals(1, g.recomputeCount(b))
    }

    @Test
    fun `repeated reads are memoised`() {
        val a = g.input(2.0)
        val b = g.derive { read(a) * 10.0 }
        repeat(10) { assertEquals(20.0, g.read(b)) }
        assertEquals(1, g.recomputeCount(b))
    }

    @Test
    fun `writing an input invalidates dependents`() {
        val a = g.input(2.0)
        val b = g.derive { read(a) * 10.0 }
        assertEquals(20.0, g.read(b))
        g.setInput(a, 3.0)
        assertEquals(30.0, g.read(b))
        assertEquals(2, g.recomputeCount(b))
    }

    @Test
    fun `writing an unchanged value does nothing`() {
        val a = g.input(2.0)
        val b = g.derive { read(a) * 10.0 }
        g.read(b)
        g.setInput(a, 2.0)
        g.read(b)
        assertEquals(1, g.recomputeCount(b))
    }

    // -------------------------------------------------------------------------------
    // The diamond problem
    // -------------------------------------------------------------------------------

    @Test
    fun `diamond evaluates the join exactly once`() {
        val a = g.input(1.0)
        val b = g.derive("b") { read(a) * 2.0 }
        val c = g.derive("c") { read(a) * 3.0 }
        val d = g.derive("d") { read(b) + read(c) }

        assertEquals(5.0, g.read(d))
        assertEquals(1, g.recomputeCount(d))

        g.setInput(a, 2.0)
        assertEquals(10.0, g.read(d))
        assertEquals(2, g.recomputeCount(d), "the join must not run once per branch")
    }

    @Test
    fun `settling leaves nothing dirty, so the next read is free`() {
        val a = g.input(1.0)
        val b = g.derive("b") { read(a) * 2.0 }
        val c = g.derive("c") { read(a) * 3.0 }
        val d = g.derive("d") { read(b) + read(c) }

        g.read(d)
        g.setInput(a, 2.0)
        g.read(d)
        assertEquals(2, g.recomputeCount(d))

        // The read the diamond test above never performs, and the one that matters: `d` broke
        // out of its dependency walk early, then re-dirtied itself by reading `c` during its own
        // recompute. If that mark survives, `d` is left dirty after settling and every frame
        // pays for a second evaluation of every node with more than one stale dependency.
        g.read(d)
        assertEquals(2, g.recomputeCount(d), "a settled node must not still be dirty")
    }

    @Test
    fun `diamond never observes a half-updated world`() {
        // b and c must always agree on which value of `a` they were computed from.
        val a = g.input(1.0)
        val b = g.derive("b") { read(a) * 2.0 }
        val c = g.derive("c") { read(a) * 3.0 }
        val seen = mutableListOf<Pair<Double, Double>>()
        val d = g.derive("d") {
            val vb = read(b)
            val vc = read(c)
            seen += vb to vc
            vb + vc
        }

        g.read(d)
        g.setInput(a, 2.0)
        g.read(d)
        g.setInput(a, 5.0)
        g.read(d)

        assertEquals(listOf(2.0 to 3.0, 4.0 to 6.0, 10.0 to 15.0), seen)
    }

    // -------------------------------------------------------------------------------
    // Two-colour marking: the CHECK payoff
    // -------------------------------------------------------------------------------

    @Test
    fun `an unchanged intermediate stops propagation`() {
        val a = g.input(1.0)
        // b's value does not depend on the magnitude of a, only on its sign.
        val b = g.derive("b") { if (read(a) > 0.0) 10.0 else 20.0 }
        val d = g.derive("d") { read(b) + 1.0 }

        assertEquals(11.0, g.read(d))
        assertEquals(1, g.recomputeCount(b))
        assertEquals(1, g.recomputeCount(d))

        g.setInput(a, 2.0)
        assertEquals(11.0, g.read(d))
        assertEquals(2, g.recomputeCount(b), "b's input changed, so b must re-run")
        assertEquals(1, g.recomputeCount(d), "b's value did not change, so d must not")

        g.setInput(a, -1.0)
        assertEquals(21.0, g.read(d))
        assertEquals(3, g.recomputeCount(b))
        assertEquals(2, g.recomputeCount(d), "now b's value changed, so d must re-run")
    }

    @Test
    fun `marking is cheap but not free, and is counted separately from recomputes`() {
        // A wide fan-out where nothing downstream actually changes value.
        val a = g.input(1.0)
        val sign = g.derive("sign") { if (read(a) > 0.0) 1.0 else -1.0 }
        val leaves = (0 until 100).map { i -> g.derive("leaf$i") { read(sign) * i } }
        leaves.forEach { g.read(it) }

        g.stats.reset()
        g.setInput(a, 2.0)
        leaves.forEach { g.read(it) }

        assertEquals(101, g.stats.marked.toInt(), "sign plus every leaf gets marked")
        assertEquals(1, g.stats.recomputed.toInt(), "but only sign re-runs")
    }

    // -------------------------------------------------------------------------------
    // Dynamic dependencies
    // -------------------------------------------------------------------------------

    @Test
    fun `dependencies follow the branch actually taken`() {
        val cond = g.input(0.0)
        val a = g.input(1.0)
        val b = g.input(2.0)
        val d = g.derive("d") { if (read(cond) > 0.0) read(a) else read(b) }

        assertEquals(2.0, g.read(d))
        assertEquals(listOf(cond, b), g.dependenciesOf(d))

        // `a` is not a dependency, so writing it must not even mark d.
        g.stats.reset()
        g.setInput(a, 99.0)
        assertEquals(0, g.stats.marked.toInt())
        assertEquals(2.0, g.read(d))
        assertEquals(1, g.recomputeCount(d))

        // Flip the branch.
        g.setInput(cond, 1.0)
        assertEquals(99.0, g.read(d))
        assertEquals(listOf(cond, a), g.dependenciesOf(d))

        // Now `b` is the one that no longer matters.
        g.stats.reset()
        g.setInput(b, 50.0)
        assertEquals(0, g.stats.marked.toInt())
        assertEquals(99.0, g.read(d))
    }

    @Test
    fun `abandoning a dependency removes the reverse edge`() {
        val cond = g.input(1.0)
        val a = g.input(1.0)
        val b = g.input(2.0)
        val d = g.derive { if (read(cond) > 0.0) read(a) else read(b) }

        g.read(d)
        assertEquals(listOf(d), g.subscribersOf(a))
        assertEquals(emptyList(), g.subscribersOf(b))

        g.setInput(cond, 0.0)
        g.read(d)
        assertEquals(emptyList(), g.subscribersOf(a))
        assertEquals(listOf(d), g.subscribersOf(b))
    }

    // -------------------------------------------------------------------------------
    // Link reconciliation
    // -------------------------------------------------------------------------------

    @Test
    fun `steady state allocates no links`() {
        val inputs = (0 until 20).map { g.input(it.toDouble()) }
        val sum = g.derive("sum") { inputs.sumOf { read(it) } }
        g.read(sum)

        g.stats.reset()
        repeat(50) { i ->
            g.setInput(inputs[i % inputs.size], (i + 100).toDouble())
            g.read(sum)
        }
        assertEquals(50, g.stats.recomputed.toInt())
        assertEquals(0, g.stats.linksAllocated.toInt(), "links must be reused in place")
        assertEquals(0, g.stats.linksTrimmed.toInt())
    }

    @Test
    fun `out-of-order repeat reads do not allocate`() {
        // A governor's freeze loop shape: the same slots re-read across iterations,
        // interleaved rather than consecutively.
        val a = g.input(1.0)
        val b = g.input(2.0)
        val c = g.input(3.0)
        val d = g.derive("d") {
            var acc = 0.0
            repeat(5) { acc += read(a) + read(b) + read(c) }
            acc
        }
        g.read(d)
        assertEquals(3, g.stats.linksAllocated.toInt(), "one link per distinct dependency")
        assertEquals(listOf(a, b, c), g.dependenciesOf(d))

        g.stats.reset()
        g.setInput(a, 10.0)
        g.read(d)
        assertEquals(0, g.stats.linksAllocated.toInt())
    }

    @Test
    fun `adjacent repeat reads do not allocate`() {
        val a = g.input(1.0)
        val d = g.derive { read(a) + read(a) + read(a) }
        g.read(d)
        assertEquals(1, g.stats.linksAllocated.toInt())
        assertEquals(listOf(a), g.dependenciesOf(d))
    }

    // -------------------------------------------------------------------------------
    // Chains
    // -------------------------------------------------------------------------------

    @Test
    fun `long chain propagates, as in a vertical list`() {
        // top { prev?.bottom ?: parent.top } over a long list.
        val parentTop = g.input(0.0)
        val heights = (0 until 500).map { g.input(10.0) }
        var prev: Slot? = null
        val tops = ArrayList<Slot>(500)
        for (i in 0 until 500) {
            val p = prev
            val h = heights[i]
            val top = if (p == null) g.derive("top$i") { read(parentTop) }
            else g.derive("top$i") { read(p) + read(h) }
            tops += top
            prev = top
        }

        assertEquals(4990.0, g.read(tops.last()))

        // Change one height in the middle: only the tail of the chain re-runs.
        g.stats.reset()
        g.setInput(heights[250], 20.0)
        assertEquals(5000.0, g.read(tops.last()))
        assertEquals(250, g.stats.recomputed.toInt(), "only items after the change re-run")
    }

    @Test
    fun `heights track read edges`() {
        val a = g.input(1.0)
        val b = g.derive { read(a) }
        val c = g.derive { read(b) }
        val d = g.derive { read(b) + read(c) }
        g.read(d)

        assertEquals(0, g.graphHeightOf(a), "inputs are height-invisible")
        assertEquals(0, g.graphHeightOf(b))
        assertEquals(1, g.graphHeightOf(c))
        assertEquals(2, g.graphHeightOf(d))
    }

    // -------------------------------------------------------------------------------
    // Cycles
    // -------------------------------------------------------------------------------

    @Test
    fun `a cycle is detected, reported, and broken with the previous value`() {
        val sink = RecordingSink()
        g.diagnostics = sink

        val seed = g.input(5.0)
        var b = Slot(-1)
        val a = g.derive("a") { read(seed) + read(b) }
        b = g.derive("b") { read(a) }

        // First evaluation: `b` re-enters `a`, whose stored value is still its initial 0.0.
        assertEquals(5.0, g.read(a))

        assertEquals(1, sink.cycles.size)
        assertEquals(listOf("a", "b"), sink.cycles[0].map { it.name })

        // Second frame: the cycle now relaxes towards a fixed point one frame at a time.
        g.setInput(seed, 5.0 + 1e-12)
        assertEquals(10.0 + 1e-12, g.read(a))
    }

    @Test
    fun `a cycle relaxes on every read, with no input change to drive it`() {
        g.diagnostics = DiagnosticSink.Ignore
        var b = Slot(-1)
        val a = g.derive("a") { read(b) / 2.0 + 5.0 }   // fixed point: a == b == 10
        b = g.derive("b") { read(a) }

        // A node's own commit propagates back to it around the cycle and re-dirties it. That
        // mark arrives while the node is still inside its own updateIfNecessary, so it must
        // not be cleared on the way out — otherwise the relaxation stalls on the first frame
        // and only an unrelated input write can restart it.
        assertEquals(listOf(5.0, 7.5, 8.75, 9.375), List(4) { g.read(a) })
    }

    @Test
    fun `cycle detection terminates rather than overflowing the stack`() {
        g.diagnostics = DiagnosticSink.Ignore
        var c = Slot(-1)
        val a = g.derive("a") { read(c) + 1.0 }
        val b = g.derive("b") { read(a) + 1.0 }
        c = g.derive("c") { read(b) + 1.0 }
        assertEquals(3.0, g.read(a))
    }

    @Test
    fun `a throwing sink surfaces the cycle for tests and debug builds`() {
        g.diagnostics = DiagnosticSink.Throwing
        var b = Slot(-1)
        val a = g.derive("a") { read(b) }
        b = g.derive("b") { read(a) }
        val e = assertFailsWith<CycleException> { g.read(a) }
        assertEquals(2, e.members.size)
        assertTrue(e.message!!.contains("a"))
    }

    @Test
    fun `self reference is a cycle of one`() {
        val sink = RecordingSink()
        g.diagnostics = sink
        var a = Slot(-1)
        a = g.derive("a") { read(a) + 1.0 }
        assertEquals(1.0, g.read(a))
        assertEquals(1, sink.cycles.size)
        assertEquals(1, sink.cycles[0].size)
    }

    // -------------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------------

    @Test
    fun `NaN does not re-dirty the graph forever`() {
        val a = g.input(0.0)
        val b = g.derive("b") { 1.0 / read(a) - 1.0 / read(a) }   // 0/0 -> NaN
        val d = g.derive("d") { read(b) }
        assertTrue(g.read(d).isNaN())

        g.stats.reset()
        g.setInput(a, 0.5)
        assertEquals(0.0, g.read(d))
        g.setInput(a, 0.0)
        assertTrue(g.read(d).isNaN())

        val before = g.recomputeCount(d)
        g.setInput(a, -0.0)   // -0.0 differs from 0.0, so b re-runs...
        g.read(d)
        assertTrue(g.read(d).isNaN())
        assertEquals(before, g.recomputeCount(d), "...but NaN == NaN, so d must not")
    }

    @Test
    fun `negative zero is a change`() {
        val a = g.input(0.0)
        val b = g.derive { read(a) }
        g.read(b)
        g.setInput(a, -0.0)
        g.read(b)
        assertEquals(2, g.recomputeCount(b))
    }

    @Test
    fun `epsilon suppresses drift below the tolerance`() {
        val a = g.input(100.0)
        val b = g.derive("b") { read(a) }
        g.setEpsilon(b, 0.5)
        val d = g.derive("d") { read(b) * 2.0 }
        assertEquals(200.0, g.read(d))

        g.setInput(a, 100.25)
        g.read(d)
        assertEquals(1, g.recomputeCount(d), "within tolerance: no downstream work")

        g.setInput(a, 101.0)
        g.read(d)
        assertEquals(2, g.recomputeCount(d))
    }

    // -------------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------------

    @Test
    fun `inputs cannot be written from inside a computation`() {
        val a = g.input(1.0)
        val b = g.input(1.0)
        val d = g.derive { setInput(b, read(a)); 0.0 }
        assertFailsWith<IllegalStateException> { g.read(d) }
    }

    @Test
    fun `derived nodes cannot be written as inputs`() {
        val a = g.input(1.0)
        val b = g.derive { read(a) }
        assertFailsWith<IllegalArgumentException> { g.setInput(b, 2.0) }
    }
}
