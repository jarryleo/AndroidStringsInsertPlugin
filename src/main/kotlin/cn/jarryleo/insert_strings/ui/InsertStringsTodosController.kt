package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.TodoItem
import cn.jarryleo.insert_strings.ai.TodoPriority
import cn.jarryleo.insert_strings.ai.TodoService

/**
 * 代办列表的 CRUD 协调器。
 *
 * 拆分理由:与 [InsertStringsPhrasesController] / [InsertStringsRolesController] 类似,
 * 列表读 / 写是相对独立的一组操作,独立成类便于阅读,也避免 [InsertStringsSettingsController] 继续膨胀。
 *
 * 状态(列表 / 编辑中条目)由 [InsertStringsUI] 持有,本类只负责:
 * - 从 [TodoService] 加载到 UI state;
 * - 把 UI 上的 add / edit / delete / complete 操作落库,并同步 UI state;
 * - 维护稳定 id(新建时分配,编辑已有时复用);
 * - 接受来自 AI 工具调用(chat driver)的结果,同步刷新 UI 列表(让用户的 Todo tab 立刻反映 AI 的改动)。
 *
 * **排序约定**(controller 写入 UI 时统一):
 * - active(未完成)按 `priority 倒序 + createdAt 倒序` —— URGENT/HIGH 浮在上面,同优先级新加的靠前;
 * - completed(已完成)按 `completedAt 倒序` —— 最近完成的靠前。
 * 这样 UI 默认视图无需再排序,直接渲染即可。
 */
internal class InsertStringsTodosController(
    private val ui: InsertStringsUI,
) {

    /**
     * 从服务加载全部代办到 UI,按上述排序约定排好。
     * 在 tool window 打开时调用一次,只读。
     */
    fun loadTodos() {
        reloadTodos()
    }

    /**
     * 重新从 service 拉取并按排序约定重排,整体替换 [ui.todos]。
     *
     * 公开给 chat driver 调用 —— 当 AI 通过 `todo_add` / `todo_update` / `todo_delete`
     * 修改 service 后,driver 调本方法让 UI 列表立即反映 AI 的改动,
     * 用户切到 Todo tab 时无需手动刷新。
     *
     * 排序规则(active + completed)与 [loadTodos] 相同。
     *
     * 配合 [TodosList] 的 `key(item.id)` 让 Compose 按 id 稳定追踪每行;
     * 整体 clear+addAll 触发的重组能正确把"刚完成的"那条 item 的
     * `isCompleted` 新值反映到 UI。
     */
    fun reloadTodos() {
        val sorted = fetchAndSort()
        ui.todos.clear()
        ui.todos.addAll(sorted)
    }

    /**
     * 进入「新增代办」态:分配新 id 的空白 item 作为草稿,设置到 [InsertStringsUI.editingTodo]。
     * 不写库;Save 时由 [saveEdit] 校验通过后落库(此时 item 才会被加入 [ui.todos] 列表)。
     * 用户中途取消 [cancelEdit] 直接丢弃草稿,无任何持久化副作用。
     */
    fun beginAdd() {
        ui.editingTodo = TodoItem.blank()
    }

    /**
     * 进入「编辑已有代办」态:把目标 item 复制一份到 [ui.editingTodo],保留 id / createdAt / isCompleted。
     * 不写库,Save 时按 id upsert。
     */
    fun beginEdit(item: TodoItem) {
        ui.editingTodo = item.copy()
    }

    /**
     * 取消编辑,不写库。
     */
    fun cancelEdit() {
        ui.editingTodo = null
    }

    /**
     * 保存当前编辑中的代办(新增或更新)。校验:
     * - title 必填(trim 后非空),否则返回 false(UI 用 toast 提示);
     * - content 允许为空;
     * - priority 允许任意值(enum,UI 下拉框约束输入)。
     *
     * 落库后:
     * - 新增:item 进入 service 与 UI 列表(按排序约定插到合适位置);
     * - 更新:按 id 替换 service 与 UI 列表中的原条目;
     * - 退出编辑态。
     *
     * @return true 表示已落库;false 表示校验失败(UI 用 toast 提示)。
     */
    fun saveEdit(
        newTitle: String,
        newContent: String,
        newPriority: TodoPriority,
    ): Boolean {
        val editing = ui.editingTodo ?: return false
        val title = newTitle.trim()
        if (title.isEmpty()) return false
        val content = newContent
        val updated = editing.copy(title = title, content = content, priority = newPriority)
        TodoService.getInstance().upsert(updated)
        // 同步 UI 列表:重排 active + completed,然后整体替换,避免 UI 列表里出现重复条目。
        // 关键:upsert 后已完成的代办不能跑到 active 顶部(isCompleted 由 setCompleted 单独维护,
        // 这里只更新字段不切换完成态),所以排序逻辑可统一处理两种情况。
        // 但要小心:如果 updated.isCompleted = true(AI 此前标了完成,现在用户在改文本),
        // 仍要走 completed 排序,不能塞到 active 顶部。已通过 isCompleted 字段判断,无需特判。
        reloadTodos()
        ui.editingTodo = null
        return true
    }

    /**
     * 删除一条代办。立即写库 + 同步 UI 列表。
     * 若删的是当前正在编辑的代办,自动退出编辑态。
     */
    fun delete(item: TodoItem) {
        TodoService.getInstance().delete(item.id)
        // 用 removeAll 触发单条删除的细粒度重组;Compose 配合 [TodosList] 的
        // key(item.id) 能正确把对应行移出。
        ui.todos.removeAll { it.id == item.id }
        if (ui.editingTodo?.id == item.id) {
            ui.editingTodo = null
        }
    }

    /**
     * 切换完成状态(勾选 / 取消勾选 checkbox)。由 service 自动维护 completedAt 时间戳。
     * UI 列表立即重排:刚完成的会跑到 completed 区(底部),刚取消完成的会回到 active 区
     * (按 priority + createdAt 排序)。
     *
     * 配合 [TodosList] 的 `key(item.id)`,整体重排时 Compose 能正确把"刚完成的"
     * 那条 item 的新 isCompleted 反映到 UI —— 之前 UI 不更新的根因是 forEach 没有 key,
     * 整列重排时 Compose 按位置匹配,把刚完成的 item 误当成"原来位置上的另一个 item"
     * 而跳过重组。
     */
    fun setCompleted(item: TodoItem, completed: Boolean) {
        item.completeState.value = completed
        TodoService.getInstance().setCompleted(item.id, completed)
        reloadTodos()
    }

    /**
     * 从 service 拉最新数据并按排序约定(active 按 priority + createdAt 倒序在前,
     * completed 按 completedAt 倒序在后)排序,返回新 List。
     * 不直接修改 [ui.todos] —— 调用方负责用返回值整体替换 `ui.todos = newList`。
     */
    private fun fetchAndSort(): List<TodoItem> {
        val service = TodoService.getInstance()
        val active = service.listActive()
            .sortedWith(
                compareByDescending<TodoItem> { it.priority.ordinal }
                    .thenByDescending { it.createdAt }
            )
        val completed = service.listCompleted()
            .sortedByDescending { it.completedAt ?: 0L }
        return active + completed
    }
}
