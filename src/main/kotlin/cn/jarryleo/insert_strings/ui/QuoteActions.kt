package cn.jarryleo.insert_strings.ui

/**
 * 引用气泡底部的预置功能按钮对应的提示词模板与工具方法。
 *
 * 设计目标:
 *  - 让 AskAi / ExtractStrings 入口捕获的"选区文本"无需用户手写 prompt,
 *    一键触发 AI 完成翻译 / 解释 / 总结 / 复制。
 *  - 提示词统一收敛在这里,方便后续:
 *      1. 调整措辞不必碰 UI 代码;
 *      2. 让 translator / context builder 也能引用同一份措辞,避免 AI 看到不一致的指令;
 *      3. 单元测试覆盖。
 *
 * 重要设计 —— 短指令而非完整 prompt:
 *  - 旧的"按钮式 prompt"会把引用文本用 ``` 包裹后整段塞进 user 消息,导致 user 气泡
 *    与顶部的引用气泡内容重复(且通常引用很长),视觉上很占空间。
 *  - 新版改为**只发一句短指令**(如"请翻译引用内容。"),AI 从上下文 JSON 的
 *    `editorSelection.text` 字段直接拿到原文 —— 这个字段由
 *    [cn.jarryleo.insert_strings.ui.InsertStringsChatContextBuilder] 注入,AskAi /
 *    ExtractStrings 入口始终会带。
 *  - AI 的处理逻辑在 system prompt 的「关于「引用内容」入口」一节统一说明,
 *    本文件不再重复约束(避免维护两份散落的指令)。
 *  - 复制是唯一一个不发消息的(走 [onCopy] 而非 [onQuickSend])。
 *
 * 为什么不回退为"old style":
 *  - 当 editorSelection 为 null 时(理论上 main panel 不会显示引用气泡,这里只是
 *    兜底),AI 拿不到原文会困惑。这是已知的边界场景 —— 引用面板只在 askAi /
 *    extractStrings 入口出现,而这两个入口在打开时一定会设置 editorSelection。
 *    若未来出现"只设 quoteContent 不设 editorSelection"的入口,再回到带文本的版本。
 */
internal object QuoteActions {

    enum class Kind(val label: String) {
        TRANSLATE("翻译"),
        EXPLAIN("解释"),
        SUMMARIZE("总结"),
        COPY("复制"),
    }

    /**
     * 引用面板底部 4 个预置按钮的固定顺序(影响渲染顺序)。
     * 复制放最后 —— 它是唯一一个不发消息的(走 [onCopy] 而非 [onQuickSend])。
     */
    val all: List<Kind> = listOf(Kind.TRANSLATE, Kind.EXPLAIN, Kind.SUMMARIZE, Kind.COPY)

    /**
     * 把选中文本包成 user 消息,作为 AI 一轮请求的输入。
     *
     * 新版:只发一句短指令,实际原文由 AI 从上下文 `editorSelection.text` 读取。
     * 保留 [quotedText] 参数仅为签名稳定,实际不再使用(切换回"old style"时不用改 UI)。
     *
     * 调用方应在 chatSending=false 时调用 —— 按钮在被禁用的状态下不会触发。
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildPrompt(kind: Kind, quotedText: String): String = when (kind) {
        Kind.TRANSLATE -> "请把引用内容翻译成中文。"
        Kind.EXPLAIN -> "请用简洁清晰的中文解释引用内容,重点说明其含义、用途和关键逻辑。"
        Kind.SUMMARIZE -> "请用简洁的中文对引用内容进行总结,提炼关键点和核心信息(尽量用列表 / 要点形式,字数 ≤ 原文 1/3)。"
        Kind.COPY -> error("复制 不发消息,应走 onCopy 回调")
    }
}
