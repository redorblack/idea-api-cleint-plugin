package dev.red.apiscope.core

import dev.red.apiscope.core.http.Cookies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookiesTest {

    @Test
    fun `解析单条基本 cookie`() {
        val cookies = Cookies.parseSetCookie(listOf("JSESSIONID=abc123"))
        assertEquals(1, cookies.size)
        assertEquals("JSESSIONID", cookies[0].name)
        assertEquals("abc123", cookies[0].value)
    }

    @Test
    fun `一个响应里的多条 Set-Cookie 都要解析`() {
        val cookies = Cookies.parseSetCookie(listOf("a=1; Path=/", "b=2; HttpOnly", "c=3"))
        assertEquals(listOf("a", "b", "c"), cookies.map { it.name })
        assertEquals(listOf("1", "2", "3"), cookies.map { it.value })
    }

    @Test
    fun `解析全套 attribute 并忽略 Max-Age 与 Secure`() {
        val cookies = Cookies.parseSetCookie(
            listOf("sid=xyz; Domain=.example.com; Path=/api; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Max-Age=3600; HttpOnly; Secure")
        )
        val cookie = cookies.single()
        assertEquals("sid", cookie.name)
        assertEquals("xyz", cookie.value)
        // Domain 的前导点要去掉
        assertEquals("example.com", cookie.domain)
        assertEquals("/api", cookie.path)
        // Expires 原样保存，不解析成日期
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", cookie.expires)
        assertTrue(cookie.httpOnly)
    }

    @Test
    fun `没有 attribute 时其余字段为默认值`() {
        val cookie = Cookies.parseSetCookie(listOf("token=t1")).single()
        assertEquals("", cookie.domain)
        assertEquals("", cookie.path)
        assertEquals("", cookie.expires)
        assertFalse(cookie.httpOnly)
    }

    @Test
    fun `value 内部的等号要保留`() {
        val cookie = Cookies.parseSetCookie(listOf("a=b=c; Path=/")).single()
        assertEquals("a", cookie.name)
        assertEquals("b=c", cookie.value)
    }

    @Test
    fun `value 允许为空`() {
        val cookie = Cookies.parseSetCookie(listOf("a=; Path=/")).single()
        assertEquals("a", cookie.name)
        assertEquals("", cookie.value)
    }

    @Test
    fun `attribute 名大小写不敏感`() {
        val cookie = Cookies.parseSetCookie(listOf("a=1; domain=.example.com; PATH=/; HTTPONLY")).single()
        assertEquals("example.com", cookie.domain)
        assertEquals("/", cookie.path)
        assertTrue(cookie.httpOnly)
    }

    @Test
    fun `第一段没有等号或名字为空的整条丢弃`() {
        val cookies = Cookies.parseSetCookie(listOf("garbage; Path=/", "=1; Path=/", "ok=1"))
        assertEquals(listOf("ok"), cookies.map { it.name })
    }

    @Test
    fun `header 拼接并跳过空名`() {
        assertEquals("a=b; c=d", Cookies.header(listOf("a" to "b", "c" to "d")))
        assertEquals("a=b", Cookies.header(listOf("a" to "b", "  " to "x", "" to "y")))
        assertEquals("", Cookies.header(emptyList()))
    }
}
