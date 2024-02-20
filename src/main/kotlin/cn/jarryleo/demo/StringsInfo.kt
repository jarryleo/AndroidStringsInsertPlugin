package cn.jarryleo.demo

import com.intellij.openapi.vfs.VirtualFile

data class StringsInfo(
    val stringsFile: VirtualFile,
    val language: String,
)
