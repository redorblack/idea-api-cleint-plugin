package dev.red.apiscope.core.export

import dev.red.apiscope.core.http.MultipartPart
import dev.red.apiscope.core.http.RequestBody
import java.util.Base64

/**
 * 一条 cURL 命令的解析结果
 *
 * headers 用 `List<Pair>` 而不是 Map：cURL 里 `-H` 可以重复同名（如多个 Set-Cookie 风格的头），
 * 先按原样保留，要不要去重交给上层面板决定。
 */
data class ParsedCurl(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: RequestBody,
    /** 识别到但不支持的参数，如 --compressed；上层会显示给用户 */
    val unsupported: List<String>
)

/**
 * 把别人丢过来的一条 cURL 命令解析成面板可以直接填的结构
 *
 * 关键取舍：
 * 1. **不静默丢弃**任何以 `-` 开头的未知参数，一律进 [ParsedCurl.unsupported] 让上层提示。
 *    静默丢会让人以为导入成功，等到请求行为不一致时再回头查，比直接报出来难查得多。
 * 2. 自己做分词而不是按空白 split：cURL 里 `-H 'Content-Type: application/json'` 的值天然带空格，
 *    按空白切会碎成两个 token。分词器同时支持单/双引号（同事从 Chrome「Copy as cURL」
 *    复制出来的，Windows 版用双引号、*nix 版用单引号）。
 * 3. 缺少 `curl` 前缀也尽力解析：用户经常只粘参数片段，为此报错不划算。
 *    唯一返回 null 的情况是**解析不出 URL** —— 没有 URL 的请求填不进面板，是真的无法继续。
 *
 * @author Red
 * @since 2026-08-14
 */
object CurlReader {

    /**
     * 只有名单里的 flag 会消费后面一个 token 当值。
     *
     * 用白名单而不是「列举已知的无值 flag（--compressed / -k / -s / -i / -v / -g …）」：
     * 两者对已知 flag 效果相同，但白名单对**未知** flag 也安全。反过来做的话，
     * `--compressed -H 'A: b'` 里的 `-H` 会被当成 `--compressed` 的值吞掉，
     * 症状是「导入后莫名少了一个 header」，比直接把 flag 报成 unsupported 难查得多；
     * 而未知 flag 的元数我们本来就无从得知，只能选择不吞。
     */
    private val VALUE_FLAGS = setOf(
        "-X", "--request", "--url", "-H", "--header",
        "-d", "--data", "--data-raw", "--data-binary",
        "-F", "--form", "-b", "--cookie", "-u", "--user"
    )

    /**
     * `-L/--location` 单独一档：它同样无值（必须认，否则吞掉后面的 token），
     * 但**不进 unsupported** —— 我们的执行器本来就跟随重定向，这个 flag 的语义已经被满足了。
     * unsupported 的含义是「你写了但我们做不到」，把已满足的行为报给用户属于误报噪音。
     */
    private val FOLLOWED_NO_OP_FLAGS = setOf("-L", "--location")

    private const val DEFAULT_TEXT_CONTENT_TYPE = "application/json; charset=utf-8"
    private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

    /** 解析不出 URL 时返回 null */
    fun read(text: String): ParsedCurl? {
        val tokens = tokenize(text)

        var method: String? = null
        var url: String? = null
        val headers = ArrayList<Pair<String, String>>()
        val dataChunks = ArrayList<String>()
        val parts = ArrayList<MultipartPart>()
        // LinkedHashSet：同一个不支持的 flag 写多次只提示一次，但保留首次出现顺序
        val unsupported = LinkedHashSet<String>()

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            // 命令名跳过；有人会粘 `/usr/bin/curl`。排除 `://` 是为了不误伤
            // `http://host/curl` 这种真的以 /curl 结尾的 URL
            if (token == "curl" || (token.endsWith("/curl") && !token.contains("://"))) {
                i++
                continue
            }
            if (!token.startsWith("-") || token == "-") {
                // 不是 flag、也不是某个 flag 的值（值都在下面的分支里被提前消费掉了）→ 当作 URL
                if (url == null) url = token
                i++
                continue
            }
            when (token) {
                "-X", "--request" -> nextValue(tokens, i)?.let { method = it.uppercase() }
                "--url" -> nextValue(tokens, i)?.let { if (url == null) url = it }
                "-H", "--header" -> nextValue(tokens, i)?.let { headers += splitHeader(it) }
                // -d/--data/--data-raw/--data-binary 在「填面板」这个场景下没有区别：
                // 我们不实现 -d 的 @file 读取和 --data 的换行剥离，一律当字面 body 文本。
                "-d", "--data", "--data-raw", "--data-binary" -> nextValue(tokens, i)?.let { dataChunks += it }
                "-F", "--form" -> nextValue(tokens, i)?.let { parts += parsePart(it) }
                "-b", "--cookie" -> nextValue(tokens, i)?.let { headers += "Cookie" to it }
                // curl 的 -u 只是 Basic 认证的语法糖，展开成 header 面板才能显示出来
                "-u", "--user" -> nextValue(tokens, i)?.let { headers += "Authorization" to basic(it) }
                // 无值 flag（--compressed / -k / -s / -i / -v / -g …）和未知 flag 都走这里：
                // 原样记进 unsupported 让上层提示，不静默丢弃
                else -> if (token !in FOLLOWED_NO_OP_FLAGS) unsupported += token
            }
            i += if (token in VALUE_FLAGS && i + 1 < tokens.size) 2 else 1
        }

