package dev.red.apiscope.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.red.apiscope.core.http.ApiRequest
import dev.red.apiscope.core.http.HttpExecutor
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpExecutorTest {

    /** 用 JDK 自带的 HttpServer 起一个本地服务，端口交给系统分配，避免占用固定端口引发冲突 */
    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return server
    }

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray()
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    @Test
    fun `正常 200 响应`() {
        val server = startServer { exchange -> exchange.respond(200, "{\"code\":0}") }
        try {
            val executor = HttpExecutor()
            val response = executor.execute(
                ApiRequest(method = "GET", url = "http://127.0.0.1:${server.address.port}/")
            )
            assertEquals(200, response.status)
            assertEquals("{\"code\":0}", response.body)
            assertFalse(response.isNetworkFailure)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `真取消 —— cancel 后立刻返回，不会等满慢响应`() {
        // handler 故意慢响应 5s，验证 cancel 是否真正中断了底层交换，而不是让它在后台跑满
        val server = startServer { exchange ->
            Thread.sleep(5000)
            runCatching { exchange.respond(200, "too-late") }
        }
        try {
            val executor = HttpExecutor()
            val startedAt = System.nanoTime()
            val exchange = executor.start(ApiRequest(method = "GET", url = "http://127.0.0.1:${server.address.port}/"))
            Thread.sleep(200)
            exchange.cancel()
            val response = exchange.response.join()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMs < 3000, "耗时 ${elapsedMs}ms，取消没能真正中断底层请求（应远小于 handler 的 5000ms sleep）")
            assertTrue(response.isNetworkFailure)
            assertTrue(response.error?.contains("已取消") == true, "实际 error：${response.error}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `读超时会失败并给出超时文案`() {
        val server = startServer { exchange ->
            Thread.sleep(5000)
            runCatching { exchange.respond(200, "too-late") }
        }
        try {
            // readTimeout 远小于 handler 的 5000ms sleep，留足余量避免边界 flaky
            val executor = HttpExecutor(readTimeout = Duration.ofMillis(300))
            val response = executor.execute(
                ApiRequest(method = "GET", url = "http://127.0.0.1:${server.address.port}/")
            )
            assertTrue(response.isNetworkFailure)
            assertTrue(response.error?.contains("超时") == true, "实际 error：${response.error}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `response future 永不异常完成 —— UI 侧不需要 catch`() {
        // 127.0.0.1:1 上没有任何服务在监听，连接会立刻被拒绝，不需要等超时
        val executor = HttpExecutor()
        val exchange = executor.start(ApiRequest(method = "GET", url = "http://127.0.0.1:1/"))
        val response = exchange.response.join() // 不应抛出任何异常
        assertTrue(response.isNetworkFailure)
    }
}
