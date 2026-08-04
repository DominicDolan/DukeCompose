package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The element tree: navigation, structural dependencies, and `query`. `layout-design.md` §4.3,
 * §8.7, §8.8; `dsl-and-engine.md` §2.6, §3.5.
 */
class ElementTreeTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g)

    /** A fixed box, for the many cases that only care about structure. */
    private fun ElementBuilder.box(x: Double = 0.0, w: Double = 10.0) {
        h { origin { x }; size { w } }
        v { origin { 0.0 }; size { 10.0 } }
    }

    // -------------------------------------------------------------------------------
    // Structure
    // -------------------------------------------------------------------------------

    @Test
    fun `a tree is built depth-first and knows its own shape`() {
        val root = tree.root("root") {
            box()
            child("a") { box(); child("a1") { box() } }
            child("b") { box() }
        }

        assertSame(root, tree.root)
        assertEquals(4, tree.size)
        assertEquals(listOf("a", "b"), root.children.map { it.id })
        assertEquals(listOf("a1"), root.children[0].children.map { it.id })
        assertSame(root, root.children[0].parent)
        assertNull(root.parent)
        assertEquals(listOf(0, 1), root.children.map { it.index })
    }

    @Test
    fun `paths name graph nodes after their position in the tree`() = withNodeNames {
        tree.root("app") {
            box()
            child("panel") { box(); child { box() } }
        }
        val unnamed = tree.query("panel").children[0]
        assertEquals("app.panel.[0]", unnamed.path)
        assertEquals("app.panel.[0].h.origin", g.nameOf(unnamed.h.firstSlot))
    }

    @Test
    fun `walk visits depth-first with depth`() {
        tree.root("root") {
            box()
            child("a") { box(); child("a1") { box() } }
            child("b") { box() }
        }
        val seen = ArrayList<String>()
        tree.walk { e, d -> seen += "${"  ".repeat(d)}${e.id}" }
        assertEquals(listOf("root", "  a", "    a1", "  b"), seen)
    }

    @Test
    fun `axes are independent per element`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 100.0 } }
            v { origin { 5.0 }; end { 25.0 } }
        }
        assertEquals(Basis.ORIGIN_SIZE, root.h.basis)
        assertEquals(Basis.ORIGIN_END, root.v.basis)
        assertEquals(100.0, g.widthOf(root))
        assertEquals(20.0, g.heightOf(root))
    }

    @Test
    fun `an element missing an axis is a composition error`() {
        val e = assertFailsWith<IllegalStateException> {
            tree.root("root") { h { origin { 0.0 }; size { 1.0 } } }
        }
        assertTrue("both h { } and v { }" in e.message!!, e.message!!)
    }

    @Test
    fun `two roots is an error`() {
        tree.root { box() }
        assertFailsWith<IllegalStateException> { tree.root { box() } }
    }

    @Test
    fun `startIsLow flips which edge is left`() {
        val normal = ElementTree(g).root("n") {
            h { origin { 10.0 }; size { 40.0 } }
            v { origin { 0.0 }; size { 10.0 } }
        }
        assertEquals(10.0, g.leftOf(normal))
        assertEquals(50.0, g.rightOf(normal))

        val flipped = ElementTree(g).root("f") {
            h(startIsLow = false) { origin { 10.0 }; size { 40.0 } }
            v(startIsLow = false) { origin { 0.0 }; size { 10.0 } }
        }
        assertEquals(50.0, g.leftOf(flipped), "origin is still the low coordinate…")
        assertEquals(10.0, g.rightOf(flipped), "…but `left` is now the high edge")
        assertEquals(10.0, g.bottomOf(flipped))
        assertEquals(0.0, g.topOf(flipped))
    }

    // -------------------------------------------------------------------------------
    // Navigation resolves at evaluation time — dsl-and-engine §2.6
    // -------------------------------------------------------------------------------

    @Test
    fun `a forward reference to a following sibling works`() {
        // `next` does not exist when this expression is registered. Registration does not
        // evaluate, so by the time it matters the whole tree is built.
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 300.0 } }
            v { origin { 0.0 }; size { 10.0 } }
            child("a") {
                h {
                    origin { 0.0 }
                    end { leftOf(nextOf(element)!!) - 8.0 }   // forward reference
                }
                v { origin { 0.0 }; size { 10.0 } }
            }
            child("b") {
                h { origin { 100.0 }; size { 50.0 } }
                v { origin { 0.0 }; size { 10.0 } }
            }
        }
        val a = root.children[0]
        assertEquals(92.0, g.rightOf(a))
        assertEquals(92.0, g.widthOf(a))
    }

    @Test
    fun `a chain of siblings each pinned to the previous`() {
        val gap = g.input(4.0, "gap")
        val root = tree.root("row") {
            h { origin { 0.0 }; size { 1000.0 } }
            v { origin { 0.0 }; size { 10.0 } }
            repeat(6) { i ->
                child("i$i") {
                    h {
                        origin {
                            val p = prevOf(element)
                            if (p == null) 0.0 else rightOf(p) + read(gap)
                        }
                        size { 20.0 }
                    }
                    v { origin { 0.0 }; size { 10.0 } }
                }
            }
        }

        assertEquals(listOf(0.0, 24.0, 48.0, 72.0, 96.0, 120.0), root.children.map { g.leftOf(it) })
        g.setInput(gap, 0.0)
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), root.children.map { g.leftOf(it) })
    }

    @Test
    fun `prev and next are null at the ends, and prevOrParent falls back`() {
        val root = tree.root("root") { box(); child("a") { box() }; child("b") { box() } }
        val (a, b) = root.children

        assertNull(g.prevOf(a))
        assertSame(b, g.nextOf(a))
        assertSame(a, g.prevOf(b))
        assertNull(g.nextOf(b))
        assertNull(g.prevOf(root))
        assertNull(g.nextOf(root))

        assertSame(root, g.prevOrParentOf(a), "the first item pins to its container")
        assertSame(a, g.prevOrParentOf(b))
    }

    @Test
    fun `siblings and count include self, and the root is alone`() {
        val root = tree.root("root") { box(); child("a") { box() }; child("b") { box() } }
        val a = root.children[0]
        assertEquals(2, g.countOf(a))
        assertEquals(root.children, g.siblingsOf(a))
        assertEquals(listOf(root), g.siblingsOf(root))
        assertEquals(1, g.countOf(root))
    }

    @Test
    fun `parent chains upwards`() {
        tree.root("root") {
            box()
            child("mid") { box(); child("leaf") { box() } }
        }
        val leaf = tree.query("leaf")
        assertEquals("mid", leaf.parent!!.id)
        assertEquals("root", leaf.parent!!.parent!!.id)
        assertNull(leaf.parent!!.parent!!.parent)
    }

    // -------------------------------------------------------------------------------
    // Navigation is a *tracked* read — layout-design §8.7
    // -------------------------------------------------------------------------------

    @Test
    fun `reading children records a dependency on the child list`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { childrenOf(element).sumOf { widthOf(it) } } }
            v { origin { 0.0 }; size { 10.0 } }
            child("a") { box(w = 30.0) }
            child("b") { box(w = 12.0) }
        }
        assertEquals(42.0, g.widthOf(root))

        val deps = g.dependenciesOf(root.h.slotFor(Property.SIZE)!!)
        assertTrue(
            root.childrenSlot in deps,
            "the list itself must be a dependency, not only the children's widths: $deps",
        )
        assertEquals(3, deps.size, "the list plus two child size slots")
    }

    @Test
    fun `navigating to a sibling depends on the parent's child list, not just its values`() {
        // `next` changing is a purely structural event: no number anywhere has to move for the
        // answer to change. Only the child-list edge can carry that.
        val root = tree.root("root") { box(); child("a") { box() }; child("b") { box() } }
        val a = root.children[0]
        val probe = g.derive("probe") { nextOf(a)?.id?.length?.toDouble() ?: -1.0 }

        assertEquals(1.0, g.read(probe))
        assertEquals(listOf(root.childrenSlot), g.dependenciesOf(probe))
    }

    @Test
    fun `an id binding is a graph node, so a probe depends on it`() {
        tree.root("root") { box(); child("target") { box(w = 44.0) } }
        val probe = g.derive("probe") { widthOf(tree.query("target")) }

        assertEquals(44.0, g.read(probe))
        assertEquals(2, g.dependenciesOf(probe).size, "the id binding plus the width slot")
    }

    // -------------------------------------------------------------------------------
    // query — dsl-and-engine §3.5
    // -------------------------------------------------------------------------------

    @Test
    fun `query resolves an id defined later in composition`() {
        val root = tree.root("root") {
            box()
            child("first") {
                // Refers to an element that does not exist yet. Lazy, so it resolves fine.
                h { origin { 0.0 }; size { widthOf(tree.query("later")) * 2.0 } }
                v { origin { 0.0 }; size { 10.0 } }
            }
            child("later") { box(w = 15.0) }
        }
        assertEquals(30.0, g.widthOf(root.children[0]))
    }

    @Test
    fun `query spans arbitrary parts of the tree`() {
        // §4.3: once query exists, an edge may connect anything to anything. The evaluator does
        // not care; subtree isolation as an optimisation is what this forecloses.
        tree.root("root") {
            box()
            child("leftBranch") { box(); child("deep") { box(w = 7.0) } }
            child("rightBranch") {
                h { origin { 0.0 }; size { widthOf(tree.query("deep")) } }
                v { origin { 0.0 }; size { 10.0 } }
            }
        }
        assertEquals(7.0, g.widthOf(tree.query("rightBranch")))
    }

    @Test
    fun `duplicate ids are a composition-time error`() {
        val e = assertFailsWith<IllegalStateException> {
            tree.root("root") { box(); child("dup") { box() }; child("dup") { box() } }
        }
        assertTrue("duplicate id 'dup'" in e.message!!, e.message!!)
    }

    @Test
    fun `a missing id is a layout-time error naming the slot that asked`() = withNodeNames {
        tree.root("root") {
            h { origin { 0.0 }; size { widthOf(tree.query("nope")) } }
            v { origin { 0.0 }; size { 10.0 } }
        }
        val e = assertFailsWith<IllegalStateException> { g.widthOf(tree.root) }
        assertTrue("no element with id 'nope'" in e.message!!, e.message!!)
        assertTrue("root.h.size" in e.message!!, "must say who asked: ${e.message}")
    }

    @Test
    fun `boundIds lists what composition registered`() {
        tree.root("root") { box(); child("a") { box() }; child { box() } }
        assertEquals(setOf("root", "a"), tree.boundIds, "unnamed children are not bound")
    }

    // -------------------------------------------------------------------------------
    // The two shapes the tree exists to make expressible
    // -------------------------------------------------------------------------------

    @Test
    fun `vertical list of 500 rows, and only what moved recomputes`() {
        // layout-design §8.1. The chain runs through `prev`, so the graph is 500 deep here.
        val rowHeight = g.input(20.0, "rowHeight")
        val root = tree.root("list") {
            h { origin { 0.0 }; size { 200.0 } }
            v { origin { 0.0 }; size { 10_000.0 } }
            repeat(500) { i ->
                child("row$i") {
                    h { origin { 0.0 }; size { 200.0 } }
                    v {
                        origin {
                            val p = prevOf(element)
                            if (p == null) 0.0 else topOf(p)
                        }
                        size { read(rowHeight) }
                    }
                }
            }
        }

        val last = root.children.last()
        val mid = root.children[250]
        assertEquals(9_980.0, g.bottomOf(last))
        assertEquals(10_000.0, g.topOf(last))

        g.setInput(rowHeight, 10.0)
        assertEquals(5_000.0, g.topOf(last))
        assertEquals(2_510.0, g.topOf(mid))

        // Every row's origin depends on the row above, so a global height change is the
        // worst case: all 500 move. What matters is that each origin recomputed *once* —
        // the chain is walked once, not once per reader.
        assertEquals(2, g.recomputeCount(last.v.slotFor(Property.ORIGIN)!!))
        assertEquals(2, g.recomputeCount(mid.v.slotFor(Property.ORIGIN)!!))

        // And that the graph really is 500 deep, so this test is exercising the recursion
        // limit noted in the reactivity README rather than a shallow tree.
        assertEquals(499, g.graphHeightOf(last.v.slotFor(Property.ORIGIN)!!))
    }

    @Test
    fun `shrink-wrap panel with aligned children is cycle-free`() {
        // layout-design §8.2 and the §4.1 argument, now through the real tree: the panel's width
        // comes from its children, and the children's positions come from the panel. Two chains
        // in opposite directions through the same pair of elements, and they never meet.
        val sink = RecordingSink()
        g.diagnostics = sink

        val root = tree.root("panel") {
            h {
                origin { 0.0 }
                size { childrenOf(element).maxOf { widthOf(it) } }
            }
            v { origin { 0.0 }; size { 100.0 } }
            child("a") {
                h { origin { leftOf(element.parent!!) }; size { 40.0 } }
                v { origin { 0.0 }; size { 10.0 } }
            }
            child("b") {
                h { origin { leftOf(element.parent!!) }; size { 90.0 } }
                v { origin { 10.0 }; size { 10.0 } }
            }
        }

        assertEquals(90.0, g.widthOf(root))
        assertEquals(0.0, g.leftOf(root.children[0]))
        assertEquals(0.0, g.leftOf(root.children[1]))
        assertTrue(sink.cycles.isEmpty(), "no governor should be needed here: ${sink.cycles}")

        // The panel's width slot must not touch the children's *origin* slots — those are the
        // ones pointing back at the panel. This is §4.2's provenance precision, through the tree.
        val panelSize = root.h.slotFor(Property.SIZE)!!
        val deps = g.dependenciesOf(panelSize).toSet()
        assertEquals(
            setOf(root.childrenSlot) + root.children.map { it.h.slotFor(Property.SIZE)!! },
            deps,
        )
        for (c in root.children) {
            assertTrue(c.h.slotFor(Property.ORIGIN)!! !in deps, "$c origin must not be a dep")
        }
    }

    @Test
    fun `a shrink-wrap panel invalidates when a child is added`() {
        // The whole reason the child list is a node. Composition is not incremental yet, so this
        // models a second frame by building a second tree over the same graph and checking that
        // structure alone drives the answer.
        fun build(widths: List<Double>): Pair<ElementTree, Element> {
            val t = ElementTree(g)
            val r = t.root("panel${widths.size}") {
                h { origin { 0.0 }; size { childrenOf(element).sumOf { widthOf(it) } } }
                v { origin { 0.0 }; size { 10.0 } }
                for (w in widths) child { box(w = w) }
            }
            return t to r
        }

        val (_, small) = build(listOf(10.0, 20.0))
        assertEquals(30.0, g.widthOf(small))

        val (_, big) = build(listOf(10.0, 20.0, 5.0))
        assertEquals(35.0, g.widthOf(big))
    }

    @Test
    fun `appending to a live tree invalidates the parent through the child list node`() {
        // The direct version of the above, driven by re-publishing the child list — which is what
        // incremental composition will do.
        val root = tree.root("panel") {
            h { origin { 0.0 }; size { childrenOf(element).sumOf { widthOf(it) } } }
            v { origin { 0.0 }; size { 10.0 } }
            child("a") { box(w = 10.0) }
        }
        assertEquals(10.0, g.widthOf(root))
        assertEquals(1, g.recomputeCount(root.h.slotFor(Property.SIZE)!!))

        val extra = tree.newElement(root, 1, "b")
        ElementBuilder(tree, extra).apply { box(w = 25.0) }.finish()
        root.childList += extra
        root.snapshot = root.childList.toList()
        g.setRefInput(root.childrenSlot, root.snapshot)

        assertEquals(35.0, g.widthOf(root))
        assertEquals(2, g.recomputeCount(root.h.slotFor(Property.SIZE)!!))
    }

    // -------------------------------------------------------------------------------
    // Incremental behaviour
    // -------------------------------------------------------------------------------

    @Test
    fun `moving one element does not recompute its siblings' sizes`() {
        val x = g.input(0.0, "x")
        val root = tree.root("root") {
            h { origin { read(x) }; size { 300.0 } }
            v { origin { 0.0 }; size { 10.0 } }
            child("a") {
                h { origin { leftOf(element.parent!!) + 5.0 }; size { 40.0 } }
                v { origin { 0.0 }; size { 10.0 } }
            }
        }
        val a = root.children[0]
        g.leftOf(a)
        g.widthOf(a)

        g.setInput(x, 100.0)
        assertEquals(105.0, g.leftOf(a))
        assertEquals(40.0, g.widthOf(a))
        assertEquals(2, g.recomputeCount(a.h.slotFor(Property.ORIGIN)!!))
        assertEquals(1, g.recomputeCount(a.h.slotFor(Property.SIZE)!!), "size is independent")
    }

    @Test
    fun `an unchanged parent width stops propagation to children`() {
        val a = g.input(10.0, "a")
        val b = g.input(40.0, "b")
        val root = tree.root("root") {
            // (ORIGIN, END), so both slots move when the interval slides but the size does not.
            h { origin { read(a) }; end { read(a) + read(b) } }
            v { origin { 0.0 }; size { 10.0 } }
            child("centred") {
                h { center { centerXOf(element.parent!!) }; size { widthOf(element.parent!!) / 2.0 } }
                v { origin { 0.0 }; size { 10.0 } }
            }
        }
        val kid = root.children[0]
        assertEquals(20.0, g.widthOf(kid))
        val kidSize = kid.h.slotFor(Property.SIZE)!!
        assertEquals(1, g.recomputeCount(kidSize))

        g.setInput(a, 200.0)                  // slide the parent; its width is unchanged
        assertEquals(20.0, g.widthOf(kid))
        assertEquals(2, g.recomputeCount(kidSize), "it re-ran…")

        val downstream = g.derive("downstream") { widthOf(kid) * 2.0 }
        g.read(downstream)
        g.setInput(a, 300.0)
        g.read(downstream)
        assertEquals(1, g.recomputeCount(downstream), "…but nothing further heard about it")
    }
}
