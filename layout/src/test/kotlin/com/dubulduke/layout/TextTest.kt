package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Text measurement and intrinsic sizing. `layout-design.md` §8.3, §8.6;
 * `dsl-and-engine.md` §6.1.
 */
class TextTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val metrics = HeadlessTextMetrics()
    private val tree = ElementTree(g, metrics)

    // 10pt font ⇒ 6.0 per character, 12.0 per line.
    private val font = SimpleFont("Test", 10.0)

    // -------------------------------------------------------------------------------
    // The stub, whose behaviour golden files will encode
    // -------------------------------------------------------------------------------

    @Test
    fun `measurement is charWidth times length by lineHeight`() {
        val m = metrics.measure("hello", font, Double.POSITIVE_INFINITY)
        assertEquals(30.0, m.width)
        assertEquals(12.0, m.height)
        assertEquals(1, m.lines)
    }

    @Test
    fun `greedy word wrap breaks between words`() {
        // "aaa bbb ccc" is 11 chars = 66.0 unwrapped. At 45.0 only "aaa bbb" (42.0) fits.
        val m = metrics.measure("aaa bbb ccc", font, 45.0)
        assertEquals(2, m.lines)
        assertEquals(42.0, m.width)
        assertEquals(24.0, m.height)
    }

    @Test
    fun `a word wider than the limit overflows rather than breaking`() {
        val m = metrics.measure("unbreakable", font, 12.0)
        assertEquals(1, m.lines)
        assertEquals(66.0, m.width, "wider than the 12.0 limit, deliberately")
    }

    @Test
    fun `explicit newlines always break`() {
        val m = metrics.measure("a\nb\nc", font, Double.POSITIVE_INFINITY)
        assertEquals(3, m.lines)
        assertEquals(6.0, m.width)
        assertEquals(36.0, m.height)
    }

    @Test
    fun `empty text is one empty line, not zero`() {
        val m = metrics.measure("", font, 100.0)
        assertEquals(1, m.lines)
        assertEquals(0.0, m.width)
        assertEquals(12.0, m.height, "a text box with no text still occupies a line box")
    }

    @Test
    fun `a trailing newline produces a trailing empty line`() {
        val m = metrics.measure("a\n", font, 100.0)
        assertEquals(2, m.lines)
    }

    @Test
    fun `an unknown font type is rejected loudly`() {
        val alien = object : FontRef {}
        assertFailsWith<IllegalArgumentException> { metrics.measure("x", alien, 100.0) }
    }

    @Test
    fun `ratios are configurable`() {
        val wide = HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 2.0)
        val m = wide.measure("abc", SimpleFont("T", 10.0), Double.POSITIVE_INFINITY)
        assertEquals(30.0, m.width)
        assertEquals(20.0, m.height)
    }

    // -------------------------------------------------------------------------------
    // §8.3: constraints flow down one axis while size flows up the other
    // -------------------------------------------------------------------------------

    @Test
    fun `text wrapping falls out of the edges with no special case`() {
        val sink = RecordingSink()
        g.diagnostics = sink
        val panelWidth = g.input(60.0, "panelWidth")

        val root = tree.root("panel") {
            h { origin { 0.0 }; size { read(panelWidth) } }
            v { origin { 0.0 }; size { 500.0 } }
            child("label") {
                text("aaa bbb ccc", font)
                // Horizontal edges flow *down* from the parent…
                h { origin { leftOf(element.parent!!) }; end { rightOf(element.parent!!) } }
                // …and the vertical size flows *up* from the text, on the other axis.
                v { origin { 0.0 }; size { contentHeight(element) } }
            }
        }
        val label = root.children[0]

        assertEquals(60.0, g.widthOf(label))
        assertEquals(24.0, g.heightOf(label), "two lines at 60.0 wide")
        assertTrue(sink.cycles.isEmpty(), "opposite directions on different axes is not a cycle")

        // Narrower panel ⇒ more lines ⇒ taller label. No traversal order made this happen.
        g.setInput(panelWidth, 20.0)
        assertEquals(36.0, g.heightOf(label), "three lines at 20.0 wide")
        g.setInput(panelWidth, 200.0)
        assertEquals(12.0, g.heightOf(label), "one line at 200.0 wide")
    }

    @Test
    fun `the height slot depends on the width slots, which is what makes wrapping work`() {
        val root = tree.root("panel") {
            h { origin { 0.0 }; size { 60.0 } }
            v { origin { 0.0 }; size { 500.0 } }
            child("label") {
                text("aaa bbb", font)
                h { origin { 0.0 }; size { 60.0 } }
                v { origin { 0.0 }; size { contentHeight(element) } }
            }
        }
        val label = root.children[0]
        g.heightOf(label)

        val deps = g.dependenciesOf(label.v.slotFor(Property.SIZE)!!).toSet()
        assertTrue(label.content!!.textSlot in deps)
        assertTrue(label.content!!.fontSlot in deps)
        assertTrue(
            label.h.slotFor(Property.SIZE)!! in deps,
            "the cross-axis edge: vertical size reads horizontal size",
        )
    }

    @Test
    fun `intrinsic width is the unwrapped width — layout-design 8_6`() {
        val root = tree.root("panel") {
            h { origin { 0.0 }; size { intrinsicWidthOf(childrenOf(element)[0]) } }
            v { origin { 0.0 }; size { 100.0 } }
            child("label") {
                text("shrink to fit", font)
                h { origin { 0.0 }; size { intrinsicWidthOf(element) } }
                v { origin { 0.0 }; size { 12.0 } }
            }
        }
        assertEquals(78.0, g.widthOf(root), "13 characters at 6.0")
        assertEquals(78.0, g.widthOf(root.children[0]))
    }

    // -------------------------------------------------------------------------------
    // Re-measurement happens exactly when it should
    // -------------------------------------------------------------------------------

    @Test
    fun `changing the text re-measures`() {
        val label = textLabel("hello")
        assertEquals(30.0, g.widthOf(label))

        g.setRefInput(label.content!!.textSlot, "hello there")
        assertEquals(66.0, g.widthOf(label))
    }

    @Test
    fun `re-writing equal text does not re-measure — the reason EQUALS exists`() {
        val label = textLabel("Total: 5")
        assertEquals(48.0, g.widthOf(label))
        val slot = label.h.slotFor(Property.SIZE)!!
        assertEquals(1, g.recomputeCount(slot))

        // A fresh String with the same content, which is what recomposition produces.
        g.setRefInput(label.content!!.textSlot, "Total: " + 5)
        assertEquals(48.0, g.widthOf(label))
        assertEquals(1, g.recomputeCount(slot), "text measurement must not re-run")

        g.setRefInput(label.content!!.textSlot, "Total: 12")
        assertEquals(54.0, g.widthOf(label))
        assertEquals(2, g.recomputeCount(slot))
    }

    @Test
    fun `changing the font re-measures`() {
        val label = textLabel("hello")
        assertEquals(30.0, g.widthOf(label))
        g.setRefInput(label.content!!.fontSlot, SimpleFont("Test", 20.0))
        assertEquals(60.0, g.widthOf(label))
    }

    @Test
    fun `an unchanged wrap width does not re-measure`() {
        val panelWidth = g.input(60.0, "panelWidth")
        val root = tree.root("panel") {
            h { origin { 0.0 }; size { read(panelWidth) } }
            v { origin { 0.0 }; size { 500.0 } }
            child("label") {
                text("aaa bbb ccc", font)
                h { origin { 0.0 }; size { read(panelWidth) } }
                v { origin { 0.0 }; size { contentHeight(element) } }
            }
        }
        val heightSlot = root.children[0].v.slotFor(Property.SIZE)!!
        assertEquals(24.0, g.heightOf(root.children[0]))
        assertEquals(1, g.recomputeCount(heightSlot))

        repeat(5) { g.heightOf(root.children[0]) }
        assertEquals(1, g.recomputeCount(heightSlot), "memoised like any other node")
    }

    @Test
    fun `a wrap width change that does not change the line count still stops propagating`() {
        val panelWidth = g.input(60.0, "panelWidth")
        val root = tree.root("panel") {
            h { origin { 0.0 }; size { read(panelWidth) } }
            v { origin { 0.0 }; size { 500.0 } }
            child("label") {
                text("aaa bbb ccc", font)
                h { origin { 0.0 }; size { read(panelWidth) } }
                v { origin { 0.0 }; size { contentHeight(element) } }
            }
        }
        val label = root.children[0]
        val below = g.derive("below") { topOf(label) + 4.0 }
        assertEquals(28.0, g.read(below))

        // 60 → 50 still fits exactly two lines, so the height is unchanged and nothing below
        // needs to move. Re-measuring is unavoidable; re-laying-out is not.
        g.setInput(panelWidth, 50.0)
        assertEquals(28.0, g.read(below))
        assertEquals(1, g.recomputeCount(below), "an unchanged height stops the propagation")
    }

    // -------------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------------

    @Test
    fun `an element without content cannot report a content height`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 10.0 } }
            v { origin { 0.0 }; size { contentHeight(element) } }
        }
        val e = assertFailsWith<IllegalStateException> { g.heightOf(root) }
        assertTrue("has no content" in e.message!!, e.message!!)
    }

    @Test
    fun `content cannot be attached twice`() {
        assertFailsWith<IllegalStateException> {
            tree.root("root") {
                text("a", font)
                text("b", font)
                h { origin { 0.0 }; size { 10.0 } }
                v { origin { 0.0 }; size { 10.0 } }
            }
        }
    }

    @Test
    fun `attaching text marks the element as a text element`() {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 10.0 } }
            v { origin { 0.0 }; size { 10.0 } }
            child("label") {
                text("x", font)
                h { origin { 0.0 }; size { 10.0 } }
                v { origin { 0.0 }; size { 10.0 } }
            }
        }
        assertEquals("box", root.kind)
        assertEquals("text", root.children[0].kind)
        assertNull(root.content)
    }

    @Test
    fun `text and font nodes are named after the element`() {
        val label = textLabel("x")
        assertEquals("root.label.text", g.nameOf(label.content!!.textSlot))
        assertEquals("root.label.font", g.nameOf(label.content!!.fontSlot))
    }

    private fun textLabel(text: String): Element {
        val root = tree.root("root") {
            h { origin { 0.0 }; size { 1000.0 } }
            v { origin { 0.0 }; size { 1000.0 } }
            child("label") {
                text(text, font)
                h { origin { 0.0 }; size { intrinsicWidthOf(element) } }
                v { origin { 0.0 }; size { contentHeight(element) } }
            }
        }
        return root.children[0]
    }
}
