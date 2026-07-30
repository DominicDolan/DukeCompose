package com.dubulduke.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan step 6: **measure incremental frame cost.** The last of the three go/no-go checkpoints.
 *
 * > Kills the design if incremental cost isn't materially below full re-layout. The main payoff
 * > is absent.
 *
 * "Reverse-edge dirty propagation" — the other half of step 6 — is already built: `setInput`
 * walks subscriber lists to raise colours, which is what the `marked` counter counts. So what is
 * left is the measurement, and the measurement is the part that can invalidate the design.
 *
 * The metric is **recompute counts, not wall time.** Wall time on a JVM with no warmup contract
 * measures the JIT as much as the algorithm, and it cannot distinguish "we re-ran 4 slots" from
 * "we re-ran 8000 slots quickly". Recomputes are exact, deterministic, and are the thing the
 * design claims to minimise; they are printed alongside a timing column that is informational
 * only and never asserted.
 *
 * The comparator for "full re-layout" is the **cold frame**: every derived slot evaluated exactly
 * once, which is precisely the work a traditional measure/arrange engine does on every frame. A
 * resize looked like the obvious baseline and is not one — it turns out to leave most of the scene
 * alone, so using it would have flattered the result. It is reported as its own row instead.
 */
class IncrementalCostTest {

    private val defaultRows = 500
    private val shortText = SceneText.SHORT
    private val otherShortText = SceneText.OTHER_SHORT
    private val longText = SceneText.LONG

    /** Recomputes attributable to one frame, having already settled the previous one. */
    private fun frame(scene: Scene, change: () -> Unit): Long {
        scene.g.stats.reset()
        change()
        scene.layoutAll()
        return scene.g.stats.recomputed
    }

    // -------------------------------------------------------------------------------

    @Test
    fun `incremental frame cost is materially below full re-layout`() {
        val scene = buildScene(defaultRows)
        val g = scene.g

        // The comparator, and it is the *cold* frame rather than a resize: every derived slot
        // evaluated exactly once. That is precisely the work a traditional measure/arrange engine
        // does on every frame, so it is the honest "full re-layout" number. Asserting each slot
        // runs at most once is also worth having on its own — a slot running twice here would mean
        // the pull evaluator is re-entering something.
        g.stats.reset()
        scene.layoutAll()
        val fullRelayout = g.stats.recomputed
        val slots = g.nodeCount.toLong()
        assertTrue(fullRelayout <= slots, "cold frame ran $fullRelayout computes over $slots nodes")

        // A resize is *not* a full re-layout, which is the first thing the measurement showed and
        // is worth reporting as its own row rather than as the baseline. It invalidates the whole
        // horizontal axis, but the labels still wrap to the same number of lines, so every
        // vertical slot in the scene survives untouched.
        val resize = frame(scene) { g.setInput(scene.viewportW, 1000.0) }

        // Best case: the last row's text. Nothing is positioned after it.
        val lastRowText = frame(scene) {
            scene.setText(defaultRows - 1, longText)
        }

        // The chain cost, at the worst point: a height change halfway down moves every row below.
        val middleRowText = frame(scene) {
            scene.setText(defaultRows / 2, longText)
        }

        // The equality firewall: a *different* string that wraps to the same height. The measured
        // size compares equal, so nothing downstream of the measurement wakes at all.
        val sameHeightText = frame(scene) {
            scene.setText(defaultRows / 2 - 1, otherShortText)
        }

        // A no-op write. Must be entirely free, including the marking pass.
        g.stats.reset()
        g.setInput(scene.viewportW, 1000.0)
        scene.layoutAll()
        val noop = g.stats.recomputed
        val noopMarks = g.stats.marked

        println(
            """
            |
            | Incremental cost, $defaultRows rows / ${scene.tree.size} elements / $slots graph nodes
            | ---------------------------------------------------------------------
            | full re-layout (every slot once) ${fullRelayout.pad()} computes   1.0000x
            | viewport resize                  ${resize.pad()} computes   ${ratio(resize, fullRelayout)}
            | text change, last row            ${lastRowText.pad()} computes   ${ratio(lastRowText, fullRelayout)}
            | text change, middle row          ${middleRowText.pad()} computes   ${ratio(middleRowText, fullRelayout)}
            | text change, same wrapped height ${sameHeightText.pad()} computes   ${ratio(sameHeightText, fullRelayout)}
            | no-op input write                ${noop.pad()} computes   ($noopMarks marks)
            """.trimMargin()
        )

        assertEquals(0L, noop, "writing a value a slot already holds must do nothing")
        assertEquals(0L, noopMarks, "and must not even mark")

        // The step-6 verdict. A localised change at the end of the list touches a tiny constant
        // number of slots; the same change halfway down touches roughly half the list, which is
        // the chain's inherent cost and not the evaluator's overhead.
        assertTrue(
            lastRowText * 100 < fullRelayout,
            "a leaf text change cost $lastRowText against $fullRelayout for a full re-layout; " +
                "incremental is not materially cheaper"
        )
        assertTrue(
            middleRowText < fullRelayout / 3,
            "a mid-list text change cost $middleRowText against $fullRelayout"
        )
        assertTrue(
            sameHeightText * 100 < fullRelayout,
            "a text change that does not change wrapped height cost $sameHeightText; the " +
                "value-equality firewall is not holding"
        )
        // Even the whole-axis case is well under a full re-layout, because the two axes are
        // independent nodes rather than one interleaved pass (`layout-design.md` §2.2).
        assertTrue(
            resize < fullRelayout / 2,
            "a resize cost $resize against $fullRelayout; the axes are not independent"
        )
    }

