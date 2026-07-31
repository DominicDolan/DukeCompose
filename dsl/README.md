# `:dsl`

The authoring surface. Sits on [`:layout`](../layout/README.md), which knows nothing about it —
see [`docs/dsl-and-engine.md`](../../docs/dsl-and-engine.md) §2–3.

```kotlin
tree.compose(StyleFactory { GameStyle() }) {
    layout { left { 0.0 }; width { read(viewportW) }; top { 0.0 }; height { read(viewportH) } }

    column(gap = 8.0, id = "list") {
        style { colour = PANEL }
        layout { fillWidth(16.0); top { 16.0 }; height { children.last.bottom - top + 12.0 } }

        for (item in items) {
            row(gap = 8.0) {
                onClick { select(item) }
                layout { height { children.maxOf { it.height } + 20.0 } }

                chip(28.0)
                label(item.text, font) { layout { right { parent.right - 12.0 } } }
            }
        }
    }
}
```

## Assignments are buffered

A `layout { }` block records expressions into a slot table; the element commits at the end of its
block. That is not bookkeeping — it is the mechanism that makes container defaults work.

`:layout` deliberately refuses to let a property be written twice, so committing eagerly would make
a default and an override a hard error. Buffering instead means the container stamps `left`, `right`
and `top` when the scope is created, the child's own block runs afterwards, and **overriding one
slot is ordinary last-write-wins**. No strength system, no merge step, no ordering subtleties — and
a child that sets only `height` keeps the other three.

## Defaults yield to the author

Last-write-wins only covers a child overriding *the same* slot. The commoner case is a child
setting a **different** slot in the same axis: `row` stamps `top` and `bottom` to fill its height,
and a child states its own `height`. That is three assignments, of which only one was written by the
person laying the screen out — so the container's yield.

Which one yields is not a matter of taste: the **later-declared default** goes first. `row` declares
`top` then `bottom`, so `top` survives and the child sits at the top of the row rather than the
bottom; `column` declares `left` then `right`, so a child with its own width stays left-aligned.
Deterministic, and it keeps the part of the container's intent that was stated first.

That is the entire precedence system — two levels, no strength arithmetic. It is also the *only*
automatic resolution: once everything remaining is explicit, there is no principled winner, and
three explicit assignments stay an error. `layout-design.md` §2.1 is the reason — DukeUI resolved
that silently with last-two-writes-win, and turning it into a build error was one of the two
behaviours this design set out to fix.

The two-degrees-of-freedom check therefore happens here, after defaults are folded in and yielded,
which is what lets the error name the real conflict rather than whichever assignment arrived third:

```
Element(app.a) over-constrains its horizontal axis: left, right, width are all set
explicitly, but an axis has two degrees of freedom. Container defaults yield to an
element's own block automatically; these do not, because nothing distinguishes them.
Remove one.
```

Under-constrained is an error too, for the reason §2.1 gives: a slot defaulted to zero produces a
plausible-looking layout with the element in the wrong place.

## Flex rows

```kotlin
flexRow(gap = 8.0) {
    layout { fillWidth(12.0); height { 48.0 } }
    style { colour = ROW }

    fixed({ 28.0 })                       // an icon, its own width
    grow(1.0) { text("one share", font) } // and these split what is left, 1:2
    grow(2.0) { text("two shares", font) }
}
```

An item's main axis is **reserved**, not merely defaulted: both its position and its size belong to
the row, so its `layout { }` block supplies the cross axis only and `width { }` inside a horizontal
row's item is an error naming the row. That's a third precedence level above the two above, and a
different kind — not "the container yields to the author" but "the author has no say here". Defaults
aimed at a reserved axis are dropped silently, so a `flexRow` nests inside a `column` without the
column's `left`/`right` stamping fighting it.

The cross axis *is* an ordinary default (`top` + `bottom`, so an item fills the row), which has one
consequence worth stating: **a row's cross size must not come from its items.** `height { children.maxOf { it.height } }`
on a horizontal flex row is a real self-cycle, because the children's heights come from the row's.
The same is true of the main axis for a different reason — the pool reads the container's own
main-axis size, so a flex row inside a shrink-to-fit container is `layout-design.md` §7.3's governed
case, not this one.

