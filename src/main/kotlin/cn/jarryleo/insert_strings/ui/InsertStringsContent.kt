package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.AiProvider
import cn.jarryleo.insert_strings.ai.AiProtocol
import cn.jarryleo.insert_strings.ai.AiRole
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.ai.TodoItem
import cn.jarryleo.insert_strings.ai.TodoPriority
import cn.jarryleo.insert_strings.phrases.QuickPhrase

/**
 * 插件主面板的根 Composable。
 *
 * 重构说明(2026.x):之前是「showSettings / showChat 两个 Boolean + 主表 else 分支」的方式
 * 切换「翻译表 / Chat / 设置」三个页面,顶部要单独留一行 Chat / Settings 按钮做导航;
 * 现在改为「顶部一个 tab 栏,选哪个就展示哪个视图」,符合常见 IDE 工具窗口的多 tab 范式。
 *
 * 三 tab 各自承载的内容(由 [mainTab] 决定):
 * - [MainTab.TRANSLATIONS] 选中翻译列表 / 主编辑表:
 *     顶部 key 选择器 + `<string name=` 输入 + 多语言 StringsTable + Copy / Paste / Insert 按钮。
 * - [MainTab.CHAT]         AI 聊天视图:由 [AiChatContent] 渲染,自带 Clear / Context / New Topic 等按钮。
 * - [MainTab.SETTINGS]     设置页:由 [SettingsContent] 渲染,内部仍有 [SettingsTab] 子 tab(AI / Role / Sheets / Phrases / Debug)。
 *
 * Toast 由最外层 Box 统一挂载,所有 tab 共享。
 */
