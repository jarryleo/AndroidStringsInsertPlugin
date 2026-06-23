# Android Strings Insert

一款面向 **Android Studio** 的国际化（i18n）字符串资源增强插件，专注于 `strings.xml` 的多语言编辑、AI 翻译与 Google Sheets 同步。

> 当前版本：`3.9.0`

---

## 核心功能

### 1. `strings.xml` 可视化批量编辑
- 选中一个或多个 key，IDE 右侧工具窗口会一次性把当前模块下所有语言的 `strings.xml` 内容以表格形式呈现。
- 支持在表格中直接编辑、复制、粘贴、清空单条翻译，并按语言实时回写到对应 `values-xx/` 目录的 `strings.xml`。
- 通过 **Copy / Paste** 可在不同模块、不同工程间搬运多语言翻译。
- **Insert Strings** 把当前表格内容插入（合并/覆盖）到 `strings.xml` 中，并按 `anchor` 节点尽量保持原文件排版。

### 2. AI 翻译 & 多语言补全
- 表格每行支持单条 AI 翻译，模型读取对应 key 的所有现有语言作为上下文，给出更连贯的多语言结果。
- 内置 AI 聊天面板（工具窗口内的 Chat 标签页）支持自然语言指令，例如「把这几个 key 的法语改成更口语化」「把缺失的法语补全」。
- 支持 OpenAI 兼容协议与 Anthropic Messages 协议，可对接国内外多种模型服务（默认 `qwen-plus`）。
- AI 通过 **Function Calling** 操纵 `strings.xml`：可查询、读取、修改、删除、反查 key，并在执行前后与你确认。

### 3. Ask AI 快速弹窗
- 编辑器右键 / `Edit` 菜单中的 **Ask AI**（快捷键 `Shift F3`）。
- 选中代码或文本后弹出小窗，自动以「解释选中内容」作为首条消息发出。
- 弹窗内复用主面板的 Chat 能力（工具调用、上下文、Stop 等），但隐藏表格区，专注于轻量问答。

### 4. Google Sheets 双向同步
- 在 **Settings → Sheets** 中配置默认电子表格 ID 与工作表名，使用 OAuth 授权。
- AI 聊天中可让模型直接在 Google Sheets 中：读行、查行、插入行、追加行、修改行、删除行、调整列、冻结行列、批量改值、批量填色、批量改文字色、跨表查重等。
- 「选中翻译插入表格」/「表格翻译插入文件」等快捷短语可一键完成 `strings.xml` ↔ Google Sheets 的双向回流。
- 复杂的批量修改会合并到一次 `batch_modify` 工具调用，后端自动分组成最少的 Google API 请求，避免触发工具调用次数限制。

### 5. 快捷短语（Quick Phrases）
- 内置 6 条常用指令（翻译检测、翻译修复、表格检测、表格修复、表格插入、文件回写等）。
- 支持新增、编辑、删除、拖拽排序、恢复默认，并持久化到 `insertStringsAiSettings.xml`。
- 在 Chat 输入区一键发送，省去重复输入长指令的麻烦。

### 6. 多模块 / 多项目支持
- 插件会扫描当前工程内所有包含 `strings.xml` 的模块（`ContextManager` 缓存模块与语言目录的映射）。
- AI 工具调用允许通过 `module` 参数精确指定目标模块，避免误改其他模块。

---

## 使用流程

### 步骤 1：安装插件
1. 从 `build/distributions/` 加载本地 ZIP。
2. 重启 IDE，右侧边栏会出现 **InsertStrings** 工具窗口。

### 步骤 2：打开 strings.xml 并触发主面板
1. 在 `app/src/main/res/values/strings.xml`（或任意 `values-xx/strings.xml`）中，将光标置于一个 `<string>` 节点或选中文本。
2. 通过以下任一方式触发：
   - 菜单栏 **Edit → Insert Strings**
   - 编辑器右键 **Insert Strings**
   - 快捷键 `Shift F1`（macOS 可用 `Control Shift F1`）
3. 右侧 **InsertStrings** 工具窗口会自动打开，并按当前模块加载所有语言的字符串。

### 步骤 3：编辑与回写
- **顶部输入框**：作为新 key 的 `name`，留空时表示批量替换当前选中行。
- **表格编辑**：直接修改各语言列的文本；右侧按钮可「清空该行」「AI 翻译该行」。
- **Copy**：把当前选中的多语言条目复制到剪贴板（JSON 格式）。
- **Paste**：从剪贴板恢复多语言条目到当前 `strings.xml`。
- **Insert Strings**：把当前表格内容写回 `strings.xml`，自动处理 `values-xx/` 多语言目录。
- **Toast 提示**：操作完成后顶部会出现成功 / 失败提示。

