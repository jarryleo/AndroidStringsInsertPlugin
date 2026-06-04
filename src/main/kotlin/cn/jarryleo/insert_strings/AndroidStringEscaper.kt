package cn.jarryleo.insert_strings

object AndroidStringEscaper {
    private val entityPattern = Regex("""&(amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);""")

    fun escape(text: String): String {
        return escapeXmlText(escapeAndroidText(text))
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
