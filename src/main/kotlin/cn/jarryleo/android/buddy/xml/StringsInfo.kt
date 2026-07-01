package cn.jarryleo.android.buddy.xml

import com.intellij.openapi.vfs.VirtualFile

data class StringsInfo(
    val stringsFile: VirtualFile?,
    val language: String,
    val key: String,
    var text: String
)

/**
 * 单个选中 key 的多语言翻译集合。
 * [anchorNodeName] 为该 key 在源文件中紧随其后的下一个 <string> key，
 * 用于在其它语言文件中插入新 key 时定位。
 */
data class KeyedStringsInfo(
    val key: String,
    val anchorNodeName: String,
    val stringsInfoList: List<StringsInfo>
)
