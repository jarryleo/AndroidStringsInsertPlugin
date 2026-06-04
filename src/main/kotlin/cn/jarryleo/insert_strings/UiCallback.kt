package cn.jarryleo.insert_strings

import cn.jarryleo.insert_strings.xml.StringsInfo

fun interface UiCallback {
    fun updateUI(nodeName: String, stringsList: List<StringsInfo>?)
}