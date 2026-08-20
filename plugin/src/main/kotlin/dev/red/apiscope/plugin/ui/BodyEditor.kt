package dev.red.apiscope.plugin.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import dev.red.apiscope.core.http.MultipartPart
import dev.red.apiscope.core.http.RequestBody
import dev.red.apiscope.core.json.JsonPrinter
import dev.red.apiscope.core.kv.KvSeparator
import dev.red.apiscope.core.vars.Interpolator
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * Body 编辑器 —— 按类型换视图：raw 文本框 或 键值表格
 *
 * **两套缓冲各存各的**（[rawView] 与 [kvPanel]），这不是实现细节而是修 bug：
 * 早先只有一个文本框，在 multipart 下插了 `file=@/路径` 再把类型切成 JSON，
 * 那行会被原样当 JSON body 发出去，服务端报 400 却看不出原因。
 * 分成两套之后**这个错误从结构上不可能发生** —— 文件是表格里一行的值，不是 JSON 文本里的一段字符。
 *
 * 落盘只存**当前类型对应的那一套**（见 [text]）：历史记录本来就是 `bodyType` + `body` 成对存的，
 * 回填时按类型灌回对应视图即可。
 *
 * @author Red
 * @since 2026-08-14
 */
class BodyEditor(private val project: Project) {

