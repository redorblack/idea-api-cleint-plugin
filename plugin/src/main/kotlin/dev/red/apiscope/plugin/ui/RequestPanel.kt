package dev.red.apiscope.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import dev.red.apiscope.core.endpoint.EndpointDescriptor
import dev.red.apiscope.core.endpoint.QueryStrings
import dev.red.apiscope.core.endpoint.UrlEscaper
import dev.red.apiscope.core.export.CurlReader
import dev.red.apiscope.core.export.CurlWriter
import dev.red.apiscope.core.http.ApiRequest
import dev.red.apiscope.core.http.CookieJar
import dev.red.apiscope.core.http.Cookies
import dev.red.apiscope.core.http.HttpExecutor
import dev.red.apiscope.core.http.MultipartPart
import dev.red.apiscope.core.http.RequestBody
import dev.red.apiscope.core.vars.Interpolator
import dev.red.apiscope.plugin.settings.ApiScopeState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.URI
import java.time.Duration
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * 请求面板 —— 编排地址栏、六个请求页签、响应区，以及发送流程本身
 *
 * 布局与交互上几条刻意的取舍：
 * - **上下分栏**（请求在上、响应在下）。工具窗口常停在右侧、只有 ~400px 宽，
 *   左右分栏会把响应挤成一条缝，连状态码都看不全
 * - **地址框是 query 的唯一真源**，Params 表是它的结构化视图。粘一整条 URL 进来是最高频动作，
 *   不能因为多了张表就失效；表格只在**提交单元格**时回写地址框，不边敲边写（否则地址栏一直抖）
 * - 请求中「发送」变「取消」，点一下真中断底层连接 —— 地址写错时等超时最磨人
 * - 未定义的 `{{变量}}` **拦住不发**：地址悄悄少一段比直接报错难查得多
 *
 * @author Red
 * @since 2026-08-14
 */
class RequestPanel(project: Project) : JPanel(BorderLayout()) {

    private val state = ApiScopeState.getInstance(project)
    /** 改了读超时就换一个（HttpClient 的超时是建好之后不能改的），见 [editTimeout] */
    private var executor = HttpExecutor(readTimeout = Duration.ofSeconds(state.readTimeoutSeconds.toLong()))
    private val cookieJar = CookieJar(state.cookieJar)

    private val tabs = RequestTabs(
        project,
        onChange = { onTabsChanged() },
        onParamsCommit = { syncPathFromParams() }
    )
    private val responseView = ResponseView(project)
    private val urlBar = UrlBar(state.baseUrls(), onSend = { onSendClicked() })

    private val actions = RequestActions(
        project,
        curlOf = { curlOfCurrentRequest() },
        onImport = { importCurl(it) },
        responseOf = { lastResponseBody },
        onClearCookies = { clearCookies() },
        onRefill = { refillFromSource() },
        onTimeout = { editTimeout() },
        onNotice = { showMessage(it, UIUtil.getContextHelpForeground()) }
    )

    /** 进行中的请求；非 null 表示「发送」按钮此刻是「取消」 */
    private var pending: HttpExecutor.Exchange? = null

    /** 地址框 ⇄ Params 表互相回写时的防环标志 */
    private var syncing: Boolean = false

    private var lastResponseBody: String = ""

    /** 最近一次从源码填进来的接口，以及当时填的内容 —— 用来判断用户后来改过没有 */
    private var loaded: EndpointDescriptor? = null
    private var filledBody: String = ""
    private var filledParams: String = ""

    /** 发送前的输入问题（未定义变量、地址不完整），底部左侧 */
    private val messageLabel = UiText.small(" ")

    private val endpointLabel = UiText.small(" ")

    init {
        border = JBUI.Borders.empty(8)
        add(urlBar.component, BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)

        tabs.globalHeaders.text = state.globalHeaders
        tabs.variables.text = state.variables
        tabs.cookies.text = state.cookieLines
        tabs.refreshBadges()

        urlBar.onPathEdited = { syncParamsFromPath() }

        // ⌘↩ / Ctrl+↩ 直接发送：body 敲完不用摸鼠标。WHEN_IN_FOCUSED_WINDOW 让焦点在任何输入框里都生效
        listOf(InputEvent.META_DOWN_MASK, InputEvent.CTRL_DOWN_MASK).forEach { modifier ->
            registerKeyboardAction(
                { onSendClicked() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier),
                JComponent.WHEN_IN_FOCUSED_WINDOW
            )
        }
    }

