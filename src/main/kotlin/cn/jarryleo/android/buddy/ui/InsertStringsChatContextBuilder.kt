package cn.jarryleo.android.buddy.ui

import cn.jarryleo.android.buddy.ai.AITranslator
import cn.jarryleo.android.buddy.ai.TodoService
import cn.jarryleo.android.buddy.sheets.SheetsManager
import cn.jarryleo.android.buddy.sheets.SheetsSettingsService
import cn.jarryleo.android.buddy.xml.ContextManager
import cn.jarryleo.android.buddy.xml.ModuleInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 构造每次 AI 调用时附带的「当前项目上下文」JSON。
 *
 * 拆分理由:这块逻辑只读 state,本身没有副作用,放在 UI 类里只是堆体积。
 */
internal class InsertStringsChatContextBuilder(
    private val state: ChatStateHolder,
) {

    companion object {
        private const val DEFAULT_LANGUAGE = "values"
        /**
         * 聊天上下文里注入 active 代办的最大条数(2026.x 优化:20 → 5)。
         * 选 5 兼顾「足够多让 AI 看到全貌」和「不爆 token」(每条约 30~60 字符,5 条 ≈ 300B JSON)。
         * 超过时 AI 想看更多细节用 todo_list 工具按需取(完整字段 content / createdAt / completedAt)。
         */
        private const val TODO_CONTEXT_LIMIT = 5
        /**
         * availableSheets 列表展示的最大条数(2026.x 优化:不再全列,只前 N 个 + 计数)。
         * 选 5 与 SheetsManager 实际用户经常用的 sheet 数对齐;真实表格可能有几十个 sheet,
         * 全列会塞进几百字符。AI 真要遍历时调 sheets_operation(operation=list_sheets) 或 query_keys。
         */
        private const val AVAILABLE_SHEETS_LIMIT = 5
        // 时间格式:本地时区的 yyyy-MM-dd HH:mm:ss,让 AI 能直接读懂"现在是几点"。
        private val HUMAN_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        // reminder 摘要里的时间格式(2026.x 新增):yyyy-MM-dd HH:mm,精度足够让人/AI 理解。
        private val REMINDER_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        private val TRIGGER_DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    fun build(): String {
        val contextInfo = ContextManager.getInstance(state.project).contextInfo ?: return ""
        // 基础集合:优先用用户在表格里选中的语言,否则用 currentModule 的 xmlFiles。
        // 始终把默认英语 "values" 包含进去,避免用户没选英语行时 AI 漏写 English,
        // 进而让 StringsWriter 把 values/strings.xml 写成空文本。
        val baseLanguages = state.insertStringsManager.languages?.takeIf { it.isNotEmpty() }
            ?: contextInfo.currentModule?.xmlFiles?.map { it.language }
            ?: emptyList()
        val availableLanguages = if (DEFAULT_LANGUAGE in baseLanguages) baseLanguages
        else baseLanguages + DEFAULT_LANGUAGE
        // 同步把当前编辑保存到 keyEntries,保证 AI 看到的 currentKeys 是最新
        state.saveCurrentEdits()
        val sheetsSettings = SheetsSettingsService.getInstance(state.project).state
        val sheetsConfigured = SheetsManager.isConfigured(state.project)
        val availableSheetNames = state.sheetsAvailableSheets.map { it.title }
            .takeIf { it.isNotEmpty() }

        val editorSel = state.editorSelection
        // 当前时间:每次 build 重新计算(确保是真实的 now,不是缓存)
        val nowMillis = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val humanTime = synchronized(HUMAN_TIME_FORMAT) {
            HUMAN_TIME_FORMAT.format(Date(nowMillis))
        }
        val root = JsonObject().apply {
            addProperty("projectBase", state.project.basePath)
            // ===== 当前时间(2026.x 新增)=====
            // 让 AI 知道「现在是什么时候」,以便在「5 分钟后提醒我喝水」这种相对时间场景下
            // 准确计算 reminderTime(否则 AI 经常瞎猜 8am)。同时给 timezone,
            // 让 AI 在「明天下午 3 点」场景下用本地时区算出正确时间戳。
            //
            // 精度:每条 user 消息都会重新 build 一次,所以这里的 now 是发送那一刻的真实时间;
            // AI 拿到后做 5*60*1000 加法即可。如果中间有慢工具调用,可用 current_time 工具
            // 重新拉一次最新的 now。
            add("now", JsonObject().apply {
                addProperty("timestamp", nowMillis)
                addProperty("formatted", humanTime)
                addProperty("timezone", tz.id)
                addProperty("offsetMinutes", tz.getOffset(nowMillis) / 60_000)
            })
            add("androidProject", JsonObject().apply {
                addProperty("name", contextInfo.projectName)
                addProperty(
                    "note",
                    "Android 工程名,仅用于展示。module 参数必须用 modules[].moduleName,不要传这个 name。"
                )
            })
            // chatEntry:当前 chat 入口标识。AI 必须根据这个字段判断 replace_selection
            // 是否必做 —— 见 system prompt 中「选择『使用现有 key:』后第一步」一节。
            //  - "mainPanel"        主面板聊天视图(InsertStringsUI)。无编辑器上下文。
            //  - "extractStrings"   用户从右键菜单触发 "Extract strings.xml" 打开的弹框,
            //                       已捕获编辑器选中的硬编码文本,driver/AI 完成 insert /
            //                       用户选「使用现有 key」时**必须** replace_selection。
            //  - "askAi"            用户从右键菜单触发 "Ask AI" 打开的弹框,若打开时编辑器
            //                       有选区,则同样捕获;与 extractStrings 相同的 replace 规则。
            addProperty("chatEntry", state.chatEntry)
            // editorSelection:把入口打开时捕获的编辑器选区快照暴露给 AI —— 取代之前要求
            // AI「自行判断」的不可靠逻辑。AI 在收到「使用现有 key」选项时,**直接看
            // editorSelection 是否为 null**:非 null 表示有硬编码文本待替换,必须先
            // replace_selection 才能继续。
            //
            // 同时也是引用面板「翻译 / 解释 / 总结」按钮的**唯一文本来源**:这些按钮
            // 不再把原文塞进 user 消息(避免与顶部引用气泡重复),AI 必须从这里的
            // `text` 字段读取原文,详见 system prompt 中「关于「引用内容」入口」一节。
            //
            // 2026.x 优化 B6:主面板场景(chatEntry=mainPanel)永远无编辑器选区,
            // 整个字段不写入 JSON,避免每轮多出 "editorSelection: null" 一行。AI 知道 chatEntry=mainPanel 即知道无选区。
            if (editorSel != null) {
                add("editorSelection", JsonObject().apply {
                    addProperty("text", editorSel.selectedText)
                    addProperty("file", editorSel.file?.path)
                    addProperty("selectionStart", editorSel.selectionStart)
                    addProperty("selectionEnd", editorSel.selectionEnd)
                    addProperty(
                        "note",
                        "用户在 chat 入口打开时从编辑器中选中的硬编码文本。" +
                                "**两个用途**:" +
                                "(1) 插入翻译流程:若 chatEntry=extractStrings/askAi 且任务为「插入翻译」," +
                                "AI 应把这段文本视为待翻译的原文,在用户选「使用现有 key:<key>」时" +
                                "**必须**先调 replace_selection(key=<key>) 把这段选区替换为对 key 的引用。" +
                                "(2) 引用面板快捷操作:用户点引用条目的「翻译 / 解释 / 总结」按钮时," +
                                "按钮只发短指令(不重复带原文),AI 必须从本字段的 `text` 拿到原文,直接返回结果,不要调任何工具。"
                    )
                })
            } else if (state.chatEntry != AITranslator.CHAT_ENTRY_MAIN_PANEL) {
                // 非主面板入口且无选区时,显式写 null(让弹框场景下 AI 明确知道「该入口没捕获到选区」)。
                add("editorSelection", null)
            }
            add("currentModule", contextInfo.currentModule?.let { moduleToJson(it) })
            // 2026.x 优化 B2:modules[] 里仅 currentModule + recommendedDefaultModule 给完整 xmlFiles,
            // 其它 module 只发 moduleName + totalLines。AI 切到其它模块时用 query_keys(module=X) 按需取详情。
            // 原始全量展开一个 5 模块 × 8 语种项目会塞 40 个 xmlFiles 节点;
            // 优化后只 1-2 个全量 + 3-4 个精简,context 段 -60-70%。
            add("modules", JsonArray().apply {
                contextInfo.modules.forEach { module ->
                    val isPrimary = module.moduleName == contextInfo.currentModule?.moduleName ||
                        module.moduleName == contextInfo.recommendedDefaultModule?.moduleName
                    add(moduleToJson(module, fullXmlFiles = isPrimary))
                }
            })
            add("recommendedDefaultModule", contextInfo.recommendedDefaultModule?.let { moduleToJson(it) })
            add("availableLanguages", JsonArray().apply {
                availableLanguages.forEach { add(it) }
            })
            // 2026.x 优化 B3:currentSelectedKeys 只发 key 名(数组元素只剩 {key: "..."}),
            // 不再带各语种翻译 —— 那些信息会随 N(选中 key 数)线性增长,极易爆 token。
            // AI 想看某 key 的翻译时,改用 read_string("<key>") 精确拉取(返回全语种文本)。
            add("currentSelectedKeys", JsonObject().apply {
                addProperty(
                    "note",
                    "用户在主面板入口 mainPanel 打开时选择的 key 列表(只发 key 名,不附带翻译文本)。" +
                        "想看具体 key 在各语种的当前翻译,用 read_string(\"<key>\")。" +
                        "上下文 recommendedDefaultModule 的 xmlFiles 列出目标模块的全语种。"
                )
                add("currentKeys", JsonArray().apply {
                    state.keyEntries.forEach { entry ->
                        add(JsonObject().apply {
                            addProperty("key", entry.key)
                        })
                    }
                })
            })
            // 2026.x 优化 B5:availableSheets 限前 N 个 + "等 X 个" 后缀,避免几十个 sheet 名撑爆 JSON。
            // 真实表格配置项中会保留完整列表,这里只裁剪 AI 上下文。
            add("googleSheets", JsonObject().apply {
                addProperty("configured", sheetsConfigured)
                addProperty("defaultSpreadsheetId", sheetsSettings.defaultSpreadsheetId)
                addProperty("defaultSheetName", sheetsSettings.defaultSheetName)
                if (availableSheetNames != null) {
                    add("availableSheets", JsonArray().apply {
                        availableSheetNames.take(AVAILABLE_SHEETS_LIMIT).forEach { add(it) }
                    })
                    val more = (availableSheetNames.size - AVAILABLE_SHEETS_LIMIT).coerceAtLeast(0)
                    if (more > 0) {
                        addProperty("availableSheetsMore", more)
                    }
                }
            })
            // ===== 代办列表(2026.x 新增) =====
            // 注入 active 代办的简要摘要 + 总数,让 AI 主动提醒用户 / 在合适时机用 todo_* 工具。
            // 只取前 [TODO_CONTEXT_LIMIT] 条 active(避免 token 爆炸),其它细节让 AI 用 todo_list 工具
            // 按需深取。
            add("todos", JsonObject().apply {
                val todoService = TodoService.getInstance()
                val active = todoService.listActive()
                addProperty("activeCount", active.size)
                addProperty("completedCount", todoService.listCompleted().size)
                // 下一条即将触发的提醒(用于「下一个提醒是什么」「还有多久提醒」类问题)
                val upcomingReminders = active
                    .mapNotNull { item -> item.reminder?.let { r -> r to item } }
                    .filter { (r, _) -> r.enabled && r.nextTriggerAt != null }
                    .sortedBy { it.first.nextTriggerAt }
                add("upcomingReminders", JsonArray().apply {
                    upcomingReminders.take(5).forEach { (r, item) ->
                        add(JsonObject().apply {
                            addProperty("id", item.id)
                            addProperty("title", item.title)
                            addProperty("nextTriggerAt", r.nextTriggerAt)
                            val at = r.nextTriggerAt
                            if (at != null) {
                                addProperty("nextTriggerAtFormatted", REMINDER_FMT.format(java.util.Date(at)))
                                addProperty("triggerInMinutes", (at - nowMillis) / 60_000L)
                            }
                            addProperty("recurrence", r.recurrence.name)
                            val tod = r.timeOfDay
                            if (tod != null) addProperty("timeOfDay", tod.format())
                        })
                    }
                })
                add("active", JsonArray().apply {
                    active
                        .sortedWith(
                            compareByDescending<cn.jarryleo.android.buddy.ai.TodoItem> { it.priority.ordinal }
                                .thenByDescending { it.createdAt }
                        )
                        .take(TODO_CONTEXT_LIMIT)
                        .forEach { item ->
                            add(JsonObject().apply {
                                addProperty("id", item.id)
                                addProperty("title", item.title)
                                addProperty("priority", item.priority.name)
                                if (item.content.isNotBlank()) {
                                    addProperty("content", item.content)
                                }
                                val r = item.reminder
                                if (r != null) {
                                    val tod = r.timeOfDay
                                    add("reminder", JsonObject().apply {
                                        addProperty("nextTriggerAt", r.nextTriggerAt)
                                        // 人类可读时间 + 距离触发的剩余分钟数,
                                        // 让 AI 不用自己转时区也能理解「还有多久触发」。
                                        val at = r.nextTriggerAt
                                        if (at != null) {
                                            addProperty(
                                                "nextTriggerAtFormatted",
                                                REMINDER_FMT.format(java.util.Date(at))
                                            )
                                            addProperty(
                                                "triggerInMinutes",
                                                (at - nowMillis) / 60_000L
                                            )
                                            // 触发日期(YYYY-MM-DD 本地),让 AI 一眼看出"是哪一天",方便回答
                                            // 「明天的提醒是啥」「这周有哪些提醒」类问题,以及让 AI 写 todo_update 时
                                            // 可以直接用 reminderDate 改日期。
                                            addProperty(
                                                "triggerDate",
                                                TRIGGER_DATE_FMT.format(java.util.Date(at))
                                            )
                                        }
                                        // 2026.x 增强:暴露 expired 字段,让 AI 看到 nextTriggerAt 在过去时
                                        // 直接知道"该提醒已过期",主动告知用户(否则只能靠 triggerInMinutes
                                        // 是负数去猜)。仅当 reminder 启用 + 有 nextTriggerAt + 已过 now 时为 true。
                                        addProperty(
                                            "expired",
                                            r.enabled && at != null && at < nowMillis
                                        )
                                        addProperty("recurrence", r.recurrence.name)
                                        if (tod != null) {
                                            addProperty("timeOfDay", tod.format())
                                        }
                                        if (r.recurrence == cn.jarryleo.android.buddy.ai.TodoRecurrence.CUSTOM) {
                                            add("recurrenceDays", JsonArray().apply {
                                                r.recurrenceDays.sorted().forEach { add(it) }
                                            })
                                        }
                                    })
                                }
                            })
                        }
                })
                addProperty(
                    "note",
                    "这是用户主页 Todo tab 维护的待办清单(active 部分前 ${TODO_CONTEXT_LIMIT} 条)。" +
                        "AI 可通过 todo_list / todo_add / todo_update / todo_delete 工具读写," +
                        "完整字段(content / completedAt / reminder 等)用 todo_list 拿。" +
                        "upcomingReminders 是按 nextTriggerAt 升序的最近 5 条(用于「下一个提醒」类问题);" +
                        "每条 reminder 里有 triggerDate(YYYY-MM-DD 本地)字段,告诉 AI 提醒落在哪一天。" +
                        "**典型用法**:用户说「提醒我 X」「记下 Y」时,调 todo_add;" +
                        "用户说「5 分钟后提醒我喝水」时,**先调 current_time 拿最新 timestamp**(上下文里的 now 可能已过时)," +
                        "再算 timestamp + 5*60*1000,再调 todo_add(title='喝水', reminderTime=..., recurrence='NONE');" +
                        "用户说「3 月 15 日上午 10 点提醒我交季报」时,基于 current_time + timezone 算出 2026-03-15 这个日期," +
                        "**优先**调 todo_add(title='交季报', reminderDate='2026-03-15', reminderTimeOfDay='10:00')," +
                        "系统按本地时区组装 timestamp,AI 不用自己跨日跨年;" +
                        "用户说「明天下午 3 点」时,基于 current_time 的 timestamp + timezone 算次日 15:00 本地时间戳,再调 todo_add;" +
                        "用户说「每周一三五提醒开会」时,算下一次匹配日的时间戳,再调 todo_add(title='开会', reminderTime=..., recurrence='CUSTOM', recurrenceDays=[1,3,5]);" +
                        "用户问「我有什么待办」/「都做完了吗」时,调 todo_list 拿完整数据,本字段只够简单提醒场景。"
                )
            })
        }
        return root.toString()
    }

    private fun moduleToJson(module: ModuleInfo, fullXmlFiles: Boolean = true): JsonObject {
        return JsonObject().apply {
            addProperty("moduleName", module.moduleName)
            addProperty("originalModuleName", module.originalModuleName)
            addProperty("modulePath", module.modulePath)
            addProperty("totalLines", module.totalLines)
            if (fullXmlFiles) {
                add("xmlFiles", JsonArray().apply {
                    module.xmlFiles.forEach { file ->
                        add(JsonObject().apply {
                            addProperty("filePath", file.filePath)
                            addProperty("language", file.language)
                            addProperty("fileLines", file.fileLines)
                        })
                    }
                })
            } else {
                // 2026.x 优化 B2:非主模块精简模式 —— 只发语言目录名(无 filePath / fileLines)。
                // AI 切到该模块时用 query_keys(module=X) 按需拉详情。
                add("languages", JsonArray().apply {
                    module.xmlFiles.forEach { add(it.language) }
                })
            }
        }
    }
}
