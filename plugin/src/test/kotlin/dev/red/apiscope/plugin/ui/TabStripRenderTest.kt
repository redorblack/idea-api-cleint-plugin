package dev.red.apiscope.plugin.ui

import com.intellij.ide.ui.laf.darcula.ui.DarculaTabbedPaneUI
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 页签标题「真的画出来了」的离屏渲染验证
 *
 * 为什么要用渲染而不是断言 `getTitleAt()`：0.2.0 交付时六个页签的标题在 IDEA 2026.2 上**整排都不显示**，
 * 只剩选中项的蓝色下划线，而模型层完全正常 —— `getTitleAt()` 全都对，肉眼看却是空白。
 * 根因是 `SCROLL_TAB_LAYOUT` 会让平台的 [DarculaTabbedPaneUI] 换上私有的 `WrappingLayout`
 * ＋「更多页签」按钮，那条路径按鼠标位置重算 tab viewport（`ensureSelectedTabIsVisible` 里读
 * `MouseInfo`），算歪之后 viewport 高度成 0，标题一个都不画；下划线由外层 `paint` 单独画，所以还在。
 *
 * 因此这里把面板离屏画进 [BufferedImage]，逐个页签数它自己矩形内的墨迹像素 ——
 * 只要标题没落到画布上就红。任何「模型对、界面空」的改动都会被这条挡住。
 *
 * 测试里显式把 `TabbedPaneUI` 指向 [DarculaTabbedPaneUI]：默认 LaF 下的 `BasicTabbedPaneUI` 不会
 * 复现这类问题，验证必须打在 IDE 真正用的那个 UI 类上。
 *
 * @author Red
 * @since 2026-08-14
 */
class TabStripRenderTest : BasePlatformTestCase() {

    private var previousTabbedPaneUi: Any? = null

    override fun setUp() {
        super.setUp()
        previousTabbedPaneUi = UIManager.get(TABBED_PANE_UI)
        UIManager.put(TABBED_PANE_UI, DarculaTabbedPaneUI::class.java.name)
    }

    override fun tearDown() {
        try {
            UIManager.put(TABBED_PANE_UI, previousTabbedPaneUi)
        } finally {
            super.tearDown()
        }
    }

    fun testRequestTabTitlesAreActuallyPainted() {
        val tabs = RequestTabs(project, onChange = {}, onParamsCommit = {})
        tabs.refreshBadges()

        assertTitlesPainted(tabs.component, listOf("Params", "Body", "Headers", "Cookie", "全局", "变量"))
    }

    fun testResponseTabTitlesAreActuallyPainted() {
        val view = ResponseView(project)
        // 切到 Headers（纯文本页）再画：Body 页是平台 Editor，测试里不去实例化它
        findTabbedPane(view.component).selectedIndex = 1

        assertTitlesPainted(view.component, listOf("Body", "Headers", "Cookie", "实际请求"))
    }

    /**
     * badge 会改标题文本（`Params 2`），带上数字之后同样得能画出来。
     *
     * 同时钉住「badge 只给前三个页签」：`Cookie / 全局 / 变量` 配好就不动，
     * 给它们加计数只是让标题栏跟着抖，见 [RequestTabs.refreshBadges]。
     */
    fun testOnlyThePerRequestTabsCarryBadges() {
        val tabs = RequestTabs(project, onChange = {}, onParamsCommit = {})
        tabs.params.text = "a = 1\nb = 2"
        tabs.headers.text = "X-Trace: 1"
        tabs.cookies.text = "sid = 1"
        tabs.variables.text = "token = x"
        tabs.refreshBadges()

        assertTitlesPainted(tabs.component, listOf("Params 2", "Body", "Headers 1", "Cookie", "全局", "变量"))
    }

