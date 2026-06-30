package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.ChatAttachment
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import kotlin.math.max
import kotlin.math.min

/**
 * 聊天图片预览弹框(2026.x 多模态)。
 *
 * 用户点击聊天气泡里的图片缩略图时弹出,显示该图的原始大图。
 *
 * ## 尺寸规则(2026.x 第二轮)
 *  - 弹框宽高 = **图片原图的宽高比**;
 *  - 弹框宽 / 高 各自限制在 **[320, 600] 像素** 之间(Swing 用物理像素;
 *    96dpi 下与 320-600dp 等价;HiDPI 下像素会更大但视觉密度仍 OK);
 *  - 长宽比极端的图(如 banner / 竖长截图)会被 clamp 到边界框,同时在 box 内用
 *    `Fit` 模式渲染,保持原图比例并留白,不裁切不拉伸。
 *  - 弹框**不可 resize**(原图宽高比唯一决定尺寸,resize 改不了原图比例),resize grip
 *    没必要出现。改成「fixed 尺寸 + 可拖动」即可。
 *
 * ## 图像渲染
 *  - 弹框内显示**原图**(从 [ChatAttachment.decodeBytes] 重新解码,绕开 96dp
 *    缩略图,确保用户看到的是真原图);
 *  - 用 [Image.getScaledInstance] 等比缩放,等比缩到恰好装入 box(SCALE_SMOOTH);
 *  - 原图本身比 box 小时**不放大**(避免插值糊掉),居中显示。
 *
 * ## 关闭路径
 *  1. 点击标题栏右侧 × 按钮;
 *  2. 按 Esc 键;
 *  3. 调用 [dispose] 后[WindowEvent.WINDOW_CLOSED] 也会兜底置空 `icon` 引用,让 GC 回收。
 *
 * ## 居中
 *  - 弹窗打开时相对父窗口(或屏幕)居中。
 *
 * ## Swing 集成原因(2026.x)
 *  - 本弹框入口是 Compose `Modifier.clickable`,Compose Desktop 缺少成熟的「图片查看器」
 *    组件;复用项目里 AskAi 弹框的 JDialog 模式最稳,与 IDE 主题契合。
 *
 * @param attachment  预览的图片附件
 * @param parent      父窗口(可为 null),非 null 时弹窗相对它定位,否则居中屏幕
 */
