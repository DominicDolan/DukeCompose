package com.dubulduke.dsl

import com.dubulduke.layout.HeadlessTextMetrics
import com.dubulduke.layout.heightOf
import com.dubulduke.layout.widthOf
import com.dubulduke.layout.NodeView
import com.dubulduke.layout.SimpleFont
import com.dubulduke.layout.Viewport
import com.dubulduke.layout.Window
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DukeApp] — the one class a platform extends.
 *
 * What is worth testing here is not the drawing (`:layout`'s RenderTest covers the walk) but the
 * wiring the base class takes over: that the viewport inputs track [DukeApp.viewport] without the
 * subclass touching them, that they are live *during* composition rather than zero until the first
 * frame, and that clicks arrive with only the four platform members implemented.
 */
class DukeAppTest {

    class AppStyle {
        var colour: Int = 0
    }

    /** A whole platform integration, to see how much a subclass actually has to write. */
    private class TestApp(
        var screenWidth: Double = 200.0,
        var screenHeight: Double = 100.0,
    ) : DukeApp<AppStyle, MutableList<String>>(
        StyleFactory { AppStyle() },
        HeadlessTextMetrics(charWidthRatio = 1.0, lineHeightRatio = 1.0),
    ) {
        override val window: Window get() = Window(0.0, 0.0, screenWidth, screenHeight)

        // Negative height: the y-up host case, which is the one that would show up as a sign bug
        // in the viewport inputs if the base class forwarded the extent unchanged.
        override val viewport: Viewport get() = Viewport(0.0, 0.0, screenWidth, -screenHeight)

        override fun drawBox(node: NodeView<AppStyle>, renderer: MutableList<String>) {
            renderer += "box ${node.element.path}"
        }

        override fun drawText(node: NodeView<AppStyle>, text: String, renderer: MutableList<String>) {
            renderer += "text ${node.element.path} '$text'"
        }
    }

    private val font = SimpleFont("Test", 10.0)

    /** The root filling the screen — what every one of these tests wants and none is about. */
    private fun ElementScope<AppStyle>.fill(app: TestApp) = layout {
        left { 0.0 }; width { app.viewportWidth }
        top { 0.0 }; height { app.viewportHeight }
    }

    @Test
    fun `the viewport size is available during composition`() {
        val app = TestApp(screenWidth = 640.0, screenHeight = 480.0)
        var seen = -1.0
        app.compose {
            fill(app)
            // Not a slot expression: this runs at composition time, which is exactly the moment the
            // inputs would still hold their construction-time zero if compose did not sync first.
            seen = app.viewportWidth
        }
        assertEquals(640.0, seen)
        assertEquals(640.0, app.graph.widthOf(app.root!!))
    }

    @Test
    fun `viewport inputs follow the camera with no subclass wiring`() {
        val app = TestApp()
        app.compose { fill(app) }
        assertEquals(200.0, app.graph.widthOf(app.root!!))

        app.screenWidth = 320.0
        app.update()
        assertEquals(320.0, app.graph.widthOf(app.root!!))
    }

    @Test
    fun `a negative viewport extent still gives layout a positive size`() {
        val app = TestApp(screenHeight = 100.0)
        app.compose { fill(app) }
        // The sign says the host's y axis runs opposite to layout's; it is the draw transform's
        // business, and a `height { viewportHeight }` must not come out negative because of it.
        assertEquals(100.0, app.viewportHeight)
        assertEquals(100.0, app.graph.heightOf(app.root!!))
    }

    @Test
    fun `draw uses the app as its own backend`() {
        val app = TestApp()
        app.compose {
            fill(app)
            label("hi", font) { layout { left { 0.0 }; width { 40.0 }; top { 0.0 } } }
        }

        val calls = mutableListOf<String>()
        app.draw(calls)
        assertContentEquals(listOf("box ui", "text ui.[0] 'hi'"), calls)
    }

    @Test
    fun `clicks dispatch through the base class`() {
        val app = TestApp()
        var clicks = 0
        app.compose {
            fill(app)
            box {
                onClick { clicks++ }
                layout { left { 10.0 }; width { 30.0 }; top { 10.0 }; height { 20.0 } }
            }
        }
        app.draw(mutableListOf())

        // Window coordinates: the y-flip from the negative viewport extent is applied for us, so
        // an element at layout y=10..30 is hit at window y=70..90 on a 100-tall screen.
        app.update(20.0, 80.0, pressed = true)
        app.update(20.0, 80.0, pressed = false)
        assertEquals(1, clicks)

        app.update(20.0, 5.0, pressed = true)
        app.update(20.0, 5.0, pressed = false)
        assertEquals(1, clicks)
    }

    @Test
    fun `update and draw before compose do nothing`() {
        val app = TestApp()
        assertFalse(app.isComposed)
        app.update()
        val calls = mutableListOf<String>()
        app.draw(calls)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `compose is once only`() {
        val app = TestApp()
        app.compose { fill(app) }
        val e = assertFailsWith<IllegalStateException> { app.compose { fill(app) } }
        assertTrue("once" in e.message!!, e.message!!)
    }
}
