package dev.red.apiscope.core

import dev.red.apiscope.core.export.CurlWriter
import dev.red.apiscope.core.http.ApiRequest
import dev.red.apiscope.core.http.MultipartPart
import dev.red.apiscope.core.http.RequestBody
import kotlin.test.Test
import kotlin.test.assertEquals

class CurlWriterTest {

    /** 期望值按「行尾 ` \` + 换行 + 2 空格缩进」拼，避免在字面量里数空格 */
    private fun lines(vararg line: String) = line.joinToString(" \\\n  ")

    @Test
    fun `GET 无 body 只有一行且带 -X`() {
        val curl = CurlWriter.write(ApiRequest("GET", "http://localhost:8080/ping"))
        assertEquals("curl -X GET 'http://localhost:8080/ping'", curl)
    }

    @Test
    fun `JSON body 用 data-raw 输出`() {
        val curl = CurlWriter.write(
            ApiRequest(
                method = "POST",
                url = "http://localhost:8080/order/create",
                headers = linkedMapOf("Content-Type" to "application/json", "X-Trace" to "t1"),
                body = RequestBody.Text("""{"orderId":10086}""")
            )
        )
        assertEquals(
            lines(
                "curl -X POST 'http://localhost:8080/order/create'",
                "-H 'Content-Type: application/json'",
                "-H 'X-Trace: t1'",
                """--data-raw '{"orderId":10086}'"""
            ),
            curl
        )
    }

    @Test
    fun `Form body 拼成 data-raw 的键值串`() {
        val curl = CurlWriter.write(
            ApiRequest(
                method = "POST",
                url = "http://localhost:8080/login",
                body = RequestBody.Form(listOf("username" to "red", "age" to "18"))
            )
        )
        assertEquals(
            lines("curl -X POST 'http://localhost:8080/login'", "--data-raw 'username=red&age=18'"),
            curl
        )
    }

    @Test
    fun `Form body 与 FormEncoder 一样做 URL 编码`() {
        // 与 FormEncoder.encode 对齐（它用 URLEncoder，空格 -> +），保证粘出去的命令和插件自己发的字节一致
        val curl = CurlWriter.write(
            ApiRequest(
                method = "POST",
                url = "http://h/s",
                body = RequestBody.Form(listOf("q" to "a b", "tag" to "x&y"))
            )
        )
        assertEquals(lines("curl -X POST 'http://h/s'", "--data-raw 'q=a+b&tag=x%26y'"), curl)
    }

    @Test
    fun `Multipart 文件 part 用 @ 前缀`() {
        val curl = CurlWriter.write(
            ApiRequest(
                method = "POST",
                url = "http://localhost:8080/upload",
                body = RequestBody.Multipart(
                    listOf(
                        MultipartPart.Field("name", "red"),
                        MultipartPart.FileRef("avatar", "/tmp/a.png")
                    )
                )
            )
        )
        assertEquals(
            lines(
                "curl -X POST 'http://localhost:8080/upload'",
                "-F 'name=red'",
                "-F 'avatar=@/tmp/a.png'"
            ),
            curl
        )
    }

    @Test
    fun `值内的单引号转义成闭合再重开的写法`() {
        val curl = CurlWriter.write(
            ApiRequest(
                method = "GET",
                url = "http://h/s",
                headers = mapOf("X-Note" to "it's ok")
            )
        )
        assertEquals(lines("curl -X GET 'http://h/s'", "-H 'X-Note: it'\\''s ok'"), curl)
    }

    @Test
    fun `URL 的查询参数被单引号保护`() {
        val curl = CurlWriter.write(ApiRequest("GET", "http://h/search?q=kotlin&page=2"))
        assertEquals("curl -X GET 'http://h/search?q=kotlin&page=2'", curl)
    }

    @Test
    fun `不输出 headers 里不存在的 Content-Type 推断值`() {
        // ApiRequest.headers 才是真发出去的东西，多输出一个推断值会让人以为面板里配过它
        val curl = CurlWriter.write(
            ApiRequest("POST", "http://h/s", body = RequestBody.Text("""{"a":1}"""))
        )
        assertEquals(lines("curl -X POST 'http://h/s'", """--data-raw '{"a":1}'"""), curl)
    }
}
