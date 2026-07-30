package com.dubulduke.layout

import com.dubulduke.reactivity.Slot

/**
 * Serialise phase-2 output as JSON.
 *
 * Not a backend. `dsl-and-engine.md` §8: for tests there is no renderer, so what is needed is a
 * serialiser of layout output, and it lives in the core rather than behind a drawing interface.
 * The whole test story rests on compose + layout running with nothing to draw on.
 *
 * Reading a rect *evaluates* it, so this doubles as "lay out the whole tree": every slot of every
 * element is pulled, and the dependency edges recorded along the way are what [includeDerivation]
 * reports.
 *
 * Field notes:
 *
 *  - `x` / `y` are the **low** corner in graph coordinates — `origin` of each axis — and
 *    `width` / `height` the two sizes. Whether `y` is visually the top depends on
 *    [Axis.startIsLow], which is the host's business; keeping the dump orientation-independent is
 *    what makes it a stable golden-file target.
 *  - Empty fields are omitted (`id`, `classes`, `text`, `children`) so that golden files stay
 *    diffable.
 *  - `NaN` and infinities are emitted as JSON *strings*, because they are not valid JSON numbers
 *    and because a `NaN` in layout output is a bug you want to see rather than have silently
 *    normalised.
 */
fun ElementTree.dumpJson(
    includeDerivation: Boolean = false,
    from: Element = root,
): String {
    val sb = StringBuilder(1024)
    writeElement(sb, from, includeDerivation, indent = 0)
    sb.append('\n')
    return sb.toString()
}

private fun ElementTree.writeElement(
    sb: StringBuilder,
    e: Element,
    derivation: Boolean,
    indent: Int,
) {
    val pad = "  ".repeat(indent)
    val inner = "  ".repeat(indent + 1)

    sb.append(pad).append("{\n")
    val fields = ArrayList<String>()

    if (e.id != null) fields += "\"id\": ${quote(e.id)}"
    fields += "\"kind\": ${quote(e.kind)}"
    if (e.classes.isNotEmpty()) {
        fields += "\"classes\": [${e.classes.joinToString(", ") { quote(it) }}]"
    }
    fields += "\"rect\": { " +
        "\"x\": ${num(graph.originOf(e.h))}, " +
        "\"y\": ${num(graph.originOf(e.v))}, " +
        "\"width\": ${num(graph.sizeOf(e.h))}, " +
        "\"height\": ${num(graph.sizeOf(e.v))} }"
    e.content?.let { fields += "\"text\": ${quote(graph.textOf(it))}" }

    for ((i, f) in fields.withIndex()) {
        sb.append(inner).append(f)
        val last = i == fields.lastIndex && !derivation && e.children.isEmpty()
        sb.append(if (last) "\n" else ",\n")
    }

    if (derivation) {
        writeDerivation(sb, e, indent + 1, trailingComma = e.children.isNotEmpty())
    }

    if (e.children.isNotEmpty()) {
        sb.append(inner).append("\"children\": [\n")
        for ((i, c) in e.children.withIndex()) {
            writeElement(sb, c, derivation, indent + 2)
            sb.append(if (i == e.children.lastIndex) "\n" else ",\n")
        }
        sb.append(inner).append("]\n")
    }

    sb.append(pad).append("}")
}

/**
 * Where each of the element's four written slots got its value from.
 *
 * This is the dividend `dsl-and-engine.md` §8.1 calls a headline feature rather than test
 * scaffolding, because it supports assertions no conventional UI framework can: not "the box is
 * at x=12" but "the box's left is *derived from its parent*, not hardcoded" — which catches the
 * bug class where a layout is right at one viewport size and wrong at another.
 *
 * What is emitted today is the dependency edge list, keyed by axis and property. The expression
 * *text* and `@Screen.kt:41` source locations of §8.1 need capture at expression-registration
 * time in debug builds (`layout-design.md` Q6) and are not built; when they are, they slot in
 * beside these lists rather than replacing them. Note that an empty list is meaningful: it says
 * the value is a constant, which is exactly the assertion §8.1 is about.
 */
private fun ElementTree.writeDerivation(
    sb: StringBuilder,
    e: Element,
    indent: Int,
    trailingComma: Boolean,
) {
    val pad = "  ".repeat(indent)
    sb.append(pad).append("\"derivation\": {\n")

    val entries = ArrayList<Pair<String, Slot>>()
    for ((axisName, axis) in listOf("h" to e.h, "v" to e.v)) {
        entries += "$axisName.${axis.basis.first.name.lowercase()}" to axis.firstSlot
        entries += "$axisName.${axis.basis.second.name.lowercase()}" to axis.secondSlot
    }

    for ((i, entry) in entries.withIndex()) {
        val (label, slot) = entry
        val deps = graph.dependenciesOf(slot).map { graph.nameOf(it) ?: it.toString() }
        sb.append("  ".repeat(indent + 1))
            .append(quote(label)).append(": [")
            .append(deps.joinToString(", ") { quote(it) })
            .append(']')
        sb.append(if (i == entries.lastIndex) "\n" else ",\n")
    }

    sb.append(pad).append("}").append(if (trailingComma) ",\n" else "\n")
}

/** Stable across platforms, which is the only requirement golden files impose. */
private fun num(d: Double): String = when {
    d.isNaN() -> "\"NaN\""
    d == Double.POSITIVE_INFINITY -> "\"Infinity\""
    d == Double.NEGATIVE_INFINITY -> "\"-Infinity\""
    else -> d.toString()
}

private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
