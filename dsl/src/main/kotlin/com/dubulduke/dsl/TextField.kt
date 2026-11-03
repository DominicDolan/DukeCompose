package com.dubulduke.dsl

import com.dubulduke.layout.Element
import com.dubulduke.layout.FontRef
import com.dubulduke.layout.leftOf
import com.dubulduke.layout.widthOf

/** Platform-interpreted editing events, in the order they occurred during the frame. */
sealed interface TextEdit {
    data class Insert(val text: String) : TextEdit
    data class Left(val select: Boolean = false) : TextEdit
    data class Right(val select: Boolean = false) : TextEdit
    data class Home(val select: Boolean = false) : TextEdit
    data class End(val select: Boolean = false) : TextEdit
    data object Backspace : TextEdit
    data object Delete : TextEdit
    data object SelectAll : TextEdit
    data object Submit : TextEdit
    data object Cancel : TextEdit
}

/** Single-line editable text. Positions are UTF-16 offsets at Unicode code point boundaries. */
class TextFieldState internal constructor(internal val app: DukeApp<*, *>, initial: String) {
    private val content = app.refSignal(initial)
    private val caretSignal = app.signal(initial.length.toDouble())
    private val anchorSignal = app.signal(initial.length.toDouble())

    var text: String
        get() = content.value
        set(value) {
            require('\n' !in value && '\r' !in value) { "Text fields are single-line" }
            content.value = value
            setSelection(value.length, value.length)
        }
    val caret: Int get() = caretSignal.value.toInt()
    val anchor: Int get() = anchorSignal.value.toInt()
    val selectionStart: Int get() = minOf(caret, anchor)
    val selectionEnd: Int get() = maxOf(caret, anchor)

    init { require('\n' !in initial && '\r' !in initial) { "Text fields are single-line" } }

    /** Set the selection using UTF-16 offsets, without splitting a surrogate pair. */
    fun setSelection(anchor: Int, caret: Int) {
        fun boundary(i: Int): Int {
            val p = i.coerceIn(0, text.length)
            return if (p > 0 && p < text.length && text[p].isLowSurrogate() && text[p - 1].isHighSurrogate()) p - 1 else p
        }
        anchorSignal.value = boundary(anchor).toDouble()
        caretSignal.value = boundary(caret).toDouble()
    }

    private fun previous(i: Int): Int = if (i <= 0) 0 else if (i >= 2 && text[i - 1].isLowSurrogate() && text[i - 2].isHighSurrogate()) i - 2 else i - 1
    private fun next(i: Int): Int = if (i >= text.length) text.length else if (i + 1 < text.length && text[i].isHighSurrogate() && text[i + 1].isLowSurrogate()) i + 2 else i + 1
    private fun move(to: Int, select: Boolean) = setSelection(if (select) anchor else to, to)
    private fun replace(start: Int, end: Int, replacement: String) {
        content.value = text.substring(0, start) + replacement + text.substring(end)
        setSelection(start + replacement.length, start + replacement.length)
    }

    fun edit(event: TextEdit) {
        when (event) {
            is TextEdit.Insert -> {
                val inserted = event.text.filter { it != '\n' && it != '\r' }
                if (inserted.isNotEmpty()) replace(selectionStart, selectionEnd, inserted)
            }
            is TextEdit.Left -> move(if (!event.select && caret != anchor) selectionStart else previous(caret), event.select)
            is TextEdit.Right -> move(if (!event.select && caret != anchor) selectionEnd else next(caret), event.select)
            is TextEdit.Home -> move(0, event.select)
            is TextEdit.End -> move(text.length, event.select)
            TextEdit.Backspace -> if (caret != anchor) replace(selectionStart, selectionEnd, "") else replace(previous(caret), caret, "")
            TextEdit.Delete -> if (caret != anchor) replace(selectionStart, selectionEnd, "") else replace(caret, next(caret), "")
            TextEdit.SelectAll -> setSelection(0, text.length)
            TextEdit.Submit, TextEdit.Cancel -> Unit
        }
    }

    /** Horizontal position of an offset, in the same units as layout. */
    fun position(font: FontRef, offset: Int = caret): Double =
        app.textMetrics.measure(text.substring(0, offset.coerceIn(0, text.length)), font, Double.POSITIVE_INFINITY).width

    /** Geometry in local layout coordinates, including the text offset needed to reveal the caret. */
    fun geometry(font: FontRef, width: Double): TextFieldGeometry {
        val w = width.coerceAtLeast(0.0)
        val caretX = position(font)
        val scroll = (caretX - w).coerceAtLeast(0.0)
        return TextFieldGeometry(
            textOffset = -scroll,
            caret = (caretX - scroll).coerceIn(0.0, w),
            selectionStart = (position(font, selectionStart) - scroll).coerceIn(0.0, w),
            selectionEnd = (position(font, selectionEnd) - scroll).coerceIn(0.0, w),
        )
    }

    internal fun closest(font: FontRef, x: Double): Int {
        var best = 0
        var distance = kotlin.math.abs(x)
        var i = 0
        while (i < text.length) {
            i = next(i)
            val d = kotlin.math.abs(position(font, i) - x)
            if (d < distance) { best = i; distance = d }
        }
        return best
    }
}

/** Horizontal field geometry, relative to its left edge. Clip text to the field bounds. */
data class TextFieldGeometry(val textOffset: Double, val caret: Double, val selectionStart: Double, val selectionEnd: Double)

/** Create a single-line text field state owned by this app's graph. */
fun DukeApp<*, *>.textFieldState(initial: String = ""): TextFieldState = TextFieldState(this, initial)

/**
 * Turn this element into a single-line editable field. Configure its layout and style normally.
 * The backend/application can draw a caret and selection using [TextFieldState.position] and its
 * tracked selection properties. Pointer dragging selects text, including outside the element.
 */
fun <S : Any> ElementScope<S>.textField(
    app: DukeApp<*, *>, state: TextFieldState, font: FontRef,
    onSubmit: () -> Unit = {}, onCancel: () -> Unit = {}, onFocusChanged: (Boolean) -> Unit = {},
) {
    require(graph === app.graph) { "The text field must belong to this app" }
    require(state.belongsTo(app)) { "The text field state must belong to this app" }
    text(font) { state.text }
    focusable()
    app.registerTextField(element, state, font, onSubmit, onCancel, onFocusChanged)
    var dragging = false
    onDrag { x, _ ->
        val offset = state.closest(font, x - graph.leftOf(element) - state.geometry(font, graph.widthOf(element)).textOffset)
        if (!dragging) {
            app.focus.focus(element)
            state.setSelection(offset, offset)
            dragging = true
        } else state.setSelection(state.anchor, offset)
    }
    app.registerTextFieldRelease(element) { dragging = false }
}

private fun TextFieldState.belongsTo(app: DukeApp<*, *>): Boolean = this.app === app
