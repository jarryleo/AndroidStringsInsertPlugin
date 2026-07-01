package cn.jarryleo.android.buddy.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
import cn.jarryleo.android.buddy.ClipboardManager
import cn.jarryleo.android.buddy.InsertStringsManager
import cn.jarryleo.android.buddy.UiCallback
import cn.jarryleo.android.buddy.ai.*
import cn.jarryleo.android.buddy.phrases.QuickPhrase
import cn.jarryleo.android.buddy.sheets.SheetsManager
import cn.jarryleo.android.buddy.xml.ContextManager
import cn.jarryleo.android.buddy.xml.KeyedStringsInfo
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 主面板的状态容器 + 协调器(轻量 facade)。
 *
 * 真实逻辑拆分到以下协作类(都在同包,见 [cn.jarryleo.android.buddy.ui]):
 *  - [InsertStringsActionsController]    Copy / Paste / Insert / 选 key / 单行 AI 翻译
 *  - [InsertStringsSettingsController]   AI + Sheets 设置加载/保存、模型拉取、工作表刷新
 *  - [InsertStringsStringsOpsController] AI 驱动的 strings.xml 读写/反查
 *  - [InsertStringsSheetsOpsController]  AI 驱动的 Google Sheets 操作 + sheet↔UI 映射
 *  - [InsertStringsChatContextBuilder]   每次 AI 调用附带的项目上下文 JSON
 *  - [InsertStringsChatDriver]           chat 流程驱动器(tool loop / 消息收发 / 协议兜底)
 *
 * InsertStringsUI 本身只负责:
 *  1. 持有 Compose state(`mutableStateOf` / `mutableStateListOf`)
 *  2. 把 state 暴露给上述 controller
 *  3. 在 Composable 中把 state 串起来
 *  4. UiCallback / ToolWindow 生命周期入口
 */
