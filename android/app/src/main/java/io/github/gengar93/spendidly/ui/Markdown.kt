package io.github.gengar93.spendidly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The markdown blocks model replies use: paragraphs, headings, lists, tables and rules. */
private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class ListItem(val marker: String, val text: String, val indent: Int) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>, val alignRight: List<Boolean>) : Block
    data object Rule : Block
}

private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val heading = Regex("^(#{1,6})\\s+(.*)$")
/** A table cell that is an amount, count or percentage: "₹1,250", "1.250,50 €", "CHF 12", "120,000 toman", "−4%". */
private val amountCell = Regex("^[+\\-−]?([^\\d\\s]{1,3}\\s?)?[+\\-−]?\\d[\\d,.\\s]*%?(\\s?(\\p{Sc}|toman))?$")
private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

private fun cells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private fun parse(text: String): List<Block> {
    val lines = text.replace("\r", "").lines()
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()
    fun flush() { if (paragraph.isNotEmpty()) { blocks += Block.Paragraph(paragraph.joinToString(" ")); paragraph.clear() } }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
            trimmed.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                flush()
                val header = cells(line)
                val align = cells(lines[i + 1]).map { it.endsWith(":") && !it.startsWith(":") }
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                // Right-align columns whose cells look like amounts or counts, even without ":" hints.
                val numeric = header.indices.map { c ->
                    align.getOrElse(c) { false } || rows.isNotEmpty() && rows.all { r ->
                        r.getOrNull(c)?.replace("*", "")?.let { amountCell.matches(it.trim()) } ?: true
                    }
                }
                blocks += Block.Table(header, rows, numeric)
                continue
            }
            heading.matches(trimmed) -> { flush(); heading.find(trimmed)!!.let { blocks += Block.Heading(it.groupValues[1].length, it.groupValues[2]) } }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed) -> { flush(); blocks += Block.Rule }
            bullet.matches(line) -> { flush(); bullet.find(line)!!.let { blocks += Block.ListItem("•", it.groupValues[2], it.groupValues[1].length / 2) } }
            numbered.matches(line) -> { flush(); numbered.find(line)!!.let { blocks += Block.ListItem("${it.groupValues[2]}.", it.groupValues[3], it.groupValues[1].length / 2) } }
            else -> paragraph += trimmed
        }
        i++
    }
    flush()
    return blocks
}

/** Inline **bold**, *italic*, `code`; bold uses the accent so key figures stand out. */
private fun inline(text: String, accent: Color, code: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])")
    var last = 0
    for (m in pattern.findAll(text)) {
        append(text.substring(last, m.range.first))
        val (b1, b2, c, i1, i2) = m.destructured
        when {
            b1.isNotEmpty() || b2.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)) { append(b1.ifEmpty { b2 }) }
            c.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code, fontSize = 14.sp)) { append(c) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(i1.ifEmpty { i2 }) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.body.copy(lineHeight = 25.sp)) {
    val c = MaterialTheme.colorScheme
    val blocks = remember(text) { parse(text.trim()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Paragraph -> Text(inline(block.text, c.primary, c.surfaceContainerHigh), style = style, color = c.onSurface)
                is Block.Heading -> Text(
                    inline(block.text, c.onSurface, c.surfaceContainerHigh),
                    style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = if (block.level <= 2) 18.sp else 16.sp),
                    color = c.onSurface, modifier = Modifier.padding(top = if (index > 0) 6.dp else 0.dp),
                )
                is Block.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, style = style, color = c.onSurfaceVariant, modifier = Modifier.width(if (block.marker == "•") 18.dp else 26.dp))
                    Text(inline(block.text, c.primary, c.surfaceContainerHigh), style = style, color = c.onSurface)
                }
                is Block.Table -> Table(block)
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
            }
        }
    }
}

@Composable
private fun Table(table: Block.Table) {
    val c = MaterialTheme.colorScheme
    val columns = table.header.size
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surfaceContainerLow)) {
        Column(Modifier.horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max).padding(horizontal = 6.dp, vertical = 6.dp)) {
            @Composable
            fun RowOf(values: List<String>, header: Boolean) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until columns) {
                        val right = table.alignRight.getOrElse(col) { false }
                        Text(
                            inline(values.getOrElse(col) { "" }, if (header) c.onSurfaceVariant else c.primary, c.surfaceContainerHigh),
                            style = if (header) Type.small.copy(fontWeight = FontWeight.SemiBold) else Type.small.copy(fontSize = 14.5.sp),
                            color = if (header) c.onSurfaceVariant else c.onSurface,
                            textAlign = if (right) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.weight(1f, fill = true).widthIn(min = 72.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            RowOf(table.header, header = true)
            table.rows.forEach { row ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(c.outlineVariant))
                RowOf(row, header = false)
            }
        }
    }
}

/** First readable line of a reply, without markdown syntax, for chat previews. */
fun plainPreview(text: String): String =
    text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("|") && !tableSeparator.matches(it) }
        ?.replace(Regex("^#{1,6}\\s+|^[-*+]\\s+|^\\d+[.)]\\s+"), "")?.replace("**", "")?.replace("`", "").orEmpty()
