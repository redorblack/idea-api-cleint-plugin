package dev.red.apiscope.plugin.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * 地址栏 `…` 里的那几个动作 —— 导出 / 导入 / 存响应 / 清 Cookie
 *
 * 单独一个文件是为了让 `RequestPanel` 只留「怎么拼请求、怎么发」这一件事，
 * 剪贴板和文件选择器这类平台琐事挪出去。
 *
 * @author Red
 * @since 2026-08-14
 */
class RequestActions(
    private val project: Project,
    /** 当前请求导出的 cURL；拼不出来（地址为空、变量没定义）时返回 null */
    private val curlOf: () -> String?,
    private val onImport: (String) -> Unit,
    private val responseOf: () -> String,
    private val onClearCookies: () -> Unit,
    /** 按最新源码重填 method / 路径 / 参数 / body，丢掉手填内容 */
    private val onRefill: () -> Unit,
    private val onTimeout: () -> Unit,
    /** 命令没东西可做时（没响应、地址拼不出来）报一句，别静默无反应像坏了 */
    private val onNotice: (String) -> Unit
) {

    /**
     * 这几项做成工具窗口标题栏齿轮菜单里的 [AnAction]，而不是地址栏上一个 `⋮` 按钮弹的列表。
     *
     * 两个好处：地址栏省下 ~60px 让给路径框（400px 宽时路径框本来会被挤到 0），
     * 并且它们从此能被 `Find Action` 搜到 —— 齿轮菜单正是 JetBrains 工具窗口摆次要命令的标准位置。
     */
    fun asActionGroup(): ActionGroup = DefaultActionGroup(ITEMS.map { item -> item.toAction() })

    private fun String.toAction(): AnAction {
        val item = this
        return object : AnAction(item) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun actionPerformed(event: AnActionEvent) = run(item)
        }
    }

    private fun run(item: String) {
        when (item) {
            COPY_CURL -> copyCurl()
            IMPORT_CURL -> importCurl()
            COPY_BODY -> copyBody()
            SAVE_BODY -> saveResponse()
            CLEAR_COOKIES -> onClearCookies()
            REFILL -> onRefill()
            TIMEOUT -> onTimeout()
        }
    }

    private fun copyCurl() {
        val curl = curlOf()
        if (curl == null) {
            onNotice("地址不完整或有未定义变量，导不出 cURL")
            return
        }
        copy(curl)
        onNotice("已复制 cURL")
    }

    private fun copyBody() {
        val body = responseOf()
        if (body.isEmpty()) {
            onNotice("还没有响应可复制")
            return
        }
        copy(body)
        onNotice("已复制响应体")
    }

    /**
     * 从剪贴板读一条 cURL 灌进面板。
     *
     * 走剪贴板而不是弹输入框：cURL 永远是别人发过来的一段文本，
     * 中间加一步「粘到对话框里再确认」纯属多余。
     */
    private fun importCurl() {
        val text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String
        if (text.isNullOrBlank()) return
        onImport(text)
    }

    private fun saveResponse() {
        val body = responseOf()
        if (body.isEmpty()) {
            onNotice("还没有响应可保存")
            return
        }
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("保存响应", "把当前响应体写到文件"), project)
            .save("response.json")
            ?: return
        wrapper.file.writeText(body)
    }

    private fun copy(text: String) {
        if (text.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private companion object {
        const val COPY_CURL = "复制为 cURL"
        const val IMPORT_CURL = "从剪贴板导入 cURL"
        const val COPY_BODY = "复制响应体"
        const val SAVE_BODY = "保存响应到文件…"
        const val CLEAR_COOKIES = "清空自动收下的 Cookie"
        const val REFILL = "从源码重新填充"
        const val TIMEOUT = "读超时…"

        val ITEMS = listOf(REFILL, COPY_CURL, IMPORT_CURL, COPY_BODY, SAVE_BODY, CLEAR_COOKIES, TIMEOUT)
    }
}
