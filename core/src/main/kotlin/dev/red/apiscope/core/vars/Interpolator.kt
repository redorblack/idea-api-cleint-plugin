package dev.red.apiscope.core.vars

/**
 * Postman 风格的 `{{变量}}` 插值器：把地址栏 / Headers / Body 里的占位符替换成
 * 「变量」页签里配置的值
 *
 * 未定义变量为什么原样保留、绝不替换成空串：如果 `{{token}}` 因为拼错名字而没命中，
 * 悄悄变成空串会发出一个「地址看起来完整、实际少了一段」的请求，用户很难从结果反推
 * 是插值出了问题；原样保留 `{{token}}` 能让请求本身就长得不对，UI 也才有东西可报错
 * （配合 [missing] 提前提示）。
 *
 * 为什么不递归展开：如果变量值里又含 `{{...}}`，替换一次就停手，不再对结果二次插值。
 * 一是变量互相引用容易写出自引用死循环（a 引用 b、b 又引用 a），二是真实场景里变量值
 * 就是最终字面量（一段 URL、一段 token），不需要「变量的变量」这种间接层。
 *
 * @author Red
 * @since 2026-08-14
 */
object Interpolator {

    // 变量名两侧允许空白（`{{ baseUrl }}` 与 `{{baseUrl}}` 等价），但名字本身不能再含 { 或 }——
    // 这样嵌套的 `{{{{x}}}}` 之类畸形输入不会被贪婪匹配吞掉外层花括号
    private val PLACEHOLDER = Regex("""\{\{([^{}]*)}}""")

    fun apply(text: String, variables: Map<String, String>): String {
        if (text.isEmpty()) return text
        return PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[1].trim()
            // 空名字 `{{}}` 不是合法占位符，原样保留；找不到值同理原样保留（见类注释）
            if (name.isEmpty()) return@replace match.value
            variables[name] ?: match.value
        }
    }

    fun missing(text: String, variables: Map<String, String>): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = LinkedHashSet<String>()
        for (match in PLACEHOLDER.findAll(text)) {
            val name = match.groupValues[1].trim()
            if (name.isEmpty()) continue
            if (!variables.containsKey(name)) result.add(name)
        }
        return result.toList()
    }
}
