package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Structural change at the slot layer. `layout-design.md` Q10.
 *
 * `:dsl`'s ReconcileTest covers keyed matching; what belongs here is narrower and load-bearing
 * under it: that removing a child **invalidates the expressions that navigated past it**, and
 * that everything the subtree owned is actually returned.
 */
class DisposeTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g)

    /** A parent stacking children, each 10 tall, with the parent sized by its last child. */
    private fun stack(count: Int): Element = tree.root("root") {
        h { origin { 0.0 }; size { 100.0 } }
        v {
            origin { 0.0 }
            size { childrenOf(element).lastOrNull()?.let { endOf(it.v) } ?: 0.0 }
        }
        repeat(count) { i ->
            child("c$i") {
                h { origin { 0.0 }; size { 100.0 } }
                v {
                    origin { prevOf(element)?.let { endOf(it.v) } ?: 0.0 }
                    size { 10.0 }
                }
            }
        }
    }

    @Test
    fun `removing a child re-lays out the siblings after it`() {
        val root = stack(3)
        assertEquals(30.0, g.sizeOf(root.v))
        val third = tree.query("c2")
        assertEquals(20.0, g.originOf(third.v))

        tree.dispose(tree.query("c1"))

        // `prev` reads the parent's child list, so the removal reaches c2 without anything
        // walking the tree to tell it — the whole reason the child list is a node (§8.7).
        assertEquals(10.0, g.originOf(third.v))
        assertEquals(20.0, g.sizeOf(root.v))
        assertEquals(1, third.index)
    }

    @Test
    fun `a disposed subtree gives back every node it owned`() {
        stack(1)
        val before = g.nodeCount

        tree.editChildren(tree.root) {
            val added = create(key = "extra") {
                h { origin { 0.0 }; size { 10.0 } }
                v { origin { 0.0 }; size { 10.0 } }
                text("hello", SimpleFont("F", 10.0))
                child("nested") {
                    h { origin { 0.0 }; size { 5.0 } }
                    v { origin { 0.0 }; size { 5.0 } }
                }
            }
            install(current + added)
        }
        assertTrue(g.nodeCount > before)

        tree.dispose(tree.root.children.last())
        assertEquals(before, g.nodeCount)
    }

    @Test
    fun `an id is unbound on disposal and may be used again`() {
        stack(2)
        assertTrue("c1" in tree.boundIds)

        tree.dispose(tree.query("c1"))
        assertTrue("c1" !in tree.boundIds)

        // Freed, so the same id can name a different element — which is what a list that loses
        // an item and gains one under the same name does.
        tree.editChildren(tree.root) {
            install(
                current + create(key = null, id = "c1") {
                    h { origin { 0.0 }; size { 100.0 } }
                    v { origin { 0.0 }; size { 10.0 } }
                }
            )
        }
        assertTrue("c1" in tree.boundIds)
    }

    @Test
    fun `reading a disposed element's geometry is a named error`() {
        stack(2)
        val gone = tree.query("c1")
        tree.dispose(gone)

        // Not a plausible stale number: the slot handle no longer names a live node.
        val e = assertFailsWith<IllegalStateException> { g.sizeOf(gone.v) }
        assertTrue("disposed" in e.message!!, e.message!!)
        assertTrue(gone.disposed)
    }

    @Test
    fun `the root cannot be disposed`() {
        stack(1)
        val e = assertFailsWith<IllegalStateException> { tree.dispose(tree.root) }
        assertTrue("root" in e.message!!, e.message!!)
    }

    @Test
    fun `disposing a clamped flex row releases its governor first`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 100.0 } }
            v { origin { 0.0 }; size { 20.0 } }
            child("row") {
                h { origin { 0.0 }; size { 100.0 } }
                v { origin { 0.0 }; size { 20.0 } }
                flexRow(clamped = true) {
                    grow(1.0, id = "a", max = { 30.0 }) { v { origin { 0.0 }; size { 20.0 } } }
                    grow(1.0, id = "b") { v { origin { 0.0 }; size { 20.0 } } }
                }
            }
        }
        assertEquals(30.0, g.sizeOf(tree.query("a").h))
        val before = g.nodeCount

        // The children's size slots are the governor's claims, so disposing them without
        // releasing it first would leave a governor failing totality with no clue why.
        tree.dispose(tree.query("row"))
        assertTrue(g.nodeCount < before)
        assertEquals(0, root.children.size)
    }
}
