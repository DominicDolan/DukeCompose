package com.dubulduke.dsl

import com.dubulduke.layout.*
import kotlin.test.*

class TextFieldTest {
    private class App : DukeApp<Any, Unit>(StyleFactory { Any() }, HeadlessTextMetrics(1.0, 1.0)) {
        override val input = TestInput()
        override val window get() = Window(0.0, 0.0, 200.0, 100.0)
        override val viewport get() = Viewport(0.0, 0.0, 200.0, 100.0)
        override fun drawBox(node: NodeView<Any>, renderer: Unit) {}
        override fun drawText(node: NodeView<Any>, text: String, renderer: Unit) {}
    }
    private val font = SimpleFont("test", 10.0)

    @Test fun `editing respects selection and code point boundaries`() {
        val state = App().textFieldState("a😀b")
        state.edit(TextEdit.Left())
        state.edit(TextEdit.Left(select = true))
        assertEquals(1, state.selectionStart)
        assertEquals(3, state.selectionEnd)
        state.edit(TextEdit.Insert("X"))
        assertEquals("aXb", state.text)
        state.edit(TextEdit.Backspace)
        assertEquals("ab", state.text)
        state.edit(TextEdit.Delete)
        assertEquals("a", state.text)
        state.text = "😀"
        state.edit(TextEdit.Backspace)
        assertEquals("", state.text)
    }

    @Test fun `pointer selects and events go only to focused field`() {
        val app = App()
        val state = app.textFieldState("abcd")
        app.compose {
            layout { left { 0.0 }; top { 0.0 }; width { 200.0 }; height { 100.0 } }
            child {
                layout { left { 0.0 }; top { 0.0 }; width { 100.0 }; height { 20.0 } }
                textField(app, state, font)
            }
        }
        app.input.textEdits = listOf(TextEdit.Insert("!"))
        app.frame()
        assertEquals("abcd", state.text)
        assertFalse(app.textEditsConsumed)
        app.input.textEdits = emptyList()
        app.frame(x = 10.0, y = 10.0, pressed = true)
        app.frame(x = 30.0, y = 10.0, pressed = true)
        assertEquals(1, state.selectionStart)
        assertEquals(3, state.selectionEnd)
        app.input.textEdits = listOf(TextEdit.Insert("X"))
        app.frame(x = 30.0, y = 10.0)
        assertEquals("aXd", state.text)
        assertTrue(app.textEditsConsumed)
        app.focus.clear()
        app.frame()
        assertFalse(app.textEditsConsumed)
    }
    @Test fun `geometry clips selection and keeps caret visible`() {
        val state = App().textFieldState("abcdef")
        state.setSelection(1, 6)
        val geometry = state.geometry(font, 30.0)
        assertEquals(-30.0, geometry.textOffset)
        assertEquals(30.0, geometry.caret)
        assertEquals(0.0, geometry.selectionStart)
        assertEquals(30.0, geometry.selectionEnd)
    }

    @Test fun `field lifecycle follows focus and routes commands`() {
        val app = App()
        val state = app.textFieldState()
        val events = mutableListOf<String>()
        lateinit var field: Element
        app.compose {
            layout { left { 0.0 }; top { 0.0 }; width { 200.0 }; height { 100.0 } }
            field = child {
                layout { left { 0.0 }; top { 0.0 }; width { 100.0 }; height { 20.0 } }
                textField(app, state, font, onSubmit = { events += "submit" },
                    onCancel = { events += "cancel" }, onFocusChanged = { events += "focus:$it" })
            }
        }
        app.focus.focus(field)
        app.input.textEdits = listOf(TextEdit.Submit, TextEdit.Cancel)
        app.frame()
        app.focus.clear()
        assertEquals(listOf("focus:true", "submit", "cancel", "focus:false"), events)
        assertFalse(app.isEditingText)
    }

}
