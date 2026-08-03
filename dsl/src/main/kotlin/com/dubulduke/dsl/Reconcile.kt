package com.dubulduke.dsl

import com.dubulduke.layout.Element
import com.dubulduke.layout.ElementTree
import com.dubulduke.layout.Region
import com.dubulduke.reactivity.RefEquality

/**
 * Keyed dynamic children — structural change under compose-once. `layout-design.md` Q10.
 *
 * ```kotlin
 * column(gap = 8.0, id = "list") {
 *     layout { fillWidth(12.0); height { children.last.bottom - top + 12.0 } }
 *
 *     forEach({ model.messages }, key = { it.id }) { item ->
 *         style { colour = ROW }
 *         layout { height { 40.0 } }
 *         label({ item.value.title }, font)
 *     }
 * }
 * ```
 *
 * ### Why this rather than composing every frame
 *
 * `layout-design.md` Q10 left three options open. Re-running the whole compose block per frame
 * is the one DukeUI used, and it is O(tree) work every frame in a design whose entire premise
 * is O(changed) — it re-registers every slot expression on every element whether or not
 * anything moved. The other prerequisites for compose-once were already paid off along the way:
 * stylers are retained lambdas (§4.1), click handlers are retained (§7.4), and text can be a
 * derived node. Structure was the only thing composition alone could still do.
 *
 * So composition still happens **once**. A region is the explicit boundary at which it may
 * happen again, and only there. Cost when nothing changed is one identity comparison; cost when
 * the list changed is proportional to *the list*, never to the tree.
 *
 * ### What a key buys, and what it does not
 *
 * An element whose key survives is **not rebuilt**. Its subtree, its memoised values and its
 * dependency edges persist untouched — that is the whole point, and it is why matching is keyed
 * rather than positional: inserting an item at the head of a positionally-matched list
 * re-associates every element with the wrong data and discards everything below it.
 *
 * The consequence is the one thing to get right when using this. **A surviving item's body does
 * not re-run**, so anything about the item that can change must be read through [Item.value]
 * *inside an expression*, not captured while the element was being built. `label(item.value.title, font)`
 * freezes the title at build time; `label({ item.value.title }, font)` tracks it. That is
 * `dsl-and-engine.md` §5.5's rule applied per item instead of per frame.
 */

/** One item of a [forEach], and the handle through which its data is read. */
class Item<T> internal constructor(
    private val signal: RefSignal<T>,
    /** This item's key. Stable for the life of the element. */
    @JvmField val key: Any,
) {
    /**
     * The item's current data — a **tracked** read.
     *
     * Read inside a layout expression, a style block or a text lambda, this updates when
     * reconciliation replaces the item behind an unchanged key. Read in the item body itself, it
     * captures whatever was there when the element was built — which for a surviving item is
     * whenever it first appeared, possibly a long time ago.
     */
    val value: T get() = signal.value

    override fun toString(): String = "Item($key)"
}

/**
 * Make this element's children come from a list, matched across changes by [key].
 *
 * The element this is called on **owns** its children entirely: mixing `forEach` with statically
 * composed children is rejected, because the two would disagree about what the child list is on
 * the first reconcile. Wrap the static parts in their own sibling instead.
 *
 * [items] is called once per [ElementTree.reconcile] — every frame, in the standard loop — so it
 * must be cheap and should not allocate a fresh list when nothing changed. Returning the same
 * instance short-circuits on identity before a single key is examined.
 */
fun <S : Any, T> ElementScope<S>.forEach(
    items: () -> List<T>,
    key: (T) -> Any,
    body: ElementScope<S>.(Item<T>) -> Unit,
) {
    check(element.currentChildren.isEmpty()) {
        "$element already has statically composed children; an element's children come either " +
            "from its own block or from forEach, not both"
    }
    tree.register(ListRegion(this, items, key, body))
}