class ImagePreviewDialog(
    private val attachment: ChatAttachment,
    parent: Window? = null,
) : JDialog() {

    private val parentWindow: Window? = parent
    private val imageLabel: JLabel = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        isOpaque = false
    }

    // 原图解码后的 BufferedImage(在 [loadOriginalImage] 中加载,缓存在字段);
    // 用 BufferedImage 而不是解码 raw bytes,方便 [Image.getScaledInstance] 复用
    // 同一个 source 多次缩放(resize 时换 scale 即可)。
    private var originalImage: java.awt.image.BufferedImage? = null

    init {
        title = "图片预览 - ${attachment.fileName}"
        isModal = false
        isAlwaysOnTop = true
        isUndecorated = true
        // 不可 resize:弹框尺寸由图片宽高比 + [MIN_BOX_PX, MAX_BOX_PX] 唯一决定,
        // resize 没有任何意义,反而会让用户困惑。
        isResizable = false
        defaultCloseOperation = DISPOSE_ON_CLOSE
        background = Color(0, 0, 0, 0)
        // Esc 关闭
        rootPane.registerKeyboardAction(
            { disposeSafely() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        // 先加载原图(在 dialog 还没 pack 之前),拿到尺寸后才能算 box 大小。
        originalImage = loadOriginalImage()
        contentPane = buildContent()
        // 用 pack() 让 Swing 算出标题栏 + 布局的「natural size」,但我们随后会
        // 覆盖成「原图宽高比」box,让 dialog 尺寸精确可控。
        pack()
        applyDesiredSize()
        centerOnScreen()
        applyRoundedShape()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                // 兜底:dispose 后置空 icon / buffered image 引用,让 GC 尽快回收。
                imageLabel.icon = null
                originalImage = null
            }
        })
    }

    /**
     * 从 [ChatAttachment.decodeBytes] 解码原图。
     *
     * 为什么不直接复用 [ChatAttachment.thumbnail]?
     *  - thumbnail 已经被 [ChatAttachment.loadFromBytes] 降采样到 96px,只是给输入框
     *    缩略图条用的,精度有限,用户点开想看「原图」时会发现仍然糊。
     *  - 原图是 2048px 长边(见 [ChatAttachment] 里的 maxEdge),能给用户更清晰的预览。
     *  - 真正想看「全分辨率」可以再用 [cn.jarryleo.insert_strings.ai.ChatAttachment.decodeBytes]
     *    的字节,这里已经是 2048px 边的降采样版 —— 真要「像素级 1:1」预览需另开
     *    外部图片查看器,2026.x 暂不做。
     */
    private fun loadOriginalImage(): java.awt.image.BufferedImage? {
        return runCatching {
            val bytes = attachment.decodeBytes()
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        }.getOrNull()
    }

    /**
     * 标题栏 + 图片区。
     */
    private fun buildContent(): JPanel {
        val borderColor = UIManager.getColor("Component.borderColor") ?: Color(0x8A8A8A)
        val titleBg = UIManager.getColor("Panel.background") ?: Color(0x2D2D2D)
        val titleFg = UIManager.getColor("Label.foreground") ?: Color(0xE0E0E0)

        val root = JPanel(BorderLayout()).apply {
            isOpaque = false
            background = Color(0, 0, 0, 0)
        }

        // 标题栏(30px 高):左 标题(可拖动)、右 × 关闭
        val titleBar = JPanel(BorderLayout()).apply {
            background = titleBg
            preferredSize = Dimension(0, TITLE_BAR_HEIGHT)
            border = EmptyBorder(0, 12, 0, 6)
        }
        val titleLabel = JLabel(attachment.fileName).apply {
            foreground = titleFg
            font = font.deriveFont(Font.BOLD, 12f)
        }
        val closeBtn = JButton("✕").apply {
            isFocusPainted = false
            isContentAreaFilled = true
            background = titleBg
            foreground = titleFg
            border = EmptyBorder(0, 0, 0, 0)
            font = font.deriveFont(Font.BOLD, 14f)
            preferredSize = Dimension(28, 22)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { disposeSafely() }
        }
        titleBar.add(titleLabel, BorderLayout.WEST)
        titleBar.add(closeBtn, BorderLayout.EAST)
        // 标题栏拖动(fixed-size 弹框依然要支持拖动,否则居中位置不灵活)
        val dragState = DragState()
        titleBar.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragState.start = e.point
            }
        })
        titleBar.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val s = dragState.start ?: return
                val loc = location
                setLocation(loc.x + e.x - s.x, loc.y + e.y - s.y)
            }
        })

        // 中部:图片区(放在一个 JLabel 里,JLabel 会按 ImageIcon 缩放)
        // 用一个 JPanel 包着 JLabel,背景 = 深色,让透明 PNG 也能看到边。
        val imageHolder = JPanel(BorderLayout()).apply {
            background = Color(0x1E1E1E)
        }
        imageHolder.add(imageLabel, BorderLayout.CENTER)

        root.add(titleBar, BorderLayout.NORTH)
        root.add(imageHolder, BorderLayout.CENTER)

        // 1px 圆角描边,通过 glassPane 画(同 AskAi,避免 LineBorder 圆角破洞)
        glassPane = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                if (!isVisible) return
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = borderColor
                g2.draw(
                    RoundRectangle2D.Double(
                        0.5, 0.5,
                        (width - 1).toDouble(),
                        (height - 1).toDouble(),
                        CORNER_RADIUS, CORNER_RADIUS,
                    )
                )
                g2.dispose()
            }
        }.apply { isVisible = true }

        return root
    }

    /**
     * 计算并应用弹框的目标尺寸(2026.x 第二轮):
     *  - 弹框宽 = clamp(原图宽, MIN_BOX_PX, MAX_BOX_PX)
     *  - 弹框高 = 弹框宽 / 原图宽高比
     *  - 但弹框高也要 clamp 到 [MIN_BOX_PX, MAX_BOX_PX]
     *  - 极端比例(细长条 / 高瘦条)会同时触顶 / 触底,此时按「先满足宽度」规则,
     *    让高度自然 break 出 box 上限,弹框最终是方框(600x600);原图按 Fit 渲染,
     *    留白居中。
     *
     * 计算完 box 后,把原图按 box 等比缩放后画到 JLabel。
     */
    private fun applyDesiredSize() {
        val img = originalImage
        val (boxW, boxH) = if (img == null || img.width <= 0 || img.height <= 0) {
            // 加载失败时用默认方框
            DEFAULT_BOX_PX to DEFAULT_BOX_PX
        } else {
            computeBox(img.width, img.height)
        }
        // dialog 总尺寸 = box + 标题栏高度
        val totalW = boxW
        val totalH = boxH + TITLE_BAR_HEIGHT
        size = Dimension(totalW, totalH)
        // 渲染图到 box 内(等比缩放;原图小于 box 时不放大)
        if (img != null) {
            imageLabel.icon = renderScaledImage(img, boxW, boxH)
        } else {
            imageLabel.icon = null
            imageLabel.text = "图片加载失败"
        }
    }

    /**
     * 给定原图宽高,算弹框的 box 宽高(2026.x)。
     *
     * 算法:以原图宽高比 `aspect = srcW / srcH` 为基准,把 box 一边顶到 [320, 600] 区间内,
     * 另一边按 aspect 推出;然后再 clamp 另一边。
     *
     *  - landscape (srcW > srcH):先 w = 600,h = 600 / aspect。若 h < 320 则反过来
     *    h = 320,w = 320 * aspect(w 可能 > 600,但取不到更小 w,只能保 h=320);
     *    类似地若 w < 320 则反过来。
     *  - portrait:对称。
     *
     * 简化:实际上只要 320 ≤ w,h ≤ 600 即可,不严格保持 aspect 也不是问题
     * (「image 填 box」天然容许 box 比例轻微失真,反正 box 已经基本贴合 aspect 了)。
     * 这里用「先固定一轴到 600,另一轴按 aspect 推出 + clamp」即可。
     */
    private fun computeBox(srcW: Int, srcH: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return DEFAULT_BOX_PX to DEFAULT_BOX_PX
        val aspect = srcW.toDouble() / srcH.toDouble()
        // 第一轮:固定 w = 600,推 h
        var w = MAX_BOX_PX
        var h = (w / aspect).toInt()
        if (h < MIN_BOX_PX) {
            // w/aspect 太扁 → 反过来固定 h = MIN,推 w
            h = MIN_BOX_PX
            w = (h * aspect).toInt()
        }
        if (w > MAX_BOX_PX) {
            // 极端细长:再 clamp 一次
            w = MAX_BOX_PX
            h = (w / aspect).toInt()
            if (h < MIN_BOX_PX) h = MIN_BOX_PX
        }
        if (w < MIN_BOX_PX) {
            // 极端高瘦:再 clamp
            w = MIN_BOX_PX
            h = (w / aspect).toInt()
            if (h > MAX_BOX_PX) h = MAX_BOX_PX
        }
        return w to h
    }

    /**
     * 把原图等比缩放到目标 box 内(不放大,原图小时居中显示)。
     *
     * 等比 = 短边取 fit 比例,长边 ≤ box 边;原图小于 box 时 scale = 1.0(不放大)。
     */
    private fun renderScaledImage(img: java.awt.image.BufferedImage, boxW: Int, boxH: Int): ImageIcon {
        val sx = boxW.toDouble() / img.width
        val sy = boxH.toDouble() / img.height
        val scale = min(1.0, min(sx, sy))
        val targetW = (img.width * scale).toInt().coerceAtLeast(1)
        val targetH = (img.height * scale).toInt().coerceAtLeast(1)
        if (targetW == img.width && targetH == img.height) {
            // 原图刚好装下 / 原图小于 box,直接用原图(不缩放)
            return ImageIcon(img)
        }
        val scaled = img.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH)
        return ImageIcon(scaled)
    }

    private fun centerOnScreen() {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val p = parentWindow
        val x = if (p != null) {
            p.location.x + (p.width - width) / 2
        } else {
            (screen.width - width) / 2
        }
        val y = if (p != null) {
            p.location.y + (p.height - height) / 2
        } else {
            (screen.height - height) / 2
        }
        setLocation(max(0, x), max(0, y))
    }

    private fun applyRoundedShape() {
        shape = RoundRectangle2D.Double(
            0.0, 0.0,
            width.toDouble(),
            height.toDouble(),
            CORNER_RADIUS, CORNER_RADIUS,
        )
    }

    private fun disposeSafely() {
        if (isShowing) dispose()
    }

    /**
     * 拖动起点状态(2026.x 简化:不存为字段,改用 [JComponent.putClientProperty]
     * 避免在 companion 中共享 state 引起的并发问题)。
     */
    private class DragState {
        var start: java.awt.Point? = null
    }

    companion object {
        const val TITLE_BAR_HEIGHT = 30
        private const val CORNER_RADIUS = 12.0

        // 弹框 box 尺寸上下限(2026.x 用户要求,像素单位):
        // 在 96dpi 下与 320-600dp 等价;HiDPI 下视觉密度仍 OK(更大但更清晰)。
        private const val MIN_BOX_PX = 320
        private const val MAX_BOX_PX = 600
        private const val DEFAULT_BOX_PX = 480

        /**
         * 静态便捷方法:在 EDT 上弹一个图片预览弹框(2026.x 多模态)。
         *
         * 推荐所有 caller 都走这个入口(避免 caller 忘 wrap 到 EDT):
         *  - Compose onClick 本来就在 EDT;
         *  - 兜底也用 invokeLater,防止别处误用。
         *
         * [parent] 传 null 时会自动用 [ComposeWindowLocator] 找当前活动窗口。
         */
        fun show(attachment: ChatAttachment, parent: Window? = null) {
            val target = parent ?: ComposeWindowLocator.findActiveWindow()
            val run = {
                val dlg = ImagePreviewDialog(attachment, target)
                dlg.isVisible = true
                dlg.toFront()
            }
            if (SwingUtilities.isEventDispatchThread()) run() else SwingUtilities.invokeLater { run() }
        }
    }
}
