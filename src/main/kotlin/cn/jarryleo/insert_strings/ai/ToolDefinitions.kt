package cn.jarryleo.insert_strings.ai

import cn.jarryleo.insert_strings.ai.AiAction.SheetsOperation
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 工具定义集合:把 [AiAction] 类型映射为 OpenAI / Anthropic 的 function calling schema。
 *
 * 工具列表设计原则:
 * 1. 每个 [AiAction] 子类对应一个 tool,模型通过原生 function calling 调用。
 * 2. `task_complete` 是唯一的「合法终止」信号:模型不调用它,系统就持续驱动工具循环。
 * 3. 复杂工具的详细用法(枚举值/约束/示例)由 `load_tool_doc` 按需注入,主 prompt 保持精简。
 */
object ToolDefinitions {

    /** OpenAI / OpenAI 兼容协议的 tools 数组(放在 request body 的 `tools` 字段)。 */
    val openAiTools: JsonArray = buildOpenAiTools()

    /** Anthropic Messages API 的 tools 数组(放在 request body 的 `tools` 字段)。 */
    val anthropicTools: JsonArray = buildAnthropicTools()

    /** 工具名 → JSON Schema 的 properties 引用,供 driver 在解析 tool_call.arguments 时复用。 */
    val toolNames: List<String> = listOf(
        TOOL_INSERT_STRINGS,
        TOOL_UPDATE_STRING,
        TOOL_QUERY_KEYS,
        TOOL_READ_STRING,
        TOOL_FIND_KEYS_BY_TEXT,
        TOOL_SHEETS_OPERATION,
        TOOL_FIND_ROWS_BY_TEXT,
        TOOL_ASK_USER,
        TOOL_LOAD_TOOL_DOC,
        TOOL_TASK_COMPLETE,
    )

    const val TOOL_INSERT_STRINGS = "insert_strings"
    const val TOOL_UPDATE_STRING = "update_string"
    const val TOOL_QUERY_KEYS = "query_keys"
    const val TOOL_READ_STRING = "read_string"
    const val TOOL_FIND_KEYS_BY_TEXT = "find_keys_by_text"
    const val TOOL_SHEETS_OPERATION = "sheets_operation"
    const val TOOL_FIND_ROWS_BY_TEXT = "find_rows_by_text"
    const val TOOL_ASK_USER = "ask_user"
    const val TOOL_LOAD_TOOL_DOC = "load_tool_doc"
    const val TOOL_TASK_COMPLETE = "task_complete"

    // region 工具描述文案(主 prompt 引用,这里集中维护)

    private const val DESC_INSERT_STRINGS =
        "向 Android strings.xml 插入或修改翻译字符串。" +
            "可同时调用多次以插入多个字符串。" +
            "translations 键必须覆盖上下文 availableLanguages 列出的所有语言。" +
            "若只想修改个别语言,请改用 update_string(部分语言更新,不覆写其他语言)。"

    private const val DESC_UPDATE_STRING =
        "精准修改指定 key 的部分语言翻译,只动 translations 中列出的语言,其他语言保持原样。" +
            "适用场景:用户说「把 X 的繁体改成 Y」「修正 Z 的某个语言翻译」,无需提供全部语言。" +
            "若 key 不存在则自动创建。"

    private const val DESC_QUERY_KEYS =
        "列出或搜索模块内的字符串 key。" +
            "pattern 为空时列出所有 key;非空时按正则匹配 key 名(例: \"mall_.*\")。" +
            "includeTranslations=true 时返回各语言当前翻译(消耗较多 token,谨慎使用)。" +
            "适用场景:用户说「找一下关于房间的 key」「列出所有错误提示的 key」,或 AI 需要先发现 key 名再修改。"

    private const val DESC_READ_STRING =
        "读取指定 key 在模块所有语言的当前翻译,返回 key+各语言文本+文件路径。" +
            "适用场景:用户说「看看 X 现在怎么翻译的」,AI 在修改前先确认原文,避免覆盖已有正确翻译。"

