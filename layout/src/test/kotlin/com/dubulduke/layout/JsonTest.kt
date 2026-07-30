package com.dubulduke.layout

import com.dubulduke.reactivity.DiagnosticSink
import com.dubulduke.reactivity.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON path. `dsl-and-engine.md` §8.
 *
 * These are the golden-file tests the design is aiming at: compose, lay out, serialise, compare —
 * with no renderer anywhere.
 */
class JsonTest {

    private val g = Graph().apply { diagnostics = DiagnosticSink.Throwing }
    private val tree = ElementTree(g)
    private val font = SimpleFont("Test", 10.0)

    /** A small but representative screen: a fixed sidebar, a growing pane, and wrapped text. */
    private fun screen(viewportWidth: Double = 400.0): Element = tree.root("app") {
        h { origin { 0.0 }; size { viewportWidth } }
        v { origin { 0.0 }; size { 200.0 } }
        flexRow {
            fixed(mainSize = { 100.0 }, id = "sidebar") {
                classes("panel", "elevated")
                v { origin { 0.0 }; size { 200.0 } }
            }
            grow(1.0, "content") {
                v { origin { 0.0 }; size { 200.0 } }
            }
        }
    }

    // -------------------------------------------------------------------------------
    // Shape
    // -------------------------------------------------------------------------------

    @Test
    fun `the dump is a golden-file-stable tree of rects`() {
        screen()
        assertEquals(
            """
            {
              "id": "app",
              "kind": "box",
              "rect": { "x": 0.0, "y": 0.0, "width": 400.0, "height": 200.0 },
              "children": [
                {
                  "id": "sidebar",
                  "kind": "box",
                  "classes": ["panel", "elevated"],
                  "rect": { "x": 0.0, "y": 0.0, "width": 100.0, "height": 200.0 }
                },
                {
                  "id": "content",
                  "kind": "box",
                  "rect": { "x": 100.0, "y": 0.0, "width": 300.0, "height": 200.0 }
                }
              ]
            }
            """.trimIndent() + "\n",
            tree.dumpJson(),
        )
    }

    @Test
    fun `text elements carry their content`() {
        tree.root("root") {
            h { origin { 0.0 }; size { 60.0 } }
            v { origin { 0.0 }; size { 100.0 } }
            child("label") {
                text("aaa bbb ccc", font)
                h { origin { 0.0 }; size { 60.0 } }
                v { origin { 0.0 }; size { contentHeight(element) } }
            }
        }
        val json = tree.dumpJson()
        assertTrue("\"kind\": \"text\"" in json, json)
        assertTrue("\"text\": \"aaa bbb ccc\"" in json, json)
        assertTrue("\"height\": 24.0" in json, "wrapped to two lines:\n$json")
    }

    @Test
    fun `empty fields are omitted so golden files stay readable`() {
        tree.root {
            h { origin { 0.0 }; size { 1.0 } }
            v { origin { 0.0 }; size { 2.0 } }
        }
        assertEquals(
            """
            {
              "kind": "box",
              "rect": { "x": 0.0, "y": 0.0, "width": 1.0, "height": 2.0 }
            }
            """.trimIndent() + "\n",
            tree.dumpJson(),
        )
    }

    @Test
    fun `a subtree can be dumped on its own`() {
        screen()
        val json = tree.dumpJson(from = tree.query("content"))
        assertTrue(json.startsWith("{\n  \"id\": \"content\""), json)
        assertTrue("sidebar" !in json)
    }

    // -------------------------------------------------------------------------------
    // Derivation — the part §8.1 calls a headline feature
    // -------------------------------------------------------------------------------

    @Test
    fun `derivation reports where each slot's value came from`() {
        screen()
        val json = tree.dumpJson(includeDerivation = true)

        // The assertion no conventional framework supports: not "the pane is 300 wide" but
        // "the pane's width is derived from the container's free pool, not hardcoded".
        assertTrue(
            "\"h.size\": [\"app.totalFlex\", \"app.freeSpace\", \"app.content.flex\"]" in json,
            json,
        )
        // And its position comes from the sibling before it, not from a literal.
        assertTrue(
            "\"h.origin\": [\"app.children\", \"app.sidebar.h.origin\", \"app.sidebar.h.size\"]" in json,
            json,
        )
    }

