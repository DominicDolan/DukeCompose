package com.dubulduke.utils

import com.dubulduke.dsl.Layout
import com.dubulduke.dsl.LayoutScope

/**
 * Sizing utilities: how big an element is, and how much of its parent it takes.
 *
 * `dsl-and-engine.md` §2.3 is the argument for this whole module. A utility here writes **slots**,
 * so two of them conflict only if they name the same one, and that is caught at composition rather
 * than resolved by write order. `layout { fillWidth(8.0); block(4.0); fixedHeight(40.0) }` is three
 * utilities filling five of the eight slots; the two per axis that survive are the layout.
 *
 * ### What a utility costs
 *
 * Each one writes an expression per slot, and `§2.2`'s allocation note applies unchanged: a lambda
 * capturing nothing — `fill()`'s `{ parent.left }` — is a **singleton**, one object for the whole
 * program however many elements use it. One that captures a parameter — `fill(8.0)` — allocates
 * once per composition, not per frame. So prefer the argument-less form where it says what you
 * mean, and do not think twice about the other.
 */

// ── Filling the parent ────────────────────────────────────────────────────────────────────────

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

/** Fill the parent entirely, inset on all four sides. All four slots, so nothing else may. */
fun LayoutScope.fill(inset: Double = 0.0) {
    fillWidth(inset)
    fillHeight(inset)
}

/**
 * Fill the parent, inset by [horizontal] on the left and right and [vertical] on the top and
 * bottom. The two-value form of [fill] — `inset(12.0, 8.0)` is a padded panel.
 */
fun LayoutScope.inset(horizontal: Double, vertical: Double) {
    fillWidth(horizontal)
    fillHeight(vertical)
}

/**
 * Fill the parent, inset differently on each side. CSS order: top, right, bottom, left.
 *
 * The parameters are read into locals first because a parameter called `left` would otherwise
 * shadow the slot-writing `left { }` — the price of naming them after the edges they inset, which
 * is worth paying at four arguments.
 */
fun LayoutScope.inset(top: Double, right: Double, bottom: Double, left: Double) {
    val t = top
    val r = right
    val b = bottom
    val l = left
    this.left { parent.left + l }
    this.right { parent.right - r }
    this.top { parent.top + t }
    this.bottom { parent.bottom - b }
}

/** The same as [fill]. Present because "inset by 8" is how the padded case is usually spoken. */
fun LayoutScope.inset(all: Double = 0.0) = fill(all)

/** Take a fraction of the parent's width — `widthFraction(0.5)` is a half-width column. */
fun LayoutScope.widthFraction(fraction: Double) {
    width { parent.width * fraction }
}

/** Take a fraction of the parent's height. */
fun LayoutScope.heightFraction(fraction: Double) {
    height { parent.height * fraction }
}

// ── Stating a size outright ───────────────────────────────────────────────────────────────────

/** A width in layout units. Named `fixedWidth` rather than `width` so it never reads as a slot. */
fun LayoutScope.fixedWidth(w: Double) {
    width { w }
}

/** A height in layout units. */
fun LayoutScope.fixedHeight(h: Double) {
    height { h }
}

/** Both sizes at once. */
fun LayoutScope.size(w: Double, h: Double) {
    width { w }
    height { h }
}

/** A square of a given side. */
fun LayoutScope.size(side: Double) {
    width { side }
    height { side }
}

/** As tall as it is wide. The clearest case for slot-level expressions: one axis reads the other. */
fun LayoutScope.square() {
    height { width }
}

/**
 * Height from width at a fixed [ratio] of width to height — `aspect(16.0 / 9.0)`.
 *
 * Writes the *height* slot only, so the width still has to come from somewhere: `fillWidth()` plus
 * `aspect(…)` is the usual pair, and gives a box that keeps its shape as its parent resizes.
 */
fun LayoutScope.aspect(ratio: Double) {
    require(ratio > 0.0) { "aspect ratio must be positive, was $ratio" }
    height { width / ratio }
}

// ── Borrowing a size ──────────────────────────────────────────────────────────────────────────

/** The same width as [other], plus [delta]. */
fun LayoutScope.matchWidth(other: Layout, delta: Double = 0.0) {
    width { other.width + delta }
}

/** The same height as [other], plus [delta]. */
fun LayoutScope.matchHeight(other: Layout, delta: Double = 0.0) {
    height { other.height + delta }
}

/** The same size as [other] on both axes. Its position is still yours to state. */
fun LayoutScope.matchSize(other: Layout, delta: Double = 0.0) {
    matchWidth(other, delta)
    matchHeight(other, delta)
}

/**
 * Exactly [other]'s box, grown by [outset] on every side.
 *
 * All four slots. This is the utility for an overlay, a focus ring or a drop shadow: the element
 * tracks another's geometry entirely, wherever that other one ends up.
 */
fun LayoutScope.matchBox(other: Layout, outset: Double = 0.0) {
    left { other.left - outset }
    right { other.right + outset }
    top { other.top - outset }
    bottom { other.bottom + outset }
}

/** Stretch horizontally from [from]'s right edge to [to]'s left edge, [gap] clear of each. */
fun LayoutScope.spanBetweenX(from: Layout, to: Layout, gap: Double = 0.0) {
    left { from.right + gap }
    right { to.left - gap }
}

/** Stretch vertically from [from]'s bottom edge to [to]'s top edge, [gap] clear of each. */
fun LayoutScope.spanBetweenY(from: Layout, to: Layout, gap: Double = 0.0) {
    top { from.bottom + gap }
    bottom { to.top - gap }
}

// ── Sizing from content ───────────────────────────────────────────────────────────────────────

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

/** [wrapChildren] on the horizontal axis alone, for a row that is as wide as its content. */
fun LayoutScope.wrapChildrenWidth(pad: Double = 0.0) {
    width { if (children.isEmpty) 0.0 else children.maxOf { it.right } - left + pad }
}

/** [wrapChildren] on the vertical axis alone — the common one, for a panel as tall as its list. */
fun LayoutScope.wrapChildrenHeight(pad: Double = 0.0) {
    height { if (children.isEmpty) 0.0 else children.maxOf { it.bottom } - top + pad }
}

/**
 * Height from this element's own wrapped text, at whatever width it ends up with.
 *
 * The §8.3 dependency, and the reason it is worth a name: vertical size from horizontal size, on a
 * different axis and in the opposite direction to the width the element was handed.
 */
fun LayoutScope.heightFromText() {
    height { self.contentHeight }
}

/** Width from this element's own text, unwrapped — a label that refuses to break. */
fun LayoutScope.widthFromText(pad: Double = 0.0) {
    width { self.contentWidth + pad }
}

/**
 * Both axes from this element's own text: as wide as the text is and as tall as it needs at that
 * width. [pad] widens the box, and the extra width is available to the wrap.
 */
fun LayoutScope.sizeFromText(pad: Double = 0.0) {
    widthFromText(pad)
    heightFromText()
}
