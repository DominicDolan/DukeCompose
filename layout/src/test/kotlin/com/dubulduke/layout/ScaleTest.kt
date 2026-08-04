package com.dubulduke.layout

import com.dubulduke.reactivity.layoutThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan step 7, first half: **what does the node store cost at 10k and 40k elements?**
 *
 * > Kills the design if… it can't reach frame budget.
 *
 * Step 7 is written as "struct-of-arrays rewrite of the node store; benchmark at 10k / 40k". The
 * benchmark is the half that decides, so it comes first: rewriting the evaluator is expensive and
 * risky, and doing it before measuring would mean rewriting on a hunch.
 *
 * Measuring first was the right call, because the first thing found was not about the node store
 * at all — see [ChainDepthTest]. The evaluator is recursive by design (the call stack *is* the
 * topological order, which is how `reactivity-core.md` solves the diamond problem), so **JVM stack
 * depth is proportional to the longest dependency chain**, and a 2500-row list chained through
 * `prev` overflows a default thread stack before any timing can be taken.
 *
 * So this measures two scene *shapes* at each size — chained (chain depth O(rows)) and indexed
 * (same elements, same nodes, chain depth 1) — which is what separates "40k elements is expensive"
 * from "a 40k-long dependency chain is expensive". Everything runs on a thread with a large stack
 * so the deep case can be measured rather than merely crashing.
 *
 * Two traps in measuring this, both of which caught me:
 *
 *  - **The first frame measures the JIT.** It is also the first execution of every code path in
 *    the evaluator, and it reported 1675 ns/node at 2k against 182 ns/node at 40k — the smaller
 *    scene looking 9x *slower* per node. So the JVM is warmed on throwaway scenes first, and the
 *    reported `full` is a **fresh graph laid out with warm code**: warm code, cold data, every
 *    derived slot running exactly once.
 *  - **There is no way to invalidate this scene from an input.** Changing both viewport dimensions
 *    re-ran 2499 of 8994 slots, because the vertical axis depends on *text*, not on the viewport.
 *    That is the design working as intended (§2.2) and it is exactly why the full-re-layout
 *    comparator has to be a cold graph rather than a big `setInput`.
 *  - **A time without its recompute count is uninterpretable.** Every timing column has a matching
 *    count, so ns-per-recompute can be derived rather than guessed.
 *
 * Budgets: **16.67 ms** at 60 Hz, **6.94 ms** at 144 Hz, and layout is only one phase of a frame.
 * Times are machine- and JIT-specific, so the assertions are on recompute *counts* wherever a
 * count exists; the one timing assertion is deliberately loose.
 */
class ScaleTest {

    private val budget60 = 16.67
    private val budget144 = 6.94

    private class Row(
        val shape: String,
        val elements: Int,
        val nodes: Int,
        val links: Long,
        val chainNodes: Int,
        val heapMb: Double,
        val composeMs: Double,
        val fullMs: Double,
        val fullComputes: Long,
        val resizeMs: Double,
        val resizeComputes: Long,
        val resizeMarked: Long,
        val walkOnlyMs: Double,
        val leafMs: Double,
        val leafComputes: Long,
    )