    private fun assertTitlesPainted(root: JComponent, expectedTitles: List<String>) {
        val pane = findTabbedPane(root)

        assertEquals(
            "页签必须留在 WRAP：SCROLL_TAB_LAYOUT 在 2026.x 上会让整排标题不绘制",
            JTabbedPane.WRAP_TAB_LAYOUT,
            pane.tabLayoutPolicy
        )
        assertEquals(expectedTitles, (0 until pane.tabCount).map { pane.getTitleAt(it) })

        val image = paintOffscreen(root)
        expectedTitles.forEachIndexed { index, title ->
            val bounds = SwingUtilities.convertRectangle(pane, pane.getBoundsAt(index), root)
            val ink = inkPixels(image, bounds)
            assertTrue(
                "页签「$title」的标题没画到画布上（墨迹像素 $ink，至少要 $MIN_INK）；" +
                    "标题栏矩形 $bounds",
                ink >= MIN_INK
            )
        }
    }

    private fun paintOffscreen(root: JComponent): BufferedImage {
        root.size = Dimension(WIDTH, HEIGHT)
        layoutTree(root)

        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, WIDTH, HEIGHT)
            root.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    /**
     * 一个页签矩形里「不是底色」的像素数 —— 也就是文字的墨迹。
     *
     * 底色取矩形内出现最多的颜色（页签背景可能是选中态/hover 态，不能写死），
     * 四周缩进是为了甩掉页签边框，底部多缩是为了甩掉选中项的蓝色下划线 —— 下划线由外层单独画，
     * 算进来就会把「只剩下划线、标题没了」这个正是要抓的情况判成通过。
     */
    private fun inkPixels(image: BufferedImage, bounds: Rectangle): Int {
        val area = Rectangle(
            bounds.x + EDGE,
            bounds.y + EDGE,
            bounds.width - EDGE * 2,
            bounds.height - EDGE - UNDERLINE
        ).intersection(Rectangle(0, 0, image.width, image.height))
        if (area.isEmpty) return 0

        val histogram = HashMap<Int, Int>()
        for (y in area.y until area.y + area.height) {
            for (x in area.x until area.x + area.width) {
                histogram.merge(image.getRGB(x, y), 1, Int::plus)
            }
        }
        val background = histogram.maxByOrNull { it.value }!!.key
        return histogram.entries
            .filter { (color, _) -> differsVisibly(color, background) }
            .sumOf { it.value }
    }

    private fun differsVisibly(color: Int, background: Int): Boolean {
        val delta = intArrayOf(16, 8, 0).maxOf { shift ->
            Math.abs((color shr shift and 0xFF) - (background shr shift and 0xFF))
        }
        return delta > CHANNEL_TOLERANCE
    }

    /** 没有原生 peer 也能排版，测试才能在 headless 下跑 */
    private fun layoutTree(component: Component) {
        component.doLayout()
        if (component is Container) {
            component.components.forEach { layoutTree(it) }
        }
    }

    private fun findTabbedPane(root: Component): JTabbedPane =
        requireNotNull(firstTabbedPane(root)) { "面板里找不到 JTabbedPane：${root.javaClass.name}" }

    private fun firstTabbedPane(component: Component): JTabbedPane? {
        if (component is JTabbedPane) return component
        if (component !is Container) return null
        return component.components.asSequence().mapNotNull { firstTabbedPane(it) }.firstOrNull()
    }

    private companion object {
        const val TABBED_PANE_UI = "TabbedPaneUI"

        /** 工具窗口常停在右侧，宽度就这个量级 */
        const val WIDTH = 420
        const val HEIGHT = 560

        /** 页签矩形四周/底部的缩进，单位像素 */
        const val EDGE = 3
        const val UNDERLINE = 5

        /** 单通道差超过这个值才算墨迹，抗掉抗锯齿的浅边 */
        const val CHANNEL_TOLERANCE = 30

        /** 最短的标题（`Body`）也远不止这个墨迹量，留足余量防抖 */
        const val MIN_INK = 20
    }
}
