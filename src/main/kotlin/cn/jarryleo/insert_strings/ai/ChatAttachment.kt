package cn.jarryleo.insert_strings.ai

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 用户发送给 AI 的图片附件(多模态聊天功能,2026.x 新增)。
 *
 * 设计:
 * - 一张图 = 一个 [ChatAttachment],存 base64 数据 + mimeType,序列化时按 OpenAI / Anthropic
 *   多模态协议拼到 message.content(从纯字符串变成 content part 数组)。
 * - 在 chat tab 上 UI 端会用 [thumbnail] 渲染 80~96dp 的缩略图(用户可读);
 *   真正发给 AI 的是 [data] 里的原图或降采样后的图(见 [loadFromBytes])。
 * - id 用于缩略图右上角 × 删除按钮定位,无需 UUID,本地 `img-${nanoTime}` 即可。
 * - 不参与 AI 协议序列化(`toOpenAiMessage` / `toAnthropicMessage` 不读 id / fileName / thumbnail)。
 *
 * 字段类型选 `List<ChatAttachment>` 而非 `Map<String, ChatAttachment>`:
 * AI 在「请看第 1 / 2 张图」这类语义里需要**稳定顺序**;按 list 顺序发,AI 也按 list 顺序理解即可。
 */
data class ChatAttachment(
    val id: String,
    val mimeType: String,
    /**
     * 原始(或降采样后)的图片字节经 base64 编码的字符串。
     * OpenAI / Anthropic 都要求把图片以 `data:<mime>;base64,<data>` 形式内联到请求里。
     */
    val data: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    /**
     * 缩略图(已缩放 80~96dp)的 BufferedImage 引用,仅供 UI 显示用。
     * 设为 nullable 是为了序列化(虽然本字段 transient-ish)在某些场景能 fail-safe
     * (例如未来如果要让消息历史持久化);同时简化 [copy]。
     */
    val thumbnail: BufferedImage? = null,
) {
    /**
     * 解码 base64 → 原始字节,UI / 网络层共用。
     */
    fun decodeBytes(): ByteArray = Base64.getDecoder().decode(data)

    companion object {
        /**
         * 支持作为附件的 mime 类型白名单(其它类型拒绝,避免误把 PDF / 视频也吞进去)。
         * 同时作为 [loadFromFile] / [loadFromBytes] / [loadFromClipboard] 的判断依据。
         */
        val SUPPORTED_MIME_TYPES: Set<String> = setOf(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "image/bmp",
        )

        /**
         * 文件扩展名 → mime 映射。
         */
        private val EXT_TO_MIME: Map<String, String> = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
        )

        fun mimeFromFileName(name: String): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return EXT_TO_MIME[ext]
        }

        /**
         * 从文件加载图片附件:读取字节 → 解析尺寸 → 必要时降采样 → 编码 base64 → 生成缩略图。
         *
         * 大图防御:
         *  - [maxEdge] 限制最长边(默认 2048),超过则等比缩放再编码。OpenAI / Anthropic 对
         *    超大图(例如 8000x6000 截图)会自动 resize 但会先按原图计 token;
         *    本地先压一压,显著省 token。
         *  - [maxBytes] 限制 base64 解码后字节数(默认 5MB),超过直接拒绝(返回 null,
         *    由 caller 弹 toast 「图片过大」)。
         *
         * 失败 / 越界一律返回 null(并附 [error] 描述,方便 UI 区分「不是图片」/「图片过大」/「读失败」)。
         */
        fun loadFromFile(file: File, maxEdge: Int = 2048, maxBytes: Long = 5L * 1024 * 1024): ChatAttachmentLoadResult {
            val mime = mimeFromFileName(file.name) ?: return ChatAttachmentLoadResult.Error("不支持的图片格式: ${file.name}")
            if (file.length() > maxBytes) {
                return ChatAttachmentLoadResult.Error("图片过大(${(file.length() / 1024)}KB),超过 ${maxBytes / 1024}KB 上限")
            }
            val bytes = runCatching { file.readBytes() }
                .getOrElse { return ChatAttachmentLoadResult.Error("读取文件失败: ${it.message}") }
            return loadFromBytes(bytes, mime, file.name, maxEdge)
        }

        /**
         * 从字节数组加载附件(粘贴板 / 拖拽 / 内部拷贝场景)。
         */
        fun loadFromBytes(
            bytes: ByteArray,
            mime: String,
            fileName: String = "pasted-image.${extFor(mime)}",
            maxEdge: Int = 2048,
        ): ChatAttachmentLoadResult {
            if (mime !in SUPPORTED_MIME_TYPES) {
                return ChatAttachmentLoadResult.Error("不支持的图片类型: $mime")
            }
            val original = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }
                .getOrElse { return ChatAttachmentLoadResult.Error("解析图片失败: ${it.message}") }
                ?: return ChatAttachmentLoadResult.Error("解析图片失败: 未知图像格式")
            val (payload, w, h) = downscale(original, maxEdge)
            val payloadBytes = encodeBytes(payload, mime)
                ?: return ChatAttachmentLoadResult.Error("编码图片失败")
            val data = Base64.getEncoder().encodeToString(payloadBytes)
            val thumbnail = renderThumbnail(original, 96)
            return ChatAttachmentLoadResult.Ok(
                ChatAttachment(
                    id = "img-${System.nanoTime()}-${(0..9999).random()}",
                    mimeType = mime,
                    data = data,
                    fileName = fileName,
                    width = w,
                    height = h,
                    thumbnail = thumbnail,
                )
            )
        }

        /**
         * 从系统剪贴板读取图片(粘贴场景)。
         * 剪贴板有图片时返回 [ChatAttachmentLoadResult.Ok],否则返回 [ChatAttachmentLoadResult.Unavailable] —— UI 端可以据此
         * 决定是「默默忽略」还是「toast 提示剪贴板无图片」。
         */
        fun loadFromClipboard(transferable: Transferable?): ChatAttachmentLoadResult {
            val t = transferable ?: return ChatAttachmentLoadResult.Unavailable
            if (!t.isDataFlavorSupported(DataFlavor.imageFlavor)) return ChatAttachmentLoadResult.Unavailable
            val image = runCatching { t.getTransferData(DataFlavor.imageFlavor) as? Image }
                .getOrNull() ?: return ChatAttachmentLoadResult.Unavailable
            val buffered = when (image) {
                is BufferedImage -> image
                else -> {
                    val w = image.getWidth(null).coerceAtLeast(1)
                    val h = image.getHeight(null).coerceAtLeast(1)
                    BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
                        b.createGraphics().drawImage(image, 0, 0, null)
                    }
                }
            }
            return loadFromBuffered(buffered, fileName = "pasted-image.${extFor("image/png")}")
        }

        /**
         * 直接从 BufferedImage 构造附件(剪贴板 / 截图工具内部使用)。
         */
        fun loadFromBuffered(image: BufferedImage, fileName: String, maxEdge: Int = 2048): ChatAttachmentLoadResult {
            val (payload, w, h) = downscale(image, maxEdge)
            val bytes = encodeBytes(payload, "image/png")
                ?: return ChatAttachmentLoadResult.Error("编码图片失败")
            val data = Base64.getEncoder().encodeToString(bytes)
            val thumbnail = renderThumbnail(image, 96)
            return ChatAttachmentLoadResult.Ok(
                ChatAttachment(
                    id = "img-${System.nanoTime()}-${(0..9999).random()}",
                    mimeType = "image/png",
                    data = data,
                    fileName = fileName,
                    width = w,
                    height = h,
                    thumbnail = thumbnail,
                )
            )
        }

        /**
         * 读取当前系统剪贴板内容(用于 Ctrl+V 拦截时的剪贴板嗅探)。
         */
        fun readSystemClipboard(): Transferable? = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.getOrNull()

        private fun downscale(image: BufferedImage, maxEdge: Int): Triple<BufferedImage, Int, Int> {
            val w = image.width
            val h = image.height
            val longest = maxOf(w, h)
            if (longest <= maxEdge) return Triple(image, w, h)
            val scale = maxEdge.toDouble() / longest
            val nw = (w * scale).toInt().coerceAtLeast(1)
            val nh = (h * scale).toInt().coerceAtLeast(1)
            val resized = BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB)
            val g = resized.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(image, 0, 0, nw, nh, null)
            g.dispose()
            return Triple(resized, nw, nh)
        }

        private fun encodeBytes(image: BufferedImage, mime: String): ByteArray? {
            val format = when (mime) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/bmp" -> "bmp"
                else -> return null
            }
            return runCatching {
                ByteArrayOutputStream().use { baos ->
                    ImageIO.write(image, format, baos)
                    baos.toByteArray()
                }
            }.getOrNull()
        }

        private fun renderThumbnail(image: BufferedImage, maxEdge: Int): BufferedImage {
            val (scaled, _, _) = downscale(image, maxEdge)
            return scaled
        }

        private fun extFor(mime: String): String = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "png"
        }

        /**
         * 加载结果占位(2026.x):见文件顶层 [ChatAttachmentLoadResult]。
         * 这里保留 `ChatAttachmentLoadResult` 名字以维持现有方法签名,内部就是顶层 sealed class 的别名。
         */
    }
}

/**
 * [ChatAttachment] 加载结果(2026.x 新增)。
 *
 * 抽到顶层而不是 [ChatAttachment] 的 companion object 内,是因为跨包引用
 * `ChatAttachment.ChatAttachmentLoadResult` 在某些 Kotlin 版本下会被编译器误报
 * "Unresolved reference"(本仓库的 Kotlin 2.2.0 + Gradle 配置下复现),抽到顶层更稳。
 *
 * 命名:不叫 `Result` 是为了避免与 [kotlin.Result] 在解析时混淆,callers 用
 * `ChatAttachmentLoadResult.Ok` 显式书写。
 */
sealed class ChatAttachmentLoadResult {
    data class Ok(val attachment: ChatAttachment) : ChatAttachmentLoadResult()
    data class Error(val message: String) : ChatAttachmentLoadResult()
    object Unavailable : ChatAttachmentLoadResult()
}


