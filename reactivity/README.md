# `:reactivity`

The dependency-graph evaluator that the layout engine will sit on. A port of the reactive
core of `@solidjs/signals` 2.0, specialised to `Double` (with a reference-valued node type
alongside for text, fonts and child lists), with cycle detection added and all the async
machinery left out. Rationale and source correspondence:
[`docs/reactivity-core.md`](../../docs/reactivity-core.md).

Nothing here knows about elements, axes, or layout. It is a graph of values.

## Using it

```kotlin
val g = Graph()

val width  = g.input(800.0, "viewport.width")
val half   = g.derive("half")  { read(width) / 2.0 }
val padded = g.derive("padded") { read(half) - 16.0 }

g.read(padded)          // 384.0 — runs both computations
g.read(padded)          // 384.0 — runs neither

g.setInput(width, 1000.0)
g.read(padded)          // 484.0 — runs both again
```

`derive` bodies are lambdas with `Graph` as receiver, so `read(...)` inside them is tracked
and becomes a dependency edge. Nothing runs until a value is demanded.

When a set of values is mutually dependent — flex-shrink with clamps being the canonical
case — a **governor** computes the whole set in one body, where an ordinary loop can handle
what the graph cannot order:

```kotlin
val a = g.input(); val b = g.input()

val gov = g.governor(listOf(a, b), "split") {
    writeOutput(a, read(total) * 0.25)
    writeOutput(b, read(total) * 0.75)
}

g.read(a)               // runs the solve
g.read(b)               // does not
```

Reading either output runs the solve once; each output is then compared and propagated
separately, so a re-solve that lands on the same value for `a` does not wake `a`'s readers.

Not everything a layout depends on is a number. Text content, resolved fonts and child lists
get reference-valued nodes, which are the same nodes with a different payload:

```kotlin
val text  = g.refInput("Total: 5", "text", RefEquality.EQUALS)
val width = g.derive("width") { (readRef(text) as String).length * read(perChar) }

g.setRefInput(text, "Total: " + 5)   // a fresh String, equal content
g.read(width)                        // does not re-measure
```

`RefEquality` is per node and matters: see the list below.

## What is implemented

- **Intrusive doubly-linked edges.** One `Link` object is simultaneously a member of the
  subscriber's dependency list and the dependency's subscriber list, so reverse edges cost
  nothing extra.
- **Dependency reconciliation.** Links are reused in place across recomputes via a pass
  generation stamp. In steady state a recompute allocates nothing, and out-of-order re-reads
  of the same dependency are O(1) rather than a list scan.
- **Two-colour marking.** A write marks direct subscribers `DIRTY` and everything further
  downstream `CHECK`. Resolving a `CHECK` is a pull, and a node that recomputes to an
  unchanged value stops the propagation entirely.
- **Pull evaluation.** The call stack is the topological order; it is never computed. Solves
  the diamond problem — the join of a diamond evaluates exactly once and never observes a
  half-updated world. **The price is a hard limit on chain length**: recursion depth equals the
  longest dependency chain at ~128–145 bytes of JVM stack per link, so a default thread stack
  settles about 3 900 nodes deep and then throws `StackOverflowError`. 8 MB buys ~64 000, and
  depth is *linear* in stack size, so a host that owns its layout thread can size it from a known
  worst case. It is a real constraint rather than a theoretical one — an ordinary list where each
  row sits below the previous one is two chain nodes per row. Measured in `:layout`'s
  `ChainDepthTest`; see `layout-design.md` §11.2.
- **Dynamic dependencies.** A conditional that stops reading a node stops depending on it,
  and the reverse edge is removed.
- **Cycle detection.** Absent from Solid entirely (a cyclic Solid graph is a stack
  overflow). Costs one bit test on a word already loaded. Default policy is previous-frame
  relaxation: always terminates, O(1), cannot oscillate within a frame. The full cycle
  membership is reported to a `DiagnosticSink`.
- **Governors.** One node claiming and computing a set of slots, each independently
  observable — Solid's `FirewallSignal` structure. Ownership is exclusive by construction
  (a node has exactly one owner field), so overlapping claims are unrepresentable rather
  than merely forbidden. A governor may read back its own outputs, which is what makes a
  freeze loop expressible; that read records no edge. Every claim must be written on every
  solve, checked afterwards, because an unwritten output is indistinguishable from a
  converged one.
- **Non-scalar nodes.** `refInput` / `deriveRef` / `readRef` / `setRefInput`, for child-list
  identity, text content and resolved fonts. They share every code path with the scalar
  nodes — same flags, same marking, same links, same governor resolution — and differ only in
  which field holds the value and in how equality is decided. That last part is a per-node
  `RefEquality` policy rather than a fixed rule: `EQUALS` for text (strings arrive freshly
  allocated constantly, and under `===` every composition would re-trigger measurement),
  `IDENTITY` for child lists and interned handles, `ALWAYS` as the escape hatch for a payload
  mutated in place. Governors claim scalars only, deliberately.
