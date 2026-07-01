package cn.jarryleo.android.buddy.ui

/**
 * 主页面的顶级 tab。
 *
 * 重构说明(2026.x):之前是「showSettings / showChat 两个 Boolean + 主表 else 分支」的方式切换页面,
 * 多个布尔状态容易出现同时为 true 的非法组合,且顶部要单独留一行 Chat / Settings 按钮做导航。
 * 现在统一成单个 [MainTab] 枚举,UI 顶部直接渲染一个 tab 栏,点哪个就切到哪个视图。
 *
 * 四个 tab 各自的承载内容:
 * - [CHAT]         AI 聊天视图(AskAi 流式对话、引用面板、Clear 按钮、选项按钮)。
 *                  作为 Android Buddy 的「首页」,工具窗口打开时默认进入此 tab,
 *                  tab 栏中也排在第一位。
 * - [TRANSLATIONS] 选中翻译列表 / 主编辑表(key 选择器 + string name 输入 + 多语言表格 + Copy/Paste/Insert 按钮)。
 * - [TODOS]        代办列表(三秒添加 / 一键完成 / 就地编辑 / 过滤切换 / 优先级色块,
 *                  AI 可通过 `todo_*` 工具读写,系统会把 active 代办摘要注入聊天上下文,
 *                  让 AI 主动提醒用户)。
 * - [SETTINGS]     设置页(其内部仍然有 [SettingsTab] 子 tab:AI / Role / Google Sheets / Quick Phrases / Debug)。
 *
 * 与 [SettingsTab] 的关系:本枚举是顶级导航,后者只在 SETTINGS tab 内做二级切换。
 * 切到 SETTINGS 时,默认进入 [SettingsTab.AI] 子 tab(由 [InsertStringsUI] 持有 [settingsTab] 状态)。
 */
enum class MainTab {
    CHAT,
    TRANSLATIONS,
    TODOS,
    SETTINGS,
}