`gap` is charged to the pool as fixed-size spacer *elements*. The row owns each item's main-axis
origin and chains it off the previous item's end, so there is nowhere else for a gap to live.

`clamped = true` switches to the governed solve, which `min`/`max` on an item require, and it is an
explicit argument rather than something inferred: a governed item's size slot has to be created as a
claimable input *while the item is being built*, and whether any clamp exists is not known until
every item has been.

## Structural change — `forEach`

```kotlin
column(gap = 8.0, id = "list") {
    layout { fillWidth(12.0); height { children.last.bottom - top + 12.0 } }

    forEach({ model.messages }, key = { it.id }) { item ->
        stackAcross(gap = 8.0)                     // the item *is* the row
        layout { height { 40.0 } }
        label({ item.value.title }, font)          // note the lambda
    }
}
```

`compose` still runs **once**. A `forEach` region is the only place composition may run again, and
only for the items whose keys actually moved. `layout-design.md` Q10 left three options open; this
is the third. Per-frame composition — DukeUI's answer — re-registers every slot expression on every
element whether or not anything moved, which is O(tree) every frame in a design whose premise is
O(changed). Everything else compose-once needs was already paid for: stylers are retained lambdas,
click handlers are retained, and text can be a derived node. Structure was the last thing left.

**A surviving key is not rebuilt.** Its subtree, its memoised values and its dependency edges all
persist. That is the point of matching by key rather than by position: inserting at the head of a
positionally-matched list re-associates every element with the wrong data and discards everything
below it.

**And so its body does not re-run** — the one sharp edge. Anything about the item that can change
must be read through `item.value` *inside an expression*: `label(item.value.title, font)` freezes the
title at build time, `label({ item.value.title }, font)` tracks it. Same rule as everywhere else in
this design, applied per item instead of per frame.

A `forEach` item is an element you did not create, so `row(gap) { }` — which makes a *child* a
container — is the wrong tool: it would wrap every item in a second element. `stackAcross(gap)` and
`stackDown(gap)` apply the same defaults to the current element instead, and `row`/`column` are now
literally `child { stackAcross(gap); … }`.

An element's children come either from its own block or from `forEach`, never both. The list lambda
runs once per frame, so it should return the same instance when nothing changed — that hits an
identity check before a single key is examined. A frame where nothing changed costs zero recomputes
and zero marks; there is a test asserting exactly that over 100 reconciles.

Underneath, `:reactivity` gained `dispose`, which recycles node indices — otherwise a churning list
grows the graph forever. Recycling means a stale `Slot` could address somebody else's node, so
`Slot` now packs an 8-bit generation and a disposed handle fails loudly instead of returning a
plausible wrong number.

## Signals — no `graph.read` at the call site

```kotlin
val zoom = ui.signal(1.0, "zoom")

layout { width { 200.0 * zoom.value } }   // reading here records the dependency
zoom.value = 1.5                          // writing invalidates exactly that width
```

A two-line wrapper over `graph.input` / `graph.read`. The mechanics are unchanged — reading inside
an expression is tracked, reading outside one is a plain fetch, and writing an unchanged value costs
a comparison and marks nothing. It exists because an authoring surface should not make people spell
out `g.read(slot)`. Hosts expose their own the same way: `ui.viewportWidth` is a plain `Double`
getter that reads through the graph.

The one subtlety the wrapper hides is worth knowing: **tracking is dynamic, not lexical.**
`zoom.value` records an edge because something is *currently evaluating*, not because of where the
read appears. Read it directly in a `layout { }` block rather than inside a slot lambda and it runs
at composition time, records nothing, and captures a stale number. That is why expressions are
lambdas, and it is the one rule this convenience does not remove.

`refSignal` is the same for non-numbers, defaulting to `EQUALS` comparison — a caption rebuilt by
interpolation is a fresh string with equal content, and under identity every rebuild would
re-trigger measurement, the most expensive input the layout graph has.

## `DukeApp` — one class per platform

