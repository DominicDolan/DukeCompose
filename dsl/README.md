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

- **Reconciliation.** `compose` builds once. Structural change — a list whose length varies — needs
  keyed matching and a mutable `Element.index` (`layout-design.md` Q10). Values change through
  graph inputs, which is what makes compose-once useful rather than limiting.
- **Flex containers.** `:layout`'s `flexRow` works but has no DSL wrapper yet; §3.3 puts
  `FlexScope` alongside `ColumnScope` and `RowScope`, including installing the governor.
- **Style classes and contextual styling** (§4.1–4.3). `style { }` is per element today.

## Running

```
./gradlew :dsl:test
```
