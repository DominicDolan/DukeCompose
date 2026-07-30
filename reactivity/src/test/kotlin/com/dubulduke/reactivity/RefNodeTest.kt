package com.dubulduke.reactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Non-scalar nodes: child-list identity, text content, resolved fonts. See
 * `reactivity-core.md` §3.7 and `layout-design.md` §8.6–8.7.
 */
class RefNodeTest {

    private val g = Graph()

    // -------------------------------------------------------------------------------
    // Basics — same machinery, different field
    // -------------------------------------------------------------------------------

    @Test
    fun `ref input reads back`() {
        val a = g.refInput("hello", "text")
        assertEquals("hello", g.readRef(a))
        g.setRefInput(a, "goodbye")
        assertEquals("goodbye", g.readRef(a))
    }

    @Test
    fun `null is a legal value, before and after a write`() {
        val a = g.refInput(name = "font")
        assertNull(g.readRef(a))
        g.setRefInput(a, "Inter")
        assertEquals("Inter", g.readRef(a))
        g.setRefInput(a, null)
        assertNull(g.readRef(a))
    }

    @Test
    fun `a derived ref node is lazy and memoised`() {
        val family = g.refInput("Inter", "family")
        val size = g.input(12.0, "size")
        val font = g.deriveRef("font") { "${readRef(family)}@${read(size)}" }

        assertEquals(0, g.recomputeCount(font))
        assertEquals("Inter@12.0", g.readRef(font))
        repeat(5) { g.readRef(font) }
        assertEquals(1, g.recomputeCount(font))

        g.setInput(size, 14.0)
        assertEquals("Inter@14.0", g.readRef(font))
        assertEquals(2, g.recomputeCount(font))
    }

    @Test
    fun `a scalar node may depend on a ref node`() {
        // The intrinsic-sizing shape: measured width is a scalar derived from text content.
        val text = g.refInput("hello", "text", RefEquality.EQUALS)
        val perChar = g.input(7.0, "perChar")
        val width = g.derive("width") { (readRef(text) as String).length * read(perChar) }

        assertEquals(35.0, g.read(width))
        g.setRefInput(text, "hi")
        assertEquals(14.0, g.read(width))
        assertEquals(listOf(text, perChar), g.dependenciesOf(width))
    }

    @Test
    fun `a ref node may depend on a scalar node, and dynamic dependencies still work`() {
        val useBold = g.input(0.0, "useBold")
        val regular = g.refInput("Inter", "regular")
        val bold = g.refInput("Inter-Bold", "bold")
        val font = g.deriveRef("font") {
            if (read(useBold) > 0.5) readRef(bold) else readRef(regular)
        }

        assertEquals("Inter", g.readRef(font))
        assertEquals(listOf(useBold, regular), g.dependenciesOf(font))

        g.setInput(useBold, 1.0)
        assertEquals("Inter-Bold", g.readRef(font))
        assertEquals(listOf(useBold, bold), g.dependenciesOf(font))
        assertTrue(g.subscribersOf(regular).isEmpty(), "the abandoned edge is gone")
    }

    // -------------------------------------------------------------------------------
    // Comparison policy — the reason RefEquality exists
    // -------------------------------------------------------------------------------

    @Test
    fun `IDENTITY treats an equal but distinct value as a change`() {
        val a = g.refInput(StringBuilder("x").toString(), "a", RefEquality.IDENTITY)
        val watcher = g.derive("watch") { readRef(a); 0.0 }
        g.read(watcher)

        g.setRefInput(a, StringBuilder("x").toString())   // equal, not identical
        g.read(watcher)
        assertEquals(2, g.recomputeCount(watcher))
    }

    @Test
    fun `EQUALS suppresses an equal but distinct value — the text remeasure case`() {
        val label = g.refInput("Total: 5", "label", RefEquality.EQUALS)
        var measures = 0
        val measured = g.derive("measured") {
            measures++
            (readRef(label) as String).length.toDouble()
        }
        assertEquals(8.0, g.read(measured))

        // A fresh String with the same content — exactly what recomposition produces.
        g.setRefInput(label, "Total: " + 5)
        g.read(measured)
        assertEquals(1, measures, "text measurement must not re-run for identical content")

        g.setRefInput(label, "Total: 6")
        assertEquals(8.0, g.read(measured))
        assertEquals(2, measures)
    }

    @Test
    fun `ALWAYS propagates even when the value is the very same instance`() {
        val buffer = StringBuilder("x")
        val a = g.refInput(buffer, "a", RefEquality.ALWAYS)
        val watcher = g.derive("watch") { readRef(a); 0.0 }
        g.read(watcher)

        buffer.append("y")                 // mutated in place; no test could see this
        g.setRefInput(a, buffer)           // same instance
        g.read(watcher)
        assertEquals(2, g.recomputeCount(watcher), "the escape hatch for in-place mutation")
    }

