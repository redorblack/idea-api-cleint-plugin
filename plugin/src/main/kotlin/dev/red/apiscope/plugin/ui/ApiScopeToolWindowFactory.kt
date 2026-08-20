package dev.red.apiscope.plugin.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory

/**
 * ApiScope 工具窗口入口
 *
 * @author Red
 * @since 2026-08-14
 */
class ApiScopeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RequestPanel(project)
        RequestConsole.getInstance(project).panel = panel

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        // 次要命令放标题栏（历史图标 + 齿轮菜单），把地址栏那 ~60px 让给路径框，
        // 顺带让这些命令能被 Find Action 搜到
        toolWindow.setTitleActions(panel.titleActions())
        (toolWindow as? ToolWindowEx)?.setAdditionalGearActions(panel.gearActions())
    }
}
