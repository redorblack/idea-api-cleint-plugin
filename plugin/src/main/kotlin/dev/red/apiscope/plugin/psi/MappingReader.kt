package dev.red.apiscope.plugin.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod

/**
 * Spring 映射注解的统一读取
 *
 * 只做类级 + 方法级路径拼接，`{id}` 占位符保持原样。
 *
 * @author Red
 * @since 2026-08-14
 */
object MappingReader {

    fun mappingAnnotation(method: PsiMethod): PsiAnnotation? =
        method.annotations.firstOrNull { it.qualifiedName in SpringPsi.ALL_MAPPINGS }

    fun httpMethod(mapping: PsiAnnotation): String {
        SpringPsi.SHORTCUT_MAPPINGS[mapping.qualifiedName]?.let { return it }
        // @RequestMapping 未写 method 时 Spring 接受所有 method，取 GET 作为最保守的默认
        return SpringPsi.requestMappingHttpMethod(mapping) ?: "GET"
    }

    /** 类级 + 方法级路径拼接；保留 `{id}` 原样，匹配两侧必须用同一形态 */
    fun rawPath(owner: PsiClass, mapping: PsiAnnotation): String {
        val classPath = owner.annotations
            .firstOrNull { it.qualifiedName == SpringPsi.REQUEST_MAPPING }
            ?.let { SpringPsi.stringAttribute(it, "value", "path") }
        val methodPath = SpringPsi.stringAttribute(mapping, "value", "path")
        return join(classPath, methodPath)
    }

    fun join(vararg parts: String?): String {
        val segments = parts.filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "/" }
            .map { it.trim('/') }
            .filter { it.isNotEmpty() }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }
}
