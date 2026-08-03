package com.dubulduke.dsl

import com.dubulduke.layout.Direction
import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementBuilder
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.FontRef
import com.dubulduke.layout.interactive
import com.dubulduke.layout.onClick
import com.dubulduke.layout.onDrag
import com.dubulduke.layout.onScroll
import com.dubulduke.layout.style
import com.dubulduke.reactivity.Graph

/**
 * Makes the application's style objects. `dsl-and-engine.md` §4.5 — there are no framework
 * default styles; the application supplies this, exactly as iteration A's `createStyle()` did.
 */
fun interface StyleFactory<S : Any> {
    fun create(): S
}

/**
 * The authoring surface for one element: its own configuration, and its children.
 *
 * ### Why `S` is here and not on `:layout`
 *
 * The style type is a **type parameter supplied by the application** (§4.5), because platforms
 * differ in capability and a fixed framework style class must take either the union — properties
 * that silently no-op — or the intersection, with capabilities unreachable. Threading it through
 * the scope types is what buys **compile-time capability checking**: a component can declare
 *
 * ```kotlin
 * fun <S> ElementScope<S>.card(body: ElementScope<S>.() -> Unit) where S : HasBackground, S : HasElevation
 * ```
 *
 * and it will not compile against a style type lacking either, rather than silently no-opping at
 * runtime. That is also what lets a host define a base style representing its own capabilities and
 * an application extend it further — a `glowLevel` on top of a Mechanica style, say — with
 * components in between constrained to exactly what they use.
 *
 * `:layout` stays non-generic underneath, holding the style as an opaque payload, because nothing
 * there may read it: a style object cannot have slot identity, so anything layout read from one
 * would be invisible to invalidation (§4.5's hard rule). The parameter is an *authoring* concern,
 * so it lives in the authoring layer.
 *
 * ### Children are statements
 *
 * There is no `children { }` block. Children *are* the element-factory calls, as in kotlinx.html
 * and Compose; ambiguity does not arise because self-configuration is always `layout` / `style` /
 * `id` / `classes` and children are always factories (§3.1).
 */
