package com.dubulduke.reactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Graph.dispose] — what `layout-design.md` Q10 was waiting on.
 *
 * Composition could create nodes but never destroy them, so structural change could only grow
 * the graph. The three claims worth pinning: a disposed node's readers are woken, its edges are
 * gone in *both* directions, and its index comes back.
 */
class DisposalTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }

    @Test
    fun `disposal frees the index for reuse`() {
        val before = g.nodeCount
        val a = g.input(1.0, "a")
        assertEquals(before + 1, g.nodeCount)

        g.dispose(a)
        assertEquals(before, g.nodeCount)

        val b = g.input(2.0, "b")
        assertEquals(before + 1, g.nodeCount)
        assertEquals(2.0, g.read(b))
    }

    @Test
    fun `a stale handle to a recycled index is a named error, not a wrong number`() {
        val a = g.input(1.0, "a")
        g.dispose(a)
        val b = g.input(99.0, "b")

        // The whole reason Slot carries a generation: without it this would quietly return 99.
        val e = assertFailsWith<IllegalStateException> { g.read(a) }
        assertTrue("disposed" in e.message!!, e.message!!)
        assertEquals(99.0, g.read(b))
    }

    @Test
    fun `reading a disposed slot whose index was not reused also fails`() {
        val a = g.input(1.0, "a")
        g.dispose(a)
        assertFalse(g.isAlive(a))
        assertFailsWith<IllegalStateException> { g.read(a) }
    }

    @Test
    fun `a reader of a disposed node is woken`() {
        val a = g.input(1.0, "a")
        val b = g.input(10.0, "b")
        var useA = true
        val sum = g.derive("sum") { if (useA) read(a) + read(b) else read(b) }
        assertEquals(11.0, g.read(sum))

        // The reader must re-run: its answer was derived from something that no longer exists.
        useA = false
        g.dispose(a)
        assertEquals(10.0, g.read(sum))
    }

    @Test
    fun `edges are cut in both directions`() {
        val a = g.input(1.0, "a")
        val b = g.derive("b") { read(a) * 2.0 }
        assertEquals(2.0, g.read(b))
        assertEquals(listOf(a), g.dependenciesOf(b))

        g.dispose(a)
        // b no longer depends on a — otherwise a *recycled* a would mark b on every write,
        // which is invalidation noise with no traceable cause.
        assertTrue(g.dependenciesOf(b).isEmpty())
    }

    @Test
    fun `a recycled index does not notify the disposed node's old readers`() {
        val a = g.input(1.0, "a")
        val other = g.input(3.0, "other")
        var useA = true
        val b = g.derive("b") { if (useA) read(a) * 2.0 else read(other) }
        g.read(b)

        useA = false
        g.dispose(a)
        g.read(b) // let b settle after the disposal wake-up, dropping its edge to `a`

        val reused = g.input(0.0, "reused")
        val recomputes = g.recomputeCount(b)
        g.setInput(reused, 5.0)
        g.read(b)
        assertEquals(recomputes, g.recomputeCount(b))
    }

    @Test
    fun `disposing a governor releases its claims`() {
        val x = g.input(0.0, "x")
        val y = g.input(0.0, "y")
        val gov = g.governor(listOf(x, y), "gov") {
            writeOutput(x, 1.0)
            writeOutput(y, 2.0)
        }
        assertEquals(1.0, g.read(x))

        g.dispose(gov)
        // Released, so they are ordinary inputs again and writable from outside.
        g.setInput(x, 7.0)
        assertEquals(7.0, g.read(x))
    }

    @Test
    fun `a claimed output cannot be disposed on its own`() {
        val x = g.input(0.0, "x")
        g.governor(listOf(x), "gov") { writeOutput(x, 1.0) }
        val e = assertFailsWith<IllegalArgumentException> { g.dispose(x) }
        assertTrue("dispose the governor first" in e.message!!, e.message!!)
    }

    @Test
    fun `disposal is refused during evaluation`() {
        val a = g.input(1.0, "a")
        val b = g.input(1.0, "b")
        val d = g.derive("d") {
            g.dispose(b)
            read(a)
        }
        val e = assertFailsWith<IllegalStateException> { g.read(d) }
        assertTrue("inside a computation" in e.message!!, e.message!!)
    }

    @Test
    fun `churning a subgraph does not grow the node array`() {
        val root = g.input(100.0, "root")
        val baseline = g.nodeCount

        repeat(50) {
            val slots = ArrayList<Slot>()
            repeat(10) { i ->
                slots += g.derive("item$i") { read(root) / 10.0 }
            }
            for (s in slots) g.read(s)
            for (s in slots) g.dispose(s)
        }

        // The point of recycling: 500 nodes created, none retained.
        assertEquals(baseline, g.nodeCount)
        assertEquals(baseline + 10, g.nodeCapacity)
        assertEquals(500L, g.stats.disposed)
    }
}