@Composable
internal fun InsertStringsContent(
    stringName: String,
    rows: List<StringRow>,
    keys: List<String>,
    selectedKeyIndex: Int,
    onStringNameChange: (String) -> Unit,
    onTextChange: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAi: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onInsert: () -> Unit,
    onSelectKey: (Int) -> Unit,
    toastMessage: String,
    // ===== 顶级 tab 导航 =====
    /**
     * 当前激活的顶级 tab(翻译表 / Chat / 设置)。
     * 顶部 tab 栏会根据这个值高亮对应按钮。
     */
    mainTab: MainTab,
    /**
     * 切到新 tab 的回调,直接把 [MainTab] 写回 [InsertStringsUI.mainTab]。
     */
    onMainTabChange: (MainTab) -> Unit,
    // ===== Settings 子 tab =====
    /**
     * 设置页内的子 tab(AI / Role / Sheets / Phrases / Debug)。
     * 仅 [mainTab] = [MainTab.SETTINGS] 时生效;其它 tab 渲染时本参数被忽略。
     */
    settingsTab: SettingsTab,
    /**
     * 切换设置子 tab 的回调,直接写回 [InsertStringsUI.settingsTab]。
     * 非设置 tab 不需要;但为统一签名,这里始终要求传入,UI 层用 `when` 自动路由。
     */
    onSettingsTabChange: (SettingsTab) -> Unit,
    // ===== AI 设置 state =====
    aiProviders: List<AiProvider>,
    currentAiProviderId: String?,
    editingAiProvider: AiProvider?,
    editingIsNew: Boolean,
    modelOptions: List<String>,
    modelFetchStatus: String,
    onAddAiProvider: () -> Unit,
    onEditAiProvider: (AiProvider) -> Unit,
    onDeleteAiProvider: (AiProvider) -> Unit,
    onUseAiProvider: (AiProvider) -> Unit,
    onAiProviderNameChange: (String) -> Unit,
    onAiProviderUrlChange: (String) -> Unit,
    onAiProviderApiKeyChange: (String) -> Unit,
    onAiProviderProtocolChange: (AiProtocol) -> Unit,
    onAiProviderModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveAiProvider: () -> Boolean,
    onCancelAiProviderEdit: () -> Unit,
    // ===== Sheets 设置 =====
    sheetsDefaultSpreadsheetId: String,
    sheetsDefaultSheetName: String,
    sheetsConnectionStatus: String,
    sheetsAvailableSheetNames: List<String>,
    sheetsListStatus: String,
    onSheetsDefaultSpreadsheetIdChange: (String) -> Unit,
    onSheetsDefaultSheetNameChange: (String) -> Unit,
    onTestSheetsConnection: () -> Unit,
    onSaveSheetsSettings: () -> Unit,
    onRefreshSheetsList: () -> Unit,
    // ===== Quick Phrases =====
    phrases: List<QuickPhrase>,
    editingPhrase: QuickPhrase?,
    onAddPhrase: () -> Unit,
    onEditPhrase: (QuickPhrase) -> Unit,
    onDeletePhrase: (QuickPhrase) -> Boolean,
    onMovePhrase: (fromIndex: Int, toIndex: Int) -> Unit,
    onSavePhraseEdit: (title: String, text: String, color: String?) -> Boolean,
    onCancelPhraseEdit: () -> Unit,
    onResetDefaultPhrases: () -> Unit,
    // ===== AI Roles =====
    roles: List<AiRole>,
    editingRole: AiRole?,
    onAddRole: () -> Unit,
    onEditRole: (AiRole) -> Unit,
    onDeleteRole: (AiRole) -> Unit,
    onSetRoleEnabled: (AiRole, Boolean) -> Unit,
    onDraftRoleTitleChange: (String) -> Unit,
    onDraftRolePromptChange: (String) -> Unit,
    onSaveRoleEdit: (title: String, prompt: String) -> Boolean,
    onCancelRoleEdit: () -> Unit,
    // ===== Todo =====
    /**
     * 已保存的代办列表(按 controller 排序好的:active 在上,completed 在下)。
     * 由 [InsertStringsTodosController.loadTodos] / 任何 add/update/delete/complete 后重排写入。
     */
    todos: List<TodoItem>,
    /**
     * 当前 Todo tab 的过滤模式(All / Active / Completed)。
     */
    todoFilter: TodoFilter,
    /**
     * 未完成 todo 的总数(用于 toolbar 上 Active tab 旁的数字)。
     */
    activeTodoCount: Int,
    /**
     * 已完成 todo 的总数(用于 toolbar 上 Completed tab 旁的数字)。
     */
    completedTodoCount: Int,
    /**
     * 当前正在行内编辑的 todo(null 表示列表模式)。
     * 编辑态下,对应行就地展开为编辑表单(Title + Content + Priority + Save / Cancel)。
     */
    editingTodo: TodoItem?,
    /**
     * 切换 Todo 过滤的回调,直接写回 [InsertStringsUI.todoFilter]。
     */
    onTodoFilterChange: (TodoFilter) -> Unit,
    onAddTodo: () -> Unit,
    onEditTodo: (TodoItem) -> Unit,
    onDeleteTodo: (TodoItem) -> Unit,
    onSetTodoCompleted: (TodoItem, Boolean) -> Unit,
    onDraftTodoTitleChange: (String) -> Unit,
    onDraftTodoContentChange: (String) -> Unit,
    onDraftTodoPriorityChange: (TodoPriority) -> Unit,
    onSaveTodoEdit: (title: String, content: String, priority: TodoPriority) -> Boolean,
    onCancelTodoEdit: () -> Unit,
    // ===== Chat =====
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onStopChat: () -> Unit,
    onQuickSend: (String) -> Unit,
    onNewChat: () -> Unit,
    onOptionClick: (Int, Int, String) -> Unit,
    onOpenContext: () -> Unit,
    onCloseContext: () -> Unit,
    showContextPopup: Boolean,
    chatContextText: String,
    chatMessages: List<ChatMessage>,
    chatInput: String,
    chatSending: Boolean,
    /**
     * 「Clear」按钮回调:清除主面板 keyEntries + rows,同步清掉聊天顶部「已选择翻译(N)」
     * 与主面板表格。仅主面板聊天传这个回调;弹框场景不传,按钮不渲染。
     * [canClearSelected] 控制按钮 enabled 状态(无选中内容时置灰)。
     */
    onClearSelected: () -> Unit,
    canClearSelected: Boolean,
    /**
     * 当前用户在 strings.xml 中选中的 key 列表,用于聊天面板顶部展示「已选择翻译(N)」。
     * 由父组件从 keyEntries 派生(随用户重新选 key 同步更新)。
     */
    selectedKeys: List<String> = emptyList(),
    /**
     * 入口携带的「引用内容」,聊天面板顶部以独立气泡展示。
     * 主面板聊天视图始终传 null(无编辑器上下文);AskAi/ExtractStrings 弹框会传入选区文本。
     */
    quoteContent: String? = null,
    onQuoteDismiss: (() -> Unit)? = null,
    /**
     * 引用面板「复制」按钮回调:由调用方写剪贴板 + 弹 toast。
     * 主面板 / 弹框都需要,统一透传。
     */
    onCopyQuote: ((String) -> Unit)? = null,
) {
    val colors = rememberIdeColors()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.panel,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ===== 顶部顶级 tab 栏 =====
                // 三个 tab(翻译表 / Chat / 设置)固定一行,选中态用 accent 底色高亮。
                // tab 顺序按使用频率:翻译表(最高频)→ Chat(次频)→ 设置(偶尔)。
                MainTabBar(
                    selected = mainTab,
                    onTabChange = onMainTabChange,
                    colors = colors,
                )

                // ===== 选中 tab 对应的视图 =====
                when (mainTab) {
                    MainTab.TRANSLATIONS -> TranslationsTabContent(
                        keys = keys,
                        selectedKeyIndex = selectedKeyIndex,
                        onSelectKey = onSelectKey,
                        stringName = stringName,
                        onStringNameChange = onStringNameChange,
                        rows = rows,
                        onTextChange = onTextChange,
                        onClear = onClear,
                        onAi = onAi,
                        onCopy = onCopy,
                        onPaste = onPaste,
                        onInsert = onInsert,
                        colors = colors,
                    )
                    MainTab.CHAT -> AiChatContent(
                        chatMessages = chatMessages,
                        chatInput = chatInput,
                        chatSending = chatSending,
                        // 顶级 tab 模式下 Chat 不再需要 Back 按钮关闭,关闭语义 = 切到其它 tab。
                        // 这里传一个 no-op,因为 AiChatContent 的 [onClose] 是工具窗口级关闭,
                        // 由 IDE 自身的「关闭工具窗口」动作处理,不由 tab 切回触发。
                        onClose = {},
                        onNewChat = onNewChat,
                        onChatInputChange = onChatInputChange,
                        onSendChat = onSendChat,
                        onStopChat = onStopChat,
                        onQuickSend = onQuickSend,
                        onOptionClick = onOptionClick,
                        onOpenContext = onOpenContext,
                        onCloseContext = onCloseContext,
                        showContextPopup = showContextPopup,
                        chatContextText = chatContextText,
                        quickPhrases = phrases,
                        selectedKeys = selectedKeys,
                        quoteContent = quoteContent,
                        onQuoteDismiss = onQuoteDismiss,
                        onCopyQuote = onCopyQuote,
                        onClearSelected = onClearSelected,
                        canClear = canClearSelected,
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                        // 顶级 tab 模式下不再渲染 Chat 顶部的 "Back" 按钮(切 tab 由外层 tab 栏承担);
                        // "New Topic" / "Context" 仍保留。
                        showHeader = true,
                    )
                    MainTab.TODOS -> TodosContent(
                        // 按当前过滤模式筛选展示,保持 controller 排序(active 在上 / completed 在下)。
                        // 过滤是 UI 行为,不在 controller 里做(避免每次切 tab 都要重新 load)。
                        todos = filterTodos(todos, todoFilter),
                        activeCount = activeTodoCount,
                        completedCount = completedTodoCount,
                        currentFilter = todoFilter,
                        onFilterChange = onTodoFilterChange,
                        editingTodo = editingTodo,
                        onAdd = onAddTodo,
                        onEdit = onEditTodo,
                        onDelete = onDeleteTodo,
                        onSetCompleted = onSetTodoCompleted,
                        onDraftTitleChange = onDraftTodoTitleChange,
                        onDraftContentChange = onDraftTodoContentChange,
                        onDraftPriorityChange = onDraftTodoPriorityChange,
                        onSaveEdit = onSaveTodoEdit,
                        onCancelEdit = onCancelTodoEdit,
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                    )
                    MainTab.SETTINGS -> SettingsContent(
                        selectedTab = settingsTab,
                        onTabChange = onSettingsTabChange,
                        // 设置页内的顶部 "Back" 按钮在顶级 tab 模式下退化为 no-op
                        // (由 tab 栏切回其它 tab);保留签名兼容。
                        onClose = {},
                        aiProviders = aiProviders,
                        currentAiProviderId = currentAiProviderId,
                        editingAiProvider = editingAiProvider,
                        editingIsNew = editingIsNew,
                        modelOptions = modelOptions,
                        modelFetchStatus = modelFetchStatus,
                        onAddAiProvider = onAddAiProvider,
                        onEditAiProvider = onEditAiProvider,
                        onDeleteAiProvider = onDeleteAiProvider,
                        onUseAiProvider = onUseAiProvider,
                        onAiProviderNameChange = onAiProviderNameChange,
                        onAiProviderUrlChange = onAiProviderUrlChange,
                        onAiProviderApiKeyChange = onAiProviderApiKeyChange,
                        onAiProviderProtocolChange = onAiProviderProtocolChange,
                        onAiProviderModelChange = onAiProviderModelChange,
                        onFetchModels = onFetchModels,
                        onSaveAiProvider = onSaveAiProvider,
                        onCancelAiProviderEdit = onCancelAiProviderEdit,
                        sheetsDefaultSpreadsheetId = sheetsDefaultSpreadsheetId,
                        sheetsDefaultSheetName = sheetsDefaultSheetName,
                        sheetsConnectionStatus = sheetsConnectionStatus,
                        sheetsAvailableSheetNames = sheetsAvailableSheetNames,
                        sheetsListStatus = sheetsListStatus,
                        onSheetsDefaultSpreadsheetIdChange = onSheetsDefaultSpreadsheetIdChange,
                        onSheetsDefaultSheetNameChange = onSheetsDefaultSheetNameChange,
                        onTestSheetsConnection = onTestSheetsConnection,
                        onSaveSheetsSettings = onSaveSheetsSettings,
                        onRefreshSheetsList = onRefreshSheetsList,
                        phrases = phrases,
                        editingPhrase = editingPhrase,
                        onAddPhrase = onAddPhrase,
                        onEditPhrase = onEditPhrase,
                        onDeletePhrase = onDeletePhrase,
                        onMovePhrase = onMovePhrase,
                        onSavePhraseEdit = onSavePhraseEdit,
                        onCancelPhraseEdit = onCancelPhraseEdit,
                        onResetDefaultPhrases = onResetDefaultPhrases,
                        roles = roles,
                        editingRole = editingRole,
                        onAddRole = onAddRole,
                        onEditRole = onEditRole,
                        onDeleteRole = onDeleteRole,
                        onSetRoleEnabled = onSetRoleEnabled,
                        onDraftRoleTitleChange = onDraftRoleTitleChange,
                        onDraftRolePromptChange = onDraftRolePromptChange,
                        onSaveRoleEdit = onSaveRoleEdit,
                        onCancelRoleEdit = onCancelRoleEdit,
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                    )
                }
            }

            if (toastMessage.isNotEmpty()) {
                ToastMessage(
                    text = toastMessage,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 42.dp),
                    colors = colors,
                )
            }
        }
    }
}

