# `:layout`

The layout engine: axes, the element tree, and the governors that resolve genuine cycles.
Sits on [`:reactivity`](../reactivity/README.md) and knows nothing about the DSL — see
[`docs/layout-design.md`](../../docs/layout-design.md).

The split from the composition module is deliberate and load-bearing: the two go/no-go
validation cases in the plan (§11 steps 3 and 4) have to be buildable **without** a DSL, or
they would be validating the DSL rather than the layout algorithm. This module's tests must
therefore always be able to construct trees programmatically.

## The slot layer

An axis has four names — `origin`, `size`, `end`, `center` — and **two degrees of freedom**.
Write any two, and the other two are derived. That is the piece of DukeUI worth keeping
(`layout-design.md` §2.1), and here it becomes the graph's node granularity.

```kotlin
val panel = g.axis("panel.h") {
    origin { 0.0 }
    size   { read(viewport) / 2.0 }
}

g.endOf(panel)      // 400.0 — arithmetic over the two slots, not a third node
g.centerOf(panel)   // 200.0
```

Three things are going on, all of them consequences of the design docs rather than choices:

**Two nodes per axis, no matter which two properties were written.** `layout-design.md` §4.1:
a node is a *written slot*, not an element and not a property. Reading `end` off an
`(ORIGIN, SIZE)` axis is `read(origin) + read(size)` performed at the read site. There is no
node for `end`, and no cache to invalidate.

**Each written property is its own expression, therefore its own node.** §4.4: a lambda is
atomic to the evaluator, so two properties sharing one lambda would share the union of that
lambda's reads — which is on its own enough to recreate the false cycles that slot
granularity exists to remove. The builder enforces one expression per property.

**Reads touch exactly the slots that feed them.** The 24 cases in `Axis.kt` *are* the
provenance table of §4.2, written as code rather than data, and because the reads happen
inside the caller's tracking context the graph records edges to precisely those slots. This
is the part that prevents false cycles, and it is tested two ways: the table is asserted
case by case, and there is a test showing that a deliberately conservative "reads the whole
axis" variant of the same layout **does** cycle.

## Degrees of freedom are enforced, not resolved

The old `VaryingDimension` discovered which two properties were live at runtime, by shifting
priorities as they were assigned. Here the pair is fixed at composition time — it is simply
which two expressions were registered — which turns two classes of mistake into build errors:

| | Old behaviour | Now |
|---|---|---|
| `origin`, `end` **and** `size` | last two writes win, silently | error naming all three |
| only `size` | missing property defaults to `0.0` | error |
| `size` twice | last write wins | error |

Under-constrained is an error rather than a default on purpose. A silently zeroed origin
produces a plausible-looking layout with the element in the wrong place. Defaults belong in
the DSL, where they are visible policy at the point they are chosen, not buried here.

`Basis` is an enum of the six valid pairs rather than a `Pair<Property, Property>`, so the
other ten combinations are unrepresentable, and the read functions can index the two slots
positionally.

## The element tree

Two independent axes per element, plus enough structure for layout expressions to navigate:

```kotlin
val tree = ElementTree(g)
val root = tree.root("app") {
    h { origin { 0.0 }; size { read(viewportW) } }
    v { origin { 0.0 }; size { read(viewportH) } }

    child("panel") {
        h { origin { leftOf(element.parent!!) }; size { 200.0 } }
        v { origin { 0.0 }; size { childrenOf(element).sumOf { heightOf(it) } } }
    }
}
```

The tree is *not* the dependency graph and bears no structural resemblance to it (§4.3). It
exists so expressions have something to navigate and the draw walk has something to walk.

**Navigation is a tracked read.** `childrenOf`, `prevOf`, `nextOf`, `siblingsOf` and
`countOf` all read the parent's child-list node, so an expression that navigates the tree
depends on the *shape* of the tree and not only on the values it finds there. §8.7 makes this
argument for `children.maxOf { }`; `next` is the sharper case, because inserting a sibling
changes the answer without any number anywhere moving. `Element.children` is the untracked
sibling of `childrenOf` — for the draw walk and tests, never inside an expression.

**Everything resolves at evaluation time, never at registration.** That is what makes a
forward reference work:

