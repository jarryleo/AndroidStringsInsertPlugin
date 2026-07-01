package cn.jarryleo.android.buddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
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
    /**
     * 承载 markdown 的气泡背景色。
     *  - 助手气泡(深色 fieldBackground):blockquote / 行内 code 的"块底色"需要叠
     *    一层半透明暗色才能与气泡拉开层次;
     *  - 用户气泡(accent):行内 code 需要叠一层亮色压暗以保持可读;
     *  - 默认(无气泡,引用气泡等):传 null,内部回退到 colors.fieldBackground。
     * 用 null / Color.Unspecified 表示"无气泡主题",由调用方决定。
     */
    bubbleColor: Color? = null,
) {
    val effectiveBubble = bubbleColor ?: colors.fieldBackground
    val palette = MarkdownPalette.from(colors, effectiveBubble)
    val document = remember(markdown) { markdownParser.parse(markdown) }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var child = document.firstChild
        while (child != null) {
            renderBlock(child, palette, uriHandler::openUri)
            child = child.next
        }
    }
}

/**
 * Markdown 渲染所需的色板:把 IDE 主题色 + 气泡底色组合成
 *  - codeBlockBackground / codeBlockBorder:围栏代码块底色 + 描边
 *  - inlineCodeBackground:行内 code 底色(在气泡之上叠半透明)
 *
 * 关键约束:行内 code 的底色必须**与气泡底色**形成层次,而**不能简单沿用
 * `colors.fieldBackground`** —— 用户气泡(accent)是品牌色,直接套 fieldBackground
 * (白/近白)会形成"白底白字"的刺眼对比;助手气泡(fieldBackground)再叠一层
 * fieldBackground 也看不出块底,导致行内 code 与正文无层次。
 * 解决:在 bubbleColor 之上叠一层 alpha,顺势与气泡同色系(亮气泡压暗,暗气泡提亮)。
 */
private data class MarkdownPalette(
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val fieldBorder: Color,
    val codeBlockBackground: Color,
    val codeBlockBorder: Color,
    val inlineCodeBackground: Color,
    val blockquoteBar: Color,
) {
    companion object {
        fun from(colors: IdeColors, bubbleColor: Color): MarkdownPalette {
            val isLight = bubbleColor.luminance() > 0.5f
            // 行内 code:在气泡色上叠 alpha,与气泡同色系但拉出层次。
            // 亮气泡 -> 压暗(黑 ~ 18% alpha);暗气泡 -> 提亮(白 ~ 22% alpha)。
            val inlineCodeBg = if (isLight) {
                Color.Black.copy(alpha = 0.14f).compositeOver(bubbleColor)
            } else {
                Color.White.copy(alpha = 0.22f).compositeOver(bubbleColor)
            }
            // 围栏代码块:背景进一步压/提,加 1dp 描边,与气泡视觉上明确分离。
            val codeBlockBg = if (isLight) {
                Color.Black.copy(alpha = 0.06f).compositeOver(bubbleColor)
            } else {
                Color.Black.copy(alpha = 0.32f).compositeOver(bubbleColor)
            }
            return MarkdownPalette(
                text = colors.text,
                secondaryText = colors.secondaryText,
                accent = colors.accent,
                fieldBorder = colors.fieldBorder,
                codeBlockBackground = codeBlockBg,
                codeBlockBorder = colors.fieldBorder,
                inlineCodeBackground = inlineCodeBg,
                blockquoteBar = colors.accent,
            )
        }
    }
}

/**
 * 把 [overlay] 以 src-over 合成在 [base] 之上,得到一个不透明的 Color。
 * 比 `Color.copy(alpha = ...)` 直接覆盖 alpha 更准(后者忽略背景)。
 */
private fun Color.compositeOver(base: Color): Color {
    val a = this.alpha
    val r = this.red * a + base.red * (1f - a)
    val g = this.green * a + base.green * (1f - a)
    val b = this.blue * a + base.blue * (1f - a)
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 1f)
}

@Composable
private fun renderBlock(
    node: Node,
    palette: MarkdownPalette,
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
                color = palette.text,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                lineHeight = (fontSize.value + 6).sp,
            )
        }

        is Paragraph -> {
            val annotated = buildInlineAnnotated(node, palette, openUri)
            Text(
                text = annotated,
                style = compactTextStyle(palette.text),
            )
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
                    .background(palette.codeBlockBackground, RoundedCornerShape(4.dp))
                    .border(1.dp, palette.codeBlockBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = code.trimEnd('\n'),
                    color = palette.text,
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
                    renderListItem(item, palette, "•", openUri)
                    index++
                }
                item = item.next
            }
        }

        is OrderedList -> {
            var item = node.firstChild
            var index = node.markerStartNumber ?: 1
            while (item != null) {
                if (item is ListItem) {
                    renderListItem(item, palette, "$index.", openUri)
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
                        .background(palette.blockquoteBar)
                )
                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    var child = node.firstChild
                    while (child != null) {
                        renderBlock(child, palette, openUri)
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
                    .background(palette.fieldBorder)
            )
        }

        is TableBlock -> {
            renderTable(node, palette, openUri)
        }

        is HtmlBlock -> {
            Text(
                text = node.literal.orEmpty().trim(),
                color = palette.secondaryText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        else -> {
            var child = node.firstChild
            while (child != null) {
                renderBlock(child, palette, openUri)
                child = child.next
            }
        }
    }
}

@Composable
private fun renderListItem(
    item: ListItem,
    palette: MarkdownPalette,
    prefix: String,
    openUri: (String) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = prefix,
            color = palette.text,
            style = compactTextStyle(palette.text),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var child = item.firstChild
            while (child != null) {
                renderBlock(child, palette, openUri)
                child = child.next
            }
        }
    }
}