/**
 * 顶级 tab 栏(翻译表 / Chat / 设置)。
 *
 * 视觉与 [SettingsContent] 的子 tab 栏保持一致(用同一个 [TabButton] 组件),
 * 选中态用 accent 底色高亮,未选中用 buttonBackground。
 */
@Composable
private fun MainTabBar(
    selected: MainTab,
    onTabChange: (MainTab) -> Unit,
    colors: IdeColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabButton(
            text = "Translations",
            selected = selected == MainTab.TRANSLATIONS,
            onClick = { onTabChange(MainTab.TRANSLATIONS) },
            colors = colors,
        )
        TabButton(
            text = "Chat",
            selected = selected == MainTab.CHAT,
            onClick = { onTabChange(MainTab.CHAT) },
            colors = colors,
        )
        // Todo tab:位于 Chat 与 Settings 之间,展示代办列表(主面板专属)。
        // 不在 tab label 上加计数(避免标签过长,让 toolbar 的 TabButton 保持统一宽度);
        // 计数信息在 [TodosContent] 内部的过滤 tab 上展示。
        TabButton(
            text = "Todo",
            selected = selected == MainTab.TODOS,
            onClick = { onTabChange(MainTab.TODOS) },
            colors = colors,
        )
        TabButton(
            text = "Settings",
            selected = selected == MainTab.SETTINGS,
            onClick = { onTabChange(MainTab.SETTINGS) },
            colors = colors,
        )
    }
}

