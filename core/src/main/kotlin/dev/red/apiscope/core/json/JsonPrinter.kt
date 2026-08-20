package dev.red.apiscope.core.json

/**
 * 单遍字符扫描给 HTTP 响应体加缩进，不解析成对象模型
 *
 * 为什么不复用 JsonSample：JsonSample 是「PSI 类型 → 树 → 文本」的单向管线，没有从字符串
 * 反向解析的能力；而响应体美化只需要「补缩进」，没必要为此再写一个完整的 JSON parser（容错、
 * 性能、维护成本都不划算）。也不能引第三方 JSON 库——IntelliJ 平台自带 Jackson，插件里再带
 * 一份版本很容易 classloader 冲突，这也是本模块的既有约束。
 *
 * 响应体可能根本不是 JSON（HTML 错误页、纯文本 "OK"、XML），所以整个实现的第一原则是
 * 「宁可不美化，也不能把原文搞坏」：开头字符不认识就原样返回；扫描中途括号不配平（截断响应
 * 之类）也绝不抛异常，尽力吐出结果。
 *
 * @author Red
 * @since 2026-08-14
 */
object JsonPrinter {

    fun pretty(raw: String, indent: Int = 2): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return raw
        // 只认「以 { 或 [ 开头」的输入是 JSON，否则原样返回——避免把 HTML/纯文本误判成 JSON 改坏
        if (trimmed[0] != '{' && trimmed[0] != '[') return raw

        val sb = StringBuilder(raw.length + 32)
        var depth = 0
        var inString = false
        var i = 0
        val n = raw.length

        while (i < n) {
            val c = raw[i]

            if (inString) {
                // 字符串内部一律原样透传，{ } [ ] , : 在字符串里不是结构字符
                sb.append(c)
                // 转义序列必须连着两个字符一起消费：\" 里的 " 不能被当成字符串结束，
                // 而 \\ 这种「反斜杠自身被转义」的情况也要靠成对消费才不会误判下一个字符
                if (c == '\\' && i + 1 < n) {
                    sb.append(raw[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }

            when (c) {
                '"' -> {
                    inString = true
                    sb.append(c)
                    i++
                }
                '{', '[' -> {
                    val closing = if (c == '{') '}' else ']'
                    // 跳过空白看紧跟的是不是配对的闭合符：空容器 {}/[] 内联一行输出，
                    // 不展开成两行——展开反而降低可读性
                    var j = i + 1
                    while (j < n && raw[j].isWhitespace()) j++
                    if (j < n && raw[j] == closing) {
                        sb.append(c).append(closing)
                        i = j + 1
                    } else {
                        sb.append(c)
                        depth++
                        sb.append('\n').append(indentOf(indent, depth))
                        i++
                    }
                }
                '}', ']' -> {
                    // 括号不配平（比如响应被截断）时 depth 不能变负，否则后面所有缩进都会错位
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append('\n').append(indentOf(indent, depth))
                    sb.append(c)
                    i++
                }
                ',' -> {
                    sb.append(c)
                    sb.append('\n').append(indentOf(indent, depth))
                    i++
                }
                ':' -> {
                    sb.append(": ")
                    i++
                    while (i < n && raw[i].isWhitespace()) i++
                }
                else -> {
                    // 结构字符之外的空白（空格/换行/制表等）直接丢弃，缩进由我们自己重新生成
                    if (!c.isWhitespace()) sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun indentOf(indent: Int, depth: Int): String = " ".repeat(indent * depth)
}
