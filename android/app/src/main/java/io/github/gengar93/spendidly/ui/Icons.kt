package io.github.gengar93.spendidly.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Hand-drawn 24px line icons. Strokes are tinted by Icon(). */
private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList(), width: Float = 1.9f) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in strokes) addPath(
            addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        for (d in fills) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
    }.build()

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy} a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0 Z"

object Glyph {
    /** A donut chart with its top-right slice pulled out. */
    val Summary = icon("summary", listOf(
        "M20.03 13.59A8.6 8.6 0 1 1 10.41 3.97L10.41 8.03A4.6 4.6 0 1 0 15.97 13.59Z",
        "M13.6 2.98A8.6 8.6 0 0 1 21.02 10.4L16.96 10.4A4.6 4.6 0 0 0 13.6 7.04Z",
    ))
    val History = icon("history", listOf("M10 5h10M10 12h7M10 19h9"), listOf(circle(4.5f, 5f, 1.6f), circle(4.5f, 12f, 1.6f), circle(4.5f, 19f, 1.6f)))
    val Ask = icon("ask", listOf("M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v8a2.5 2.5 0 0 1-2.5 2.5H11l-4.5 3.5V17A2.5 2.5 0 0 1 4 14.5Z"))
    val Settings = icon("settings", listOf("M4 7h2.4M11.6 7H20M4 17h8.4M17.6 17H20", circle(9f, 7f, 2.6f), circle(15f, 17f, 2.6f)))
    val Add = icon("add", listOf("M12 5v14M5 12h14"), width = 2.2f)
    val Send = icon("send", listOf("M12 19V6M6 11l6-6 6 6"), width = 2.2f)
    /** A capsule microphone on a stand. */
    val Mic = icon("mic", listOf("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0Z", "M5.5 11a6.5 6.5 0 0 0 13 0", "M12 17.5V21"))
    val Stop = icon("stop", emptyList(), listOf("M8 5.5h8a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5H8a2.5 2.5 0 0 1-2.5-2.5V8A2.5 2.5 0 0 1 8 5.5Z"))
    val Back = icon("back", listOf("M15 5l-7 7 7 7"), width = 2.1f)
    val Search = icon("search", listOf(circle(11f, 11f, 6.5f), "M16 16l4 4"))
    val Close = icon("close", listOf("M6 6l12 12M18 6L6 18"))
    val Chevron = icon("chevron", listOf("M9 6l6 6-6 6"))
    val Check = icon("check", listOf("M5 12.5l4.5 4.5L19 7.5"), width = 2.2f)
    val Delete = icon("delete", listOf("M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13", "M10.5 11v5.5M13.5 11v5.5"))
    val Table = icon("table", listOf("M4.5 5.5h15v13h-15Z", "M4.5 10h15M4.5 14.3h15M10 10v8.5"))
    val Filter = icon("filter", listOf("M4 5.5h16l-6 7v6l-4-2v-4Z"))
    val DragHandle = icon("drag_handle", listOf("M5 9.5h14M5 14.5h14"))
    val Info = icon("info", listOf(circle(12f, 12f, 9f), "M12 11v5.5"), listOf(circle(12f, 7.8f, 1.2f)))
    val Flag = icon("flag", listOf("M6 20.5V4.5", "M6 5h11.5l-2.8 4.2 2.8 4.3H6"))
    val Calendar = icon("calendar", listOf("M5 6.5h14v13H5Z", "M5 10.5h14M9 4v4M15 4v4"))

    // Categories
    val Food = icon("food", listOf("M4 11h16a8 8 0 0 1-16 0Z", "M9 7c0-1.5 1-1.5 1-3M13 7c0-1.5 1-1.5 1-3"))
    val Groceries = icon("groceries", listOf("M5 9h14l-1.4 10H6.4Z", "M9 9V7a3 3 0 0 1 6 0v2"))
    val Transport = icon("transport", listOf("M5 15v-4l2-4h10l2 4v4Z", circle(8f, 17f, 1.6f), circle(16f, 17f, 1.6f)))
    val Bills = icon("bills", listOf("M7 3h10v18l-2.5-1.6L12 21l-2.5-1.6L7 21Z", "M10 8h4M10 12h4"))
    val Home = icon("home", listOf("M4 11 12 4l8 7v9H4Z", "M10 20v-5h4v5"))
    val Personal = icon("personal", listOf(circle(12f, 8f, 3.5f), "M5 20c1-4 4-5.5 7-5.5s6 1.5 7 5.5"))
    val Family = icon("family", listOf("M12 20s-7-4.3-7-9.5A3.8 3.8 0 0 1 12 8a3.8 3.8 0 0 1 7 2.5C19 15.7 12 20 12 20Z"))
    val Other = icon("other", emptyList(), listOf(circle(6.5f, 12f, 1.5f), circle(12f, 12f, 1.5f), circle(17.5f, 12f, 1.5f)))
    val Accessories = icon("accessories", listOf("M9 3v4M15 3v4M7 7h10v4a5 5 0 0 1-10 0Z", "M12 16v5"))
    val Electronics = icon("electronics", listOf("M5 6h14v10H5Z", "M3 19h18"))
    val Clothes = icon("clothes", listOf("M9 4 4 7l2 4 2-1v10h8V10l2 1 2-4-5-3c0 1.7-1.3 3-3 3s-3-1.3-3-3Z"))
    val Digital = icon("digital", listOf("M7 3h10v18H7Z", "M11 18h2"))
    val Learning = icon("learning", listOf("M4 5.5c3-1 5.5-1 8 1 2.5-2 5-2 8-1V19c-3-1-5.5-1-8 1-2.5-2-5-2-8-1Z", "M12 6.5V20"))
    val Uncategorized = icon("uncategorized", listOf(circle(12f, 12f, 8f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.5v.4"), listOf(circle(12f, 16.8f, 1.1f)))
}
