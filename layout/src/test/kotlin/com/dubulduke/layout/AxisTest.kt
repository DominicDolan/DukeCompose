package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RecordingSink
import com.dubulduke.reactivity.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The slot layer: four properties over two degrees of freedom. `layout-design.md` §2.1, §4.1,
 * §4.2.
 */
class AxisTest {

    private val g = Graph()

    // The one interval every basis below describes, from four different pairs of numbers.
    private fun canonical(p: Property): Double = when (p) {
        Property.ORIGIN -> 10.0
        Property.SIZE -> 40.0
        Property.END -> 50.0
        Property.CENTER -> 30.0
    }

    private fun AxisBuilder.constant(p: Property, v: Double) {
        when (p) {
            Property.ORIGIN -> origin { v }
            Property.SIZE -> size { v }
            Property.END -> end { v }
            Property.CENTER -> center { v }
        }
    }

    /** An axis on the canonical interval, written through [basis]'s pair of properties. */
    private fun canonicalAxis(basis: Basis): Axis = g.axis(basis.name) {
        constant(basis.first, canonical(basis.first))
        constant(basis.second, canonical(basis.second))
    }

    // -------------------------------------------------------------------------------
    // Two degrees of freedom, four names
    // -------------------------------------------------------------------------------

    @Test
    fun `all six bases describe the same interval`() {
        for (basis in Basis.entries) {
            val axis = canonicalAxis(basis)
            assertEquals(10.0, g.originOf(axis), "$basis origin")
            assertEquals(40.0, g.sizeOf(axis), "$basis size")
            assertEquals(50.0, g.endOf(axis), "$basis end")
            assertEquals(30.0, g.centerOf(axis), "$basis center")
        }
    }

    @Test
    fun `the derivations agree with the old VaryingDimension`() {
        // Spot-check the two least obvious rows against VaryingDimension.set() directly:
        //   SIZE_CENTER -> origin = center - size/2
        //   END_CENTER  -> size = (end - center)*2 ; origin = end - size
        val sc = g.axis("sc") { size { 30.0 }; center { 100.0 } }
        assertEquals(85.0, g.originOf(sc))
        assertEquals(115.0, g.endOf(sc))

        val ec = g.axis("ec") { end { 200.0 }; center { 150.0 } }
        assertEquals(100.0, g.sizeOf(ec))
        assertEquals(100.0, g.originOf(ec))
    }

    @Test
    fun `property order at registration does not matter`() {
        val a = g.axis("a") { origin { 10.0 }; size { 40.0 } }
        val b = g.axis("b") { size { 40.0 }; origin { 10.0 } }
        assertEquals(Basis.ORIGIN_SIZE, a.basis)
        assertEquals(Basis.ORIGIN_SIZE, b.basis)
        assertEquals(g.endOf(a), g.endOf(b))
        // Canonicalised: the slot for ORIGIN is `firstSlot` in both, whatever the write order.
        assertEquals(10.0, g.read(a.firstSlot))
        assertEquals(10.0, g.read(b.firstSlot))
    }

    @Test
    fun `slotFor names the written properties and nothing else`() {
        val axis = g.axis("x") { origin { 1.0 }; end { 2.0 } }
        assertEquals(axis.firstSlot, axis.slotFor(Property.ORIGIN))
        assertEquals(axis.secondSlot, axis.slotFor(Property.END))
        assertNull(axis.slotFor(Property.SIZE), "derived, not written")
        assertNull(axis.slotFor(Property.CENTER))
    }

    @Test
    fun `startIsLow is recorded and otherwise inert`() {
        val flipped = g.axis("flipped", startIsLow = false) { origin { 10.0 }; size { 40.0 } }
        assertTrue(!flipped.startIsLow)
        // Orthogonal to the algorithm — the geometry is unchanged. Mapping it onto
        // left/right/top/bottom needs to know the axis's orientation, which is the element
        // layer's job.
        assertEquals(10.0, g.originOf(flipped))
        assertEquals(50.0, g.endOf(flipped))
    }

    // -------------------------------------------------------------------------------
    // Slot provenance — layout-design §4.2, as a spec test
    // -------------------------------------------------------------------------------

    private enum class Which { FIRST, SECOND, BOTH }

    /** A transcription of the §4.2 table. Rows are bases, columns are the four properties. */
    private val provenance: Map<Basis, Map<Property, Which>> = mapOf(
        Basis.ORIGIN_SIZE to mapOf(
            Property.ORIGIN to Which.FIRST, Property.SIZE to Which.SECOND,
            Property.END to Which.BOTH, Property.CENTER to Which.BOTH,
        ),
        Basis.ORIGIN_END to mapOf(
            Property.ORIGIN to Which.FIRST, Property.SIZE to Which.BOTH,
            Property.END to Which.SECOND, Property.CENTER to Which.BOTH,
        ),
        Basis.ORIGIN_CENTER to mapOf(
            Property.ORIGIN to Which.FIRST, Property.SIZE to Which.BOTH,
            Property.END to Which.BOTH, Property.CENTER to Which.SECOND,
        ),
        Basis.SIZE_END to mapOf(
            Property.ORIGIN to Which.BOTH, Property.SIZE to Which.FIRST,
            Property.END to Which.SECOND, Property.CENTER to Which.BOTH,
        ),
        Basis.SIZE_CENTER to mapOf(
            Property.ORIGIN to Which.BOTH, Property.SIZE to Which.FIRST,
            Property.END to Which.BOTH, Property.CENTER to Which.SECOND,
        ),
        Basis.END_CENTER to mapOf(
            Property.ORIGIN to Which.BOTH, Property.SIZE to Which.BOTH,
            Property.END to Which.FIRST, Property.CENTER to Which.SECOND,
        ),
    )

