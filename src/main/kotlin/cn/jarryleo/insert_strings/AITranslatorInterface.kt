package cn.jarryleo.insert_strings

interface AITranslatorInterface {
    fun translate(code: String, text: String, callback: AiCallback)
}

interface AiCallback {
    fun onAiTranslateComplete(text: String)
}