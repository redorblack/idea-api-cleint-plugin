package dev.red.apiscope.plugin.psi

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiReferenceExpression

/**
 * Spring / Feign 注解常量与读取工具
 *
 * @author Red
 * @since 2026-08-14
 */
object SpringPsi {

    private const val WEB = "org.springframework.web.bind.annotation"

    const val REQUEST_MAPPING = "$WEB.RequestMapping"
    const val REST_CONTROLLER = "$WEB.RestController"
    const val CONTROLLER = "org.springframework.stereotype.Controller"
    const val REQUEST_BODY = "$WEB.RequestBody"
    const val REQUEST_PARAM = "$WEB.RequestParam"
    const val PATH_VARIABLE = "$WEB.PathVariable"
    const val REQUEST_HEADER = "$WEB.RequestHeader"
    const val COOKIE_VALUE = "$WEB.CookieValue"
    const val MODEL_ATTRIBUTE = "$WEB.ModelAttribute"

    /** 参数上出现这些注解之一，就说明它已经有明确归属，不再当作"隐式绑 query" */
    val BINDING_ANNOTATIONS = setOf(
        REQUEST_BODY, REQUEST_PARAM, PATH_VARIABLE, REQUEST_HEADER, COOKIE_VALUE,
        "$WEB.RequestPart", "$WEB.RequestAttribute", "$WEB.SessionAttribute", "$WEB.MatrixVariable"
    )

    /** 快捷映射注解 → HTTP method */
    val SHORTCUT_MAPPINGS = mapOf(
        "$WEB.GetMapping" to "GET",
        "$WEB.PostMapping" to "POST",
        "$WEB.PutMapping" to "PUT",
        "$WEB.DeleteMapping" to "DELETE",
        "$WEB.PatchMapping" to "PATCH"
    )

    val ALL_MAPPINGS: Set<String> = SHORTCUT_MAPPINGS.keys + REQUEST_MAPPING

    /**
     * 读注解的字符串属性
     *
     * 三种写法都要支持，否则大量真实代码读不到路径：
     * `@PostMapping("/list")`、`@PostMapping(path = {"/list"})`、`@PostMapping(Constants.LIST)`
     */
    fun stringAttribute(annotation: PsiAnnotation, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            annotation.findDeclaredAttributeValue(name)?.let(::asString)
        }

    private fun asString(value: PsiAnnotationMemberValue): String? = when (value) {
        is PsiArrayInitializerMemberValue ->
            value.initializers.firstNotNullOfOrNull { asString(it) }

        else -> constantValue(value)?.takeIf { it.isNotEmpty() }
    }

    /** 常量引用（`Constants.PATH`）也要能求值，直接取 text 会拿到标识符而不是值 */
    private fun constantValue(element: PsiElement): String? {
        if (element is PsiExpression) {
            val helper = JavaPsiFacade.getInstance(element.project).constantEvaluationHelper
            helper.computeConstantExpression(element)?.let { return it.toString().trim() }
        }
        if (element is PsiReferenceExpression) {
            val resolved = element.resolve()
            if (resolved is com.intellij.psi.PsiVariable) {
                resolved.computeConstantValue()?.let { return it.toString().trim() }
            }
        }
        return element.text?.trim()?.removeSurrounding("\"")?.takeIf { !it.contains('.') || it.startsWith("/") }
    }

    /** `@RequestMapping(method = RequestMethod.POST)` 里的 method */
    fun requestMappingHttpMethod(annotation: PsiAnnotation): String? {
        val value = annotation.findDeclaredAttributeValue("method") ?: return null
        val text = when (value) {
            is PsiArrayInitializerMemberValue -> value.initializers.firstOrNull()?.text
            else -> value.text
        } ?: return null
        return text.substringAfterLast('.').trim().takeIf { it.isNotEmpty() }
    }
}
