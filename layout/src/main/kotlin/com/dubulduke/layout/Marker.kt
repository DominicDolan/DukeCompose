package com.dubulduke.layout

import kotlin.jvm.JvmField

/**
 * A typed tag on an element, for the ancestor queries of `dsl-and-engine.md` §4.2.
 *
 * ```kotlin
 * val Toolbar = Marker("toolbar")
 *
 * toolbar { marker(Toolbar); … }
 * button  { val compact = hasAncestor(Toolbar) }
 * ```
 *
 * A `val` rather than a string, and that is the whole point: `hasAncestor(Toolbar)` survives a
 * rename and cannot be typo'd, where `".toolbar &"` does neither. Same reach as a CSS ancestor
 * selector, with no parser and no specificity rules.
 *
 * Distinct from [Element.classes], which are carried for the JSON dump and explicitly *not*
 * selectors (§3.4). Conflating them would make a debugging aid load-bearing.
 */
class Marker(@JvmField val name: String) {
    override fun toString(): String = "Marker($name)"
}

/** Tag this element, so descendants can find it with [hasAncestor]. */
fun ElementBuilder.marker(marker: Marker) {
    val existing = element.markers
    if (existing == null) element.markers = arrayListOf(marker)
    else if (marker !in existing) existing += marker
}

/** Whether this element itself carries [marker]. */
fun Element.has(marker: Marker): Boolean = markers?.contains(marker) == true

/**
 * Whether any strict ancestor carries [marker].
 *
 * **Resolved once, when called, and untracked** — there is no graph node behind this and nothing to
 * invalidate. That is sound rather than a shortcut: an element's ancestry is fixed for its lifetime,
 * because nothing in this design reparents. A reconciled list rebuilds items under the same
 * container; it never moves one somewhere else.
 *
 * So the intended use is at composition time, hoisted out of the styler:
 *
 * ```kotlin
 * val compact = hasAncestor(Toolbar)          // once, when the element is built
 * style { padding = if (compact) 4.0 else 8.0 }
 * ```
 *
 * Calling it inside a styler is not wrong, merely wasteful — it walks the same parents to the same
 * answer on every restyle.
 */
fun Element.hasAncestor(marker: Marker): Boolean {
    var e = parent
    while (e != null) {
        if (e.has(marker)) return true
        e = e.parent
    }
    return false
}

/**
 * How many strict ancestors carry [marker] — the depth query of §4.2.
 *
 * Where [hasAncestor] answers "is this in a card", this answers "how deeply", which is what lets a
 * third-level card differ from a second. Prefer an ambient when the ancestor is yours to change:
 * this exists for the retrofit case, where the container is third-party or should not need to know
 * that anything is counting it.
 */
fun Element.ancestorCount(marker: Marker): Int {
    var count = 0
    var e = parent
    while (e != null) {
        if (e.has(marker)) count++
        e = e.parent
    }
    return count
}

/** The nearest strict ancestor carrying [marker], or `null`. */
fun Element.closestAncestor(marker: Marker): Element? {
    var e = parent
    while (e != null) {
        if (e.has(marker)) return e
        e = e.parent
    }
    return null
}