    private val typeCombo = ComboBox(TYPES).apply {
        // 不钉死宽度的话会被拉满整行 —— 一个六选一的下拉占掉半个面板，很难看
        val size = Dimension(JBUI.scale(170), preferredSize.height)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        // 只改显示：常量值 `none` / `form-urlencoded` 等是**持久化契约**（历史记录按 bodyType 回填视图），
        // 直接改文案会让已存的历史条目灌进错误的缓冲，所以映射只发生在渲染这一层
        renderer = object : SimpleListCellRenderer<String>() {
            override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = LABELS[value] ?: value.orEmpty()
            }
        }
    }

    private val formatButton = JButton("格式化").apply {
        toolTipText = "把压缩成一行的 JSON 展开"
        isEnabled = false
    }

    private val pickFileButton = JButton("选文件…").apply {
        toolTipText = "把文件路径填进选中行"
        isVisible = false
    }

    /**
     * raw body 用平台 Editor 而不是 `JBTextArea`。
     *
     * 原先能力给反了：**只读的响应**有 JSON 高亮、折叠、`Ctrl+F`、非法 JSON 由 JSON 插件直接标红，
     * 而**需要手敲 JSON 的请求体**什么都没有 —— 可漏逗号少引号的恰恰是写的那一侧。
     * 换过来之后「格式化」按钮只是顺手保留，畸形 JSON 不用点按钮就能看见。
     */
    private val rawView = JsonTextView(project, placeholder = "在这里写请求体", viewer = false)

    private val kvPanel = BulkEditPanel(KvSeparator.EQUALS, nameTitle = "Name", valueTitle = "Value")

    private val cards = CardLayout()
    private val content = JPanel(cards).apply {
        // Editor 自带滚动，不用再套一层 JBScrollPane（套了会出现两条滚动条）
        add(rawView.component, CARD_RAW)
        add(kvPanel.component, CARD_KV)
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.emptyBottom(6)
                add(typeCombo)
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(formatButton)
                add(pickFileButton)
                add(Box.createHorizontalGlue())
            },
            BorderLayout.NORTH
        )
        add(content, BorderLayout.CENTER)
    }

    /** 类型或内容变化时回调，面板拿它更新页签 badge */
    var onChange: () -> Unit = {}

    init {
        typeCombo.addActionListener {
            applyType()
            onChange()
        }
        formatButton.addActionListener { rawView.text = JsonPrinter.pretty(rawView.text) }
        pickFileButton.addActionListener { pickFile() }
        rawView.onTextChanged { onChange() }
        kvPanel.onCommit = { onChange() }
        applyType()
    }

    /**
     * 当前视图的文本内容 —— 落盘与历史记录用它。
     *
     * 读写都只认**当前类型对应的那一套缓冲**，另一套的内容不参与序列化：
     * 存两套等于让落盘的状态记住用户已经切走的旧内容，回填时反而困惑。
     */
    var text: String
        get() = if (isKeyValue) kvPanel.text else rawView.text
        set(value) {
            if (isKeyValue) kvPanel.text = value else rawView.text = value
        }

    var type: String
        get() = (typeCombo.selectedItem as? String) ?: NONE
        set(value) {
            typeCombo.selectedItem = value
        }

    /** 有内容的项数，用于页签 badge；raw 类型只回答「有没有」 */
    fun count(): Int = when {
        type == NONE -> 0
        isKeyValue -> kvPanel.enabledCount()
        else -> if (rawView.text.isBlank()) 0 else 1
    }

    /**
     * 文本 → 可发送的 body
     *
     * 插值在这里做而不是在执行器里：core 只管把给定的 body 编码发出去，不认识「变量」这回事。
     */
    fun build(variables: Map<String, String>): RequestBody = when (type) {
        NONE -> RequestBody.None

        FORM -> RequestBody.Form(
            kvPanel.toMap().map { (name, value) -> name to Interpolator.apply(value, variables) }
        )

        MULTIPART -> RequestBody.Multipart(
            kvPanel.toMap().map { (name, raw) ->
                val value = Interpolator.apply(raw, variables)
                if (value.startsWith(FILE_MARK)) {
                    MultipartPart.FileRef(name, value.removePrefix(FILE_MARK).trim())
                } else {
                    MultipartPart.Field(name, value)
                }
            }
        )

        // raw 文本：空的就当没 body，免得白搭一个 Content-Type 让服务端按空 JSON 解析报 400
        else -> Interpolator.apply(rawView.text, variables)
            .takeIf { it.isNotBlank() }
            ?.let { RequestBody.Text(it, contentTypeOf(type)) }
            ?: RequestBody.None
    }

    private val isKeyValue: Boolean get() = type == FORM || type == MULTIPART

    private fun applyType() {
        val keyValue = isKeyValue
        cards.show(content, if (keyValue) CARD_KV else CARD_RAW)
        rawView.enabled = type != NONE
        // JSON 才上高亮/标红；XML 与纯文本落到 PlainText（为 XML 再挂一个语言依赖不值）
        rawView.showAs(type == JSON)
        formatButton.isEnabled = type == JSON || type == XML
        pickFileButton.isVisible = type == MULTIPART
    }

    /** 选中的文件路径写进当前行；`@` 前缀沿用 cURL 的 `-F name=@file` 写法，导出 cURL 时正好对上 */
    private fun pickFile() {
        val chosen = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
            project,
            null
        ) ?: return
        val row = kvPanel.table.selectedRow()
        kvPanel.table.setValueAt(row, FILE_MARK + chosen.path)
        // 文件填进去了但这行可能还没字段名 —— 没名字发送时会被静默丢弃，光标送进名字格提示补上
        kvPanel.table.editNameIfBlank(row)
    }

    private fun contentTypeOf(type: String): String = when (type) {
        XML -> "application/xml; charset=utf-8"
        TEXT -> "text/plain; charset=utf-8"
        else -> "application/json; charset=utf-8"
    }

    companion object {
        const val NONE = "none"
        const val JSON = "JSON"
        const val XML = "XML"
        const val TEXT = "text"
        const val FORM = "form-urlencoded"
        const val MULTIPART = "multipart/form-data"

        val TYPES = arrayOf(NONE, JSON, XML, TEXT, FORM, MULTIPART)

        /** 下拉里显示的文案。六个选项原本混了小写单词 / 大写缩写 / mime 片段 / 完整 mime 四种风格 */
        private val LABELS = mapOf(
            NONE to "无",
            JSON to "JSON",
            XML to "XML",
            TEXT to "纯文本",
            FORM to "Form",
            MULTIPART to "Multipart"
        )

        /** 值以此开头即表示「这是文件路径」，沿用 cURL 的 -F name=@file 写法 */
        const val FILE_MARK = "@"

        private const val CARD_RAW = "raw"
        private const val CARD_KV = "kv"
    }
}
