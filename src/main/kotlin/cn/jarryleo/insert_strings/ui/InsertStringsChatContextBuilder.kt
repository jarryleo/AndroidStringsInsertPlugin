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
    private val ui: InsertStringsUI,
) {

    companion object {
        private const val DEFAULT_LANGUAGE = "values"
    }

    fun build(): String {
        val contextInfo = ContextManager.contextInfo ?: return ""
        // 基础集合:优先用用户在表格里选中的语言,否则用 currentModule 的 xmlFiles。
        // 始终把默认英语 "values" 包含进去,避免用户没选英语行时 AI 漏写 English,
        // 进而让 StringsWriter 把 values/strings.xml 写成空文本。
        val baseLanguages = ui.insertStringsManager.languages?.takeIf { it.isNotEmpty() }
            ?: contextInfo.currentModule?.xmlFiles?.map { it.language }
            ?: emptyList()
        val availableLanguages = if (DEFAULT_LANGUAGE in baseLanguages) baseLanguages
        else baseLanguages + DEFAULT_LANGUAGE
        // 同步把当前编辑保存到 keyEntries,保证 AI 看到的 currentKeys 是最新
        ui.actionsController.saveCurrentEdits()
        val sheetsSettings = SheetsSettingsService.getInstance(ui.project).state
        val sheetsConfigured = SheetsManager.isConfigured(ui.project)
        val availableSheetNames = ui.sheetsAvailableSheets.map { it.title }
            .takeIf { it.isNotEmpty() }

        val root = JsonObject().apply {
            add("androidProject", JsonObject().apply {
                addProperty("name", contextInfo.projectName)
                addProperty(
                    "note",
                    "Android 工程名,仅用于展示。module 参数必须用 modules[].moduleName,不要传这个 name。"
                )
            })
            add("currentModule", contextInfo.currentModule?.let { moduleToJson(it) })
            add("modules", JsonArray().apply {
                contextInfo.modules.forEach { add(moduleToJson(it)) }
            })
            add("moduleWithMostLines", contextInfo.moduleWithMostLines?.let { moduleToJson(it) })
            add("availableLanguages", JsonArray().apply {
                availableLanguages.forEach { add(it) }
            })
            add("currentKeys", JsonArray().apply {
                ui.keyEntries.forEach { entry ->
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
