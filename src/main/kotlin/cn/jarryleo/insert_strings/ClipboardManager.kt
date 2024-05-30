package cn.jarryleo.insert_strings

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * 剪贴板管理类
 */

object ClipboardManager {

    //获取剪贴板内容
    fun getSysClipboardText(): String {
        var ret = ""
        val sysClip: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        // 获取剪切板中的内容
        val clipTf = sysClip.getContents(null)

        if (clipTf != null) {
            // 检查内容是否是文本类型
            if (clipTf.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    ret = clipTf
                        .getTransferData(DataFlavor.stringFlavor) as String
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return ret
    }

    //写入剪贴板
    fun setSysClipboardText(writeMe: String?) {
        val clip: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val tText: Transferable = StringSelection(writeMe) //覆盖系统剪切板
        clip.setContents(tText, null)
    }
}