package com.dubulduke.layout

import com.dubulduke.reactivity.Graph

/**
 * Phase 3: hand laid-out elements to something that draws.
 *
 * `dsl-and-engine.md` §6.1 splits three concerns that iteration C's `DukeContext` had conflated:
 * the **host** supplies platform geometry and text measurement, the **backend** draws, and the
 * engine knows about neither. The split is not decoration — it is what lets compose and layout run
 * with no renderer at all, which is the whole test story, and it is what deletes iteration C's
 * mutable nullable `drawer` field and its `require(draw != null)` on every element.
 *
 * Nothing in this file is reactive. The draw walk sits outside the graph by construction: it
 * *pulls* geometry and style, recording no edges, which is why it may use [Element.children]
 * rather than `childrenOf`.
 */

/** A rectangle in the host's own coordinates — where the UI is drawn. */
data class Window(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * The rectangle that layout coordinates span, mapped onto [Window].
 *
 * Either extent may be negative, and that is the mechanism rather than an edge case: a host whose
 * screen space is y-up passes a negative viewport height and gets layout's y-down convention
 * mapped for it, with no flag anywhere in the layout module. Mechanica does exactly this.
 */
data class Viewport(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * The platform: geometry and measurement, **no drawing**.
 *
 * `dsl-and-engine.md` §6.1 — separating this from the backend exposed a requirement that was
 * previously invisible: layout needs text measurement, text measurement is backend-specific, and
 * `content.heightAt(width)` therefore cannot be computed without something the renderer owns. So
 * metrics must be reachable without a renderer in hand.
 */
interface DukeHost {
    val window: Window
    val viewport: Viewport
    val textMetrics: TextMetrics
}

/**
 * One element, ready to draw: geometry already transformed into window coordinates, style
 * resolved on demand.
 *
 * This is iteration A/C's `RenderDescription`, retained. The scale-and-offset arithmetic stays
 * inside the engine so **a backend never sees viewport coordinates** (§6.1).
 *
 * **The instance is reused for every element in a walk** and is only valid inside the backend call
 * that receives it. A game loop draws this walk every frame; allocating a view per element per
 * frame is the kind of steady-state garbage the rest of this codebase goes out of its way to avoid.
 * A backend that needs to keep one must copy what it wants out.
 *
 * The `S` parameter is unchecked — see [style]. It is here so backends and application draw code
 * are typed, not because the tree guarantees it.
 */
class NodeView<S> internal constructor(
    private val tree: ElementTree,
    private val widthScale: Double,
    private val heightScale: Double,
    private val xAdjust: Double,
    private val yAdjust: Double,
) {
    private var current: Element? = null

    internal fun moveTo(e: Element) {
        current = e
    }

    /** The element being drawn. Its geometry is [x] / [y] / [width] / [height], not its slots. */
    val element: Element get() = checkNotNull(current) { "no element is being drawn" }

    /**
     * The graph, for the reference payloads a backend legitimately needs to resolve — the font of
     * a text element being the standing case, since what is drawn has to match what was measured.
     *
     * Reads from here record no dependency: the draw walk is outside the graph by construction.
     * Reading is all a backend may do. Writing during a draw would mean a frame whose layout
     * depends on its own drawing.
     */
    val graph: Graph get() = tree.graph

    /**
     * The low corner in window coordinates.
     *
     * "Low" in *layout* coordinates: whether that is visually the top depends on the sign of the
     * viewport extent, and after transformation it may be either edge. Backends that care should
     * use [minY] / [maxY].
     */
    val x: Double get() = tree.graph.originOf(element.h) * widthScale + xAdjust

    /** The low corner in window coordinates. See [x]. */
    val y: Double get() = tree.graph.originOf(element.v) * heightScale + yAdjust

    /** Signed: negative when the host's horizontal axis runs opposite to layout's. */
    val width: Double get() = tree.graph.sizeOf(element.h) * widthScale

    /** Signed: negative when the host's vertical axis runs opposite to layout's — the usual case. */
    val height: Double get() = tree.graph.sizeOf(element.v) * heightScale

    val minX: Double get() = x.let { if (width < 0.0) it + width else it }
    val maxX: Double get() = x.let { if (width < 0.0) it else it + width }
    val minY: Double get() = y.let { if (height < 0.0) it + height else it }
    val maxY: Double get() = y.let { if (height < 0.0) it else it + height }

    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0

    /**
     * The application's style object, its styler re-run if any input changed since last frame.
     *
     * Unchecked: the cast is the price of keeping the style type off [ElementTree] and every scope
     * type (see [com.dubulduke.layout.style]). It is safe for the intended usage — one style type
     * per tree — and a `ClassCastException` here means two were mixed.
     */
    @Suppress("UNCHECKED_CAST")
    val style: S get() = tree.graph.styleOf(element) as S

    /** The style, or `null` for an element composed without one. */
    @Suppress("UNCHECKED_CAST")
    val styleOrNull: S? get() = tree.graph.styleOrNullOf(element) as S?

    /** True if [x] / [y] in window coordinates falls inside this element. For hit testing. */
    fun contains(x: Double, y: Double): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY

    override fun toString(): String = "NodeView(${current?.path ?: "-"})"
}

/**
 * Drawing, and nothing else.
 *
 * Generic in the renderer `R` rather than one non-generic `renderElement`, because the interface
 * cannot otherwise know what a Mechanica `Drawer` is; and generic in the style `S` for the same
 * reason. Both were already this shape in iteration A's `ElementRenderer<S, E, *>`.
 *
 * Dispatch is per element *kind* rather than one entry point, because text needs materially
 * different treatment from a box — iteration A handled that with a renderer per element class, and
 * this is the same idea flattened (§6.1).
 */
interface DukeBackend<S, R> {
    fun drawBox(node: NodeView<S>, renderer: R)

    /** [text] is passed rather than read off the node, because resolving it is a graph pull. */
    fun drawText(node: NodeView<S>, text: String, renderer: R)

    /** Anything the application named with `kind(...)`. Ignored by default. */
    fun drawCustom(node: NodeView<S>, kind: String, renderer: R) {}
}

/**
 * Walk the tree and draw it.
 *
 * Paint order is the composition order: an element before its children, siblings in index order,
 * so later siblings and children paint on top. That is also the order [ElementTree.walk] uses and
 * the reverse of the order hit testing will want (§7.5).
 *
 * Reading geometry *evaluates* it, so this doubles as "lay out the tree" exactly as
 * [dumpJson] does. Calling it on a settled graph costs a few branches per slot and recomputes
 * nothing; there is no separate layout pass to forget.
 */
fun <S, R> ElementTree.draw(
    host: DukeHost,
    backend: DukeBackend<S, R>,
    renderer: R,
    from: Element = root,
) {
    val window = host.window
    val viewport = host.viewport
    require(viewport.width != 0.0 && viewport.height != 0.0) {
        "the viewport has a zero extent ($viewport); every element would map to one point"
    }

    // Iteration A's RenderDescription arithmetic, unchanged because it is already proven against
    // Mechanica's camera. The sign adjustment is what keeps a mirrored axis inside the window
    // rather than reflecting it out the far side.
    val widthScale = window.width / viewport.width
    val heightScale = window.height / viewport.height
    val xAdjust = window.x - widthScale * viewport.x + if (widthScale < 0.0) window.width else 0.0
    val yAdjust = window.y - heightScale * viewport.y + if (heightScale < 0.0) window.height else 0.0

    val view = NodeView<S>(this, widthScale, heightScale, xAdjust, yAdjust)

    fun rec(e: Element) {
        view.moveTo(e)
        // Dispatch on kind, not on the presence of content, so that `kind("button")` on a text
        // element reaches drawCustom and can decide for itself what to do with the text.
        when (e.kind) {
            "text" -> backend.drawText(view, graph.textOf(e.content!!), renderer)
            "box" -> backend.drawBox(view, renderer)
            else -> backend.drawCustom(view, e.kind, renderer)
        }
        for (c in e.children) rec(c)
    }
    rec(from)
}
