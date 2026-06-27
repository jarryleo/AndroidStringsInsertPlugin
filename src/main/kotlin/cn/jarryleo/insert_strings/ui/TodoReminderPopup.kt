package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.TodoItem
import cn.jarryleo.insert_strings.ai.TodoRecurrence
import cn.jarryleo.insert_strings.ai.TodoReminder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.AbstractBorder
import javax.swing.border.Border

/**
 * 单条提醒的「右下角弹框」(非模态、always-on-top)。
 *
 * 为什么是 JDialog 而不是 NotificationGroup balloon?
 * - balloon 只能放 action 链接,放不下「1 分钟 / 5 分钟 / 10 分钟 / 完成」这种多按钮;
 * - 弹框显示期间,即便用户切到别的 IDE / 浏览器,也能继续看到内容(always-on-top);
 *   balloon 在切走后会收起,用户可能错过提醒。
 *
 * 视觉:用 IDE 主题色 (`Notification.background` / `Notification.foreground`) 渲染,
 * 与原生通知风格一致;不引入新依赖。
 *
 * 生命周期:点击任一按钮或关闭弹框都会 dispose;
 * 关闭按钮(标题栏 ×)与「完成」按钮语义不同 —— 关闭按钮表示"我看到了",
 * 行为与「完成」一致(「完成」语义按 [onChoice] 回调解释为 dismiss)。
 *
 * **关键坑(2026.x 修复)**:
 * 1. `dispose()` 会触发 `windowClosed` 监听,若 `windowClosed` 又调 `onChoice`,
 *    会把按钮的选择覆盖成 "DONE"(导致 reminder 被清空)。用 [choiceLock] 互斥。
 * 2. 默认 JButton + `isContentAreaFilled=true` 的背景是矩形,会超出圆角边框。
 *    用 [RoundedButton] 自定义 paintComponent 让背景填在圆角内。
 * 3. JLabel + HTML body width 在 BorderLayout.CENTER 不一定换行。
 *    标题 / 内容 / 副文本全部改用 JTextArea + lineWrap=true,显式设置 width 触发换行。
 *
 * @param item    被提醒的 todo(展示 title + content)
 * @param reminder 触发时的 reminder 副本(用于显示「下一次提醒时间」)
 * @param onChoice 用户在弹框里做的选择,由 scheduler 负责后续写库
 *                 (完成 / 1m / 5m / 10m 四个 enum 之一)。
 */
