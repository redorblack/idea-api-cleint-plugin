package dev.red.apiscope.core

import dev.red.apiscope.core.kv.KeyValueLines
import dev.red.apiscope.core.kv.KvRow
import dev.red.apiscope.core.kv.KvSeparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueLinesTest {

    @Test
    fun `等号分隔的常规行解析出名与值`() {
        val rows = KeyValueLines.parse(
            """
            username = red
            age=18
            """.trimIndent(),
            KvSeparator.EQUALS
        )
        assertEquals(
            listOf(
                KvRow(name = "username", value = "red"),
                KvRow(name = "age", value = "18")
            ),
            rows
        )
    }

    @Test
    fun `冒号分隔的常规行解析出名与值`() {
        val rows = KeyValueLines.parse(
            """
            Accept: application/json
            X-Token:abc
            """.trimIndent(),
            KvSeparator.COLON
        )
        assertEquals(
            listOf(
                KvRow(name = "Accept", value = "application/json"),
                KvRow(name = "X-Token", value = "abc")
            ),
            rows
        )
    }

    @Test
    fun `井号开头的行解析成未勾选的行`() {
        val rows = KeyValueLines.parse("# X-Debug: 1\n#X-Trace:2", KvSeparator.COLON)
        assertEquals(
            listOf(
                KvRow(enabled = false, name = "X-Debug", value = "1"),
                KvRow(enabled = false, name = "X-Trace", value = "2")
            ),
            rows
        )
    }

    @Test
    fun `行内注释存进 comment 字段`() {
        val rows = KeyValueLines.parse("a = 1  # 说明", KvSeparator.EQUALS)
        assertEquals(listOf(KvRow(name = "a", value = "1", comment = "说明")), rows)
    }

    @Test
    fun `未勾选的行也能带行内注释`() {
        val rows = KeyValueLines.parse("# a = 1 # 暂时不发", KvSeparator.EQUALS)
        assertEquals(listOf(KvRow(enabled = false, name = "a", value = "1", comment = "暂时不发")), rows)
    }

    @Test
    fun `色值的井号不被当成注释截断`() {
        val rows = KeyValueLines.parse("color = #fff", KvSeparator.EQUALS)
        assertEquals(listOf(KvRow(name = "color", value = "#fff")), rows)
    }

    @Test
    fun `URL 的 fragment 不被当成注释截断`() {
        val rows = KeyValueLines.parse("url = http://x/y#frag", KvSeparator.EQUALS)
        assertEquals(listOf(KvRow(name = "url", value = "http://x/y#frag")), rows)
    }

    @Test
    fun `冒号分隔时值内部的冒号保留`() {
        val rows = KeyValueLines.parse("Host: example.com:8080", KvSeparator.COLON)
        assertEquals(listOf(KvRow(name = "Host", value = "example.com:8080")), rows)
    }

    @Test
    fun `等号分隔时值内部的等号保留`() {
        val rows = KeyValueLines.parse("token=a=b=c", KvSeparator.EQUALS)
        assertEquals(listOf(KvRow(name = "token", value = "a=b=c")), rows)
    }

    @Test
    fun `没有分隔符的行保留成只有名字的行`() {
        val rows = KeyValueLines.parse("  halfTyped  ", KvSeparator.EQUALS)
        assertEquals(listOf(KvRow(name = "halfTyped", value = "")), rows)
    }

    @Test
    fun `空行被跳过`() {
        val rows = KeyValueLines.parse("\n  \na = 1\n\n", KvSeparator.EQUALS)
        assertEquals(listOf(KvRow(name = "a", value = "1")), rows)
    }

    @Test
    fun `空文本得到空列表`() {
        assertTrue(KeyValueLines.parse("   \n\n", KvSeparator.COLON).isEmpty())
    }

    @Test
    fun `回车换行不残留在值里`() {
        val rows = KeyValueLines.parse("Accept: json\r\nHost: x\r\n", KvSeparator.COLON)
        assertEquals(
            listOf(
                KvRow(name = "Accept", value = "json"),
                KvRow(name = "Host", value = "x")
            ),
            rows
        )
    }

    @Test
    fun `render 在值为空时不留尾随空格`() {
        val rows = listOf(KvRow(name = "a", value = ""))
        assertEquals("a =", KeyValueLines.render(rows, KvSeparator.EQUALS))
        assertEquals("a:", KeyValueLines.render(rows, KvSeparator.COLON))
    }

    @Test
    fun `render 输出停用标记与行内注释`() {
        val rows = listOf(KvRow(enabled = false, name = "X-Debug", value = "1", comment = "排查用"))
        assertEquals("# X-Debug = 1  # 排查用", KeyValueLines.render(rows, KvSeparator.EQUALS))
        assertEquals("# X-Debug: 1  # 排查用", KeyValueLines.render(rows, KvSeparator.COLON))
    }

    @Test
    fun `toMap 跳过未勾选的行`() {
        val map = KeyValueLines.toMap(
            listOf(
                KvRow(name = "Accept", value = "json"),
                KvRow(enabled = false, name = "X-Debug", value = "1")
            )
        )
        assertEquals(mapOf("Accept" to "json"), map)
        assertNull(map["X-Debug"])
    }

    @Test
    fun `toMap 跳过名字空白的行`() {
        val map = KeyValueLines.toMap(
            listOf(
                KvRow(name = "   ", value = "orphan"),
                KvRow(name = "a", value = "1")
            )
        )
        assertEquals(mapOf("a" to "1"), map)
    }

    @Test
    fun `toMap 收下空值的行`() {
        val map = KeyValueLines.toMap(listOf(KvRow(name = "X-Debug", value = "")))
        assertEquals(mapOf("X-Debug" to ""), map)
    }

    @Test
    fun `toMap 同名时后者覆盖前者`() {
        val map = KeyValueLines.toMap(
            listOf(
                KvRow(name = "page", value = "1"),
                KvRow(name = "size", value = "20"),
                KvRow(name = "page", value = "2")
            )
        )
        assertEquals(mapOf("page" to "2", "size" to "20"), map)
        // 覆盖只改值不挪位置，插入顺序仍是用户从上往下写的顺序
        assertEquals(listOf("page", "size"), map.keys.toList())
    }

    @Test
    fun `等号分隔往返解析得到完全相同的行`() {
        val rows = roundTripRows()
        assertEquals(rows, KeyValueLines.parse(KeyValueLines.render(rows, KvSeparator.EQUALS), KvSeparator.EQUALS))
    }

    @Test
    fun `冒号分隔往返解析得到完全相同的行`() {
        val rows = roundTripRows()
        assertEquals(rows, KeyValueLines.parse(KeyValueLines.render(rows, KvSeparator.COLON), KvSeparator.COLON))
    }

    /** 同时含停用行、行内注释、空 value，以及值里带两种分隔符的行 */
    private fun roundTripRows(): List<KvRow> = listOf(
        KvRow(name = "Accept", value = "application/json"),
        KvRow(enabled = false, name = "X-Debug", value = "1", comment = "临时排查用"),
        KvRow(name = "X-Trace", value = ""),
        KvRow(enabled = false, name = "X-Empty", value = "", comment = "空值加注释"),
        KvRow(name = "Authorization", value = "Bearer a:b=c"),
        KvRow(name = "color", value = "#fff")
    )
}
