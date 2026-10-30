package com.dubulduke.layout

import kotlin.jvm.JvmField

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RefEquality
import com.dubulduke.reactivity.Slot

/**
 * An opaque, backend-owned font handle. Layout never inspects one; it only passes it to
 * [TextMetrics]. Implementations should be interned and comparable by identity, since that is
 * how the graph compares the font node.
 */
interface FontRef

/** A font description sufficient for [HeadlessTextMetrics]. Real backends supply their own. */
data class SimpleFont(val family: String, val size: Double) : FontRef

/** The result of measuring a run of text. */
data class TextSize(val width: Double, val height: Double, val lines: Int)

/**
 * Text measurement, supplied by the host rather than the backend.
 *
 * `dsl-and-engine.md` §6.1 makes the case: layout needs measurement but not drawing, so metrics
 * have to be available independently of a renderer. That is also what lets compose and layout
 * run with no renderer at all, which is the entire test story.
 *
 * [maxWidth] may be [Double.POSITIVE_INFINITY] to ask for the unwrapped width.
 */
interface TextMetrics {
    fun measure(text: String, font: FontRef, maxWidth: Double): TextSize
}

/**
 * A deterministic stub: `charWidth = ratio × size`, `lineHeight = ratio × size`, greedy word
 * wrapping.
 *
 * Deliberately fake. Real font rasterisation varies across platforms and driver versions, which
 * would make golden files worthless — `dsl-and-engine.md` §8.
 *
 * Documented behaviours, since golden files will encode them:
 *  - Explicit `\n` always breaks a line.
 *  - Words are never broken, so a single word wider than [maxWidth] overflows on its own line.
 *  - Empty text is one empty line, not zero lines: a text box with no text still occupies a
 *    line box, which is what every real text engine does.
 */
class HeadlessTextMetrics(
    private val charWidthRatio: Double = 0.6,
    private val lineHeightRatio: Double = 1.2,
) : TextMetrics {

    override fun measure(text: String, font: FontRef, maxWidth: Double): TextSize {
        val fontSize = when (font) {
            is SimpleFont -> font.size
            else -> throw IllegalArgumentException(
                "HeadlessTextMetrics only understands SimpleFont, got ${font::class.simpleName}"
            )
        }
        val charWidth = charWidthRatio * fontSize
        val lineHeight = lineHeightRatio * fontSize

        var widest = 0.0
        var lines = 0
        for (paragraph in text.split('\n')) {
            for (lineChars in wrap(paragraph, charWidth, maxWidth)) {
                widest = maxOf(widest, lineChars * charWidth)
                lines++
            }
        }
        return TextSize(widest, lines * lineHeight, lines)
    }

    /** Character counts of each wrapped line of one paragraph. Always at least one line. */
    private fun wrap(paragraph: String, charWidth: Double, maxWidth: Double): List<Int> {
        if (paragraph.isEmpty()) return listOf(0)
        val out = ArrayList<Int>()
        var current = 0
        for (word in paragraph.split(' ')) {
            val added = if (current == 0) word.length else current + 1 + word.length
            if (current > 0 && added * charWidth > maxWidth) {
                out += current
                current = word.length
            } else {
                current = added
            }
        }
        out += current
        return out
    }
}

/**
 * The measurable content of an element: what the text is, and what font it is in.
 *
 * Two ref nodes, with the comparison policies that matter. Text is [RefEquality.EQUALS] because
 * strings arrive freshly allocated on every composition — interpolation, formatting — and under
 * identity comparison every composition would re-trigger measurement, the most expensive input
 * the layout graph has. The font is [RefEquality.IDENTITY] because handles are interned.
 *
 * Note what is *not* here: no measured-size node. `heightAt(width)` is an ordinary expression
 * evaluated at the read site, so it is memoised by whichever slot asks and re-measures exactly
 * when the text, the font or that width changes. `layout-design.md` §8.6 — Flutter needs a
 * separate intrinsic-measurement protocol with documented O(n²) hazards; here it is arithmetic.
 */
class Content internal constructor(
    val textSlot: Slot,
    val fontSlot: Slot,
    @JvmField internal val metrics: TextMetrics,
)

/** The current text. A tracked read. */
fun Graph.textOf(c: Content): String = readRef(c.textSlot) as String

/** The current font. A tracked read. */
fun Graph.fontOf(c: Content): FontRef = readRef(c.fontSlot) as FontRef

/** Measure [c] wrapped to [maxWidth]. Tracks the text and the font; [maxWidth] is the caller's. */
fun Graph.measure(c: Content, maxWidth: Double): TextSize =
    c.metrics.measure(textOf(c), fontOf(c), maxWidth)

/** Height of [c] when wrapped to [maxWidth] — the `content.heightAt(width)` of §8.3. */
fun Graph.heightAt(c: Content, maxWidth: Double): Double = measure(c, maxWidth).height

/** Width of [c] with no wrapping: the intrinsic, maximum-content width. */
fun Graph.intrinsicWidthOf(c: Content): Double =
    measure(c, Double.POSITIVE_INFINITY).width

/**
 * The height this element's own text needs at this element's own width — the whole of §8.3.
 *
 * ```kotlin
 * h { origin { leftOf(parent) }; end { rightOf(parent) } }
 * v { origin { 0.0 }; size { contentHeight(element) } }
 * ```
 *
 * The horizontal edges flow *down* from the parent and the vertical size flows *up* from the
 * text — on a different axis, in the opposite direction. CSS and Flutter hardcode
 * "constraints down, sizes up" into their traversal order; here it is not a rule, it is just
 * what the edges happen to be.
 */
fun Graph.contentHeight(e: Element): Double =
    heightAt(requireContent(e), widthOf(e))

/** Intrinsic width of this element's own text, ignoring wrapping. */
fun Graph.intrinsicWidthOf(e: Element): Double = intrinsicWidthOf(requireContent(e))

private fun requireContent(e: Element): Content =
    checkNotNull(e.content) { "$e has no content; add text { } during composition" }