class InsertStringsUI(
    private val toolWindow: ToolWindow
) : UiCallback, ChatStateHolder {

    // ============== 业务依赖(由 IntelliJ 注入) ==============
    override lateinit var project: Project
        private set
    override lateinit var insertStringsManager: InsertStringsManager
        private set

    // ============== 主表编辑 state ==============
    internal var stringName by mutableStateOf("")
    override val rows = mutableStateListOf<StringRow>()
    override val keyEntries = mutableStateListOf<KeyedStringsInfo>()
    override var selectedKeyIndex by mutableStateOf(0)
    internal var toastMessage by mutableStateOf("")
    internal var toastTimer: Timer? = null

    // ============== 主页 tab state ==============
    /**
     * 当前主页所在的顶级 tab(Chat / 翻译表 / Todo / Settings)。
     * Android Buddy 默认以 Chat 为首页,工具窗口打开时直接进入 AI 聊天面板,
     * 用户点顶部 tab 栏可切换到其它视图。重构自原先的 `showSettings` / `showChat`
     * 两个 Boolean,改成单枚举后避免非法组合(同时为 true / 都不为 true)与多入口不一致。
     */
    internal var mainTab by mutableStateOf(MainTab.CHAT)

    /**
     * Settings tab 内的子 tab 状态(AI / Role / Google Sheets / Quick Phrases / Debug)。
     * 仅在 [mainTab] = [MainTab.SETTINGS] 时生效;切换到其它顶级 tab 不重置,
     * 用户从 Settings 切到 Chat 再切回 Settings 时,保留上次的子 tab。
     */
    internal var settingsTab by mutableStateOf(SettingsTab.AI)

    // ============== AI 设置 state(多 provider 模型) ==============
    /**
     * 全部 AI provider 列表。展示在设置面板的「提供商列表」中,持久化在 [cn.jarryleo.android.buddy.ai.AiSettingsService]。
     * UI 上任何增/删/改都同步写入 service。
     */
    internal val aiProviders = mutableStateListOf<AiProvider>()

    /**
     * 当前激活 provider 的 id(AI 调用时实际使用)。null 表示还没有任何 provider。
     */
    internal var currentAiProviderId by mutableStateOf<String?>(null)

    /**
     * 当前正在编辑的 provider 草稿。
     * - 非 null 时,设置面板下方展开编辑表单;
     * - null 时,设置面板不显示编辑表单。
     * 编辑中字段 = 用户当前输入;Save 时由 controller 校验后写入 service 并同步到 [aiProviders]。
     */
    internal var editingAiProvider: AiProvider? by mutableStateOf(null)

    /**
     * 当前编辑的 provider 是「新建」还是「编辑已有」。
     * - true:用户点 "+ Add" 进入新建,editingAiProvider.id 不在 [aiProviders] 中,Save 时按新增走;
     * - false:用户点列表某条的「Edit」进入编辑,editingAiProvider.id 是已存在 provider 的 id。
     * 用一个独立字段而不是「editingAiProvider.id 是否在列表中」来推导,避免编辑过程中
     * 由于 save 后再编辑造成状态混乱。
     */
    internal var editingIsNew: Boolean = false

    /**
     * 编辑表单「Get Models」时拉取到的模型列表(供 ModelField 下拉使用)。
     */
    internal val modelOptions = mutableStateListOf<String>()

    /**
     * 编辑表单「Get Models」状态文本("Loading models." / "Loaded N models." / 错误信息)。
     */
    internal var modelFetchStatus by mutableStateOf("")

    // ============== Sheets 设置 state ==============
    internal var sheetsDefaultSpreadsheetId by mutableStateOf("")
    internal var sheetsDefaultSheetName by mutableStateOf("Sheet1")
    internal var sheetsConnectionStatus by mutableStateOf("")
    override val sheetsAvailableSheets = mutableStateListOf<SheetsManager.SheetInfo>()
    internal var sheetsListStatus by mutableStateOf("")

    // ============== 快捷短语 state ==============
    /**
     * 已保存的快捷短语列表(应用级持久化)。
     * 在 tool window 打开时由 [InsertStringsPhrasesController.loadPhrases] 加载。
     */
    internal val quickPhrases = mutableStateListOf<QuickPhrase>()

    /**
     * 当前正在行内编辑的短语(null 表示列表模式)。
     * 编辑态下,原行就地展开为编辑表单;Save 落库或 Cancel 丢弃。
     */
    internal var editingPhrase: QuickPhrase? by mutableStateOf(null)

    // ============== AI 角色预设 state ==============
    /**
     * 已保存的 AI 角色预设列表(应用级持久化)。
     * 在 tool window 打开时由 [InsertStringsRolesController.loadRoles] 加载。
     * 启用其中一条后,AI 聊天时会把该角色的 prompt 注入到 system 消息(由
     * [cn.jarryleo.android.buddy.ai.AITranslator] 读取)。
     */
    internal val aiRoles = mutableStateListOf<AiRole>()

    /**
     * 当前正在行内编辑的角色(null 表示列表模式)。
     * 编辑态下,对应行就地展开为编辑表单(Title + Prompt + Save / Cancel)。
     * 新增时 id 分配 UUID 但尚未落库,Save 时由 controller 写入 service 并加入列表。
     */
    internal var editingRole: AiRole? by mutableStateOf(null)

    // ============== 代办 state ==============
    /**
     * 已保存的代办列表(应用级持久化)。
     * 在 tool window 打开时由 [InsertStringsTodosController.loadTodos] 加载,
     * 之后任何修改都立即落库 + 整体重排(active 按 priority + createdAt 倒序,completed 按 completedAt 倒序)。
     */
    internal val todos = mutableStateListOf<TodoItem>()

    /**
     * 当前正在行内编辑的代办(null 表示列表模式)。
     * 编辑态下,对应行就地展开为编辑表单(Title + Content + Priority + Save / Cancel)。
     * 新增时 id 分配 UUID 但尚未落库,Save 时由 controller 写入 service 并加入列表。
     */
    internal var editingTodo: TodoItem? by mutableStateOf(null)

    /**
     * Todo tab 的过滤模式(All / Active / Completed)。仅在 Todo tab 显示时生效,
     * 切到其它顶级 tab 不重置,用户从 Todo 切走再切回时保留上次的过滤。
     */
    internal var todoFilter by mutableStateOf(TodoFilter.ALL)

    // ============== Chat state ==============
    override val chatMessages = mutableStateListOf<ChatMessage>()
    override var chatInput by mutableStateOf("")
    override var chatSending by mutableStateOf(false)

    // 加载工具文档的连续次数(防止 AI 反复加载文档)
    override var toolDocLoadCount: Int = 0

    // 当前轮待响应的 ask_user 工具调用 ID
    override var pendingAskUserToolCallId: String? = null

    // ask_user 连续调用次数(防死循环)
    override var askUserCallCount: Int = 0

    // 用户点击「停止」时置为 true,tool loop 看到就退出
    @Volatile
    override var stopRequested: Boolean = false

    // 编辑器选区是否已被替换(主面板无编辑器上下文,始终为 false;由 controller 在替换成功后写入)
    @Volatile
    override var editorReplacementTriggered: Boolean = false

    // AI 上下文弹窗
    override var showContextPopup by mutableStateOf(false)
    override var chatContextText by mutableStateOf("")

    // 主面板聊天视图无编辑器上下文,本字段始终为 null;extract/askai 弹框的 ChatHolder 会覆写。
    override var editorSelection: EditorSelectionContext? = null

    // 当前 chat 入口标识 —— 主面板固定 "mainPanel",见 ChatStateHolder.chatEntry 注释。
    override val chatEntry: String = "mainPanel"

    // 主面板聊天视图无引用内容(无编辑器上下文),始终为 null。
    override var quoteContent: String? = null

    // 待发送的图片附件(2026.x 多模态):用户在聊天输入区粘贴/选择/拖拽进来的图,
    // 发送前显示为缩略图横排,可单张删除;发送时随本条 user 消息一起发出,然后清空。
    override val pendingImages = mutableStateListOf<ChatAttachment>()

    /**
     * 「重复 key 插入」二次确认时持有的状态。
     * 拆出去会让 ChatDriver 反向依赖过多,保留在 UI 上更直接。
     */
    override var pendingSheetsInsert: PendingSheetsInsert? = null

    // ============== 协作类(延迟初始化,见 [createToolWindowContent]) ==============
    internal lateinit var actionsController: InsertStringsActionsController
        private set
    internal lateinit var settingsController: InsertStringsSettingsController
        private set
    internal lateinit var phrasesController: InsertStringsPhrasesController
        private set
    internal lateinit var rolesController: InsertStringsRolesController
        private set
    internal lateinit var todosController: InsertStringsTodosController
        private set
    internal lateinit var stringsOpsController: InsertStringsStringsOpsController
        private set
    internal lateinit var sheetsOpsController: InsertStringsSheetsOpsController
        private set
    internal lateinit var fileOpsController: InsertStringsFileOpsController
        private set
    internal lateinit var chatContextBuilder: InsertStringsChatContextBuilder
        private set
    internal lateinit var chatDriver: InsertStringsChatDriver
        private set

    private val rootPanel = ComposePanel().apply {
        setContent {
            MaterialTheme {
                InsertStringsContent(
                    stringName = stringName,
                    rows = rows,
                    keys = keyEntries.map { it.key },
                    selectedKeyIndex = selectedKeyIndex,
                    onStringNameChange = { stringName = it },
                    onTextChange = actionsController::updateRowText,
                    onClear = { row -> actionsController.updateRowText(row, "") },
                    onAi = actionsController::translateRow,
                    onCopy = actionsController::copy,
                    onPaste = actionsController::paste,
                    onInsert = actionsController::insert,
                    onSelectKey = actionsController::selectKey,
                    toastMessage = toastMessage,
                    mainTab = mainTab,
                    onMainTabChange = { mainTab = it },
                    settingsTab = settingsTab,
                    onSettingsTabChange = { settingsTab = it },
                    aiProviders = aiProviders,
                    currentAiProviderId = currentAiProviderId,
                    editingAiProvider = editingAiProvider,
                    editingIsNew = editingIsNew,
                    modelOptions = modelOptions,
                    modelFetchStatus = modelFetchStatus,
                    chatMessages = chatMessages,
                    chatInput = chatInput,
                    chatSending = chatSending,
                    onAddAiProvider = settingsController::beginAddProvider,
                    onEditAiProvider = settingsController::beginEditProvider,
                    onDeleteAiProvider = settingsController::deleteProvider,
                    onUseAiProvider = settingsController::useProvider,
                    onAiProviderNameChange = { name -> editingAiProvider = editingAiProvider?.copy(name = name) },
                    onAiProviderUrlChange = { url -> editingAiProvider = editingAiProvider?.copy(url = url) },
                    onAiProviderApiKeyChange = { key -> editingAiProvider = editingAiProvider?.copy(apiKey = key) },
                    onAiProviderProtocolChange = { protocol ->
                        editingAiProvider = editingAiProvider?.copy(protocol = protocol.name)
                    },
                    onAiProviderModelChange = { model -> editingAiProvider = editingAiProvider?.copy(model = model) },
                    onFetchModels = settingsController::fetchModels,
                    onSaveAiProvider = settingsController::saveEditingProvider,
                    onCancelAiProviderEdit = settingsController::cancelEditingProvider,
                    sheetsDefaultSpreadsheetId = sheetsDefaultSpreadsheetId,
                    sheetsDefaultSheetName = sheetsDefaultSheetName,
                    sheetsConnectionStatus = sheetsConnectionStatus,
                    sheetsAvailableSheetNames = sheetsAvailableSheets.map { it.title },
                    sheetsListStatus = sheetsListStatus,
                    onSheetsDefaultSpreadsheetIdChange = { sheetsDefaultSpreadsheetId = it },
                    onSheetsDefaultSheetNameChange = { sheetsDefaultSheetName = it },
                    onTestSheetsConnection = settingsController::testSheetsConnection,
                    onSaveSheetsSettings = settingsController::saveSheetsSettings,
                    onRefreshSheetsList = settingsController::refreshSheetsList,
                    phrases = quickPhrases,
                    editingPhrase = editingPhrase,
                    onAddPhrase = phrasesController::beginAdd,
                    onEditPhrase = phrasesController::beginEdit,
                    onDeletePhrase = phrasesController::delete,
                    onMovePhrase = phrasesController::move,
                    onSavePhraseEdit = phrasesController::saveEdit,
                    onCancelPhraseEdit = phrasesController::cancelEdit,
                    onResetDefaultPhrases = phrasesController::resetDefaults,
                    roles = aiRoles,
                    editingRole = editingRole,
                    onAddRole = rolesController::beginAdd,
                    onEditRole = rolesController::beginEdit,
                    onDeleteRole = rolesController::delete,
                    onSetRoleEnabled = rolesController::setEnabled,
                    onDraftRoleTitleChange = { title -> editingRole = editingRole?.copy(title = title) },
                    onDraftRolePromptChange = { prompt -> editingRole = editingRole?.copy(prompt = prompt) },
                    onSaveRoleEdit = rolesController::saveEdit,
                    onCancelRoleEdit = rolesController::cancelEdit,
                    // ===== Todo 透传给 InsertStringsContent,主面板 chat / settings tab 不用这些参数 =====
                    todos = todos,
                    todoFilter = todoFilter,
                    activeTodoCount = todos.count { !it.isCompleted },
                    completedTodoCount = todos.count { it.isCompleted },
                    editingTodo = editingTodo,
                    onTodoFilterChange = { todoFilter = it },
                    onAddTodo = todosController::beginAdd,
                    onEditTodo = todosController::beginEdit,
                    onDeleteTodo = todosController::delete,
                    onSetTodoCompleted = todosController::setCompleted,
                    onDraftTodoTitleChange = { title -> editingTodo = editingTodo?.copy(title = title) },
                    onDraftTodoContentChange = { content -> editingTodo = editingTodo?.copy(content = content) },
                    onDraftTodoPriorityChange = { priority -> editingTodo = editingTodo?.copy(priority = priority) },
                    onSaveTodoEdit = todosController::saveEdit,
                    onCancelTodoEdit = todosController::cancelEdit,
                    onSaveTodoReminder = todosController::saveReminder,
                    onShowTodoToast = { message -> showToast(message) },
                    onChatInputChange = { chatInput = it },
                    onSendChat = chatDriver::sendChat,
                    onStopChat = chatDriver::stopChat,
                    onQuickSend = chatDriver::quickSend,
                    onNewChat = chatDriver::newChat,
                    onOptionClick = chatDriver::onChatOptionClick,
                    onOpenContext = chatDriver::openContextPopup,
                    onCloseContext = chatDriver::closeContextPopup,
                    showContextPopup = showContextPopup,
                    chatContextText = chatContextText,
                    // 把当前已选 key 列表传给聊天面板顶部展示。
                    // keyEntries 本身是 mutableStateListOf,这里每次重组都会拿到最新快照,
                    // 用户在 strings.xml 中重新选 key 时,updateUI 会重建 keyEntries,
                    // 派生出的 selectedKeys 自动同步到聊天 UI。
                    selectedKeys = keyEntries.map { it.key },
                    // 主面板聊天视图无引用内容,固定传 null。
                    quoteContent = quoteContent,
                    onQuoteDismiss = { quoteContent = null },
                    // 主面板聊天视图无引用,实际不会触发;保留回调以保证签名一致 + 未来扩展。
                    onCopyQuote = { text ->
                        ClipboardManager.setSysClipboardText(text)
                        showToast("已复制到剪贴板")
                    },
                    // 「Clear」按钮:点击后清空主面板选中的 keyEntries 与 rows,
                    // 同步清掉聊天顶部「已选择翻译(N)」面板与主面板表格。
                    // canClear:无选中内容时按钮置灰,避免误点。
                    onClearSelected = actionsController::clearSelected,
                    canClearSelected = keyEntries.isNotEmpty() || rows.isNotEmpty(),
                    // ===== 多模态图片(2026.x 新增)=====
                    pendingImages = pendingImages,
                    onPickImage = ::onPickImageClicked,
                    onRemoveImage = { id -> pendingImages.removeAll { it.id == id } },
                    onImageDropped = { t -> onImageDroppedOrPasted(t) },
                    onPasteFromClipboard = ::onPasteFromClipboard,
                )
            }
        }
    }

    fun createToolWindowContent(project: Project) {
        this.project = project
        insertStringsManager = InsertStringsManager.getInstance(project)
        val currentFile = FileEditorManager.getInstance(project).selectedEditor?.file
        ContextManager.getInstance(project).updateCurrentModule(currentFile)

        // 1) 装配协作类(状态 binding 必须先于任何调用)
        wireCollaborators()

        // 2) 加载持久化设置
        settingsController.loadAiSettings()
        settingsController.loadSheetsSettings()
        phrasesController.loadPhrases()
        rolesController.loadRoles()
        todosController.loadTodos()

        // 3) 拉取默认表格的工作表列表
        settingsController.refreshSheetsList()

        // 4) 注册 UI ↔ TodoReminderScheduler 回调钩子,
        //    让 scheduler 触发 reminder 后能刷新代办列表(闹钟图标 / 下次时间)。
        //    用 runLater 保证在 EDT 上执行(UI 状态只允许 EDT 写)。
        TodoUiRefresher.setRefresher {
            javax.swing.SwingUtilities.invokeLater {
                todosController.reloadTodos()
            }
        }

        // 4.5) 注册 UI ↔ TodoAiResponder 回调(2026.x 新增):
        //    用户代办提醒触发时,scheduler 通过这里把"提醒 X 已触发"作为系统消息发给 AI,
        //    等待 AI 回复一段简短友好文本(用于 IDE 气泡展示)。
        //    必须在 chatDriver 装配完成之后调用 —— chatDriver 由 wireCollaborators 初始化。
        TodoAiResponder.setResponder { systemMessage, onResponse ->
            SwingUtilities.invokeLater {
                chatDriver.sendSystemMessageAndAwait(systemMessage, onResponse)
            }
        }

        // 5) 注册 UI 回调,让 Manager 在数据更新时回调本类
        insertStringsManager.setUiCallBack(this)
    }

    private fun wireCollaborators() {
        actionsController = InsertStringsActionsController(this)
        settingsController = InsertStringsSettingsController(this)
        phrasesController = InsertStringsPhrasesController(this)
        rolesController = InsertStringsRolesController(this)
        todosController = InsertStringsTodosController(this)
        stringsOpsController = InsertStringsStringsOpsController(this)
        sheetsOpsController = InsertStringsSheetsOpsController(this)
        fileOpsController = InsertStringsFileOpsController(this)
        chatContextBuilder = InsertStringsChatContextBuilder(this)
        chatDriver = InsertStringsChatDriver(
            state = this,
            stringsOps = stringsOpsController,
            sheetsOps = sheetsOpsController,
            fileOps = fileOpsController,
            editorOps = InsertStringsEditorOpsController(this),
            chatContextBuilder = chatContextBuilder,
        )
    }

    fun getRootPanel(): JComponent = rootPanel

    /**
     * 业务层扫描到新 key 时,会通过本回调把数据推给 UI。
     */
    override fun updateUI(entries: List<KeyedStringsInfo>) {
        if (entries.isEmpty()) return

        // 通过 actions controller 复用选 key + 刷新 rows 的逻辑
        actionsController.saveCurrentEdits()
        keyEntries.clear()
        keyEntries.addAll(entries)
        if (selectedKeyIndex >= keyEntries.size) selectedKeyIndex = 0
        actionsController.updateRowsForSelectedKey()
        toolWindow.show()
    }

    // ============== ChatStateHolder 回调桥接 ==============
    override fun showToast(message: String) = actionsController.showToast(message)
    override fun saveCurrentEdits() = actionsController.saveCurrentEdits()
    override fun updateRowsForSelectedKey() = actionsController.updateRowsForSelectedKey()

    // ============== 多模态图片(2026.x 新增)==============
    /**
     * 「📎」按钮回调:弹 IntelliJ 文件选择器,把选中的图片(单/多张)逐个加入 [pendingImages]。
     * 选择器本身在 EDT 线程跑(Compose onClick);后台 [ChatAttachment.loadFromFile] 内部
     * 是同步 IO,可能略卡(读 4K 大图 100ms 量级),符合用户对"选完图要等一下"的预期。
     */
    private fun onPickImageClicked() {
        try {
            val picked = ChatImagePicker.pickImageFiles(project)
            picked.forEach { att ->
                if (pendingImages.none { it.id == att.id }) {
                    pendingImages.add(att)
                }
            }
        } catch (t: Throwable) {
            // 选图抛错(IDE 异常 / I/O 错)不阻塞 chat,toast 一行提示即可。
            showToast("选图失败: ${t.message}")
        }
    }

    /**
     * 拖拽文件到聊天输入框时回调:从 Transferable 中识别图片并加入 [pendingImages]。
     * 不支持的拖拽内容(如纯文本拖拽)会被忽略(无 toast,避免误报)。
     */
    private fun onImageDroppedOrPasted(transferable: Transferable) {
        when (val r = ChatImagePicker.addFromTransferable(transferable)) {
            is ChatAttachmentLoadResult.Ok -> {
                if (pendingImages.none { it.id == r.attachment.id }) {
                    pendingImages.add(r.attachment)
                }
            }

            is ChatAttachmentLoadResult.Error -> showToast(r.message)
            ChatAttachmentLoadResult.Unavailable -> { /* 静默忽略 */
            }
        }
    }

    /**
     * Ctrl+V / Cmd+V 拦截回调(2026.x 多模态):
     *  - 剪贴板是图片 → 加入 [pendingImages];
     *  - 剪贴板是文字 → 等同原生粘贴(把文字追加到 chatInput);
     *  - 剪贴板是其它(如文件 URI)→ toast 提示,不打扰用户;
     *  - 空 → toast「剪贴板为空」。
     *
     * 用 [ChatImagePicker.addFromClipboard] 嗅探,失败兜底为「文字粘贴」行为。
     */
    private fun onPasteFromClipboard() {
        when (val r = ChatImagePicker.addFromClipboard()) {
            is ChatAttachmentLoadResult.Ok -> {
                if (pendingImages.none { it.id == r.attachment.id }) {
                    pendingImages.add(r.attachment)
                }
                return
            }

            is ChatAttachmentLoadResult.Error -> {
                showToast(r.message)
                return
            }

            ChatAttachmentLoadResult.Unavailable -> { /* 继续走文字粘贴兜底 */
            }
        }
        // 兜底:从剪贴板读文本并追加到 chatInput(等同原生 Ctrl+V 行为)
        runCatching {
            val text = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getContents(null)
                ?.takeIf { it.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor) }
                ?.let { it.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String }
            if (!text.isNullOrEmpty()) {
                chatInput = chatInput + text
            } else {
                showToast("剪贴板为空")
            }
        }.onFailure { showToast("粘贴失败: ${it.message}") }
    }
}

