package dev.red.apiscope.core

import dev.red.apiscope.core.export.CurlReader
import dev.red.apiscope.core.export.CurlWriter
import dev.red.apiscope.core.http.ApiRequest
import dev.red.apiscope.core.http.FormEncoder
import dev.red.apiscope.core.http.MultipartPart
import dev.red.apiscope.core.http.RequestBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurlReaderTest {

    private fun parse(text: String) = assertNotNull(CurlReader.read(text), "应该能解析: $text")

    @Test
    fun `反斜杠续行的多行命令`() {
        val parsed = parse(
            """
            curl -X POST 'http://localhost:8080/order/create' \
              -H 'Content-Type: application/json' \
              --data-raw '{"orderId":10086}'
            """.trimIndent()
        )
        assertEquals("POST", parsed.method)
        assertEquals("http://localhost:8080/order/create", parsed.url)
        assertEquals(listOf("Content-Type" to "application/json"), parsed.headers)
        assertEquals(RequestBody.Text("""{"orderId":10086}""", "application/json"), parsed.body)
    }

    @Test
    fun `单引号包裹的值`() {
        val parsed = parse("curl -X GET 'http://h/s' -H 'Accept: application/json'")
        assertEquals("GET", parsed.method)
        assertEquals("http://h/s", parsed.url)
        assertEquals(listOf("Accept" to "application/json"), parsed.headers)
    }

    @Test
    fun `双引号包裹的值`() {
        val parsed = parse("""curl -X GET "http://h/s" -H "Accept: application/json" """)
        assertEquals("http://h/s", parsed.url)
        assertEquals(listOf("Accept" to "application/json"), parsed.headers)
    }

    @Test
    fun `引号内的空白不切分`() {
        val parsed = parse("curl 'http://h/s' -H 'X-Note: hello world  again'")
        assertEquals(listOf("X-Note" to "hello world  again"), parsed.headers)
    }

    @Test
    fun `引号内的另一种引号是普通字符`() {
        val parsed = parse("""curl 'http://h/s' -d 'say "hi"'""")
        assertEquals(RequestBody.Text("say \"hi\"", "application/json; charset=utf-8"), parsed.body)
    }

    @Test
    fun `双引号内的转义双引号还原成字面量`() {
        // 输入里是字面的 \" —— 模拟 Windows 版 Chrome「Copy as cURL」的双引号写法
        val parsed = parse("curl 'http://h/s' -d \"{\\\"a\\\":1}\"")
        assertEquals("""{"a":1}""", (parsed.body as RequestBody.Text).content)
    }

    @Test
    fun `多个 -H 全部保留且按出现顺序`() {
        val parsed = parse("curl 'http://h/s' -H 'A: 1' --header 'B: 2' -H 'A: 3'")
        assertEquals(listOf("A" to "1", "B" to "2", "A" to "3"), parsed.headers)
    }

    @Test
    fun `header 按第一个冒号切分`() {
        val parsed = parse("curl 'http://h/s' -H 'X-Origin: http://a:8080/x'")
        assertEquals(listOf("X-Origin" to "http://a:8080/x"), parsed.headers)
    }

    @Test
    fun `-d 与 --data-raw 等价`() {
        val a = parse("""curl 'http://h/s' -d '{"a":1}'""")
        val b = parse("""curl 'http://h/s' --data-raw '{"a":1}'""")
        val c = parse("""curl 'http://h/s' --data-binary '{"a":1}'""")
        assertEquals(a.body, b.body)
        assertEquals(a.body, c.body)
    }

    @Test
    fun `没有 -X 时按有无 body 推断 method`() {
        assertEquals("GET", parse("curl 'http://h/s'").method)
        assertEquals("POST", parse("""curl 'http://h/s' -d '{"a":1}'""").method)
        assertEquals("POST", parse("curl 'http://h/s' -F 'k=v'").method)
        // 显式 -X 优先于推断
        assertEquals("PUT", parse("""curl -X put 'http://h/s' -d '{"a":1}'""").method)
    }

    @Test
    fun `-F 的 @ 路径解析成 FileRef`() {
        val parsed = parse("curl 'http://h/upload' -F 'name=red' -F 'avatar=@/tmp/a.png'")
        assertEquals(
            RequestBody.Multipart(
                listOf(
                    MultipartPart.Field("name", "red"),
                    MultipartPart.FileRef("avatar", "/tmp/a.png")
                )
            ),
            parsed.body
        )
    }

    @Test
    fun `form-urlencoded 的 Content-Type 让 body 变成 Form`() {
        val parsed = parse(
            "curl 'http://h/login' -H 'Content-Type: application/x-www-form-urlencoded; charset=utf-8' " +
                "-d 'username=red&age=18'"
        )
        assertEquals(RequestBody.Form(listOf("username" to "red", "age" to "18")), parsed.body)
    }

    @Test
    fun `-u 展开成 Basic 认证 header`() {
        val parsed = parse("curl 'http://h/s' -u 'red:secret123'")
        assertEquals(listOf("Authorization" to "Basic cmVkOnNlY3JldDEyMw=="), parsed.headers)
    }

    @Test
    fun `-b 展开成 Cookie header`() {
        val parsed = parse("curl 'http://h/s' -b 'sid=abc; theme=dark'")
        assertEquals(listOf("Cookie" to "sid=abc; theme=dark"), parsed.headers)
    }

    @Test
    fun `不支持的 flag 进 unsupported 且不吞掉后面的 token`() {
        val parsed = parse("curl 'http://h/s' --compressed -H 'A: 1' -k --retry 3 -H 'B: 2'")
        assertEquals(listOf("A" to "1", "B" to "2"), parsed.headers)
        assertEquals(listOf("--compressed", "-k", "--retry"), parsed.unsupported)
        assertEquals("http://h/s", parsed.url)
    }

    @Test
    fun `跟随重定向的 -L 不算不支持`() {
        val parsed = parse("curl -L 'http://h/s' -H 'A: 1'")
        assertTrue(parsed.unsupported.isEmpty())
        assertEquals(listOf("A" to "1"), parsed.headers)
    }

    @Test
    fun `没有 URL 返回 null`() {
        assertNull(CurlReader.read("curl -X POST -H 'A: 1'"))
        assertNull(CurlReader.read(""))
    }

    @Test
    fun `没有 curl 前缀也能解析`() {
        val parsed = parse("-X DELETE 'http://h/s/1' -H 'A: 1'")
        assertEquals("DELETE", parsed.method)
        assertEquals("http://h/s/1", parsed.url)
    }

    @Test
    fun `--url 与 --request 长选项`() {
        val parsed = parse("curl --request patch --url 'http://h/s/1'")
        assertEquals("PATCH", parsed.method)
        assertEquals("http://h/s/1", parsed.url)
    }

    // ---- 往返：write 出去的命令必须能被 read 原样读回来 ----

    private fun assertRoundTrip(request: ApiRequest) {
        val curl = CurlWriter.write(request)
        val parsed = assertNotNull(CurlReader.read(curl), "写出的命令应可解析:\n$curl")
        assertEquals(request.method, parsed.method, curl)
        assertEquals(request.url, parsed.url, curl)
        assertEquals(request.headers.toList(), parsed.headers, curl)
        assertEquals(request.body, parsed.body, curl)
        assertTrue(parsed.unsupported.isEmpty(), curl)
    }

    @Test
    fun `往返 GET 含单引号的 header 与查询参数`() {
        assertRoundTrip(
            ApiRequest(
                method = "GET",
                url = "http://h/search?q=kotlin&page=2",
                headers = linkedMapOf("X-Note" to "it's ok", "Accept" to "application/json")
            )
        )
    }

    @Test
    fun `往返 JSON POST`() {
        assertRoundTrip(
            ApiRequest(
                method = "POST",
                url = "http://localhost:8080/order/create",
                // Content-Type 必须显式写在 headers 里：CurlWriter 不输出推断值，
                // 少了它 read 回来会退化成默认 contentType
                headers = linkedMapOf("Content-Type" to "application/json; charset=utf-8"),
                body = RequestBody.Text("""{"orderId":10086,"note":"it's fine"}""")
            )
        )
    }

    @Test
    fun `往返 Form`() {
        assertRoundTrip(
            ApiRequest(
                method = "POST",
                url = "http://localhost:8080/login",
                headers = linkedMapOf("Content-Type" to FormEncoder.CONTENT_TYPE),
                // 字段值用 URL 编码后不变的字符：writer 与 FormEncoder 对齐会编码，而 reader 不解码
                body = RequestBody.Form(listOf("username" to "red", "age" to "18"))
            )
        )
    }

    @Test
    fun `往返 Multipart`() {
        assertRoundTrip(
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
    }
}