```kotlin
child("a") { h { origin { 0.0 }; end { leftOf(nextOf(element)!!) - 8.0 } } }
child("b") { h { origin { 100.0 }; size { 50.0 } } }
```

`next` does not exist when `a`'s expression is registered. Registration does not evaluate, so
by the time it runs the whole tree is built. `dsl-and-engine.md` §2.6 flags this as a
constraint to preserve rather than an accident — resolving handles eagerly at registration
would break it *silently*, capturing `null`.

Since reads are functions on the graph rather than methods on a wrapper, §2.6's other
requirement — that `Layout` views be pooled flyweights — dissolves: `leftOf(parent)` is two
field reads and some arithmetic, with nothing allocated per read and no pool to maintain.

**`query(id)` is a plain lookup against a graph node.** The id→element *binding* is itself a
ref node, so an id that points at a different element next frame invalidates its readers
correctly (§3.5). Duplicate ids are a composition-time error; a missing id is a layout-time
error naming the slot that asked (`no element with id 'nope', queried from root.h.size`),
which is the asymmetry §3.5 predicts — you cannot know which ids get queried until the
expressions run.

**Child lists are published as snapshots**, once per element at the end of its composition.
That is what makes `IDENTITY` comparison correct on the children node: an element whose
structure never changes publishes exactly once, and the recycled-list-mutated-in-place hazard
that `RefEquality.ALWAYS` exists for cannot arise here.

## Flex-grow, without a governor

The design's central claim, and its hardest-looking case. Written naively, flex-grow *is* a
genuine cycle — each growing child computes the leftover pool itself, so `B.width` reads
`C.width` reads `B.width`. Hoisting the pool into a node the container owns dissolves it:

```kotlin
child("row") {
    h { origin { 0.0 }; size { read(viewportW) } }
    v { origin { 0.0 }; size { 40.0 } }
    flexRow {
        fixed(mainSize = { 100.0 }) { origin { 0.0 }; size { 40.0 } }
        grow(1.0)                   { origin { 0.0 }; size { 40.0 } }
        grow(2.0)                   { origin { 0.0 }; size { 40.0 } }
    }
}
```

`freeSpace` reads only the **fixed** items' sizes. Both growing items read `freeSpace`; neither
reads the other. Plain DAG, one evaluation per slot, no iteration, no governor. `FlexTest`
asserts the absent edge directly, and includes the naive formulation asserted to cycle — so
hoisting is shown to fix something rather than claimed to.

Two things the §6.2 sketch does not mention, both found by building it:

- **The fixed/growing test must be structural, not numeric.** Deciding "is this child
  flexible?" by reading its flex factor makes `freeSpace` depend on every flex factor in the
  row, so changing one re-runs the pool to produce the same number. Deciding it by which
  constructor built the item costs a field load and no edge. A predicate over a value creates
  an edge from everything that partitions on it — worth remembering when `min`/`max` arrive.
- **Flex factors are graph nodes, but only where they exist.** `Element.flexSlot` is `null` for
  a fixed item, so the common case allocates nothing, and a flex factor can itself be computed.

The boundary: `freeSpace` reads the container's own main-axis size, so flex-grow inside a
*shrink-to-fit* container is a real cycle — the contested quantity depends on the contestants.
That is `FlexGovernor`'s job, not hoisting's.

## Flex-shrink, with a governor

Min/max clamps are the case hoisting cannot reach, because freezing a clamp-violating item
returns space to the pool and changes everyone else's share — which is why the CSS flexbox spec
has a freeze-and-loop step. One argument switches the row over:

```kotlin
flexRow(clamped = true) {
    fixed(mainSize = { 100.0 })   { … }
    grow(1.0, "a", max = { 20.0 }) { … }
    grow(1.0, "b")                 { … }
}
```

`a` caps at 20 and hands back its share; `b` absorbs it. The two mechanisms differ by one word,
and the cheap one is the default.

The mode is a **composition-time** decision, and `clamped` is explicit rather than inferred from
whether any item declared a clamp. A governed item's size slot has to be created as a plain input
*while the item is being built*, but whether a clamp exists anywhere is not known until every item
is built — so inferring it would mean either deferring the child elements (and `fixed`/`grow`
could then no longer return one) or discovering the governor at layout time, which is what §7.3
exists to avoid.

Four things worth knowing, all of them found by building it:

