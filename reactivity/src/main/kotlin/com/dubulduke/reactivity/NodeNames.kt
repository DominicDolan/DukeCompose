package com.dubulduke.reactivity

/**
 * Whether graph nodes carry the human-readable names their diagnostics are written in.
 *
 * `deferred.md` P4's cheap half. §11.2 measured **281 bytes per node and 74 MB at 40 000
 * elements**, and noted that much of it is incidental: every node holds a `String` like
 * `ui.list.row412.v.size`, built during composition and retained for the life of the graph, purely
 * so that a cycle report or a "did not write its claimed output" error can say which slot it means.
 * At six or seven nodes per element that is a String and its backing array per node — roughly a
 * fifth of the heap — spent on text nobody reads unless something goes wrong.
 *
 * ### Why this is a switch and not a deletion
 *
 * Because the names are load-bearing when they are read at all. This codebase's errors are unusually
 * specific — `governor ui.toolbar.flex did not write its claimed output ui.toolbar.[2].h.size` is
 * actionable in a way that `Slot(48117)` is not — and a cycle report is a *list* of slots, which
 * without names is a list of integers. Turning that off by default to save memory nobody has run out
 * of would be trading the thing that makes the design debuggable for a number on a benchmark.
 *
 * So: **on by default, off for a release build**, which is exactly what §11.2 asked for. Set
 * [PROPERTY] on the command line, or assign [enabled] before building the graph.
 *
 * ```
 * java -Dduke.nodeNames=false …
 * ```
 *
 * ### It removes the *building*, not just the storing
 *
 * [nodeName] is `inline`, so a disabled build does not concatenate the string and throw it away —
 * the interpolation is inside the lambda and the lambda is inlined, so nothing is allocated at all.
 * That matters more than it looks: composing a 40 000-element scene takes ~40 ms, and a large part
 * of it is string building that never reaches a `Node`. A field that is merely nulled out on
 * assignment would fix the heap and not the composition.
 */
object NodeNames {

    /** System property that turns names off: `-Dduke.nodeNames=false`. */
    const val PROPERTY: String = "duke.nodeNames"

    /**
     * `false` drops every node name, and every string built to make one.
     *
     * A `var` rather than a `val` so a benchmark can measure both sides of the trade in one JVM —
     * which is the only way the number in `deferred.md` P4 stays honest. Changing it mid-life is
     * legal but pointless: nodes already built keep whatever they were given.
     */
    @JvmField
    var enabled: Boolean = System.getProperty(PROPERTY)?.lowercase() != "false"
}

/**
 * The name to give a node, or `null` when names are off. See [NodeNames].
 *
 * ```kotlin
 * graph.refInput(children, nodeName { "$path.children" })
 * ```
 *
 * Always write the interpolation *inside* the lambda. Passing an already-built string defeats the
 * point — the allocation has happened by the time this is called.
 */
inline fun nodeName(build: () -> String): String? =
    if (NodeNames.enabled) build() else null
