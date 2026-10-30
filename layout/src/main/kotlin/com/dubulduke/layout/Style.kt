package com.dubulduke.layout

import kotlin.jvm.JvmField

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RefEquality
import com.dubulduke.reactivity.Slot
import com.dubulduke.reactivity.nodeName

/**
 * An element's style: an application-owned object, plus the tracked block that fills it in.
 *
 * `dsl-and-engine.md` §4.5 — the style type belongs to the application, not the framework,
 * because platforms differ in capability and a fixed framework style class must take either the
 * union (properties that silently no-op) or the intersection (capabilities unreachable). Nothing
 * here inspects the payload; it is carried and handed to a backend.
 *
 * **The whole styler is one node**, deliberately, against the slot-per-property granularity that
 * layout requires. §4.5's argument: fine granularity is mandatory for layout because coarse
 * granularity there produces *false cycles*, which is a correctness failure. Style has no cycles,
 * because **style is a sink — nothing reads it**. Coarse granularity therefore costs only
 * redundant work, and the work is a few field writes into an object that is never reallocated.
 *
 * Its *inputs* are tracked normally, which is the part that matters: `style { color = if
 * (hovering) a else b }` records an edge on the hover node and invalidates correctly (§7).
 *
 * The node is [RefEquality.ALWAYS] because the styler mutates [target] in place and returns the
 * same instance, so neither `===` nor `==` could ever see a change. That is exactly the case
 * `ALWAYS` documents itself as existing for, and the verdict costs nothing because a sink has no
 * subscribers to propagate to.
 */
class StyleNode internal constructor(
    /** The application's style object, reused across every re-run. Opaque to the framework. */
    @JvmField internal val target: Any,
    /** The `ALWAYS` ref node whose body runs the styler. */
    val slot: Slot,
)

/**
 * Attach a style to this element: [target] is the application's own style object, [styler] fills
 * it in.
 *
 * Generic per call rather than a type parameter on [ElementTree] / [ElementBuilder] / [FlexScope].
 * `dsl-and-engine.md` §4.5 proposes threading `S` through every scope type, and lists the
 * propagation as its cost; that buys compile-time capability checking (`where S : HasBlur`), which
 * only pays once there is more than one backend to be wrong about. Storage is opaque either way,
 * so promoting `S` to the scope types later changes signatures and not the graph. **Until then a
 * tree is not checked for using one style type throughout**, and mixing two produces a
 * `ClassCastException` at draw rather than a compile error.
 *
 * Applications collapse the verbosity with one alias of their own:
 *
 * ```kotlin
 * fun ElementBuilder.style(styler: GameStyle.() -> Unit) = style(GameStyle(), styler)
 * ```
 *
 * Nothing runs here. Like every other expression in this module the block is lazy, so a styler may
 * read hover state, viewport size or a sibling's geometry that does not exist yet.
 */
fun <S : Any> ElementBuilder.style(target: S, styler: S.() -> Unit): S {
    check(element.styleNode == null) { "$element already has a style" }
    element.styleNode = StyleNode(
        target = target,
        slot = tree.graph.deriveRef(nodeName { "${element.path}.style" }, RefEquality.ALWAYS) {
            target.styler()
            target
        },
    )
    return target
}

/**
 * Resolve this element's style, re-running the styler only if one of its inputs changed.
 *
 * Called from the draw walk, which is outside the graph by construction, so this records no
 * dependency — it only pulls. `dsl-and-engine.md` §4.4: colours and the rest are "resolved lazily
 * using the same dirty mechanism, but not participating in layout dependency edges".
 */
fun Graph.styleOf(e: Element): Any =
    readRef(checkNotNull(e.styleNode) { "$e has no style" }.slot)!!

/** This element's style, or `null` if it was composed without one. */
fun Graph.styleOrNullOf(e: Element): Any? = e.styleNode?.let { readRef(it.slot) }
