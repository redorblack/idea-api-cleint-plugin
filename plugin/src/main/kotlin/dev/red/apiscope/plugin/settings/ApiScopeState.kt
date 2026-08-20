package dev.red.apiscope.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 面板状态的纯持久化载体：baseUrl 下拉历史 + 全局 header + 请求历史。
 *
 * `.apiscope.yml`（环境/Consul/网关配置文件形态）整套废弃后，
 * 状态改为面板上直接编辑、由 IDE 原生持久化，本类是唯一落盘点。
 *
 * **落在 `workspace.xml` 而不是自己的 `.idea/apiscope.xml`**：这里装的是
 * 全局 Header（`Authorization: Bearer …`）、变量（token）、自动收下的 Cookie（真实 session）、
 * 以及含请求体的历史 —— 全是**不能给别人看的东西**。而 `.idea/` 下除 `workspace.xml`
 * 之类以外的文件默认是**跟着 git 走**的，一次 `git add .` 就把个人凭据推上远端，
 * 别人 clone 下来还会带着你的 cookie 去打 dev 环境。
 * `workspace.xml` 是平台给「本机私有状态」准备的位置，天生不进版本库。
 *
 * 旧位置用 `deprecated = true` 保留：装过 0.2.0 的人升级后，原来 `.idea/apiscope.xml`
 * 里的内容会被读进来、然后写到新位置（旧文件建议自行删除并从 git 移除）。
 *
 * @author Red
 * @since 2026-08-14
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ApiScopeState",
    storages = [
        Storage(StoragePathMacros.WORKSPACE_FILE),
        Storage(value = "apiscope.xml", deprecated = true)
    ]
)
class ApiScopeState : PersistentStateComponent<ApiScopeState.Data> {

    private var data = Data()

    /**
     * 持久化的数据结构。
     *
     * 坑：必须是普通 class + `var` 字段 + 无参构造，**不能用 data class 的 `val`**——
     * IntelliJ 的 XmlSerializer 靠反射读写可变 bean 属性，只读属性（val）序列化时写不回去，
     * 重启 IDE 后这里的状态会被读成空。
     */
    class Data {
        var baseUrls: MutableList<String> = mutableListOf(DEFAULT_BASE_URL)
        var globalHeaders: String = ""
        var history: MutableList<HistoryEntry> = mutableListOf()

        /**
         * Postman 风格的 `{{变量}}` 定义原始文本，「变量」页签里按每行 `name = value` 编辑。
         * 这里只整段存字符串，不做解析——解析交给 core 的 KeyValueLines。
         * 存文本而不是结构化列表，是为了让「批量编辑」视图和表格视图共用同一份落盘数据。
         */
        var variables: String = ""

        /** 「Cookie」页签里手写的内容，形态同 [variables]（每行 `name=value`） */
        var cookieLines: String = ""

        /**
         * 自动收下来的 cookie：host -> `a=b; c=d`。
         *
         * 存扁平字符串而不是结构化对象：这份数据的唯一用途是拼回一个 `Cookie` 请求头，
         * 拆成对象再序列化只是给 XmlSerializer 多加几个必须是 var 的坑。
         */
        var cookieJar: MutableMap<String, String> = mutableMapOf()

        /** 是否自动携带上面收下来的 cookie。默认开：「先登录再调业务接口」是主场景 */
        var cookieJarEnabled: Boolean = true

        /**
         * 读超时（秒）。
         *
         * 默认 300 而不是 15：这是个 IDE 插件，最典型的用法就是**在 Controller 里打了断点再发请求**，
         * 断点一停，15 秒后底层连接就被掐断，等你单步完回到面板只看到「请求超时」，
         * 还会以为是接口写错了。慢 SQL、冷启动、跨 VPN 的 dev 环境同理。
         *
         * 「地址写错」这类要快速失败的场景由**连接**超时（3s，见 HttpExecutor）负责，
         * 与读超时无关；真的等太久还有「取消」按钮。
         */
        var readTimeoutSeconds: Int = 300
    }

    /**
     * 单条历史请求记录。同上，必须是普通 class + var + 无参构造，理由同 [Data]。
     */
    class HistoryEntry {
        var method: String = "GET"
        var baseUrl: String = ""
        var path: String = ""
        var headers: String = ""

