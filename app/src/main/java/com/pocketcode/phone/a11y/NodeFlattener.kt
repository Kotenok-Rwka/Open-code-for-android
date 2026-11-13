// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.a11y

import com.pocketcode.phone.PRect
import com.pocketcode.phone.UiNode

/** Узел дерева доступности без android.*: так разбор дерева проверяется обычными тестами. [source] — исходный объект. */
class RawNode(
    val cls: String,
    val text: String,
    val desc: String,
    val resId: String,
    val bounds: PRect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checked: Boolean?,
    val focused: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val visible: Boolean = true,
    val children: List<RawNode> = emptyList(),
    val source: Any? = null,
)

/** Плоский список элементов с исходными объектами: [sources] [i] соответствует элементу с номером i + 1. */
class FlatTree(val nodes: List<UiNode>, val sources: List<Any?>)

object NodeFlattener {
    /** Имя класса без пакета: android.widget.Button → Button. */
    fun shortClass(cls: String): String = cls.substringAfterLast('.').ifEmpty { "View" }

    /** Идентификатор ресурса без пакета: com.app:id/send → send. */
    fun shortResId(resId: String): String = resId.substringAfter(":id/", resId.substringAfterLast('/'))

    private fun meaningful(n: RawNode) =
        n.text.isNotBlank() || n.desc.isNotBlank() || n.clickable || n.longClickable || n.scrollable || n.editable || n.checked != null

    /**
     * Обходит дерево в глубину. В список попадают только видимые элементы, у которых есть текст, описание или действие,
     * а также пустые контейнеры прокрутки (по ним агент листает). Родитель — ближайший попавший в список предок.
     * Невидимые ветки пропускаются целиком; [maxNodes] ограничивает размер снимка.
     */
    fun flatten(root: RawNode, maxNodes: Int = 400): FlatTree {
        val nodes = ArrayList<UiNode>()
        val sources = ArrayList<Any?>()
        fun walk(n: RawNode, parentId: Int) {
            if (!n.visible || nodes.size >= maxNodes) return
            var myId = parentId
            if (meaningful(n) && n.bounds.width > 0 && n.bounds.height > 0) {
                myId = nodes.size + 1
                nodes += UiNode(
                    id = myId, parent = parentId, cls = shortClass(n.cls),
                    text = if (n.password) "" else n.text, desc = n.desc, resId = shortResId(n.resId),
                    bounds = n.bounds, clickable = n.clickable, longClickable = n.longClickable,
                    scrollable = n.scrollable, editable = n.editable, checked = n.checked,
                    focused = n.focused, enabled = n.enabled, password = n.password,
                )
                sources += n.source
            }
            for (c in n.children) walk(c, myId)
        }
        walk(root, 0)
        return FlatTree(nodes, sources)
    }
}
