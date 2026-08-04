package com.dubulduke.dsl

import com.dubulduke.utils.*

import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.Marker
import com.dubulduke.layout.NodeView
import com.dubulduke.layout.Viewport
import com.dubulduke.layout.Window
import com.dubulduke.layout.isHovered
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.rightOf
import com.dubulduke.layout.styleOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ambients, markers and styler functions. `dsl-and-engine.md` §4.1–4.3.
 *
 * The case worth testing hardest is a `forEach` item built **after** composition finished: `provide`
 * is scoped to a block that has long since returned, so an item added at runtime would otherwise
 * see the root's environment and be styled differently from the rows beside it, with nothing in the
 * source to explain why.
 */
class AmbientTest {

    /** A style with enough in it to tell two themes apart. */
    class Style {
        var colour: Long = 0
        var textColour: Long = 0
        var radius: Double = 0.0
    }

    private class App : DukeApp<Style, MutableList<String>>(
        StyleFactory { Style() },
        HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0),
    ) {
        override val window get() = Window(0.0, 0.0, 200.0, 200.0)
        override val viewport get() = Viewport(0.0, 0.0, 200.0, 200.0)
        override val input = TestInput()
        override fun drawBox(node: NodeView<Style>, renderer: MutableList<String>) {}
        override fun drawText(node: NodeView<Style>, t: String, renderer: MutableList<String>) {}
    }

    private data class Theme(val surface: Long, val text: Long)

    private val dark = Theme(surface = 0x111111FF, text = 0xEEEEEEFF)
    private val light = Theme(surface = 0xFFFFFFFF, text = 0x111111FF)

    private val Themed = ambientOf("theme") { dark }
    private val Depth = ambientOf("depth") { 0 }

    private val Card = Marker("card")
    private val Toolbar = Marker("toolbar")

    private fun App.styleOf(id: String): Style = graph.styleOf(tree.query(id)) as Style

    private fun fill(): LayoutScope.() -> Unit = {
        left { 0.0 }; width { 100.0 }; top { 0.0 }; height { 20.0 }
    }

    // -----------------------------------------------------------------------------------
    // Styler functions — the "classes" half, which needs no mechanism at all
    // -----------------------------------------------------------------------------------

    @Test
    fun `styler functions compose and later writes win`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("a") {
                // Two "classes" and an override, which is three function calls and no framework
                // concept — §4.1's whole claim.
                style { panel(); elevated(); colour = 0xABCDEFFF }
                layout(fill())
            }
            box("b") {
                style { panel() }
                layout(fill())
            }
        }
        assertEquals(0xABCDEFFF, app.styleOf("a").colour, "the override won")
        assertEquals(8.0, app.styleOf("a").radius, "but elevated's radius survived")
        assertEquals(4.0, app.styleOf("b").radius)
    }

    @Test
    fun `a styler function still re-runs when its inputs change`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("btn") {
                interactive()
                // Factored into a function and still reactive: the styler is a retained lambda, so
                // where the read is written does not matter, only that it happens while evaluating.
                style { interactivePanel(graph.isHovered(element)) }
                layout(fill())
            }
        }
        assertEquals(0x222222FF, app.styleOf("btn").colour)

        app.frame(10.0, 10.0)
        assertEquals(0x444444FF, app.styleOf("btn").colour, "hovering re-ran it")
    }

    // -----------------------------------------------------------------------------------
    // Ambients
    // -----------------------------------------------------------------------------------

    @Test
    fun `an ambient with no provider gives its default`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("a") {
                val theme = ambient(Themed)
                style { colour = theme.surface }
                layout(fill())
            }
        }
        assertEquals(dark.surface, app.styleOf("a").colour)
    }

    @Test
    fun `provide reaches descendants and stops at the end of its block`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            provide(Themed to light) {
                box("inside") {
                    val theme = ambient(Themed)
                    style { colour = theme.surface }
                    layout(fill())
                }
            }
            box("outside") {
                val theme = ambient(Themed)
                style { colour = theme.surface }
                layout(fill())
            }
        }
        assertEquals(light.surface, app.styleOf("inside").colour)
        assertEquals(dark.surface, app.styleOf("outside").colour, "the block ended")
    }

    @Test
    fun `the nearest provider wins, and nesting carries a value rather than a boolean`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            provide(Depth to 1) {
                box("outer") {
                    style { radius = ambient(Depth).toDouble() }
                    layout(fill())
                    provide(Depth to 2) {
                        box("middle") {
                            style { radius = ambient(Depth).toDouble() }
                            layout(fill())
                            provide(Depth to 3) {
                                box("inner") {
                                    style { radius = ambient(Depth).toDouble() }
                                    layout(fill())
                                }
                            }
                        }
                    }
                }
            }
        }
        // A third-level card differing from a second is exactly what `.card .card &` cannot say
        // without enumerating depths.
        assertEquals(1.0, app.styleOf("outer").radius)
        assertEquals(2.0, app.styleOf("middle").radius)
        assertEquals(3.0, app.styleOf("inner").radius)
    }

    @Test
    fun `provideAll supplies several at once`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            provideAll(Themed to light, Depth to 7) {
                box("a") {
                    val theme = ambient(Themed)
                    val depth = ambient(Depth)
                    style { colour = theme.surface; radius = depth.toDouble() }
                    layout(fill())
                }
            }
        }
        assertEquals(light.surface, app.styleOf("a").colour)
        assertEquals(7.0, app.styleOf("a").radius)
    }

    @Test
    fun `provide does not disturb the element it is called on`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("host") {
                style { colour = 0x010203FF }
                layout(fill())
                // Sharing the layout scope matters: building a second one would make `:layout`
                // reject the duplicate axis, which is the failure this arrangement avoids.
                provide(Themed to light) {
                    box("child") {
                        style { colour = ambient(Themed).surface }
                        layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
                    }
                }
            }
        }
        assertEquals(0x010203FF, app.styleOf("host").colour)
        assertEquals(light.surface, app.styleOf("child").colour)
    }

    @Test
    fun `container defaults still reach children declared inside a provide`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            column(gap = 4.0, id = "list") {
                layout { fillWidth(0.0); top { 0.0 }; height { 100.0 } }
                provide(Themed to light) {
                    // Says only how tall it is; left/right/top come from the column, through the
                    // provide block.
                    box("row") { layout { height { 20.0 } } }
                }
            }
        }
        assertEquals(0.0, app.graph.leftOf(app.tree.query("row")))
        assertEquals(200.0, app.graph.rightOf(app.tree.query("row")))
    }

    // -----------------------------------------------------------------------------------
    // The reconciliation case
    // -----------------------------------------------------------------------------------

    @Test
    fun `a row added at runtime sees the ambients where its forEach was declared`() {
        val app = App()
        var rows = listOf("first")
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            provide(Themed to light) {
                column(gap = 0.0, id = "list") {
                    layout { fillWidth(0.0); top { 0.0 }; height { 100.0 } }
                    forEach({ rows }, key = { it }) { item ->
                        val theme = ambient(Themed)
                        style { colour = theme.surface }
                        layout { height { 10.0 } }
                    }
                }
            }
        }
        val first = app.tree.query("list").children.single()
        assertEquals(light.surface, (app.graph.styleOf(first) as Style).colour)

        // The `provide` block returned during composition. Without capturing the environment at
        // registration this row gets the root's `dark`, and a list ends up with rows styled
        // differently for no reason visible in the source.
        rows = listOf("first", "second")
        app.update(FRAME)

        val second = app.tree.query("list").children[1]
        assertEquals(light.surface, (app.graph.styleOf(second) as Style).colour)
    }

    // -----------------------------------------------------------------------------------
    // Markers
    // -----------------------------------------------------------------------------------

    @Test
    fun `hasAncestor finds a marked ancestor and ignores the element itself`() {
        val app = App()
        var buttonInToolbar = false
        var loneButton = false
        var toolbarSeesItself = false
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("toolbar") {
                marker(Toolbar)
                layout(fill())
                toolbarSeesItself = hasAncestor(Toolbar)
                box("inner") {
                    buttonInToolbar = hasAncestor(Toolbar)
                    layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
                }
            }
            box("lone") {
                loneButton = hasAncestor(Toolbar)
                layout(fill())
            }
        }
        assertTrue(buttonInToolbar)
        assertFalse(loneButton)
        assertFalse(toolbarSeesItself, "strict ancestors only")
    }

    @Test
    fun `ancestorCount answers how deeply nested`() {
        val app = App()
        val depths = ArrayList<Int>()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("l1") {
                marker(Card)
                layout(fill())
                depths += ancestorCount(Card)
                box("l2") {
                    marker(Card)
                    layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
                    depths += ancestorCount(Card)
                    box("l3") {
                        layout { left { 0.0 }; width { 5.0 }; top { 0.0 }; height { 5.0 } }
                        depths += ancestorCount(Card)
                    }
                }
            }
        }
        assertEquals(listOf(0, 1, 2), depths)
    }

    @Test
    fun `an ancestor query costs nothing per frame`() {
        val app = App()
        app.compose {
            layout { left { 0.0 }; width { 200.0 }; top { 0.0 }; height { 200.0 } }
            box("toolbar") {
                marker(Toolbar)
                layout(fill())
                box("button") {
                    // Hoisted out of the styler, which is the intended shape: ancestry cannot
                    // change, so this is a plain Boolean the styler closes over.
                    val compact = hasAncestor(Toolbar)
                    style { radius = if (compact) 2.0 else 8.0 }
                    layout { left { 0.0 }; width { 10.0 }; top { 0.0 }; height { 10.0 } }
                }
            }
        }
        app.draw(ArrayList())
        val before = app.graph.stats.recomputed

        repeat(30) { app.update(FRAME); app.draw(ArrayList()) }

        assertEquals(before, app.graph.stats.recomputed, "no node stands behind an ancestor query")
        assertEquals(2.0, app.styleOf("button").radius)
    }
}

// -------------------------------------------------------------------------------------
// The "stylesheet": a file of extension functions on the style type.
//
// This is the whole of §4.1's "classes are functions" — no framework concept, no registration,
// no matching step. Kept at the bottom of the test rather than in a separate file only because
// it is the thing under test.
// -------------------------------------------------------------------------------------

private fun AmbientTest.Style.panel() {
    colour = 0x2A2A33FF
    radius = 4.0
}

private fun AmbientTest.Style.elevated() {
    radius = 8.0
}

private fun AmbientTest.Style.interactivePanel(hovered: Boolean) {
    panel()
    colour = if (hovered) 0x444444FF else 0x222222FF
}