    @Test
    fun `reading a property records edges to exactly the slots that feed it`() {
        for ((basis, row) in provenance) {
            val axis = canonicalAxis(basis)
            for ((property, expected) in row) {
                val probe = g.derive("$basis.$property") { propertyOf(axis, property) }
                g.read(probe)

                val want = when (expected) {
                    Which.FIRST -> setOf(axis.firstSlot)
                    Which.SECOND -> setOf(axis.secondSlot)
                    Which.BOTH -> setOf(axis.firstSlot, axis.secondSlot)
                }
                assertEquals(
                    want,
                    g.dependenciesOf(probe).toSet(),
                    "$basis: reading $property should touch $expected",
                )
            }
        }
    }

    @Test
    fun `the provenance table is complete`() {
        // Guards against a basis being added without a row, which would silently skip it above.
        assertEquals(Basis.entries.toSet(), provenance.keys)
        for ((basis, row) in provenance) {
            assertEquals(Property.entries.toSet(), row.keys, "$basis")
        }
    }

    // -------------------------------------------------------------------------------
    // Why the precision matters: the false cycle of §4.1 / §4.2
    // -------------------------------------------------------------------------------

    @Test
    fun `a shrink-wrap parent and a centred child are acyclic`() {
        val sink = RecordingSink()
        g.diagnostics = sink

        // The §4.1 shape, with the child positioned by centre rather than by edge — which is
        // what makes the parent's *derived* centre (origin + size/2) reach back into the
        // parent's size slot, and so what makes provenance precision load-bearing.
        var child = null as Axis?
        val parent = g.axis("parent.h") {
            origin { 0.0 }
            size { sizeOf(child!!) }
        }
        child = g.axis("child.h") {
            size { 100.0 }
            center { centerOf(parent) }
        }

        assertEquals(100.0, g.sizeOf(parent))
        assertEquals(50.0, g.centerOf(parent))
        assertEquals(0.0, g.originOf(child))
        assertEquals(100.0, g.endOf(child))
        assertTrue(sink.cycles.isEmpty(), "no cycle: ${sink.cycles}")

        // The two chains run in opposite directions through the same pair of elements, and
        // never meet:
        assertEquals(
            setOf(child.firstSlot),                       // the child's SIZE slot, only
            g.dependenciesOf(parent.slotFor(Property.SIZE)!!).toSet(),
        )
        assertEquals(
            setOf(parent.firstSlot, parent.secondSlot),   // parent centre needs origin + size
            g.dependenciesOf(child.slotFor(Property.CENTER)!!).toSet(),
        )
    }

    @Test
    fun `a conservative whole-axis dependency reintroduces the cycle`() {
        // The same layout, with one change: the parent's size expression touches both of the
        // child's slots instead of only the one it needs — which is exactly what a "depends on
        // the whole axis" approximation would produce. This is the failure §4.2 warns about,
        // pinned so the claim is demonstrated rather than asserted.
        val sink = RecordingSink()
        g.diagnostics = sink

        var child = null as Axis?
        val parent = g.axis("cons.parent.h") {
            origin { 0.0 }
            size {
                val c = child!!
                read(c.firstSlot) + 0.0 * read(c.secondSlot)   // conservative: reads both
            }
        }
        child = g.axis("cons.child.h") {
            size { 100.0 }
            center { centerOf(parent) }
        }

        g.sizeOf(parent)
        assertTrue(sink.cycles.isNotEmpty(), "the conservative version must cycle")
        assertTrue(
            sink.cycles.first().any { it.name == "cons.parent.h.size" },
            "cycle should run through the parent's size slot: ${sink.cycles.first()}",
        )
    }

    // -------------------------------------------------------------------------------
    // It is just the graph underneath
    // -------------------------------------------------------------------------------

    @Test
    fun `only the properties that actually depend on a changed slot recompute`() {
        g.diagnostics = DiagnosticSink.Throwing
        val viewport = g.input(800.0, "viewport")
        val panel = g.axis("panel.h") {
            origin { 0.0 }
            size { read(viewport) / 2.0 }
        }
        val leftEdge = g.derive("leftEdge") { originOf(panel) }
        val rightEdge = g.derive("rightEdge") { endOf(panel) }

        assertEquals(0.0, g.read(leftEdge))
        assertEquals(400.0, g.read(rightEdge))

        g.setInput(viewport, 1000.0)
        assertEquals(0.0, g.read(leftEdge))
        assertEquals(500.0, g.read(rightEdge))

        assertEquals(1, g.recomputeCount(leftEdge), "origin does not read size under ORIGIN_SIZE")
        assertEquals(2, g.recomputeCount(rightEdge))
    }