    private const val DESC_FIND_KEYS_BY_TEXT =
        "strings.xml 反查:通过翻译文本查找对应的 key。" +
            "支持 exact(完全相等)/ contains(子串,默认)/ regex(正则) 三种匹配模式。" +
            "可选限定 module(只查该模块)和 language(只查该语言目录,例 values-zh-rTW)。" +
            "适用场景:用户看到一段文字想反查是哪个 key,排查重复翻译,跨语言确认某文本对应哪个 key。"

    private const val DESC_FIND_ROWS_BY_TEXT =
        "Google Sheets 反查:在表格中按文本搜索行,返回行号+列名+整行内容。" +
            "支持 exact/ contains(默认)/ regex 三种匹配模式,可选 column(只查指定列名)。" +
            "适用场景:用户问「这个翻译对应表格里哪一行」,查重,定位某文案在 sheet 中的位置。"

    private const val DESC_SHEETS_OPERATION =
        "执行 Google 表格操作。operation 决定具体动作类型。" +
            "不确定用法时先调用 load_tool_doc(\"sheets_basic\"/\"sheets_row_ops\"/...) 获取详细文档。" +
            "安全约束:修改/删除行前先用 search 定位行号;列操作需用户确认;全表检查/修正用 check_translations/fix_translations。"

    private const val DESC_ASK_USER =
        "向用户提问并等待回复。options 非空时显示按钮供用户点击;为空时只显示文本等待用户输入。" +
            "使用场景:关键参数缺失、风险操作确认、目标不明确需要澄清。"

    private const val DESC_LOAD_TOOL_DOC =
        "按需加载工具的详细使用文档(枚举值、参数约束、示例)。" +
            "返回的文档会作为工具结果回传给你,你据此继续返回实际执行动作,不要重复请求同一工具的文档。"

    private const val DESC_TASK_COMPLETE =
        "声明任务已完成,结束当前对话循环。" +
            "这是唯一的「合法终止」信号 — 没有调用本工具 = 你仍在执行,系统会持续驱动你继续。" +
            "status 取值: success(完全达成) / partial(部分达成,如用户拒绝) / failed(执行失败)。" +
            "调用本工具后不要在同一次回复中再调用其他工具。"

    // endregion

