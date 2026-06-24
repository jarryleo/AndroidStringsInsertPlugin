package cn.jarryleo.insert_strings.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
import cn.jarryleo.insert_strings.InsertStringsManager
import cn.jarryleo.insert_strings.UiCallback
import cn.jarryleo.insert_strings.ai.AiProvider
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.phrases.QuickPhrase
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import javax.swing.JComponent
import javax.swing.Timer

/**
 * 主面板的状态容器 + 协调器(轻量 facade)。
 *
 * 真实逻辑拆分到以下协作类(都在同包,见 [cn.jarryleo.insert_strings.ui]):
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

    // ============== 弹窗 / 标签页 state ==============
    internal var showSettings by mutableStateOf(false)
    internal var showChat by mutableStateOf(false)
    internal var settingsTab by mutableStateOf(SettingsTab.AI)

    // ============== AI 设置 state(多 provider 模型) ==============
    /**
     * 全部 AI provider 列表。展示在设置面板的「提供商列表」中,持久化在 [cn.jarryleo.insert_strings.ai.AiSettingsService]。
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
    // AI 上下文弹窗
    override var showContextPopup by mutableStateOf(false)
    override var chatContextText by mutableStateOf("")
    // 主面板聊天视图无编辑器上下文,本字段始终为 null;extract/askai 弹框的 ChatHolder 会覆写。
    override var editorSelection: EditorSelectionContext? = null

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
                    showSettings = showSettings,
                    showChat = showChat,
                    settingsTab = settingsTab,
                    aiProviders = aiProviders,
                    currentAiProviderId = currentAiProviderId,
                    editingAiProvider = editingAiProvider,
                    editingIsNew = editingIsNew,
                    modelOptions = modelOptions,
                    modelFetchStatus = modelFetchStatus,
                    chatMessages = chatMessages,
                    chatInput = chatInput,
                    chatSending = chatSending,
                    onSettingsTabChange = { settingsTab = it },
                    onOpenSettings = { showSettings = true },
                    onCloseSettings = { showSettings = false },
                    onOpenChat = { showChat = true },
                    onCloseChat = { showChat = false },
                    onAddAiProvider = settingsController::beginAddProvider,
                    onEditAiProvider = settingsController::beginEditProvider,
                    onDeleteAiProvider = settingsController::deleteProvider,
                    onUseAiProvider = settingsController::useProvider,
                    onAiProviderNameChange = { name -> editingAiProvider = editingAiProvider?.copy(name = name) },
                    onAiProviderUrlChange = { url -> editingAiProvider = editingAiProvider?.copy(url = url) },
                    onAiProviderApiKeyChange = { key -> editingAiProvider = editingAiProvider?.copy(apiKey = key) },
                    onAiProviderProtocolChange = { protocol -> editingAiProvider = editingAiProvider?.copy(protocol = protocol.name) },
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

        // 3) 拉取默认表格的工作表列表
        settingsController.refreshSheetsList()

        // 4) 注册 UI 回调,让 Manager 在数据更新时回调本类
        insertStringsManager.setUiCallBack(this)
    }

    private fun wireCollaborators() {
        actionsController = InsertStringsActionsController(this)
        settingsController = InsertStringsSettingsController(this)
        phrasesController = InsertStringsPhrasesController(this)
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
    override fun closeChatView() { showChat = false }
}
