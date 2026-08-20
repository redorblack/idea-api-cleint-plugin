package dev.red.apiscope.core

import dev.red.apiscope.core.json.JsonPrinter
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPrinterTest {

    @Test
    fun `嵌套对象和数组能正确缩进`() {
        val input = "{\"a\":1,\"b\":[1,2,3],\"c\":{\"d\":true}}"
        val expected = """
            {
              "a": 1,
              "b": [
                1,
                2,
                3
              ],
              "c": {
                "d": true
              }
            }
        """.trimIndent()
        assertEquals(expected, JsonPrinter.pretty(input))
    }

    @Test
    fun `字符串内部的特殊字符和转义引号不被破坏`() {
        val input = """{"msg":"a{b,c:d\"e"}"""
        val expected = """
            {
              "msg": "a{b,c:d\"e"
            }
        """.trimIndent()
        assertEquals(expected, JsonPrinter.pretty(input))
    }

    @Test
    fun `非 JSON 输入原样返回`() {
        val html = "<html><body>404</body></html>"
        assertEquals(html, JsonPrinter.pretty(html))

        val plain = "OK"
        assertEquals(plain, JsonPrinter.pretty(plain))
    }

    @Test
    fun `已格式化的 JSON 再次格式化保持不变`() {
        val compact = "{\"a\":[1,{\"b\":2}]}"
        val once = JsonPrinter.pretty(compact)
        val twice = JsonPrinter.pretty(once)
        assertEquals(once, twice)
    }

    @Test
    fun `空对象和空数组内联输出`() {
        val input = "{\"a\":{},\"b\":[]}"
        val expected = """
            {
              "a": {},
              "b": []
            }
        """.trimIndent()
        assertEquals(expected, JsonPrinter.pretty(input))
    }

    @Test
    fun `空白或空字符串原样返回`() {
        assertEquals("", JsonPrinter.pretty(""))

        val blank = "   \n\t  "
        assertEquals(blank, JsonPrinter.pretty(blank))
    }
}
