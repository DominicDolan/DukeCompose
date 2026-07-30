package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ceiling plan step 7 actually found: **the evaluator's recursion depth is the longest
 * dependency chain, so JVM stack size caps chain length.**
 *
 * This is not a bug, it is the cost of the central design decision. `reactivity-core.md` resolves
 * the diamond problem by making the call stack *be* the topological order — `read` → `settle` →
 * `updateIfNecessary` → `recompute` → the body, which reads again. That is what makes evaluation
 * pull-driven, allocation-free and exactly-once, and it is why there is no scheduler and no
 * explicit topological sort. The price is one JVM frame group per link in the chain.
 *
 * It matters because a chain of that length is *ordinary layout*, not a pathological case: a list
 * where each row sits below the previous one is `rows` links deep. So the limit is worth measuring
 * exactly and stating in the docs, rather than being discovered as a crash in someone's list view.
 *
 * The measurement is a binary search for the deepest chain that settles without a
 * `StackOverflowError`, at several stack sizes. It is a real test rather than a scratch probe
 * because the number is a design constraint that should not silently regress.
 */
class ChainDepthTest {

    /** Roughly what one link in a dependency chain costs in JVM stack bytes. */
    private val bytesPerLink = 145

    private fun chainSettles(n: Int, stackBytes: Long): Boolean {
        var ok = false
        val t = Thread(null, {
            ok = try {
                val g = Graph()
                var prev = g.input(1.0, "in")
                for (i in 0 until n) {
                    val p = prev
                    prev = g.derive("d$i") { read(p) + 1.0 }
                }
                g.read(prev)
                true
            } catch (e: StackOverflowError) {
                false
            }
        }, "chain-probe", stackBytes)
        t.start()
        t.join()
        return ok
    }

    private fun maxChain(stackBytes: Long): Int {
        var lo = 1
        var hi = 4_000_000
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (chainSettles(mid, stackBytes)) lo = mid else hi = mid - 1
        }
        return lo
    }

    @Test
    fun `chain depth scales with thread stack size, at a knowable cost per link`() {
        val stacks = listOf(0L, 1L shl 20, 8L shl 20, 64L shl 20)
        val measured = stacks.map { it to maxChain(it) }

        println(
            buildString {
                appendLine()
                appendLine(" Maximum dependency-chain depth by thread stack size")
                appendLine(" " + "-".repeat(64))
                for ((stack, depth) in measured) {
                    val name = if (stack == 0L) "JVM default" else "${stack shr 20} MB"
                    val perLink =
                        if (stack == 0L) "" else " (%d bytes/link)".format(stack / depth)
                    appendLine(" %-12s -> %,9d nodes deep%s".format(name, depth, perLink))
                }
                appendLine()
                appendLine(" A list chained through `prev` is ~2 chain nodes per row, so the")
                appendLine(" JVM default stack caps such a list at roughly")
                appendLine(" %,d rows. Set the layout thread's stack explicitly.".format(measured[0].second / 2))
            }
        )

        val default = measured[0].second
        val big = measured.last().second

        // The property that makes a bigger stack a real fix rather than a hope: depth is linear in
        // stack size, so the requirement can be *computed* from a known worst-case chain length.
        // A floor rather than a figure. The measured depth moves with JIT state — an interpreted
        // frame is several times the size of a compiled one — so a busier build genuinely settles
        // fewer levels on the same stack. It has been seen between roughly 1 500 and 4 000 here.
        // The claim being defended is "a default stack is a real constraint in the low thousands,
        // i.e. an ordinary list", not any particular number; §11.2 carries the measurements.
        assertTrue(default > 1_000, "default stack managed only $default deep")
        assertTrue(
            big > default * 20,
            "64 MB gave $big against $default on the default stack; depth is not scaling with stack"
        )
        val perLink = (64L shl 20) / big
        assertTrue(
            perLink in 20..600,
            "measured $perLink bytes of stack per chain link, expected around $bytesPerLink"
        )
    }

    @Test
    fun `a deep chain does not degrade per-node cost`() {
        // If the recursion were doing anything superlinear — rescanning, re-marking — a long chain
        // would cost more per node than a short one. It must not: settling a chain is one pass.
        fun costNs(n: Int): Double {
            var out = 0.0
            val t = Thread(null, {
                val g = Graph()
                val head = g.input(1.0, "in")
                var prev = head
                for (i in 0 until n) {
                    val p = prev
                    prev = g.derive("d$i") { read(p) + 1.0 }
                }
                val tail = prev
                g.read(tail)
                var best = Double.MAX_VALUE
                for (i in 0 until 200) {
                    g.setInput(head, 2.0 + i)
                    val t0 = System.nanoTime()
                    g.read(tail)
                    val dt = (System.nanoTime() - t0).toDouble() / n
                    if (dt < best) best = dt
                }
                out = best
            }, "deep", 256L shl 20)
            t.start(); t.join()
            return out
        }

        val shallow = costNs(1_000)
        val deep = costNs(100_000)
        println(" per-node settle cost: 1k chain %.1f ns, 100k chain %.1f ns".format(shallow, deep))
        assertTrue(
            deep < shallow * 6,
            "100k-deep chain cost $deep ns/node against $shallow ns/node at 1k; superlinear"
        )
    }
}