    @Test
    fun `IDENTITY misses an in-place mutation, which is why ALWAYS exists`() {
        val buffer = StringBuilder("x")
        val a = g.refInput(buffer, "a", RefEquality.IDENTITY)
        val watcher = g.derive("watch") { readRef(a); 0.0 }
        g.read(watcher)

        buffer.append("y")
        g.setRefInput(a, buffer)
        g.read(watcher)
        assertEquals(1, g.recomputeCount(watcher), "documented hazard, not a bug")
    }

    @Test
    fun `a derived ref node applies its own policy, not its dependency's`() {
        val n = g.input(5.0, "n")
        val text = g.deriveRef("text", RefEquality.EQUALS) { "n=" + read(n) }
        val watcher = g.derive("watch") { readRef(text); 0.0 }
        g.read(watcher)

        g.setInput(n, 5.0 + 0.0)     // no change at all: the derive never even re-runs
        g.read(watcher)
        assertEquals(1, g.recomputeCount(text))

        // Force the derive to re-run by moving `n`, but back to a value producing equal text.
        g.setInput(n, 6.0)
        g.read(watcher)
        assertEquals(2, g.recomputeCount(text))
        assertEquals(2, g.recomputeCount(watcher))
    }

    // -------------------------------------------------------------------------------
    // The child-list case (layout-design §8.7)
    // -------------------------------------------------------------------------------

    @Test
    fun `shrink-wrap depends on child list identity, not only on child values`() {
        val childWidths = ArrayList<Slot>()
        childWidths += g.input(10.0, "kid0")
        childWidths += g.input(30.0, "kid1")

        // The list itself is a node. Without it, appending a child cannot invalidate anything.
        val children = g.refInput(childWidths.toList(), "children")

        @Suppress("UNCHECKED_CAST")
        val parentWidth = g.derive("parent.width") {
            (readRef(children) as List<Slot>).maxOf { read(it) }
        }

        assertEquals(30.0, g.read(parentWidth))

        // A child's own value changing: an ordinary scalar edge.
        g.setInput(childWidths[1], 40.0)
        assertEquals(40.0, g.read(parentWidth))

        // Structure changing: only the list node can carry this.
        childWidths += g.input(100.0, "kid2")
        g.setRefInput(children, childWidths.toList())
        assertEquals(100.0, g.read(parentWidth))
        assertEquals(4, g.dependenciesOf(parentWidth).size, "list + three children")
    }

    @Test
    fun `a recycled child list mutated in place is invisible under IDENTITY`() {
        // The DukeUI §2.4 hazard, pinned so it cannot be rediscovered as a mystery bug.
        val kids = arrayListOf(g.input(10.0, "kid0"))
        val children = g.refInput(kids, "children", RefEquality.IDENTITY)

        @Suppress("UNCHECKED_CAST")
        val parentWidth = g.derive("parent.width") {
            (readRef(children) as List<Slot>).maxOf { read(it) }
        }
        assertEquals(10.0, g.read(parentWidth))

        kids += g.input(99.0, "kid1")          // recycled list, mutated in place
        g.setRefInput(children, kids)          // same instance
        assertEquals(10.0, g.read(parentWidth), "stale — either copy the list or use ALWAYS")

        // Both fixes work. ALWAYS:
        val children2 = g.refInput(kids, "children2", RefEquality.ALWAYS)
        @Suppress("UNCHECKED_CAST")
        val parentWidth2 = g.derive("parent.width2") {
            (readRef(children2) as List<Slot>).maxOf { read(it) }
        }
        assertEquals(99.0, g.read(parentWidth2))
    }

    // -------------------------------------------------------------------------------
    // Ref nodes share every other code path
    // -------------------------------------------------------------------------------

    @Test
    fun `an unchanged ref stops propagation just like a scalar`() {
        val src = g.input(1.0, "src")
        val bucket = g.deriveRef("bucket", RefEquality.EQUALS) {
            if (read(src) > 0.0) "positive" else "other"
        }
        val watcher = g.derive("watch") { readRef(bucket); 0.0 }
        g.read(watcher)

        g.setInput(src, 2.0)
        g.read(watcher)
        assertEquals(2, g.recomputeCount(bucket), "it re-ran…")
        assertEquals(1, g.recomputeCount(watcher), "…but produced the same bucket")
    }

    @Test
    fun `a ref node in a diamond evaluates its join exactly once`() {
        val src = g.input(1.0, "src")
        val left = g.deriveRef("left") { read(src).toString() }
        val right = g.derive("right") { read(src) * 2.0 }
        val join = g.derive("join") { (readRef(left) as String).length + read(right) }

        g.read(join)
        g.setInput(src, 2.0)
        g.read(join)
        assertEquals(2, g.recomputeCount(join))
    }

    @Test
    fun `a cycle through a ref node is broken with the stored reference`() {
        val sink = RecordingSink()
        g.diagnostics = sink
        var b = Slot(-1)
        val a = g.deriveRef("a") { "a(" + readRef(b) + ")" }
        b = g.deriveRef("b") { "b(" + readRef(a) + ")" }

        assertEquals("a(b(null))", g.readRef(a), "b saw a's uncommitted null")
        assertEquals(listOf("a", "b"), sink.cycles.single().map { it.name })
    }

