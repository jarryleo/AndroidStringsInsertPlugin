package cn.jarryleo.insert_strings

import com.intellij.openapi.vfs.VirtualFile

data class StringsInfo(
    val stringsFile: VirtualFile, //strings.xml文件
    val language: String, //对应的国际化语言
    val key:String,//对应的text的key
    val text:String //对应的text
)