    @Test
    fun `a chain of axes propagates like a vertical list`() {
        g.diagnostics = DiagnosticSink.Throwing
        val rowHeight = g.input(20.0, "rowHeight")
        val axes = ArrayList<Axis>()
        repeat(50) { i ->
            val prev = axes.lastOrNull()
            axes += g.axis("row$i.v") {
                origin { if (prev == null) 0.0 else endOf(prev) }
                size { read(rowHeight) }
            }
        }

        assertEquals(980.0, g.originOf(axes.last()))
        g.setInput(rowHeight, 10.0)
        assertEquals(490.0, g.originOf(axes.last()))
    }

    @Test
    fun `an axis whose size is unchanged does not disturb its neighbours`() {
        g.diagnostics = DiagnosticSink.Throwing
        val a = g.input(10.0, "a")
        val b = g.input(40.0, "b")
        // Written as (ORIGIN, END) so size is derived from both slots.
        val axis = g.axis("x") { origin { read(a) }; end { read(a) + read(b) } }
        val width = g.derive("width") { sizeOf(axis) }

        assertEquals(40.0, g.read(width))

        // Slide the whole interval: both slots change, but the size does not.
        g.setInput(a, 100.0)
        assertEquals(40.0, g.read(width))
        assertEquals(2, g.recomputeCount(width), "it re-ran…")

        val downstream = g.derive("downstream") { read(width) * 2.0 }
        g.read(downstream)
        g.setInput(a, 200.0)
        g.read(downstream)
        assertEquals(1, g.recomputeCount(downstream), "…but propagation stopped at the size")
    }

    // -------------------------------------------------------------------------------
    // Degrees of freedom are enforced
    // -------------------------------------------------------------------------------

    @Test
    fun `three properties is over-constrained`() {
        val e = assertFailsWith<IllegalArgumentException> {
            g.axis("box.h") { origin { 0.0 }; end { 100.0 }; size { 50.0 } }
        }
        assertTrue("over-constrained" in e.message!!, e.message!!)
        assertTrue("SIZE" in e.message!!, "should name the offending property: ${e.message}")
    }

    @Test
    fun `one property is under-constrained`() {
        val e = assertFailsWith<IllegalStateException> {
            g.axis("box.h") { size { 50.0 } }
        }
        assertTrue("under-constrained" in e.message!!, e.message!!)
    }

    @Test
    fun `no properties is under-constrained`() {
        assertFailsWith<IllegalStateException> { g.axis("box.h") { } }
    }

    @Test
    fun `the same property twice is an error, not last-wins`() {
        val e = assertFailsWith<IllegalArgumentException> {
            g.axis("box.h") { size { 50.0 }; size { 60.0 } }
        }
        assertTrue("already has an expression" in e.message!!, e.message!!)
    }

    @Test
    fun `a rejected axis leaves no orphan nodes in the graph`() {
        val before = g.nodeCount
        assertFailsWith<IllegalArgumentException> {
            g.axis("box.h") { origin { 0.0 }; end { 1.0 }; size { 2.0 } }
        }
        assertEquals(before + 2, g.nodeCount, "the two valid expressions, not the third")
    }

    @Test
    fun `Basis of rejects a repeated property`() {
        assertFailsWith<IllegalArgumentException> { Basis.of(Property.SIZE, Property.SIZE) }
    }

    @Test
    fun `Basis of is order-independent and total`() {
        for (basis in Basis.entries) {
            assertEquals(basis, Basis.of(basis.first, basis.second))
            assertEquals(basis, Basis.of(basis.second, basis.first))
        }
    }

    // -------------------------------------------------------------------------------
    // Node naming, for diagnostics
    // -------------------------------------------------------------------------------

    @Test
    fun `slot nodes are named after their property, so cycle reports are legible`() {
        val sink = RecordingSink()
        g.diagnostics = sink

        var other = null as Axis?
        val axis = g.axis("panel.h") {
            origin { 0.0 }
            size { sizeOf(other!!) }
        }
        other = g.axis("badge.h") {
            origin { 0.0 }
            size { sizeOf(axis) }        // a genuine cycle: two shrink-wraps facing each other
        }

        g.sizeOf(axis)

        assertEquals(
            listOf("panel.h.size", "badge.h.size"),
            sink.cycles.single().map { it.name },
            "the report must name the two slots, not the two elements",
        )
    }

    @Test
    fun `an unnamed axis still builds`() {
        val axis = g.axis { origin { 1.0 }; size { 2.0 } }
        assertEquals(3.0, g.endOf(axis))
        assertEquals(listOf<Slot>(), g.dependenciesOf(axis.firstSlot), "a constant has no deps")
    }
}
