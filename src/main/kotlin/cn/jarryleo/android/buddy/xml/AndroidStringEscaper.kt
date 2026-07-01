package cn.jarryleo.android.buddy.xml

object AndroidStringEscaper {
    private val entityPattern = Regex("""&(amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);""")

    /**
      * 保留原有 XML 包裹:若 [existing] 用了 `<![CDATA[...]]>` 或 `<Data>...</Data>` 包裹
      * (支持多层嵌套,如 `<Data><![CDATA[...]]></Data>`),但 [new] 没有,自动给 [new]
      * 套上同样的多层包裹 — 避免 AI 翻译/修改时漏写包裹,导致「带 HTML 标签的翻译」
      * 回退成「被 `&lt;` 转义的纯文本」。
      *
      * 规则(只兜底,不主动改):
      * - 原文本为空(新增场景)→ 原样返回 [new],不强行加包裹。
      * - 原文本无包裹 → 原样返回 [new]。
      * - 原文本有包裹(1 层 / 2 层嵌套均支持),**新文本没有** → 沿用原包裹结构,套到新文本上。
      * - 新文本**已经显式写了外层包裹**(CDATA 或 Data 任一) → 原样返回,不动 AI 的选择。
      * - 两种包裹混用(existing CDATA + new Data,或反之) → 原样返回 [new],
      *   让 lint / Code Review 自然暴露这种不一致,而不是静默「修正」AI 的意图。
      *
      * 配套说明见 [StringsService.updateOrCreateInFile] 与
      * [cn.jarryleo.android.buddy.ui.InsertStringsChatDriver] 中 android.buddy
      * 批处理的 merge 逻辑 — 这两处会读「原文本」并调用本方法,让
      * `<Data><![CDATA[<b>粗体</b>]]></Data>` 这种带格式的翻译在 AI 改写后不丢任何一层包裹。
      */
    fun preserveWrapping(existing: String, new: String): String {
        val existingTrimmed = existing.trim()
        if (existingTrimmed.isEmpty()) return new

        val (outerOpen, outerClose) = when {
            existingTrimmed.startsWith("<![CDATA[", ignoreCase = true) &&
                existingTrimmed.endsWith("]]>") -> "<![CDATA[" to "]]>"

            existingTrimmed.startsWith("<Data>", ignoreCase = true) &&
                existingTrimmed.endsWith("</Data>", ignoreCase = true) -> "<Data>" to "</Data>"

            else -> return new
        }

        val newTrimmed = new.trim()
        val newHasOuterWrap = newTrimmed.startsWith("<![CDATA[", ignoreCase = true) ||
            newTrimmed.startsWith("<Data>", ignoreCase = true)
        if (newHasOuterWrap) return new

        val existingInner = existingTrimmed.substring(
            outerOpen.length,
            existingTrimmed.length - outerClose.length
        )
        return "$outerOpen${preserveWrapping(existingInner, newTrimmed)}$outerClose"
    }


    fun escape(text: String): String {
        // <![CDATA[...]]> 与 <Data>...</Data> 块保持原样，不经过 Android/XML 转义
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val cdataStart = text.indexOf("<![CDATA[", index, ignoreCase = true)
            val dataStart = text.indexOf("<Data>", index, ignoreCase = true)

            val nextSpecialStart = when {
                cdataStart == -1 && dataStart == -1 -> -1
                cdataStart == -1 -> dataStart
                dataStart == -1 -> cdataStart
                else -> minOf(cdataStart, dataStart)
            }

            if (nextSpecialStart == -1) {
                builder.append(escapeXmlText(escapeAndroidText(text.substring(index))))
                break
            }

            if (nextSpecialStart > index) {
                builder.append(escapeXmlText(escapeAndroidText(text.substring(index, nextSpecialStart))))
            }

            when (nextSpecialStart) {
                cdataStart -> {
                    val cdataEnd = text.indexOf("]]>", cdataStart + 9)
                    if (cdataEnd == -1) {
                        // 没有闭合的 CDATA，按普通文本处理
                        builder.append(escapeXmlText(escapeAndroidText(text.substring(cdataStart))))
                        break
                    }
                    builder.append(text.substring(cdataStart, cdataEnd + 3))
                    index = cdataEnd + 3
                }

                dataStart -> {
                    val dataEnd = text.indexOf("</Data>", dataStart + 6, ignoreCase = true)
                    if (dataEnd == -1) {
                        // 没有闭合的 <Data>，按普通文本处理
                        builder.append(escapeXmlText(escapeAndroidText(text.substring(dataStart))))
                        break
                    }
                    builder.append(text.substring(dataStart, dataEnd + 7))
                    index = dataEnd + 7
                }
            }
        }
        return builder.toString()
    }

    private fun escapeAndroidText(text: String): String {
        val builder = StringBuilder(text.length)
        text.forEachIndexed { index, char ->
            when (char) {
                '\n' -> builder.append("\\n")
                '\t' -> builder.append("\\t")
                '\r' -> Unit
                '\'', '"' -> {
                    if (!isEscaped(text, index)) {
                        builder.append('\\')
                    }
                    builder.append(char)
                }

                '@', '?' -> {
                    if (index == 0) {
                        builder.append('\\')
                    }
                    builder.append(char)
                }

                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun escapeXmlText(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when (char) {
                '&' -> {
                    val entity = entityPattern.find(text, index)
                    if (entity != null && entity.range.first == index) {
                        builder.append(entity.value)
                        index = entity.range.last + 1
                    } else {
                        builder.append("&amp;")
                        index++
                    }
                }

                '<' -> {
                    builder.append("&lt;")
                    index++
                }

                '>' -> {
                    builder.append("&gt;")
                    index++
                }

                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return builder.toString()
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            slashCount++
            cursor--
        }
        return slashCount % 2 == 1
    }
}
