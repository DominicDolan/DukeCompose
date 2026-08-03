package com.dubulduke.dsl

/**
 * A [DukeInput] a test drives directly.
 *
 * [DukeApp] now pulls input from a platform interface rather than taking it as arguments, which is
 * the right shape — a platform supplies its mouse the same way it supplies its window and its
 * renderer — but it means a test has to supply one too. This is that, plus the two conveniences
 * every test in this module wanted: set the state and run a frame, and run enough frames for the
 * wheel easing to finish.
 */
class TestInput : DukeInput {
    var x: Double = 0.0
    var y: Double = 0.0
    var down: Boolean = false
    var rightDown: Boolean = false

    /**
     * Consumed on read, like a real per-frame wheel delta.
     *
     * Mechanica's `MouseHandler.scrollY` is reset every frame, so a test that left this latched
     * would scroll forever and pass for the wrong reason.
     */
    var wheel: Double = 0.0

    override fun isClickDown(): Boolean = down

    override fun isRightClickDown(): Boolean = rightDown

    override val pointerX: Double get() = x
    override val pointerY: Double get() = y

    override val scrollDistance: Double
        get() = wheel.also { wheel = 0.0 }

    /** A navigation command for the next frame. Tests set it, press, and clear it. */
    override var navigation: com.dubulduke.layout.FocusCommand? = null
}

/** The frame length these tests use. Nothing depends on the value; it just has to be consistent. */
const val FRAME: Double = 1.0 / 60.0

/** Point the pointer somewhere and run one frame. */
fun DukeApp<*, *>.frame(
    x: Double = -1e6,
    y: Double = -1e6,
    pressed: Boolean = false,
    wheel: Double = 0.0,
    delta: Double = FRAME,
) {
    val input = this.input as TestInput
    input.x = x
    input.y = y
    input.down = pressed
    if (wheel != 0.0) input.wheel = wheel
    update(delta)
}

/**
 * Run a frame and then let any wheel easing play out completely.
 *
 * Twenty ordinary frames first, so the accumulation is genuinely exercised, then one frame long
 * enough that the fraction clamps to 1 and delivers whatever is left. The long frame is not
 * cosmetic: exponential decay approaches zero without arriving, so stepping alone leaves a
 * fraction of a percent outstanding and any exact assertion downstream would be flaky.
 */
fun DukeApp<*, *>.frameSettled(
    x: Double = -1e6,
    y: Double = -1e6,
    pressed: Boolean = false,
    wheel: Double = 0.0,
) {
    frame(x, y, pressed, wheel)
    repeat(20) { frame(x, y, pressed) }
    frame(x, y, pressed, delta = 1.0)
}
