package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val markdownParser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create()))
    .build()

@Composable
fun MarkdownContent(
    markdown: String,
    colors: IdeColors,
    modifier: Modifier = Modifier,
) {
    val document = remember(markdown) { markdownParser.parse(markdown) }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var child = document.firstChild
        while (child != null) {
            renderBlock(child, colors, uriHandler::openUri)
            child = child.next
        }
    }
}

@Composable
private fun renderBlock(
    node: Node,
    colors: IdeColors,
    openUri: (String) -> Unit,
) {
    when (node) {
        is Heading -> {
            val fontSize = when (node.level) {
                1 -> 20.sp
                2 -> 17.sp
                3 -> 15.sp
                4 -> 14.sp
                5 -> 13.sp
                else -> 12.sp
            }
            Text(
                text = collectInlineText(node),
                color = colors.text,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                lineHeight = (fontSize.value + 6).sp,
            )
        }

        is Paragraph -> {
            val annotated = buildInlineAnnotated(node, colors, openUri)
            if (annotated.getStringAnnotations("URL", 0, annotated.length).isNotEmpty()) {
                ClickableText(
                    text = annotated,
                    onClick = { offset ->
                        annotated.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { openUri(it.item) }
                    },
                    style = compactTextStyle(colors.text),
                )
            } else {
                Text(
                    text = annotated,
                    style = compactTextStyle(colors.text),
                )
            }
        }

        is FencedCodeBlock, is IndentedCodeBlock -> {
            val code = when (node) {
                is FencedCodeBlock -> node.literal.orEmpty()
                is IndentedCodeBlock -> node.literal.orEmpty()
                else -> ""
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.fieldBackground, RoundedCornerShape(4.dp))
                    .border(1.dp, colors.fieldBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = code.trimEnd('\n'),
                    color = colors.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        is BulletList -> {
            var item = node.firstChild
            var index = 0
            while (item != null) {
                if (item is ListItem) {
                    renderListItem(item, colors, "•", openUri)
                    index++
                }
                item = item.next
            }
        }

        is OrderedList -> {
            var item = node.firstChild
            var index = node.startNumber
            while (item != null) {
                if (item is ListItem) {
                    renderListItem(item, colors, "$index.", openUri)
                    index++
                }
                item = item.next
            }
        }

        is BlockQuote -> {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(IntrinsicSize.Min)
                        .background(colors.accent)
                )
                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    var child = node.firstChild
                    while (child != null) {
                        renderBlock(child, colors, openUri)
                        child = child.next
                    }
                }
            }
        }

        is ThematicBreak -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(1.dp)
                    .background(colors.border)
            )
        }

        is TableBlock -> {
            renderTable(node, colors, openUri)
        }

        is HtmlBlock -> {
            Text(
                text = node.literal.orEmpty().trim(),
                color = colors.secondaryText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        else -> {
            var child = node.firstChild
            while (child != null) {
                renderBlock(child, colors, openUri)
                child = child.next
            }
        }
    }
}

@Composable
private fun renderListItem(
    item: ListItem,
    colors: IdeColors,
    prefix: String,
    openUri: (String) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = prefix,
            color = colors.text,
            style = compactTextStyle(colors.text),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var child = item.firstChild
            while (child != null) {
                renderBlock(child, colors, openUri)
                child = child.next
            }
        }
    }
}

@Composable
private fun renderTable(
    tableBlock: TableBlock,
    colors: IdeColors,
    openUri: (String) -> Unit,
) {
    val headerCells = mutableListOf<String>()
    val bodyRows = mutableListOf<List<String>>()

    var section = tableBlock.firstChild
    while (section != null) {
        when (section) {
            is TableHead -> {
                val row = section.firstChild
                if (row is TableRow) {
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            headerCells.add(collectInlineText(cell))
                        }
                        cell = cell.next
                    }
                }
            }

            is TableBody -> {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = mutableListOf<String>()
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) {
                                cells.add(collectInlineText(cell))
                            }
                            cell = cell.next
                        }
                        bodyRows.add(cells)
                    }
                    row = row.next
                }
            }
        }
        section = section.next
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
    ) {
        if (headerCells.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.headerBackground)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                headerCells.forEachIndexed { i, text ->
                    Text(
                        text = text,
                        color = colors.text,
                        fontWeight = FontWeight.Bold,
                        style = compactTextStyle(colors.text),
                        modifier = if (i < headerCells.size - 1) Modifier.weight(1f) else Modifier,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border)
            )
        }
        bodyRows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                row.forEachIndexed { i, text ->
                    Text(
                        text = text,
                        color = colors.text,
                        style = compactTextStyle(colors.text),
                        modifier = if (i < row.size - 1) Modifier.weight(1f) else Modifier,
                    )
                }
            }
        }
    }
}

private fun collectInlineText(node: Node): String {
    val sb = StringBuilder()
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> sb.append(child.literal)
            is Code -> sb.append(child.literal)
            is SoftLineBreak -> sb.append(' ')
            is HardLineBreak -> sb.append('\n')
            is StrongEmphasis -> sb.append(collectInlineText(child))
            is Emphasis -> sb.append(collectInlineText(child))
            is Link -> sb.append(collectInlineText(child))
            else -> sb.append(collectInlineText(child))
        }
        child = child.next
    }
    return sb.toString()
}

private fun buildInlineAnnotated(
    node: Node,
    colors: IdeColors,
    openUri: (String) -> Unit,
): AnnotatedString {
    return buildAnnotatedString {
        var child = node.firstChild
        while (child != null) {
            renderInlineNode(child, colors)
            child = child.next
        }
    }
}

private fun AnnotatedString.Builder.renderInlineNode(
    node: Node,
    colors: IdeColors,
) {
    when (node) {
        is Text -> append(node.literal.orEmpty())
        is Code -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.fieldBackground.copy(alpha = 0.3f),
                    fontSize = 11.sp,
                )
            ) {
                append(node.literal.orEmpty())
            }
        }

        is SoftLineBreak -> append(' ')
        is HardLineBreak -> append('\n')

        is StrongEmphasis -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                var child = node.firstChild
                while (child != null) {
                    renderInlineNode(child, colors)
                    child = child.next
                }
            }
        }

        is Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                var child = node.firstChild
                while (child != null) {
                    renderInlineNode(child, colors)
                    child = child.next
                }
            }
        }

        is Link -> {
            val url = node.destination.orEmpty()
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(
                SpanStyle(
                    color = colors.accent,
                    textDecoration = TextDecoration.Underline,
                )
            ) {
                var child = node.firstChild
                while (child != null) {
                    renderInlineNode(child, colors)
                    child = child.next
                }
            }
            pop()
        }

        else -> {
            var child = node.firstChild
            while (child != null) {
                renderInlineNode(child, colors)
                child = child.next
            }
        }
    }
}
