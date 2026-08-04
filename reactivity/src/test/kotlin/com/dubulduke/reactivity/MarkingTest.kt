package com.dubulduke.reactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the marking pass costs, and what it must keep costing. `deferred.md` P3.
 *
 * §11.2 measured a 40 000-element resize at 49 997 recomputes plus **89 993 marks**, at 2.5× a cold
 * layout's cost per recompute — "the marking walk and the `CHECK` resolution pass visit far more
 * nodes than actually run". The work that followed was mostly about *how many times a node is
 * touched* rather than how many are marked, so the assertions here are on counts that must not
 * regress: a node marked twice, or a settled node re-entering resolution, is the regression this
 * file exists to catch. `ScaleTest`'s `mark alone` column carries the timings.
 */
class MarkingTest {

    /** A diamond `n` levels deep: every level reads both nodes of the level above. */
    private fun diamond(g: Graph, levels: Int): Pair<Slot, List<Slot>> {
        val root = g.input(1.0, "root")
        var a = root
        var b = root
        val all = ArrayList<Slot>()
        for (i in 0 until levels) {
            val pa = a
            val pb = b
            a = g.derive("a$i") { read(pa) + read(pb) }
            b = g.derive("b$i") { read(pa) - read(pb) }
            all += a
            all += b
        }
        return root to all
    }

    @Test
    fun `a node reachable by many paths is marked exactly once`() {
        // The property raise-at-push had to preserve. Colours are now raised when a node is
        // *pushed* onto the worklist rather than when it is popped, which is what keeps the stack
        // proportional to the nodes marked instead of to the edges traversed — but only if the set
        // raised is identical either way. In a diamond every node is reachable by two paths, so a
        // scheme that raised on pop and deduplicated late would report the same total; one that
        // raised twice would not.
        val g = Graph()
        val (root, all) = diamond(g, levels = 8)
        for (s in all) g.read(s)

        g.stats.reset()
        g.setInput(root, 2.0)
        assertEquals(
            all.size.toLong(), g.stats.marked,
            "16 nodes in the cone, each reachable by several paths, must be marked 16 times"
        )
    }

    @Test
    fun `marking stops at a node that is already at least that colour`() {
        val g = Graph()
        val a = g.input(1.0, "a")
        val b = g.input(1.0, "b")
        val join = g.derive("join") { read(a) + read(b) }
        val tail = g.derive("tail") { read(join) * 2.0 }
        g.read(tail)

        g.stats.reset()
        g.setInput(a, 2.0)
        assertEquals(2L, g.stats.marked, "join DIRTY, tail CHECK")

        // A second write before anything is read. `join` is already DIRTY and `tail` already CHECK,
        // so there is nothing left to say and the walk must not re-traverse the cone.
        g.setInput(b, 2.0)
        assertEquals(2L, g.stats.marked, "the second write re-marked an already-coloured cone")

        assertEquals(8.0, g.read(tail))
    }

    @Test
    fun `a settled read marks nothing and recomputes nothing`() {
        // The steady-state frame, which is what the read walk's 5.3 ms at 40k was made of. Reading
        // an uncoloured node now stops in `settle` rather than entering resolution, so this has to
        // stay free — including the second time, when every flag is already clear.
        val g = Graph()
        val (_, all) = diamond(g, levels = 8)
        for (s in all) g.read(s)

        g.stats.reset()
        repeat(50) { for (s in all) g.read(s) }
        assertEquals(0L, g.stats.recomputed)
        assertEquals(0L, g.stats.marked)
        assertEquals(0L, g.stats.linksAllocated, "a settled read reconciled a dependency edge")
    }