    /**
     * gutter 图标点进来时填充。
     *
     * **同一个接口 + 用户已经手改过 body/参数 → 只把窗口叫到前面，什么都不覆盖。**
     * 真实节奏是「点 gutter → 把生成的全字段 body 填成想要的值 → 发现 DTO 少个字段 →
     * 回编辑器改代码 → 再点一下 gutter」，而历史只在**发送时**才写入，
     * 所以无条件覆盖会让十分钟的手填凭空消失、且无处可恢复。
     * 要按最新源码重填，走 `⋮ → 从源码重新填充`（[refillFromSource]）。
     */
    fun load(descriptor: EndpointDescriptor) {
        if (loaded?.displayName == descriptor.displayName && hasManualEdits()) {
            showMessage(
                "已是当前接口，保留你填的内容 —— 要按最新源码重填走「⋮ → 从源码重新填充」",
                UIUtil.getContextHelpForeground()
            )
            return
        }
        fill(descriptor)
    }

    /** `⋮` 菜单里的「从源码重新填充」：明确要求时才丢掉手填内容 */
    fun refillFromSource() {
        val descriptor = loaded
        if (descriptor == null) {
            showMessage("还没从源码填充过 —— 在 Controller 方法旁点 ➜", UIUtil.getContextHelpForeground())
            return
        }
        fill(descriptor)
    }

    private fun fill(descriptor: EndpointDescriptor) {
        // 源码里读出来的本地地址只是个候选项；只有用户还停在那个"猜的" 8080 默认值上才替他选中
        descriptor.suggestedBaseUrl?.let { suggested ->
            urlBar.offerBaseUrl(suggested)
            if (urlBar.baseUrl == ApiScopeState.DEFAULT_BASE_URL) urlBar.baseUrl = suggested
        }

        urlBar.method = descriptor.httpMethod.uppercase()
        withoutSync { urlBar.path = descriptor.path }
        tabs.loadParams(descriptor.queryParams)
        tabs.mergeHeaders(descriptor.headers)
        tabs.mergeCookies(descriptor.cookies)
        syncPathFromParams()

        tabs.body.type = if (descriptor.bodyJson != null) BodyEditor.JSON else BodyEditor.NONE
        tabs.body.text = descriptor.bodyJson ?: ""

        responseView.clear()
        lastResponseBody = ""
        clearMessage()
        showEndpoint(descriptor.displayName)
        tabs.refreshBadges()

        // 招牌功能是「从源码把 body 填好」—— POST 类接口（有 body、Params 表本就空）直接落在 Body 页签，
        // 别让递归展开的全字段 JSON 藏在相邻页签后、只靠一个 badge 数字提示
        if (descriptor.bodyJson != null && descriptor.queryParams.isEmpty()) {
            tabs.showBody()
        }

        loaded = descriptor
        filledBody = tabs.body.text
        filledParams = tabs.params.text
    }

    /** 填进去之后有没有被人改过 —— 只比对我们自己填的那两样 */
    private fun hasManualEdits(): Boolean =
        tabs.body.text != filledBody || tabs.params.text != filledParams

    // ————————————————————————— 布局 —————————————————————————

    /** 上下分栏：垂直分割才能在窄工具窗口里同时看清请求和响应 */
    private fun buildCenter(): JBSplitter = JBSplitter(true, 0.55f).apply {
        // key 带 .v 后缀：早期版本是左右分栏，沿用同一个 key 会读到那时候存的水平比例
        splitterProportionKey = "ApiScope.request.response.v"
        firstComponent = tabs.component
        secondComponent = responseView.component
    }

    // 消息放 CENTER 而不是 WEST：BorderLayout 里 WEST 和 EAST 都各取自身 preferred 宽，
    // 窄工具窗口下「未定义变量…」这类长文案会和右侧的接口全限定名相互覆盖糊成一团。
    // CENTER 只拿剩余空间、超了走省略号（tooltip 已兜住全文），endpoint 常是短的 `类#方法`，两者不再打架
    private fun buildFooter(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        add(messageLabel, BorderLayout.CENTER)
        add(endpointLabel, BorderLayout.EAST)
    }

    // ————————————————————————— 地址框 ⇄ Params 表 —————————————————————————

    /** 地址栏回写不在这里做 —— 它只由 Params 表自己的提交触发（见 [RequestTabs] 的 onParamsCommit） */
    private fun onTabsChanged() {
        persistTabs()
        tabs.refreshBadges()
    }

