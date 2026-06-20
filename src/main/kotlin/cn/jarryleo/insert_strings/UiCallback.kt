package cn.jarryleo.insert_strings

import cn.jarryleo.insert_strings.xml.KeyedStringsInfo

fun interface UiCallback {
    fun updateUI(entries: List<KeyedStringsInfo>)
}
