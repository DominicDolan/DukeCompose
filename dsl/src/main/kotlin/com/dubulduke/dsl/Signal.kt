package com.dubulduke.dsl

import kotlin.jvm.JvmField

import com.dubulduke.reactivity.Graph
import com.dubulduke.reactivity.RefEquality
import com.dubulduke.reactivity.Slot

/**
 * A value the application owns and the layout graph watches.
 *
 * ```kotlin
 * val zoom = graph.signal(1.0, "zoom")
 *
 * layout { width { 200.0 * zoom.value } }   // reading here records the dependency
 * zoom.value = 1.5                          // and writing invalidates exactly that width
 * ```
 *
 * This is a two-line wrapper over `graph.input` / `graph.read`, and it exists for one reason: an
 * authoring surface should not make people write `g.read(slot)`. The mechanics do not change —
 * reading inside an expression is tracked, reading outside one is a plain fetch, and writing an
 * unchanged value costs a comparison and marks nothing.
 *
 * The subtlety worth knowing, since the wrapper hides the call: **tracking is dynamic, not
 * lexical.** `value` records an edge because *something is currently evaluating*, not because of
 * where the read is written. A read that happens inside a `layout { }` block at composition time —
 * before anything evaluates — records nothing and captures a stale number. That is why expressions
 * are lambdas rather than values, and it is the one rule this convenience does not remove.
 */
class Signal internal constructor(
    @JvmField internal val graph: Graph,
    // No @JvmField: a value-class-typed property cannot have one, and Slot is a value class
    // over an Int. Same reason as FlexItem's and Interaction's slots.
    /** The underlying node, for the rare case that wants `:reactivity` directly. */
    val slot: Slot,
) {
    var value: Double
        get() = graph.read(slot)
        set(v) {
            graph.setInput(slot, v)
        }

    override fun toString(): String = "Signal(${graph.nameOf(slot) ?: slot})"
}

/** A [Signal] holding something other than a number — text, a font, a selected item. */
class RefSignal<T> internal constructor(
    @JvmField internal val graph: Graph,
    val slot: Slot,
) {
    @Suppress("UNCHECKED_CAST")
    var value: T
        get() = graph.readRef(slot) as T
        set(v) {
            graph.setRefInput(slot, v)
        }

    override fun toString(): String = "RefSignal(${graph.nameOf(slot) ?: slot})"
}

/** Create an application-owned number the graph can watch. */
fun Graph.signal(initial: Double = 0.0, name: String? = null): Signal =
    Signal(this, input(initial, name))

/**
 * Create an application-owned reference the graph can watch.
 *
 * [equality] defaults to [RefEquality.EQUALS] rather than identity, because the things that go in
 * one of these are usually strings and data classes rebuilt on the fly — and under identity every
 * rebuild would report a change and re-trigger measurement, the most expensive input the layout
 * graph has. Pass [RefEquality.IDENTITY] for interned handles.
 */
fun <T> Graph.refSignal(
    initial: T,
    name: String? = null,
    equality: RefEquality = RefEquality.EQUALS,
): RefSignal<T> = RefSignal(this, refInput(initial, name, equality))