/**
 * The mechanism behind [forEach].
 *
 * Holds the scope rather than copying out of it, because it outlives the composition that
 * created it: it may have to build a new child from the same style factory and the same
 * container defaults an arbitrary number of frames later.
 */
internal class ListRegion<S : Any, T>(
    private val scope: ElementScope<S>,
    private val items: () -> List<T>,
    private val key: (T) -> Any,
    private val body: ElementScope<S>.(Item<T>) -> Unit,
) : Region {

    override val container: Element get() = scope.element

    private val tree: ElementTree = scope.tree

    /**
     * The ambient environment as it stood where this `forEach` was declared.
     *
     * Captured, not read on demand, and this is the one genuinely subtle thing about ambients.
     * `provide` is scoped to a *block* during composition, and that block finished long before a
     * row is added at runtime — so an item built during reconciliation would otherwise see whatever
     * environment happened to be current, which is the root's. The result would be a list whose
     * later rows are styled differently from its first ones, with nothing in the source to explain
     * it.
     */
    private val ambients: Ambients? = scope.ambients

    private class Entry(@JvmField val element: Element, @JvmField val data: RefSignal<Any?>)

    /** Key → what was built for it. Insertion-ordered to match the installed child list. */
    private val live = LinkedHashMap<Any, Entry>()

    /** The list as last seen, for the identity short-circuit. */
    private var lastList: List<T>? = null

    /** Scratch, reused: this runs every frame and must not allocate when nothing changed. */
    private val keys = ArrayList<Any>()

    init {
        reconcile()
    }

    override fun reconcile(): Boolean {
        val next = items()
        if (next === lastList) return false
        lastList = next

        keys.clear()
        for (item in next) keys += key(item)

        if (sameKeysInOrder()) {
            // Structure held; only the data behind the keys may have moved. Pushing it into each
            // item's signal is what makes `item.value` update without a rebuild — and a signal
            // written an equal value marks nothing, so an untouched item costs one comparison.
            for (i in keys.indices) live[keys[i]]!!.data.value = next[i]
            return false
        }

        checkNoDuplicates()
        rebuild(next)
        return true
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
                "duplicate key '$k' in $container. A key identifies an element across a " +
                    "structural change, so two items sharing one is unresolvable rather than " +
                    "merely ambiguous."
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rebuild(next: List<T>) {
        val wanted = HashSet<Any>(keys)
        val survivors = ArrayList<Element>(next.size)

        tree.editChildren(container) {
            for (i in keys.indices) {
                val k = keys[i]
                val existing = live[k]
                if (existing != null) {
                    // Survives. Not rebuilt, not re-run; every memoised value and dependency
                    // edge below it is kept, which is the entire point of matching by key.
                    existing.data.value = next[i]
                    survivors += existing.element
                } else {
                    val data = tree.graph.refSignal<Any?>(
                        next[i], "${container.path}.{$k}", RefEquality.EQUALS,
                    )
                    val built = create(key = k) {
                        val itemScope = ElementScope(
                            this, scope.styles, scope.childDefaults, ambients = ambients,
                        )
                        itemScope.body(Item(data as RefSignal<T>, k))
                        itemScope.finish()
                    }
                    live[k] = Entry(built, data)
                    survivors += built
                }
            }

            // Removals last, so a key that merely moved is never destroyed and rebuilt.
            val gone = live.keys.filter { it !in wanted }
            for (k in gone) {
                val e = live.remove(k)!!
                dispose(e.element)
                tree.graph.dispose(e.data.slot)
            }

            install(survivors)
        }

        reorderToMatchKeys()
    }

    /** Keep [live] in installed order, which is what [sameKeysInOrder] compares against. */
    private fun reorderToMatchKeys() {
        val ordered = ArrayList<Entry>(keys.size)
        for (k in keys) ordered += live[k]!!
        live.clear()
        for (i in keys.indices) live[keys[i]] = ordered[i]
    }
}