    /** 地址框改了 → 重新拆 query 灌进表格 */
    private fun syncParamsFromPath() {
        if (syncing) return
        withoutSync { tabs.setParamsFromUrl(QueryStrings.split(urlBar.path).params) }
        tabs.refreshBadges()
    }

    /**
     * 表格提交了 → 用表格重建 query，替换地址框 `?` 之后的部分。
     *
     * 用 [RequestTabs.paramPairs] 而不是 `toMap()`：重名参数（`ids=1&ids=2`）不能被折叠。
     * 拼出来和现有 query 一样就不动输入框，免得光标乱跳。
     */
    private fun syncPathFromParams() {
        if (syncing) return
        val base = QueryStrings.split(urlBar.path).base
        val query = tabs.paramPairs().joinToString("&") { (name, value) -> "$name=$value" }
        val rebuilt = if (query.isEmpty()) base else "$base?$query"
        if (rebuilt == urlBar.path) return
        withoutSync { urlBar.path = rebuilt }
    }

    private inline fun withoutSync(block: () -> Unit) {
        syncing = true
        try {
            block()
        } finally {
            syncing = false
        }
    }

    private fun persistTabs() {
        state.globalHeaders = tabs.globalHeaders.text
        state.variables = tabs.variables.text
        state.cookieLines = tabs.cookies.text
    }

    // ————————————————————————— 发送 / 取消 —————————————————————————

    private fun onSendClicked() {
        val running = pending
        if (running != null) {
            running.cancel()
            return
        }
        val request = buildRequest() ?: return
        clearMessage()
        // 「我发出去过的配置一定存住」—— 文本视图里粘的东西如果还没失焦提交，这里补一次
        persistTabs()
        rememberRequest()
        responseView.showRequest(CurlWriter.write(request))
        startExchange(request)
    }

    /**
     * 面板内容 → 可发送的请求；有输入问题时报到底部并返回 null。
     *
     * 「导出为 cURL」也走这条，所以它导出的一定是**真正会发出去的东西**（插值后、合并全局
     * Headers 与 Cookie 之后），而不是带 `{{var}}` 的模板 —— 贴给别人要能直接跑。
     */
    private fun buildRequest(): ApiRequest? {
        val variables = tabs.variables.toMap()
        val rawUrl = urlBar.resolveUrl()

        val unresolved = listOf(rawUrl, tabs.headers.text, tabs.globalHeaders.text, tabs.body.text)
            .flatMap { Interpolator.missing(it, variables) }
            .distinct()
        if (unresolved.isNotEmpty()) {
            showMessage("未定义变量 ${unresolved.joinToString("、") { "{{$it}}" }} —— 在「变量」页签补上", NamedColorUtil.getErrorForeground())
            return null
        }

        val interpolated = Interpolator.apply(rawUrl, variables)
        if (!interpolated.contains("://")) {
            showMessage("地址不完整：Base URL 形如 http://localhost:8080", NamedColorUtil.getErrorForeground())
            return null
        }

        // 路径占位符和未定义变量同一个道理：地址悄悄少一段比直接报错难查得多。
        // 只查 `?` 之前：query 的值里出现大括号是合法的（比如整段 JSON 当参数值传）
        val placeholders = PATH_PLACEHOLDER.findAll(interpolated.substringBefore('?'))
            .map { it.value }
            .distinct()
            .toList()
        if (placeholders.isNotEmpty()) {
            showMessage("路径里 ${placeholders.joinToString("、")} 还没填 —— 直接在地址栏改掉", NamedColorUtil.getErrorForeground())
            return null
        }

        // 转义放在最后一步：表格和地址栏里始终是用户敲的原文，只有真正要发的这一份被编码
        val url = UrlEscaper.escape(interpolated)

        val request = ApiRequest(
            method = urlBar.method,
            url = url,
            headers = interpolated(tabs.headers.toMap(), variables),
            body = tabs.body.build(variables)
        )
            // 越往后优先级越低：本次请求里显式写的 header 压过全局默认值，全局又压过自动 cookie
            .withHeaders(interpolated(tabs.globalHeaders.toMap(), variables))
            .withHeaders(cookieHeader(url, variables))
        return request
    }

    private fun interpolated(headers: Map<String, String>, variables: Map<String, String>): Map<String, String> =
        headers.mapValues { (_, value) -> Interpolator.apply(value, variables) }

