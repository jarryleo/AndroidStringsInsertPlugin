package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.sheets.SheetsSettingsService
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import cn.jarryleo.insert_strings.xml.ModuleInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject

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
        val root = JsonObject().apply {
            addProperty("projectBase", state.project.basePath)
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
            if (editorSel != null) {
                add("editorSelection", JsonObject().apply {
                    addProperty("text", editorSel.selectedText)
                    addProperty("file", editorSel.file?.path)
                    addProperty("selectionStart", editorSel.selectionStart)
                    addProperty("selectionEnd", editorSel.selectionEnd)
                    addProperty(
                        "note",
                        "用户在 chat 入口打开时从编辑器中选中的硬编码文本。" +
                            "如果当前任务是「插入翻译」且 chatEntry=extractStrings/askAi," +
                            "AI 应把这段文本视为待翻译的原文,并在用户选「使用现有 key:<key>」时" +
                            "**必须**先调 replace_selection(key=<key>) 把这段选区替换为对 key 的引用。"
                    )
                })
            } else {
                add("editorSelection", null)
            }
            add("currentModule", contextInfo.currentModule?.let { moduleToJson(it) })
            add("modules", JsonArray().apply {
                contextInfo.modules.forEach { add(moduleToJson(it)) }
            })
            add("moduleWithMostLines", contextInfo.moduleWithMostLines?.let { moduleToJson(it) })
            add("recommendedDefaultModule", contextInfo.recommendedDefaultModule?.let { moduleToJson(it) })
            add(
                "moduleRanking",
                JsonArray().apply {
                    contextInfo.modules
                        .sortedByDescending { it.xmlFiles.size * 1_000_000 + it.totalLines }
                        .forEach { add(moduleToJson(it)) }
                }
            )
            add("availableLanguages", JsonArray().apply {
                availableLanguages.forEach { add(it) }
            })
            add("currentKeys", JsonArray().apply {
                state.keyEntries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("key", entry.key)
                        add("translations", JsonObject().apply {
                            entry.stringsInfoList.filter { it.text.isNotEmpty() }.forEach {
                                addProperty(it.language, it.text)
                            }
                        })
                    })
                }
            })
            add("googleSheets", JsonObject().apply {
                addProperty("configured", sheetsConfigured)
                addProperty("defaultSpreadsheetId", sheetsSettings.defaultSpreadsheetId)
                addProperty("defaultSheetName", sheetsSettings.defaultSheetName)
                if (availableSheetNames != null) {
                    add("availableSheets", JsonArray().apply {
                        availableSheetNames.forEach { add(it) }
                    })
                }
            })
        }
        return root.toString()
    }

    private fun moduleToJson(module: ModuleInfo): JsonObject {
        return JsonObject().apply {
            addProperty("moduleName", module.moduleName)
            addProperty("originalModuleName", module.originalModuleName)
            addProperty("modulePath", module.modulePath)
            addProperty("totalLines", module.totalLines)
            add("xmlFiles", JsonArray().apply {
                module.xmlFiles.forEach { file ->
                    add(JsonObject().apply {
                        addProperty("filePath", file.filePath)
                        addProperty("language", file.language)
                        addProperty("fileLines", file.fileLines)
                    })
                }
            })
        }
    }
}
