package cn.jarryleo.insert_strings.xml

import com.intellij.openapi.vfs.VirtualFile

data class StringsInfo(
    val stringsFile: VirtualFile?,
    val language: String,
    val key:String,
    var text:String
)