    /**
     * 合成一个 `Cookie` 头：手写的（Cookie 页签）+ 自动收下的（jar），同名以手写为准。
     *
     * 拼成一个头而不是多个：HTTP 规范里 `Cookie` 只应出现一次，多个头的行为各家服务端不一致。
     */
    private fun cookieHeader(url: String, variables: Map<String, String>): Map<String, String> {
        val manual = tabs.cookies.toMap()
            .map { (name, value) -> name to Interpolator.apply(value, variables) }
        val automatic = if (state.cookieJarEnabled) pairsOf(cookieJar.headerFor(hostOf(url))) else emptyList()

        val merged = LinkedHashMap<String, String>()
        automatic.forEach { (name, value) -> merged[name] = value }
        manual.forEach { (name, value) -> merged[name] = value }
        if (merged.isEmpty()) return emptyMap()
        return mapOf("Cookie" to Cookies.header(merged.toList()))
    }

    private fun pairsOf(header: String?): List<Pair<String, String>> =
        header.orEmpty()
            .split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }

    private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun startExchange(request: ApiRequest) {
        // response future 约定只会正常完成（取消/异常都映射成失败响应），所以这里不需要 catch
        val exchange = executor.start(request)
        pending = exchange
        urlBar.busy = true
        responseView.showBusy()

        exchange.response.whenComplete { response, _ ->
            SwingUtilities.invokeLater {
                // 上一次请求慢一步回来时不能覆盖新一次的结果
                if (pending !== exchange) return@invokeLater
                pending = null
                urlBar.busy = false
                lastResponseBody = response.body
                responseView.show(response)
                storeCookies(request.url, response.headers)
            }
        }
    }

    private fun storeCookies(url: String, headers: Map<String, List<String>>) {
        if (!state.cookieJarEnabled) return
        val setCookie = headers.entries
            .firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }
            ?.value
            ?: return
        cookieJar.store(hostOf(url), setCookie)
        state.cookieJar = cookieJar.snapshot()
    }

    /**
     * `⋮ → 读超时…`
     *
     * 默认 300 秒是为了「在 Controller 里打了断点再发请求」这个最典型的用法 ——
     * 15 秒的旧默认值会在你还在单步的时候就把连接掐断。真的等太久有「取消」按钮。
     */
    private fun editTimeout() {
        val input = Messages.showInputDialog(
            this,
            "读超时（秒）。断点调试建议留大一点；「地址写错」由 3 秒的连接超时负责，与这里无关。",
            "ApiScope 读超时",
            null,
            state.readTimeoutSeconds.toString(),
            null
        ) ?: return

        val seconds = input.trim().toIntOrNull()
        if (seconds == null || seconds <= 0) {
            showMessage("读超时要填一个正整数（秒）", NamedColorUtil.getErrorForeground())
            return
        }
        state.readTimeoutSeconds = seconds
        executor = HttpExecutor(readTimeout = Duration.ofSeconds(seconds.toLong()))
        showMessage("读超时已设为 ${seconds}s", UIUtil.getContextHelpForeground())
    }

    private fun clearCookies() {
        cookieJar.clear()
        state.cookieJar = cookieJar.snapshot()
        showMessage("已清空自动收下的 Cookie", UIUtil.getContextHelpForeground())
    }

    private fun rememberRequest() {
        val baseUrl = urlBar.baseUrl
        state.rememberBaseUrl(baseUrl)
        urlBar.refreshBaseUrls(state.baseUrls(), baseUrl)
        state.remember(urlBar.method, baseUrl, urlBar.path, tabs.headers.text, tabs.body.type, tabs.body.text)
    }

    // ————————————————————————— 导入 / 导出 —————————————————————————

    private fun curlOfCurrentRequest(): String? = buildRequest()?.let { CurlWriter.write(it) }

    /**
     * 一条 cURL 灌进面板。
     *
     * 整条 URL 直接放进**路径框**（而不是拆成 Base URL + path）：导入的地址往往和当前
     * Base URL 无关，硬拆会让人看不出到底发去哪里。路径框本来就支持整条 `http://` 地址。
     */
    private fun importCurl(text: String) {
        val parsed = CurlReader.read(text)
        if (parsed == null) {
            showMessage("剪贴板里没有可识别的 cURL 命令", NamedColorUtil.getErrorForeground())
            return
        }

        urlBar.method = parsed.method
        withoutSync { urlBar.path = parsed.url }
        tabs.setParamsFromUrl(QueryStrings.split(parsed.url).params)
        tabs.headers.text = parsed.headers.joinToString("\n") { (name, value) -> "$name: $value" }
        applyImportedBody(parsed.body)

        showEndpoint("来自 cURL")
        tabs.refreshBadges()
        if (parsed.unsupported.isEmpty()) {
            clearMessage()
        } else {
            // 导入其实成功了，只是忽略了几个不支持的 flag —— 用提示色 + 正向措辞，
            // 别和「剪贴板里没有 cURL」共用红色，那会让人以为整条没进来、重复粘贴
            showMessage(
                "已导入（忽略未支持的参数：${parsed.unsupported.joinToString(" ")}）",
                UIUtil.getContextHelpForeground()
            )
        }
    }

    /** 类型要先设再灌内容：[BodyEditor] 按类型分了两套缓冲，顺序反了会写进另一套里 */
    private fun applyImportedBody(body: RequestBody) {
        when (body) {
            is RequestBody.None -> {
                tabs.body.type = BodyEditor.NONE
                tabs.body.text = ""
            }

            is RequestBody.Text -> {
                tabs.body.type = if (body.contentType.contains("xml")) BodyEditor.XML else BodyEditor.JSON
                tabs.body.text = body.content
            }

            is RequestBody.Form -> {
                tabs.body.type = BodyEditor.FORM
                tabs.body.text = body.fields.joinToString("\n") { (name, value) -> "$name = $value" }
            }

            is RequestBody.Multipart -> {
                tabs.body.type = BodyEditor.MULTIPART
                tabs.body.text = body.parts.joinToString("\n") { part ->
                    when (part) {
                        is MultipartPart.Field -> "${part.name} = ${part.value}"
                        is MultipartPart.FileRef -> "${part.name} = ${BodyEditor.FILE_MARK}${part.path}"
                    }
                }
            }
        }
    }

    // ————————————————————————— 工具窗口标题栏 —————————————————————————

    /**
     * 标题栏右侧那个「历史」图标。
     *
     * 放标题栏而不是地址栏：地址栏 400px 宽时固定开销就有 392px，路径框会被挤到 0 宽 ——
     * 而「历史」是一天点几次的次要命令，本来就该待在工具窗口标题栏（平台的标准位置），
     * 顺带还能被 `Find Action` 搜到。
     */
    fun titleActions(): List<AnAction> = listOf(historyAction)

    /** 标题栏齿轮菜单里的那几项，见 [RequestActions.asActionGroup] */
    fun gearActions(): ActionGroup = actions.asActionGroup()

    private val historyAction = object : AnAction("最近请求", "最近发过的请求", AllIcons.Vcs.History) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(event: AnActionEvent) = showHistory(event)
    }

    private fun showHistory(event: AnActionEvent) {
        val entries = state.history()
        if (entries.isEmpty()) {
            showMessage("还没有历史请求", UIUtil.getContextHelpForeground())
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("最近请求")
            .setRenderer(SimpleListCellRenderer.create<ApiScopeState.HistoryEntry>("") { it.label })
            .setItemChosenCallback { restore(it) }
            .createPopup()
            .showInBestPositionFor(event.dataContext)
    }

    private fun restore(entry: ApiScopeState.HistoryEntry) {
        urlBar.method = entry.method
        urlBar.baseUrl = entry.baseUrl
        withoutSync { urlBar.path = entry.path }
        tabs.setParamsFromUrl(QueryStrings.split(entry.path).params)
        tabs.headers.text = entry.headers
        tabs.body.type = entry.bodyType
        tabs.body.text = entry.body
        showEndpoint("来自历史")
        clearMessage()
        tabs.refreshBadges()
    }

    // ————————————————————————— 状态显示 —————————————————————————

    private fun showMessage(text: String, color: Color) {
        messageLabel.text = text
        // 底部这行宽度不够时会被省略号截断，tooltip 是唯一能把话说全的地方
        messageLabel.toolTipText = text.takeIf { it.isNotBlank() }
        messageLabel.foreground = color
    }

    /** 和 [showMessage] 同理：接口全限定名在窄窗口里会被截断，tooltip 兜住完整名字 */
    private fun showEndpoint(text: String) {
        endpointLabel.text = text
        endpointLabel.toolTipText = text.takeIf { it.isNotBlank() }
    }

    private fun clearMessage() = showMessage(" ", UIUtil.getContextHelpForeground())

    private companion object {
        /** `/user/{id}` 里没填的路径占位符。只用于 `?` 之前的路径段 */
        val PATH_PLACEHOLDER = Regex("\\{[^/{}]+}")
    }
}
