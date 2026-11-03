package com.dubulduke.dsl

import com.dubulduke.layout.FocusCommand

/**
 * This frame's input, as the platform sees it.
 *
 * Pulled rather than pushed: [DukeApp.update] asks once per frame, which keeps the sampling instant
 * in one place and lets a platform supply its mouse the same way it supplies its window and its
 * renderer.
 */
interface DukeInput {
    fun isClickDown(): Boolean
    fun isRightClickDown(): Boolean

    val pointerX: Double
    val pointerY: Double

    val scrollDistance: Double

    /**
     * A focus-navigation command pressed this frame, or `null` — which is almost every frame.
     *
     * Already interpreted, deliberately: **which key means "next" is a platform question**, and one
     * platforms genuinely disagree about — tab on a keyboard, a shoulder button on a pad, both on a
     * handheld. Keeping key codes out of the framework is the same choice as `Pointer` taking
     * coordinates rather than mouse buttons, and it is what lets a gamepad drive the same UI with
     * no framework change at all.
     *
     * One command per frame rather than a list. Real input produces at most one navigation press
     * per frame, and a collection would allocate on every frame to express something that is null
     * on nearly all of them.
     *
     * Defaulted, so a platform with no keyboard — and every existing test double — needs no change.
     */
    val navigation: FocusCommand? get() = null

    /** Editing events for this frame, ordered as received. Empty when there are none. */
    val textEdits: List<TextEdit> get() = emptyList()
}