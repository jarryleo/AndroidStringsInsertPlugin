package cn.jarryleo.insert_strings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 剪贴板中单条 key 的翻译快照。
 */
data class ClipEntry(
    val key: String,
    val translations: Map<String, String>
)

/**
 * 多 key 复制/粘贴的剪贴板载体。
 *
 * 新格式：{"anchor": "...", "entries": [{"key": "k1", "translations": {"values": "..."}}, ...]}
 * 旧格式：{"node": "k1", "anchor": "...", "value": {"values": "..."}} 会被兼容解析为单条 entry。
 */
data class ClipInfo(
    val entries: List<ClipEntry>,
    val anchor: String,
) {

    fun toJson(): String {
        val obj = JsonObject()
        obj.addProperty("anchor", anchor)
        val arr = JsonArray()
        entries.forEach { entry ->
            val e = JsonObject()
            e.addProperty("key", entry.key)
            val t = JsonObject()
            entry.translations.forEach { (k, v) -> t.addProperty(k, v) }
            e.add("translations", t)
            arr.add(e)
        }
        obj.add("entries", arr)
        return obj.toString()
    }

    companion object {

        fun fromJson(json: String): ClipInfo? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val anchor = obj.get("anchor")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val entriesArr = obj.getAsJsonArray("entries")
                if (entriesArr != null) {
                    val entries = entriesArr.mapNotNull { el ->
                        if (!el.isJsonObject) return@mapNotNull null
                        val e = el.asJsonObject
                        val key = e.get("key")?.takeIf { !it.isJsonNull }?.asString
                            ?: return@mapNotNull null
                        val translationsObj = e.getAsJsonObject("translations")
                        val translations = translationsObj?.entrySet()?.associate { (k, v) ->
                            k to v.asString
                        }.orEmpty()
                        ClipEntry(key, translations)
                    }
                    if (entries.isEmpty()) return null
                    ClipInfo(entries, anchor)
                } else {
                    // 旧格式兼容:{"node":"k1","anchor":"...","value":{...}}
                    val node = obj.get("node")?.takeIf { !it.isJsonNull }?.asString
                        ?: return null
                    val valueObj = obj.getAsJsonObject("value") ?: return null
                    val value = valueObj.entrySet().associate { (k, v) -> k to v.asString }
                    ClipInfo(listOf(ClipEntry(node, value)), anchor)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