    @Test
    fun `an empty derivation list means the value is a constant`() {
        // This is the distinction that catches "correct at one viewport size, wrong at another".
        tree.root("root") {
            h { origin { 0.0 }; size { 100.0 } }
            v { origin { 0.0 }; size { 100.0 } }
        }
        val json = tree.dumpJson(includeDerivation = true)
        assertTrue("\"h.origin\": []" in json, json)
        assertTrue("\"h.size\": []" in json, json)
    }

    @Test
    fun `derivation keys name the axis and the written property`() {
        tree.root("root") {
            // (SIZE, CENTER), so the keys must say so rather than assuming origin/size.
            h { size { 40.0 }; center { 100.0 } }
            v { origin { 0.0 }; end { 50.0 } }
        }
        val json = tree.dumpJson(includeDerivation = true)
        assertTrue("\"h.size\": []" in json, json)
        assertTrue("\"h.center\": []" in json, json)
        assertTrue("\"v.origin\": []" in json, json)
        assertTrue("\"v.end\": []" in json, json)
        assertTrue("h.origin" !in json, "origin is derived on this axis, not written")
    }

    // -------------------------------------------------------------------------------
    // Robustness
    // -------------------------------------------------------------------------------

    @Test
    fun `dumping lays the tree out, so it is also the phase-2 driver`() {
        val root = screen()
        val sizeSlot = tree.query("content").h.slotFor(Property.SIZE)!!
        assertEquals(0, g.recomputeCount(sizeSlot), "nothing has been pulled yet")

        tree.dumpJson()
        assertEquals(1, g.recomputeCount(sizeSlot))
        assertTrue(root.children.isNotEmpty())
    }

    @Test
    fun `the dump reflects a changed input without recomposition`() {
        val width = g.input(400.0, "width")
        tree.root("app") {
            h { origin { 0.0 }; size { read(width) } }
            v { origin { 0.0 }; size { 100.0 } }
        }
        assertTrue("\"width\": 400.0" in tree.dumpJson())

        g.setInput(width, 800.0)
        assertTrue("\"width\": 800.0" in tree.dumpJson())
    }

    @Test
    fun `NaN and infinity are emitted as strings rather than invalid JSON`() {
        tree.root("root") {
            h { origin { 0.0 }; size { 0.0 / 0.0 } }
            v { origin { 0.0 }; size { 1.0 / 0.0 } }
        }
        val json = tree.dumpJson()
        assertTrue("\"width\": \"NaN\"" in json, json)
        assertTrue("\"height\": \"Infinity\"" in json, json)
    }

    @Test
    fun `strings are escaped`() {
        tree.root("root") {
            text("say \"hi\"\nand\ttab \\ done", font)
            h { origin { 0.0 }; size { 10.0 } }
            v { origin { 0.0 }; size { 10.0 } }
        }
        val json = tree.dumpJson()
        assertTrue("""\"hi\"""" in json, json)
        assertTrue("""\n""" in json, json)
        assertTrue("""\t""" in json, json)
        assertTrue("""\\""" in json, json)
    }

    @Test
    fun `deep nesting indents consistently`() {
        tree.root("a") {
            h { origin { 0.0 }; size { 10.0 } }
            v { origin { 0.0 }; size { 10.0 } }
            child("b") {
                h { origin { 0.0 }; size { 10.0 } }
                v { origin { 0.0 }; size { 10.0 } }
                child("c") {
                    h { origin { 0.0 }; size { 10.0 } }
                    v { origin { 0.0 }; size { 10.0 } }
                }
            }
        }
        val json = tree.dumpJson()
        assertEquals(1, json.lines().count { it == """      "id": "b",""" }, json)
        assertEquals(1, json.lines().count { it == """          "id": "c",""" }, json)
        // Balanced, which is the cheap proxy for "parseable".
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }
}
