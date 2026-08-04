package com.dubulduke.dsl

import com.dubulduke.layout.Direction
import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementBuilder
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.FocusDirection
import com.dubulduke.layout.FontRef
import com.dubulduke.layout.Marker
import com.dubulduke.layout.ancestorCount
import com.dubulduke.layout.hasAncestor
import com.dubulduke.layout.marker
import com.dubulduke.layout.focusNeighbour
import com.dubulduke.layout.focusable
import com.dubulduke.layout.interactive
import com.dubulduke.layout.onClick
import com.dubulduke.layout.onActivate
import com.dubulduke.layout.onDrag
import com.dubulduke.layout.onReveal
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
    /**
     * The `:layout` element under construction.
     *
     * Public for the same reason [childDefaults] is: a container mechanism built on `:layout`'s
     * primitives — `flexRow` is the shipped example, and it lives in `:utils` rather than here —
     * has to reach the builder to install one. See [buildChild] for the other half.
     */
    @JvmField val builder: ElementBuilder,
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
    /**
     * The ambient environment in force here. Passed down on [child] and pushed by [provide].
     *
     * Carried on the scope rather than held in a mutable stack somewhere central, because that is
     * what makes it survivable across reconciliation: a `forEach` region captures the environment
     * that was in force where it was declared, and hands it to items built long afterwards.
     */
    @JvmField internal val ambients: Ambients? = null,
) {
    @JvmField
    val element: Element = builder.element

    val tree: ElementTree get() = builder.tree
    val graph: Graph get() = builder.tree.graph

    /** This element, for utilities and for reading its own geometry outside a layout block. */
    val self: Layout get() = Layout(element)

    internal var layoutScope: LayoutScope? = null

    /**
     * Defaults this scope stamps on *its* children. Null unless a container scope sets one.
     *
     * Public because a container is nothing more than this being set — [stackDown] and
     * [stackAcross] are two lines each — and an application that wants a container the DSL does not
     * ship (V-Tess stacks its menus down a *slanted* edge) has no other way to write one. Keeping
     * it internal would have made the two shipped containers privileged, which §3.3's whole point
     * is that they are not.
     *
     * Set it *before* adding children: defaults are read when a child is created, so a child added
     * first does not get them.
     */
    var childDefaults: (LayoutScope.() -> Unit)? = null

    private fun scope(): LayoutScope =
        layoutScope ?: LayoutScope(graph, tree, element).also {
            layoutScope = it
            if (reserved != null) it.reserve(reserved == Direction.HORIZONTAL)
            inherited?.let { d -> it.applyDefaults(d) }
        }

    /**
     * The scope, forced into existence. For a flex item, whose size expression needs it.
     *
     * Public because a container mechanism outside this module needs it for two things: to hold an
     * item's scope while the item is still being built, so a size expression evaluated later has
     * somewhere to resolve against, and to make a reservation ([LayoutScope.reserveSize]) before
     * the element's own block runs.
     */
    fun layoutScope(): LayoutScope = scope()

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
     * Make this element a tab stop, and give it focus state readable with `graph.isFocused(element)`.
     *
     * Opt-in, and separate from [interactive]: a scroll area answers the pointer and must never be
     * a tab stop, while a control is usually both.
     */
    fun focusable() {
        builder.focusable()
    }

    /**
     * What activating this element does from the keyboard. Implies [focusable].
     *
     * Unset, activation falls through to [onClick] — so a focusable button needs nothing extra to
     * work from the keyboard.
     */
    fun onActivate(handler: () -> Unit) {
        builder.onActivate(handler)
    }

    /**
     * Override where directional navigation goes from this element. Implies [focusable].
     *
     * Resolved when navigation happens, not when it is registered, so it can name an element that
     * does not exist yet — the same rule as every other cross-element reference here. Returning
     * `null` means "nothing that way" rather than "fall back on geometry".
     */
    fun focusNeighbour(direction: FocusDirection, target: () -> Element?) {
        builder.focusNeighbour(direction, target)
    }

    /**
     * Let this element bring a descendant into view — implemented by scroll containers.
     *
     * Focus walks *up* from the newly focused element asking each ancestor, because a control must
     * not have to know it is inside a scroll area or it could not be reused outside one.
     */
    fun onReveal(handler: (Element) -> Unit) {
        builder.onReveal(handler)
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
            val scope = ElementScope(
                this, styles, this@ElementScope.childDefaults,
                ambients = this@ElementScope.ambients,
            )
            scope.body()
            scope.finish()
        }

    // ── Ambients — `dsl-and-engine.md` §4.2 ───────────────────────────────────────────────────

    /** Read an ambient: the nearest ancestor's [provide], or the ambient's default. */
    fun <T> ambient(key: Ambient<T>): T = Ambients.lookup(ambients, key)

    /**
     * Supply an ambient to everything built inside [body].
     *
     * Scoped to the block rather than to the element, so one element can provide different values
     * to different parts of its own subtree, and so leaving the block needs no restore step — the
     * environment is an immutable list and the outer scope still holds its own head.
     */
    fun <T> provide(entry: Pair<Ambient<T>, T>, body: ElementScope<S>.() -> Unit) {
        val inner = ElementScope(
            builder, styles, inherited, reserved,
            ambients = Ambients(entry.first, entry.second, ambients),
        )
        // The same element, so the layout scope has to be shared or the inner block's `layout { }`
        // would build a second one and `:layout` would reject the duplicate axis.
        inner.layoutScope = layoutScope ?: scope()
        inner.childDefaults = childDefaults
        inner.body()
        childDefaults = inner.childDefaults
    }

    /** Supply several ambients at once. Sugar for nested [provide] calls. */
    fun provideAll(vararg entries: Pair<Ambient<*>, Any?>, body: ElementScope<S>.() -> Unit) {
        var env = ambients
        for ((key, value) in entries) env = Ambients(key, value, env)
        val inner = ElementScope(builder, styles, inherited, reserved, ambients = env)
        inner.layoutScope = layoutScope ?: scope()
        inner.childDefaults = childDefaults
        inner.body()
        childDefaults = inner.childDefaults
    }

    // ── Markers and ancestor queries ──────────────────────────────────────────────────────────

    /** Tag this element so descendants can find it with [hasAncestor]. */
    fun marker(marker: Marker) {
        builder.marker(marker)
    }

    /**
     * Whether any ancestor carries [marker]. Resolved now, untracked — ancestry cannot change.
     *
     * Prefer [ambient] where the ancestor is yours to change: an ambient carries a value rather
     * than a boolean, and does not require walking. This is the retrofit escape hatch, for when the
     * container is third-party or should not have to know anything is asking.
     */
    fun hasAncestor(marker: Marker): Boolean = element.hasAncestor(marker)

    /** How many ancestors carry [marker] — "how deeply nested am I", for depth-varying styling. */
    fun ancestorCount(marker: Marker): Int = element.ancestorCount(marker)

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
