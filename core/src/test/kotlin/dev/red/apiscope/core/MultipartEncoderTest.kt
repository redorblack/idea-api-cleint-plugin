package dev.red.apiscope.core

import dev.red.apiscope.core.http.MultipartEncoder
import dev.red.apiscope.core.http.MultipartPart
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultipartEncoderTest {

    @Test
    fun `文本字段与文件字段都正确编码`() {
        val tempFile = createTempFile(prefix = "apiscope-test-", suffix = ".bin")
        try {
            // 特意包含一个 >127 的字节（0xFF），如果编码过程把文件内容当 String 拼过一趟，
            // 这个字节在 UTF-8 往返转换中会被替换成 U+FFFD 从而丢失，断言会失败
            val originalBytes = byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0x7F, 0x00, 0x10)
            tempFile.writeBytes(originalBytes)

            val encoded = MultipartEncoder.encode(
                listOf(
                    MultipartPart.Field(name = "username", value = "red"),
                    MultipartPart.FileRef(name = "avatar", path = tempFile.toString())
                )
            )

            // boundary 从 contentType 里提取，body 里必须出现同一个 boundary
            val boundary = Regex("boundary=(\\S+)").find(encoded.contentType)!!.groupValues[1]
            val text = String(encoded.bytes, StandardCharsets.ISO_8859_1)

            assertContains(text, "--$boundary")
            assertContains(text, "--$boundary--")
            assertContains(text, "Content-Disposition: form-data; name=\"username\"")
            assertContains(text, "Content-Disposition: form-data; name=\"avatar\"; filename=\"${tempFile.name}\"")

            // 原始字节必须按字节序列原样出现在结果里（用 ISO_8859_1 往返不改变字节值，可安全比对）
            val fileBytesAsIso = String(originalBytes, StandardCharsets.ISO_8859_1)
            assertContains(text, fileBytesAsIso)
            assertTrue(text.trim().endsWith("--$boundary--"))
        } finally {
            tempFile.deleteIfExists()
        }
    }

    @Test
    fun `文件不存在时抛出 IOException`() {
        assertFailsWith<IOException> {
            MultipartEncoder.encode(listOf(MultipartPart.FileRef(name = "avatar", path = "/no/such/file/apiscope-not-exist.bin")))
        }
    }
}
