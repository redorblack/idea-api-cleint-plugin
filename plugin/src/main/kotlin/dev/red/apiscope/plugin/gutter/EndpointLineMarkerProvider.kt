package dev.red.apiscope.plugin.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.util.concurrency.AppExecutorUtil
import dev.red.apiscope.core.endpoint.EndpointDescriptor
import dev.red.apiscope.plugin.psi.EndpointScanner
import dev.red.apiscope.plugin.ui.RequestConsole

/**
 * Controller 方法旁的「发送」图标
 *
 * 两个性能约束（写错就是全 IDE 卡顿）：
 * - 只认 [PsiIdentifier]，不在整个 PsiMethod 上返回 marker（否则 gutter 位置错乱）
 * - dumb 模式直接返回 null；构造端点要查索引，索引没建好时会抛 IndexNotReadyException
 *
 * @author Red
 * @since 2026-08-14
 */
class EndpointLineMarkerProvider : LineMarkerProvider {

    private val icon = IconLoader.getIcon("/icons/send.svg", EndpointLineMarkerProvider::class.java)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        if (DumbService.isDumb(element.project)) return null
        if (!EndpointScanner.isEndpoint(method)) return null

        // 点它只是把面板填好、把窗口叫到前面，并不直接发请求 —— 文案要和右键菜单的
        // 「Open Endpoint in ApiScope」一致，别让人以为点一下就发出去了
        val tooltip = "ApiScope: 在面板中打开该接口（填好后点发送）"
        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            { _, target -> openConsole(target) },
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    /** DTO 递归展开可能不快，放后台读线程算，算完回 UI 线程填面板 */
    private fun openConsole(target: PsiElement) {
        val project = target.project
        val method = target.parent as? PsiMethod ?: return

        ReadAction.nonBlocking<EndpointDescriptor?> { EndpointScanner.scan(method) }
            .expireWhen { !method.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { descriptor ->
                descriptor?.let { RequestConsole.getInstance(project).open(it) }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
