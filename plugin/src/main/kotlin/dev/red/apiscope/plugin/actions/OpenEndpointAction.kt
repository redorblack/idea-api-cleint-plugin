package dev.red.apiscope.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import dev.red.apiscope.plugin.psi.EndpointScanner
import dev.red.apiscope.plugin.ui.RequestConsole

/**
 * 「在 ApiScope 里打开光标所在接口」—— gutter 图标之外的第二个入口
 *
 * 为什么必须有它：原先唯一的入口是行号旁那个小图标，也就是**只能用鼠标**。
 * `Cmd+Shift+A` 搜不到 ApiScope、keymap 里没有可绑的项，键盘流用户享受不到
 * 「改完代码不摸鼠标就发一发」；而这恰恰是一个 IDE 插件相对 Postman 的全部意义。
 *
 * 挂在编辑器右键菜单和 Tools 菜单下，快捷键在 `plugin.xml` 里给了默认值、用户可在 Keymap 改。
 *
 * @author Red
 * @since 2026-08-14
 */
class OpenEndpointAction : AnAction() {

    /** 要读 PSI，必须在后台线程做 update —— 平台 2024.2+ 的硬要求 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = endpointMethod(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val method = endpointMethod(event) ?: return
        val descriptor = EndpointScanner.scan(method) ?: return
        RequestConsole.getInstance(project).open(descriptor)
    }

    /** 光标所在（或往外找到的第一个）方法，且它得是个能发的接口 */
    private fun endpointMethod(event: AnActionEvent): PsiMethod? {
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        return method.takeIf { isJava(file) && EndpointScanner.isEndpoint(it) }
    }

    /** 扫描器只认 Java PSI，Kotlin Controller 目前读不了，别在那里亮出菜单项 */
    private fun isJava(file: PsiFile): Boolean = file.name.endsWith(".java")
}
