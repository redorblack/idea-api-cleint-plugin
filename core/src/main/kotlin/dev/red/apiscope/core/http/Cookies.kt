package dev.red.apiscope.core.http

/**
 * 一条 cookie 的解析结果
 *
 * [expires] 是**原样保留的字符串**，不是时间戳：见 [Cookies.parseSetCookie] 里关于过期时间的取舍说明。
 *
 * @author Red
 * @since 2026-08-14
 */
data class Cookie(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "",
    val expires: String = "",
    val httpOnly: Boolean = false
)

/**
 * `Set-Cookie` 响应头的解析与 `Cookie` 请求头的拼接
 *
 * 典型场景：先调登录接口拿到 `Set-Cookie: JSESSIONID=xxx`，随后的业务请求自动带上它。
 * 这是「够用的 cookie 支持」，不是 RFC 6265 实现，两处刻意的简化：
 *
 * 1. **不解析过期时间**：`Expires` 原样存字符串，`Max-Age` 直接忽略。
 *    要正确处理，得同时支持 `Expires` 的 GMT 日期格式与 `Max-Age` 的相对秒数、
 *    再算本地时区与时钟偏差 —— 代码量会超过整个 cookie 功能本身。
 *    代价：长期使用的会话可能一直带着已过期的 cookie 发出去，服务端会当它无效；
 *    靠上层面板的「清空 Cookie」动作（[CookieJar.clear]）兜底。
 * 2. **`Secure` / `HttpOnly` 不影响是否发送**：`Secure` 完全忽略，`HttpOnly` 只解析出来供界面展示。
 *    浏览器用它们防脚本读取和明文外泄，而这里是开发者自己手动发的调试请求，没有这层威胁模型。
 *    代价：http 明文请求上也会带 `Secure` cookie，与浏览器行为不一致。
 *
 * 解析全程容错优先：认不出的段静默跳过，不因为服务端多下发一个新 attribute 就让整个响应处理失败。
 *
 * @author Red
 * @since 2026-08-14
 */
object Cookies {

    private const val ATTR_DOMAIN = "domain"
    private const val ATTR_PATH = "path"
    private const val ATTR_EXPIRES = "expires"
    private const val ATTR_HTTP_ONLY = "httponly"

    /**
     * 解析响应里的 `Set-Cookie` 头 —— 一个响应可能下发多条，所以入参是 [List]
     *
     * 每条形如 `name=value; Domain=.example.com; Path=/; Expires=...; HttpOnly; Secure`：
     * 按 `;` 切段，第一段是 name=value，其余是 attribute（名字大小写不敏感）。
     * 只认 `Domain` / `Path` / `Expires` / `HttpOnly` 四个，`Max-Age` / `Secure` / `SameSite`
     * 等一律忽略（原因见类注释）。
     *
     * name=value 只按**第一个** `=` 切分，因为 base64 编码的 session 值里带 `=` 很常见（`a=b=c` → value 是 `b=c`）；
     * value 允许为空（`a=; Path=/` 是服务端删 cookie 的常见写法，要能解析出来）。
     * 第一段没有 `=`、或名字为空 → 整条丢弃：没名字的 cookie 发不出去，留着只会污染 jar。
     */
    fun parseSetCookie(values: List<String>): List<Cookie> = values.mapNotNull { parseOne(it) }

    /** 拼成请求用的 `Cookie` 头值：`a=b; c=d`。名字空白的项跳过，空列表得到空字符串 */
    fun header(pairs: List<Pair<String, String>>): String = pairs
        .filter { it.first.isNotBlank() }
        .joinToString("; ") { "${it.first}=${it.second}" }

    private fun parseOne(raw: String): Cookie? {
        val segments = raw.split(';')
        val first = segments.first()
        val eq = first.indexOf('=')
        if (eq < 0) return null
        val name = first.substring(0, eq).trim()
        if (name.isEmpty()) return null

        var cookie = Cookie(name = name, value = first.substring(eq + 1).trim())
        for (i in 1 until segments.size) {
            cookie = applyAttribute(cookie, segments[i])
        }
        return cookie
    }

    private fun applyAttribute(cookie: Cookie, segment: String): Cookie {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return cookie
        val eq = trimmed.indexOf('=')
        // 无值的 flag（HttpOnly / Secure）没有 '='，整段就是 attribute 名
        val attr = (if (eq < 0) trimmed else trimmed.substring(0, eq)).trim().lowercase()
        val value = if (eq < 0) "" else trimmed.substring(eq + 1).trim()
        return when (attr) {
            // 前导点是 RFC 2109 的老写法，与不带点等价，统一去掉便于 CookieJar 按 host 匹配
            ATTR_DOMAIN -> cookie.copy(domain = value.removePrefix("."))
            ATTR_PATH -> cookie.copy(path = value)
            ATTR_EXPIRES -> cookie.copy(expires = value)
            ATTR_HTTP_ONLY -> cookie.copy(httpOnly = true)
            else -> cookie
        }
    }
}
