package dev.red.apiscope.core.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * 请求执行器 —— 基于 JDK17 内置 HttpClient，零第三方依赖
 *
 * 这样 IDEA Community 版（无内置 HTTP Client）也能直接发请求，
 * 同时避免往插件里塞 OkHttp 造成 classloader 冲突。
 *
 * @author Red
 * @since 2026-08-14
 */
class HttpExecutor(
    // 本地调试面对的多是「地址写错」（比如漏了 .com），不是服务真的慢；
    // 快速失败让用户尽快看到「连不上」，比干等 10s 有用得多
    connectTimeout: Duration = Duration.ofSeconds(3),
    private val readTimeout: Duration = Duration.ofSeconds(15)
) : RequestSender {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * 一次进行中的请求。
     *
     * [source] 是 `client.sendAsync(...)` 直接返回的 future —— 取消必须作用在它身上：
     * JDK17 HttpClient 把这个 future 和底层的 socket/HTTP exchange 绑在一起，cancel(true)
     * 会真正中断连接、唤醒阻塞的读线程。如果只 cancel 由它派生（map）出来的 [response]，
     * 效果只是"我不再关心结果了"——底层的连接/读取仍在后台继续跑，直到自然超时或完成，
     * 白白占着线程和 socket。这不是真取消，只是假装取消。
     */
    class Exchange internal constructor(
        private val source: CompletableFuture<*>,
        val response: CompletableFuture<ApiResponse>
    ) {
        fun cancel() {
            source.cancel(true)
        }
    }

    override fun execute(request: ApiRequest): ApiResponse = start(request).response.join()

    /**
     * 发起一次可取消的请求。
     *
     * [Exchange.response] 这个 future 只会正常完成，永远不会异常完成：无论是成功、
     * 网络异常、编码异常还是被取消，最终都落到一个 [ApiResponse]（成功 or [ApiResponse.failure]）。
     * 调用方（UI 侧）因此不需要 catch 任何东西，直接消费结果即可。
     */
    fun start(request: ApiRequest): Exchange {
        val startedAt = System.nanoTime()

        val httpRequest = try {
            build(request)
        } catch (e: Exception) {
            // build() 阶段就地失败（如 multipart 引用的文件不存在），此时还没有底层交换，
            // 直接返回一个已完成的失败 Exchange，不把异常抛给调用方
            val noop = CompletableFuture.completedFuture<Any?>(null)
            val failed = CompletableFuture.completedFuture(ApiResponse.failure(describe(e), elapsedMs(startedAt)))
            return Exchange(noop, failed)
        }

        val source = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
        val response = source.handle { httpResponse, throwable ->
            if (throwable != null) {
                ApiResponse.failure(describe(unwrap(throwable)), elapsedMs(startedAt))
            } else {
                ApiResponse(
                    status = httpResponse.statusCode(),
                    headers = httpResponse.headers().map(),
                    body = httpResponse.body() ?: "",
                    elapsedMs = elapsedMs(startedAt)
                )
            }
        }
        return Exchange(source, response)
    }

    /**
     * sendAsync 派生出来的 future 在异常路径上可能把真正的原因包在 [CompletionException] 里
     * （比如取消时拿到的是 CompletionException(CancellationException)），
     * 这里拆包一层，拿到真正能用来 describe() 的 cause，否则取消/超时会被误判成"未知异常"
     */
    private fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        while (current is CompletionException) {
            current = current.cause ?: return current
        }
        return current
    }

    private fun build(request: ApiRequest): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url))
            .timeout(readTimeout)

        // 不同 body 形态各自决定 publisher 和默认 Content-Type；multipart 的 boundary
        // 只有编码那一刻才生成，所以它的 content-type 必须强制生效（见下方 forceContentType）
        val (publisher, defaultContentType, forceContentType) = when (val body = request.body) {
            is RequestBody.None -> Triple(HttpRequest.BodyPublishers.noBody(), null, false)
            is RequestBody.Text -> Triple(
                HttpRequest.BodyPublishers.ofString(body.content, Charsets.UTF_8),
                body.contentType,
                false
            )
            is RequestBody.Form -> Triple(
                HttpRequest.BodyPublishers.ofString(FormEncoder.encode(body.fields), Charsets.UTF_8),
                FormEncoder.CONTENT_TYPE,
                false
            )
            is RequestBody.Multipart -> {
                val encoded = MultipartEncoder.encode(body.parts)
                Triple(HttpRequest.BodyPublishers.ofByteArray(encoded.bytes), encoded.contentType, true)
            }
        }
        builder.method(request.method.uppercase(), publisher)

        request.headers.forEach { (name, value) ->
            // Restricted header（Host / Connection 等）JDK 会直接抛异常，静默跳过更实用
            runCatching { builder.header(name, value) }
        }

        val userSetContentType = request.headers.any { it.key.equals("content-type", ignoreCase = true) }
        if (defaultContentType != null && (forceContentType || !userSetContentType)) {
            // 用 setHeader 而不是 header：header() 是"追加"，如果用户已经手写了 Content-Type，
            // 追加会导致请求里出现两个 Content-Type 头；setHeader 会整体覆盖，只留一个
            //
            // multipart 分支必须强制覆盖（forceContentType=true）：boundary 只在上面编码那一刻随机生成，
            // 用户手写的 content-type 头不可能包含正确的 boundary，留着服务端会拿错的 boundary 切分 body，请求必坏
            builder.setHeader("Content-Type", defaultContentType)
        }
        return builder.build()
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    /** 网络异常的可读描述 —— 排错时「连不上」和「超时」结论完全不同，必须区分 */
    private fun describe(e: Throwable): String = when (e) {
        is CancellationException -> "已取消"
        is java.net.http.HttpTimeoutException -> "请求超时（${readTimeout.seconds}s）"
        is java.net.ConnectException -> "连接被拒绝，服务未启动或地址不可达：${e.message}"
        is java.net.UnknownHostException -> "域名解析失败：${e.message}"
        is java.nio.file.NoSuchFileException -> "上传文件不存在：${e.message}"
        is IllegalArgumentException -> "URL 非法：${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}
