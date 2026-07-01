package cn.jarryleo.android.buddy.ui

import java.awt.Component
import java.awt.Window

/**
 * Compose / AWT 跨框架的"当前活动窗口"定位器(2026.x 多模态)。
 *
 * 用途:[ImagePreviewDialog] 等需要在 Compose onClick 中弹 Swing `JDialog` 的场景,
 * 需要把父窗口传过去做 `JDialog(owner)` 或居中定位;但 Compose `onClick` 回调里没有
 * 直接拿到 Window 的途径(不像 Swing 有 `e.component` 之类)。
 *
 * 策略:
 * 1. 优先 `KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner`,
 *    沿 `getParent()` 链向上找最近的 [Window]。
 * 2. 兜底用 `Window.getWindows()` 取所有 owner-less 顶层窗口,选最后一个
 *    (通常是最近打开的工具窗口 / 弹框 / 对话框)。
 * 3. 全失败时返回 null,让 caller 把弹框 owner 设为 null + 居中屏幕。
 *
 * 注意:本方法必须在 EDT 调用(KeyboardFocusManager 与 Window.getWindows
 * 都假设 EDT 上下文);Compose `onClick` 默认就在 EDT,符合。
 */
internal object ComposeWindowLocator {
    fun findActiveWindow(): Window? {
        // 路径 1:焦点组件 → 父 Window
        runCatching {
            val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val focus = kfm.focusOwner ?: kfm.focusedWindow
            val w = walkUpToWindow(focus)
            if (w != null) return w
        }
        // 路径 2:遍历所有顶层 Window,挑最近一个
        return runCatching {
            val all = Window.getWindows()
            all.lastOrNull { it.isShowing && it.isDisplayable }
        }.getOrNull()
    }

    private fun walkUpToWindow(c: Component?): Window? {
        var cur: Component? = c ?: return null
        while (cur != null) {
            if (cur is Window) return cur
            cur = cur.parent
        }
        return null
    }
}