- **The claim set is the growing items only** — smaller than §7.3's sketch, which claims every
  child. A fixed item's size is not contested, so its size stays an ordinary derived node that the
  governor *reads*. Smaller claim sets also collide less with nested containers.
- **Nested clamped rows work**, because the claim sets are disjoint: the inner container's own size
  is claimed by the outer governor, its children's by the inner one. That is `layout-design.md` Q1
  discharged on real containers rather than on hand-built slots.
- **The freeze loop needs no convergence test and no iteration cap.** Each pass either freezes at
  least one item — so the pool strictly shrinks — or settles every survivor and returns, bounding
  it at *n*+1 passes. Tested at 64 items all clamped: 64 passes, one solve. Contrast §7.4's
  `RelaxGovernor`; this terminates exactly, which is why declared governors are the intended path.
- **`min` wins a contradictory clamp pair.** `coerceIn(80.0, 20.0)` throws, and a layout that dies
  on a bad clamp pair is worse than one that picks. CSS does the same.

Outputs stay fine-grained: an item pinned at its cap across a resize does not wake its readers,
which is the difference between one governor with many outputs and one node computing a tuple.

## What incremental costs

Plan step 6, the last go/no-go point: *incremental cost must be materially below full re-layout,
or the main payoff is absent.* Measured on 500 rows — sidebar plus a shrink-wrapped list, each row
a flex row of icon / label / badge, rows chained through `prev` — 2003 elements, 13520 nodes:

| Frame | Recomputes | vs full |
|---|---|---|
| full re-layout (every slot once) | 9012 | 1.0000× |
| viewport resize | 2502 | 0.2776× |
| text change, last row | 3 | 0.0003× |
| text change, middle row | 999 | 0.1109× |
| text change, same wrapped height | 1 | 0.0001× |
| no-op input write | 0 | — |

The baseline is deliberately the **cold frame** — every slot once, which is what a traditional
measure/arrange engine does every frame — and not a resize, because using a resize would have
flattered the result. The metric is recomputes, not wall time: wall time cannot tell "we re-ran 4
slots" from "we re-ran 8000 slots quickly".

- A leaf change costs **3 recomputes in a 50-row list and 3 in a 500-row list**. Nothing walks
  anything per frame.
- A **resize is only 28% of a full re-layout**, untuned. Two independent axes are two independent
  *nodes*, so a width change leaves every vertical slot alone when the text still wraps the same.
  An engine with one interleaved measure pass cannot express that.
- A mid-list change is **O(rows below it)**, and should be: row *n*'s position depends on row
  *n−1*'s height. 999 ≈ 4 slots × 250 rows — the chain itself, no overhead on top.
- **Equality beats marking.** A different short label costs **1** recompute, the measurement
  itself, because the height compares equal and the verdict stops there.

The caveat the same test reports: **reading every rect is O(elements) whatever changed.** Eight
thousand settle calls on a frame that recomputes three slots, so a full read walk hides the win —
0.0959 ms with a full walk against 0.0004 ms reading one row. That is an argument about the draw
side, not the layout side: the backend wants a dirty list, not a tree walk.

## What it costs at scale

Plan step 7 asked whether the node store reaches frame budget at 10k and 40k elements, and proposed
a struct-of-arrays rewrite. `ScaleTest` measures first, because the benchmark is the half that
decides — and the measurement says **don't do the rewrite yet**. Two scene shapes at each size:
*chained* (each row positioned from the previous one, chain depth O(rows)) and *indexed* (positioned
by index against a uniform pitch, chain depth 1 — what a virtualised list produces).

| elements | shape | nodes | chain | heap | full | resize | walk | leaf |
|---|---|---|---|---|---|---|---|---|
| 2 000 | chained | 13 493 | 504 | 3.6 MB | 1.1 | 0.46 | 0.10 | 0.002 |
| 10 000 | chained | 67 493 | 2 504 | 18.2 MB | 6.1 | 3.11 | 0.49 | 0.003 |
| 10 000 | indexed | 67 493 | 1 | 18.0 MB | 4.1 | 1.92 | 0.65 | 0.002 |
| 40 000 | chained | 269 993 | 10 004 | 73.9 MB | 23.7 | 21.9 | 5.26 | 0.002 |
| 40 000 | indexed | 269 993 | 1 | 73.0 MB | 15.6 | 11.9 | 5.70 | 0.002 |

