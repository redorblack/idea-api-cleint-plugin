package dev.red.apiscope.plugin.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 面板里反复用到的几种控件样式，集中一处，免得每个面板各写一套字号/前景色
 *
 * @author Red
 * @since 2026-08-14
 */
internal object UiText {

    fun monoArea(): JBTextArea = JBTextArea().apply {
        // 用编辑器配置的等宽字体，而不是 AWT 逻辑字体 MONOSPACED —— 响应 Body 页签走的是平台
        // Editor（编辑器字体），Headers / Cookie / 实际请求这几个若用 MONOSPACED，切页签时字形会跳变
        val scheme = EditorColorsManager.getInstance().globalScheme
        font = Font(scheme.editorFontName, Font.PLAIN, font.size)
        lineWrap = true
        wrapStyleWord = false
        border = JBUI.Borders.empty(4)
    }

    /** 常驻的灰色用法说明 —— 不用输入框的 emptyText，那个一点就没，新手正需要时恰好消失 */
    fun hint(html: String): JBLabel = JBLabel("<html>$html</html>").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.emptyBottom(4)
    }

    fun small(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }

    fun leftAligned(component: JComponent): JPanel =
        JPanel(BorderLayout()).apply { add(component, BorderLayout.WEST) }
}
