package dev.red.apiscope.core

import dev.red.apiscope.core.vars.Interpolator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterpolatorTest {

    @Test
    fun `多变量且同变量重复出现时全部替换`() {
        val result = Interpolator.apply(
            "{{baseUrl}}/order/{{orderId}}?ref={{orderId}}",
            mapOf("baseUrl" to "http://localhost:8080", "orderId" to "123")
        )
        assertEquals("http://localhost:8080/order/123?ref=123", result)
    }

    @Test
    fun `未定义变量原样保留且 missing 能报出来`() {
        val text = "{{baseUrl}}/order/{{token}}"
        val vars = mapOf("baseUrl" to "http://localhost:8080")
        assertEquals("http://localhost:8080/order/{{token}}", Interpolator.apply(text, vars))
        assertEquals(listOf("token"), Interpolator.missing(text, vars))
    }

    @Test
    fun `变量名两侧空白容错`() {
        val result = Interpolator.apply("{{ baseUrl }}/ping", mapOf("baseUrl" to "http://localhost:8080"))
        assertEquals("http://localhost:8080/ping", result)
    }

    @Test
    fun `变量值里含占位符不会被二次展开`() {
        val result = Interpolator.apply(
            "{{a}}",
            mapOf("a" to "{{b}}", "b" to "real-value")
        )
        assertEquals("{{b}}", result)
    }

    @Test
    fun `空名占位符原样保留`() {
        assertEquals("{{}}", Interpolator.apply("{{}}", emptyMap()))
    }

    @Test
    fun `missing 去重并保持首次出现顺序`() {
        val text = "{{b}}/{{a}}/{{b}}/{{c}}"
        assertEquals(listOf("b", "a", "c"), Interpolator.missing(text, emptyMap()))
    }

    @Test
    fun `没有占位符的文本 missing 返回空 list`() {
        assertTrue(Interpolator.missing("plain text, no vars", mapOf("x" to "1")).isEmpty())
    }

    @Test
    fun `空文本与空 variables 都不抛异常`() {
        assertEquals("", Interpolator.apply("", emptyMap()))
        assertTrue(Interpolator.missing("", emptyMap()).isEmpty())
        assertEquals("{{a}}", Interpolator.apply("{{a}}", emptyMap()))
    }
}