- **Height maintenance.** Each derived node sits strictly above everything it reads. Unused
  by the pull evaluator, kept because it is nearly free and it is the enabling structure for
  ordered eager execution and for parallel evaluation.
- **`Object.is` equality semantics.** `NaN == NaN` and `-0.0 != 0.0`. The first matters:
  under IEEE comparison a node holding `NaN` would report "changed" every evaluation
  forever, silently.
- **Instrumentation.** `Stats` counts marks, recomputes and link allocations separately —
  marks and recomputes are expected to differ by an order of magnitude and only the second
  benefits from `CHECK`.

## Disposal

`dispose(slot)` destroys a node, recycles its index, and wakes everything that read it. Three
steps in a fixed order: subscribers are marked `DIRTY` (their answers came from something that no
longer exists), every edge is cut in *both* directions (a leftover link would let a recycled node
mark a stranger), and the index goes on the free list with the generation bumped.

Index recycling is what keeps a churning UI bounded — without it a list that replaces its items
every second grows the node array forever. It also means a stale handle could address somebody
else's node, so `Slot` packs a 24-bit index and an 8-bit generation, and a disposed handle raises
a named error rather than returning a plausible wrong number. That is one compare on the read
path, and the honest limit is that the tag wraps after 256 reuses of one index.

Disposing a governor releases its claims; disposing a *claimed output* on its own is refused.
`nodeCount` excludes freed indices, so "churn a list and come back to where you started" is a
one-line assertion.

## Not implemented yet

In rough order of intended work:

1. **The slot layer** — `VaryingDimension`'s four properties over two degrees of freedom.
   Belongs to the **layout** module, not this one: it is pure composition over `read`, and it
   knows about axes and coordinate inversion, which this module deliberately does not. Needs
   no new node types — a read of `end` under an `(ORIGIN, SIZE)` axis links to both slots
   because the two `read` calls happen at the read site, which is where the dependency
   precision comes from.
2. **`reserve` / `define`** — splitting node creation from node definition, so a node can be
   named before its computation exists. Q11 wanted this for two reasons; the first has since
   evaporated. Recomposition does *not* need to re-point a computation in place, because
   reconciliation matches by key and simply **does not re-run** a surviving item's body
   (`layout-design.md` Q10) — the edges are never orphaned because they are never rebuilt. What
   remains is eager slot-handle capture, which nothing needs yet, so the tests still thread a
   `var slot = Slot(-1)` placeholder where they want a forward reference.
3. **The height heap** — ordered eager execution. Only needed if something other than the
   draw walk has to be driven from the graph.
4. **Struct-of-arrays** — a cache-locality optimisation, not a GC one; the evaluator already
   does not churn. `Slot` being a value class over an `Int` keeps the public API stable
   across that rewrite.

## Known limits

- **Single-threaded by construction.** Every entry point asserts the owning thread.
- **A cycle relaxes one step per read, not to convergence.** That is the intended policy —
  it always terminates and cannot oscillate within a frame — but it means a graph left with
  a live cycle never reaches a fixed point on its own. Frame N gets frame N−1's answer. A
  cycle through a *governor* is additionally order-sensitive inside the solve, because the
  governor may already have written some claims before the cyclic read arrives.
- **Recursion depth follows chain length, not tree depth.** The 500-item chain in the tests
  is fine; a 10,000-item list with `top { prev.bottom }` will need either a layout thread
  with an explicit large stack or an explicit stack machine in `updateIfNecessary`.

## A note on `Computation` and boxing

`Computation` is a hand-written `fun interface`, not a `Graph.() -> Double` function type,
and that choice is load-bearing. A Kotlin function type compiles to
`Function1<Graph, Double>`, whose erased `Object` return would box a `java.lang.Double` on
every single recompute. Declaring the interface explicitly keeps the return type concrete,
so it maps to a JVM primitive.

Verified against the emitted bytecode rather than assumed:

```
interface     public abstract double evaluate(Graph)                      // primitive
call site     invokedynamic evaluate:(I)Computation                       // LambdaMetafactory,
                                                                          // no Function1 wrapper
lambda body   private static final double ..$lambda$0(int, Graph)         // primitive; the
                                                                          // captured Slot is an int
invocation    invokeinterface Computation.evaluate:(Graph)D ; dstore      // no boxing
```

`Slot` being a value class over `Int` unboxes the same way — `read` compiles to
`read-MWGscVg:(I)D`.

The one allocation left is the lambda object itself, and only for capturing lambdas
(non-capturing ones are singletons). That happens once per `derive` call at composition
time, never per recompute. If this signature is ever changed, re-check it with
`javap -c`; the failure mode is silent and would show up only as GC pressure under load.


The full register of deferred work — performance ceilings, unbuilt features, known gaps —
is [`docs/deferred.md`](../../docs/deferred.md).

## Running

```
./gradlew :reactivity:test
```
