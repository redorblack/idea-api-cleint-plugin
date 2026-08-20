package dev.red.apiscope.core

import dev.red.apiscope.core.endpoint.UrlEscaper
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlEscaperTest {

    @Test
    fun `query 值里的空格编码成 %20`() {
        assertEquals(
            "http://h:8080/s?kw=a%20b",
            UrlEscaper.escape("http://h:8080/s?kw=a b")
        )
    }

    @Test
    fun `中文按 UTF-8 编码`() {
        assertEquals(
            "http://h/s?kw=%E5%BC%A0%E4%B8%89",
            UrlEscaper.escape("http://h/s?kw=张三")
        )
    }

    @Test
    fun `已经编码过的不再二次编码`() {
        val already = "http://h/s?kw=a%20b&x=%E5%BC%A0"
        assertEquals(already, UrlEscaper.escape(already))
    }

    @Test
    fun `单独的百分号编码成 %25`() {
        assertEquals("http://h/s?pct=50%25", UrlEscaper.escape("http://h/s?pct=50%"))
    }

    @Test
    fun `%ZZ 不是合法编码 按普通字符处理`() {
        assertEquals("http://h/s?v=%25ZZ", UrlEscaper.escape("http://h/s?v=%ZZ"))
    }

    @Test
    fun `分隔符与空值原样保留`() {
        val url = "http://h:8080/a/b?x=1&y=&z=3#frag"
        assertEquals(url, UrlEscaper.escape(url))
    }

    @Test
    fun `路径里的空格也编码`() {
        assertEquals("http://h/a%20b/c", UrlEscaper.escape("http://h/a b/c"))
    }

    @Test
    fun `大括号被编码 —— 变量插值后不该还有大括号`() {
        assertEquals("http://h/s?v=%7Bid%7D", UrlEscaper.escape("http://h/s?v={id}"))
    }

    @Test
    fun `代理对不会被拆坏`() {
        // 𠮷（U+20BB7）是代理对，逐 char 编码会产出两个坏字节
        assertEquals("http://h/s?v=%F0%A0%AE%B7", UrlEscaper.escape("http://h/s?v=𠮷"))
    }

    @Test
    fun `编码后的地址 URI 能解析 —— 这是这个类存在的唯一理由`() {
        listOf(
            "http://h:8080/s?startTime=2026-08-14 00:00:00",
            "http://h:8080/s?kw=张 三",
            "http://h:8080/s?name=iPhone 15&pct=50%"
        ).forEach { raw ->
            // 先钉住前提：不编码时 URI 确实抛异常（这就是界面上那句「URL 非法」的来源）
            assertFailsWith<IllegalArgumentException>("原始地址本该被 URI 拒绝：$raw") { URI.create(raw) }
            URI.create(UrlEscaper.escape(raw))
        }
    }
}
