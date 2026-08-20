package dev.red.apiscope.plugin.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.red.apiscope.core.endpoint.EndpointDescriptor

/**
 * 工具窗口与 gutter 之间的桥
 *
 * @author Red
 * @since 2026-08-14
 */
@Service(Service.Level.PROJECT)
class RequestConsole(private val project: Project) {

    @Volatile
    var panel: RequestPanel? = null

    /** 激活工具窗口并把端点填进去；窗口首次打开时内容由 Factory 创建，回调里 panel 已就绪 */
    fun open(descriptor: EndpointDescriptor) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            panel?.load(descriptor)
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "ApiScope"

        fun getInstance(project: Project): RequestConsole = project.getService(RequestConsole::class.java)
    }
}
