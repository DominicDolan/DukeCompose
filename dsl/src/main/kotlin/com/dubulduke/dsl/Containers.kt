package com.dubulduke.dsl

import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.FontRef

/**
 * Element factories and container scopes. `dsl-and-engine.md` §3.1, §3.3.
 *
 * A container is not a new *type* of scope so much as an [ElementScope] with `childDefaults` set.
 * §2.4's insight is that defaults and overrides target **slots** rather than whole layouts, so a
 * column stamping `left`, `right` and `top` composes with a child that sets only `height` — and
 * "last write wins within a single slot" needs no strength system to be unambiguous.
 */

/** Build the root of a tree. Composition, phase 1. */
fun <S : Any> ElementTree.compose(
    styles: StyleFactory<S>,
    id: String? = "ui",
    body: ElementScope<S>.() -> Unit,
): Element = root(id) {
    val scope = ElementScope(this, styles, null)
    scope.body()
    scope.finish()
}

/** A plain child with no container defaults beyond whatever this scope stamps. */
fun <S : Any> ElementScope<S>.box(id: String? = null, body: ElementScope<S>.() -> Unit): Element =
    child(id, body)

/**
 * A text element, sized to its own content by default.
 *
 * The defaults are the reason this exists rather than being `box { text(...) }`: a label almost
 * always wants its height from its wrapped text, and that is the §8.3 dependency — vertical size
 * from horizontal size, on a different axis and in the opposite direction to the width it is given.
 */
fun <S : Any> ElementScope<S>.label(
    text: String,
    font: FontRef,
    id: String? = null,
    body: ElementScope<S>.() -> Unit = {},
): Element = child(id) {
    text(text, font)
    layout { height { self.contentHeight } }
    body()
}

/**
 * Stack children vertically, each below the last.
 *
 * A child that sets only `height` keeps the column's `left`, `right` and `top`. Setting `top`
 * itself opts out of the stacking for that child alone.
 */
fun <S : Any> ElementScope<S>.column(
    gap: Double = 0.0,
    id: String? = null,
    body: ElementScope<S>.() -> Unit,
): Element = child(id) {
    childDefaults = {
        left { parent.left }
        right { parent.right }
        top { prev?.let { it.bottom + gap } ?: parent.top }
    }
    body()
}

/** Stack children horizontally, each after the last. The mirror of [column]. */
fun <S : Any> ElementScope<S>.row(
    gap: Double = 0.0,
    id: String? = null,
    body: ElementScope<S>.() -> Unit,
): Element = child(id) {
    childDefaults = {
        top { parent.top }
        bottom { parent.bottom }
        left { prev?.let { it.right + gap } ?: parent.left }
    }
    body()
}

/**
 * An empty element that takes up space. `spacer()` in a [row] or [column] pushes what follows.
 *
 * Cheap and structural: two constant slots, no style, no content, not hit-testable.
 */
fun <S : Any> ElementScope<S>.spacer(size: Double, id: String? = null): Element = child(id) {
    layout {
        width { size }
        height { size }
    }
}

// -------------------------------------------------------------------------------------
// Utilities — §2.3
//
// These are the payoff of slot-level assignment. Under iteration C's builder chain
// (`.alignTop(1.0).width(5.0)`) none of this could be factored into a reusable helper at all,
// because the chain's `edit { p, s -> }` was atomic. Two utilities conflict only if they assign
// the same slot, which is mechanically detectable rather than a matter of ordering luck.
// -------------------------------------------------------------------------------------

/** Span the parent's width, inset by [inset] on each side. */
fun LayoutScope.fillWidth(inset: Double = 0.0) {
    left { parent.left + inset }
    right { parent.right - inset }
}

/** Span the parent's height, inset by [inset] top and bottom. */
fun LayoutScope.fillHeight(inset: Double = 0.0) {
    top { parent.top + inset }
    bottom { parent.bottom - inset }
}

/** Fill the parent entirely, inset on all four sides. */
fun LayoutScope.fill(inset: Double = 0.0) {
    fillWidth(inset)
    fillHeight(inset)
}

/** Sit below the previous sibling, or at the top of the parent for the first. */
fun LayoutScope.below(gap: Double = 0.0) {
    top { prev?.let { it.bottom + gap } ?: parent.top }
}

/** Sit after the previous sibling, or at the parent's left edge for the first. */
fun LayoutScope.after(gap: Double = 0.0) {
    left { prev?.let { it.right + gap } ?: parent.left }
}

fun LayoutScope.size(w: Double, h: Double) {
    width { w }
    height { h }
}

/** As tall as it is wide. The clearest case for slot-level expressions: one axis reads the other. */
fun LayoutScope.square() {
    height { width }
}

fun LayoutScope.centreIn(other: Layout) {
    centerX { other.centerX }
    centerY { other.centerY }
}

fun LayoutScope.centreInParent() {
    centerX { parent.centerX }
    centerY { parent.centerY }
}

/**
 * Shrink-wrap this element around its children: the box that contains all of them, plus [pad].
 *
 * Reads the child *list*, not merely the children's geometry, so adding one invalidates this
 * correctly — the whole point of `layout-design.md` §8.7.
 */
fun LayoutScope.wrapChildren(pad: Double = 0.0) {
    width { if (children.isEmpty) 0.0 else children.maxOf { it.right } - left + pad }
    height { if (children.isEmpty) 0.0 else children.maxOf { it.bottom } - top + pad }
}

/** Height from this element's own wrapped text, at whatever width it ends up with. */
fun LayoutScope.heightFromText() {
    height { self.contentHeight }
}