    private fun bestOf(n: Int, body: (Int) -> Unit): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until n) {
            val t0 = System.nanoTime()
            body(i)
            val dt = (System.nanoTime() - t0) / 1e6
            if (dt < best) best = dt
        }
        return best
    }

    private fun usedHeapMb(): Double {
        val rt = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(15) }
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
    }

    /**
     * Build and lay out throwaway scenes until the evaluator's code paths are compiled, so that
     * the measured full layout below is warm code over cold data rather than a JIT benchmark.
     */
    private fun warmUp() {
        repeat(6) {
            val s = buildScene(rowsForElements(2_000), it % 2 == 0)
            s.layoutAll()
            s.g.setInput(s.viewportW, 1100.0)
            s.layoutAll()
            s.setText(s.rows - 1, SceneText.LONG)
            s.layoutAll()
        }
    }

    /** Peak retained heap for one built-and-settled scene. Measured on its own, because
     *  `usedHeapMb` forces collections and doing that inside a timing loop distorts it. */
    private fun heapForScene(rows: Int, chained: Boolean): Double {
        val before = usedHeapMb()
        var scene: Scene? = buildScene(rows, chained)
        scene!!.layoutAll()
        val after = usedHeapMb()
        scene = null
        return after - before
    }

    private fun measure(targetElements: Int, chained: Boolean): Row {
        val rows = rowsForElements(targetElements)
        val reps = if (targetElements > 20_000) 3 else 5

        val heapMb = heapForScene(rows, chained)

        // Full re-layout: a fresh graph, every derived slot running exactly once, with the
        // evaluator already compiled. This is the comparator — the work a traditional
        // measure/arrange engine does on every frame.
        //
        // Unlike every other column it cannot be looped over one scene, because a settled graph
        // has nothing left to compute. So each repeat rebuilds and only the timing is kept. A
        // single shot was too noisy to interpret: it reported the shallow scene as slower than the
        // deep one at 10k.
        var fullMs = Double.MAX_VALUE
        var composeMs = Double.MAX_VALUE
        var fullComputes = 0L
        var links = 0L
        repeat(reps) {
            val t0 = System.nanoTime()
            val s = buildScene(rows, chained)
            val cMs = (System.nanoTime() - t0) / 1e6
            s.g.stats.reset()
            val t1 = System.nanoTime()
            s.layoutAll()
            val fMs = (System.nanoTime() - t1) / 1e6
            if (fMs < fullMs) {
                fullMs = fMs
                fullComputes = s.g.stats.recomputed
                links = s.g.stats.linksAllocated
            }
            if (cMs < composeMs) composeMs = cMs
        }

        // One settled scene for everything incremental.
        val scene = buildScene(rows, chained)
        val g = scene.g
        scene.layoutAll()

        // The evaluator's recursion depth, as the graph itself reports it: a node's height is one
        // more than the tallest thing it reads, so the deepest slot's height *is* the chain length.
        val chainNodes = g.graphHeightOf(scene.rowList.last().v.slotFor(Property.ORIGIN)!!)

        val lastRow = scene.rows - 1
        val iterations = if (targetElements > 20_000) 10 else 30

        var resizeComputes = 0L
        var resizeMarked = 0L
        val resizeMs = bestOf(iterations) { i ->
            g.setInput(scene.viewportW, 1000.0 + (i % 2) * 40)
            g.stats.reset()
            scene.layoutAll()
            if (g.stats.recomputed > 0) {
                resizeComputes = g.stats.recomputed
                resizeMarked = g.stats.marked
            }
        }

        // The floor: read every rect with nothing changed at all. Pure cost of asking.
        val walkOnlyMs = bestOf(iterations) { scene.layoutAll() }

        var leafComputes = 0L
        val leafMs = bestOf(iterations) { i ->
            scene.setText(lastRow, if (i % 2 == 0) SceneText.LONG else SceneText.SHORT)
            g.stats.reset()
            scene.readRow(lastRow)
            if (g.stats.recomputed > 0) leafComputes = g.stats.recomputed
        }

        return Row(
            shape = if (chained) "chained" else "indexed",
            elements = scene.tree.size,
            nodes = g.nodeCount,
            links = links,
            chainNodes = chainNodes,
            heapMb = heapMb,
            composeMs = composeMs,
            fullMs = fullMs,
            fullComputes = fullComputes,
            resizeMs = resizeMs,
            resizeComputes = resizeComputes,
            resizeMarked = resizeMarked,
            walkOnlyMs = walkOnlyMs,
            leafMs = leafMs,
            leafComputes = leafComputes,
        )
    }

    /**
     * Run [body] on a thread sized for the 40k chained scene's ~10 000-link chain.
     *
     * This used to be a hand-rolled `Thread(…, 256 MB)` — a number picked because smaller ones
     * crashed. It is now [layoutThread], which is the same thing with the arithmetic done the other
     * way round: state the depth, get the stack. Graphs built inside adopt the matching
     * [Graph.maxChainDepth], so the deep scene is measured with the ceiling *raised* rather than
     * with it disabled, which is what a host shipping a chained list would actually do.
     */
    private fun onBigStack(body: () -> Unit) {
        var failure: Throwable? = null
        layoutThread(name = "scale", maxChainDepth = 100_000) {
            try { body() } catch (e: Throwable) { failure = e }
        }.join()
        failure?.let { throw it }
    }

    @Test
    fun `the node store at 2k, 10k and 40k elements`() = onBigStack {
        warmUp()
        val results = listOf(2_000, 10_000, 40_000)
            .flatMap { n -> listOf(measure(n, true), measure(n, false)) }

        val sb = StringBuilder("\n")
        sb.appendLine(" Node store at scale — array-of-objects, best of N, one JVM, one machine")
        sb.appendLine(" " + "-".repeat(104))
        sb.appendLine(
            " %8s %-8s %8s %8s %6s %6s | %7s %7s %7s %7s %8s".format(
                "elements", "shape", "nodes", "links", "chain", "heapMB",
                "compose", "full", "resize", "walk", "leaf",
            )
        )
        for (r in results) {
            sb.appendLine(
                " %8d %-8s %8d %8d %6d %6.1f | %7.1f %7.2f %7.2f %7.2f %8.4f".format(
                    r.elements, r.shape, r.nodes, r.links, r.chainNodes, r.heapMb,
                    r.composeMs, r.fullMs, r.resizeMs, r.walkOnlyMs, r.leafMs,
                )
            )
        }
        sb.appendLine(" ms. full = fresh graph, warm code: every derived slot exactly once.")
        sb.appendLine(" budget 16.67 at 60 Hz, 6.94 at 144 Hz. chain = longest dependency path.")
        sb.appendLine()
        sb.appendLine(" Recomputes, and the derived per-recompute cost")
        sb.appendLine(" " + "-".repeat(104))
        for (r in results) {
            sb.appendLine(
                " %6d %-8s full %7d ea %6.2f ms = %4.0f ns/ea | resize %6d ea + %6d marks %6.2f ms = %4.0f ns/ea | leaf %d ea %.4f ms".format(
                    r.elements, r.shape,
                    r.fullComputes, r.fullMs, r.fullMs * 1e6 / r.fullComputes,
                    r.resizeComputes, r.resizeMarked, r.resizeMs,
                    (r.resizeMs - r.walkOnlyMs) * 1e6 / r.resizeComputes,
                    r.leafComputes, r.leafMs,
                )
            )
        }
        sb.appendLine()
        sb.appendLine(" Budget fractions, and the cost of merely asking")
        sb.appendLine(" " + "-".repeat(104))
        for (r in results) {
            sb.appendLine(
                " %6d %-8s full re-layout %5.1f%% of 60Hz, %6.1f%% of 144Hz | read walk %5.2f ms = %4.0f ns/el, %4.1f%% of 60Hz | %5.0f B/node".format(
                    r.elements, r.shape,
                    100.0 * r.fullMs / budget60, 100.0 * r.fullMs / budget144,
                    r.walkOnlyMs, r.walkOnlyMs * 1e6 / r.elements,
                    100.0 * r.walkOnlyMs / budget60,
                    r.heapMb * 1024 * 1024 / r.nodes,
                )
            )
        }
        println(sb)

        fun row(n: Int, shape: String) =
            results.first { it.elements in (n - n / 5)..(n + n / 5) && it.shape == shape }

        val chained40k = row(40_000, "chained")
        val indexed40k = row(40_000, "indexed")
        val chained2k = row(2_000, "chained")

        // Deterministic, machine-independent, and the actual claim: what a typical frame costs must
        // not grow with the scene at all.
        assertEquals(
            chained2k.leafComputes, chained40k.leafComputes,
            "a leaf change cost ${chained2k.leafComputes} computes at ${chained2k.elements} " +
                "elements but ${chained40k.leafComputes} at ${chained40k.elements}"
        )
        assertEquals(
            chained40k.leafComputes, indexed40k.leafComputes,
            "chain depth changed the cost of a leaf change"
        )

        // A full re-layout re-runs every derived slot, whatever the shape — so the graph is doing
        // the same *work* in both, and any timing difference between them is locality, not work.
        assertEquals(
            chained40k.fullComputes, indexed40k.fullComputes,
            "the two shapes should recompute identically on a full invalidation"
        )

        // The one timing assertion, kept very loose: the typical frame must be nowhere near budget.
        assertTrue(
            indexed40k.leafMs < budget144 / 10.0,
            "a leaf change at 40k elements took ${indexed40k.leafMs} ms against a " +
                "$budget144 ms 144 Hz budget"
        )

        // Chain depth is what it says on the tin, and is the thing that limits scale.
        assertEquals(1, indexed40k.chainNodes, "the indexed scene should be depth 1")
        assertTrue(
            chained40k.chainNodes > 9_000,
            "expected a ~10k-deep chain, measured ${chained40k.chainNodes}"
        )
    }
}
