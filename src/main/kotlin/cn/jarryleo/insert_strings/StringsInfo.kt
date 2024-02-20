package cn.jarryleo.insert_strings

import com.intellij.openapi.vfs.VirtualFile

data class StringsInfo(
    val stringsFile: VirtualFile,
    val language: String,
)
