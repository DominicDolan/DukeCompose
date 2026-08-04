package com.dubulduke.utils

import com.dubulduke.dsl.Layout
import com.dubulduke.dsl.LayoutScope

/**
 * Alignment: which edge of the parent — or of some other element — this one is pinned to.
 *
 * Every utility here writes **one** slot, which is what makes them pair with the sizing ones:
 * `alignRight(8.0)` says where the right edge is and nothing about how wide the element is, so
 * `alignRight(8.0); fixedWidth(120.0)` is a right-hand panel and `alignRight(8.0); alignLeft(8.0)`
 * is the same thing [fillWidth] says. Two alignments on the same axis are two slots and legal; a
 * third is the over-constraint error, naming all three.
 *
 * The exceptions are [alignCenter] and [centreIn], which write one slot per axis.
 */

// ── Against the parent ────────────────────────────────────────────────────────────────────────

/** Pin the left edge [inset] inside the parent's. */
fun LayoutScope.alignLeft(inset: Double = 0.0) {
    left { parent.left + inset }
}

/** Pin the right edge [inset] inside the parent's. */
fun LayoutScope.alignRight(inset: Double = 0.0) {
    right { parent.right - inset }
}

/** Pin the top edge [inset] inside the parent's. */
fun LayoutScope.alignTop(inset: Double = 0.0) {
    top { parent.top + inset }
}

/** Pin the bottom edge [inset] inside the parent's. */
fun LayoutScope.alignBottom(inset: Double = 0.0) {
    bottom { parent.bottom - inset }
}

/** Centre horizontally in the parent, [offset] to the right of centre. */
fun LayoutScope.alignCenterX(offset: Double = 0.0) {
    centerX { parent.centerX + offset }
}

/** Centre vertically in the parent, [offset] below centre. */
fun LayoutScope.alignCenterY(offset: Double = 0.0) {
    centerY { parent.centerY + offset }
}

/** Centre on both axes in the parent. Sizes are still yours to state. */
fun LayoutScope.alignCenter() {
    centerX { parent.centerX }
    centerY { parent.centerY }
}

// ── Against another element ───────────────────────────────────────────────────────────────────
//
// The target may be an ancestor, a child, a sibling in either direction, or anything `query(id)`
// finds. Nothing has to be *laid out* first — the evaluator resolves the order, which is what
// cross-element references buy — but it does have to have been **built**, because a utility takes a
// `Layout` and therefore resolves its target when it runs, at composition:
//
// ```kotlin
// box("a") { layout { alignLeft(query("b")) } }        // fails: b does not exist yet
// box("a") { layout { left { query("b").left } } }     // fine: the query runs at layout time
// ```
//
// A forward reference goes in the expression. That is the one thing the vocabulary cannot say for
// you, and it is a compile-time-shaped restriction rather than a layout one.

/** Line this element's left edge up with [other]'s, plus [offset]. */
fun LayoutScope.alignLeft(other: Layout, offset: Double = 0.0) {
    left { other.left + offset }
}

/** Line this element's right edge up with [other]'s, plus [offset]. */
fun LayoutScope.alignRight(other: Layout, offset: Double = 0.0) {
    right { other.right + offset }
}

/** Line this element's top edge up with [other]'s, plus [offset]. */
fun LayoutScope.alignTop(other: Layout, offset: Double = 0.0) {
    top { other.top + offset }
}

/** Line this element's bottom edge up with [other]'s, plus [offset]. */
fun LayoutScope.alignBottom(other: Layout, offset: Double = 0.0) {
    bottom { other.bottom + offset }
}

/** Share [other]'s horizontal centre. */
fun LayoutScope.alignCenterX(other: Layout, offset: Double = 0.0) {
    centerX { other.centerX + offset }
}

/** Share [other]'s vertical centre. */
fun LayoutScope.alignCenterY(other: Layout, offset: Double = 0.0) {
    centerY { other.centerY + offset }
}

/** Centre on [other] in both axes. */
fun LayoutScope.centreIn(other: Layout) {
    centerX { other.centerX }
    centerY { other.centerY }
}

/** Centre in the parent. The same as [alignCenter], under the name §2.3 gave it. */
fun LayoutScope.centreInParent() {
    centerX { parent.centerX }
    centerY { parent.centerY }
}

// ── Placed beside another element ─────────────────────────────────────────────────────────────
//
// These write the *far* slot on purpose — `after` sets `left`, `before` sets `right` — so that each
// pairs with a size or with the opposite alignment. `after(icon, 8.0); before(badge, 8.0)` is a
// label filling whatever is between two things that move.

/** Sit to the right of [other], [gap] clear of it. */
fun LayoutScope.after(other: Layout, gap: Double = 0.0) {
    left { other.right + gap }
}

/** Sit to the left of [other], [gap] clear of it. */
fun LayoutScope.before(other: Layout, gap: Double = 0.0) {
    right { other.left - gap }
}

/** Sit under [other], [gap] clear of it. */
fun LayoutScope.below(other: Layout, gap: Double = 0.0) {
    top { other.bottom + gap }
}

/** Sit over [other], [gap] clear of it. */
fun LayoutScope.above(other: Layout, gap: Double = 0.0) {
    bottom { other.top - gap }
}
