package dev.red.apiscope.plugin.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiUtil
import dev.red.apiscope.core.endpoint.EndpointDescriptor
import dev.red.apiscope.core.endpoint.QueryParam

/**
 * PSI 方法 → [EndpointDescriptor]
 *
 * 只认 Spring Controller：面板要的是「把这个接口发出去」，源码里能读到的就是路径、
 * 查询参数和请求体结构，地址由用户在面板上给 Base URL。
 *
 * 无状态，做成 object：gutter 每渲染一个方法都会调 [isEndpoint]，每次 new 一个扫描器
 * 连带 new 一个 [JsonSampleGenerator] 纯属浪费。
 *
 * @author Red
 * @since 2026-08-14
 */
object EndpointScanner {

    private val jsonGenerator = JsonSampleGenerator()

    fun scan(method: PsiMethod): EndpointDescriptor? {
        val owner = method.containingClass ?: return null
        if (!isController(owner)) return null

        val mapping = MappingReader.mappingAnnotation(method) ?: return null

        return EndpointDescriptor(
            httpMethod = MappingReader.httpMethod(mapping),
            path = substitutePathVariables(MappingReader.rawPath(owner, mapping), method),
            displayName = "${owner.name}#${method.name}",
            queryParams = queryParams(method),
            bodyJson = bodyJson(method),
            headers = annotatedParams(method, SpringPsi.REQUEST_HEADER),
            cookies = annotatedParams(method, SpringPsi.COOKIE_VALUE),
            suggestedBaseUrl = ServerAddress.suggest(method)
        )
    }

    /** 方法是否值得挂 gutter 图标 */
    fun isEndpoint(method: PsiMethod): Boolean {
        val owner = method.containingClass ?: return false
        return isController(owner) && MappingReader.mappingAnnotation(method) != null
    }

    fun isController(owner: PsiClass): Boolean =
        owner.hasAnnotation(SpringPsi.REST_CONTROLLER) || owner.hasAnnotation(SpringPsi.CONTROLLER)

    /** `@RequestParam` 显式声明的 + 没有注解但按约定也会绑到 query 的 */
    private fun queryParams(method: PsiMethod): List<QueryParam> =
        annotatedParams(method, SpringPsi.REQUEST_PARAM) + implicitQueryParams(method)

    /**
     * 读某一个绑定注解（`@RequestParam` / `@RequestHeader` / `@CookieValue`）下的参数。
     *
     * 三者形状完全一样：对外名（注解的 value/name，缺省用形参名）、样例值、是否必填、类型、说明。
     */
    private fun annotatedParams(method: PsiMethod, annotationFqn: String): List<QueryParam> {
        // javadoc 一次读完按参数名查，别在循环里对每个参数重新解析一遍注释
        val comments = JavadocParams.of(method)
        return method.parameterList.parameters.mapNotNull { parameter ->
            val annotation = parameter.getAnnotation(annotationFqn) ?: return@mapNotNull null
            val name = SpringPsi.stringAttribute(annotation, "value", "name") ?: parameter.name
            QueryParam(
                name = name,
                sampleValue = sampleValueOf(parameter.type),
                required = SpringPsi.stringAttribute(annotation, "required") != "false",
                type = parameter.type.presentableText,
                // javadoc 里的 @param 用的是**形参名**，不是注解改过的对外名
                comment = comments[parameter.name].orEmpty()
            )
        }
    }

    /**
     * 没写任何绑定注解的参数 —— Spring 照样会把它绑到 query 上，源码里也读得到。
     *
     * 不读它的话，`public R list(OrderQueryDTO q)` 这种 GET 接口点了 gutter **面板全空**：
     * 既没有 body 也没有 params，等于白点，而这在 Spring 项目里很常见。
     *
     * - 简单类型（`String keyword`）→ 一行参数，名字就是形参名
     * - 项目自己的 POJO（查询条件对象）→ **只摊开一层**字段。嵌套对象绑 query 本身就少见，
     *   再往下摊只会灌进一堆 `a.b.c` 噪音
     * - `HttpServletRequest` / `Model` / `MultipartFile` 这类框架参数直接跳过
     *
     * 一律 `required = false`（源码里没有"必填"这个信息）：查询条件对象的字段多半是可选筛选项，
     * 全勾上会让第一次发送就带一堆空值。
     */
    private fun implicitQueryParams(method: PsiMethod): List<QueryParam> {
        val comments = JavadocParams.of(method)
        return method.parameterList.parameters.flatMap { parameter ->
            when {
                parameter.annotations.any { it.qualifiedName in SpringPsi.BINDING_ANNOTATIONS } -> emptyList()
                isFrameworkType(parameter.type) -> emptyList()

                else -> flattenableClass(parameter.type)?.let { owner ->
                    owner.allFields
                        .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
                        .map { field ->
                            QueryParam(
                                name = field.name,
                                sampleValue = sampleValueOf(field.type),
                                required = false,
                                type = field.type.presentableText
                            )
                        }
                } ?: listOf(
                    QueryParam(
                        name = parameter.name,
                        sampleValue = sampleValueOf(parameter.type),
                        required = false,
                        type = parameter.type.presentableText,
                        comment = comments[parameter.name].orEmpty()
                    )
                )
            }
        }
    }

