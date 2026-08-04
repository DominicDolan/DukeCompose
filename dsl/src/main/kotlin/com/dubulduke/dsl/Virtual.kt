package com.dubulduke.dsl

import com.dubulduke.layout.ChildEdit
import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.Region
import com.dubulduke.layout.heightOf
import com.dubulduke.reactivity.RefEquality
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Virtualised rows: build only what is near the viewport. `deferred.md` P2.
 *
 * ```kotlin
 * scrollArea(id = "inbox") {
 *     layout { fillWidth(12.0); top { 16.0 }; height { 400.0 } }
 *
 *     virtualContent({ messages }, key = { it.id }, rowHeight = 40.0, gap = 8.0) { row ->
 *         style { colour = if (row.index % 2 == 0) EVEN else ODD }
 *         label({ row.value.title }, font)
 *     }
 * }
 * ```
 *
 * ### The problem this solves is the *read* walk, not the layout
 *
 * §11.2 measured a settled 40 000-element scene at 5.3 ms just to read every rectangle — 32% of a
 * 60 Hz frame spent asking questions whose answers did not change. Clipping already skips drawing
 * the off-screen rows, but a clipped row is still a live element with live slots: it is laid out,
 * hit-tested against, and walked. Culling answers "what do I draw"; only virtualisation answers
 * "what exists".
 *
 * So the win here is not a faster evaluator. It is that ten thousand rows become the forty that
 * are on screen, and every per-element cost in the system — the draw walk, the dispose bookkeeping,
 * the 300 bytes of graph node per slot — scales with the viewport instead of with the data.
 *
 * ### Uniform pitch is the whole contract
 *
 * A row is [rowHeight] tall and rows are [gap] apart, and that is not a simplification — it is what
 * makes the feature possible at all. **The content's height has to be known without measuring the
 * children**, or `maxScroll` would need the rows built to decide which rows to build. Under uniform
 * pitch it is arithmetic: `count × pitch − gap`. A list whose rows size themselves to their own
 * wrapped text cannot be virtualised this way, and should use [ScrollScope.content] with
 * [forEach].
 *
 * The same contract buys the second thing §11.2 asked for. Rows are positioned **by index against
 * the pitch** rather than from the previous row's bottom, which is the *indexed* graph shape — the
 * one measured at 1 link of chain depth instead of one per row, and consistently the cheaper of the
 * two. Virtualisation and the chain-depth ceiling turn out to be the same fix.
 *
 * ### What it costs, and what it gives up
 *
 * A row that is not built does not exist. It is not hit-testable, it is not in the tab order, and
 * [ScrollScope.reveal] cannot bring it into view — focus can only move between rows the window
 * contains, and scrolling is what extends the window. That is the honest trade and it is why this
 * is opt-in rather than what `content { }` does.
 *
 * [overscan] rows are kept built on each side of the viewport so that a scroll of less than one row
 * does not rebuild anything.
 */
fun <S : Any, T> ScrollScope<S>.virtualContent(
    items: () -> List<T>,
    key: (T) -> Any,
    rowHeight: Double,
    gap: Double = 0.0,
    overscan: Int = 2,
    id: String? = null,
    body: ElementScope<S>.(VirtualItem<T>) -> Unit,
): Element {
    require(rowHeight > 0.0) { "rowHeight must be positive, was $rowHeight" }
    require(gap >= 0.0) { "gap must not be negative, was $gap" }
    require(overscan >= 0) { "overscan must not be negative, was $overscan" }
    return virtualise(this, items, key, rowHeight, gap, overscan, id, body)
}

/**
 * One row of a [virtualContent], and the handle through which its data and its place are read.
 *
 * Both are **tracked reads**, and for the same reason [Item.value] is: a row that survives a
 * reconcile is not rebuilt, so anything that can change about it has to be read inside an
 * expression rather than captured while the element was being built.
 */
class VirtualItem<T> internal constructor(
    private val data: RefSignal<T>,
    private val position: Signal,
    /** This row's key. Stable for the life of the element. */
    @JvmField val key: Any,
) {
    /** The row's current data. Tracked — see the class docs. */
    val value: T get() = data.value

    /**
     * This row's index in the **whole list**, not in the built window.
     *
     * Tracked, so an insertion above a surviving row moves it without rebuilding it: the region
     * writes the new index and exactly the slots that read it wake up. That is also why this is not
     * `LayoutScope.index`, which is the position among the element's built siblings and means
     * something different here.
     */
    val index: Int get() = position.value.toInt()

    override fun toString(): String = "VirtualItem($key @ $index)"
}

