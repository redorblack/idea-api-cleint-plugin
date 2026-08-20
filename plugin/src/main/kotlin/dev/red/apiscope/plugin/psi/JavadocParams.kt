package dev.red.apiscope.plugin.psi

import com.intellij.psi.PsiMethod
import com.intellij.psi.javadoc.PsiDocTag

/**
 * 从方法 Javadoc 的 `@param` 抽出参数说明
 *
 * 这是插件相对通用 API 客户端的独有优势：参数的**类型、是否必填、中文说明**在源码里本来就有，
 * 现成的工具只能靠人手填或导 OpenAPI。抓出来直接填进 Params 表的「说明」列，一分钱不用用户出。
 *
 * @author Red
 * @since 2026-08-14
 */
object JavadocParams {

    /** 参数名 -> 说明；没有 javadoc 或没有 `@param` 时返回空 map */
    fun of(method: PsiMethod): Map<String, String> {
        val doc = method.docComment ?: return emptyMap()
        return doc.findTagsByName("param")
            .mapNotNull { tag ->
                val name = tag.valueElement?.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                name to description(tag)
            }
            .filter { (_, description) -> description.isNotEmpty() }
            .toMap()
    }

    /**
     * `@param` 后面除参数名之外的部分。
     *
     * `dataElements` 的第 0 项就是参数名本身（即 `valueElement`），所以要跳过。
     * 多行说明由 PSI 拆成多个元素、且已经去掉了行首的 `*`，这里再把换行压成单空格 ——
     * 表格单元格是单行的，留着换行只会显示成一个方框。
     */
    private fun description(tag: PsiDocTag): String =
        tag.dataElements.drop(1)
            .joinToString(" ") { it.text }
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("""\s+""")
}
