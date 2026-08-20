package dev.red.apiscope.core.endpoint

/**
 * URL 与 query string 的切分结果
 *
 * 用 `List<Pair<String, String>>` 而不是 Map，是因为 query 允许同名参数重复出现（如 `id=1&id=2`），
 * 且面板里的参数表格要保持源 URL 的原始顺序 —— Map 两点都做不到。
 *
 * @author Red
 * @since 2026-08-14
 */
data class SplitUrl(
    /** `?` 之前的部分，不含问号 */
    val base: String,
    val params: List<Pair<String, String>>
)

/**
 * 查询参数的拼接与切分 —— 请求面板（RequestPanel）用它在「URL 输入框」和「参数表格」之间来回搬数据
 *
 * 关键取舍：全程不做 URL encode / decode，原样搬运。这样 [split] 与 [append] 严格互逆，
 * 用户在面板里手改过的 URL 回填表格时不会被悄悄改写；代价是值里要出现 `&` 或 `=` 时得由调用方自行编码。
 *
 * @author Red
 * @since 2026-08-14
 */
object QueryStrings {

    fun append(url: String, params: List<QueryParam>): String {
        if (params.isEmpty()) return url
        val query = params.joinToString("&") { "${it.name}=${it.sampleValue}" }
        return if (url.contains('?')) "$url&$query" else "$url?$query"
    }

    /**
     * 把完整 URL 拆成 base 与有序的 name-value 对
     *
     * 无 `?`、或 `?` 后为空时，params 为空列表。切分点只认第一个 `?`，每段只认第一个 `=`，
     * 因此 `a=1=2` 得到 `("a", "1=2")`，第二个 `?` 会留在 value 里。
     * 空段与没有名字的段（如 `=1`）直接丢弃：无名参数发不出去，留着只会污染参数表格。
     *
     * 不做 URL decode，也不 trim name/value —— URL 里的空格是有意义的字符，trim 会静默改变请求内容。
     * `#fragment` 同样不做特殊处理，会留在最后一个 value 里：面板里的地址极少带 fragment，
     * 为它多引入一个解析分支不值。
     */
    fun split(url: String): SplitUrl {
        val mark = url.indexOf('?')
        if (mark < 0) return SplitUrl(url, emptyList())

        val params = url.substring(mark + 1)
            .split('&')
            .mapNotNull { segment ->
                if (segment.isEmpty()) return@mapNotNull null
                val eq = segment.indexOf('=')
                val name = if (eq < 0) segment else segment.substring(0, eq)
                if (name.isEmpty()) return@mapNotNull null
                name to if (eq < 0) "" else segment.substring(eq + 1)
            }
        return SplitUrl(url.substring(0, mark), params)
    }
}