    private fun buildOpenAiTools(): JsonArray {
        return JsonArray().apply {
            add(openAiTool(TOOL_INSERT_STRINGS, DESC_INSERT_STRINGS, openAiInsertStringsParams()))
            add(openAiTool(TOOL_UPDATE_STRING, DESC_UPDATE_STRING, openAiUpdateStringParams()))
            add(openAiTool(TOOL_QUERY_KEYS, DESC_QUERY_KEYS, openAiQueryKeysParams()))
            add(openAiTool(TOOL_READ_STRING, DESC_READ_STRING, openAiReadStringParams()))
            add(openAiTool(TOOL_FIND_KEYS_BY_TEXT, DESC_FIND_KEYS_BY_TEXT, openAiFindKeysByTextParams()))
            add(openAiTool(TOOL_SHEETS_OPERATION, DESC_SHEETS_OPERATION, openAiSheetsOperationParams()))
            add(openAiTool(TOOL_FIND_ROWS_BY_TEXT, DESC_FIND_ROWS_BY_TEXT, openAiFindRowsByTextParams()))
            add(openAiTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(openAiTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(openAiTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

    private fun buildAnthropicTools(): JsonArray {
        return JsonArray().apply {
            add(anthropicTool(TOOL_INSERT_STRINGS, DESC_INSERT_STRINGS, openAiInsertStringsParams()))
            add(anthropicTool(TOOL_UPDATE_STRING, DESC_UPDATE_STRING, openAiUpdateStringParams()))
            add(anthropicTool(TOOL_QUERY_KEYS, DESC_QUERY_KEYS, openAiQueryKeysParams()))
            add(anthropicTool(TOOL_READ_STRING, DESC_READ_STRING, openAiReadStringParams()))
            add(anthropicTool(TOOL_FIND_KEYS_BY_TEXT, DESC_FIND_KEYS_BY_TEXT, openAiFindKeysByTextParams()))
            add(anthropicTool(TOOL_SHEETS_OPERATION, DESC_SHEETS_OPERATION, openAiSheetsOperationParams()))
            add(anthropicTool(TOOL_FIND_ROWS_BY_TEXT, DESC_FIND_ROWS_BY_TEXT, openAiFindRowsByTextParams()))
            add(anthropicTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(anthropicTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(anthropicTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

    private fun openAiTool(name: String, description: String, parameters: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", parameters)
            })
        }
    }

    private fun anthropicTool(name: String, description: String, inputSchema: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("input_schema", inputSchema)
        }
    }

    // region 各工具的 JSON Schema (OpenAI 风格,Anthropic 共用同一份 input_schema)

    private fun openAiInsertStringsParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时使用 currentModule.moduleName。"
                    )
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "字符串 key,使用 snake_case。")
                })
                add("translations", obj {
                    addProperty("type", "object")
                    addProperty(
                        "description",
                        "键为语言目录名(values / values-zh-rCN / values-fr 等)," +
                            "值为对应翻译文本。必须覆盖上下文 availableLanguages 中的所有语言。"
                    )
                })
            })
            add("required", JsonArray().apply {
                add("name")
                add("translations")
            })
        }
    }

    private fun openAiQueryKeysParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。")
                })
                add("pattern", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选正则表达式,对 key 名做匹配。为空或省略时列出所有 key(分页)。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,最大返回条数,默认 50,最大 500。")
                })
                add("offset", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,分页偏移,默认 0。")
                })
                add("includeTranslations", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,是否在结果中带各语言当前翻译。默认 false。开启后 token 消耗大,谨慎使用。")
                })
            })
        }
    }

    private fun openAiReadStringParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,字符串 key 名。")
                })
            })
            add("required", JsonArray().apply { add("name") })
        }
    }

    private fun openAiUpdateStringParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,字符串 key,使用 snake_case。")
                })
                add("translations", obj {
                    addProperty("type", "object")
                    addProperty(
                        "description",
                        "必填,键为语言目录名(values/values-zh-rCN/values-fr 等),值仅包含需要修改的翻译。" +
                            "未列出的语言保持原样。"
                    )
                })
            })
            add("required", JsonArray().apply {
                add("name")
                add("translations")
            })
        }
    }

    private fun openAiFindKeysByTextParams(): JsonObject {
        val matchTypeEnum = JsonArray().apply {
            add("exact")
            add("contains")
            add("regex")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("text", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,要查找的翻译文本。")
                })
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,限定 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时搜索项目中所有模块。")
                })
                add("language", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,限定语言目录(如 values-zh-rTW)。省略时搜索所有语言。"
                    )
                })
                add("matchType", obj {
                    addProperty("type", "string")
                    add("enum", matchTypeEnum)
                    addProperty("description", "匹配模式,默认 contains(子串匹配)。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "是否区分大小写,默认 false。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "最大返回条数,默认 30,最大 200。")
                })
            })
            add("required", JsonArray().apply { add("text") })
        }
    }

    private fun openAiFindRowsByTextParams(): JsonObject {
        val matchTypeEnum = JsonArray().apply {
            add("exact")
            add("contains")
            add("regex")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("text", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,要查找的文本。")
                })
                add("spreadsheetId", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认用上下文 googleSheets 配置。")
                })
                add("sheetName", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认用 defaultSheetName。")
                })
                add("column", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,限定列名(与表头精确匹配,忽略大小写)。例:values-zh-rTW。"
                    )
                })
                add("matchType", obj {
                    addProperty("type", "string")
                    add("enum", matchTypeEnum)
                    addProperty("description", "匹配模式,默认 contains(子串匹配)。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "是否区分大小写,默认 false。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "最大返回条数,默认 30,最大 200。")
                })
            })
            add("required", JsonArray().apply { add("text") })
        }
    }

    private fun openAiSheetsOperationParams(): JsonObject {
        val operationEnum = JsonArray().apply {
            SheetsOperation.Operation.entries.forEach { add(it.name.lowercase()) }
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("operation", obj {
                    addProperty("type", "string")
                    add("enum", operationEnum)
                    addProperty("description", "操作类型,决定后续字段的取值。")
                })
                add("spreadsheetId", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认使用上下文 googleSheets.defaultSpreadsheetId。")
                })
                add("sheetName", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认使用 defaultSheetName 或上下文 availableSheets 中的某个值。")
                })
                add("range", obj {
                    addProperty("type", "string")
                    addProperty("description", "A1 表示法范围,如 \"Sheet1!A1:D10\"。read/write 时使用。")
                })
                add("key", obj {
                    addProperty("type", "string")
                    addProperty("description", "search/read 时按 key 查找第一列匹配行。")
                })
                add("rowNumber", obj {
                    addProperty("type", "integer")
                    addProperty("description", "1-based 行号,insert_row / update_row / delete_row / clear_row 必填。")
                })
                add("rows", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "array")
                        add("items", obj { addProperty("type", "string") })
                    })
                    addProperty("description", "二维数组,外层每项是一行数据(单元格字符串数组)。append/insert/update_row 取首元素。")
                })
                add("columnIndex", obj {
                    addProperty("type", "integer")
                    addProperty("description", "1-based 列号,insert_column / update_column / delete_column / clear_column 必填。")
                })
                add("columnHeader", obj {
                    addProperty("type", "string")
                    addProperty("description", "新列的表头,append_column 建议填写。")
                })
                add("columnValues", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty(
                        "description",
                        "一维数组,首元素为表头,其余为各行值。insert_column / append_column / update_column / clear_column 必填。"
                    )
                })
                add("freezeRowCount", obj {
                    addProperty("type", "integer")
                    addProperty("description", ">= 0,freeze_rows 必填。0 表示取消冻结。")
                })
                add("freezeColumnCount", obj {
                    addProperty("type", "integer")
                    addProperty("description", ">= 0,freeze_columns 必填。0 表示取消冻结。")
                })
                add("color", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,背景色。fill_color 必填。支持 hex(例 #FF0000、#f0a)或命名色(red/green/blue/yellow/orange/purple/pink/gray/grey/white/black/light_gray/dark_gray/brown/cyan/magenta)。"
                    )
                })
                add("textColor", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,文字色。set_text_color 必填。颜色格式同 color。"
                    )
                })
                add("rowTextColors", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "array")
                        add("items", obj {
                            addProperty(
                                "type",
                                "string"
                            )
                            addProperty("description", "颜色字符串,或 null 表示该格不上色。")
                        })
                    })
                    addProperty(
                        "description",
                        "可选,与 rows 同形(行操作为 [[c1,c2,...]],write 为 [[..],[..]])的二维数组。null 元素表示该格不上色。仅对当前写入的单元格生效。"
                    )
                })
                add("columnTextColors", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "string")
                        addProperty("description", "颜色字符串,或 null 表示该格不上色。")
                    })
                    addProperty(
                        "description",
                        "可选,与 columnValues 同形的一维数组,按行顺序逐个对应。null 元素表示该格不上色。"
                    )
                })
            })
            add("required", JsonArray().apply { add("operation") })
        }
    }

    private fun openAiAskUserParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("question", obj {
                    addProperty("type", "string")
                    addProperty("description", "问题文本,会直接展示给用户。")
                })
                add("options", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty("description", "可选的按钮选项,非空时显示为可点击按钮。")
                })
            })
            add("required", JsonArray().apply { add("question") })
        }
    }

    private fun openAiLoadToolDocParams(): JsonObject {
        val availableTools = JsonArray().apply {
            add("insert_strings")
            add("sheets_basic")
            add("sheets_row_ops")
            add("sheets_column_ops")
            add("sheets_freeze")
            add("sheets_review")
            add("sheets_color")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("tool", obj {
                    addProperty("type", "string")
                    add("enum", availableTools)
                    addProperty("description", "要加载详细文档的工具名。")
                })
            })
            add("required", JsonArray().apply { add("tool") })
        }
    }

    private fun openAiTaskCompleteParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("summary", obj {
                    addProperty("type", "string")
                    addProperty("description", "给用户看的最终总结,会直接展示。")
                })
                add("status", obj {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        add("success")
                        add("partial")
                        add("failed")
                    })
                    addProperty("description", "任务完成状态:success 完全达成 / partial 部分达成 / failed 执行失败。")
                })
                add("notes", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,补充说明(例如「用户拒绝」「缺少必要信息」)。")
                })
            })
            add("required", JsonArray().apply {
                add("summary")
                add("status")
            })
        }
    }

    // endregion

    private fun obj(builder: JsonObject.() -> Unit): JsonObject = JsonObject().apply(builder)
}
