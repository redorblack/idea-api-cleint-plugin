package dev.red.apiscope.plugin.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import java.awt.Dimension

/**
 * 「批量编辑」切换链接在窄工具窗口里必须始终露得出来，不能被超长的 hint 盖住。
 *
 * 早先 hint 放 [java.awt.BorderLayout.WEST]：HTML label 不给宽度就按**单行**算 preferred，
 * 而 BorderLayout 的 WEST 按子组件的 preferred 宽摆放 —— 于是「变量」页签那条长 hint 会横跨整行、
 * 盖在右侧 EAST 的 [ActionLink] 上（Swing 里 index 0 的组件最后画、压在最上，正好是 hint）。
 * 用户在 400px 的工具窗口里根本点不到「批量编辑」。
 *
 * 改到 CENTER 之后 hint 只拿 toggle 之外的剩余宽、自动回流，两者不再水平重叠。
 * 这里离屏排版后做纯几何断言（不依赖绘制）：hint 的右边界不得越过 toggle 的左边界。
 *
 * @author Red
 * @since 2026-08-17
 */
class HintToggleLayoutTest : BasePlatformTestCase() {

    /** 「变量」页签的 hint 是六个页签里最长的一条，最容易压住 toggle */
    fun testBulkEditToggleNotCoveredByLongHint() {
        val tabs = RequestTabs(project, onChange = {}, onParamsCommit = {})
        assertToggleClearOfHint(tabs.variables.component)
    }

    /** Cookie / 全局 Headers 的 hint 短一些，但同一个布局，一并钉住防回归 */
    fun testBulkEditToggleNotCoveredForOtherHintedTabs() {
        val tabs = RequestTabs(project, onChange = {}, onParamsCommit = {})
        assertToggleClearOfHint(tabs.cookies.component)
        assertToggleClearOfHint(tabs.globalHeaders.component)
    }

    private fun assertToggleClearOfHint(panel: javax.swing.JComponent) {
        panel.size = Dimension(WIDTH, HEIGHT)
        layoutTree(panel)

        val toggle = requireNotNull(firstOfType(panel, ActionLink::class.java)) { "找不到「批量编辑」切换链接" }
        // hint 与 toggle 是同一个 header 子面板里的兄弟组件，用同一坐标系直接比
        val header = requireNotNull(toggle.parent) { "toggle 没有父容器" }
        val hint = requireNotNull(header.components.filterIsInstance<JBLabel>().firstOrNull()) {
            "header 里找不到 hint 标签"
        }

        assertTrue("toggle「批量编辑」没有可见宽度，等于没画出来", toggle.width > 0)
        assertTrue(
            "hint 盖住了「批量编辑」链接：hint 右边界 ${hint.x + hint.width} 越过了 toggle 左边界 ${toggle.x}" +
                "（面板宽 $WIDTH）—— hint 必须放 CENTER 让它随宽度回流，别放 WEST 吃满单行 preferred 宽",
            hint.x + hint.width <= toggle.x
        )
    }

    /** 没有原生 peer 也能排版，测试才能在 headless 下跑（同 TabStripRenderTest） */
    private fun layoutTree(component: Component) {
        component.doLayout()
        if (component is Container) {
            component.components.forEach { layoutTree(it) }
        }
    }

    private fun <T : Component> firstOfType(root: Component, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root !is Container) return null
        return root.components.asSequence().mapNotNull { firstOfType(it, type) }.firstOrNull()
    }

    private companion object {
        /** 工具窗口常停在右侧，宽度就这个量级 */
        const val WIDTH = 400
        const val HEIGHT = 560
    }
}
