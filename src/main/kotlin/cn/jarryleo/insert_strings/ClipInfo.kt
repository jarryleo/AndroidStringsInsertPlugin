package cn.jarryleo.insert_strings

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject

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
        val obj = JSONObject()
        obj["anchor"] = anchor
        val arr = JSONArray()
        entries.forEach { entry ->
            val e = JSONObject()
            e["key"] = entry.key
            val t = JSONObject()
            entry.translations.forEach { (k, v) -> t[k] = v }
            e["translations"] = t
            arr.add(e)
        }
        obj["entries"] = arr
        return runCatching { JSON.toJSONString(obj) }.getOrNull() ?: ""
    }

    companion object {

        fun fromJson(json: String): ClipInfo? {
            return try {
                val obj = JSON.parseObject(json)
                val anchor = obj.getString("anchor") ?: ""
                val entriesArr = obj.getJSONArray("entries")
                if (entriesArr != null) {
                    val entries = entriesArr.mapNotNull { el ->
                        val e = el as? JSONObject ?: return@mapNotNull null
                        val key = e.getString("key") ?: return@mapNotNull null
                        val translationsObj = e.getJSONObject("translations") ?: JSONObject()
                        val translations = translationsObj.mapValues { it.value.toString() }
                        ClipEntry(key, translations)
                    }
                    if (entries.isEmpty()) return null
                    ClipInfo(entries, anchor)
                } else {
                    val node = obj.getString("node") ?: return null
                    val valueObj = obj.getJSONObject("value") ?: return null
                    val value = valueObj.mapValues { it.value.toString() }
                    ClipInfo(listOf(ClipEntry(node, value)), anchor)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
