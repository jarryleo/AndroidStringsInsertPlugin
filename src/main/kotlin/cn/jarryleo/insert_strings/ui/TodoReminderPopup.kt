package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.TodoItem
import cn.jarryleo.insert_strings.ai.TodoRecurrence
import cn.jarryleo.insert_strings.ai.TodoReminder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
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
import javax.swing.UIManager

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
 * **关键坑(2026.x 修复)**:之前 dispose() 会触发 windowClosed 监听,
 * 而 windowClosed 又调 onChoice(Choice.DONE),结果用户点「5 分钟后」按钮时,
 * 1) disposeWith 先调 onChoice(SNOOZE_5M) → reminder = now+5min
 * 2) dispose() → windowClosed → onChoice(DONE) → reminder = null
 * 最后 reminder 被清空,代办列表的闹钟图标消失,详情里的 nextTriggerAt 也不更新。
 * 修复:用 [choiceLock] AtomicBoolean 保证 onChoice 只触发一次。
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
            // 不预设固定高度,改用 min/preferred 让内容自适应(标题 / 内容太长时撑高)
            preferredSize = Dimension(380, 200)
            minimumSize = Dimension(380, 160)
        }

        // ===== 顶部:图标 + 标题 =====
        val header = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
        }
        val iconLabel = JLabel("⏰").apply {
            font = font.deriveFont(20f)
            foreground = fg
        }
        // 标题用 <html> 包裹,自动按可用宽度换行;不再依赖 wrapText 的字符级硬切。
        val titleText = item.title.ifBlank { "(untitled)" }
        val titleLabel = JLabel("<html><body style='width:280px'>" + escapeHtml(titleText) + "</body></html>").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            foreground = fg
        }
        header.add(iconLabel, BorderLayout.WEST)
        header.add(titleLabel, BorderLayout.CENTER)
        root.add(header, BorderLayout.NORTH)

        // ===== 中部:内容 + 下次提醒时间 =====
        val center = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
        }
        if (item.content.isNotBlank()) {
            // 内容用 JTextArea 自动换行(wrapStyleWord=true 按词换行,不像 JLabel 那样用 <html>)。
            val contentArea = JTextArea(item.content).apply {
                isOpaque = false
                isEditable = false
                isFocusable = false
                lineWrap = true
                wrapStyleWord = true
                foreground = fg
                font = font.deriveFont(12f)
                border = null
                // 限制宽度以触发换行;高度由内容决定
                size = Dimension(340, Short.MAX_VALUE.toInt())
            }
            contentArea.preferredSize = Dimension(340, contentArea.preferredSize.height.coerceAtLeast(20))
            contentArea.alignmentX = Component.LEFT_ALIGNMENT
            center.add(contentArea)
            center.add(Box.createVerticalStrut(6))
        }
        val nextLabel = JLabel("<html><body style='width:340px'>" + escapeHtml(buildNextLabel(reminder)) + "</body></html>").apply {
            foreground = fg
            font = font.deriveFont(Font.PLAIN, 11f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        center.add(nextLabel)
        center.add(Box.createVerticalGlue())
        root.add(center, BorderLayout.CENTER)

        // ===== 底部:按钮(完成 / 1m / 5m / 10m) =====
        val buttons = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(0, 0, 0, 0)
        }
        c.gridx = 0
        buttons.add(buildButton("完成", dangerBg, dangerFg, primary = true) { disposeWith(Choice.DONE) }, c)
        c.gridx = 1
        c.insets = Insets(0, 6, 0, 0)
        buttons.add(buildButton("1 分钟后", accentBg, accentFg) { disposeWith(Choice.SNOOZE_1M) }, c)
        c.gridx = 2
        buttons.add(buildButton("5 分钟后", secondaryBg, secondaryFg) { disposeWith(Choice.SNOOZE_5M) }, c)
        c.gridx = 3
        buttons.add(buildButton("10 分钟后", secondaryBg, secondaryFg) { disposeWith(Choice.SNOOZE_10M) }, c)
        root.add(buttons, BorderLayout.SOUTH)

        return root.also { applyForegroundRecursively(it, fg) }
    }

    private fun buildButton(
        text: String,
        bg: Color,
        fg: Color,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): JButton {
        val btn = JButton(text)
        btn.background = bg
        btn.foreground = fg
        btn.isFocusPainted = false
        btn.isContentAreaFilled = true
        btn.isOpaque = true
        btn.font = if (primary) btn.font.deriveFont(Font.BOLD, 12f) else btn.font.deriveFont(12f)
        btn.margin = Insets(4, 10, 4, 10)
        btn.addActionListener { onClick() }
        return btn
    }

    private fun buildNextLabel(reminder: TodoReminder): String {
        val now = timeFormatter.format(Date())
        val next = reminder.nextTriggerAt?.let { timeFormatter.format(Date(it)) } ?: "-"
        val recurrenceDesc = when (reminder.recurrence) {
            TodoRecurrence.NONE -> "一次性"
            TodoRecurrence.DAILY -> "每日"
            TodoRecurrence.WEEKDAYS -> "工作日"
            TodoRecurrence.WEEKLY -> "每周"
            TodoRecurrence.CUSTOM -> {
                val days = reminder.recurrenceDays.sorted().joinToString(",")
                "自定义($days)"
            }
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
        // 先用 CAS 抢锁:抢到就跑 onChoice;抢不到说明 windowClosed 已经处理过(用户 X 关闭的并发场景),
        // 直接 dispose 即可,避免覆盖真实选择。
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

    /**
     * HTML 文本最小转义(只处理 & < >),用于把含特殊字符的 title / next label 嵌入 <html>...</html>。
     * 不做完整转义,够 JLabel 渲染用。
     */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
