package dev.red.apiscope.plugin.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import javax.swing.event.DocumentEvent
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 地址栏 —— `[GET ▾] [Base URL ▾] [/path] [发送]` 一行
 *
 * 这一行的唯一硬约束是**窄窗口下「发送」不能被挤出可视区**（工具窗口停在右侧时常只有 ~400px）。
 * 为此：method 下拉钉死在 "DELETE" 刚好的宽度；「历史」和 `⋮` 那几项**挪到工具窗口标题栏**
 * （见 [RequestActions.asActionGroup]）——那是 JetBrains 摆次要命令的标准位置，
 * 这一行只留每次请求都要动的东西。
 *
 * **Base URL 和 path 按权重分剩余空间**（0.35 / 0.65），不再把 Base URL 钉死：
 * 钉死 150px 时固定开销合计 392px，工具窗口 400px 下 `BorderLayout.CENTER` 里的 path 框只剩 8px ——
 * 一天改一次的 Base URL 稳占宽度，每次请求都要改的 path 反而先消失。
 * Base URL 缩到 56px 仍可用（值一直在下拉里选得到），所以牺牲顺序应该反过来。
 *
 * @author Red
 * @since 2026-08-14
 */
class UrlBar(
    baseUrls: List<String>,
    private val onSend: () -> Unit
) {

    private val methodCombo = ComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH")).apply {
        fix(JBUI.scale(88))
    }

    private val baseUrlCombo = ComboBox(DefaultComboBoxModel(baseUrls.toTypedArray())).apply {
        isEditable = true
        // 只给「想要多宽」和「最少多宽」，剩下交给 GridBagLayout 按权重压
        preferredSize = Dimension(JBUI.scale(150), preferredSize.height)
        minimumSize = Dimension(JBUI.scale(56), preferredSize.height)
        toolTipText = "Base URL，如 http://localhost:8080，也可写 {{baseUrl}}"
    }

    private val pathField = JBTextField().apply {
        // 空态就是新手唯一会看的地方，把第二个入口写在这儿，不额外占一行常驻提示
        emptyText.text = "/order/list（也可在 Controller 方法旁点 ➜ 自动填好）"
        toolTipText = "相对路径；也可以直接粘一整条 http:// 地址"
        minimumSize = Dimension(JBUI.scale(60), preferredSize.height)
    }

    private val sendButton = JButton(SEND).apply { toolTipText = "发送（⌘↩ / Ctrl+↩）" }

    val component: JPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        border = JBUI.Borders.emptyBottom(8)
        add(methodCombo, BorderLayout.WEST)
        add(
            JPanel(GridBagLayout()).apply {
                add(
                    baseUrlCombo,
                    GridBagConstraints().apply {
                        gridx = 0
                        weightx = 0.35
                        fill = GridBagConstraints.HORIZONTAL
                        insets = JBUI.insetsRight(6)
                    }
                )
                add(
                    pathField,
                    GridBagConstraints().apply {
                        gridx = 1
                        weightx = 0.65
                        fill = GridBagConstraints.HORIZONTAL
                    }
                )
            },
            BorderLayout.CENTER
        )
        add(sendButton, BorderLayout.EAST)
    }

    /** 路径框内容变化 —— Params 表要跟着重新拆 query */
    var onPathEdited: () -> Unit = {}

    init {
        pathField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onPathEdited()
        })
        sendButton.addActionListener { onSend() }
    }

    var method: String
        get() = (methodCombo.selectedItem as? String) ?: "GET"
        set(value) {
            methodCombo.selectedItem = value
        }

    var path: String
        get() = pathField.text.orEmpty()
        set(value) {
            pathField.text = value
        }

    /** 可编辑下拉框要读 editor 里的现场文本，selectedItem 拿不到用户刚敲进去还没回车的值 */
    var baseUrl: String
        get() = ((baseUrlCombo.editor.item as? String) ?: (baseUrlCombo.selectedItem as? String)).orEmpty().trim()
        set(value) {
            baseUrlCombo.selectedItem = value
        }

    /** 请求中把「发送」变成「取消」—— 地址写错时等超时最磨人，得能立刻中断 */
    var busy: Boolean = false
        set(value) {
            field = value
            sendButton.text = if (value) CANCEL else SEND
        }

    /**
     * 往下拉里塞一个候选地址（放首位），已经有就不动。
     *
     * 只是"多给一个选项"，不改当前选中值 —— 选不选由调用方决定（见 `RequestPanel.fill`）。
     */
    fun offerBaseUrl(url: String) {
        val model = baseUrlCombo.model
        if ((0 until model.size).any { model.getElementAt(it) == url }) return
        (model as? DefaultComboBoxModel<String>)?.insertElementAt(url, 0)
    }

    fun refreshBaseUrls(items: List<String>, selected: String) {
        baseUrlCombo.model = DefaultComboBoxModel(items.toTypedArray())
        baseUrlCombo.selectedItem = selected
    }

    /** 路径里直接粘了整条 URL 就以它为准，否则拼在 Base URL 后面 */
    fun resolveUrl(): String {
        val path = pathField.text?.trim().orEmpty()
        val base = baseUrl.trimEnd('/')
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.isEmpty() -> base
            path.startsWith("?") -> base + path
            else -> "$base/${path.trimStart('/')}"
        }
    }

    /** 三个尺寸一起钉死：BorderLayout 只认 preferred，BoxLayout 会拿 max 去抢剩余空间（只有 method 下拉要这样） */
    private fun JComponent.fix(width: Int) {
        val size = Dimension(width, preferredSize.height)
        preferredSize = size
        minimumSize = size
        maximumSize = size
    }

    private companion object {
        const val SEND = "发送"
        const val CANCEL = "取消"
    }
}