### 步骤 4：配置 AI（如需 AI 翻译或聊天）
1. 点击工具窗口右上角 **Settings** 按钮。
2. 切换到 **AI** 标签页：
   - **URL**：服务地址（如 `https://api.openai.com` 或自建网关）。
   - **API Key**：调用所需的密钥。
   - **Protocol**：`OpenAI`（兼容 OpenAI Chat Completions）或 `Anthropic`（Anthropic Messages）。
   - **Model**：模型名；可点击右侧「拉取模型」按钮从服务端 `GET /v1/models` 动态获取。
3. 点击 **保存**。配置会持久化到 `insertStringsAiSettings.xml`，下次打开自动加载。

### 步骤 5：使用 AI 聊天
1. 点击工具窗口右上角 **Chat** 按钮。
2. 在输入框中用自然语言描述需求，例如：
   - 「帮我把这几个 key 的繁体中文修正成台湾用语」
   - 「查找所有包含『订单』的 key 并翻译成日语」
   - 「把表格中缺失的法语补全」
3. 可以使用底部 **快捷短语** 一键发送内置指令。
4. AI 会按需调用工具（`insert_strings` / `update_string` / `delete_string` / `query_keys` / `read_string` / `find_keys_by_text` 等），所有写操作会先与你确认再执行。
5. 点击 **Stop** 可中断当前工具循环；**New Chat** 清空上下文重新开始。

### 步骤 6：使用 Ask AI 快速弹窗
1. 在编辑器中选中代码或文本。
2. 右键 **Ask AI** 或快捷键 `Shift F3`。
3. 弹窗自动以「解释选中内容」作为首条消息发送；可继续在弹窗中提问。
4. 弹窗支持拖拽、缩放（右下角把手），关闭按钮在标题栏右侧。

### 步骤 7：配置 Google Sheets 同步（如需表格协作）
1. 进入 **Settings → Sheets**：
   - **Default Spreadsheet ID**：默认电子表格 ID（URL 中 `/d/` 后那段）。
   - **Default Sheet Name**：默认工作表名（默认 `Sheet1`）。
2. 点击 **Test Connection** 触发 OAuth 授权：首次使用会跳转浏览器登录 Google 并授权 `META-INF/client_secret.json` 中声明的 scope。
3. 点击 **Refresh Sheet List** 拉取当前电子表格下的所有工作表。
4. 在 AI 聊天中让模型「把选中的翻译插入表格」「按表格修正 strings.xml」即可完成双向同步。

### 步骤 8：管理快捷短语
- 在工具窗口底部 **Quick Phrases** 区域：
  - **+** 新建短语：填入标题、文本、颜色。
  - 点击短语进入编辑态（可改标题、文本、颜色）。
  - 拖拽左侧手柄调整顺序。
  - **Reset Defaults** 恢复出厂内置 6 条。
- 内置短语不可删除，自建短语可删除。

---

## 快捷键

| 操作 | 快捷键 |
| --- | --- |
| Insert Strings | `Shift F1` / `Control Shift F1`（备用） |
| Ask AI | `Shift F3` |

> 在 macOS 上按系统习惯替换 `Control` 即可。

---

## 常见问题

- **Q：为什么 InsertStrings 按钮是灰的？**  
  A：仅在光标位于 `strings.xml` 中时才启用；切到 `values-xx/strings.xml` 之外的任意文件都会自动禁用。

- **Q：AI 配置保存后还是提示未配置？**  
  A：检查 URL 是否带协议头（`https://`），API Key 是否填写；保存成功后重启工具窗口一次以重新加载状态。

- **Q：Google Sheets 连接失败？**  
  A：确认 Spreadsheet ID 正确，且授权账号对该表格有读 / 写权限；首次授权后请回到 IDE 点击 **Test Connection** 完成授权回调。

- **Q：能不能保留原 `strings.xml` 的注释与排版？**  
  A：插件使用 `StringsXmlFormatter` 重新格式化输出，会尽量保留原始 `anchor` 节点附近的排版；建议先用 Git 跟踪变更。

---

## 反馈

- 作者：Jarry Leo
- 邮箱：yjtx256@qq.com
- 主页：<https://www.github.com/jarryleo>
