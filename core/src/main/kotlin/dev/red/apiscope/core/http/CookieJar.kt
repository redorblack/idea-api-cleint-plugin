package dev.red.apiscope.core.http

/**
 * 按 host 归档 cookie 的容器：登录接口的 `Set-Cookie` 存进来，后续业务请求从这里取 `Cookie` 头
 *
 * 内部就是 `host -> "a=b; c=d"` 的扁平字符串，[snapshot] 直接就是这份内容 ——
 * 因为上层要把它持久化进 IDE 配置，重启后再通过构造参数 `initial` 灌回来。
 * 存结构化对象的话，持久化层得额外维护一套序列化格式，而扁平字符串本身就是最终要发出去的头值。
 *
 * 刻意的简化：**只做 host 后缀匹配，不实现 RFC 6265 的 domain-match**。
 * 归属 key 等于请求 host，或请求 host 以 `.key` 结尾即命中（`example.com` 的 cookie 会发给 `api.example.com`）。
 * 少了两件事：一是不校验 `Domain` 是否为 public suffix（服务端若下发 `Domain=com`，这里会把 cookie 发给所有 `.com` 域名，
 * 浏览器则会拒收）；二是 `Path` 解析出来但不参与匹配，`Path=/admin` 的 cookie 也会带给 `/api` 请求。
 * 代价只落在「多发了本不该发的 cookie」上：调试工具里 host 都是开发者自己填的，没有跨站攻击面，
 * 而漏发 cookie（导致接口 401）才是真正影响可用性的问题。过期时间同样不参与判断，见 [Cookies]。
 *
 * 不做线程安全：只在 UI 线程与单个请求回调里使用。也刻意不做全局单例 ——
 * 每个请求面板 / 环境各持一个实例，避免不同环境的会话互相串味。
 *
 * @author Red
 * @since 2026-08-14
 */
class CookieJar(initial: Map<String, String> = emptyMap()) {

    /** host（一律小写）-> 该 host 下的 `Cookie` 头值 */
    private val byHost = LinkedHashMap<String, String>()

    init {
        // 灌回来的内容先过一遍解析再拼回，把持久化过程中可能混进的空段、大小写差异normalize 掉
        initial.forEach { (host, header) ->
            byHost[normalize(host)] = Cookies.header(pairsOf(header).toList())
        }
    }

    /**
     * 解析并存入一批 `Set-Cookie`
     *
     * 归属 key 取 cookie 自己的 `Domain`（已去前导点），没有则退回请求的 [host]：
     * 服务端显式声明 `Domain` 就是要让整个域下的子域共用，不能钉死在当前 host 上。
     * 同一 key 下同名 cookie 后者覆盖前者（会话续期就是靠这个），不同名累加。
     */
    fun store(host: String, setCookieValues: List<String>) {
        val fallback = normalize(host)
        Cookies.parseSetCookie(setCookieValues).forEach { cookie ->
            val key = if (cookie.domain.isNotEmpty()) normalize(cookie.domain) else fallback
            val pairs = pairsOf(byHost[key])
            pairs[cookie.name] = cookie.value
            byHost[key] = Cookies.header(pairs.toList())
        }
    }

    /**
     * 取该 host 该带的 `Cookie` 头值，一条都没命中返回 null（而不是空字符串，调用方据此决定是否加这个 header）
     *
     * 多个 key 同时命中时（如 jar 里同时有 `example.com` 和 `api.example.com`），
     * 更精确的（key 更长的）后写入，同名 cookie 以它的值为准。
     */
    fun headerFor(host: String): String? {
        val target = normalize(host)
        val matched = byHost.keys.filter { it == target || target.endsWith(".$it") }
        if (matched.isEmpty()) return null

        val merged = LinkedHashMap<String, String>()
        // 按 key 长度升序合并：精确匹配最后写入，从而覆盖父域的同名 cookie
        matched.sortedBy { it.length }.forEach { merged.putAll(pairsOf(byHost[it])) }
        return Cookies.header(merged.toList())
    }

    fun clear() = byHost.clear()

    /** 当前状态的拷贝，调用方修改它不影响本实例 */
    fun snapshot(): Map<String, String> = LinkedHashMap(byHost)

    /** 域名不区分大小写，统一小写存取，免得 `API.example.com` 与 `api.example.com` 各存一份 */
    private fun normalize(host: String): String = host.trim().lowercase()

    /** 把 `a=b; c=d` 拆回有序的 name -> value；value 里允许有 `=`，只按第一个切 */
    private fun pairsOf(header: String?): LinkedHashMap<String, String> {
        val pairs = LinkedHashMap<String, String>()
        if (header.isNullOrBlank()) return pairs
        header.split(';').forEach { segment ->
            val eq = segment.indexOf('=')
            if (eq < 0) return@forEach
            val name = segment.substring(0, eq).trim()
            if (name.isNotEmpty()) pairs[name] = segment.substring(eq + 1).trim()
        }
        return pairs
    }
}
