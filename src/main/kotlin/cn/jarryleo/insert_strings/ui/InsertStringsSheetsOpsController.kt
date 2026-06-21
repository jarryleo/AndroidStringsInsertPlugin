package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AITranslator
import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.ai.ReviewResult
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import cn.jarryleo.insert_strings.xml.StringsInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Google Sheets 域动作的执行器 + UI ↔ sheet 双向映射。
 *
 * 拆分理由:SheetsOperation 有 20+ 个枚举,加上 runTranslationReview、findRowsByText、
 * buildSheetRows、applySheetRowsToUi 等等,这一块本来就独立且大;抽离后 UI 主类更干净。
 *
 * 该类不直接更新 chatMessages / pendingSheetsInsert,只负责「把单个 action 跑一遍拿到结果」,
 * 由上层 ChatDriver 负责组装 tool_result 与驱动流程。
 */
internal class InsertStringsSheetsOpsController(
    private val ui: InsertStringsUI,
) {

    private val project: Project get() = ui.project

    /**
     * 批量翻译审查/修正的每批行数。控制单次 AI 调用 token,避免溢出。
     */
    private val reviewBatchSize: Int = 80

    /**
     * SheetsOperation 同步执行结果(供上层包装成 tool result)。
     */
    data class SheetsToolResult(
        val operation: String,
        val success: Boolean,
        val message: String,
        val data: List<List<String>>? = null,
        val sheetNames: List<String>? = null,
        val rowNumber: Int? = null
    )

    /**
     * 批量翻译审查/修正结果(聚合后)。
     */
    data class TranslationReviewReport(
        val success: Boolean,
        val summary: String,
        val issueLines: List<String>,
        val fixAppliedCount: Int,
        val fixFailedCount: Int
    ) {
        fun toReportText(): String {
            return buildString {
                appendLine(summary)
                if (fixAppliedCount > 0 || fixFailedCount > 0) {
                    appendLine("已写入修正行数:$fixAppliedCount;失败:$fixFailedCount。")
                }
                if (issueLines.isNotEmpty()) {
                    appendLine("问题清单:")
                    issueLines.take(200).forEach { appendLine("- $it") }
                    if (issueLines.size > 200) {
                        appendLine("...(共 ${issueLines.size} 条,已省略后续)")
                    }
                }
            }
        }
    }

    fun runFindRowsByText(action: AiAction.FindRowsByText): String {
        val spreadsheetId = SheetsManager.resolveSpreadsheetId(project, action.spreadsheetId)
        if (spreadsheetId.isBlank()) {
            return "[工具执行结果] 类型:find_rows_by_text 状态:失败 信息:Spreadsheet ID 为空,请先在设置中配置"
        }
        val sheetName = action.sheetName ?: SheetsManager.defaultSheetName(project)
        val matchType = mapToSheetsMatchType(action.matchType)
        val result = SheetsManager.findRowsByText(
            project = project,
            spreadsheetId = spreadsheetId,
            sheetName = sheetName,
            text = action.text,
            column = action.column,
            matchType = matchType,
            caseSensitive = action.caseSensitive,
            limit = action.limit
        )
        return result.fold(
            onSuccess = { rows ->
                if (rows.isEmpty()) {
                    val scope = buildString {
                        append("text:\"").append(action.text).append("\"")
                        append(" sheet:").append(sheetName)
                        if (action.column != null) append(" column:").append(action.column)
                        append(" match:").append(action.matchType.name.lowercase())
                    }
                    "[工具执行结果] 类型:find_rows_by_text 状态:成功 命中:0 范围:$scope 未找到匹配行"
                } else {
                    buildString {
                        append("[工具执行结果] 类型:find_rows_by_text 状态:成功 命中:").append(rows.size)
                        append(" sheet:").append(sheetName)
                        append(" match:").append(action.matchType.name.lowercase())
                        appendLine()
                        rows.forEachIndexed { idx, r ->
                            append(idx + 1).append(". 行").append(r.rowNumber)
                            if (r.columnName.isNotEmpty()) {
                                append(" 列=").append(r.columnName)
                            }
                            append(" 命中=\"").append(truncateForLog(r.matchedText, 50)).append("\"")
                            append(" | ").append(r.row.joinToString(" | "))
                            appendLine()
                        }
                        if (rows.size >= action.limit) {
                            appendLine("… 已达返回上限,可能还有更多匹配。")
                        }
                    }
                }
            },
            onFailure = { e ->
                "[工具执行结果] 类型:find_rows_by_text 状态:失败 信息:${e.message ?: "unknown"}"
            }
        )
    }

    private fun mapToSheetsMatchType(type: AiAction.TextMatchType): SheetsManager.TextMatchType {
        return when (type) {
            AiAction.TextMatchType.EXACT -> SheetsManager.TextMatchType.EXACT
            AiAction.TextMatchType.CONTAINS -> SheetsManager.TextMatchType.CONTAINS
            AiAction.TextMatchType.REGEX -> SheetsManager.TextMatchType.REGEX
        }
    }

    fun executeSheetsOperationSync(action: AiAction.SheetsOperation): SheetsToolResult {
        val spreadsheetId = SheetsManager.resolveSpreadsheetId(project, action.spreadsheetId)
        if (spreadsheetId.isBlank()) {
            return SheetsToolResult(actionLabel(action), false, "Spreadsheet ID 为空,请先在设置中配置。")
        }
        val sheetName = action.sheetName ?: SheetsManager.defaultSheetName(project)

        return when (action.operation) {
            AiAction.SheetsOperation.Operation.LIST_SHEETS -> {
                val result = SheetsManager.listSheetNames(project, spreadsheetId)
                result.fold(
                    onSuccess = { sheets ->
                        SwingUtilities.invokeLater {
                            ui.sheetsAvailableSheets.clear()
                            ui.sheetsAvailableSheets.addAll(sheets)
                        }
                        val names = sheets.map { it.title }
                        val summary = if (names.isEmpty()) "表格中没有工作表" else "共 ${names.size} 个工作表: ${names.joinToString(", ")}"
                        SheetsToolResult(
                            operation = "列出工作表",
                            success = true,
                            message = summary,
                            sheetNames = names
                        )
                    },
                    onFailure = {
                        SheetsToolResult("列出工作表", false, it.message ?: "Failed to list sheets.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.READ -> {
                val result = if (!action.range.isNullOrBlank()) {
                    SheetsManager.readRange(project, spreadsheetId, action.range)
                } else if (!action.key.isNullOrBlank()) {
                    SheetsManager.searchRowInSheet(project, spreadsheetId, sheetName, action.key).map { listOf(it.second) }
                } else {
                    SheetsManager.readSheet(project, spreadsheetId, sheetName)
                }
                result.fold(
                    onSuccess = { sheetRows ->
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Read from Google Sheets.") }
                        val dataSummary = if (sheetRows.isEmpty()) "工作表 '$sheetName' 为空" else "读取到 ${sheetRows.size} 行数据"
                        SheetsToolResult("读取表格", true, dataSummary, sheetRows)
                    },
                    onFailure = {
                        SheetsToolResult("读取表格", false, it.message ?: "Sheets read failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.SEARCH -> {
                val key = action.key
                if (key.isNullOrBlank()) {
                    return SheetsToolResult("搜索表格", false, "Search key 为空。")
                }
                val range = action.range ?: "$sheetName!A1:Z100000"
                val result = SheetsManager.searchRowByKey(project, spreadsheetId, range, key)
                result.fold(
                    onSuccess = { (rowNum, row) ->
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Found key '$key' at row $rowNum.") }
                        SheetsToolResult(
                            operation = "搜索表格",
                            success = true,
                            message = "找到 key '$key' 在第 $rowNum 行",
                            data = listOf(row),
                            rowNumber = rowNum
                        )
                    },
                    onFailure = {
                        SheetsToolResult("搜索表格", false, it.message ?: "Sheets search failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.WRITE -> {
                val rowsToWrite = action.rows ?: buildSheetRows()
                val range = action.range ?: "$sheetName!A1:Z1000"
                val result = SheetsManager.writeRange(project, spreadsheetId, range, rowsToWrite, action.rowTextColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Wrote to Google Sheets.") }
                        SheetsToolResult("写入表格", true, "成功写入 ${rowsToWrite.size} 行数据到范围 $range")
                    },
                    onFailure = {
                        SheetsToolResult("写入表格", false, it.message ?: "Sheets write failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.APPEND_ROW -> {
                val rowsToAppend = action.rows
                if (rowsToAppend.isNullOrEmpty()) {
                    return SheetsToolResult("追加行", false, "rows 为空。")
                }
                val row = rowsToAppend.first()
                val rowColors = action.rowTextColors?.firstOrNull()
                val result = SheetsManager.appendRow(project, spreadsheetId, sheetName, row, rowColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Appended row to $sheetName.") }
                        SheetsToolResult("追加行", true, "成功在工作表 '$sheetName' 末尾追加 1 行: ${row.joinToString(" | ")}")
                    },
                    onFailure = {
                        SheetsToolResult("追加行", false, it.message ?: "Sheets append failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.INSERT_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("插入行", false, "rowNumber 无效,必须为 >= 1 的整数。")
                }
                val rowsToInsert = action.rows
                if (rowsToInsert.isNullOrEmpty()) {
                    return SheetsToolResult("插入行", false, "rows 为空。")
                }
                val row = rowsToInsert.first()
                val rowColors = action.rowTextColors?.firstOrNull()
                val result = SheetsManager.insertRow(project, spreadsheetId, sheetName, rowNum, row, rowColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Inserted row at $rowNum in $sheetName.") }
                        SheetsToolResult("插入行", true, "成功在工作表 '$sheetName' 第 $rowNum 行插入新行: ${row.joinToString(" | ")}")
                    },
                    onFailure = {
                        SheetsToolResult("插入行", false, it.message ?: "Sheets insert failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.UPDATE_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("更新行", false, "rowNumber 无效,必须为 >= 1 的整数。")
                }
                val rowsToUpdate = action.rows
                if (rowsToUpdate.isNullOrEmpty()) {
                    return SheetsToolResult("更新行", false, "rows 为空。")
                }
                val row = rowsToUpdate.first()
                val rowColors = action.rowTextColors?.firstOrNull()
                val result = SheetsManager.updateRow(project, spreadsheetId, sheetName, rowNum, row, rowColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Updated row $rowNum in $sheetName.") }
                        SheetsToolResult("更新行", true, "成功更新工作表 '$sheetName' 第 $rowNum 行为: ${row.joinToString(" | ")}")
                    },
                    onFailure = {
                        SheetsToolResult("更新行", false, it.message ?: "Sheets update failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.DELETE_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("删除行", false, "rowNumber 无效,必须为 >= 1 的整数。")
                }
                val result = SheetsManager.deleteRow(project, spreadsheetId, sheetName, rowNum)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Deleted row $rowNum in $sheetName.") }
                        SheetsToolResult("删除行", true, "成功删除工作表 '$sheetName' 第 $rowNum 行")
                    },
                    onFailure = {
                        SheetsToolResult("删除行", false, it.message ?: "Sheets delete failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("清空行", false, "rowNumber 无效,必须为 >= 1 的整数。")
                }
                val result = SheetsManager.clearRow(project, spreadsheetId, sheetName, rowNum)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Cleared row $rowNum in $sheetName.") }
                        SheetsToolResult("清空行", true, "成功清空工作表 '$sheetName' 第 $rowNum 行内容")
                    },
                    onFailure = {
                        SheetsToolResult("清空行", false, it.message ?: "Sheets clear failed.")
                    }
                )
            }

            // ==================== 列操作(需要用户确认) ====================

            AiAction.SheetsOperation.Operation.INSERT_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("插入列", false, "columnIndex 无效,必须为 >= 1 的整数。")
                }
                val values = action.columnValues
                if (values.isNullOrEmpty()) {
                    return SheetsToolResult("插入列", false, "columnValues 为空。")
                }
                if (!confirmColumnOperation("插入列", "将在工作表 '$sheetName' 第 $colIdx 列插入新列,原该列及之后整体右移。\n列头:${values.first()}\n是否允许?")) {
                    return SheetsToolResult("插入列", false, "用户已取消插入列操作。")
                }
                val result = SheetsManager.insertColumn(project, spreadsheetId, sheetName, colIdx, values, action.columnTextColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Inserted column at $colIdx in $sheetName.") }
                        SheetsToolResult("插入列", true, "成功在工作表 '$sheetName' 第 $colIdx 列插入新列,共 ${values.size} 个单元格")
                    },
                    onFailure = { SheetsToolResult("插入列", false, it.message ?: "Sheets insert column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.APPEND_COLUMN -> {
                val values = action.columnValues
                if (values.isNullOrEmpty()) {
                    return SheetsToolResult("追加列", false, "columnValues 为空。")
                }
                if (!confirmColumnOperation("追加列", "将在工作表 '$sheetName' 末尾追加新列。\n列头:${values.first()}\n是否允许?")) {
                    return SheetsToolResult("追加列", false, "用户已取消追加列操作。")
                }
                val result = SheetsManager.appendColumn(project, spreadsheetId, sheetName, values, action.columnTextColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Appended column to $sheetName.") }
                        SheetsToolResult("追加列", true, "成功在工作表 '$sheetName' 末尾追加新列,共 ${values.size} 个单元格")
                    },
                    onFailure = { SheetsToolResult("追加列", false, it.message ?: "Sheets append column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.UPDATE_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("更新列", false, "columnIndex 无效,必须为 >= 1 的整数。")
                }
                val values = action.columnValues
                if (values.isNullOrEmpty()) {
                    return SheetsToolResult("更新列", false, "columnValues 为空。")
                }
                if (!confirmColumnOperation("更新列", "将更新工作表 '$sheetName' 第 $colIdx 列内容,共 ${values.size} 个单元格。\n是否允许?")) {
                    return SheetsToolResult("更新列", false, "用户已取消更新列操作。")
                }
                val result = SheetsManager.updateColumn(project, spreadsheetId, sheetName, colIdx, values, action.columnTextColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Updated column $colIdx in $sheetName.") }
                        SheetsToolResult("更新列", true, "成功更新工作表 '$sheetName' 第 $colIdx 列,共 ${values.size} 个单元格")
                    },
                    onFailure = { SheetsToolResult("更新列", false, it.message ?: "Sheets update column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.DELETE_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("删除列", false, "columnIndex 无效,必须为 >= 1 的整数。")
                }
                if (!confirmColumnOperation("删除列", "将删除工作表 '$sheetName' 第 $colIdx 列,后续列整体左移,数据不可恢复。\n是否允许?")) {
                    return SheetsToolResult("删除列", false, "用户已取消删除列操作。")
                }
                val result = SheetsManager.deleteColumn(project, spreadsheetId, sheetName, colIdx)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Deleted column $colIdx in $sheetName.") }
                        SheetsToolResult("删除列", true, "成功删除工作表 '$sheetName' 第 $colIdx 列")
                    },
                    onFailure = { SheetsToolResult("删除列", false, it.message ?: "Sheets delete column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("清空列", false, "columnIndex 无效,必须为 >= 1 的整数。")
                }
                if (!confirmColumnOperation("清空列", "将清空工作表 '$sheetName' 第 $colIdx 列内容(保留空列)。\n是否允许?")) {
                    return SheetsToolResult("清空列", false, "用户已取消清空列操作。")
                }
                val result = SheetsManager.clearColumn(project, spreadsheetId, sheetName, colIdx)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Cleared column $colIdx in $sheetName.") }
                        SheetsToolResult("清空列", true, "成功清空工作表 '$sheetName' 第 $colIdx 列内容")
                    },
                    onFailure = { SheetsToolResult("清空列", false, it.message ?: "Sheets clear column failed.") }
                )
            }

            // ==================== 批量翻译审查/修正(最小 token) ====================

            AiAction.SheetsOperation.Operation.CHECK_TRANSLATIONS -> {
                val report = runTranslationReview(action, spreadsheetId, sheetName, mode = "check")
                SwingUtilities.invokeLater { ui.actionsController.showToast(report.summary.ifBlank { "检查完成" }) }
                SheetsToolResult("检查全部翻译", true, report.toReportText())
            }

            AiAction.SheetsOperation.Operation.FIX_TRANSLATIONS -> {
                val report = runTranslationReview(action, spreadsheetId, sheetName, mode = "fix")
                SwingUtilities.invokeLater { ui.actionsController.showToast(report.summary.ifBlank { "修正完成" }) }
                SheetsToolResult("修正全部翻译", report.success, report.toReportText())
            }

            AiAction.SheetsOperation.Operation.FREEZE_ROWS -> {
                val rowCount = action.freezeRowCount
                if (rowCount == null || rowCount < 0) {
                    return SheetsToolResult("冻结行", false, "freezeRowCount 无效,必须为 >= 0 的整数。")
                }
                val result = SheetsManager.freezeRows(project, spreadsheetId, sheetName, rowCount)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater {
                            ui.actionsController.showToast(if (rowCount == 0) "已取消冻结行" else "已冻结前 $rowCount 行")
                        }
                        SheetsToolResult(
                            "冻结行",
                            true,
                            if (rowCount == 0) "已取消工作表 '$sheetName' 的冻结行"
                            else "已冻结工作表 '$sheetName' 前 $rowCount 行"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("冻结行", false, it.message ?: "Sheets freeze rows failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.FREEZE_COLUMNS -> {
                val colCount = action.freezeColumnCount
                if (colCount == null || colCount < 0) {
                    return SheetsToolResult("冻结列", false, "freezeColumnCount 无效,必须为 >= 0 的整数。")
                }
                val result = SheetsManager.freezeColumns(project, spreadsheetId, sheetName, colCount)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater {
                            ui.actionsController.showToast(if (colCount == 0) "已取消冻结列" else "已冻结前 $colCount 列")
                        }
                        SheetsToolResult(
                            "冻结列",
                            true,
                            if (colCount == 0) "已取消工作表 '$sheetName' 的冻结列"
                            else "已冻结工作表 '$sheetName' 前 $colCount 列"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("冻结列", false, it.message ?: "Sheets freeze columns failed.")
                    }
                )
            }

            // ==================== 填充/清除背景色 ====================

            AiAction.SheetsOperation.Operation.FILL_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("填充颜色", false, "range 为空。")
                }
                val color = action.color
                if (color.isNullOrBlank()) {
                    return SheetsToolResult("填充颜色", false, "color 为空。")
                }
                val result = SheetsManager.fillColor(project, spreadsheetId, range, color, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Filled $range with $color.") }
                        SheetsToolResult(
                            "填充颜色",
                            true,
                            "已在工作表 '$sheetName' 的范围 $range 填充背景色 $color"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("填充颜色", false, it.message ?: "Sheets fill color failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("清除颜色", false, "range 为空。")
                }
                val result = SheetsManager.clearColor(project, spreadsheetId, range, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Cleared color on $range.") }
                        SheetsToolResult(
                            "清除颜色",
                            true,
                            "已清除工作表 '$sheetName' 范围 $range 的背景色"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("清除颜色", false, it.message ?: "Sheets clear color failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.SET_TEXT_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("设置文字色", false, "range 为空。")
                }
                val textColor = action.textColor
                if (textColor.isNullOrBlank()) {
                    return SheetsToolResult("设置文字色", false, "textColor 为空。")
                }
                val result = SheetsManager.setTextColor(project, spreadsheetId, range, textColor, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Set text color $textColor on $range.") }
                        SheetsToolResult(
                            "设置文字色",
                            true,
                            "已将工作表 '$sheetName' 范围 $range 的文字设为 $textColor"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("设置文字色", false, it.message ?: "Sheets set text color failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_TEXT_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("清除文字色", false, "range 为空。")
                }
                val result = SheetsManager.clearTextColor(project, spreadsheetId, range, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { ui.actionsController.showToast("Cleared text color on $range.") }
                        SheetsToolResult(
                            "清除文字色",
                            true,
                            "已清除工作表 '$sheetName' 范围 $range 的文字色"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("清除文字色", false, it.message ?: "Sheets clear text color failed.")
                    }
                )
            }
        }
    }

    fun actionLabel(action: AiAction.SheetsOperation): String {
        return when (action.operation) {
            AiAction.SheetsOperation.Operation.LIST_SHEETS -> "列出工作表"
            AiAction.SheetsOperation.Operation.READ -> "读取表格"
            AiAction.SheetsOperation.Operation.SEARCH -> "搜索表格"
            AiAction.SheetsOperation.Operation.WRITE -> "写入表格"
            AiAction.SheetsOperation.Operation.APPEND_ROW -> "追加行"
            AiAction.SheetsOperation.Operation.INSERT_ROW -> "插入行"
            AiAction.SheetsOperation.Operation.UPDATE_ROW -> "更新行"
            AiAction.SheetsOperation.Operation.DELETE_ROW -> "删除行"
            AiAction.SheetsOperation.Operation.CLEAR_ROW -> "清空行"
            AiAction.SheetsOperation.Operation.INSERT_COLUMN -> "插入列"
            AiAction.SheetsOperation.Operation.APPEND_COLUMN -> "追加列"
            AiAction.SheetsOperation.Operation.UPDATE_COLUMN -> "更新列"
            AiAction.SheetsOperation.Operation.DELETE_COLUMN -> "删除列"
            AiAction.SheetsOperation.Operation.CLEAR_COLUMN -> "清空列"
            AiAction.SheetsOperation.Operation.CHECK_TRANSLATIONS -> "检查全部翻译"
            AiAction.SheetsOperation.Operation.FIX_TRANSLATIONS -> "修正全部翻译"
            AiAction.SheetsOperation.Operation.FREEZE_ROWS -> "冻结行"
            AiAction.SheetsOperation.Operation.FREEZE_COLUMNS -> "冻结列"
            AiAction.SheetsOperation.Operation.FILL_COLOR -> "填充颜色"
            AiAction.SheetsOperation.Operation.CLEAR_COLOR -> "清除颜色"
            AiAction.SheetsOperation.Operation.SET_TEXT_COLOR -> "设置文字色"
            AiAction.SheetsOperation.Operation.CLEAR_TEXT_COLOR -> "清除文字色"
        }
    }

    /**
     * 把当前 UI 的 keyEntries 转换为 sheet 二维表(用于 WRITE 等需要 rows 的操作)。
     */
    fun buildSheetRows(): List<List<String>> {
        ui.actionsController.saveCurrentEdits()
        val languages = ui.keyEntries.firstOrNull()?.stringsInfoList?.map { it.language }
            ?: ui.rows.map { it.language }
        if (languages.isEmpty()) return emptyList()
        val header = listOf("key") + languages
        val dataRows = ui.keyEntries.map { entry ->
            val translationsMap = entry.stringsInfoList.associate { it.language to it.text }
            listOf(entry.key) + languages.map { translationsMap[it] ?: "" }
        }
        return listOf(header) + dataRows
    }

    /**
     * 把 sheet 读回的数据写回 UI:按列推断语言,合并到现有 keyEntries。
     */
    fun applySheetRowsToUi(sheetRows: List<List<String>>) {
        if (sheetRows.isEmpty()) return
        val header = sheetRows.firstOrNull() ?: return
        val keyIndex = header.indexOfFirst { it.equals("key", ignoreCase = true) }
        val dataRows = if (keyIndex != -1) sheetRows.drop(1) else sheetRows
        if (dataRows.isEmpty()) return
        val languages = if (keyIndex != -1) {
            header.filterIndexed { index, _ -> index != keyIndex }
        } else {
            header.drop(1)
        }
        val keyColumn = if (keyIndex != -1) keyIndex else 0

        val newEntries = dataRows.map { row ->
            val key = row.getOrNull(keyColumn) ?: ""
            val infoList = languages.mapIndexed { langIndex, language ->
                val originalIndex = if (keyIndex == -1) {
                    langIndex + 1
                } else {
                    if (langIndex < keyIndex) langIndex else langIndex + 1
                }
                val value = row.getOrNull(originalIndex) ?: ""
                StringRow(language = language, text = value)
            }
            KeyedStringsInfo(key, "", emptyList())
        }

        val existingLanguages = ui.keyEntries.firstOrNull()?.stringsInfoList?.map { it.language }
        if (existingLanguages != null && existingLanguages.isNotEmpty()) {
            val mergedEntries = newEntries.mapIndexed { idx, entry ->
                val dataRow = dataRows[idx]
                val translationsMap = existingLanguages.associateWith { lang ->
                    val langIdx = languages.indexOf(lang)
                    if (langIdx >= 0) {
                        val originalIndex = if (keyIndex == -1) langIdx + 1
                        else if (langIdx < keyIndex) langIdx else langIdx + 1
                        dataRow.getOrNull(originalIndex) ?: ""
                    } else ""
                }
                val infoList = ui.keyEntries.firstOrNull()?.stringsInfoList?.map { info ->
                    StringsInfo(info.stringsFile, info.language, entry.key, translationsMap[info.language] ?: "")
                } ?: emptyList()
                KeyedStringsInfo(entry.key, "", infoList)
            }
            ui.keyEntries.clear()
            ui.keyEntries.addAll(mergedEntries)
        } else {
            ui.keyEntries.clear()
            ui.keyEntries.addAll(newEntries)
        }
        ui.selectedKeyIndex = 0
        ui.actionsController.updateRowsForSelectedKey()
    }

    fun applySheetRowToUi(row: List<String>) {
        applySheetRowsToUi(listOf(row))
    }

    /**
     * 执行全表翻译审查/修正:读取整表 → 分批调用 AI(独立最小上下文)→ 汇总报告。
     * mode = "check" 仅报告问题;mode = "fix" 自动写入修正行。
     * 分批处理保证单次 AI 调用 token 不会溢出;主聊天只收到最终报告。
     */
    private fun runTranslationReview(
        action: AiAction.SheetsOperation,
        spreadsheetId: String,
        sheetName: String,
        mode: String
    ): TranslationReviewReport {
        val readResult = if (!action.range.isNullOrBlank()) {
            SheetsManager.readRange(project, spreadsheetId, action.range)
        } else {
            SheetsManager.readSheet(project, spreadsheetId, sheetName)
        }
        val sheetRows = readResult.getOrElse {
            return TranslationReviewReport(false, "读取表格失败:${it.message ?: "unknown"}", emptyList(), 0, 0)
        }
        if (sheetRows.isEmpty()) {
            return TranslationReviewReport(true, "工作表 '$sheetName' 为空,无需${
                if (mode == "fix") "修正" else "检查"
            }。", emptyList(), 0, 0)
        }

        val header = sheetRows.first()
        val dataRows = sheetRows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        if (dataRows.isEmpty()) {
            return TranslationReviewReport(true, "工作表 '$sheetName' 没有数据行。", emptyList(), 0, 0)
        }

        val batchSize = reviewBatchSize
        val issues = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        var fixApplied = 0
        var fixFailed = 0

        dataRows.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            // 该批第一行在原表格中的 1-based 行号(+1 跳过表头)
            val startRowNumber = batchIndex * batchSize + 2
            val review: ReviewResult = AITranslator.reviewTranslations(header, batch, startRowNumber, mode)

            summaries.add(review.summary)

            if (mode == "check") {
                review.issues.forEach { issue ->
                    val colName = header.getOrNull(issue.col) ?: "col${issue.col}"
                    issues.add(
                        "行${issue.row} [$colName] 「${issue.current}」→「${issue.suggested}」:${issue.reason}"
                    )
                }
            } else {
                review.fixes.forEach { fix ->
                    if (fix.values.isEmpty()) return@forEach
                    val expectedCols = header.size
                    val adjustedValues: MutableList<String> = when {
                        fix.values.size == expectedCols -> fix.values.toMutableList()
                        fix.values.size == expectedCols + 1 -> {
                            issues.add("行${fix.row}:AI 返回列数比表头多1,已自动去除首元素(疑似行号误入 values)")
                            fix.values.drop(1).toMutableList()
                        }
                        fix.values.size > expectedCols + 1 -> {
                            issues.add("行${fix.row}:AI 返回列数过多(${fix.values.size}),已截断为表头列数($expectedCols)")
                            fix.values.take(expectedCols).toMutableList()
                        }
                        else -> {
                            issues.add("行${fix.row}:AI 返回列数不足(${fix.values.size}),已补空至表头列数($expectedCols)")
                            (fix.values + List(expectedCols - fix.values.size) { "" }).toMutableList()
                        }
                    }
                    // 确保 key 列(第一列)与原值一致,AI 不应修改 key
                    val batchIdx = fix.row - startRowNumber
                    if (batchIdx in batch.indices) {
                        val originalKey = batch[batchIdx].firstOrNull().orEmpty()
                        if (originalKey.isNotBlank() && adjustedValues.isNotEmpty() && adjustedValues[0] != originalKey) {
                            issues.add("行${fix.row}:AI 修改了 key 列,已恢复原值「$originalKey」")
                            adjustedValues[0] = originalKey
                        }
                    }
                    val updateResult = SheetsManager.updateRow(
                        project, spreadsheetId, sheetName, fix.row, adjustedValues
                    )
                    if (updateResult.isSuccess) fixApplied++ else fixFailed++
                }
                // fix 模式下也把问题原因记入清单(若有 issues 字段)
                review.issues.forEach { issue ->
                    val colName = header.getOrNull(issue.col) ?: "col${issue.col}"
                    issues.add(
                        "行${issue.row} [$colName] 「${issue.current}」→「${issue.suggested}」:${issue.reason}"
                    )
                }
            }
        }

        val totalChecked = dataRows.size
        val summary = buildString {
            append(if (mode == "fix") "修正完成。" else "检查完成。")
            append("共处理 $totalChecked 行翻译。")
            if (issues.isNotEmpty()) {
                append("发现 ${issues.size} 处问题。")
            } else {
                append(if (mode == "fix") "未发现需要修正的问题。" else "未发现翻译问题。")
            }
            // 附上各批 AI 返回的 summary 片段(去重去空)
            val mergedSummaries = summaries.filter { it.isNotBlank() }.distinct()
            if (mergedSummaries.isNotEmpty()) {
                append("\n批次汇总:")
                mergedSummaries.forEach { append("\n- $it") }
            }
        }

        return TranslationReviewReport(true, summary, issues, fixApplied, fixFailed)
    }

    /**
     * 列结构变更操作的用户确认对话框(EDT 同步)。
     * executeSheetsOperationSync 运行在 pooled thread,故用 invokeAndWait 弹窗。
     */
    private fun confirmColumnOperation(title: String, message: String): Boolean {
        if (SwingUtilities.isEventDispatchThread()) {
            return Messages.showOkCancelDialog(message, title, Messages.getWarningIcon()) == Messages.OK
        }
        val approved = booleanArrayOf(false)
        try {
            SwingUtilities.invokeAndWait {
                approved[0] =
                    Messages.showOkCancelDialog(message, title, Messages.getWarningIcon()) == Messages.OK
            }
        } catch (e: Exception) {
            approved[0] = false
        }
        return approved[0]
    }
}
