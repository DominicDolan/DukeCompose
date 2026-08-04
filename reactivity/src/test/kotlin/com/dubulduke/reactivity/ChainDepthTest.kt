package com.dubulduke.reactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The chain-depth ceiling as a **stated constraint** rather than a crash.
 *
 * `:layout`'s `ChainDepthTest` measures where the JVM stack actually runs out; this one is about
 * what happens on the way there. The evaluator's depth limit exists either way — pull evaluation
 * spends stack proportional to chain length, which is the price of the call stack being the
 * topological order — so the only decision available is whether it arrives as a number somebody
 * chose or as a `StackOverflowError` at whatever depth the JIT happened to leave the frames.
 *
 * Three things have to hold for the first answer to be worth anything:
 *
 *  1. The refusal must **name the chain**, or it is no better than the crash.
 *  2. It must be **raisable**, with the stack requirement computable rather than guessed.
 *  3. The graph must **survive** it, so a host that catches one and drops a frame is not left
 *     evaluating a graph that quietly thinks it is up to date.
 */
class ChainDepthTest {

    private fun chain(g: Graph, n: Int): Pair<Slot, Slot> {
        val head = g.input(1.0, "head")
        var prev = head
        for (i in 0 until n) {
            val p = prev
            prev = g.derive("d$i") { read(p) + 1.0 }
        }
        return head to prev
    }

    @Test
    fun `a chain within budget settles normally`() {
        val g = Graph()
        g.maxChainDepth = 200
        val (head, tail) = chain(g, 199)
        assertEquals(200.0, g.read(tail))

        // And again, incrementally: the depth counter must not accumulate across pulls. If it did,
        // the second frame of any application would refuse at half the budget.
        g.setInput(head, 2.0)
        assertEquals(201.0, g.read(tail))
        g.setInput(head, 3.0)
        assertEquals(202.0, g.read(tail))
    }

    @Test
    fun `a chain past the budget is refused, naming where`() {
        val g = Graph()
        g.maxChainDepth = 50
        val (_, tail) = chain(g, 400)

        val e = assertFailsWith<ChainTooDeepException> { g.read(tail) }
        assertEquals(51, e.depth)
        assertTrue(e.message!!.contains("maxChainDepth (50)"), "message was: ${e.message}")
        // The point of the whole exercise: it says which slots, so the offending list is findable.
        assertTrue(e.chain.isNotEmpty(), "no chain reported")
        assertTrue(
            e.message!!.contains("layoutThread"),
            "the message must say how to raise the ceiling; was: ${e.message}"
        )
    }

    @Test
    fun `the budget is raisable, and the graph still works after a refusal`() {
        val g = Graph()
        g.maxChainDepth = 50
        val (head, tail) = chain(g, 400)
        assertFailsWith<ChainTooDeepException> { g.read(tail) }

        // Self-healing: the failed pull left the depth counter high, and the next top-level read
        // resets it. Without that, raising the ceiling would not actually help — the graph would
        // refuse again at a depth that has nothing to do with the chain.
        g.maxChainDepth = 500
        assertEquals(401.0, g.read(tail))
        g.setInput(head, 2.0)
        assertEquals(402.0, g.read(tail))
    }

    @Test
    fun `a CHECK resolution walk is bounded too, not only recomputes`() {
        // The case a check placed in `recompute` alone would miss. Settle the chain, then write a
        // value that changes nothing at the far end: every node goes CHECK, and resolving that
        // walks the full length of the chain through updateIfNecessary without recomputing
        // anything — recursion just as deep, with an empty evaluation stack.
        val g = Graph()
        g.maxChainDepth = 2_000
        val (head, tail) = chain(g, 800)
        g.read(tail)

        g.maxChainDepth = 100
        g.setInput(head, 2.0)
        val e = assertFailsWith<ChainTooDeepException> { g.read(tail) }
        assertEquals(101, e.depth)
    }

    @Test
    fun `marking is not recursive, so a write cannot overflow what a read survives`() {
        // Marking descends the subscriber graph, so before it was iterative its depth was also the
        // chain length — and a `setInput` at the head of a long list overflowed inside the *write*,
        // where no budget could have caught it. The budget is deliberately left low here: this must
        // pass because marking no longer recurses, not because the chain is short.
        val g = Graph()
        val head = g.input(1.0, "head")
        var prev = head
        val links = ArrayList<Slot>(60_000)
        for (i in 0 until 60_000) {
            val p = prev
            prev = g.derive("d$i") { read(p) + 1.0 }
            links += prev
        }

        // Settle it **top down, one link at a time**, so the subscriber chain reaches its full
        // 60 000 without evaluation ever going more than two frames deep. That is the whole trick
        // this test needs: the two depths are independent, and only one of them is under test.
        for (s in links) g.read(s)
        assertEquals(60_001.0, g.read(links.last()))

        // The write. 60 000 links of subscriber walk on an ordinary stack — a recursive `mark`
        // overflows here, and no evaluation budget could have caught it, because this is not
        // evaluation. Setting the budget to 1 is the point rather than an oversight.
        g.stats.reset()
        g.maxChainDepth = 1
        g.setInput(head, 2.0)
        assertEquals(60_000L, g.stats.marked)
    }

    @Test
    fun `stack size and depth convert both ways`() {
        val stack = ChainDepth.stackForDepth(64_000)
        assertTrue(
            ChainDepth.depthForStack(stack) >= 64_000,
            "a stack sized for 64 000 links reported room for only ${ChainDepth.depthForStack(stack)}"
        )
        // Linear, which is the property that makes sizing a thread a computation rather than a
        // tuning exercise — see ChainDepth's header. To within the integer division.
        val one = ChainDepth.depthForStack(1L shl 20)
        val two = ChainDepth.depthForStack(2L shl 20)
        assertTrue(two - 2 * one in -1..1, "$one links per MB but $two per two")
    }

    @Test
    fun `a layout thread carries its budget to the graphs made on it`() {
        var budget = 0
        var deepestSettled = 0.0
        var failure: Throwable? = null
        layoutThread(maxChainDepth = 40_000) {
            try {
                val g = Graph()
                budget = g.maxChainDepth
                val (_, tail) = chain(g, 20_000)
                deepestSettled = g.read(tail)
            } catch (t: Throwable) {
                failure = t
            }
        }.join()

        failure?.let { throw AssertionError("the layout thread failed: $it", it) }
        assertEquals(40_000, budget, "the graph did not adopt the thread's declared budget")
        assertEquals(
            20_001.0, deepestSettled,
            "a thread sized for 40 000 links could not settle 20 000"
        )
    }

    @Test
    fun `the default budget sits under what a default stack carries`() {
        // The race the default has to win. `:layout`'s ChainDepthTest measures a default stack at
        // roughly 3 900 links warm and notes ~1 500 cold; the budget must be below the cold figure
        // or, on precisely the runs where it matters, the StackOverflowError arrives first.
        assertTrue(
            ChainDepth.DEFAULT_MAX_CHAIN_DEPTH < 1_500,
            "default budget ${ChainDepth.DEFAULT_MAX_CHAIN_DEPTH} is not under the observed floor"
        )
        assertEquals(ChainDepth.DEFAULT_MAX_CHAIN_DEPTH, Graph().maxChainDepth)
    }
}
