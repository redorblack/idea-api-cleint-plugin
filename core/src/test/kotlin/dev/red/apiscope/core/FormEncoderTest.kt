package dev.red.apiscope.core

import dev.red.apiscope.core.http.FormEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

class FormEncoderTest {

    @Test
    fun `空格编码成加号`() {
        assertEquals("name=red+gan", FormEncoder.encode(listOf("name" to "red gan")))
    }

    @Test
    fun `中文按 UTF-8 百分号编码`() {
        // "姓名" 的 UTF-8 字节 = E5 A7 93 E5 90 8D
        assertEquals("name=%E5%A7%93%E5%90%8D", FormEncoder.encode(listOf("name" to "姓名")))
    }

    @Test
    fun `与 等号 被编码不会破坏字段分隔`() {
        assertEquals("a%26b=1", FormEncoder.encode(listOf("a&b" to "1")))
        assertEquals("a%3Db=1", FormEncoder.encode(listOf("a=b" to "1")))
    }

    @Test
    fun `多个字段用 与 连接`() {
        assertEquals(
            "a=1&b=2",
            FormEncoder.encode(listOf("a" to "1", "b" to "2"))
        )
    }
}
