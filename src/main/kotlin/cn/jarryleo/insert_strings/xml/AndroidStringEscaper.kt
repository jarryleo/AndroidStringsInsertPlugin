package cn.jarryleo.insert_strings.xml

object AndroidStringEscaper {
    private val entityPattern = Regex("""&(amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);""")

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
