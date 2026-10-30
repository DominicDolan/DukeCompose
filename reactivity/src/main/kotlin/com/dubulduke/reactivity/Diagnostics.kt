package com.dubulduke.reactivity

/** One member of a detected dependency cycle, in evaluation order. */
data class CycleMember(val slot: Slot, val name: String?) {
    override fun toString(): String = name?.let { "$it$slot" } ?: slot.toString()
}

/**
 * Where the evaluator reports problems it resolved rather than threw on.
 *
 * A cycle is *not* an exception: the default policy is previous-frame relaxation, which
 * always terminates and costs O(1). The report is how the caller finds out it happened.
 */
fun interface DiagnosticSink {

    /**
     * A dependency cycle was found and broken. [members] is the cycle in evaluation order,
     * starting at the node that was re-entered.
     */
    fun cycle(members: List<CycleMember>)

    companion object {
        val Ignore: DiagnosticSink = DiagnosticSink { }

        val Stderr: DiagnosticSink = DiagnosticSink { members ->
            writeDiagnostic(formatCycle(members))
        }

        /** Useful in tests and in any build that should treat a cycle as a bug. */
        val Throwing: DiagnosticSink = DiagnosticSink { members ->
            throw CycleException(formatCycle(members), members)
        }

        fun formatCycle(members: List<CycleMember>): String = buildString {
            append("Layout cycle (unresolved), ").append(members.size).append(" variables:\n")
            for (i in members.indices) {
                val from = members[i]
                val to = members[(i + 1) % members.size]
                append("  ").append(from).append(" ← ").append(to).append('\n')
            }
            append("Resolved using the previous frame's value.")
        }
    }
}

class CycleException(message: String, val members: List<CycleMember>) : IllegalStateException(message)

/** Recording sink, for tests and for a debug overlay. */
class RecordingSink : DiagnosticSink {
    val cycles = mutableListOf<List<CycleMember>>()
    override fun cycle(members: List<CycleMember>) {
        cycles += members
    }
}

/**
 * Counters for the measurements the design calls for. `marked` and `recomputed` are
 * reported separately on purpose: two-colour marking makes recomputes cheap, not marks,
 * and the two numbers are expected to differ by an order of magnitude.
 */
class Stats {
    /** Nodes whose colour was raised by [Graph.setInput] propagation. */
    var marked: Long = 0
        internal set

    /** Compute bodies actually run. */
    var recomputed: Long = 0
        internal set

    /** [Link] objects allocated. Should stop growing once the graph reaches steady state. */
    var linksAllocated: Long = 0
        internal set

    /** Dependency edges dropped because a compute stopped reading them. */
    var linksTrimmed: Long = 0
        internal set

    /** Nodes destroyed by [Graph.dispose]. Their indices are recycled. */
    var disposed: Long = 0
        internal set

    fun reset() {
        marked = 0; recomputed = 0; linksAllocated = 0; linksTrimmed = 0; disposed = 0
    }

    override fun toString(): String =
        "Stats(marked=$marked, recomputed=$recomputed, linksAllocated=$linksAllocated, " +
            "linksTrimmed=$linksTrimmed, disposed=$disposed)"
}
