package cn.jarryleo.insert_strings

fun interface UiCallback {
    fun updateUI(nodeName: String, stringsList: List<StringsInfo>?)
}