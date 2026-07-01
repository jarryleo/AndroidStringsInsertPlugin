package cn.jarryleo.android.buddy.ui

import cn.jarryleo.android.buddy.ai.ChatAttachment
import cn.jarryleo.android.buddy.ai.ChatAttachmentLoadResult
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * 「聊天发送图片」相关的工具函数集合(2026.x 多模态功能)。
 *
 * - [pickImageFiles] 弹出 IntelliJ 文件选择器,只允许选择白名单里的图片格式,
 *   由 UI 端 [AiChatContent] 的「📎」按钮触发。
 * - [addFromClipboard] 从系统剪贴板读取图片(用于支持「Ctrl+V 在聊天输入框粘贴图片」)。
 * - [addFromTransferable] 接受任意 [Transferable],优先尝试读取 image,否则读取 file list
 *   —— 这是拖拽文件的入口(用 DragHandler 收到 transferable 后调一次)。
 */
internal object ChatImagePicker {

    /**
     * 弹 IntelliJ 文件选择器,让用户选一张或多张图片。
     *
     * 注意:必须在 EDT 线程上调用(由 Compose UI 的 onClick 触发即可)。
     *
     * IntelliJ 2025.1 [FileChooserDescriptor] 的 6-参数构造签名:
     *   `FileChooserDescriptor(chooseFiles, chooseFolders, chooseJars, chooseJarsAsFiles, chooseMultiple, chooseSingleFile)`
     * — 这里多选 = true,单选 = false,允许选多个文件;
     * 标题 / 描述 / 文件过滤 通过 [FileChooserDescriptor.withTitle] / [withDescription] / [withFileFilter]
     * 这类 wither 方法链式设置(返回新实例),或在 [FileChooserDescriptor] 上直接调 setter。
     *
     * @param project IDE 项目,用于文件选择器的锚点窗口
     * @return 用户选择的图片附件(无文件/被取消时为空列表)
     */
    fun pickImageFiles(project: Project): List<ChatAttachment> {
        val descriptor = FileChooserDescriptor(
            /* chooseFiles = */ true,
            /* chooseFolders = */ false,
            /* chooseJars = */ false,
            /* chooseJarsAsFiles = */ false,
            /* chooseMultiple = */ true,
            /* chooseSingleFile = */ false,
        )
            .withTitle("选择要发送给 AI 的图片")
            .withDescription("支持 PNG / JPG / GIF / WebP / BMP(单张 ≤ 5MB,最长边 ≤ 2048px)")
            .withHideIgnored(true)
            .withFileFilter(Condition { vf ->
                ChatAttachment.mimeFromFileName(vf.name) != null
            })
        val files: Array<VirtualFile> = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return emptyList()
        return files.mapNotNull { vf ->
            // VirtualFile 转 java.io.File 走 LocalFileSystem IO,失败时(非本地文件)跳过。
            val ioFile = runCatching {
                val path = vf.path
                if (path.isBlank()) null else File(path)
            }.getOrNull() ?: return@mapNotNull null
            val r: ChatAttachmentLoadResult = ChatAttachment.loadFromFile(ioFile)
            when (r) {
                is ChatAttachmentLoadResult.Ok -> r.attachment
                is ChatAttachmentLoadResult.Error -> null
                ChatAttachmentLoadResult.Unavailable -> null
            }
        }
    }

    /**
     * 从系统剪贴板读取图片(支持图片型剪贴板,例如截图工具粘贴的 PNG)。
     *
     * @return 成功:Ok(attachment);剪贴板无图片:Unavailable;读失败:Error
     */
    fun addFromClipboard(): ChatAttachmentLoadResult {
        val t: Transferable? = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.getOrNull()
        return ChatAttachment.loadFromClipboard(t)
    }

    /**
     * 从拖拽产生的 [Transferable] 中读取图片或文件。
     * 优先尝试 image,否则尝试 file list(常见拖拽源)。
     */
    fun addFromTransferable(transferable: Transferable?): ChatAttachmentLoadResult {
        if (transferable == null) return ChatAttachmentLoadResult.Unavailable
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            return ChatAttachment.loadFromClipboard(transferable)
        }
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files: List<File> = runCatching {
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            }.getOrNull().orEmpty()
            if (files.isEmpty()) return ChatAttachmentLoadResult.Unavailable
            val first = files.firstOrNull { f ->
                ChatAttachment.mimeFromFileName(f.name) != null
            } ?: return ChatAttachmentLoadResult.Error("拖拽的文件不是支持的图片格式")
            return ChatAttachment.loadFromFile(first)
        }
        return ChatAttachmentLoadResult.Unavailable
    }
}