@Composable
private fun renderTable(
    tableBlock: TableBlock,
    palette: MarkdownPalette,
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

    // 列宽分配策略(2026.x 修复):
    // 旧实现只给「非最后一列」加 weight(1f),最后一列走自然宽度。
    // 一旦最后一列内容较长(URL / 长句 / 中文段落),它会按字面宽度撑开,挤掉
    // 其他列的可用空间,导致前面几列变得很窄、文字被压成密集换行,整张表高得
    // 失控,严重时滚一下要看半天。
    //
    // 新策略:**所有列统一 weight(1f)**,平均瓜分可用宽度。这样:
    //  - 无论哪一列内容偏长,各列宽度始终相等,没有「一列极窄、其余极宽」的情况;
    //  - 长文本在固定列宽内 wrap,各行高度由该行最长单元格决定,可预测;
    //  - 短内容列只是横向留白,不影响其他列的布局。
    //
    // 同时把每列加上 `Modifier.padding(end = 6.dp)`,在等宽列之间留一点视觉间隙,
    // 避免相邻单元格贴在一起时难以分辨边界。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.fieldBorder, RoundedCornerShape(4.dp))
    ) {
        if (headerCells.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.codeBlockBackground)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                headerCells.forEachIndexed { i, text ->
                    Text(
                        text = text,
                        color = palette.text,
                        fontWeight = FontWeight.Bold,
                        style = compactTextStyle(palette.text),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = if (i < headerCells.size - 1) 6.dp else 0.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.fieldBorder)
            )
        }
        bodyRows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                row.forEachIndexed { i, text ->
                    Text(
                        text = text,
                        color = palette.text,
                        style = compactTextStyle(palette.text),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = if (i < row.size - 1) 6.dp else 0.dp),
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
            // inline HTML/XML(如选区里的 `<TextView ... />`):commonmark 把内容存
            // 在 `literal` 而非子节点,这里必须显式追加,否则默认走 else 分支
            // 递归到 firstChild = null,整段标签会被吞成空串。
            is HtmlInline -> sb.append(child.literal)
            is SoftLineBreak -> sb.append('\n')
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
    palette: MarkdownPalette,
    openUri: (String) -> Unit,
): AnnotatedString {
    return buildAnnotatedString {
        var child = node.firstChild
        while (child != null) {
            renderInlineNode(child, palette, openUri)
            child = child.next
        }
    }
}

private fun AnnotatedString.Builder.renderInlineNode(
    node: Node,
    palette: MarkdownPalette,
    openUri: (String) -> Unit,
) {
    when (node) {
        is Text -> append(node.literal.orEmpty())
        is Code -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = palette.inlineCodeBackground,
                    fontSize = 11.sp,
                )
            ) {
                append(node.literal.orEmpty())
            }
        }

        // inline HTML/XML(例如选区里的 `<TextView ... />`):commonmark 把它解析成
        // HtmlInline 节点,内容存在 `literal` 而非子节点。旧实现走 else 分支递归
        // firstChild,结果整段标签被吞,UI 只能看到普通文字。改为显式追加字面量,
        // 并用次要色 + 等宽字体提示「这是被引用的源码片段」。
        is HtmlInline -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = palette.secondaryText,
                    fontSize = 11.sp,
                )
            ) {
                append(node.literal.orEmpty())
            }
        }

        is SoftLineBreak -> append('\n')
        is HardLineBreak -> append('\n')

        is StrongEmphasis -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                var child = node.firstChild
                while (child != null) {
                    renderInlineNode(child, palette, openUri)
                    child = child.next
                }
            }
        }

        is Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                var child = node.firstChild
                while (child != null) {
                    renderInlineNode(child, palette, openUri)
                    child = child.next
                }
            }
        }

        is Link -> {
            val url = node.destination.orEmpty()
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = palette.accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                    linkInteractionListener = { openUri(url) },
                )
            ) {
                var child = node.firstChild
                while (child != null) {
                    renderInlineNode(child, palette, openUri)
                    child = child.next
                }
            }
        }

        else -> {
            var child = node.firstChild
            while (child != null) {
                renderInlineNode(child, palette, openUri)
                child = child.next
            }
        }
    }
}