        /**
         * body 类型：none / JSON / XML / text / form-urlencoded / multipart。
         * 默认 "JSON"——旧版本持久化的历史条目没有这个字段，反序列化时会落到这个默认值，
         * 保证向后兼容（不会把老的 XML body 当成表单发出去）。
         */
        var bodyType: String = "JSON"
        var body: String = ""

        /**
         * 历史弹窗列表展示用，如 "POST http://localhost:8080/order/list"。
         *
         * path 本身就是一整条 http 地址时不再拼 baseUrl —— cURL 导入走的正是这条路径
         * （整条 URL 放进路径框），拼起来会得到 `POST http://localhost:8080http://baidu.com/x`。
         */
        val label: String
            get() = if (path.startsWith("http://") || path.startsWith("https://")) {
                "$method $path"
            } else {
                "$method $baseUrl$path"
            }
    }

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
    }

    /** 供 UI 下拉框用；返回的是拷贝，调用方改不到内部状态 */
    fun baseUrls(): List<String> {
        val urls = data.baseUrls
        return if (urls.isEmpty()) listOf(DEFAULT_BASE_URL) else urls.toList()
    }

    /**
     * 记住一个新用过的 baseUrl。去重时忽略末尾斜杠差异（`.../8080` 与 `.../8080/` 算同一个），
     * 命中已有项则去重后把新值放到首位；超出上限截断队尾（越老的越先被挤掉）。
     */
    fun rememberBaseUrl(url: String) {
        if (url.isBlank()) return

        val urls = data.baseUrls
        val normalized = url.trimEnd('/')
        urls.removeAll { it.trimEnd('/') == normalized }
        urls.add(0, url)

        while (urls.size > MAX_BASE_URLS) {
            urls.removeAt(urls.size - 1)
        }
    }

    /** 读超时（秒），见 [Data.readTimeoutSeconds]；非正数当没配，回落默认值 */
    var readTimeoutSeconds: Int
        get() = data.readTimeoutSeconds.takeIf { it > 0 } ?: Data().readTimeoutSeconds
        set(value) {
            data.readTimeoutSeconds = value
        }

    var globalHeaders: String
        get() = data.globalHeaders
        set(value) {
            data.globalHeaders = value
        }

    var variables: String
        get() = data.variables
        set(value) {
            data.variables = value
        }

    var cookieLines: String
        get() = data.cookieLines
        set(value) {
            data.cookieLines = value
        }

    var cookieJarEnabled: Boolean
        get() = data.cookieJarEnabled
        set(value) {
            data.cookieJarEnabled = value
        }

    /** cookie jar 的落盘内容；每次收到 Set-Cookie 后由面板把 jar 快照整份写回 */
    var cookieJar: Map<String, String>
        get() = data.cookieJar.toMap()
        set(value) {
            data.cookieJar = LinkedHashMap(value)
        }

    fun history(): List<HistoryEntry> = data.history.toList()

    /**
     * 追加一条请求历史到最前面。与当前首位六个字段完全相同则不重复插入
     * （连点两次「发送」不该塞两条一样的记录）；超出上限截断队尾。
     */
    fun remember(
        method: String,
        baseUrl: String,
        path: String,
        headers: String,
        bodyType: String,
        body: String
    ) {
        val history = data.history
        val head = history.firstOrNull()
        val isSameAsHead = head != null &&
            head.method == method &&
            head.baseUrl == baseUrl &&
            head.path == path &&
            head.headers == headers &&
            head.bodyType == bodyType &&
            head.body == body
        if (isSameAsHead) return

        val entry = HistoryEntry().apply {
            this.method = method
            this.baseUrl = baseUrl
            this.path = path
            this.headers = headers
            this.bodyType = bodyType
            this.body = body
        }
        history.add(0, entry)

        while (history.size > MAX_HISTORY) {
            history.removeAt(history.size - 1)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:8080"
        const val MAX_BASE_URLS = 8
        const val MAX_HISTORY = 20

        fun getInstance(project: Project): ApiScopeState = project.service()
    }
}
