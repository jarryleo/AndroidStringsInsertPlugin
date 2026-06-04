package cn.jarryleo.insert_strings.ai

enum class AiProtocol(
    val displayName: String,
    val chatEndpointSuffix: String,
) {
    OPENAI("OpenAI", "/v1/chat/completions"),
    ANTHROPIC("Anthropic", "/v1/messages");

    companion object {
        fun fromName(name: String?): AiProtocol {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OPENAI
        }
    }
}

object AiEndpoint {
    private val knownChatSuffixes = AiProtocol.entries.map { it.chatEndpointSuffix }

    fun completeChatEndpoint(rawUrl: String, protocol: AiProtocol): String {
        val value = rawUrl.trim().trimEnd('/')
        val suffix = protocol.chatEndpointSuffix
        if (value.isEmpty()) return suffix

        knownChatSuffixes.firstOrNull { value.endsWith(it, ignoreCase = true) }?.let {
            return value.dropLast(it.length) + suffix
        }

        val matchingPrefix = suffix.indices
            .map { suffix.substring(0, it + 1) }
            .filter { value.endsWith(it, ignoreCase = true) }
            .maxByOrNull { it.length }

        return if (matchingPrefix != null) {
            value + suffix.substring(matchingPrefix.length)
        } else {
            value + suffix
        }
    }

    fun completeModelsEndpoint(rawUrl: String, protocol: AiProtocol): String {
        val chatEndpoint = completeChatEndpoint(rawUrl, protocol)
        val suffix = protocol.chatEndpointSuffix
        return if (chatEndpoint.endsWith(suffix, ignoreCase = true)) {
            chatEndpoint.dropLast(suffix.length) + "/v1/models"
        } else {
            chatEndpoint.trimEnd('/') + "/v1/models"
        }
    }
}
