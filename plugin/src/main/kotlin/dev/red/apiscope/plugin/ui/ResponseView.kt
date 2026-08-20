package dev.red.apiscope.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import dev.red.apiscope.core.http.ApiResponse
import dev.red.apiscope.core.http.Cookies
import dev.red.apiscope.core.json.JsonPrinter
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * 响应区 —— 状态条 + `Body / Headers / Cookies / 实际请求` 四个页签
 *
 * 四个页签各自解决一类「查不出来」的问题：
 * - **Headers**：跨域、鉴权、缓存、下载文件名的证据全在响应头里，只给 body 等于藏起一半线索
 * - **Cookies**：登录接口到底给没给 session，一眼看到
 * - **实际请求**：插值后、合并全局 Headers 和 Cookie 之后**真正发出去**的东西。
 *   「怎么和我想的不一样」的终极答案。内容直接用导出的 cURL，一份代码两个用途
 *
 * 状态条带响应大小（`200 · 128ms · 899B`）：一眼能看出「接口通了但返回是空壳」。
 *
 * @author Red
 * @since 2026-08-14
 */
class ResponseView(project: Project) {

    private val bodyView = JsonTextView(project, placeholder = "点「发送」后这里显示响应")
    private val headersArea = readOnlyArea()
    private val cookiesArea = readOnlyArea()
    private val requestArea = readOnlyArea()

    private val rawCheckBox = JBCheckBox("原始文本").apply {
        toolTipText = "不美化，显示服务端原样返回的文本"
        font = JBUI.Fonts.smallFont()
    }

    /** 状态码 / 耗时 / 大小 / 类型，或失败原因 */
    private val statusLabel = UiText.small(" ")

    private val tabs = JBTabbedPane().apply {
        // 同 RequestTabs：SCROLL_TAB_LAYOUT 在 2026.x 上会让页签标题整排不绘制，必须留在 WRAP
        tabLayoutPolicy = JTabbedPane.WRAP_TAB_LAYOUT
        addTab(BODY, bodyView.component)
        addTab(HEADERS, JBScrollPane(headersArea))
        addTab(COOKIES, JBScrollPane(cookiesArea))
        addTab(REQUEST, JBScrollPane(requestArea))
    }

    /** 未美化的响应体原文；「原始」勾选框来回切要靠它，不能只留美化后的结果 */
    private var rawBody: String = ""
    private var isJson: Boolean = false

