package com.dubulduke.utils

import com.dubulduke.dsl.ElementScope
import com.dubulduke.dsl.LayoutList
import com.dubulduke.dsl.LayoutScope

/**
 * Flow: where an element sits relative to the sibling before it.
 *
 * The vocabulary is CSS's, because the distinction it draws is the useful one and everybody already
 * knows it. [block] takes a line of its own; [inline] sits alongside what came before; [inlineWrap]
 * sits alongside until there is no room and then starts a new line.
 *
 * What is *not* CSS is that these are per-element rather than per-container, and mix freely: one
 * [block] child among [inline] ones breaks the line where it appears, because each element's slots
 * are its own. `layout { }` on a child is the whole of the override mechanism — there is no
 * `display` property to fight with, and no container to reconfigure.
 *
 * Each of these writes **one slot per axis at most**, so an element still states its own size:
 *
 * ```kotlin
 * box { layout { inlineWrap(gap = 8.0); size(80.0, 24.0) } }
 * ```
 */

/**
 * Take a line of this element's own: below the previous sibling, or at the top of the parent.
 *
 * Vertical only — where the element starts horizontally, and how wide it is, is left open, so this
 * composes with [fillWidth], [alignLeft], [alignCenterX] or a container's defaults. [below] is the
 * combination worth having a name.
 */
fun LayoutScope.block(gap: Double = 0.0) {
    top { prev?.let { it.bottom + gap } ?: parent.top }
}

/**
 * [block], flush with the parent's left edge: a new line, starting at the start of it.
 *
 * Two slots, on two axes. This is the ordinary "next thing down the page" and the reason it reads
 * as one word rather than as `block(); alignLeft()`.
 */
fun LayoutScope.below(gap: Double = 0.0) {
    block(gap)
    alignLeft()
}

/** Sit after the previous sibling, or at the parent's left edge for the first. Horizontal only. */
fun LayoutScope.after(gap: Double = 0.0) {
    left { prev?.let { it.right + gap } ?: parent.left }
}

/**
 * Sit alongside the previous sibling, sharing its line: [after] plus a top edge that lines up.
 *
 * Tops rather than baselines, which is the honest simplification — `:layout` measures a text run's
 * box, not its baseline, so a baseline-aligned flow would have to invent one. For a row of things
 * of differing heights, prefer [alignCenterY] on the shorter ones.
 */
fun LayoutScope.inline(gap: Double = 0.0) {
    after(gap)
    top { prev?.top ?: parent.top }
}

/**
 * Sit alongside the previous sibling, wrapping to a new line at the parent's right edge.
 *
 * ```kotlin
 * column {                                     // any container; the children position themselves
 *     forEach(tags) { tag ->
 *         label(tag.name, font) { layout { inlineWrap(gap = 6.0); widthFromText(12.0) } }
 *     }
 * }
 * ```
 *
 * [lineGap] is the space between lines, defaulting to [gap] so the common case says one number.
 *
 * ### How it decides, and what that costs
 *
 * An element wraps when `previous.right + gap + myWidth` passes the parent's right edge. The new
 * line's top is the **lowest bottom edge on the line just ended**, not merely the previous
 * sibling's, so a tall item does not get overlapped by the row beneath it. Finding that line means
 * re-deciding the wrap for the siblings before this one, which is O(items on the line) per element
 * — fine for the tag lists and toolbars this is for, and not what you want for ten thousand items.
 *
 * ### The one constraint
 *
 * The element's **width must not come from its position**. This reads `width` to decide whether the
 * element fits, so `fillWidth()` — width from left and right — is a genuine cycle and is reported
 * as one. State a width: `fixedWidth`, `widthFromText`, `matchWidth`, `widthFraction`.
 *
 * For the same reason the parent's right edge has to be known independently of these children, so a
 * parent using [wrapChildren] to size itself horizontally will not do.
 */
fun LayoutScope.inlineWrap(gap: Double = 0.0, lineGap: Double = gap) {
    left {
        val sibs = siblings
        val i = index
        if (startsLine(sibs, i, gap, parent.right)) parent.left else sibs[i - 1].right + gap
    }
    top {
        val sibs = siblings
        val i = index
        val limit = parent.right
        when {
            i == 0 -> parent.top
            // Same line as the sibling before: line up with it, exactly as `inline` does.
            !startsLine(sibs, i, gap, limit) -> sibs[i - 1].top
            else -> {
                // Walk back over the line that just ended — its members are the siblings before
                // this one, up to and including whichever of them started a line — and clear the
                // lowest of them.
                var j = i - 1
                var bottom = sibs[j].bottom
                while (j > 0 && !startsLine(sibs, j, gap, limit)) {
                    j--
                    val b = sibs[j].bottom
                    if (b > bottom) bottom = b
                }
                bottom + lineGap
            }
        }
    }
}

/**
 * Whether the sibling at [i] is the first on its line: either the very first child, or one that no
 * longer fits beside the sibling before it.
 *
 * Deliberately recomputed from the geometry rather than remembered, and deliberately not asked of
 * the element's own resolved `left` — comparing a resolved position against the parent's edge would
 * be a float equality test, and this is an exact answer to the same question.
 */
private fun LayoutScope.startsLine(sibs: LayoutList, i: Int, gap: Double, limit: Double): Boolean =
    i == 0 || sibs[i - 1].right + gap + sibs[i].width > limit

// -------------------------------------------------------------------------------------
// Container forms. `:dsl` ships `stackDown` and `stackAcross`; these are the same idea — set
// `childDefaults`, which is public precisely so containers are not privileged (§3.3) — for the
// flow this module adds.
//
// Both stamp one slot per axis rather than the two-plus-two a `column` does, because a wrapped
// child's size cannot come from its container: see [inlineWrap]. Children state their own.
// -------------------------------------------------------------------------------------

/** Lay this element's children out in a line, each after the last. [inline] for every child. */
fun <S : Any> ElementScope<S>.stackInline(gap: Double = 0.0) {
    childDefaults = { inline(gap) }
}

/**
 * Lay this element's children out in lines that wrap at this element's right edge — a tag cloud, a
 * toolbar that reflows, a palette of swatches. [inlineWrap] for every child.
 */
fun <S : Any> ElementScope<S>.stackWrapped(gap: Double = 0.0, lineGap: Double = gap) {
    childDefaults = { inlineWrap(gap, lineGap) }
}
