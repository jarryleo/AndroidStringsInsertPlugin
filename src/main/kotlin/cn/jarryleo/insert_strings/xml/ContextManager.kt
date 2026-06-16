package cn.jarryleo.insert_strings.xml

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project


object ContextManager {

    var contextInfo: ContextInfo? = null
    fun initContextInfo(project: Project) {
        //初始化项目相关 strings.xml 信息
        //第一步：获取项目模块列表
        val moduleManager = ModuleManager.getInstance(project)
        val modules = moduleManager.modules
        val moduleNameList = modules.map { it.name }
        //第二步，获取每个模块内所有的 strings.xml 文件
        val xmlFileList = modules.map { moduleName ->

        }
    }
}