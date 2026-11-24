package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownComponents
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.MarkdownInlineContent
import me.rerere.bettertext.markdown.MarkdownTheme
import me.rerere.bettertext.markdown.TableCell
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.ui.table.DataTable
import kotlin.math.max
import me.rerere.bettertext.markdown.Markdown as BetterMarkdown

/**
 * 用 Material 3 主题渲染 Markdown，解析和排版交给 BetterText 的 Markdown 模块。
 *
 * 默认的组件只依赖 Compose：公式显示源码，代码块不高亮，图片只显示替代文本。聊天里用的
 * [MarkdownBlock] 在这之上接入了代码高亮、图片加载和 LaTeX；悬浮窗这类拿不到导航和 Activity 的地方直接用这个。
 * 文字要能选中，外面包一层 [SelectableContent]。
 *
 * @param style 正文样式，合并到 `LocalTextStyle` 上；没有颜色时用 `LocalContentColor`
 * @param streaming 内容还在生成时为 true，末尾没写完的语法（如 `**加粗`、`$\frac{`）会被补全或隐藏
 * @param onLinkClick 链接点击回调，为 null 时交给系统的 UriHandler 打开
 */
@Composable
fun MaterialMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = MaterialMarkdownDefaults.theme(style),
    components: MarkdownComponents = MaterialMarkdownDefaults.components,
    softBreakAsLineBreak: Boolean = true,
    streaming: Boolean = false,
    colorizeQuotes: Boolean = true,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val merged = LocalTextStyle.current.merge(style)
    val resolved = merged.copy(color = merged.color.takeOrElse { LocalContentColor.current })
    // 兼容酒馆的渲染习惯：<details> 状态块按列表渲染，对话引号和书名号着色。
    // 着色只用于角色（AI）的消息，用户消息由调用方传 false 关闭
    val normalized = remember(content, colorizeQuotes) {
        val base = normalizeDetailsBlocks(content)
        if (colorizeQuotes) colorizeQuotes(base) else base
    }
    // 插槽里的组件（代码块、表格工具栏等）用 Material 的 Text，让它们也拿到正文样式
    ProvideTextStyle(resolved) {
        BetterMarkdown(
            content = normalized,
            modifier = modifier,
            style = resolved,
            theme = theme,
            components = components,
            softBreakAsLineBreak = softBreakAsLineBreak,
            streaming = streaming,
            onLinkClick = onLinkClick,
        )
    }
}

object MaterialMarkdownDefaults {
    /** 代码块、图片、表格和折叠块的 Material 3 默认实现；公式没有注册，显示源码 */
    val components = MarkdownComponents(
        codeBlock = { CodeBlock(it) },
        image = { image, link -> Image(image, link) },
        table = { Table(it) },
        details = { block, summary, content -> Details(block, summary, content) },
    )

    private val HeadingScales = floatArrayOf(1.5f, 1.375f, 1.25f, 1.125f, 1f, 0.875f)