    val component: JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(4)
                // 不放「Response」分区标题：四个页签加上下分栏已经说明这是响应区，
                // 而它是整个中文界面里唯一一个裸英文标题，还白占 ~70px
                add(rawCheckBox, BorderLayout.WEST)
                add(statusLabel, BorderLayout.EAST)
            },
            BorderLayout.NORTH
        )
        add(tabs, BorderLayout.CENTER)
    }

    init {
        rawCheckBox.addActionListener { renderBody() }
        // 它只管 Body 页签，摆在页签条外面会让人以为对四个页签都生效 —— 那就跟着页签露/藏
        tabs.addChangeListener { rawCheckBox.isVisible = tabs.selectedIndex == BODY_INDEX }
    }

    fun showBusy() {
        statusLabel.text = "请求中…"
        statusLabel.foreground = UIUtil.getContextHelpForeground()
    }

    /** 发送时就把「实际请求」填上，不等响应回来 —— 请求超时/连不上时它恰恰最有用 */
    fun showRequest(dump: String) {
        requestArea.text = dump
        requestArea.caretPosition = 0
    }

    fun clear() {
        clearContent()
        requestArea.text = ""
        statusLabel.text = " "
        statusLabel.toolTipText = null
        statusLabel.foreground = UIUtil.getContextHelpForeground()
    }

    fun show(response: ApiResponse) {
        if (response.isNetworkFailure) {
            val cancelled = response.error == CANCELLED
            statusLabel.text = "${response.error} · ${response.elapsedMs}ms"
            statusLabel.toolTipText = null
            statusLabel.foreground =
                if (cancelled) UIUtil.getContextHelpForeground() else NamedColorUtil.getErrorForeground()
            // 取消时留着上一次的响应，用户往往只是手快点错了
            if (!cancelled) clearContent()
            return
        }

        val bytes = response.body.toByteArray(Charsets.UTF_8).size
        val oversized = bytes > MAX_PRETTY_BYTES
        val mime = response.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

        statusLabel.text = listOfNotNull(
            "HTTP ${response.status}",
            "${response.elapsedMs}ms",
            formatSize(bytes),
            if (oversized) "已跳过美化" else null
        ).joinToString(" · ")
        // Content-Type 只进 tooltip：`application/vnd.api+json` 这种能占掉状态条一半宽度，
        // 而它在 Headers 页签里本来就看得到，不值得挤掉状态码和耗时
        statusLabel.toolTipText = mime
        statusLabel.foreground = statusColor(response.status)

        rawBody = response.body
        if (response.body.isEmpty()) {
            // 204 / 空 body 的成功响应：Body 区若回落到占位符，就和「还没发过」长得一模一样。
            // 明写「（空响应体）」，让「接口通了但没返回内容」一眼可辨（状态条那句 0B 是另一处佐证）
            isJson = false
            bodyView.showAs(false)
            bodyView.text = EMPTY_BODY
            rawCheckBox.isEnabled = false
        } else {
            // 大响应不上 JSON 高亮也不美化：单遍缩进本身很快，卡的是 Editor 的高亮和折叠 pass
            isJson = !oversized && mime?.contains("json", ignoreCase = true) == true
            bodyView.showAs(isJson)
            rawCheckBox.isEnabled = !oversized
            renderBody(skipPretty = oversized)
        }

        showHeaders(response.headers)
        showCookies(response.headers)
    }

    private fun clearContent() {
        rawBody = ""
        bodyView.text = ""
        headersArea.text = ""
        cookiesArea.text = ""
        tabs.setTitleAt(HEADERS_INDEX, HEADERS)
        tabs.setTitleAt(COOKIES_INDEX, COOKIES)
    }

    private fun renderBody(skipPretty: Boolean = false) {
        bodyView.text = if (skipPretty || rawCheckBox.isSelected) rawBody else JsonPrinter.pretty(rawBody)
    }

    private fun showHeaders(headers: Map<String, List<String>>) {
        headersArea.text = headers.entries
            .sortedBy { it.key }
            .joinToString("\n") { (name, values) -> "$name: ${values.joinToString(", ")}" }
        headersArea.caretPosition = 0
        tabs.setTitleAt(HEADERS_INDEX, badge(HEADERS, headers.size))
    }

    private fun showCookies(headers: Map<String, List<String>>) {
        val setCookie = headers.entries
            .firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }
            ?.value
            .orEmpty()
        val cookies = Cookies.parseSetCookie(setCookie)
        cookiesArea.text = cookies.joinToString("\n") { cookie ->
            listOfNotNull(
                "${cookie.name} = ${cookie.value}",
                cookie.domain.takeIf { it.isNotEmpty() }?.let { "Domain=$it" },
                cookie.path.takeIf { it.isNotEmpty() }?.let { "Path=$it" },
                cookie.expires.takeIf { it.isNotEmpty() }?.let { "Expires=$it" },
                "HttpOnly".takeIf { cookie.httpOnly }
            ).joinToString("  ")
        }
        cookiesArea.caretPosition = 0
        tabs.setTitleAt(COOKIES_INDEX, badge(COOKIES, cookies.size))
    }

    private fun badge(title: String, count: Int): String = if (count == 0) title else "$title $count"

    private fun formatSize(bytes: Int): String =
        if (bytes < 1024) "${bytes}B" else "%.1fKB".format(bytes / 1024.0)

    /**
     * 两档就够：2xx/3xx 普通前景，4xx/5xx 走主题的错误色。
     *
     * 原先 3xx 和 4xx 一起用 `JBColor.ORANGE`，两个问题：重定向本来就不是错误，不值得占一个颜色；
     * 而 `JBColor.ORANGE` 是接近纯橙的 legacy 常量，打在亮色主题的近白背景上对比度约 2.1:1
     * （WCAG AA 要 4.5:1），再叠上 11px 小字就是"能看见但读不清"，还不走主题槽位、自定义主题改不动。
     * 「4xx 是自己写错、5xx 去看服务端」这个区分靠状态码数字本身就传达了。
     */
    private fun statusColor(status: Int): Color = when (status) {
        in 200..399 -> JBColor.foreground()
        else -> NamedColorUtil.getErrorForeground()
    }

    private fun readOnlyArea() = UiText.monoArea().apply { isEditable = false }

    private companion object {
        const val BODY = "Body"
        const val HEADERS = "Headers"
        /** 单数，和请求区的 Cookie 页签对齐 */
        const val COOKIES = "Cookie"
        const val REQUEST = "实际请求"

        const val BODY_INDEX = 0
        const val HEADERS_INDEX = 1
        const val COOKIES_INDEX = 2

        /** 超过这个大小就不美化也不高亮 */
        const val MAX_PRETTY_BYTES = 512 * 1024

        /** 成功但 body 为空时（如 204）Body 区显示这句，避免和「还没发过」的占位符混淆 */
        const val EMPTY_BODY = "（空响应体）"

        /** 与 HttpExecutor.describe() 里取消分支的文案一致 */
        const val CANCELLED = "已取消"
    }
}
