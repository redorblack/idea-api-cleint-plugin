package dev.red.apiscope.plugin.ui

import com.intellij.json.JsonFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * 用平台自己的 Editor 显示文本 —— 响应体专用
 *
 * 为什么不继续用 `JBTextArea`：换成 Editor 之后，下面这些**全是平台白给的**，
 * 自己写每一样都要几十到几百行：
 * JSON 语法高亮、代码折叠（大对象/数组收起来）、行号、`Ctrl+F` 在响应里搜、
 * 软换行（长 base64 不再横向拉出天际）、非法 JSON 由 JSON 插件的 annotator 直接标红。
 *
 * [EditorTextField] 的编辑器是**挂到界面上时才创建、摘下来时自动释放**的（`addNotify`/`removeNotify`），
 * 所以这里不需要自己管 dispose —— 反过来说也**不能**手动 release，否则会 double free。
 *
 * @author Red
 * @since 2026-08-14
 */
class JsonTextView(
    project: Project,
    placeholder: String = "",
    /** true = 只读（响应体）；false = 可编辑（请求体） */
    viewer: Boolean = true
) {

    private val textField = EditorTextField(
        EditorFactory.getInstance().createDocument(""),
        project,
        PlainTextFileType.INSTANCE,
        viewer,
        false
    ).apply {
        setPlaceholder(placeholder)
        addSettingsProvider { editor ->
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
            editor.settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = true
                isUseSoftWraps = true
                // 这两样是给「正在写代码」准备的，只读的响应体不需要，留着反而是噪音
                isLineMarkerAreaShown = false
                isCaretRowShown = false
                additionalLinesCount = 0
                additionalColumnsCount = 0
            }
        }
    }

    val component: JPanel = JPanel(BorderLayout()).apply { add(textField, BorderLayout.CENTER) }

    var text: String
        get() = textField.text
        set(value) {
            textField.text = value
            textField.editor?.caretModel?.moveToOffset(0)
        }

    /**
     * 按内容类型切换高亮。
     *
     * 只分 JSON 和纯文本两档：响应还可能是 HTML 错误页，但为它再挂一个 FileType 依赖不值 ——
     * HTML 报错页看的是文字内容，不是标签结构。
     */
    fun showAs(json: Boolean) {
        val target: FileType = if (json) JsonFileType.INSTANCE else PlainTextFileType.INSTANCE
        if (textField.fileType == target) return
        textField.setNewDocumentAndFileType(target, textField.document)
    }

    /** 内容变化回调（请求体要用它刷页签 badge） */
    fun onTextChanged(listener: () -> Unit) {
        textField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = listener()
        })
    }

    var enabled: Boolean
        get() = textField.isEnabled
        set(value) {
            textField.isEnabled = value
        }
}