        val resolvedUrl = url ?: return null
        val contentType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        val body = buildBody(dataChunks, parts, contentType)
        // 没写 -X 时按 curl 自己的规则推断：有 body 就是 POST，否则 GET
        val resolvedMethod = method ?: if (body is RequestBody.None) "GET" else "POST"

        return ParsedCurl(resolvedMethod, resolvedUrl, headers, body, unsupported.toList())
    }

    private fun nextValue(tokens: List<String>, index: Int): String? = tokens.getOrNull(index + 1)

    private fun buildBody(
        dataChunks: List<String>,
        parts: List<MultipartPart>,
        contentType: String?
    ): RequestBody {
        // -F 优先：同时出现 -F 和 -d 时 curl 自己也会报错，这里以 multipart 为准而不是拼一半
        if (parts.isNotEmpty()) return RequestBody.Multipart(parts)
        if (dataChunks.isEmpty()) return RequestBody.None
        // 多个 -d 按 curl 的行为用 & 连接
        val data = dataChunks.joinToString("&")
        val mime = contentType?.substringBefore(';')?.trim()
        if (mime.equals(FORM_CONTENT_TYPE, ignoreCase = true)) return RequestBody.Form(parseFormFields(data))
        return RequestBody.Text(data, contentType ?: DEFAULT_TEXT_CONTENT_TYPE)
    }

    /** 不做 URL 解码：面板里展示的应该是用户/同事原样写的字符串，解错了比不解更难发现 */
    private fun parseFormFields(data: String): List<Pair<String, String>> =
        data.split("&").filter { it.isNotEmpty() }.map { pair ->
            val at = pair.indexOf('=')
            if (at < 0) pair to "" else pair.substring(0, at) to pair.substring(at + 1)
        }

    /** header 按**第一个** `:` 切：值里再出现的 `:` 是内容（如 URL、时间戳），不能参与切分 */
    private fun splitHeader(raw: String): Pair<String, String> {
        val at = raw.indexOf(':')
        return if (at < 0) raw.trim() to "" else raw.substring(0, at).trim() to raw.substring(at + 1).trim()
    }

    /** `-F` 按第一个 `=` 切；值以 `@` 开头是 curl 的「上传文件」语义 */
    private fun parsePart(raw: String): MultipartPart {
        val at = raw.indexOf('=')
        if (at < 0) return MultipartPart.Field(raw, "")
        val name = raw.substring(0, at)
        val value = raw.substring(at + 1)
        return if (value.startsWith("@")) MultipartPart.FileRef(name, value.substring(1))
        else MultipartPart.Field(name, value)
    }

    private fun basic(userInfo: String): String =
        "Basic " + Base64.getEncoder().encodeToString(userInfo.toByteArray(Charsets.UTF_8))

    /**
     * shell 风格分词：引号内的空白不切分
     *
     * 单引号内不处理任何转义（shell 语义，`'a\b'` 就是 `a\b`）；双引号内只把 `\"` 和 `\\`
     * 当转义，其余 `\x` 原样保留 —— 这样 Windows 版 Chrome 复制出来的 `"{\"a\":1}"` 能正确还原成 JSON。
     * 引号外的裸反斜杠转义下一个字符，这是 `'a'\''b'` 这种 shell 惯用写法（也是 CurlWriter 的输出）
     * 能被还原成 `a'b` 的关键：三段紧挨着、中间没有空白，因此合成同一个 token。
     */
    private fun tokenize(text: String): List<String> {
        // 先消掉续行：`\` + 换行（含 \r\n）等价于一个空格
        val flat = text.replace(Regex("""\\\r?\n"""), " ")
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        // started 与 current 是否为空不等价：`-d ''` 是一个合法的空 token，不能被当成「没有 token」
        var started = false
        var i = 0
        while (i < flat.length) {
            val c = flat[i]
            when {
                c == '\'' -> {
                    started = true
                    i++
                    while (i < flat.length && flat[i] != '\'') current.append(flat[i++])
                    i++ // 跳过闭合引号；引号未闭合时这里越界，外层 while 自然结束（尽力解析，不报错）
                }
                c == '"' -> {
                    started = true
                    i++
                    while (i < flat.length && flat[i] != '"') {
                        if (flat[i] == '\\' && i + 1 < flat.length && (flat[i + 1] == '"' || flat[i + 1] == '\\')) {
                            current.append(flat[i + 1])
                            i += 2
                        } else {
                            current.append(flat[i++])
                        }
                    }
                    i++
                }
                c == '\\' && i + 1 < flat.length -> {
                    started = true
                    current.append(flat[i + 1])
                    i += 2
                }
                c.isWhitespace() -> {
                    if (started) {
                        tokens += current.toString()
                        current.setLength(0)
                        started = false
                    }
                    i++
                }
                else -> {
                    started = true
                    current.append(c)
                    i++
                }
            }
        }
        if (started) tokens += current.toString()
        return tokens
    }
}
