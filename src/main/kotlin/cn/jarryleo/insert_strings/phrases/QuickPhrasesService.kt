package cn.jarryleo.insert_strings.phrases

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 持久化层 bean。IntelliJ 的 [PersistentStateComponent] 通过反射读写 bean 属性,
 * 所以这里每个字段必须是 public `var`,`MutableList<QuickPhrase>` 会被自动 XML 序列化。
 *
 * 注意:数据类用 `var` 而不是 `val`,因为 IntelliJ 反序列化需要 setter。
 */
class QuickPhrasesState {
    var phrases: MutableList<QuickPhrase> = mutableListOf()
}

/**
 * 全局(应用级)快捷短语持久化服务。
 *
 * 用 `applicationService` 而非 `projectService`:
 * - 「快捷短语」是用户的个人偏好,不与具体项目绑定;
 * - 用户希望换一个项目也能直接用同一组短语,无需重复配置。
 *
 * 存储位置:`<config>/options/insertStringsQuickPhrases.xml`(应用级配置目录)。
 */
@State(
    name = "InsertStringsQuickPhrases",
    storages = [Storage("insertStringsQuickPhrases.xml")]
)
class QuickPhrasesService : PersistentStateComponent<QuickPhrasesState> {
    private var state = QuickPhrasesState()

    override fun getState(): QuickPhrasesState = state

    override fun loadState(state: QuickPhrasesState) {
        this.state = state
    }

    /**
     * 整体替换短语列表,用于批量编辑后保存。
     * 入参会做一次防御性拷贝,避免外部后续修改影响已保存数据。
     */
    fun replaceAll(phrases: List<QuickPhrase>) {
        state.phrases = phrases.toMutableList()
    }

    /**
     * 新增或更新一条短语(按 [QuickPhrase.id] 匹配)。
     * 编辑时复用 [QuickPhrase.id],新增时会分配新 id。
     */
    fun upsert(phrase: QuickPhrase) {
        val list = state.phrases
        val idx = list.indexOfFirst { it.id == phrase.id }
        if (idx >= 0) {
            list[idx] = phrase
        } else {
            list.add(phrase)
        }
    }

    /**
     * 按 id 删除一条短语。id 不存在时为 no-op。
     */
    fun delete(id: String) {
        state.phrases.removeAll { it.id == id }
    }

    /**
     * 当前已保存的全部短语(返回不可变副本,避免外部直接修改内部 state)。
     */
    fun list(): List<QuickPhrase> = state.phrases.toList()

    companion object {
        @JvmStatic
        fun getInstance(): QuickPhrasesService {
            return ApplicationManager.getApplication().getService(QuickPhrasesService::class.java)
        }
    }
}
