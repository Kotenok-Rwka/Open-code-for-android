package com.pocketcode.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** Рендер Mermaid/LaTeX через локальный WebView без внешних JS-библиотек: безопасный упрощённый вариант. */
object RichBlocks {
    fun isRich(lang: String, code: String): Boolean =
        lang.equals("mermaid", true) || lang == "latex" || lang == "tex" || code.contains("\\begin{")

    fun html(lang: String, code: String): String {
        val money = Char(36).toString() + Char(36).toString()
        val escaped = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = when {
            lang.equals("mermaid", true) -> {
                val nodes = code.lineSequence().map { it.trim() }
                    .filter { it.contains("-->") || it.contains("---") }.toList()
                if (nodes.isEmpty()) "<pre>$escaped</pre>"
                else nodes.joinToString("<br>") { line ->
                    line.split(Regex("-->|---")).map { it.trim('[', ']', '(', ')', ' ') }
                        .joinToString(" → ") { raw ->
                            val label = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            "<span style='padding:6px;border:1px solid #888;border-radius:6px'>$label</span>"
                        }
                }
            }
            else -> {
                // Минимальный локальный рендер формулы без внешних JS: понятные замены + monospace.
                val math = escaped
                    .replace("\\times", "×").replace("\\cdot", "·").replace("\\pm", "±")
                    .replace("\\le", "≤").replace("\\ge", "≥").replace("\\ne", "≠")
                    .replace("\\sqrt", "√").replace("\\alpha", "α").replace("\\beta", "β")
                    .replace("\\gamma", "γ").replace("\\pi", "π").replace("\\sum", "∑")
                    .replace("\\infty", "∞").replace(money, "")
                    .replace("^2", "²").replace("^3", "³")
                "<div style='font-family:serif;font-size:20px;letter-spacing:1px'>$math</div>"
            }
        }
        return "<!doctype html><html><head><meta charset='utf-8'><style>body{background:#151515;color:#eee;font-family:sans-serif;margin:8px}pre{white-space:pre-wrap}</style></head><body>$body</body></html>"
    }
}

@Composable
fun RichBlock(lang: String, code: String) {
    val ctx = LocalContext.current
    val html = remember(lang, code) { RichBlocks.html(lang, code) }
    AndroidView(
        factory = {
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.defaultFontSize = 14
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
    )
}