/**
 * 翻译表 tab 的内容(原主面板翻译表区域):
 * - 顶部:key 选择器(下拉,左侧)+ 空右侧(已无 Chat / Settings 按钮,由 tab 栏承担);
 * - 第二行:`<string name=` 前缀 + string name 输入框;
 * - 中部:多语言 StringsTable(占满剩余高度);
 * - 底部:Copy / Paste / Insert 三个等宽按钮。
 */
@Composable
private fun TranslationsTabContent(
    keys: List<String>,
    selectedKeyIndex: Int,
    onSelectKey: (Int) -> Unit,
    stringName: String,
    onStringNameChange: (String) -> Unit,
    rows: List<StringRow>,
    onTextChange: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAi: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onInsert: () -> Unit,
    colors: IdeColors,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeySelectorDropdown(
            keys = keys,
            selectedIndex = selectedKeyIndex,
            onSelect = onSelectKey,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "<string name=",
                color = colors.text,
                style = compactTextStyle(colors.text),
            )
            CompactTextField(
                value = stringName,
                onValueChange = onStringNameChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = colors,
            )
        }

        StringsTable(
            rows = rows,
            onTextChange = onTextChange,
            onClear = onClear,
            onAi = onAi,
            modifier = Modifier.weight(1f),
            colors = colors,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactButton(
                text = "Copy",
                onClick = onCopy,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            CompactButton(
                text = "Paste",
                onClick = onPaste,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            CompactButton(
                text = "Insert",
                onClick = onInsert,
                modifier = Modifier.weight(1f),
                colors = colors,
                primary = true,
            )
        }
    }
}

/**
 * 按 [TodoFilter] 过滤代办列表。
 *
 * 输入的 [todos] 已经是 controller 排好序的(active 在上 / completed 在下);
 * 这里只做"是否显示",不再重排。
 */
private fun filterTodos(todos: List<TodoItem>, filter: TodoFilter): List<TodoItem> {
    return when (filter) {
        TodoFilter.ALL -> todos
        TodoFilter.ACTIVE -> todos.filter { !it.isCompleted }
        TodoFilter.COMPLETED -> todos.filter { it.isCompleted }
    }
}
