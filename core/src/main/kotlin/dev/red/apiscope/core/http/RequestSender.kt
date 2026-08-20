package dev.red.apiscope.core.http

/**
 * 发请求的能力
 *
 * 抽成接口只为一件事：双发编排能在测试里换成假发送器，
 * 从而把「解析两条路线 → 发送 → 判定结论」整条链一起测，而不是只测末端判定。
 *
 * @author Red
 * @since 2026-08-14
 */
fun interface RequestSender {
    fun execute(request: ApiRequest): ApiResponse
}
