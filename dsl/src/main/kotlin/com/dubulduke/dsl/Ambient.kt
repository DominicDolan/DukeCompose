package com.dubulduke.dsl

import kotlin.jvm.JvmField

/**
 * A value supplied by an ancestor and read by a descendant. `dsl-and-engine.md` §4.2.
 *
 * ```kotlin
 * val Theme = ambientOf("theme") { DarkTheme }
 *
 * panel {
 *     provide(Theme to LightTheme) { … }   // this subtree, and nothing else
 * }
 *
 * label(…) { style { textColor.set(ambient(Theme).text) } }
 * ```
 *
 * ### Why this rather than a selector
 *
 * This is the mechanism that replaces three separate features, and that is the argument for it.
 * **Theme** is an ambient. **Contextual styling** — "a card inside a card is outlined rather than
 * elevated" — is an ambient. **Inheritance** of font and text colour is an ambient. CSS needs a
 * cascade, a specificity algorithm and a per-property inherit/no-inherit table to cover the same
 * ground; here it is one concept with no matching step at all.
 *
 * It is also strictly more expressive than an ancestor selector, because it carries a **value**
 * rather than a boolean: a third-level card can differ from a second-level one, which `.card .card &`
 * cannot say without enumerating depths.
 *
 * ### Composition-time, and therefore free
 *
 * An ambient is resolved when the element is **built**, not when it is drawn, and the result is a
 * plain value the styler closes over. There is no graph node, no dependency edge and no per-frame
 * cost, because the thing it depends on — where this element sits in the tree — cannot change:
 * nothing in this design reparents an element.
 *
 * The consequence worth stating plainly: **`provide` does not re-run anything.** Changing what a
 * theme ambient holds after composition has no effect, because the descendants already read it. A
 * theme that changes at runtime should hold a [Signal] or [RefSignal] and be read *inside* a styler
 * — the ambient carries the signal, the styler reads its value, and the usual invalidation applies.
 * That is the same distinction as `text("…")` versus `text { … }`, applied to styling.
 */
class Ambient<T> internal constructor(
    internal val default: () -> T,
    @JvmField val name: String?,
) {
    override fun toString(): String = "Ambient(${name ?: "unnamed"})"
}

/**
 * Declare an ambient, with the value descendants see when no ancestor provided one.
 *
 * The default is a lambda rather than a value so that declaring an ambient costs nothing until it
 * is read, which matters when the default is a whole theme object.
 */
fun <T> ambientOf(name: String? = null, default: () -> T): Ambient<T> = Ambient(default, name)

/**
 * One link of the ambient environment: an immutable cons list, innermost first.
 *
 * A list rather than a map, because the environment is a handful of entries deep in any real UI and
 * an immutable list makes [ElementScope.provide] free — pushing a scope allocates one link and
 * shares the whole tail, so nothing is copied and nothing has to be restored on the way out.
 */
internal class Ambients(
    @JvmField val key: Ambient<*>,
    @JvmField val value: Any?,
    @JvmField val next: Ambients?,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> lookup(from: Ambients?, key: Ambient<T>): T {
            var link = from
            while (link != null) {
                if (link.key === key) return link.value as T
                link = link.next
            }
            return key.default()
        }
    }
}
