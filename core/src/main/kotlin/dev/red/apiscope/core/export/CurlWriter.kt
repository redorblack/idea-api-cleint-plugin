package dev.red.apiscope.core.export

import dev.red.apiscope.core.http.ApiRequest
import dev.red.apiscope.core.http.FormEncoder
import dev.red.apiscope.core.http.MultipartPart
import dev.red.apiscope.core.http.RequestBody

/**
 * 把 [ApiRequest] 渲染成可直接粘进终端执行的 cURL 命令（面板里的「复制为 cURL」）
 *
 * 关键取舍：
 * 1. 只输出 [ApiRequest.headers] 里真实存在的 header，**不补 Content-Type 的推断值**。
 *    因为 ApiRequest 里的 header 才是面板上配置、真正发出去的东西；多输出一个推断值，
 *    拿到命令的人会以为面板里配了它，改回来时找不到对应的输入框。
 *    真发请求时的 Content-Type 由 HttpExecutor 那侧决定，不是本类的职责。
 * 2. `-X` 永远输出（GET 也输出）。命令是贴给别人看的，要自解释，
 *    读的人不必回忆 curl 「有 -d 就变 POST、否则 GET」的默认规则。
 * 3. 所有值统一用单引号包裹，值内部的单引号按 shell 惯例写成 `'\''`
 *    （闭合当前引号 → 一个转义单引号 → 重新开引号）。单引号包裹的好处是内部
 *    `$VAR`、反引号、`&` 全部不被 shell 展开，粘出去的命令不会被环境改写。
 *
 * @author Red
 * @since 2026-08-14
 */
object CurlWriter {

    /** 续行：行尾 ` \` + 换行，下一行缩进 2 空格 */
    private const val LINE_JOIN = " \\\n  "

    fun write(request: ApiRequest): String {
        val lines = ArrayList<String>()
        lines += "curl -X ${request.method} ${quote(request.url)}"

        // header 保持传入 Map 的顺序（LinkedHashMap 语义），一个 header 一行便于 diff 和手改
        request.headers.forEach { (name, value) -> lines += "-H ${quote("$name: $value")}" }

        when (val body = request.body) {
            is RequestBody.None -> Unit // 无 body 时不输出任何 data 参数，避免 curl 把方法隐式改成 POST 的错觉

            // 用 --data-raw 而不是 -d：-d 会把 body 里的 @ 当成「读文件」、换行会被吃掉，
            // --data-raw 是纯字面量，JSON 原样贴出去可复现。
            is RequestBody.Text -> lines += "--data-raw ${quote(body.content)}"

            // 与 FormEncoder.encode 对齐：它用 URLEncoder 做了 URL 编码，
            // 所以这里直接复用它，保证粘出去的 curl 发出的字节和插件自己发的完全一致。
            // 自己重新拼一遍「不编码」的版本会造成两套行为，含中文/&/= 的字段一跑就对不上。
            is RequestBody.Form -> lines += "--data-raw ${quote(FormEncoder.encode(body.fields))}"

            is RequestBody.Multipart -> body.parts.forEach { part ->
                lines += when (part) {
                    is MultipartPart.Field -> "-F ${quote("${part.name}=${part.value}")}"
                    // curl 用 `@路径` 表示上传文件，这是 -F 里 @ 的固有语义
                    is MultipartPart.FileRef -> "-F ${quote("${part.name}=@${part.path}")}"
                }
            }
        }
        return lines.joinToString(LINE_JOIN)
    }

    /** 单引号包裹；内部单引号 → `'\''` */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
