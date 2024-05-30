package cn.jarryleo.insert_strings

import com.intellij.openapi.project.Project

interface OnStringsInsertListener {
    fun onInsert(project: Project, stringName: String, stringsInfoList: Map<String, String>)
}