```kotlin
class MyUI : DukeApp<MyStyle, MyRenderer>(StyleFactory { MyStyle() }, MyTextMetrics()) {
    override val window get() = Window(0.0, 0.0, screenW, screenH)
    override val viewport get() = Viewport(0.0, 0.0, screenW, screenH)

    override fun drawBox(node: NodeView<MyStyle>, renderer: MyRenderer) { … }
    override fun drawText(node: NodeView<MyStyle>, text: String, renderer: MyRenderer) { … }
}
```

Four members. `compose` / `update` / `draw`, the graph, the element tree, the pointer, `signal`, and
`viewportWidth` / `viewportHeight` — already tracking `viewport`, magnitude only, so a y-up host's
negative extent never reaches a `height { }` expression — all come with it.

The reasoning is that an integration is four things, of which only two contain a platform-specific
decision. Making each platform assemble the graph and the tree is asking it to repeat work it cannot
get interestingly right or wrong.

`DukeHost` and `DukeBackend` stay separate interfaces in `:layout`, and that is not tidiness:
`DukeHost` is what `tree.draw(host, …)` *takes*, so a host that owned a tree would be circular, and
`:layout`'s tests build trees with no host at all — the property that lets layout be tested without
a renderer. `DukeApp` merely implements both, because a platform that supplies one nearly always
supplies the other. Anything that wants them apart still calls `tree.draw(host, backend, renderer)`.

`compose` syncs the viewport before running the body, so a size read *at composition time* — rather
than inside a slot lambda — sees the real number rather than the zero the inputs were created with.

## Style is a type parameter

`ElementScope<S>` carries the application's style type. The point is not tidiness — it is that
**platforms differ in capability**, so a fixed framework style class must take either the union
(properties that silently no-op) or the intersection (capabilities unreachable).

Capabilities are interfaces, so the layering has three levels and each is checked:

```kotlin
// the host declares what it can draw
open class MechanicaStyle : HasBackground, HasText

// the application extends it
class NeonStyle : MechanicaStyle(), HasGlow { override var glowLevel = 0.0 }

// and a component constrains itself to what it actually uses
fun <S> ElementScope<S>.neonPanel(body: ElementScope<S>.() -> Unit)
    where S : Any, S : HasBackground, S : HasGlow { … }
```

`neonPanel` will not compile against a style without glow — a compile error rather than a blank
rectangle. Backends declare the same bounds symmetrically, so a backend that cannot draw a
capability cannot be paired with a style that has it.

**`:layout` stays non-generic underneath**, holding the style as an opaque payload. That is not a
shortcut: nothing in layout may read a style, because a style object cannot have slot identity, so
anything layout read from one would be invisible to invalidation. `S` is an authoring concern, so
it lives in the authoring layer — and `:layout`'s tests never had to hear about it.

## The scope is flat, and that is what makes `@DslMarker` free

`@DukeDsl` is mandatory: with nested element scopes, its absence silently permits capturing the
wrong receiver — a mistake already made once in this codebase, where `classes("panel")` inside an
*axis* block attached to the wrong element and produced numerically correct layout.

But the marker blocks *legitimate* outer access too, and expressions routinely need `query`,
`content` and `index`. So `LayoutScope` is one flat type holding both the write methods and the read
properties, rather than a nest of builders. Flat is not merely nicer to read; it is what stops the
marker costing anything.

Reads of other elements are **member extensions** on `LayoutScope` — `parent.left`, `it.bottom` —
so `Layout` can be a value class over `Element` and `parent.left` allocates nothing. §2.6's
pooled-flyweight problem does not get solved so much as fail to arise.

`LayoutExpression` is a hand-written `fun interface`, not `LayoutScope.() -> Double`, for the same
reason `:reactivity`'s `Computation` is: the function type would box a `Double` on every recompute,
silently.

## Orientation

The DSL is **y-down**: `top { 0.0 }` is the top of the screen and a list stacks downwards by adding.
That is `startIsLow = false` on the vertical axis underneath. `:layout` supports either orientation
and stays neutral; an authoring surface where `top` sometimes means the bottom would not be
defensible, so this layer picks one and says so.

## What is not here yet

- **Style classes and contextual styling** (§4.1–4.3). `style { }` is per element today.

## Running

```
./gradlew :dsl:test
```
