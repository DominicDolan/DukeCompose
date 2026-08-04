package com.dubulduke.dsl

import com.dubulduke.utils.*

import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.SimpleFont
import com.dubulduke.layout.Pointer
import com.dubulduke.layout.isHovered
import com.dubulduke.layout.styleOf
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.rightOf
import com.dubulduke.layout.topOf
import com.dubulduke.layout.widthOf
import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The authoring surface. `dsl-and-engine.md` §2–3.
 *
 * The claims worth testing are not "boxes end up somewhere" — `:layout` covers that — but the two
 * things this layer adds: **container defaults compose with per-slot overrides**, and **the two
 * degrees of freedom are checked after defaults are folded in**, so the error names the real
 * conflict rather than whichever assignment happened to arrive third.
 */
class DslTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g, HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0))
    private val font = SimpleFont("Test", 10.0)

    /** An application style. The framework never sees this class. */
    class AppStyle {
        var colour: Int = 0
        var glow: Double = 0.0
    }

    private val styles = StyleFactory { AppStyle() }

    private fun screen(body: ElementScope<AppStyle>.() -> Unit) =
        tree.compose(styles) {
            layout { left { 0.0 }; width { 400.0 }; top { 0.0 }; height { 300.0 } }
            body()
        }

    // -------------------------------------------------------------------------------
    // Slot assignment
    // -------------------------------------------------------------------------------

    @Test
    fun `two edges per axis is the whole rule`() {
        screen {
            box("a") { layout { left { 10.0 }; width { 50.0 }; top { 20.0 }; bottom { 60.0 } } }
        }
        val a = tree.query("a")
        assertEquals(10.0, g.leftOf(a))
        assertEquals(60.0, g.rightOf(a))
        assertEquals(20.0, g.topOf(a))
        assertEquals(40.0, g.heightOf(a))
    }

    @Test
    fun `a container default yields to the element's own block on a different slot`() {
        // The case that crashed the first time the sample ran in Mechanica: `row` stamps top and
        // bottom to fill its height, and a child states its own height. Three assignments, of
        // which only one was written by the author — so a default yields rather than erroring.
        screen {
            row(id = "r") {
                layout { left { 0.0 }; width { 300.0 }; top { 10.0 }; height { 40.0 } }
                box("chip") { layout { width { 28.0 }; height { 28.0 } } }
            }
        }
        val chip = tree.query("chip")
        assertEquals(28.0, g.heightOf(chip), "the element's own height should survive")
        assertEquals(10.0, g.topOf(chip), "the earlier-declared default (top) is the one kept")
        assertEquals(0.0, g.leftOf(chip))
    }

    @Test
    fun `the later-declared default is the one dropped`() {
        // `column` declares left, then right. A child with its own width forces one out, and
        // keeping the earlier leaves it left-aligned rather than right-aligned.
        screen {
            column(id = "col") {
                layout { left { 20.0 }; width { 200.0 }; top { 0.0 }; height { 300.0 } }
                box("a") { layout { width { 50.0 }; height { 10.0 } } }
            }
        }
        assertEquals(20.0, g.leftOf(tree.query("a")), "left is declared before right, so it stays")
        assertEquals(50.0, g.widthOf(tree.query("a")))
    }

    @Test
    fun `two explicit assignments can push out both defaults`() {
        screen {
            row(id = "r") {
                layout { left { 0.0 }; width { 300.0 }; top { 0.0 }; height { 100.0 } }
                box("a") { layout { width { 10.0 }; height { 20.0 }; centerY { 50.0 } } }
            }
        }
        val a = tree.query("a")
        assertEquals(20.0, g.heightOf(a))
        assertEquals(40.0, g.topOf(a), "centred on 50 with height 20")
    }

    @Test
    fun `an over-constrained axis names every conflicting edge at once`() {
        val e = assertFailsWith<IllegalStateException> {
            screen {
                box("a") { layout { left { 0.0 }; right { 10.0 }; width { 5.0 }; height { 1.0 } } }
            }
        }
        val m = e.message!!
        assertTrue("left" in m && "right" in m && "width" in m, m)
        assertTrue("two degrees of freedom" in m, m)
        assertTrue("set explicitly" in m, m)
    }

    @Test
    fun `an under-constrained axis is an error rather than a default of zero`() {
        val e = assertFailsWith<IllegalStateException> {
            screen { box("a") { layout { left { 0.0 }; top { 0.0 }; height { 10.0 } } } }
        }
        val m = e.message!!
        assertTrue("horizontal" in m, m)
        assertTrue("only left is set" in m, m)
        // The reason it is not a default: a zeroed slot produces a plausible-looking wrong layout.
        assertTrue("plausible and wrong" in m, m)
    }

    @Test
    fun `the later assignment wins within one slot`() {
        screen {
            box("a") {
                layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
                layout { width { 99.0 } }
            }
        }
        assertEquals(99.0, g.widthOf(tree.query("a")))
    }

    // -------------------------------------------------------------------------------
    // Container defaults — §2.4
    // -------------------------------------------------------------------------------

    @Test
    fun `a column stacks its children and each keeps the column's width`() {
        screen {
            column(gap = 4.0, id = "col") {
                layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 300.0 } }
                box("a") { layout { height { 20.0 } } }
                box("b") { layout { height { 30.0 } } }
                box("c") { layout { height { 10.0 } } }
            }
        }
        // Each child set only its height; left, right and top all came from the column.
        for (id in listOf("a", "b", "c")) {
            assertEquals(0.0, g.leftOf(tree.query(id)), "$id.left")
            assertEquals(200.0, g.widthOf(tree.query(id)), "$id.width")
        }
        assertEquals(0.0, g.topOf(tree.query("a")))
        assertEquals(24.0, g.topOf(tree.query("b")), "below a, plus the gap")
        assertEquals(58.0, g.topOf(tree.query("c")))
    }

    @Test
    fun `a child overrides one inherited slot and keeps the rest`() {
        screen {
            column(gap = 0.0, id = "col") {
                layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 300.0 } }
                box("a") { layout { height { 20.0 } } }
                box("b") {
                    // Opts out of the stacking, keeps the column's left and right.
                    layout { top { 100.0 }; height { 30.0 } }
                }
            }
        }
        val b = tree.query("b")
        assertEquals(100.0, g.topOf(b), "the child's own top should win over the column's")
        assertEquals(0.0, g.leftOf(b), "left should still come from the column")
        assertEquals(200.0, g.widthOf(b))
    }

    @Test
    fun `defaults apply before the element's own block regardless of call order`() {
        // The mechanism: defaults are stamped when the scope is created, so the element's block
        // is simply a later write to the same slot. Nothing has to merge or compare.
        screen {
            column(id = "col") {
                layout { left { 0.0 }; width { 100.0 }; top { 0.0 }; height { 100.0 } }
                box("a") {
                    layout { height { 10.0 } }
                    layout { left { 42.0 } }
                }
            }
        }
        assertEquals(42.0, g.leftOf(tree.query("a")))
    }

    @Test
    fun `defaults do not leak into grandchildren`() {
        screen {
            column(gap = 0.0, id = "col") {
                layout { left { 0.0 }; width { 100.0 }; top { 0.0 }; height { 100.0 } }
                box("outer") {
                    layout { height { 50.0 } }
                    // A plain box sets no childDefaults, so this child inherits nothing and must
                    // say everything itself.
                    box("inner") { layout { left { 5.0 }; width { 5.0 }; top { 5.0 }; height { 5.0 } } }
                }
            }
        }
        assertEquals(5.0, g.leftOf(tree.query("inner")))
        assertEquals(5.0, g.widthOf(tree.query("inner")))
    }

    @Test
    fun `a row stacks horizontally and fills the parent's height`() {
        screen {
            row(gap = 5.0, id = "r") {
                layout { left { 0.0 }; width { 300.0 }; top { 10.0 }; height { 40.0 } }
                box("a") { layout { width { 20.0 } } }
                box("b") { layout { width { 30.0 } } }
            }
        }
        assertEquals(0.0, g.leftOf(tree.query("a")))
        assertEquals(25.0, g.leftOf(tree.query("b")))
        assertEquals(10.0, g.topOf(tree.query("b")))
        assertEquals(40.0, g.heightOf(tree.query("b")))
    }

    // -------------------------------------------------------------------------------
    // Utilities — §2.3
    // -------------------------------------------------------------------------------

    @Test
    fun `utilities compose because they write different slots`() {
        screen {
            box("a") {
                layout {
                    // `block` rather than `below`: `below` is block-plus-alignLeft, so it would
                    // write `left` too and — last write wins — undo the 8.0 inset. Utilities
                    // compose when they write different slots, which is the claim under test;
                    // when two of them write the same slot the later one simply wins, visibly.
                    fillWidth(8.0)
                    block(4.0)
                    height { 40.0 }
                }
            }
        }
        val a = tree.query("a")
        assertEquals(8.0, g.leftOf(a))
        assertEquals(392.0, g.rightOf(a))
        assertEquals(0.0, g.topOf(a), "no previous sibling, so the parent's top")
        assertEquals(40.0, g.heightOf(a))
    }

    @Test
    fun `square reads one axis from the other`() {
        screen {
            box("a") { layout { left { 0.0 }; width { 33.0 }; top { 0.0 }; square() } }
        }
        assertEquals(33.0, g.heightOf(tree.query("a")))
    }

    @Test
    fun `wrapChildren shrink-wraps and sees a child being added`() {
        screen {
            box("panel") {
                layout { left { 0.0 }; top { 0.0 }; wrapChildren(pad = 4.0) }
                box("a") { layout { left { 0.0 }; width { 30.0 }; top { 0.0 }; height { 10.0 } } }
                box("b") { layout { left { 0.0 }; width { 50.0 }; top { 10.0 }; height { 15.0 } } }
            }
        }
        assertEquals(54.0, g.widthOf(tree.query("panel")))
        assertEquals(29.0, g.heightOf(tree.query("panel")))
    }

    @Test
    fun `a label takes its height from its own wrapped text`() {
        screen {
            column(id = "col") {
                layout { left { 0.0 }; width { 50.0 }; top { 0.0 }; height { 300.0 } }
                // 50 wide at 10 per character is 5 characters a line; "aaa bbb ccc" is three lines.
                label("aaa bbb ccc", font, id = "l")
            }
        }
        assertEquals(30.0, g.heightOf(tree.query("l")), "three lines at lineHeight 10")
    }

    // -------------------------------------------------------------------------------
    // Signals — reading without spelling out the graph
    // -------------------------------------------------------------------------------

    @Test
    fun `a signal read inside an expression is tracked like any other read`() {
        val zoom = g.signal(1.0, "zoom")
        screen {
            box("a") {
                layout { left { 0.0 }; width { 100.0 * zoom.value }; top { 0.0 }; height { 10.0 } }
            }
        }
        assertEquals(100.0, g.widthOf(tree.query("a")))

        zoom.value = 2.5
        assertEquals(250.0, g.widthOf(tree.query("a")), "the signal wrapper lost the dependency")
    }

    @Test
    fun `writing a signal invalidates only what reads it`() {
        val zoom = g.signal(1.0)
        val unrelated = g.signal(1.0)
        screen {
            box("a") {
                layout { left { 0.0 }; width { 100.0 * zoom.value }; top { 0.0 }; height { 10.0 } }
            }
        }
        g.widthOf(tree.query("a"))

        val before = g.stats.recomputed
        unrelated.value = 99.0
        g.widthOf(tree.query("a"))
        assertEquals(before, g.stats.recomputed, "an unrelated signal invalidated the width")
    }

    @Test
    fun `writing an unchanged value costs nothing`() {
        val zoom = g.signal(2.0)
        screen {
            box("a") {
                layout { left { 0.0 }; width { 10.0 * zoom.value }; top { 0.0 }; height { 10.0 } }
            }
        }
        g.widthOf(tree.query("a"))
        val before = g.stats.marked
        zoom.value = 2.0
        assertEquals(before, g.stats.marked, "a no-op write marked something")
    }

    @Test
    fun `a ref signal compares by value, so a rebuilt string does not re-measure`() {
        val caption = g.refSignal("Total: 5")
        screen {
            column(id = "col") {
                layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 100.0 } }
                box("a") {
                    layout { height { 10.0 * caption.value.length } }
                }
            }
        }
        assertEquals(80.0, g.heightOf(tree.query("a")))

        val before = g.stats.recomputed
        caption.value = "Total: " + 5   // equal content, freshly allocated
        g.heightOf(tree.query("a"))
        assertEquals(before, g.stats.recomputed, "an equal string re-triggered the computation")

        caption.value = "Total: 12"
        assertEquals(90.0, g.heightOf(tree.query("a")))
    }

    // -------------------------------------------------------------------------------
    // Style
    // -------------------------------------------------------------------------------

    @Test
    fun `style objects come from the application's factory`() {
        var made = 0
        val counted = StyleFactory { made++; AppStyle() }
        tree.compose(counted) {
            layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
            box("a") {
                style { colour = 7; glow = 2.5 }
                layout { left { 0.0 }; width { 1.0 }; top { 0.0 }; height { 1.0 } }
            }
        }
        assertEquals(1, made, "a style object per styled element, made by the application")
        val s = g.styleOf(tree.query("a")) as AppStyle
        assertEquals(7, s.colour)
        assertEquals(2.5, s.glow)
    }

    /**
     * A component constrained to the capability it uses. `dsl-and-engine.md` §4.5's payoff: this
     * will not compile against a style type without `glow`, rather than silently no-opping.
     */
    interface HasGlow {
        var glow: Double
    }

    class NeonStyle : HasGlow {
        override var glow: Double = 0.0
        var colour: Int = 0
    }

    private fun <S> ElementScope<S>.glowingBox(id: String, level: Double) where S : Any, S : HasGlow =
        box(id) {
            style { glow = level }
            layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
        }

    @Test
    fun `a component can require a style capability and read it generically`() {
        tree.compose(StyleFactory { NeonStyle() }) {
            layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
            glowingBox("neon", 3.0)
        }
        assertEquals(3.0, (g.styleOf(tree.query("neon")) as NeonStyle).glow)
    }

    // -------------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------------

    @Test
    fun `onClick reaches through the scope`() {
        var clicks = 0
        screen {
            box("btn") {
                onClick { clicks++ }
                layout { left { 0.0 }; width { 100.0 }; top { 0.0 }; height { 50.0 } }
            }
        }
        val p = Pointer(tree)
        p.update(10.0, 10.0, down = true)
        p.update(10.0, 10.0, down = false)
        assertEquals(1, clicks)
    }

    @Test
    fun `a style can read hover, and hovering recomputes no layout slot`() {
        screen {
            box("btn") {
                interactive()
                // `graph` and `element` are reachable from inside `style { }`: the block's
                // receiver is the application's style type, which carries no @DukeDsl marker, so
                // the outer scope is not masked. That is what keeps styles able to read signals.
                style { colour = if (graph.isHovered(element)) 1 else 0 }
                layout { left { 0.0 }; width { 100.0 }; top { 0.0 }; height { 50.0 } }
            }
        }
        val btn = tree.query("btn")
        assertEquals(0, (g.styleOf(btn) as AppStyle).colour)

        val p = Pointer(tree)
        // Settle layout first: the hit test is the first thing to pull geometry, so measuring from
        // before it would count the initial evaluation rather than the effect of hovering.
        p.update(500.0, 500.0, down = false)
        val before = g.stats.recomputed
        p.update(10.0, 10.0, down = false)
        assertEquals(1, (g.styleOf(btn) as AppStyle).colour)
        assertEquals(before + 1, g.stats.recomputed, "hovering recomputed a layout slot")
    }
}
