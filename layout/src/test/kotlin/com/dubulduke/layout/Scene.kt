package com.dubulduke.layout

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.Slot

/**
 * The shared benchmark scene, in the shape real UI has: a fixed sidebar plus a shrink-wrapped
 * list of rows, each row a flex row of icon / label / badge, rows chained vertically through
 * `prev`, row height driven by its label's wrapped text.
 *
 * Used by both `IncrementalCostTest` (plan step 6 — what a change costs) and `ScaleTest` (plan
 * step 7 — what the node store costs at size), so the two are measuring the same thing.
 *
 * The vertical chaining is deliberate and is what makes the measurement interesting rather than
 * flattering: a text change in the middle of the list *must* move everything below it, so the
 * scene contains a genuinely long dependency chain rather than a wide flat fan-out.
 */
internal class Scene(
    val g: Graph,
    val tree: ElementTree,
    val viewportW: Slot,
    val viewportH: Slot,
    val rowList: List<Element>,
) {
    val rows: Int get() = rowList.size

    /** Force a full frame: every rect of every element, as a naive draw walk would. */
    fun layoutAll(): Double {
        var acc = 0.0
        tree.walk { e, _ ->
            acc += g.leftOf(e) + g.topOf(e) + g.rightOf(e) + g.bottomOf(e)
        }
        return acc
    }

    /** What a dirty-list draw would read after a change confined to one row. */
    fun readRow(i: Int): Double {
        var acc = 0.0
        tree.walk(rowList[i]) { e, _ ->
            acc += g.leftOf(e) + g.topOf(e) + g.rightOf(e) + g.bottomOf(e)
        }
        return acc
    }

    fun label(i: Int): Element = tree.query("label$i")

    fun setText(i: Int, text: String) = g.setRefInput(label(i).content!!.textSlot, text)
}

/** Uniform row pitch for the unchained scene. */
internal const val PITCH = 28.0

internal object SceneText {
    val font = SimpleFont("Inter", 16.0)

    /** One line at this font: `0.6 * 16 = 9.6` per char, `1.2 * 16 = 19.2` per line. */
    const val SHORT = "Inbox"
    const val OTHER_SHORT = "Drafts"
    val LONG = "x".repeat(40) + " " + "y".repeat(40) + " " + "z".repeat(40)
}

/** Four elements per row, plus root + sidebar + list. */
internal fun elementsForRows(rows: Int) = 4 * rows + 3

/** Rows needed to reach approximately [elements] elements. */
internal fun rowsForElements(elements: Int) = (elements - 3) / 4

/**
 * [chained] chooses the *shape of the dependency graph*, which turns out to matter far more than
 * the element count (plan step 7):
 *
 *  - `true` — row *n*'s position comes from row *n−1*'s far edge, so a text change anywhere moves
 *    everything below it and heights are fully data-driven. Chain depth is O(rows).
 *  - `false` — rows are positioned by index arithmetic against a uniform pitch, as a virtualised
 *    or absolutely-positioned list does. Same elements, same node count, but chain depth is O(1).
 *
 * Both are layouts a real UI writes. Measuring them side by side is what separates "40k elements
 * is expensive" from "a 40k-long dependency chain is expensive".
 */
internal fun buildScene(rows: Int, chained: Boolean = true): Scene {
    val g = Graph()
    val tree = ElementTree(g)
    val viewportW = g.input(1200.0, "viewportW")
    val viewportH = g.input(800.0, "viewportH")
    val rowList = ArrayList<Element>(rows)

    tree.root("app") {
        h { origin { 0.0 }; size { read(viewportW) } }
        v { origin { 0.0 }; size { read(viewportH) } }

        child("sidebar") {
            h { origin { 0.0 }; size { 200.0 } }
            v { origin { 0.0 }; size { read(viewportH) } }
        }

        child("list") {
            h { origin { rightOf(tree.query("sidebar")) }; end { rightOf(element.parent!!) } }
            v {
                origin { 0.0 }
                // Shrink-wrap: the last row's far edge. Reads the child *list*, so inserting a
                // row invalidates this even though no existing number moved (§8.7).
                size { childrenOf(element).lastOrNull()?.let { endOf(it.v) } ?: 0.0 }
            }

            for (i in 0 until rows) {
                val row = child("row$i") {
                    val self = element
                    h {
                        origin { originOf(self.parent!!.h) }
                        end { endOf(self.parent!!.h) }
                    }
                    v {
                        origin {
                            if (chained) prevOf(self)?.let { endOf(it.v) }
                                ?: originOf(self.parent!!.v)
                            else originOf(self.parent!!.v) + self.index * PITCH
                        }
                        // §8.7's `children.maxOf { }`, which is also what makes the row's height
                        // depend on its label's wrapped text.
                        size { childrenOf(self).maxOf { heightOf(it) } + 8.0 }
                    }
                    flexRow {
                        fixed(mainSize = { 16.0 }) {
                            v { origin { originOf(self.v) }; size { 16.0 } }
                        }
                        grow(1.0, "label$i") {
                            text(SceneText.SHORT, SceneText.font)
                            v { origin { originOf(self.v) }; size { contentHeight(element) } }
                        }
                        fixed(mainSize = { 40.0 }) {
                            v { origin { originOf(self.v) }; size { 20.0 } }
                        }
                    }
                }
                rowList += row
            }
        }
    }
    return Scene(g, tree, viewportW, viewportH, rowList)
}
