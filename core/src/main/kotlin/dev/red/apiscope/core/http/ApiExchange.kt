package dev.red.apiscope.core.http

/**
 * 一次请求 / 响应的数据模型（与 IntelliJ 无关，便于独立测试）
 *
 * @author Red
 * @since 2026-08-14
 */
data class ApiRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: RequestBody = RequestBody.None
) {
    fun withHeaders(extra: Map<String, String>): ApiRequest {
        if (extra.isEmpty()) return this
        // 已显式写在请求里的 header 优先，环境默认值不覆盖它
        val merged = LinkedHashMap(extra)
        merged.putAll(headers)
        return copy(headers = merged)
    }
}

data class ApiResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val elapsedMs: Long,
    /** 网络层失败（连不上 / 超时 / DNS）时非空，此时 status = -1 */
    val error: String? = null
) {
    val isNetworkFailure: Boolean get() = error != null
    val contentType: String? get() = headers.entries
        .firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value?.firstOrNull()

    companion object {
        fun failure(error: String, elapsedMs: Long) =
            ApiResponse(status = -1, headers = emptyMap(), body = "", elapsedMs = elapsedMs, error = error)
    }
}
