package cn.jarryleo.android.buddy

import cn.jarryleo.android.buddy.xml.KeyedStringsInfo

fun interface UiCallback {
    fun updateUI(entries: List<KeyedStringsInfo>)
}