private fun <S : Any, T> virtualise(
    scroll: ScrollScope<S>,
    items: () -> List<T>,
    key: (T) -> Any,
    rowHeight: Double,
    gap: Double,
    overscan: Int,
    id: String?,
    body: ElementScope<S>.(VirtualItem<T>) -> Unit,
): Element {
    val pitch = rowHeight + gap
    val count = scroll.graph.signal(0.0, "${scroll.element.path}.count")

    lateinit var contentScope: ElementScope<S>
    val content = scroll.content(id = id) {
        // The scope, held rather than rebuilt, because the region outlives the composition that
        // created it: it has to build a row from the same style factory and the same ambient
        // environment an arbitrary number of frames later. Same reason [ListRegion] holds one.
        contentScope = this
        // `content { }` makes the content a stacking container, which is right for [forEach] and
        // wrong here — rows are placed by index, and a default `top` reading `prev` would rebuild
        // the chained shape this exists to avoid. Nothing inherits it either way; cleared so that
        // a row added by other means cannot pick it up.
        childDefaults = null
        // The height that makes the whole thing work: arithmetic over the item count, not a
        // measurement of the children. `maxScroll` therefore reports the full list's overflow while
        // only the visible rows exist — which is the circularity virtualisation would otherwise
        // have, since what to build depends on how far you can scroll.
        //
        // Overrides the stacked height `content { }` installs by default; last write wins.
        layout { height { maxOf(0.0, count.value * pitch - gap) } }
    }

    scroll.tree.register(
        VirtualRegion(contentScope, scroll, count, items, key, rowHeight, pitch, overscan, body)
    )
    return content
}

/**
 * The mechanism behind [virtualContent]: a [Region] whose children are the rows the viewport can
 * currently see.
 *
 * Structurally a [ListRegion] over a moving slice, and the differences are all consequences of the
 * slice moving:
 *
 *  - It reconciles on **scroll** as well as on list change, so the cheap path has to compare the
 *    window as well as the list identity. Both are O(1).
 *  - It never looks at the whole list. Keys are taken for the window only, which is what keeps the
 *    per-frame cost proportional to the viewport — checking the full list for duplicate keys would
 *    have quietly reintroduced the O(elements) walk this exists to remove.
 *  - Each row carries a **position signal** holding its absolute index, so a row that survives a
 *    scroll or an insertion is repositioned by a write rather than by a rebuild.
 */