    @Test
    fun `a ref computation reading a governor output resolves through the governor`() {
        val total = g.input(100.0, "total")
        val share = g.input(name = "share")
        val gov = g.governor(listOf(share), "gov") { writeOutput(share, read(total) / 4.0) }
        val label = g.deriveRef("label", RefEquality.EQUALS) { "w=" + read(share) }

        assertEquals("w=25.0", g.readRef(label))
        assertEquals(1, g.solveCount(gov))
        assertEquals(1, g.graphHeightOf(label), "above the governor")

        g.setInput(total, 200.0)
        assertEquals("w=50.0", g.readRef(label))
        assertEquals(2, g.solveCount(gov))
    }

    // -------------------------------------------------------------------------------
    // Guards — scalar and ref are not interchangeable
    // -------------------------------------------------------------------------------

    @Test
    fun `read on a ref node is rejected`() {
        val a = g.refInput("x", "a")
        val e = assertFailsWith<IllegalArgumentException> { g.read(a) }
        assertTrue("use readRef()" in e.message!!, e.message!!)
    }

    @Test
    fun `readRef on a scalar node is rejected`() {
        val a = g.input(1.0, "a")
        val e = assertFailsWith<IllegalArgumentException> { g.readRef(a) }
        assertTrue("use read()" in e.message!!, e.message!!)
    }

    @Test
    fun `setInput on a ref node is rejected, and setRefInput on a scalar`() {
        val r = g.refInput("x", "r")
        val s = g.input(1.0, "s")
        assertFailsWith<IllegalArgumentException> { g.setInput(r, 1.0) }
        assertFailsWith<IllegalArgumentException> { g.setRefInput(s, "x") }
    }

    @Test
    fun `a derived ref node cannot be written as an input`() {
        val src = g.refInput("x")
        val d = g.deriveRef { readRef(src) }
        assertFailsWith<IllegalArgumentException> { g.setRefInput(d, "y") }
    }

    @Test
    fun `epsilon is meaningless on a ref node`() {
        val a = g.refInput("x", "a")
        val e = assertFailsWith<IllegalArgumentException> { g.setEpsilon(a, 0.5) }
        assertTrue("RefEquality" in e.message!!, e.message!!)
    }

    @Test
    fun `a governor cannot claim a ref slot`() {
        val a = g.refInput("x", "a")
        val e = assertFailsWith<IllegalArgumentException> {
            g.governor(listOf(a)) { }
        }
        assertTrue("governors claim scalars" in e.message!!, e.message!!)
    }

    @Test
    fun `isRefNode distinguishes them`() {
        assertTrue(g.isRefNode(g.refInput("x")))
        assertTrue(!g.isRefNode(g.input(1.0)))
        assertTrue(g.isRefNode(g.deriveRef { null }))
        assertTrue(!g.isRefNode(g.derive { 0.0 }))
    }

    // -------------------------------------------------------------------------------
    // Instrumentation still holds
    // -------------------------------------------------------------------------------

    @Test
    fun `steady state allocates no links, refs included`() {
        val text = g.refInput("hello", "text", RefEquality.EQUALS)
        val perChar = g.input(7.0, "perChar")
        val width = g.derive("width") { (readRef(text) as String).length * read(perChar) }
        g.read(width)

        g.stats.reset()
        repeat(20) { i ->
            g.setInput(perChar, 8.0 + i)
            g.read(width)
        }
        assertEquals(20L, g.stats.recomputed)
        assertEquals(0L, g.stats.linksAllocated)
        assertEquals(0L, g.stats.linksTrimmed)
    }

    @Test
    fun `heights count ref nodes like any other derived node`() {
        val src = g.refInput("hello", "src")
        val a = g.deriveRef("a") { readRef(src) }
        val b = g.derive("b") { (readRef(a) as String).length.toDouble() }
        g.read(b)
        assertEquals(0, g.graphHeightOf(a), "reads only an input")
        assertEquals(1, g.graphHeightOf(b))
    }

    @Test
    fun `ref reads are thread-confined too`() {
        val a = g.refInput("x")
        var failure: Throwable? = null
        val t = Thread { failure = runCatching { g.readRef(a) }.exceptionOrNull() }
        t.start()
        t.join()
        assertTrue(failure is IllegalStateException, "got $failure")
    }

    @Test
    fun `the same instance written twice does nothing under IDENTITY`() {
        val value = Any()
        val a = g.refInput(value, "a")
        val watcher = g.derive("watch") { readRef(a); 0.0 }
        g.read(watcher)
        g.setRefInput(a, value)
        g.read(watcher)
        assertEquals(1, g.recomputeCount(watcher))
        assertSame(value, g.readRef(a))
    }
}
