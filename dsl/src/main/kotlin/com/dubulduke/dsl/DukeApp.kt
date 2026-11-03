package com.dubulduke.dsl

import kotlin.jvm.JvmField

import com.dubulduke.layout.DukeBackend
import com.dubulduke.layout.DukeHost
import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.Focuser
import com.dubulduke.layout.Pointer
import com.dubulduke.layout.TextMetrics
import com.dubulduke.layout.draw
import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.Slot
import kotlin.math.abs

/**
 * Everything a platform needs to run a DukeCompose UI, in one class to extend.
 *
 * A platform integration is four things — a host, a backend, a graph and a tree — and only the
 * first two carry any platform-specific decisions. The other two are identical everywhere, so
 * asking each integration to wire them up is asking it to repeat work it cannot get interestingly
 * right or wrong. This owns them:
 *
 * ```kotlin
 * class MyUI : DukeApp<MyStyle, MyRenderer>(StyleFactory { MyStyle() }, MyTextMetrics()) {
 *     override val window get() = Window(0.0, 0.0, screenW, screenH)
 *     override val viewport get() = Viewport(0.0, 0.0, screenW, screenH)
 *
 *     override fun drawBox(node: NodeView<MyStyle>, renderer: MyRenderer) { … }
 *     override fun drawText(node: NodeView<MyStyle>, text: String, renderer: MyRenderer) { … }
 * }
 * ```
 *
 * Four members, one file. [compose], [update], [draw], [signal], [pointer] and the viewport inputs
 * come with it.
 *
 * ### Why this is not `DukeHost` itself
 *
 * [DukeHost] is the interface `tree.draw(host, …)` *takes*, so a host owning a tree would be
 * circular — and `:layout`'s tests build trees with no host at all, which is the property that lets
 * layout be tested without a renderer (`dsl-and-engine.md` §6.1). The host/backend split stays;
 * this class just implements both, because a platform that provides one almost always provides the
 * other. Anything that genuinely wants them apart — a recording backend in a test, two renderers
 * over one tree — still calls `tree.draw(host, backend, renderer)` directly. Nothing is closed off.
 *
 * ### Compose once
 *
 * [compose] may be called once. That is not a limitation being worked around, it is the point:
 * values change by writing graph inputs, not by re-running composition, so a UI whose *structure*
 * is fixed never recomposes at all. Running composition per frame would rebuild the graph every
 * frame, discard every memoised value, and do O(tree) work in a design whose premise is
 * O(changed).
 *
 * Structural change — a list whose length varies, a panel that appears — goes through [forEach],
 * which rebuilds only the region whose keys actually moved (`layout-design.md` Q10).
 *
 * ### The order of the phases
 *
 * [update] dispatches input, settles structure and pushes this frame's size into the graph;
 * [draw] pulls. Nothing is laid out in between, because reading geometry *is* what evaluates it —
 * the draw walk is the layout driver, and a frame in which nothing changed recomputes nothing.
 */
