package com.dubulduke.layout

/**
 * Confines each builder block to its own receiver.
 *
 * `dsl-and-engine.md` §2.5 calls this mandatory and notes it was absent from all three prior
 * iterations: with nested scopes, its absence *silently* permits capturing the wrong receiver.
 * That is not hypothetical — the first version of the flex JSON test called `classes(...)` inside
 * an axis block, which compiled, resolved to the enclosing element, and attached the classes to
 * the wrong element. The layout was still correct, so only the serialised output revealed it.
 *
 * With the marker in place the same mistake is a compile error.
 */
@DslMarker
annotation class LayoutDsl
