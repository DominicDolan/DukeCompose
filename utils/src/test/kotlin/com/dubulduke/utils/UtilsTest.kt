package com.dubulduke.utils

import com.dubulduke.dsl.ElementScope
import com.dubulduke.dsl.StyleFactory
import com.dubulduke.dsl.box
import com.dubulduke.dsl.column
import com.dubulduke.dsl.compose
import com.dubulduke.dsl.label
import com.dubulduke.dsl.signal
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.SimpleFont
import com.dubulduke.layout.bottomOf
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.rightOf
import com.dubulduke.layout.topOf
import com.dubulduke.layout.widthOf
import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The utilities themselves.
 *
 * `:dsl`'s own suite covers the mechanism these rest on — buffered slots, container defaults, the
 * two-degrees-of-freedom check. What is worth testing here is narrower and easier to get wrong: that
 * each utility writes the slots it claims to and no others, and that the flow utilities agree with
 * each other about where a line ends.
 */
class UtilsTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
    private val font = SimpleFont("Test", 10.0)

    class AppStyle { var colour: Int = 0 }

    private val styles = StyleFactory { AppStyle() }

    /** A 400×300 root, with no container defaults: every child states all four slots itself. */
    private fun screen(body: ElementScope<AppStyle>.() -> Unit) =
        tree.compose(styles) {
            layout { left { 0.0 }; width { 400.0 }; top { 0.0 }; height { 300.0 } }
            body()
        }

    private fun box(id: String) = tree.query(id)

    // -------------------------------------------------------------------------------
    // Filling and insetting
    // -------------------------------------------------------------------------------

    @Test
    fun `fill takes all four slots, inset on every side`() {
        screen { box("a") { layout { fill(10.0) } } }
        val a = box("a")
        assertEquals(10.0, g.leftOf(a))
        assertEquals(390.0, g.rightOf(a))
        assertEquals(10.0, g.topOf(a))
        assertEquals(290.0, g.bottomOf(a))
    }

    @Test
    fun `inset takes a different amount per axis`() {
        screen { box("a") { layout { inset(horizontal = 20.0, vertical = 5.0) } } }
        val a = box("a")
        assertEquals(20.0, g.leftOf(a))
        assertEquals(380.0, g.rightOf(a))
        assertEquals(5.0, g.topOf(a))
        assertEquals(295.0, g.bottomOf(a))
    }

    @Test
    fun `the four-sided inset reads in CSS order`() {
        screen { box("a") { layout { inset(top = 1.0, right = 2.0, bottom = 3.0, left = 4.0) } } }
        val a = box("a")
        assertEquals(4.0, g.leftOf(a))
        assertEquals(398.0, g.rightOf(a))
        assertEquals(1.0, g.topOf(a))
        assertEquals(297.0, g.bottomOf(a))
    }

    @Test
    fun `fractions are of the parent`() {
        screen { box("a") { layout { alignLeft(); alignTop(); widthFraction(0.25); heightFraction(0.5) } } }
        assertEquals(100.0, g.widthOf(box("a")))
        assertEquals(150.0, g.heightOf(box("a")))
    }

    // -------------------------------------------------------------------------------
    // Alignment
    // -------------------------------------------------------------------------------

    @Test
    fun `each alignment writes one slot, so two of them size an element`() {
        screen {
            box("a") { layout { alignRight(8.0); fixedWidth(100.0); alignBottom(4.0); fixedHeight(20.0) } }
        }
        val a = box("a")
        assertEquals(392.0, g.rightOf(a))
        assertEquals(292.0, g.leftOf(a))
        assertEquals(296.0, g.bottomOf(a))
        assertEquals(276.0, g.topOf(a))
    }

    @Test
    fun `alignCenter centres both axes without stating a size`() {
        screen { box("a") { layout { alignCenter(); size(40.0, 20.0) } } }
        val a = box("a")
        assertEquals(180.0, g.leftOf(a))
        assertEquals(140.0, g.topOf(a))
    }

    @Test
    fun `alignment against another element takes a Layout, so its target already exists`() {
        screen {
            box("a") { layout { left { 100.0 }; fixedWidth(10.0); alignTop(); fixedHeight(10.0) } }
            box("b") { layout { alignLeft(query("a"), 4.0); fixedWidth(10.0); alignTop(); fixedHeight(10.0) } }
        }
        assertEquals(104.0, g.leftOf(box("b")))
    }

    @Test
    fun `a forward reference goes in the expression rather than the utility`() {
        screen {
            // The utility captures its target when it runs, so it cannot name an element that does
            // not exist yet. The slot expression can, because it does not run until layout does —
            // which is the design's headline claim, unchanged by anything in this module.
            box("a") { layout { left { query("b").left + 4.0 }; fixedWidth(10.0); alignTop(); fixedHeight(10.0) } }
            box("b") { layout { left { 100.0 }; fixedWidth(10.0); alignTop(); fixedHeight(10.0) } }
        }
        assertEquals(104.0, g.leftOf(box("a")))
    }

    @Test
    fun `matchBox tracks another element entirely, outset`() {
        screen {
            box("target") { layout { left { 50.0 }; width { 30.0 }; top { 60.0 }; height { 20.0 } } }
            box("ring") { layout { matchBox(query("target"), outset = 2.0) } }
        }
        val ring = box("ring")
        assertEquals(48.0, g.leftOf(ring))
        assertEquals(82.0, g.rightOf(ring))
        assertEquals(58.0, g.topOf(ring))
        assertEquals(82.0, g.bottomOf(ring))
    }

    @Test
    fun `spanBetweenX fills the space two moving elements leave`() {
        screen {
            box("icon") { layout { alignLeft(); fixedWidth(20.0); alignTop(); fixedHeight(10.0) } }
            box("badge") { layout { alignRight(); fixedWidth(30.0); alignTop(); fixedHeight(10.0) } }
            box("title") {
                layout { spanBetweenX(query("icon"), query("badge"), gap = 5.0); alignTop(); fixedHeight(10.0) }
            }
        }
        assertEquals(25.0, g.leftOf(box("title")))
        assertEquals(365.0, g.rightOf(box("title")))
    }

    // -------------------------------------------------------------------------------
    // Sizing from content and children
    // -------------------------------------------------------------------------------

    @Test
    fun `square and aspect read one axis from the other`() {
        screen {
            box("s") { layout { alignLeft(); fixedWidth(33.0); alignTop(); square() } }
            box("a") { layout { alignLeft(); fixedWidth(160.0); alignTop(); aspect(16.0 / 9.0) } }
        }
        assertEquals(33.0, g.heightOf(box("s")))
        assertEquals(90.0, g.heightOf(box("a")))
    }

    @Test
    fun `text sizes both axes`() {
        // HeadlessTextMetrics at ratio 1.0: one unit of width per character, one line-height of 10.
        screen {
            label("hello", font, id = "t") { layout { alignLeft(); alignTop(); sizeFromText() } }
        }
        assertEquals(50.0, g.widthOf(box("t")))
        assertEquals(10.0, g.heightOf(box("t")))
    }

    @Test
    fun `wrapChildren on one axis leaves the other free`() {
        screen {
            box("panel") {
                layout { alignLeft(); alignTop(); fixedWidth(200.0); wrapChildrenHeight(pad = 4.0) }
                box("a") { layout { alignLeft(); fixedWidth(30.0); alignTop(); fixedHeight(10.0) } }
                box("b") { layout { alignLeft(); fixedWidth(30.0); block(); fixedHeight(25.0) } }
            }
        }
        assertEquals(200.0, g.widthOf(box("panel")))
        assertEquals(39.0, g.heightOf(box("panel")), "10 + 25, plus the 4 of padding")
    }

    // -------------------------------------------------------------------------------
    // Flow
    // -------------------------------------------------------------------------------

    @Test
    fun `block stacks down and leaves the horizontal alone`() {
        screen {
            box("a") { layout { fillWidth(8.0); block(4.0); fixedHeight(10.0) } }
            box("b") { layout { fillWidth(8.0); block(4.0); fixedHeight(10.0) } }
        }
        assertEquals(8.0, g.leftOf(box("a")), "block writes no horizontal slot, so the inset stands")
        assertEquals(0.0, g.topOf(box("a")), "first child, so the parent's top and no gap")
        assertEquals(14.0, g.topOf(box("b")))
        assertEquals(8.0, g.leftOf(box("b")))
    }

    @Test
    fun `below is block plus alignLeft, and therefore claims the left slot`() {
        screen {
            box("a") { layout { below(); fixedWidth(50.0); fixedHeight(10.0) } }
            // The ordering rule worth knowing: flow first, then fill. The other way round —
            // `fillWidth(8.0); below()` — leaves `left` at the parent's edge, because `below`
            // wrote it last.
            box("b") { layout { below(4.0); fillWidth(8.0); fixedHeight(10.0) } }
        }
        assertEquals(0.0, g.leftOf(box("a")))
        assertEquals(0.0, g.topOf(box("a")))
        assertEquals(14.0, g.topOf(box("b")))
        assertEquals(8.0, g.leftOf(box("b")), "fillWidth came second, so its inset stands")
        assertEquals(392.0, g.rightOf(box("b")))
    }

    @Test
    fun `inline shares the previous sibling's line`() {
        screen {
            box("a") { layout { alignLeft(); fixedWidth(30.0); alignTop(20.0); fixedHeight(10.0) } }
            box("b") { layout { inline(5.0); fixedWidth(30.0); fixedHeight(10.0) } }
        }
        assertEquals(35.0, g.leftOf(box("b")))
        assertEquals(20.0, g.topOf(box("b")), "the previous sibling's top, not the parent's")
    }

    @Test
    fun `inlineWrap breaks at the parent's right edge and clears the tallest item on the line`() {
        // A 400-wide parent, items 150 wide with a 10 gap: two per line (150 + 10 + 150 = 310,
        // and a third would need 470). The second item is the tall one, so the next line has to
        // clear *it* rather than the item immediately before.
        screen {
            box("a") { layout { inlineWrap(10.0); fixedWidth(150.0); fixedHeight(20.0) } }
            box("b") { layout { inlineWrap(10.0); fixedWidth(150.0); fixedHeight(40.0) } }
            box("c") { layout { inlineWrap(10.0); fixedWidth(150.0); fixedHeight(20.0) } }
            box("d") { layout { inlineWrap(10.0); fixedWidth(150.0); fixedHeight(20.0) } }
        }
        assertEquals(0.0, g.leftOf(box("a")))
        assertEquals(0.0, g.topOf(box("a")))

        assertEquals(160.0, g.leftOf(box("b")), "still fits: 160 + 150 <= 400")
        assertEquals(0.0, g.topOf(box("b")))

        assertEquals(0.0, g.leftOf(box("c")), "wrapped: 320 + 150 > 400")
        assertEquals(50.0, g.topOf(box("c")), "below the 40-tall b, plus the 10 line gap")

        assertEquals(160.0, g.leftOf(box("d")))
        assertEquals(50.0, g.topOf(box("d")), "same line as c")
    }

    @Test
    fun `stackWrapped applies the flow as a container default`() {
        screen {
            box("bar") {
                layout { fill(); }
                stackWrapped(gap = 10.0, lineGap = 4.0)
                box("a") { layout { fixedWidth(300.0); fixedHeight(20.0) } }
                box("b") { layout { fixedWidth(300.0); fixedHeight(20.0) } }
            }
        }
        assertEquals(0.0, g.leftOf(box("b")), "no room beside a, so a new line")
        assertEquals(24.0, g.topOf(box("b")))
    }

    @Test
    fun `a wrapped line reflows when an item's width changes`() {
        // The point of doing this with expressions rather than a measure pass: the wrap decision is
        // an ordinary graph read, so widening the first item moves the second to the next line with
        // no recomposition.
        val wide = g.signal(100.0, "wide")
        screen {
            box("a") { layout { inlineWrap(10.0); width { wide.value }; fixedHeight(20.0) } }
            box("b") { layout { inlineWrap(10.0); fixedWidth(280.0); fixedHeight(20.0) } }
        }
        assertEquals(110.0, g.leftOf(box("b")), "110 + 280 <= 400, so it fits")

        wide.value = 200.0
        assertEquals(0.0, g.leftOf(box("b")), "210 + 280 > 400, so it wraps")
        assertEquals(30.0, g.topOf(box("b")), "a's bottom at 20, plus the 10 line gap")
    }

    // -------------------------------------------------------------------------------
    // Composition with `:dsl`'s containers
    // -------------------------------------------------------------------------------

    @Test
    fun `a utility overrides a container default, as an author's write should`() {
        screen {
            column(gap = 4.0, id = "list") {
                layout { fill() }
                box("a") { layout { fixedHeight(10.0) } }
                // The column stamps left/right/top; this child overrides the horizontal pair and
                // keeps the stacking. Two of the column's three defaults survive, per slot.
                box("b") { layout { alignCenterX(); fixedWidth(50.0); fixedHeight(10.0) } }
            }
        }
        assertEquals(0.0, g.leftOf(box("a")))
        assertEquals(400.0, g.rightOf(box("a")))
        assertEquals(175.0, g.leftOf(box("b")))
        assertEquals(14.0, g.topOf(box("b")), "the column's stacking is untouched")
    }
}