ms, one machine, ±20–30%. Budget is 16.67 at 60 Hz. **A typical frame is 2 recomputes and ~2 µs at
every size** — 0.03% of a 144 Hz frame at 40 000 elements.

**Per-recompute cost is flat across a 20× scene** (~90–135 ns at every size), so there is no cache
cliff between 13k and 270k nodes and the array-of-objects store is not what misses budget. Struct of
arrays would buy a constant factor and most of that 74 MB; it would not fix anything below.

What does miss budget, in order of how much it matters:

- **Recursion depth is a hard ceiling.** The evaluator is recursive by design — the call stack *is*
  the topological order — so depth equals the longest dependency chain, at ~128–145 bytes of stack
  per link. A default JVM stack manages ~3 900 links ≈ **2 000 chained rows, then
  `StackOverflowError`.** 8 MB buys 64 000. Depth is linear in stack size, so a framework that owns
  its layout thread can compute the requirement — but it has to be a stated constraint, not
  something a list view discovers. `ChainDepthTest` measures it.
- **The read walk is O(elements) whatever changed** — 5.3 ms at 40k, 32% of a 60 Hz frame spent
  asking questions whose answers did not change. The largest avoidable cost in the system, and it
  belongs to the draw side.
- **Marking is not free at scale.** A 40k resize is 50 000 recomputes plus **90 000 marks**, and
  costs 2.5× per recompute what a full layout does. Chain depth makes it 1.8× worse again.
- **281 bytes per node**, partly incidental: every node carries a `String` name for diagnostics.

Comfortably in budget to ~10 000 elements either shape. 40 000 exceeds a 60 Hz frame on full
invalidation — but 40 000 *live* elements means no virtualisation, and virtualisation produces the
indexed shape, which is also the cheap one. So: virtualisation before struct-of-arrays, and the
chain-depth ceiling before either.

## Text and intrinsic sizing

`TextMetrics` is supplied by the *host*, not the backend: layout needs measurement but not
drawing, which is what lets compose + layout run with no renderer at all (§6.1). The default is
`HeadlessTextMetrics` — `charWidth = 0.6 × size`, `lineHeight = 1.2 × size`, greedy word wrap —
deliberately fake, because real font rasterisation varies across platforms and would make golden
files worthless.

`Content` is two ref nodes, text and font. There is **no measured-size node**: `heightAt(width)`
measures at the read site, and the asking slot memoises it, so measurement re-runs exactly when
the text, the font or that width changes. Text uses `RefEquality.EQUALS`, which is the difference
between re-measuring on every composition and not.

Text wrapping (§8.3) then needs no special case:

```kotlin
child("label") {
    text("some long label", font)
    h { origin { leftOf(element.parent!!) }; end { rightOf(element.parent!!) } }
    v { origin { 0.0 }; size { contentHeight(element) } }
}
```

Horizontal edges flow *down* from the parent; vertical size flows *up* from the text, on the
other axis, in the opposite direction. CSS and Flutter hardcode "constraints down, sizes up" into
their traversal order — here it is just what the edges are. The test asserts exactly that: the
label's **vertical** size slot has an edge to its **horizontal** size slot.

## The JSON path

`tree.dumpJson()` serialises phase-2 output. Not a backend — for tests there is no renderer
(§8), and reading a rect evaluates it, so dumping *is* the layout driver.

`includeDerivation = true` adds where each written slot's value came from:

```json
"derivation": {
  "h.origin": ["app.children", "app.sidebar.h.origin", "app.sidebar.h.size"],
  "h.size":   ["app.totalFlex", "app.freeSpace", "app.content.flex"]
}
```

That supports the assertion §8.1 is really about — not "the box is at x=12" but "the box's left is
*derived from its parent*, not hardcoded", which catches layouts that are right at one viewport
size and wrong at another. An **empty** list means the value is a literal. Keys name the *written*
properties, so an axis written as `(SIZE, CENTER)` has no `h.origin` entry at all — there is no
node for it.

Expression text and `@Screen.kt:41` locations need source capture (Q6) and are not built; they
slot in beside these lists when they arrive.

`x` / `y` are the **low** corner in graph coordinates, orientation-independent, which is what makes
the dump a stable golden-file target. `NaN` and infinities are emitted as JSON strings — invalid
as numbers, and a `NaN` in layout output is a bug you want to see.