    @Test
    fun `a localised change costs the same regardless of list length`() {
        // The sharpest form of the step-6 claim, and the one that cannot be faked by a constant
        // factor: change the *last* row's text in lists of very different sizes. If the evaluator
        // had any per-frame walk over all slots, this number would grow with the list.
        val costs = listOf(50, 500).map { n ->
            val scene = buildScene(n)
            scene.layoutAll()
            frame(scene) { scene.setText(n - 1, longText) }
        }
        println(" localised change: 50 rows -> ${costs[0]} computes, 500 rows -> ${costs[1]}")
        assertEquals(costs[0], costs[1], "cost of a leaf change must not depend on list length")
    }

    /**
     * Wall clock, informational and unasserted except for one very loose ordering check.
     *
     * This is here because the recompute counts above, on their own, would mislead. Reading every
     * rect is O(elements) *whatever* changed — 2003 elements times four reads is eight thousand
     * settle calls even on a frame that recomputes three slots — so the full-walk timings are
     * dominated by the walk and hide most of the incremental win. The third row is the one that
     * shows the real cost, and the gap between rows two and three is the argument for the draw
     * side keeping a dirty list rather than re-reading the tree (plan step 9).
     */
    @Test
    fun `frame time, informational`() {
        val scene = buildScene(defaultRows)
        val g = scene.g
        val lastRow = scene.rowList[defaultRows - 1]

        fun bestOf(n: Int, body: (Int) -> Unit): Double {
            var best = Double.MAX_VALUE
            for (i in 0 until n) {
                val t0 = System.nanoTime()
                body(i)
                val dt = (System.nanoTime() - t0) / 1e6
                if (dt < best) best = dt
            }
            return best
        }

        // Warm the JIT on both paths before measuring either.
        bestOf(200) { i ->
            g.setInput(scene.viewportW, 1000.0 + (i % 2) * 40)
            scene.layoutAll()
        }

        val resizeMs = bestOf(50) { i ->
            g.setInput(scene.viewportW, 1000.0 + (i % 2) * 40)
            scene.layoutAll()
        }
        val textFullWalkMs = bestOf(50) { i ->
            scene.setText(defaultRows - 1, if (i % 2 == 0) longText else shortText)
            scene.layoutAll()
        }
        val textLocalMs = bestOf(50) { i ->
            scene.setText(defaultRows - 1, if (i % 2 == 0) longText else shortText)
            g.leftOf(lastRow) + g.topOf(lastRow) + g.rightOf(lastRow) + g.bottomOf(lastRow)
        }

        println(
            """
            | Frame time, ${scene.tree.size} elements (best of 50, informational)
            | ---------------------------------------------------------------------
            | resize            + read every rect  ${"%8.4f".format(resizeMs)} ms
            | leaf text change  + read every rect  ${"%8.4f".format(textFullWalkMs)} ms
            | leaf text change  + read one row     ${"%8.4f".format(textLocalMs)} ms
            """.trimMargin()
        )

        assertTrue(
            textLocalMs < resizeMs,
            "a leaf change read locally ($textLocalMs ms) should beat a resize ($resizeMs ms)"
        )
    }

    @Test
    fun `steady state stops allocating links`() {
        // The other half of "incremental is cheap": no per-frame garbage. Dependency edges are
        // reconciled in place, so once the graph has seen a shape it stops allocating for it.
        val scene = buildScene(defaultRows)
        scene.layoutAll()
        scene.g.stats.reset()
        for (i in 0 until 8) {
            scene.g.setInput(scene.viewportW, 1000.0 + i * 10)
            scene.layoutAll()
        }
        assertEquals(
            0L, scene.g.stats.linksAllocated,
            "eight full re-layouts allocated ${scene.g.stats.linksAllocated} links"
        )
    }

    // -------------------------------------------------------------------------------

    private fun Long.pad() = toString().padStart(7)

    private fun ratio(part: Long, whole: Long) =
        if (whole == 0L) "n/a" else String.format("%.4fx", part.toDouble() / whole)
}
