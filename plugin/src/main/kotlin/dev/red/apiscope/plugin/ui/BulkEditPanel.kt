package dev.red.apiscope.plugin.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.red.apiscope.core.kv.KvSeparator
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel

/**
 * 一个键值页签：表格视图 + 「批量编辑」文本视图，两者切换
 *
 * 为什么两个视图都要留：**表格适合改一项，文本框适合搬一堆**。
 * 从浏览器 devtools 拷 20 个 header、整段替换某个域名、把配置发给同事 —— 这些只有文本框干得了，
 * 不能因为上了表格就把它砍掉（成熟工具都保留了这个逃生舱）。
 *
 * 切换是无损的：两个视图背后是同一份文本（见 [KeyValueTable]），
 * 表格里「没勾」的行在文本里就是行首带 `#` 的行。
 *
 * @author Red
 * @since 2026-08-14
 */
class BulkEditPanel(
    separator: KvSeparator,
    nameTitle: String = "Name",
    valueTitle: String = "Value",
    extraTitle: String? = null,
    /** 表格上方常驻的灰字说明；null 表示不需要（列头已经说明白的就别啰嗦） */
    hintHtml: String? = null
) {

    val table = KeyValueTable(separator, nameTitle, valueTitle, extraTitle)

    // 空的时候写清语法：这个文本视图正是「从 devtools 拷一堆 header 进来」的逃生舱，
    // 而分隔符是 `:` 还是 `=`、行首 `#` 表示停用，界面上原本一个字都没提
    private val rawArea = UiText.monoArea().apply {
        emptyText.text = when (separator) {
            KvSeparator.COLON -> "Authorization: Bearer xxx（每行一条，行首 # 停用）"
            KvSeparator.EQUALS -> "name = value（每行一条，行首 # 停用）"
        }
    }

    private val cards = CardLayout()
    private val content = JPanel(cards).apply {
        add(table.component, TABLE)
        add(JBScrollPane(rawArea), RAW)
    }

    private val toggle = ActionLink(TO_RAW) { switch() }

    private var showingTable = true

    init {
        // 文本视图里改完东西靠**失焦**提交，和表格的 terminateEditOnFocusLost 一条规矩。
        // 不逐键提交：Params 页签的提交会回写地址栏，边敲边写会让地址栏一直抖；
        // 而不提交就意味着「粘一堆 header 进来、直接发送、重启后全没了」（落盘只认提交过的内容）
        rawArea.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = onCommit()
        })
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(4)
                // hint 放 CENTER 而不是 WEST：HTML label 不给宽度时按单行算 preferred，
                // WEST 会把这个超宽尺寸吃满、把右侧「批量编辑」链接挤出可视区。
                // CENTER 只拿 toggle 之外的剩余宽，HTML 会自动回流换行，toggle 永远露得出来
                if (hintHtml != null) add(UiText.hint(hintHtml), BorderLayout.CENTER)
                add(toggle, BorderLayout.EAST)
            },
            BorderLayout.NORTH
        )
        add(content, BorderLayout.CENTER)
    }

    /** 任一视图里改了东西都会回调（表格提交 / 文本框失焦切回） */
    var onCommit: () -> Unit
        get() = table.onCommit
        set(value) {
            table.onCommit = value
        }

    /**
     * 页签内容的文本形态 —— 落盘、历史记录、插值都用它。
     *
     * getter 要看**当前在哪个视图**：文本视图下用户刚敲的内容还没同步回表格，
     * 直接读表格会拿到过时的值（发送时最容易踩这个）。
     */
    var text: String
        get() = if (showingTable) table.text else rawArea.text.orEmpty()
        set(value) {
            table.text = value
            rawArea.text = value
        }

    fun toMap(): Map<String, String> {
        syncToTable()
        return table.toMap()
    }

    /** 保序保重名，见 [KeyValueTable.pairs] */
    fun pairs(): List<Pair<String, String>> {
        syncToTable()
        return table.pairs()
    }

    fun enabledCount(): Int {
        syncToTable()
        return table.enabledCount()
    }

    private fun switch() {
        if (showingTable) {
            rawArea.text = table.text
            cards.show(content, RAW)
            toggle.text = TO_TABLE
        } else {
            syncToTable()
            cards.show(content, TABLE)
            toggle.text = TO_RAW
        }
        showingTable = !showingTable
        onCommit()
    }

    /** 文本视图下的现场内容灌回表格；已经在表格视图就什么都不用做 */
    private fun syncToTable() {
        if (!showingTable) table.text = rawArea.text.orEmpty()
    }

    private companion object {
        const val TABLE = "table"
        const val RAW = "raw"
        const val TO_RAW = "批量编辑"
        // 两个状态都用动词短语，用户才不用猜「表格」是当前状态还是要切过去的状态
        const val TO_TABLE = "返回表格"
    }
}