    @Test
    fun `resolution does not descend into dependencies that are not coloured`() {
        // The other early-out, and the one §11.2 named: a `CHECK` node walks its dependencies to
        // find out whether any actually changed, and most of them have not been touched at all. An
        // uncoloured dependency is settled by inspection.
        //
        // Observable as a recompute count rather than a call count: `wide` is CHECK because one of
        // its many dependencies changed, and resolving it must not re-run the others.
        val g = Graph()
        val changing = g.input(1.0, "changing")
        val fixed = (0 until 20).map { i -> g.input(i.toDouble(), "fixed$i") }
        val derived = fixed.map { f -> g.derive { read(f) * 2.0 } }
        val mid = g.derive("mid") { read(changing) + 1.0 }
        val wide = g.derive("wide") {
            var acc = read(mid)
            for (d in derived) acc += read(d)
            acc
        }
        g.read(wide)

        g.stats.reset()
        g.setInput(changing, 2.0)
        g.read(wide)
        // `mid` and `wide`, and none of the twenty untouched branches.
        assertEquals(2L, g.stats.recomputed, "resolution re-ran an uncoloured branch")
    }

    @Test
    fun `an unchanged value stops the propagation, and the marks are already spent`() {
        // The value-equality firewall, which §11.1 found is worth more than the marking scheme —
        // and the honest limit on any of this. The marks are paid *before* anything is evaluated,
        // so a change that dies at the first node has still coloured the whole cone. That is why
        // P3 is a cost of whole-scene invalidation and not something the firewall can remove.
        val g = Graph()
        val input = g.input(1.0, "input")
        val squashed = g.derive("squashed") { if (read(input) > 0.0) 1.0 else 0.0 }
        var tailRuns = 0
        val tail = g.derive("tail") { tailRuns++; read(squashed) * 10.0 }
        g.read(tail)
        assertEquals(1, tailRuns)

        g.stats.reset()
        g.setInput(input, 5.0)
        assertEquals(2L, g.stats.marked, "the whole cone is coloured before anything runs")

        g.read(tail)
        assertEquals(1L, g.stats.recomputed, "only squashed should have re-run")
        assertEquals(1, tailRuns, "the equality firewall did not stop the propagation")
    }

    @Test
    fun `marking a wide fan-out is linear in the fan, not in the edges`() {
        // Regression guard for the worklist itself. Every leaf reads the same two nodes, so a
        // scheme that pushed on traversal rather than on raise would enqueue each leaf twice; the
        // mark count is what pins that, since it counts raises rather than pushes.
        val g = Graph()
        val a = g.input(1.0, "a")
        val b = g.input(1.0, "b")
        val leaves = (0 until 500).map { g.derive("leaf$it") { read(a) + read(b) } }
        for (l in leaves) g.read(l)

        g.stats.reset()
        g.setInput(a, 2.0)
        assertEquals(500L, g.stats.marked)
        for (l in leaves) assertEquals(3.0, g.read(l))
    }

    @Test
    fun `a governor's outputs still carry the colour across to their readers`() {
        // The hop marking has to make (§2.6 rule 3): dependents subscribe to the *outputs*, so
        // propagation crosses the claim list. Worth pinning separately because the outputs are
        // never themselves coloured, and an iterative walk that forgot the hop would silently stop.
        val g = Graph()
        val input = g.input(1.0, "input")
        val x = g.input(0.0, "x")
        val y = g.input(0.0, "y")
        g.governor(listOf(x, y), "gov") {
            val v = read(input)
            writeOutput(x, v * 2.0)
            writeOutput(y, v * 3.0)
        }
        val readsX = g.derive("readsX") { read(x) + 1.0 }
        val readsY = g.derive("readsY") { read(y) + 1.0 }
        assertEquals(3.0, g.read(readsX))
        assertEquals(4.0, g.read(readsY))

        g.stats.reset()
        g.setInput(input, 2.0)
        // The governor DIRTY, and both readers CHECK across the claim list.
        assertTrue(g.stats.marked >= 3, "only ${g.stats.marked} marked; the claim hop was missed")

        assertEquals(5.0, g.read(readsX))
        assertEquals(7.0, g.read(readsY))
    }
}