    /** 能摊开成一堆 query 字段的类：项目自己的 POJO。JDK / 枚举 / 接口都不算 */
    private fun flattenableClass(type: PsiType): PsiClass? {
        val owner = PsiUtil.resolveClassInClassTypeOnly(type) ?: return null
        if (owner.isEnum || owner.isInterface || owner.isAnnotationType) return null
        val qualified = owner.qualifiedName ?: return null
        return owner.takeIf { JDK_PACKAGES.none { prefix -> qualified.startsWith(prefix) } }
    }

    /** Spring 自己会注入的参数，不是用户要填的东西 */
    private fun isFrameworkType(type: PsiType): Boolean =
        type.presentableText.substringBefore('<').substringAfterLast('.') in FRAMEWORK_TYPES

    private fun bodyJson(method: PsiMethod): String? {
        val bodyParam = method.parameterList.parameters
            .firstOrNull { it.getAnnotation(SpringPsi.REQUEST_BODY) != null }
            ?: return null
        return jsonGenerator.generate(bodyParam.type).render()
    }

    /**
     * 把 `/detail/{id}` 里的占位符换成样例值，否则生成的 URL 直接就是 404。
     *
     * **取不到样例值时保留 `{name}` 原样**（String 类型没有"猜得出来"的样例）：换成空串会得到
     * `/user/` 这种**看起来完整**的错地址，命中另一个 mapping 或 404，比留个显眼的占位符难查得多。
     * 留着它，发送前由 `RequestPanel` 拦下来提示补值 —— 和「未定义 `{{变量}}` 拦住不发」同一套心智。
     */
    private fun substitutePathVariables(path: String, method: PsiMethod): String {
        if (!path.contains('{')) return path
        var result = path
        method.parameterList.parameters.forEach { parameter ->
            val annotation = parameter.getAnnotation(SpringPsi.PATH_VARIABLE) ?: return@forEach
            val name = SpringPsi.stringAttribute(annotation, "value", "name") ?: parameter.name
            val sample = sampleValueOf(parameter.type).takeIf { it.isNotEmpty() } ?: return@forEach
            result = result.replace("{$name}", sample)
        }
        return result
    }

    private fun sampleValueOf(type: PsiType): String {
        val name = type.presentableText.substringAfterLast('.')
        return when {
            name in NUMERIC_TYPES -> "1"
            name == "Boolean" || name == "boolean" -> "false"
            else -> ""
        }
    }

    private fun PsiClass.hasAnnotation(fqn: String): Boolean = getAnnotation(fqn) != null

    /** 这些包下的类型当简单值处理，不摊开字段 */
    private val JDK_PACKAGES = listOf("java.", "javax.", "jakarta.", "kotlin.", "scala.", "groovy.")

    private val FRAMEWORK_TYPES = setOf(
        "HttpServletRequest", "HttpServletResponse", "HttpSession", "ServletRequest", "ServletResponse",
        "Model", "ModelMap", "ModelAndView", "BindingResult", "Errors", "SessionStatus",
        "Principal", "Authentication", "Locale", "TimeZone", "ZoneId",
        "MultipartFile", "MultipartHttpServletRequest", "Part",
        "InputStream", "OutputStream", "Reader", "Writer",
        "UriComponentsBuilder", "RedirectAttributes", "WebRequest", "NativeWebRequest", "HttpEntity"
    )

    private val NUMERIC_TYPES = setOf(
        "int", "long", "short", "byte", "double", "float",
        "Integer", "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger"
    )
}

/** [PsiParameter] 扩展：读取参数上的注解，找不到返回 null */
private fun PsiParameter.getAnnotation(fqn: String): PsiAnnotation? =
    annotations.firstOrNull { it.qualifiedName == fqn }
