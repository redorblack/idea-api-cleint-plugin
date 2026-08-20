package dev.red.apiscope.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import dev.red.apiscope.core.endpoint.QueryParam
import dev.red.apiscope.core.kv.KeyValueLines
import dev.red.apiscope.core.kv.KvSeparator
import javax.swing.JTabbedPane

/**
 * 请求区的六个页签 —— 组装 + badge 计数
 *
 * 从 `RequestPanel` 拆出来是因为页签这块的代码量已经超过发送流程本身，
 * 混在一起会让「这次请求到底怎么拼出来的」淹没在布局代码里。
 *
 * 六个页签里五个是键值表格（[BulkEditPanel]），只有 Body 因为要装 JSON 全文而特殊。
 *
 * @author Red
 * @since 2026-08-14
 */
class RequestTabs(
    project: Project,
    /** 任何页签改了东西 —— 刷 badge、落盘 */
    private val onChange: () -> Unit,
    /**
     * **只有 Params 表提交时**才回调 —— 地址栏回写只能由它触发。
     *
     * 早先五个键值页签共用一个回调，于是改一个 header 也会重写地址栏；
     * 而回写走的是 Map，重名参数会被折叠 —— `?ids=1&ids=2&ids=3` 粘进来，
     * 去 Headers 页签敲一个字，地址栏就静默变成 `?ids=3`。
     */
    private val onParamsCommit: () -> Unit
) {

    val params = BulkEditPanel(
        KvSeparator.EQUALS,
        nameTitle = "参数名",
        valueTitle = "参数值",
        extraTitle = "类型"
    )

    val body = BodyEditor(project)

    val headers = BulkEditPanel(KvSeparator.COLON)

    val cookies = BulkEditPanel(
        KvSeparator.EQUALS,
        hintHtml = "只对本次请求生效；自动存下来的 Cookie 走「实际请求」页签查看"
    )

    val globalHeaders = BulkEditPanel(
        KvSeparator.COLON,
        hintHtml = "所有请求都自动带上；本次请求 Headers 里的同名项优先"
    )

    val variables = BulkEditPanel(
        KvSeparator.EQUALS,
        nameTitle = "name",
        valueTitle = "value",
        hintHtml = "在地址 / Headers / Body 里写 <b>{{name}}</b> 引用；" +
            "值要完整，如 <code>http://localhost:8080</code>（漏了 http:// 会发不出去）"
    )

    val component = JBTabbedPane().apply {
        // 必须留在默认的 WRAP：SCROLL_TAB_LAYOUT 会让平台的 DarculaTabbedPaneUI 换上私有的
        // WrappingLayout + 「更多页签」按钮，那条路径在 2026.x 上会把整排页签标题画不出来
        // （只剩选中项的蓝色下划线，因为下划线是外层 paint 单独画的）。
        // 宁可窄窗口里摞成两行，也不能没有标题。回归测试见 TabStripRenderTest
        tabLayoutPolicy = JTabbedPane.WRAP_TAB_LAYOUT
        addTab(PARAMS, params.component)
        addTab(BODY, body.component)
        addTab(HEADERS, headers.component)
        addTab(COOKIE, cookies.component)
        addTab(GLOBAL_HEADERS, globalHeaders.component)
        addTab(VARIABLES, variables.component)
    }

    init {
        params.onCommit = {
            onParamsCommit()
            onChange()
        }
        listOf(headers, cookies, globalHeaders, variables).forEach { it.onCommit = { onChange() } }
        body.onChange = { onChange() }
    }

    /**
     * 用源码里读到的参数填 Params 表。
     *
     * 「类型」列不进文本模型（它不落盘、也不该让用户改），单独通过 [KeyValueTable.setExtraValues] 灌进去。
     * 非必填参数默认**不勾选**：`required = false` 的参数多半是可选筛选条件，
     * 全勾上会让第一次发送就带一堆空值。
     */
    fun loadParams(queryParams: List<QueryParam>) {
        params.text = queryParams.joinToString("\n") { line(it, KvSeparator.EQUALS) }
        params.table.setExtraValues(queryParams.associate { it.name to it.type })
    }

    /**
     * 源码里读到的 `@RequestHeader` / `@CookieValue` 补进对应页签 —— **只补不覆盖**。
     *
     * Headers 页签里用户可能手写了 Authorization，Cookie 页签的内容还会落盘（跨会话留着），
     * 点一下 gutter 就把它们冲掉是无法接受的。所以按名字去重、只追加缺的那几行。
     */
    fun mergeHeaders(headerParams: List<QueryParam>) = merge(headers, headerParams, KvSeparator.COLON)

    fun mergeCookies(cookieParams: List<QueryParam>) = merge(cookies, cookieParams, KvSeparator.EQUALS)

    private fun merge(panel: BulkEditPanel, incoming: List<QueryParam>, separator: KvSeparator) {
        if (incoming.isEmpty()) return
        val existing = KeyValueLines.parse(panel.text, separator).map { it.name }.toSet()
        val added = incoming.filterNot { it.name in existing }.map { line(it, separator) }
        if (added.isEmpty()) return

        val kept = panel.text.lines().filter { it.isNotBlank() }
        panel.text = (kept + added).joinToString("\n")
    }

    /**
     * 一个参数写成一行文本。
     *
     * 非必填的加行首 `#`（等于表格里"没勾"）：`required = false` 的多半是可选筛选条件，
     * 全勾上会让第一次发送就带一堆空值。
     */
    private fun line(param: QueryParam, separator: KvSeparator): String {
        val glue = if (separator == KvSeparator.COLON) ": " else " = "
        val base = "${param.name}$glue${param.sampleValue}".trimEnd()
        val enabled = if (param.required) base else "# $base"
        return if (param.comment.isBlank()) enabled else "$enabled  # ${param.comment}"
    }

    /** 切到 Body 页签 —— gutter 填好一个带 body 的接口后直接露出招牌功能 */
    fun showBody() {
        component.selectedIndex = BODY_INDEX
    }

    /** Params 表里勾选的行，保序保重名 —— 回写地址栏用它 */
    fun paramPairs(): List<Pair<String, String>> = params.pairs()

    /** 地址栏里的 query 换成表格行（粘一整条 URL 进来时走这条） */
    fun setParamsFromUrl(pairs: List<Pair<String, String>>) {
        params.text = pairs.joinToString("\n") { (name, value) -> "$name = $value".trimEnd() }
    }

    /**
     * badge 数的是**解析后真会发出去的条数**（未勾选、注释、空名都不算），
     * 所以它同时兼任「我这行写对了吗」的即时反馈。
     *
     * **只给前三个页签加 badge**：`Cookie / 全局 / 变量` 是用户配好就几乎不动的常量，
     * 它们的计数不提供决策信息，却贡献三分之一的宽度抖动 —— 而 badge 一变宽就可能触发摞行，
     * 于是「在 Params 里敲出第二个参数」的瞬间整条标题栏跳一下，最不该抖的时刻在抖。
     */
    fun refreshBadges() {
        component.setTitleAt(0, badge(PARAMS, params.enabledCount()))
        component.setTitleAt(1, if (body.type == BodyEditor.NONE) BODY else badge(BODY, body.count()))
        component.setTitleAt(2, badge(HEADERS, headers.enabledCount()))
    }

    private fun badge(title: String, count: Int): String = if (count == 0) title else "$title $count"

    private companion object {
        /** Body 页签在 [component] 里的位置（Params=0, Body=1），见 addTab 顺序 */
        const val BODY_INDEX = 1

        const val PARAMS = "Params"
        const val BODY = "Body"
        const val HEADERS = "Headers"
        const val COOKIE = "Cookie"
        /**
         * 叫「全局」而不是「全局 Headers」：六个页签在 400px 宽的工具窗口里一行放不下就会摞成两行，
         * 而 WRAP 布局会把**含选中项的那一行整段挪到贴近内容处** —— 于是每切一次页签标题栏就重排一次。
         * 这个词省下 ~52px，刚好让不带 badge 的一行落在 400px 以内；
         * 顺带让中文项都是两字、英文项都是单词，节奏一致。
         */
        const val GLOBAL_HEADERS = "全局"
        const val VARIABLES = "变量"
    }
}
