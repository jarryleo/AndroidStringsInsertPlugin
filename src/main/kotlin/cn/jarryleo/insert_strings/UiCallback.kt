package cn.jarryleo.insert_strings

fun interface UiCallback {
    fun updateUI(nodeName: String, languages: List<String>?, stringsList: List<StringsInfo>?)
}