abstract class DukeApp<S : Any, R>(
    /**
     * Makes the application's style objects. There are no framework default styles (§4.5); a
     * platform that wants a batteries-included default supplies one as a secondary constructor.
     */
    @JvmField val styles: StyleFactory<S>,
    final override val textMetrics: TextMetrics,
) : DukeHost, DukeBackend<S, R> {

    @JvmField val graph: Graph = Graph()

    @JvmField val tree: ElementTree = ElementTree(graph, textMetrics)

    /**
     * Hover, press and click dispatch. `dsl-and-engine.md` §7.
     *
     * Hit-tests against the geometry the *last* frame settled on, which is why hover changing
     * layout is not a cycle: one frame of lag is already inherent, since input is sampled before
     * anything is drawn.
     */
    @JvmField val pointer: Pointer = Pointer(tree)

    /**
     * Keyboard focus and directional navigation. `dsl-and-engine.md` §7, the keyboard half.
     *
     * Separate from [pointer] because focus routes *without* hit testing: it is retained state that
     * changes when something asks it to, not a per-frame answer to "what is under the cursor?".
     */
    @JvmField val focus: Focuser = Focuser(tree)

    abstract val input: DukeInput

    /** A field's state and font, exposed for platform renderers. */
    class TextFieldView internal constructor(
        val state: TextFieldState, val font: com.dubulduke.layout.FontRef,
        internal val onSubmit: () -> Unit, internal val onCancel: () -> Unit,
        internal val onFocusChanged: (Boolean) -> Unit,
    )
    private val textFields = mutableMapOf<Element, TextFieldView>()
    fun textFieldOf(element: Element): TextFieldView? = textFields[element]
    val isEditingText: Boolean get() = focus.focused?.let { textFields.containsKey(it) } == true
    private val textFieldReleases = mutableMapOf<Element, () -> Unit>()

    init {
        focus.addFocusListener { previous, current ->
            previous?.let { textFields[it]?.onFocusChanged?.invoke(false) }
            current?.let { textFields[it]?.onFocusChanged?.invoke(true) }
        }
        tree.onDispose { element ->
            textFields.remove(element)
            textFieldReleases.remove(element)
        }
    }

    internal fun registerTextField(element: Element, state: TextFieldState, font: com.dubulduke.layout.FontRef,
        onSubmit: () -> Unit, onCancel: () -> Unit, onFocusChanged: (Boolean) -> Unit) {
        textFields[element] = TextFieldView(state, font, onSubmit, onCancel, onFocusChanged)
    }

    internal fun registerTextFieldRelease(element: Element, release: () -> Unit) {
        textFieldReleases[element] = release
    }

    /** Whether the last update delivered text editing events to a focused field. */
    var textEditsConsumed: Boolean = false
        private set

    /**
     * The size of the layout coordinate space, as graph inputs.
     *
     * Two independent nodes rather than one "viewport" object, because the axes are independent
     * (`layout-design.md` §2.2): a window that gets wider but no taller must not invalidate
     * anything that only reads the height.
     *
     * Kept in sync from [viewport] by [syncViewport], magnitude only — a negative viewport extent
     * means the host's axis runs opposite to layout's, which is a concern of the draw transform and
     * not of the number a `width { }` expression should see.
     */
    private val viewportWidthSlot: Slot = graph.input(0.0, "viewport.width")
    private val viewportHeightSlot: Slot = graph.input(0.0, "viewport.height")

    /**
     * The viewport size, read through the graph, so a layout expression says
     * `width { app.viewportWidth }` and never `g.read(...)`. The read is tracked exactly as a raw
     * one would be — tracking is dynamic, so what matters is that something is evaluating when the
     * getter runs, not how the call is spelled. See [Signal].
     */
    val viewportWidth: Double get() = graph.read(viewportWidthSlot)
    val viewportHeight: Double get() = graph.read(viewportHeightSlot)

    /** Create an application-owned number the layout graph can watch. */
    fun signal(initial: Double = 0.0, name: String? = null): Signal = graph.signal(initial, name)

    /** Create an application-owned reference — a label's text, a selected item — the graph watches. */
    fun <T> refSignal(initial: T, name: String? = null): RefSignal<T> = graph.refSignal(initial, name)

    /** True once [compose] has run. [update] and [draw] are no-ops before that. */
    var isComposed: Boolean = false
        private set

    private var rootElement: Element? = null

    /** The composed root, or `null` before [compose]. */
    val root: Element? get() = rootElement

    /** Build the UI. Once — see the class docs. Returns the root element. */
    fun compose(id: String? = "ui", build: ElementScope<S>.() -> Unit): Element {
        check(!isComposed) {
            "${this::class.simpleName}.compose may only be called once; drive changes with inputs"
        }
        // Before the body runs, so a `width { viewportWidth }` written during composition reads the
        // real size rather than the zero the slots were created with.
        syncViewport()
        isComposed = true
        return tree.compose(styles, id, build).also { rootElement = it }
    }


    /**
     * Wheel movement asked for but not yet delivered. See [update].
     *
     * One number, not two. The distance still owed *is* the animation state: there is nothing to
     * remember about how far it has already travelled, because that has already been dispatched
     * and acted on by whatever was under the pointer.
     */
    private var pendingScroll = 0.0

    /**
     * Roughly how long a wheel notch takes to play out. Zero dispatches it in one frame.
     *
     * `open` because it is a feel decision, and feel belongs to the game rather than the framework.
     */
    protected open val animatedScrollTime: Double get() = 0.15

    /**
     * Push this frame's pointer and viewport size into the graph, and dispatch input.
     *
     * Order matters, and it is the order of §7.1: hit test and dispatch first, so a handler that
     * mutates application state does so *before* the layout that will read it. Writing an unchanged
     * input is free — the graph compares before it marks — so a still pointer over a still window
     * costs one hit test and no invalidation at all.
     *
     * ### The wheel is eased, and eases over what is *left*
     *
     * A wheel notch arrives as one discrete jump, which reads as a stutter, so it is spread over
     * [animatedScrollTime]. The fraction is taken from the distance still owed rather than from the
     * original notch, and that is the difference between an ease and an overshoot: a fixed fraction
     * of the original delivers a linear ramp that stops on whichever frame first crosses the target,
     * overrunning by up to a whole frame's worth. That error is invisible at 60fps and grows as the
     * frame rate drops — 11% at 30fps, 33% at 10fps — which is the worst possible failure shape,
     * because it only misbehaves on the machines least able to hide it.
     *
     * Taking a fraction of the remainder is exponential decay: it converges on exactly what was
     * asked for at any frame rate, and a long frame delivers more of the notch rather than more
     * than the notch. [delta] is seconds since the last frame.
     */
    fun update(delta: Double) {
        textEditsConsumed = false
        if (isComposed) {
            pendingScroll += input.scrollDistance

            // Clamped at 1: a frame longer than the whole animation finishes it, and must not
            // deliver more than is owed.
            val fraction = if (animatedScrollTime <= 0.0) 1.0
            else minOf(1.0, delta / animatedScrollTime)

            var step = pendingScroll * fraction
            pendingScroll -= step
            // Decay approaches zero without arriving, so the tail is folded into the last step
            // rather than left to dispatch ever-smaller wheel events forever — which would keep
            // waking the hit test on a UI nobody is touching.
            if (abs(pendingScroll) < SCROLL_EPSILON) {
                step += pendingScroll
                pendingScroll = 0.0
            }

            val down = input.isClickDown()
            pointer.update(this, input.pointerX, input.pointerY, down, step)
            if (!down) textFieldReleases.values.forEach { it() }

            val edits = input.textEdits
            val field = focus.focused?.let { textFields[it] }
            textEditsConsumed = field != null && edits.isNotEmpty()
            if (field != null) for (edit in edits) {
                if (focus.focused?.let { textFields[it] } !== field) break
                when (edit) {
                    TextEdit.Submit -> field.onSubmit()
                    TextEdit.Cancel -> field.onCancel()
                    else -> field.state.edit(edit)
                }
            }

            // Focus navigation *after* the pointer and *before* reconcile, for the same reason the
            // pointer runs where it does: activating a focused button may change the list, and that
            // has to be settled before anything measures. Navigation itself reads geometry — the
            // previous frame's — which is the standing arrangement here rather than a compromise.
            input.navigation?.let { focus.handle(it) }

            reconcile()
        }
        syncViewport()
    }

    /**
     * Rebuild any [forEach] whose list changed. Returns how many regions did.
     *
     * Runs *after* click dispatch and before anything reads geometry, which is the only order
     * that works: a handler may be what changes the list, and building an element allocates
     * graph nodes, which cannot happen once evaluation has started. Costs one identity
     * comparison per region on a frame where nothing changed.
     */
    fun reconcile(): Int = tree.reconcile()

    /** Republish [viewport]'s size to the graph inputs behind [viewportWidth] / [viewportHeight]. */
    protected fun syncViewport() {
        val vp = viewport
        graph.setInput(viewportWidthSlot, abs(vp.width))
        graph.setInput(viewportHeightSlot, abs(vp.height))
    }

    /**
     * Lay out whatever is stale and draw it, with this object as the backend.
     *
     * There is no separate layout pass to forget: reading geometry evaluates it, so the walk *is*
     * the layout driver, and a settled frame costs a few branches per slot.
     */
    /**
     * `open` so a platform can bracket the walk with state it has to restore.
     *
     * A backend that pushes global renderer state — a scissor rectangle being the standing case —
     * balances it inside the walk, but leaving that as the *only* guarantee makes an exception
     * thrown mid-draw corrupt the state of everything drawn afterwards. An override calling
     * `super.draw(renderer)` and then clearing is the intended shape.
     */
    open fun draw(renderer: R) {
        if (!isComposed) return
        tree.draw(this, this, renderer)
    }

    private companion object {
        /** Below this, the remaining scroll is not worth another frame of animation. */
        const val SCROLL_EPSILON = 1e-4
    }
}
