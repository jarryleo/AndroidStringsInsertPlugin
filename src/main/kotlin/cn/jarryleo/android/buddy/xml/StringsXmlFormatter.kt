package cn.jarryleo.android.buddy.xml

/**
 * strings.xml 缩进与空白整理工具。
 *
 * 解决问题:Android 项目的 strings.xml 缩进风格多样(2 空格 / 4 空格 / Tab),
 * 插件写入新节点时不应硬编码 `\t`,而应跟随文件自身风格;
 * 删除节点时不应留连续空行。
 *
 * 使用约定:
 * - [detectIndent]   探测 <resources> 范围内子元素的缩进字符串
 * - [buildInsertLine] 生成单行插入文本(不含前导换行,带尾部换行)
 * - [collapseBlankLines] 折叠 3+ 个连续换行 + 空白行为最多 1 个空行
 */
object StringsXmlFormatter {

    /** 默认缩进:2 空格(Android Studio 新建 strings.xml 的默认值)。 */
    const val DEFAULT_INDENT = "  "

    /**
     * 探测 <resources> 范围内子元素的缩进风格。
     *
     * 取所有顶层子元素行首空白中出现次数最多的,作为推断结果:
     * - 文件没有 <resources> 标签,或 <resources> 内无子元素:返回 [DEFAULT_INDENT]
     * - 所有子元素行首 0 缩进(扁平格式):返回 ""
     * - 出现次数最多的非空缩进;并列时取最短的(2 空格 vs 4 空格时优先 2 空格)
     *
     * @param xml 完整 XML 文本
     * @return 推断出的缩进字符串(可能是 ""、若干空格、"\t" 等)
     */
    fun detectIndent(xml: String): String {
        val resourcesContent = extractResourcesContent(xml) ?: return DEFAULT_INDENT
        val counts = HashMap<String, Int>()
        val regex = Regex("""(?m)^([ \t]*)(?=\S)""")
        regex.findAll(resourcesContent).forEach { m ->
            val indent = m.groupValues[1]
            counts[indent] = (counts[indent] ?: 0) + 1
        }
        if (counts.isEmpty()) return DEFAULT_INDENT
        val maxCount = counts.values.max()
        val topCandidates = counts.filterValues { it == maxCount }.keys
        // 优先选非空缩进(更可能是真正的缩进),并列时取最短的
        return topCandidates.firstOrNull { it.isNotEmpty() }
            ?: topCandidates.minBy { it.length }
    }

    /**
     * 生成单行插入文本(不含前导换行,带尾部换行)。
     *
     * 调用方需保证插入位置在"前一行末尾之后"(即下一行的行首),
     * 这样插入结果 = 缩进 + 节点 + 换行,新行紧跟在前一行后,无空行也无粘连。
     */
    fun buildInsertLine(indent: String, node: String): String = "$indent$node\n"

    /**
     * 折叠连续空行:3+ 个"换行 + 空白行"序列变成 2 个换行(即最多保留 1 个空行)。
     *
     * 删除节点后调用,把可能产生的连续空行整理为最多 1 个空行,保持排版整洁。
     */
    fun collapseBlankLines(xml: String): String {
        return xml.replace(Regex("""(\n[ \t]*){3,}"""), "\n\n")
    }

    /**
     * 提取 <resources>...</resources> 之间的内容(不含外层标签)。
     * @return 内容字符串;若没找到 <resources> 标签返回 null
     */
    private fun extractResourcesContent(xml: String): String? {
        val match = Regex("""<resources\b[^>]*>([\s\S]*?)</resources>""").find(xml) ?: return null
        return match.groupValues[1]
    }
}