private class VirtualRegion<S : Any, T>(
    private val scope: ElementScope<S>,
    private val scroll: ScrollScope<S>,
    private val count: Signal,
    private val items: () -> List<T>,
    private val key: (T) -> Any,
    private val rowHeight: Double,
    private val pitch: Double,
    private val overscan: Int,
    private val body: ElementScope<S>.(VirtualItem<T>) -> Unit,
) : Region {

    override val container: Element get() = scope.element

    private val tree: ElementTree = scope.tree

    /** See [ListRegion.ambients] — the same subtlety, for the same reason. */
    private val ambients: Ambients? = scope.ambients

    private class Entry(
        @JvmField val element: Element,
        @JvmField val data: RefSignal<Any?>,
        @JvmField val position: Signal,
    )

    private val live = LinkedHashMap<Any, Entry>()

    private var lastList: List<T>? = null

    /** The built window, as a half-open range of list indices. */
    private var winFirst = 0
    private var winEnd = 0

    /** Scratch, reused: this runs every frame and must not allocate when nothing changed. */
    private val keys = ArrayList<Any>()

    // Deliberately no `init { reconcile() }`, unlike ListRegion. The window is decided from the
    // viewport's *height*, and no element in this subtree has geometry yet: `scrollArea` commits
    // the viewport's axes after its body has run, which is after this constructor. So the first
    // window is built by the first `reconcile()` — which `DukeApp.update` performs before anything
    // draws, so nothing is ever shown empty.

    override fun reconcile(): Boolean {
        val next = items()
        count.value = next.size.toDouble()

        val first = window(next.size)
        val end = windowEnd

        // The every-frame path: the list is the same object and the viewport shows the same slice
        // of it. One identity comparison and two integer comparisons.
        if (next === lastList && first == winFirst && end == winEnd) return false

        val windowMoved = first != winFirst || end != winEnd
        lastList = next

        keys.clear()
        for (i in first until end) keys += key(next[i])

        if (!windowMoved && sameKeysInOrder()) {
            // Structure held; only the data behind the keys moved. Same argument as [ListRegion]:
            // pushing it into each row's signal is what makes `row.value` update without a rebuild.
            for (i in keys.indices) live[keys[i]]!!.data.value = next[first + i]
            return false
        }

        checkNoDuplicates()
        rebuild(next, first, end)
        winFirst = first
        winEnd = end
        return true
    }

    /** Set by [window] alongside its return value, which is the first index. */
    private var windowEnd = 0

    /**
     * The half-open range of row indices the viewport can see, widened by [overscan] each side.
     * Returns the first; leaves the end in [windowEnd].
     *
     * Reads the scroll offset and the viewport's height **untracked**, from the reconcile phase —
     * the same standing arrangement as [ScrollScope.maxScroll], and sound for the same reason: this
     * runs between input and layout, so the geometry it sees is the previous frame's and there is
     * no "correct current answer" being given up. A row's *position*, unlike the decision to build
     * it, is an ordinary tracked expression.
     *
     * The offset is clamped **here** rather than relied upon to have been clamped already. It is
     * clamped elsewhere too — `scrollArea` re-clamps once a frame as its own [Region] — but that
     * one runs *after* this, so a scroll position set past the end of the list (by the application,
     * or by a deletion) would otherwise produce an empty window for one frame: a list that flickers
     * blank exactly when it is scrolled to the bottom. Both clamps are the same arithmetic and this
     * one needs nothing built to do it.
     */
    private fun window(size: Int): Int {
        if (size == 0) {
            windowEnd = 0
            return 0
        }
        val viewHeight = tree.graph.heightOf(scroll.element)
        val contentHeight = size * pitch - (pitch - rowHeight)
        val top = scroll.scroll.value.coerceIn(0.0, maxOf(0.0, contentHeight - viewHeight))

        val first = (floor(top / pitch).toInt() - overscan).coerceIn(0, size)
        windowEnd = (ceil((top + viewHeight) / pitch).toInt() + overscan).coerceIn(first, size)
        return first
    }

    private fun sameKeysInOrder(): Boolean {
        if (keys.size != live.size) return false
        var i = 0
        for (k in live.keys) {
            if (k != keys[i++]) return false
        }
        return true
    }

    private fun checkNoDuplicates() {
        val seen = HashSet<Any>(keys.size)
        for (k in keys) {
            require(seen.add(k)) {
                "duplicate key '$k' in $container. A key identifies a row across a structural " +
                    "change, so two rows sharing one is unresolvable rather than merely ambiguous. " +
                    "Note that only the built window is checked — a duplicate further down the " +
                    "list is not seen until it is scrolled to."
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rebuild(next: List<T>, first: Int, end: Int) {
        val wanted = HashSet<Any>(keys)
        val survivors = ArrayList<Element>(keys.size)

        tree.editChildren(container) {
            for (i in keys.indices) {
                val k = keys[i]
                val absolute = first + i
                val existing = live[k]
                if (existing != null) {
                    // Survives — scrolled past, or shifted by an insertion above it. Not rebuilt:
                    // its subtree, its memoised values and its dependency edges are all kept, and
                    // its new place is one signal write.
                    existing.data.value = next[absolute]
                    existing.position.value = absolute.toDouble()
                    survivors += existing.element
                } else {
                    survivors += build(this, k, absolute, next[absolute])
                }
            }

            // Removals last, so a key that merely moved within the window is never destroyed and
            // rebuilt. Rows scrolled out of the window are destroyed, which is the point.
            val gone = live.keys.filter { it !in wanted }
            for (k in gone) {
                val e = live.remove(k)!!
                dispose(e.element)
                tree.graph.dispose(e.data.slot)
                tree.graph.dispose(e.position.slot)
            }

            install(survivors)
        }

        reorderToMatchKeys()
    }

    @Suppress("UNCHECKED_CAST")
    private fun build(
        edit: ChildEdit,
        k: Any,
        absolute: Int,
        item: T,
    ): Element {
        val g = tree.graph
        val data = g.refSignal<Any?>(item, "${container.path}.{$k}", RefEquality.EQUALS)
        val position = g.signal(absolute.toDouble(), "${container.path}.{$k}.index")

        val built = edit.create(key = k) {
            val rowScope = ElementScope(
                this, scope.styles,
                // The row's own layout, stamped as *container defaults* rather than written
                // directly, so a row that wants to state its own cross-axis geometry still can —
                // ordinary last-write-wins (§2.4). The vertical pair is the contract, though:
                // overriding `height` changes the box that gets drawn and not the pitch the region
                // reckons in, which would put the row somewhere its neighbours disagree about.
                inherited = {
                    left { parent.left }
                    right { parent.right }
                    top { parent.top + position.value * pitch }
                    height { rowHeight }
                },
                ambients = ambients,
            )
            rowScope.body(VirtualItem(data as RefSignal<T>, position, k))
            rowScope.finish()
        }
        live[k] = Entry(built, data, position)
        return built
    }

    /** Keep [live] in installed order, which is what [sameKeysInOrder] compares against. */
    private fun reorderToMatchKeys() {
        val ordered = ArrayList<Entry>(keys.size)
        for (k in keys) ordered += live[k]!!
        live.clear()
        for (i in keys.indices) live[keys[i]] = ordered[i]
    }
}
