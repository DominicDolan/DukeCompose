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

/** A [label] whose text is computed. See [ElementScope.text]. */
fun <S : Any> ElementScope<S>.label(
    text: () -> String,
    font: FontRef,
    id: String? = null,
    body: ElementScope<S>.() -> Unit = {},
): Element = child(id) {
    text(font, text)
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
    stackDown(gap)
    body()
}

/** Stack children horizontally, each after the last. The mirror of [column]. */
fun <S : Any> ElementScope<S>.row(
    gap: Double = 0.0,
    id: String? = null,
    body: ElementScope<S>.() -> Unit,
): Element = child(id) {
    stackAcross(gap)
    body()
}

// -------------------------------------------------------------------------------------
// Container behaviour, applied to *this* element rather than to a new child.
//
// [column] and [row] can only ever make a child a container, which leaves an element you did
// not create — a `forEach` item, or the root — with no way to become one. Splitting the
// defaults out costs nothing and closes that gap: `column(gap) { … }` is now exactly
// `child { stackDown(gap); … }`.
// -------------------------------------------------------------------------------------

/**
 * Lay *this* element's children out vertically, each below the last. [column]'s half.
 *
 * Call it before adding children: defaults are read when a child is created, so a child added
 * first does not get them.
 */
fun <S : Any> ElementScope<S>.stackDown(gap: Double = 0.0) {
    childDefaults = {
        left { parent.left }
        right { parent.right }
        top { prev?.let { it.bottom + gap } ?: parent.top }
    }
}

/** Lay *this* element's children out horizontally, each after the last. [row]'s half. */
fun <S : Any> ElementScope<S>.stackAcross(gap: Double = 0.0) {
    childDefaults = {
        top { parent.top }
        bottom { parent.bottom }
        left { prev?.let { it.right + gap } ?: parent.left }
    }
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
// The layout utilities that used to sit here — `fillWidth`, `below`, `centreIn` and the rest of
// §2.3 — are in `:utils` now, along with the flex authoring surface. Nothing about them needed
// to be here: every one is an extension on [LayoutScope] writing public slots, which is exactly
// §2.3's claim about them, and keeping them downstream makes that claim checkable rather than
// merely stated. `:dsl` is the mechanism; `:utils` is the vocabulary.
// -------------------------------------------------------------------------------------
