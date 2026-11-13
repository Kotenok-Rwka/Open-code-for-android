// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.a11y

import com.pocketcode.phone.PRect
import com.pocketcode.phone.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFlattenerTest {
    private fun raw(
        cls: String = "android.widget.TextView", text: String = "", desc: String = "", resId: String = "",
        clickable: Boolean = false, editable: Boolean = false, password: Boolean = false, visible: Boolean = true,
        children: List<RawNode> = emptyList(), source: Any? = null,
    ) = RawNode(cls, text, desc, resId, PRect(0, 0, 100, 50), clickable, false, false, editable, null, false, true, password, visible, children, source)

    @Test fun shortNames() {
        assertEquals("Button", NodeFlattener.shortClass("android.widget.Button"))
        assertEquals("send", NodeFlattener.shortResId("com.app:id/send"))
        assertEquals("", NodeFlattener.shortResId(""))
    }

    @Test fun skipsEmptyAndInvisible() {
        val tree = raw("android.view.View", children = listOf(
            raw(text = "Привет", source = "a"),
            raw("android.view.View"),
            raw(text = "скрыто", visible = false),
            raw("android.widget.Button", clickable = true, desc = "ОК", source = "b"),
        ))
        val f = NodeFlattener.flatten(tree)
        assertEquals(listOf(1, 2), f.nodes.map { it.id })
        assertEquals(listOf<Any?>("a", "b"), f.sources)
        assertEquals("Button", f.nodes[1].cls)
    }

    @Test fun parentIsNearestKeptAncestor() {
        val tree = raw("android.view.ViewGroup", clickable = true, children = listOf(
            raw("android.view.View", children = listOf(raw(text = "вложено")))))
        val f = NodeFlattener.flatten(tree)
        assertEquals(0, f.nodes[0].parent)
        assertEquals(1, f.nodes[1].parent)
    }

    @Test fun passwordTextHidden() {
        val f = NodeFlattener.flatten(raw("android.widget.EditText", text = "секрет", editable = true, password = true))
        assertEquals("", f.nodes[0].text)
        assertTrue(f.nodes[0].password)
    }

    @Test fun respectsMax() {
        val kids = (1..10).map { raw(text = "t$it") }
        assertEquals(3, NodeFlattener.flatten(raw("android.view.View", children = kids), 3).nodes.size)
    }

    @Test fun formatShowsNodesAndLimit() {
        val f = NodeFlattener.flatten(raw("android.view.View", children = listOf(raw(text = "A"), raw(text = "B"), raw(text = "C"))))
        val text = ScreenText.format(ScreenSnapshot(0, "com.x", "Main", 1080, 2400, f.nodes, true), maxNodes = 2)
        assertTrue(text.contains("com.x/Main"))
        assertTrue(text.contains("клавиатура открыта"))
        assertTrue(text.contains("[2] TextView \"B\""))
        assertTrue(text.contains("ещё элементов: 1"))
    }
}