## Style, and the draw walk

Phase 3. `dsl-and-engine.md` §4.5 and §6.1.

```kotlin
class GameStyle { var color = 0; var corner = 0.0 }
fun ElementBuilder.style(styler: GameStyle.() -> Unit) = style(GameStyle(), styler)

tree.root("app") {
    style { color = if (g.read(hovered) > 0.0) HOT else COLD }
    h { origin { 0.0 }; size { 800.0 } }
    v { origin { 0.0 }; size { 600.0 } }
}

tree.draw(host, backend, drawer)
```

**The style type belongs to the application**, because platforms differ in capability and a fixed
framework style class must take either the union (properties that silently no-op) or the
intersection (capabilities unreachable). Nothing here inspects the payload.

**The whole styler is one node**, against the slot-per-property granularity layout insists on. The
asymmetry is the point: fine granularity is mandatory for layout because coarse granularity there
produces *false cycles*, a correctness failure. Style has no cycles, because **style is a sink —
nothing reads it**, so coarse granularity costs only redundant work, and the work is a few field
writes into an object that is never reallocated. `RenderTest` asserts both halves of that claim: the
style node has dependencies and no subscribers, and no layout slot depends on it. Its *inputs* are
tracked normally, so a styler reading hover state invalidates correctly.

The node is `RefEquality.ALWAYS` — the styler mutates its target in place and returns the same
instance, so neither `===` nor `==` could see a change. The verdict costs nothing, a sink having
nobody to propagate to.

Style is generic **per call**, not a type parameter on `ElementTree` / `ElementBuilder` /
`FlexScope`. §4.5 proposes threading `S` through every scope type and lists the propagation as its
cost; what that buys is compile-time capability checking (`where S : HasBlur`), which only pays once
there is more than one backend to be wrong about. Storage is opaque either way, so promoting `S`
later changes signatures and not the graph. Until then **a tree is not checked for using one style
type throughout**, and mixing two is a `ClassCastException` at draw rather than a compile error.

The walk itself is not reactive — it pulls, records no edges, and reading geometry *evaluates* it,
so `draw` doubles as the layout driver exactly as `dumpJson` does. There is no separate layout pass
to forget. Three things it does deliberately:

- **The renderer is a parameter, not engine state.** Iteration C carried a mutable nullable
  `drawer` field and re-checked it on every element; passing it to `draw` deletes the check.
- **`NodeView` is one reused instance**, valid only inside the callback that receives it. A game
  loop runs this every frame and allocating a view per element per frame is exactly the steady-state
  garbage the rest of this codebase avoids. The test that asserts this caught the first draft of
  another test reading a view after the walk.
- **Geometry arrives in window coordinates.** The host's `Window`/`Viewport` scale-and-offset stays
  inside the engine, so a backend never sees viewport coordinates. Either viewport extent may be
  negative, and that is the mechanism rather than an edge case: a y-up host passes a negative
  viewport height and gets layout's y-down convention mapped for it, with no flag anywhere in this
  module. Extents come back signed; `minX`/`maxX`/`minY`/`maxY` are the orientation-free way to ask.

Dispatch is per element *kind* — `drawBox` / `drawText` / `drawCustom` — because text needs
materially different treatment from a box.

## What is not here yet

- **Flex-shrink inside a shrink-to-fit container.** `clamped = true` reads the container's own
  main-axis size as an outer input, so a clamped row whose container is sized *by these children*
  is still a genuine cycle. Closing it means letting the governor claim the container's size too —
  the mechanism allows it, no container drives it yet.
- **Reconciliation.** `Element.index` is assigned at composition and never updated, so
  removal and reordering are not supported. Incremental composition also needs `reserve`/
  `define` in `:reactivity` so an expression can be re-pointed without orphaning its edges
  (`layout-design.md` Q10, Q11).

All three of the plan's go/no-go points — steps 3, 4 and 6 — now pass. Step 3 is covered by
`ElementTreeTest`: a 500-row list chained through `prev`, and a shrink-wrap panel
whose children align to it. The panel test also asserts the dependency sets, since "it gives
the right answer" and "it is acyclic for the right reason" are different claims.

## Running

```
./gradlew :layout:test
```