    /** 标题字号按 [textStyle] 的字号等比放大，正文字号变化时标题跟着变 */
    @Composable
    fun theme(textStyle: TextStyle = LocalTextStyle.current): MarkdownTheme {
        val fontSize = LocalTextStyle.current.merge(textStyle).fontSize
        val colorScheme = MaterialTheme.colorScheme
        return remember(fontSize, colorScheme) {
            MarkdownTheme(
                headings = HeadingScales.map { scale ->
                    if (fontSize.isSp) {
                        TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize * scale,
                            lineHeight = fontSize * (scale * 1.25f),
                        )
                    } else {
                        TextStyle(fontWeight = FontWeight.Bold)
                    }
                },
                link = SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline),
                inlineCode = SpanStyle(
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.9.em,
                ),
                blockSpacing = 12.dp,
                markerColor = colorScheme.primary,
                quoteColor = colorScheme.outlineVariant,
                dividerColor = colorScheme.outlineVariant,
            )
        }
    }

    /** 根据 open 属性初始化折叠状态，正文更新时保留用户的展开选择。 */
    @Composable
    fun Details(
        block: MarkdownBlock.Details,
        summary: @Composable () -> Unit,
        content: @Composable () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        // 流式输出中 open 属性可能后到；只在它变化时重新初始化状态。
        var expanded by rememberSaveable(block.open) { mutableStateOf(block.open) }
        val rotation by animateFloatAsState(if (expanded) 90f else 0f)
        Column(modifier) {
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(rotation),
                )
                Column(Modifier.weight(1f, fill = false)) {
                    summary()
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 22.dp, top = 6.dp)) {
                    content()
                }
            }
        }
    }

    /** 不带语法高亮的代码块 */
    @Composable
    fun CodeBlock(block: MarkdownBlock.CodeBlock, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.language?.let { language ->
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = block.code,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                )
            }
        }
    }

    /** 模块里没有图片加载能力，默认只显示替代文本，点击后打开图片地址 */
    @Composable
    fun Image(image: MarkdownInline.Image, link: String?, modifier: Modifier = Modifier) {
        val uriHandler = LocalUriHandler.current
        Surface(
            onClick = { runCatching { uriHandler.openUri(link ?: image.url) } },
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.Image02,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = image.alt ?: image.url,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /**
     * 默认的表格，只能在 Markdown 的插槽里调用。
     * 想在外面包一层带工具栏的卡片时，可以用 [outerBorder] 和 [shape] 去掉表格自带的边框和圆角。
     */
    @Composable
    fun Table(
        table: MarkdownBlock.Table,
        modifier: Modifier = Modifier,
        outerBorder: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape: Shape = MaterialTheme.shapes.small,
    ) {
        val columnCount = max(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
        val headers: List<@Composable () -> Unit> = table.header.mapIndexed { index, cell ->
            @Composable {
                TableCellContent(cell, table.alignments.getOrNull(index), FontWeight.SemiBold)
            }
        }
        val rows: List<List<@Composable () -> Unit>> = table.rows.map { row ->
            row.mapIndexed { index, cell ->
                @Composable {
                    TableCellContent(cell, table.alignments.getOrNull(index), null)
                }
            }
        }
        DataTable(
            headers = headers,
            rows = rows,
            modifier = modifier,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            columnAlignments = List(columnCount) { index ->
                table.alignments.getOrNull(index)?.toCellAlignment() ?: Alignment.CenterStart
            },
            bodyCellAlignment = { row, column ->
                table.rows.getOrNull(row)?.getOrNull(column)?.style?.textAlign?.toCellAlignment()
            },
            outerBorder = outerBorder,
            shape = shape,
        )
    }
}

private fun TextAlign.toCellAlignment(): Alignment? = when (this) {
    TextAlign.Unspecified -> null
    TextAlign.Center -> Alignment.Center
    TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}

@Composable
private fun TableCellContent(cell: TableCell, columnAlign: TextAlign?, fontWeight: FontWeight?) {
    val columnStyle = TextStyle(fontWeight = fontWeight, textAlign = columnAlign ?: TextAlign.Unspecified)
    // 单元格自己的样式优先于列的对齐方式和表头的字重；列宽由内容决定，单元格在列内的对齐交给表格
    MarkdownInlineContent(cell.content, style = columnStyle.merge(cell.style))
}

// 匹配酒馆状态块的外围围栏：<summary> 行后紧跟的开场 ```，到 </details> 前的收尾 ```。
// 中间不允许再出现 ```（避免误伤内容里真正的代码块），且围栏之间要有 "- " 条目（状态块的特征）。
private val DetailsFenceRegex = Regex(
    """(?i)(</summary>[^\r\n]*\r?\n)```[ \t]*\r?\n((?:(?!```)[\s\S])*?)```[ \t]*\r?\n(?=[ \t]*</details>)"""
)

private fun normalizeDetailsBlocks(content: String): String {
    if (!content.contains("<details", ignoreCase = true)) return content
    // 先剥掉状态块自己的外围 ``` 围栏
    val stripped = content.replace(DetailsFenceRegex) { match ->
        val body = match.groupValues[2]
        if (body.contains("\n- ") || body.startsWith("- ")) {
            // summary 行后补一个空行，让手写 HTML 块在这里结束，块内各段就能按统一的列表渲染
            match.groupValues[1] + "\n" + body
        } else {
            match.value
        }
    }
    // 再逐行保证 <details> 区域的空白结构：
    // - <summary> 行后补空行，让手写 HTML 块在 summary 后结束，块内内容按 Markdown 渲染；
    // - </details> 行前后补空行：没有空行时，后面的文字（如『剧情发展』的选项）会被“懒延续”
    //   进 details 里最后一个列表项跟着缩进，或作为裸文本被压成一行。
    val lines = stripped.split('\n')
    val out = ArrayList<String>(lines.size + 8)
    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trim()
        val isClose = trimmed.startsWith("</details")
        if (isClose && out.isNotEmpty() && out.last().trim().isNotEmpty()) out += ""
        out += line
        if (isClose) {
            if (i + 1 < lines.size && lines[i + 1].trim().isNotEmpty()) out += ""
            continue
        }
        if (trimmed.contains("</summary>", ignoreCase = true) &&
            (i + 1 >= lines.size || lines[i + 1].trim().isNotEmpty())
        ) {
            out += ""
        }
    }
    return out.joinToString("\n")
}

// 对话与书名号着色：和酒馆的显示习惯一致，"对话"/“对话”用橙色、《作品名》用蓝色。
// 引号必须成对出现在同一行；跳过代码围栏里的行，避免把代码里的引号也包上标签。
private val AsciiQuoteRegex = Regex("\"[^\"\r\n]*\"")
private val DialogueQuoteRegex = Regex("“[^”\n]*”")
private val TitleMarkRegex = Regex("《[^》\n]*》")

private fun colorizeQuotes(content: String): String {
    if ('“' !in content && '《' !in content && '"' !in content) return content
    val out = ArrayList<String>(content.length / 32 + 1)
    var inFence = false
    for (line in content.split('\n')) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            inFence = !inFence
            out += line
            continue
        }
        // 已有着色的行不再重复处理
        val colored = if (inFence || trimmed.contains("<font color=")) line else line
            .replace(AsciiQuoteRegex) { "<font color=\"#B5651D\">${it.value}</font>" }
            .replace(DialogueQuoteRegex) { "<font color=\"#B5651D\">${it.value}</font>" }
            .replace(TitleMarkRegex) { "<font color=\"#1E90FF\">${it.value}</font>" }
        out += colored
    }
    return out.joinToString("\n")
}
