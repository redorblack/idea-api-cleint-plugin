package dev.red.apiscope.plugin.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.red.apiscope.core.kv.KeyValueLines
import dev.red.apiscope.core.kv.KvRow
import dev.red.apiscope.core.kv.KvSeparator
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * 键值表格 —— Params / Headers / Cookie / 变量 / 表单字段共用一套
 *
 * 为什么值得做成表格（推翻了早期「一个文本框够了」的判断）：一个文本框对**单个**页签成立，
 * 但这里有六处形状完全相同的键值输入。表格写一次、六处复用，边际成本几乎为零，
 * 换来的是「不用记 name=value 语法」+「点一下就能临时停用一行」+「一眼看清有几项」。
 *
 * **文本是模型，表格是视图**：[text] 才是唯一真源（落盘存的、历史记录存的都是它），
 * 表格只是它的结构化呈现。这样「批量编辑」切回文本框是无损的，旧的 `.idea/apiscope.xml` 也照样读。
 * 「勾选框没勾」对应文本里的行首 `#`（见 [KeyValueLines]），所以两个视图能严格互逆。
 *
 * @author Red
 * @since 2026-08-14
 */
class KeyValueTable(
    private val separator: KvSeparator,
    nameTitle: String = "Name",
    valueTitle: String = "Value",
    /** 额外的只读列标题（如 Params 的「类型」），null 表示不要这一列 */
    private val extraTitle: String? = null
) {

    private val model = Model(nameTitle, valueTitle, extraTitle)

    private val table = JBTable(model).apply {
        setShowGrid(false)
        rowHeight = JBUI.scale(24)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // 敲完一格按 Tab/Enter 就提交，别等焦点离开表格 —— 否则点「发送」时最后一格的值还没落下来
        putClientProperty("terminateEditOnFocusLost", true)
        emptyText.text = ""
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    /** 任一单元格提交后回调 —— 上层用它刷 badge、回写地址栏 */
    var onCommit: () -> Unit = {}

    init {
        model.onCommit = { onCommit() }
        sizeColumns()
        reorderColumns()
        // Delete 删当前行：表格里最自然的删除方式，省一个按钮。
        // 两个键都绑：mac 键盘上标着 delete 的那个键发出的是 BACK_SPACE，只绑 VK_DELETE 等于在 mac 上没有删除方式
        // （单元格编辑中不会误删：编辑器自己的 WHEN_FOCUSED 绑定先吃掉退格）
        listOf(KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE).forEach { keyCode ->
            table.registerKeyboardAction(
                { removeSelectedRow() },
                KeyStroke.getKeyStroke(keyCode, 0),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
            )
        }
        model.reset(emptyList())
    }

    /**
     * 表格内容的文本形态 —— 唯一真源。
     *
     * getter 会**丢掉末尾那行永远空着的占位行**（它只是为了让用户有地方敲下一项，不是数据）。
     */
    var text: String
        get() = KeyValueLines.render(model.dataRows(), separator)
        set(value) {
            model.reset(KeyValueLines.parse(value, separator))
        }

    /** 勾选了的行，name -> value；未勾选的、名字空的都不算 */
    fun toMap(): Map<String, String> = KeyValueLines.toMap(model.dataRows())

    /**
     * 勾选了的行，按表格顺序**保留重名**。
     *
     * 回写地址栏只能用它，不能用 [toMap]：`?ids=1&ids=2&ids=3` 是 Spring `List<Long>` 的标准写法，
     * 过一遍 Map 会被折叠成 `ids=3` —— 用户看着表格里三行，地址栏却悄悄少了两个值。
     */
    fun pairs(): List<Pair<String, String>> = model.dataRows()
        .filter { it.enabled && it.name.isNotBlank() }
        .map { it.name to it.value }

    /** 勾选了的行数，用于页签 badge */
    fun enabledCount(): Int = model.dataRows().count { it.enabled && it.name.isNotBlank() }

    /**
     * 填充只读的附加列（Params 的「类型」从源码读来，按参数名对应）。
     *
     * 单独一个入口而不是塞进 [text]：这一列不参与落盘，它是源码派生的，
     * 混进文本模型会在「批量编辑」里冒出用户改不动的列。
     */
    fun setExtraValues(values: Map<String, String>) {
        model.extraValues = values
        model.fireTableDataChanged()
    }

    /** 直接改某一行的值（multipart 的「选文件…」用它把路径填进选中行） */
    fun setValueAt(row: Int, value: String) {
        model.setValueAt(value, row, COL_VALUE)
    }

    /** 当前选中行；没选中就当作末尾那行空白占位行（用户刚打开页签就点「选文件」是常态） */
    fun selectedRow(): Int =
        table.selectedRow.takeIf { it >= 0 } ?: (model.rowCount - 1).coerceAtLeast(0)

    /**
     * 若这行还没名字，就把光标送进「名字」格。
     *
     * multipart 选完文件的兜底：文件是一行的值，没有字段名 [toMap] 会把它当空行丢掉，
     * 服务端收不到文件却毫无提示。选完直接聚焦名字格，逼着补一个名字。
     */
    fun editNameIfBlank(row: Int) {
        if (row !in 0 until model.rowCount) return
        if ((model.getValueAt(row, COL_NAME) as? String).orEmpty().isNotBlank()) return
        val viewColumn = table.convertColumnIndexToView(COL_NAME)
        table.setRowSelectionInterval(row, row)
        if (table.editCellAt(row, viewColumn)) {
            table.editorComponent?.requestFocusInWindow()
        }
    }

    private fun removeSelectedRow() {
        val row = table.selectedRow
        if (row < 0) return
        model.remove(row)
        onCommit()
    }

    /**
     * 列宽分配。
     *
     * 优先级就是「用户要敲的东西优先」：`参数名` / `参数值` 是要输入的，
     * 而「类型」是从源码派生的只读列（`String` / `int` 这种值 56px 够了）、「说明」多数行是空的 ——
     * 这两个原先按 preferred 比例拿走了 400px 里的三分之一，把要敲值的列挤到只显示一个字符。
     *
     * `AUTO_RESIZE_LAST_COLUMN` 让压缩只发生在最右边那列（也就是「说明」），
     * 而不是 `JBTable` 默认的按比例压所有列。
     */
    private fun sizeColumns() {
        val checkbox = JBUI.scale(28)
        table.columnModel.getColumn(COL_ENABLED).apply {
            minWidth = checkbox
            maxWidth = checkbox
            preferredWidth = checkbox
        }
        table.columnModel.getColumn(COL_NAME).preferredWidth = JBUI.scale(120)
        table.columnModel.getColumn(COL_VALUE).preferredWidth = JBUI.scale(160)
        table.columnModel.getColumn(COL_COMMENT).apply {
            preferredWidth = JBUI.scale(60)
            minWidth = 0
        }
        if (extraTitle != null) {
            table.columnModel.getColumn(COL_EXTRA).apply {
                preferredWidth = JBUI.scale(56)
                maxWidth = JBUI.scale(56)
            }
        }
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
    }

    /**
     * 把「类型」搬到「说明」前面，和需求参考图的 `参数名 / 参数值 / 类型 / 说明` 对齐。
     *
     * 走 [javax.swing.table.TableColumnModel.moveColumn]（**只动视图顺序**）而不是互换
     * `COL_COMMENT` / `COL_EXTRA` 这两个模型索引常量：`getColumnCount()` 在没有「类型」列时返回 4，
     * 互换常量会让"缺的那一列"从末尾跑到中间，`getValueAt` / `isCellEditable` 一串分支都得跟着改，
     * 而视图移动一行搞定且模型不用动。必须在 [sizeColumns] 之后调 —— 之前视图索引与模型索引还一致。
     */
    private fun reorderColumns() {
        if (extraTitle == null) return
        table.columnModel.moveColumn(COL_EXTRA, COL_COMMENT)
    }

    /**
     * 表格模型。
     *
     * 末尾**恒定留一行全空的占位行**：不留的话用户得先点「+」才能加下一项，
     * 而现成的 API 客户端都是「在最后一行开始敲，自动长出新行」。
     */
    private class Model(
        private val nameTitle: String,
        private val valueTitle: String,
        private val extraTitle: String?
    ) : AbstractTableModel() {

        private val rows = mutableListOf(KvRow())

        var extraValues: Map<String, String> = emptyMap()
        var onCommit: () -> Unit = {}

        fun reset(incoming: List<KvRow>) {
            rows.clear()
            rows.addAll(incoming)
            rows.add(KvRow())
            fireTableDataChanged()
        }

        /** 去掉末尾占位行后的真实数据 */
        fun dataRows(): List<KvRow> = rows.filterNot { it.isBlank() }

        fun remove(index: Int) {
            if (index !in rows.indices) return
            rows.removeAt(index)
            if (rows.isEmpty() || !rows.last().isBlank()) rows.add(KvRow())
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = if (extraTitle == null) 4 else 5

        override fun getColumnName(column: Int): String = when (column) {
            COL_ENABLED -> ""
            COL_NAME -> nameTitle
            COL_VALUE -> valueTitle
            COL_COMMENT -> "说明"
            else -> extraTitle.orEmpty()
        }

        // 勾选列声明成 Boolean，JBTable 才会渲染成复选框而不是 "true" / "false" 文本
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == COL_ENABLED) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex != COL_EXTRA

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                // 末尾占位行不显示勾：勾的语义是「这一项会发出去」，一个空名字配着勾会让人以为
                // 自己多敲了一行空参数。用户一敲名字，copy 保留的 enabled = true 就让勾自动出现
                COL_ENABLED -> row.enabled && !row.isBlank()
                COL_NAME -> row.name
                COL_VALUE -> row.value
                COL_COMMENT -> row.comment
                else -> extraValues[row.name].orEmpty()
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val old = rows[rowIndex]
            val text = value?.toString().orEmpty()
            rows[rowIndex] = when (columnIndex) {
                COL_ENABLED -> old.copy(enabled = value as? Boolean ?: true)
                COL_NAME -> old.copy(name = text)
                COL_VALUE -> old.copy(value = text)
                COL_COMMENT -> old.copy(comment = text)
                else -> old
            }
            // 在占位行里敲了东西 → 它转正，末尾再长一行新的占位行
            if (rowIndex == rows.lastIndex && !rows[rowIndex].isBlank()) {
                rows.add(KvRow())
                fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
            }
            fireTableCellUpdated(rowIndex, columnIndex)
            onCommit()
        }

        private fun KvRow.isBlank(): Boolean =
            name.isBlank() && value.isBlank() && comment.isBlank()
    }

    private companion object {
        const val COL_ENABLED = 0
        const val COL_NAME = 1
        const val COL_VALUE = 2
        const val COL_COMMENT = 3
        const val COL_EXTRA = 4
    }
}
