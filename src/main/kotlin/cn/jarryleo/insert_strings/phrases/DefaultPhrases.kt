package cn.jarryleo.insert_strings.phrases

import java.util.UUID
import kotlin.random.Random

/**
 * 出厂内置的 6 条快捷短语。
 *
 * 颜色在每次构造时随机生成(HSL 色相均匀分布,饱和度/亮度固定),
 * 让多用户 / 多设备看上去更不一样,也方便快速分辨。
 *
 * `isDeletable = false` 表示这些是「出厂内置」,不能删,可以改、可以拖;
 * 用户自建的默认 isDeletable = true。Reset Defaults 时只会重置 isDeletable = false 的条目。
 */
internal object DefaultPhrases {

    /**
     * 构造一份新的默认短语列表,每次调用都生成新的 id 和随机颜色。
     * - 不依赖外部状态(纯函数);
     * - 给同一组 title/text 多次调用会产生不同 id,适合"重置"或"首次安装"场景。
     */
    fun build(): List<QuickPhrase> {
        // 6 条短语,色相在 0..360 上均匀分布,饱和度 70%、亮度 50%(确保对比度足够,在浅色 / 深色 IDE 主题上都可读)。
        // 固定随机种子让"reset defaults"每次产生的颜色一致(用户反复点 reset 看到的颜色不会变),
        // 避免给用户造成"点了 reset 数据变了"的困惑。
        val rng = Random(SEED)
        val seeds = listOf(
            Seed(
                title = "strings选中的翻译检测",
                text = "帮我检查选中的翻译是否准确，生成总结报告，提供修复方案，并询问用户是否修正，提供对应选项按钮。"
            ),
            Seed(
                title = "strings选中的翻译修复",
                text = "帮我检查选中的翻译是否准确， 并修正错误的翻译和遗漏的翻译，不必再次询问，直接执行。"
            ),
            Seed(
                title = "表格翻译检测",
                text = "帮我检查表格内的翻译是否准确，生成总结报告，提供修复方案，并询问用户是否修正，提供对应选项按钮。"
            ),
            Seed(
                title = "表格翻译修复",
                text = "帮我检查表格内的翻译是否准确，并修正错误的翻译，补充遗漏的翻译。"
            ),
            Seed(
                title = "选中翻译插入表格",
                text = "帮我把选中的翻译插入表格，需要按表格原有格式插入，每个语言的翻译准确的插入对应的列中，" +
                    "若选中的翻译语种和表格内语种数量不一致，按表格已有的语种插入即可，" +
                    "若选中的翻译内没有表格内对应语种的翻译，则自动补全翻译插入对应表格，" +
                    "若表格内翻译有分组，一定要把翻译插入安卓分组的行内末尾，不要影响其它分组的翻译，" +
                    "切记，只插入表格，请勿修改string.xml 文件。"
            ),
            Seed(
                title = "表格翻译插入文件",
                text = "如果我有选中的翻译内容，则在表格内查找对应的翻译内容修正我选择的翻译，并修改对应的strings.xml文件，" +
                    "如果有不准确的翻译，提醒我以strings.xml 为准还是以表格内的翻译为准，" +
                    "如果两者翻译都不准确则给出准确的翻译让用户选择，须提供上面的选择按钮给用户快速选择，" +
                    "表格内没有的翻译，则你自动补全，重要：若翻译内的占位符不符合安卓的占位符规则，请替换成安卓占位符。" +
                    "如果我没有选中的翻译内容，查看表格翻译是否有分组，有分组则只取安卓端的翻译，无分组取全部翻译，" +
                    "若有key的情况，按key读取翻译插入 strings.xml 文件，" +
                    "没有key的情况，以简体中文为基准，自动生成key插入 strings.xml 文件。"
            ),
        )
        return seeds.map { seed ->
            QuickPhrase(
                id = UUID.randomUUID().toString(),
                title = seed.title,
                text = seed.text,
                color = randomColor(rng),
                isDeletable = false,
            )
        }
    }

    private data class Seed(val title: String, val text: String)

    private const val SEED = 20260622L

    /**
     * 随机生成一个 hex 颜色字符串(`#RRGGBB`)。
     * HSL 模型:色相随机,饱和度 70%,亮度 50% — 多数主题下都能保证可读性。
     */
    private fun randomColor(rng: Random): String {
        val h = rng.nextInt(0, 360)
        val s = 0.70
        val l = 0.50
        val rgb = hslToRgb(h.toDouble(), s, l)
        return "#%02X%02X%02X".format(rgb[0], rgb[1], rgb[2])
    }

    /**
     * 标准 HSL -> RGB 转换(h ∈ [0,360), s,l ∈ [0,1])。
     * 返回 [r,g,b] ∈ [0,255]。
     */
    private fun hslToRgb(h: Double, s: Double, l: Double): IntArray {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val hh = (h % 360) / 60.0
        val x = c * (1 - kotlin.math.abs(hh % 2 - 1))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        return intArrayOf(
            ((r1 + m) * 255).toInt().coerceIn(0, 255),
            ((g1 + m) * 255).toInt().coerceIn(0, 255),
            ((b1 + m) * 255).toInt().coerceIn(0, 255),
        )
    }
}
