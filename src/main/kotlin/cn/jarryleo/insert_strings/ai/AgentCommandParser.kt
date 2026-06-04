package cn.jarryleo.insert_strings.ai

data class AgentInsertCommand(
    val name: String,
    val translations: Map<String, String>
)

object AgentCommandParser {

    private val BLOCK_REGEX =
        """<insert-strings\s+name="([^"]+)">(.*?)</insert-strings>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val ITEM_REGEX =
        """<item\s+lang="([^"]+)">(.*?)</item>""".toRegex(RegexOption.DOT_MATCHES_ALL)

    fun parse(response: String): List<AgentInsertCommand> {
        return BLOCK_REGEX.findAll(response).mapNotNull { blockMatch ->
            val name = blockMatch.groupValues[1].trim()
            val content = blockMatch.groupValues[2]
            val translations = ITEM_REGEX.findAll(content).associate {
                it.groupValues[1].trim() to unescapeXml(it.groupValues[2].trim())
            }
            if (name.isNotEmpty() && translations.isNotEmpty()) {
                AgentInsertCommand(name, translations)
            } else null
        }.toList()
    }

    fun hasCommand(response: String): Boolean = BLOCK_REGEX.containsMatchIn(response)

    fun extractDisplayText(response: String): String {
        return BLOCK_REGEX.replace(response) { match ->
            val name = match.groupValues[1]
            val content = match.groupValues[2]
            val items = ITEM_REGEX.findAll(content).map {
                "  ${it.groupValues[1]}: ${it.groupValues[2].trim()}"
            }.joinToString("\n")
            "[Insert: $name]\n$items"
        }.trim()
    }

    private fun unescapeXml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
