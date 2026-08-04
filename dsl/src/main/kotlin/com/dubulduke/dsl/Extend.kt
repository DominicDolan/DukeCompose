package com.dubulduke.dsl

import com.dubulduke.layout.Direction
import com.dubulduke.layout.ElementBuilder

/**
 * The seam for container mechanisms written outside this module.
 *
 * `dsl-and-engine.md` §3.3's claim is that the shipped containers are not privileged — a `column`
 * is nothing more than [ElementScope.childDefaults] being set, and an application whose menus stack
 * down a *slanted* edge writes its own. That holds for the defaults half without any help. It does
 * not hold for the other half: a mechanism that hands children to `:layout` itself, as `flexRow`
 * does, has to build the child's [ElementScope] rather than let [ElementScope.child] build it, and
 * that constructor cannot be public because the ambient environment it takes is internal.
 *
 * [buildChild] is that step, and it is the whole of what `:utils`'s flex row needs from here.
 *
 * @param builder the child `:layout` is already building — from `FlexScope.fixed`, say, which
 *   creates the element and hands its builder to a block.
 * @param defaults what the mechanism wants for this child, applied before its own `layout { }` and
 *   yielding to it per slot, exactly as a container's defaults do (§2.4).
 * @param reserved an axis the mechanism owns outright, so that the child writing it is a named
 *   error and a *default* aimed at it is dropped rather than fighting the mechanism.
 * @return the child's [LayoutScope], for an expression that has to be evaluated later — a flex
 *   item's main-axis size is registered with `:layout` before the item exists, so it needs
 *   somewhere to resolve `parent.width` against once the item does.
 */
fun <S : Any> ElementScope<S>.buildChild(
    builder: ElementBuilder,
    defaults: (LayoutScope.() -> Unit)? = null,
    reserved: Direction? = null,
    body: ElementScope<S>.() -> Unit,
): LayoutScope {
    // The ambient environment comes along, so an item of a flex row sees what a child of an
    // ordinary container in the same position would. Nothing about being laid out by a mechanism
    // should change what `ambient(Theme)` answers.
    val scope = ElementScope(builder, styles, defaults, reserved, ambients)
    val layout = scope.layoutScope()
    scope.body()
    scope.finish()
    return layout
}