class TodoReminderPopup(
    private val item: TodoItem,
    private val reminder: TodoReminder,
    private val onChoice: (Choice) -> Unit,
) : JDialog() {

    /**
     * 用户在弹框里做的选择。决定 scheduler 后续如何写 [TodoItem.reminder]。
     */
    enum class Choice {
        /** 「完成提醒」按钮:一次性 → 清掉 reminder;循环 → 滚动到下一次 nextTriggerAt。 */
        DONE,

        /** 「1 分钟后再提醒」按钮:nextTriggerAt = now + 1min,recurrence 保持不变。 */
        SNOOZE_1M,

        /** 「5 分钟后再提醒」按钮:nextTriggerAt = now + 5min,recurrence 保持不变。 */
        SNOOZE_5M,

        /** 「10 分钟后再提醒」按钮:nextTriggerAt = now + 10min,recurrence 保持不变。 */
        SNOOZE_10M,
    }

    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    // 保证 onChoice 永远只触发一次:既被「点按钮」也走,也被「X 关闭」也走,
    // 但不能让两条路径都跑(否则后者会覆盖前者的选择)。
    private val choiceLock = AtomicBoolean(false)

    // 弹框内文本统一可用宽度 = 总宽 380 - 左右内边距 14*2 = 352,再减 2 边距给到 348
    private val textWidth = 348

    init {
        title = "⏰ 待办提醒"
        isModal = false
        isAlwaysOnTop = true
        defaultCloseOperation = DISPOSE_ON_CLOSE
        // 用户点 × 关闭时与「完成」等价,让「暂时不处理」也有一条可解释的反馈路径。
        // 用 choiceLock 防止和「点按钮」的 disposeWith 二次触发。
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                if (choiceLock.compareAndSet(false, true)) {
                    onChoice(Choice.DONE)
                }
            }
        })
        contentPane = buildContent()
        pack()
        positionBottomRight()
        // 永远把弹框带到最前(同时不影响其它窗口焦点)
        toFront()
    }

    private fun buildContent(): JPanel {
        val bg = uiColor("Notification.background", "Panel.background", fallback = Color(0x323232))
        val fg = uiColor("Notification.foreground", "Label.foreground", fallback = Color.WHITE)
        val borderColor = uiColor("Component.borderColor", fallback = Color(0x4A4A4A))
        val accentBg = uiColor("Button.default.background", "Button.background", fallback = Color(0x3574F0))
        val accentFg = uiColor("Button.default.foreground", fallback = Color.WHITE)
        val secondaryBg = uiColor("Button.background", "Panel.background", fallback = Color(0x4A4A4A))
        val secondaryFg = uiColor("Button.foreground", "Label.foreground", fallback = Color.WHITE)
        val dangerBg = Color(0x16A34A)
        val dangerFg = Color.WHITE

        val root = JPanel(BorderLayout()).apply {
            background = bg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1),
                BorderFactory.createEmptyBorder(10, 14, 10, 14),
            )
            preferredSize = Dimension(380, 200)
            minimumSize = Dimension(380, 160)
        }

        // ===== 顶部:图标 + 标题(用 JTextArea 保证换行)=====
        val header = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
        }
        val iconLabel = JLabel("⏰").apply {
            font = font.deriveFont(20f)
            foreground = fg
            verticalAlignment = javax.swing.SwingConstants.TOP
        }
        val titleText = item.title.ifBlank { "(untitled)" }
        val titleArea = makeTextArea(titleText, 14f, Font.BOLD, fg, textWidth - 28)
        header.add(iconLabel, BorderLayout.WEST)
        header.add(titleArea, BorderLayout.CENTER)
        root.add(header, BorderLayout.NORTH)

        // ===== 中部:内容 + 下次提醒时间 =====
        val center = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
        }
        if (item.content.isNotBlank()) {
            val contentArea = makeTextArea(item.content, 12f, Font.PLAIN, fg, textWidth)
            center.add(contentArea)
            center.add(Box.createVerticalStrut(6))
        }
        val nextArea = makeTextArea(buildNextLabel(reminder), 11f, Font.PLAIN, fg, textWidth)
        center.add(nextArea)
        center.add(Box.createVerticalGlue())
        root.add(center, BorderLayout.CENTER)

        // ===== 底部:按钮(完成 / 1m / 5m / 10m) ——
        // 用 RoundedButton 自定义绘制,让背景色填在圆角边框内部,不会溢出。
        val buttons = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(0, 0, 0, 0)
        }
        c.gridx = 0
        buttons.add(RoundedButton("完成", dangerBg, dangerFg, primary = true) { disposeWith(Choice.DONE) }, c)
        c.gridx = 1
        c.insets = Insets(0, 6, 0, 0)
        buttons.add(RoundedButton("1 分钟后", accentBg, accentFg) { disposeWith(Choice.SNOOZE_1M) }, c)
        c.gridx = 2
        buttons.add(RoundedButton("5 分钟后", secondaryBg, secondaryFg) { disposeWith(Choice.SNOOZE_5M) }, c)
        c.gridx = 3
        buttons.add(RoundedButton("10 分钟后", secondaryBg, secondaryFg) { disposeWith(Choice.SNOOZE_10M) }, c)
        root.add(buttons, BorderLayout.SOUTH)

        // 设置根 panel 的子组件前景(给非显式着色的子组件兜底)
        return root.also { applyForegroundRecursively(it, fg) }
    }

    /**
     * 构造一个会自动按词换行 + 固定宽度的 JTextArea。
     * 关键点:
     * - `lineWrap=true` + `wrapStyleWord=true` 让长内容按词换行;
     * - `setSize` + `setMaximumSize` + `setPreferredSize` 三处都设成 [width],
     *   强制 BoxLayout / BorderLayout 拿到我们期望的宽度来计算换行;
     * - `alignmentX = LEFT_ALIGNMENT` 避免 BoxLayout 把组件拉到全宽导致不换行。
     */
    private fun makeTextArea(
        text: String,
        fontSize: Float,
        fontStyle: Int,
        foreground: Color,
        width: Int,
    ): JTextArea {
        val area = JTextArea(text)
        area.isOpaque = false
        area.isEditable = false
        area.isFocusable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        area.foreground = foreground
        area.font = area.font.deriveFont(fontStyle, fontSize)
        area.border = null
        area.margin = Insets(0, 0, 0, 0)
        area.size = Dimension(width, area.preferredSize.height)
        area.preferredSize = Dimension(width, area.preferredSize.height)
        area.maximumSize = Dimension(width, Int.MAX_VALUE)
        area.minimumSize = Dimension(width, area.preferredSize.height)
        area.alignmentX = Component.LEFT_ALIGNMENT
        return area
    }

    @Suppress("DEPRECATION")
    private fun buildNextLabel(reminder: TodoReminder): String {
        val now = timeFormatter.format(Date())
        val next = reminder.nextTriggerAt?.let { timeFormatter.format(Date(it)) } ?: "-"
        val recurrenceDesc = when (reminder.recurrence) {
            TodoRecurrence.NONE -> "一次性"
            TodoRecurrence.DAILY -> "每日"
            TodoRecurrence.CUSTOM -> {
                val days = reminder.recurrenceDays.sorted().joinToString(",")
                "自定义($days)"
            }
            // 兼容老数据:setter 已自动迁移,这里兜底按 CUSTOM 显示。
            TodoRecurrence.WEEKDAYS, TodoRecurrence.WEEKLY -> "自定义(${reminder.recurrenceDays.sorted().joinToString(",")})"
        }
        val timeOfDay = reminder.timeOfDay?.format() ?: "-"
        return "触发:$now  ·  循环:$recurrenceDesc  ·  下次:$next  ·  时分:$timeOfDay"
    }

    private fun positionBottomRight() {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
        val margin = 24
        val x = screen.width - width - insets.right - margin
        val y = screen.height - height - insets.bottom - margin
        setLocation(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    private fun disposeWith(choice: Choice) {
        if (choiceLock.compareAndSet(false, true)) {
            onChoice(choice)
        }
        dispose()
    }

    private fun applyForegroundRecursively(c: Component, fg: Color) {
        if (c is JLabel || c is JButton) c.foreground = fg
        if (c is java.awt.Container) {
            for (child in c.components) applyForegroundRecursively(child, fg)
        }
    }

    private fun uiColor(vararg keys: String, fallback: Color): Color {
        val c = keys.firstNotNullOfOrNull(UIManager::getColor)
        return c ?: fallback
    }
}

/**
 * 圆角按钮(2026.x 新增)。
 *
 * 解决默认 JButton 的两个问题:
 * 1. `isContentAreaFilled = true` 时 Swing 画矩形背景,**超出圆角边框**;
 * 2. 默认 Look & Feel 边框是矩形,看起来不协调。
 *
 * 做法:
 * - `isContentAreaFilled = false` 关闭 Swing 默认背景绘制;
 * - 自定义 `paintComponent` 用 `fillRoundRect` 在圆角内部填色;
 * - 自定义 `paintBorder` 用 `drawRoundRect` 描一个圆角边框;
 * - 二者共用同一个圆角半径,视觉上背景"刚好填在边框内"。
 */
private class RoundedButton(
    text: String,
    private val bgColor: Color,
    private val fgColor: Color,
    private val borderColor: Color = bgColor,
    private val radius: Int = 6,
    private val primary: Boolean = false,
    onClick: () -> Unit,
) : JButton(text) {

    init {
        foreground = fgColor
        isFocusPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = true
        border = RoundedBorder(radius, borderColor)
        font = if (primary) font.deriveFont(Font.BOLD, 12f) else font.deriveFont(12f)
        margin = Insets(4, 10, 4, 10)
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        // 用我们的 paintComponent + border 自行绘制,屏蔽 L&F 的默认按钮 UI。
        isFocusable = true
        addActionListener { onClick() }
    }

    /**
     * 在圆角范围内填背景色。注意:这里直接用 width/height 是因为 fillRoundRect
     * 会自己把形状画在矩形范围内,不会超出圆角路径。
     */
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bgColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * 圆角边框(2026.x 新增)。
 * 配合 [RoundedButton] 使用,让边框也是圆角,与背景的圆角对齐。
 */
private class RoundedBorder(
    private val radius: Int,
    private val color: Color,
) : AbstractBorder() {

    override fun paintBorder(
        c: Component?,
        g: Graphics?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (g == null) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component?): Insets = Insets(2, 2, 2, 2)
}
