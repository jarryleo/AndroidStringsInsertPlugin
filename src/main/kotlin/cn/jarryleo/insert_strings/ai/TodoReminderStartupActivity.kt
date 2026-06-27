package cn.jarryleo.insert_strings.ai

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 插件启动时初始化 [TodoReminderScheduler]。
 *
 * 触发时机:project 打开 / IDE 启动后,所有项目级 extension 装配完成。
 *
 * 为什么不用 service 的 `loadState` 钩子:scheduler 不持有持久化数据,
 * 它是纯运行时 Timer;真实数据在 [TodoService] 里,通过 `getInstance()` 时被动加载。
 * 我们要的是「IDE 启动时主动 rescheduleAll() 一次,让磁盘上的未来提醒
 * 重新进入 Timer」。
 *
 * 注册方式:在 plugin.xml 加
 * ```
 * <postStartupActivity implementation="...TodoReminderStartupActivity"/>
 * ```
 */
class TodoReminderStartupActivity : StartupActivity {
    private val log = thisLogger()

    override fun runActivity(project: Project) {
        // 第一次访问会触发 @Service 实例化(单例)
        val scheduler = TodoReminderScheduler.getInstance()
        scheduler.rescheduleAll()
        log.info("TodoReminderStartupActivity: scheduler rescheduleAll() called for project=${project.name}")
    }
}