@DukeDsl
class ElementScope<S : Any> internal constructor(
    @JvmField internal val builder: ElementBuilder,
    @JvmField internal val styles: StyleFactory<S>,
    /**
     * What this element's *container* wants for it. Applied before the element's own `layout { }`,
     * so overriding one slot is ordinary last-write-wins (§2.4).
     */
    private val inherited: (LayoutScope.() -> Unit)?,
    /**
     * An axis this element does not own — set only for a flex item, whose main axis the row writes.
     * Reserved *before* [inherited] runs, so a container default aimed at that axis is dropped
     * rather than fighting the row.
     */
    private val reserved: Direction? = null,
) {
    @JvmField
    val element: Element = builder.element

    val tree: ElementTree get() = builder.tree
    val graph: Graph get() = builder.tree.graph

    /** This element, for utilities and for reading its own geometry outside a layout block. */
    val self: Layout get() = Layout(element)

    private var layoutScope: LayoutScope? = null

    /** Defaults this scope stamps on *its* children. Null unless a container scope sets one. */
    internal var childDefaults: (LayoutScope.() -> Unit)? = null

    private fun scope(): LayoutScope =
        layoutScope ?: LayoutScope(graph, tree, element).also {
            layoutScope = it
            if (reserved != null) it.reserve(reserved == Direction.HORIZONTAL)
            inherited?.let { d -> it.applyDefaults(d) }
        }

    /** The scope, forced into existence. For a flex item, whose size expression needs it. */
    internal fun layoutScope(): LayoutScope = scope()

    /**
     * Position and size this element.
     *
     * May be called more than once — a helper and a call site can each contribute — and the later
     * assignment wins per slot. That is what makes `dsl-and-engine.md` §2.3's utilities composable:
     * `layout { fillWidth(8.0); below(4.0); fixedHeight(40.0) }` is three helpers writing five
     * different slots, and two of them conflict only if they assign the same one.
     */
    fun layout(block: LayoutScope.() -> Unit) {
        scope().block()
    }

    /** Give this element measurable text. Font and text are framework-owned, never style (§4.5). */
    fun text(text: String, font: FontRef) {
        builder.text(text, font)
    }

    /**
     * Text computed from the graph — a string that can change without recomposition.
     *
     * The distinction is invisible while composition runs every frame and decisive once it does
     * not (`dsl-and-engine.md` §5.5): `text("Score: ${'$'}score", font)` freezes the string at
     * setup, and inside a [forEach] item it freezes at whatever the item held when it first
     * appeared. Costs nothing when the inputs are still — the node compares by value, so an
     * interpolation rebuilding an equal string re-triggers no measurement.
     */
    fun text(font: FontRef, text: () -> String) {
        builder.text(font) { text() }
    }

    /**
     * Fill in this element's style. The object comes from the application's [StyleFactory], and
     * the block is a tracked expression — reading hover or a signal inside it invalidates it
     * correctly, and nothing else.
     */
    fun style(block: S.() -> Unit) {
        builder.style(styles.create(), block)
    }

    /** Tag this element for the JSON dump. Not a selector — see §3.4. */
    fun classes(vararg names: String) {
        builder.classes(*names)
    }

    /**
     * Name this element for the backend. `"box"` and `"text"` are drawn by the standard paths;
     * anything else reaches `DukeBackend.drawCustom`, which is the seam for a custom-shaded widget.
     */
    fun kind(kind: String) {
        builder.kind(kind)
    }

    /** Make this element hit-testable, giving it hover and press state. */
    fun interactive() {
        builder.interactive()
    }

    /** Register a click handler. Retained rather than polled, so it works under compose-once. */
    fun onClick(handler: () -> Unit) {
        builder.onClick(handler)
    }

    /**
     * Register a wheel handler. Bubbles — see [com.dubulduke.layout.Element.onScroll].
     *
     * Unlike [onClick] this does not imply [interactive], because the handler is normally reached
     * from a descendant rather than from this element itself.
     */
    fun onScroll(handler: (Double) -> Unit) {
        builder.onScroll(handler)
    }

    /**
     * Register a drag handler, capturing the pointer while the button is held.
     *
     * The arguments are the pointer position in layout coordinates — the same space `left { }` and
     * `top { }` work in, so a handler usually reads its own geometry and writes a signal:
     *
     * ```kotlin
     * onDrag { x, _ -> value.value = ((x - self.left) / self.width).coerceIn(0.0, 1.0) }
     * ```
     */
    fun onDrag(handler: (x: Double, y: Double) -> Unit) {
        builder.onDrag(handler)
    }

    /**
     * Confine this element's children to its box when drawn and hit-tested.
     *
     * Affects no slot: a clipped child lands exactly where its expressions put it and is simply
     * not shown. For the usual case — clipping plus an offset — use [scrollArea].
     */
    fun clip() {
        builder.clip()
    }

    /**
     * Add a child.
     *
     * The general form. `box`, `text`, `column`, `row` and the rest are all this with a
     * container's defaults attached.
     */
    fun child(id: String? = null, body: ElementScope<S>.() -> Unit): Element =
        builder.child(id) {
            val scope = ElementScope(this, styles, this@ElementScope.childDefaults)
            scope.body()
            scope.finish()
        }

    internal fun finish() {
        // `h { }` builds the axis from whatever the block registered and returns it built, so the
        // registration has to happen *inside* the block — capturing the builder for later would
        // hand back an axis with nothing on it.
        val s = scope()
        // A reserved axis was already built by the flex row, before this element's block ran.
        // Building it again would hit `:layout`'s "already has an axis" check.
        if (!s.isReserved(horizontal = true)) builder.h { s.commitAxis(horizontal = true, axis = this) }
        if (s.isReserved(horizontal = false)) return
        // `startIsLow = false` on the vertical axis is what makes `top` the origin rather than the
        // end — i.e. y-down, so `top { 0.0 }` is the top of the screen and a list stacks downwards
        // by adding. `:layout` supports either orientation; the DSL picks one and states it, since
        // an authoring surface where `top` sometimes means the bottom would be indefensible.
        builder.v(startIsLow = false) { s.commitAxis(horizontal = false, axis = this) }
    }
}
