package cn.jarryleo.insert_strings

data class ClipInfo(
    val node: String,
    val anchor:String,
    val value: Map<String, String>
) {
    fun toXml(): String {
        val sb = StringBuilder()
        sb.append("<string name=\"node\">$node</string>")
        sb.append("<string name=\"anchor\">$anchor</string>")
        sb.append("<string name=\"language\">")
        value.forEach {
            sb.append("<item name=\"${it.key}\">${it.value}</item>")
        }
        sb.append("</string>")
        return sb.toString()
    }

    companion object {
        fun fromXml(xml: String): ClipInfo? {
            if (!xml.contains("<string name=\"node\">")) {
                return null
            }
            val node = xml.substringAfter("<string name=\"node\">").substringBefore("</string>")
            val anchor = xml.substringAfter("<string name=\"anchor\">").substringBefore("</string>")
            val value = mutableMapOf<String, String>()
            val language = xml.substringAfter("<string name=\"language\">").substringBefore("</string>")
            val regex = "<item name=\"(.*?)\">(.*?)</item>".toRegex()
            regex.findAll(language).forEach {
                value[it.groupValues[1]] = it.groupValues[2]
            }
            return ClipInfo(node, anchor, value)
        }
    }
}
