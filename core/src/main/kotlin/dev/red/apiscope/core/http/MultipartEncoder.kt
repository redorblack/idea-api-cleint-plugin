package dev.red.apiscope.core.http

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * multipart/form-data 编码器
 *
 * 关键坑点：文件内容必须按字节拼接，不能拼进 String 再转回字节 ——
 * 二进制文件（图片/zip 等）一旦经过 String（尤其是非 ISO-8859-1 的 charset），
 * 字节会被字符集解码/编码这一趟来回改写，文件直接损坏。所以全程用
 * ByteArrayOutputStream 写字节，文本 part 也只在写入前那一刻转成 UTF-8 字节。
 *
 * @author Red
 * @since 2026-08-14
 */
object MultipartEncoder {

    class Encoded(val contentType: String, val bytes: ByteArray)

    /** @throws java.io.IOException 文件不存在/不可读 */
    fun encode(parts: List<MultipartPart>): Encoded {
        val boundary = "----ApiScope" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()

        parts.forEach { part ->
            out.writeUtf8("--$boundary\r\n")
            when (part) {
                is MultipartPart.Field -> {
                    out.writeUtf8("Content-Disposition: form-data; name=\"${escape(part.name)}\"\r\n\r\n")
                    out.write(part.value.toByteArray(StandardCharsets.UTF_8))
                    out.writeUtf8("\r\n")
                }
                is MultipartPart.FileRef -> {
                    val path: Path = Path.of(part.path)
                    // 文件不存在/不可读时 readAllBytes 直接抛 IOException/NoSuchFileException，
                    // 不吞、不兜底成空字节 —— 调用方 (HttpExecutor.execute) 会接住转成可读的失败响应
                    val bytes = Files.readAllBytes(path)
                    val fileName = path.fileName?.toString() ?: part.name
                    val contentType = runCatching { Files.probeContentType(path) }.getOrNull()
                        ?: "application/octet-stream"

                    out.writeUtf8(
                        "Content-Disposition: form-data; name=\"${escape(part.name)}\"; " +
                            "filename=\"${escape(fileName)}\"\r\n"
                    )
                    out.writeUtf8("Content-Type: $contentType\r\n\r\n")
                    out.write(bytes)
                    out.writeUtf8("\r\n")
                }
            }
        }
        out.writeUtf8("--$boundary--\r\n")

        return Encoded(contentType = "multipart/form-data; boundary=$boundary", bytes = out.toByteArray())
    }

    /** 头部字段值里的 `"` 和换行会打断 Content-Disposition 语法甚至注入新头，一律剔除 */
    private fun escape(value: String): String = value.replace("\"", "").replace("\r", "").replace("\n", "")

    // 段头本身用 UTF-8 写（文件名可能含中文），不用 US_ASCII 避免非 ASCII 字符被替换成 '?'
    private fun ByteArrayOutputStream.writeUtf8(s: String) = write(s.toByteArray(StandardCharsets.UTF_8))
}
