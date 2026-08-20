package dev.red.apiscope.core

import dev.red.apiscope.core.http.CookieJar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieJarTest {

    @Test
    fun `存入后按 host 精确取回`() {
        val jar = CookieJar()
        jar.store("api.example.com", listOf("JSESSIONID=abc; Path=/", "lang=zh"))
        assertEquals("JSESSIONID=abc; lang=zh", jar.headerFor("api.example.com"))
    }

    @Test
    fun `Domain 决定归属 key 且 host 大小写不敏感`() {
        val jar = CookieJar()
        // 带 Domain 时归到 example.com，而不是请求用的 login.example.com
        jar.store("login.example.com", listOf("sid=1; Domain=.Example.COM"))
        assertEquals(setOf("example.com"), jar.snapshot().keys)
        assertEquals("sid=1", jar.headerFor("API.Example.com"))
    }

    @Test
    fun `父域 cookie 命中子域`() {
        val jar = CookieJar()
        jar.store("example.com", listOf("sid=1"))
        assertEquals("sid=1", jar.headerFor("api.example.com"))
    }

    @Test
    fun `子域 cookie 不发给父域`() {
        val jar = CookieJar()
        jar.store("api.example.com", listOf("sid=1"))
        assertNull(jar.headerFor("example.com"))
        // 也不能被同级兄弟域或仅后缀相近的域命中
        assertNull(jar.headerFor("web.example.com"))
        assertNull(jar.headerFor("notapi.example.com"))
    }

    @Test
    fun `同名 cookie 后者覆盖前者`() {
        val jar = CookieJar()
        jar.store("example.com", listOf("sid=old", "lang=zh"))
        jar.store("example.com", listOf("sid=new"))
        assertEquals("sid=new; lang=zh", jar.headerFor("example.com"))
    }

    @Test
    fun `多个 key 命中时更精确的优先`() {
        val jar = CookieJar()
        jar.store("example.com", listOf("t=parent", "shared=p"))
        jar.store("api.example.com", listOf("t=child"))
        val header = jar.headerFor("api.example.com")!!
        assertTrue(header.contains("t=child"), header)
        assertTrue(header.contains("shared=p"), header)
        assertFalse(header.contains("t=parent"), header)
    }

    @Test
    fun `无命中返回 null`() {
        val jar = CookieJar()
        jar.store("example.com", listOf("sid=1"))
        assertNull(jar.headerFor("other.com"))
        assertNull(CookieJar().headerFor("example.com"))
    }

    @Test
    fun `clear 清空全部`() {
        val jar = CookieJar()
        jar.store("example.com", listOf("sid=1"))
        jar.clear()
        assertTrue(jar.snapshot().isEmpty())
        assertNull(jar.headerFor("example.com"))
    }

    @Test
    fun `snapshot 与 initial 往返`() {
        val jar = CookieJar()
        // value 里的 base64 padding 要能原样往返
        jar.store("example.com", listOf("sid=abc=="))
        jar.store("api.example.com", listOf("lang=zh"))

        val snapshot = jar.snapshot()
        val restored = CookieJar(snapshot)
        assertEquals(jar.headerFor("api.example.com"), restored.headerFor("api.example.com"))
        assertEquals("sid=abc==", restored.headerFor("example.com"))
        assertEquals(snapshot, restored.snapshot())
    }

    @Test
    fun `snapshot 是快照不随后续改动而变`() {
        val jar = CookieJar()
        jar.store("example.com", listOf("sid=1"))
        val snapshot = jar.snapshot()

        jar.store("example.com", listOf("sid=2"))
        jar.store("other.com", listOf("x=1"))
        assertEquals(mapOf("example.com" to "sid=1"), snapshot)
    }
}
