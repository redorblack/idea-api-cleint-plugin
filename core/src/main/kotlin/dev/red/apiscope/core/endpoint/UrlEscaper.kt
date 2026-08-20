package dev.red.apiscope.core.endpoint

/**
 * 发送前最后一步：把 URL 里 `URI` 不接受的字符补上百分号编码
 *
 * 为什么不在 [QueryStrings] 里做：那两个函数必须严格互逆（拆开再拼回去要一字不差），
 * 一旦在中途 encode，用户在地址栏看到的就不是自己敲的东西了。所以约定是
 * **UI 与文本模型里全程保留原文，只在拼出真正要发的 URL 时escape 一次**。
 *
 * 不做这一步的后果很具体：`?startTime=2026-08-14 00:00:00`、`?keyword=张 三` 这类值
 * 会让 `URI.create` 抛 `Illegal character in query`，界面上呈现成「URL 非法」——
 * 而用户只会怀疑自己 Base URL 写错了，根本想不到是值里那个空格。
 *
 * **幂等**：已经写成 `%XX` 的不再二次编码（否则 `%20` 会变成 `%2520`），
 * 所以从别处拷来的、本身已编码的地址粘进来也不会坏。
 *
 * @author Red
 * @since 2026-08-14
 */
object UrlEscaper {

    /** URI 里可以原样出现的字符：unreserved + 各段的分隔符。其余一律编码 */
    private const val SAFE = "-._~:/?#[]@!$&'()*+,;="

    fun escape(url: String): String {
        val out = StringBuilder(url.length + 16)
        var index = 0
        while (index < url.length) {
            when {
                isEncodedTriple(url, index) -> {
                    out.append(url, index, index + 3)
                    index += 3
                }

                isSafe(url[index]) -> {
                    out.append(url[index])
                    index++
                }

                else -> {
                    // 整段一起转字节：逐个 char 转会把 emoji / 生僻字的代理对拆成两个坏字节
                    val start = index
                    while (index < url.length && !isSafe(url[index]) && !isEncodedTriple(url, index)) index++
                    url.substring(start, index).toByteArray(Charsets.UTF_8).forEach { byte ->
                        out.append('%').append(HEX[byte.toInt() shr 4 and 0xF]).append(HEX[byte.toInt() and 0xF])
                    }
                }
            }
        }
        return out.toString()
    }

    private fun isSafe(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in SAFE

    /** `%` 后面跟着两位十六进制才算已编码；单独一个 `%` 是要编码成 `%25` 的普通字符 */
    private fun isEncodedTriple(url: String, index: Int): Boolean =
        url[index] == '%' &&
            index + 2 < url.length &&
            isHex(url[index + 1]) &&
            isHex(url[index + 2])

    private fun isHex(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    private val HEX = "0123456789ABCDEF".toCharArray()
}
