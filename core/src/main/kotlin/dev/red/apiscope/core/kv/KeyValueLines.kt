package dev.red.apiscope.core.kv

/**
 * 一行键值数据：勾选状态 + 名 + 值 + 说明
 *
 * 带上 enabled 和 comment 而不是直接用 `Pair<String, String>`，是因为表格视图里这两列由用户直接编辑，
 * 解析时一旦丢弃，切回文本视图就会永久损坏用户的输入。
 *
 * 四个字段都给默认值，方便调用方只关心自己在意的那几列。
 *
 * @author Red
 * @since 2026-08-14
 */
data class KvRow(
    val enabled: Boolean = true,
    val name: String = "",
    val value: String = "",
    val comment: String = ""
)

/**
 * 键值行的分隔符形态
 *
 * 只有两种：`EQUALS` 对应 `name = value`（查询参数、表单字段、变量定义），
 * `COLON` 对应 `Name: Value`（HTTP header、Cookie）。
 * 不做成「任意分隔字符」，是因为文本要和用户从别处复制来的写法长得一样，多余的自由度只会产出互不兼容的方言。
 *
 * @author Red
 * @since 2026-08-14
 */
enum class KvSeparator { EQUALS, COLON }

/**
 * 键值行文本 ↔ [KvRow] 列表的双向转换
 *
 * 表格视图和批量编辑的文本视图共用同一份数据、且只以纯文本落盘，所以 [parse] 与 [render] 必须互逆：
 * [render] 出来的文本再 [parse] 回来必须得到完全相同的 rows，否则用户来回切一次视图数据就变形了。
 * 互逆性是「值为空不留尾随空格」「注释固定两空格 + `#` + 空格」这些格式细节的唯一理由，
 * 也是判定 `#` 归属（注释 vs 值内容）时的仲裁标准。
 *
 * 容错优先于严格：连分隔符都还没敲完的行也要保留（见 [parse]），不能因为用户正在打字就让整行消失。
 *
 * @author Red
 * @since 2026-08-14
 */
object KeyValueLines {

    private const val COMMENT_MARKER = '#'

    /** 注释与正文之间留两个空格，是 properties / http 文件里最常见的写法，也让文本视图更容易看出列 */
    private const val COMMENT_GAP = "  # "

    /**
     * 解析多行文本：空白行跳过，其余每行产出一个 [KvRow]，顺序与输入一致。
     *
     * 行首 `#` 表示「勾选框没勾」，剥掉标记后与启用行走同一条解析路径。
     */
    fun parse(text: String, separator: KvSeparator): List<KvRow> {
        val separatorChar = separator.char()
        // lineSequence 已按 \r\n / \n / \r 三种换行切分，Windows 换行与跨平台粘贴的文本无需额外处理
        return text.lineSequence()
            .mapNotNull { rawLine -> parseLine(rawLine.trim(), separatorChar) }
            .toList()
    }

    /**
     * 渲染回文本：一行一个 [KvRow]，顺序不变，末尾不带多余换行。
     */
    fun render(rows: List<KvRow>, separator: KvSeparator): String {
        val separatorToken = separator.renderToken()
        return rows.joinToString("\n") { row ->
            buildString {
                // 未勾选写成行首 `#`：在文本视图里既是注释、又能一眼看出这行被停用了
                if (!row.enabled) append(COMMENT_MARKER).append(' ')
                append(row.name).append(separatorToken)
                // 值为空时不补空格，否则 parse→render 会凭空长出看不见的尾随空格，diff 里全是噪音
                if (row.value.isNotEmpty()) append(' ').append(row.value)
                if (row.comment.isNotEmpty()) append(COMMENT_GAP).append(row.comment)
            }
        }
    }

    /**
     * 折成真正要发出去的键值对：只收勾选中、且名字非空白的行。
     *
     * 空 value 照收 —— `?a=` 和 `X-Debug:` 都是合法写法，语义不同于「不传这个 key」。
     * 同名后者覆盖前者，与用户从上往下读文本的直觉一致。
     */
    fun toMap(rows: List<KvRow>): Map<String, String> {
        // LinkedHashMap 保住插入顺序：query string 与 header 的顺序会影响签名计算和抓包可读性
        val result = LinkedHashMap<String, String>()
        for (row in rows) {
            if (!row.enabled || row.name.isBlank()) continue
            result[row.name] = row.value
        }
        return result
    }

    private fun parseLine(line: String, separatorChar: Char): KvRow? {
        if (line.isEmpty()) return null

        val disabled = line.startsWith(COMMENT_MARKER)
        // 剥掉停用标记后复用启用行的解析逻辑，避免两套规则各自演化到对不上
        val body = if (disabled) line.substring(1).removePrefix(" ") else line
        // 只剩一个光秃秃的 `#`（没有任何键值内容）当作占位，不产出垃圾行
        if (body.isBlank()) return null

        val commentAt = inlineCommentIndex(body, separatorChar)
        val comment = if (commentAt < 0) "" else body.substring(commentAt + 1).trim()
        val content = if (commentAt < 0) body else body.substring(0, commentAt)

        val separatorAt = content.indexOf(separatorChar)
        // 没有分隔符的行整行留成 name：用户敲到一半时这行不能凭空消失
        if (separatorAt < 0) {
            return KvRow(enabled = !disabled, name = content.trim(), comment = comment)
        }
        return KvRow(
            enabled = !disabled,
            // 只按第一个分隔符切：`Host: example.com:8080`、`token=a=b=c` 的值里本来就带分隔符
            name = content.substring(0, separatorAt).trim(),
            value = content.substring(separatorAt + 1).trim(),
            comment = comment
        )
    }

    /**
     * 行内注释起点，没有则返回 -1。
     *
     * 两条约束都是为了别把合法值截断：
     * 1. `#` 前面必须有空白字符 —— 否则 `url = http://x/y#frag` 的 fragment 会丢；
     * 2. 值区第一个非空白字符就是 `#` 时按值处理 —— 否则 `color = #fff` 的色值会丢。
     *    唯一例外是这个 `#` 后面紧跟空白（`name =  # 说明`），那是 render 输出「空值 + 注释」的固定形状，
     *    按注释处理才能保住往返互逆。
     */
    private fun inlineCommentIndex(body: String, separatorChar: Char): Int {
        var contentStart = body.indexOf(separatorChar) + 1
        while (contentStart < body.length && body[contentStart].isWhitespace()) contentStart++

        for (index in 1 until body.length) {
            if (body[index] != COMMENT_MARKER || !body[index - 1].isWhitespace()) continue
            if (index == contentStart && !isFollowedByWhitespace(body, index)) continue
            return index
        }
        return -1
    }

    private fun isFollowedByWhitespace(body: String, index: Int): Boolean =
        index + 1 < body.length && body[index + 1].isWhitespace()

    private fun KvSeparator.char(): Char = when (this) {
        KvSeparator.EQUALS -> '='
        KvSeparator.COLON -> ':'
    }

    /** 等号的习惯写法两侧都留空格（`a = 1`），冒号沿用 HTTP header 的 `Name: Value` 只在右侧留 */
    private fun KvSeparator.renderToken(): String = when (this) {
        KvSeparator.EQUALS -> " ="
        KvSeparator.COLON -> ":"
    }
}
