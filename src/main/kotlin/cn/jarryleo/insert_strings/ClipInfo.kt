package cn.jarryleo.insert_strings

import com.alibaba.fastjson2.JSON

data class ClipInfo(
    val node: String,
    val anchor: String,
    val value: Map<String, String>
) {

    fun toJson(): String {
        return JSON.toJSONString(this)
    }

    companion object {

        fun fromJson(json: String): ClipInfo? {
            return try {
                val obj = JSON.parseObject(json)
                val node = obj.getString("node")
                val anchor = obj.getString("anchor")
                val valueObj = obj.getJSONObject("value")
                if (node == null || anchor == null || valueObj == null) {
                    return null
                }
                val value = valueObj.mapValues { it.value.toString() }
                ClipInfo(node, anchor, value)
            } catch (e: Exception) {
                null
            }
        }
    }
}
