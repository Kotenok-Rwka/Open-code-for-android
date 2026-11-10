package com.pocketcode.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Единый набор линейных значков 24x24: тонкий контур 1,75 со скруглёнными концами.
 * Цвет задаёт тот, кто рисует значок (tint у Icon), поэтому контур здесь чёрный.
 */
object AppIcons {
    /** Нарисованный контуром значок. Если строка пути вдруг не разбирается, вернётся пустой значок, а не падение. */
    private fun line(name: String, d: String, width: Float = 1.75f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        runCatching {
            b.addPath(
                PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** Закрашенный значок (кнопка «стоп»). */
    private fun solid(name: String, d: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        runCatching { b.addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)) }
        return b.build()
    }

    val Stop by lazy { solid("stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z") }
    val Chat by lazy { line("chat", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z") }
    val Folder by lazy { line("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2z") }
    val Bolt by lazy { line("bolt", "M13 2L3 14h9l-1 8 10-12h-9l1-8z") }
    val Grid by lazy { line("grid", "M4 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z M14 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z M14 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z M4 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z") }
    val Search by lazy { line("search", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M21 21l-4.3-4.3") }
    val Plus by lazy { line("plus", "M12 5v14 M5 12h14") }
    val More by lazy { line("more", "M12 5.5v.01 M12 12v.01 M12 18.5v.01") }
    val Chevron by lazy { line("chevron", "M9 18l6-6-6-6") }
    val ChevronLeft by lazy { line("chevronleft", "M15 18l-6-6 6-6") }
    val ArrowUp by lazy { line("arrowup", "M12 19V5 M5 12l7-7 7 7") }
    val Mic by lazy { line("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z M19 10v2a7 7 0 0 1-14 0v-2 M12 19v3") }
    val Image by lazy { line("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M9 10a1 1 0 1 0 0-2 1 1 0 0 0 0 2z M21 15l-5-5L5 21") }
    val Sliders by lazy { line("sliders", "M4 21v-7 M4 10V3 M12 21v-9 M12 8V3 M20 21v-5 M20 12V3 M1 14h6 M9 8h6 M17 16h6") }
    val Globe by lazy { line("globe", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M2 12h20 M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z") }
    val Terminal by lazy { line("terminal", "M4 17l6-6-6-6 M12 19h8") }
    val Sparkles by lazy { line("sparkles", "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z M19 15l.7 1.8 1.8.7-1.8.7L19 20l-.7-1.8-1.8-.7 1.8-.7z") }
    val Shield by lazy { line("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z M12 8v4 M12 16v.01") }
    val Phone by lazy { line("phone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M12 18v.01") }
    val Refresh by lazy { line("refresh", "M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8 M3 3v5h5 M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16 M16 16h5v5") }
    val Archive by lazy { line("archive", "M21 8v13H3V8 M1 3h22v5H1z M10 12h4") }
    val Activity by lazy { line("activity", "M22 12h-4l-3 9L9 3l-3 9H2") }
    val History by lazy { line("history", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 6v6l4 2") }
    val Key by lazy { line("key", "M21 2l-2 2 M11.4 11.6a5.5 5.5 0 1 1-7.8 7.8 5.5 5.5 0 0 1 7.8-7.8z M11.4 11.6L15.5 7.5 M15.5 7.5l3 3L22 7l-3-3") }
    val File by lazy { line("file", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M16 13H8 M16 17H8 M10 9H8") }
    val Pencil by lazy { line("pencil", "M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z") }
    val Bug by lazy { line("bug", "M8 2l1.88 1.88 M14.12 3.88L16 2 M9 7.13v-1a3 3 0 1 1 6 0v1 M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6z M12 20v-9 M6.53 9C4.6 8.8 3 7.1 3 5 M6 13H2 M3 21c0-2.1 1.7-3.9 3.8-4 M20.97 5c0 2.1-1.6 3.8-3.5 4 M22 13h-4 M17.2 17c2.1.1 3.8 1.9 3.8 4") }
    val Flask by lazy { line("flask", "M10 2v7.5a2 2 0 0 1-.2.9L4.7 20.5a1 1 0 0 0 .9 1.5h12.8a1 1 0 0 0 .9-1.5l-5.1-10.1a2 2 0 0 1-.2-.9V2 M8.5 2h7 M7 16h10") }
    val Bot by lazy { line("bot", "M12 8V4H8 M6 8h12a4 4 0 0 1 4 4v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6a4 4 0 0 1 4-4z M2 14h2 M20 14h2 M15 13v2 M9 13v2") }
    val Wrench by lazy { line("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z") }
    val Book by lazy { line("book", "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z") }
    val Layers by lazy { line("layers", "M12 2l10 5-10 5L2 7z M2 17l10 5 10-5 M2 12l10 5 10-5") }
    val Package by lazy { line("package", "M7.5 4.27l9 5.15 M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z M3.3 7L12 12l8.7-5 M12 22V12") }
    val Branch by lazy { line("branch", "M6 3v12 M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M18 9a9 9 0 0 1-9 9") }
    val Play by lazy { line("play", "M6 3l14 9-14 9z") }
    val Share by lazy { line("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8 M16 6l-4-4-4 4 M12 2v13") }
    val Check by lazy { line("check", "M20 6L9 17l-5-5") }
    val Close by lazy { line("close", "M18 6L6 18 M6 6l12 12") }
    val Copy by lazy { line("copy", "M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2z M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1") }
    val Star by lazy { line("star", "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z") }
    val Alert by lazy { line("alert", "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z M12 9v4 M12 17v.01") }
    val Edit by lazy { line("edit", "M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z") }
    val Link by lazy { line("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71 M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71") }
    val Cpu by lazy { line("cpu", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M9 9h6v6H9z M9 1v3 M15 1v3 M9 20v3 M15 20v3 M20 9h3 M20 14h3 M1 9h3 M1 14h3") }
    val Info by lazy { line("info", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 16v-4 M12 8v.01") }
    val Back by lazy { line("back", "M19 12H5 M12 19l-7-7 7-7") }
}
