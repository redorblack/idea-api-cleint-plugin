package dev.red.apiscope.plugin.psi

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil
import dev.red.apiscope.core.json.JsonSample
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * DTO 类型 → 全字段 JSON 示例
 *
 * 处理要点（都是实测会崩的地方）：
 * - 泛型：`R<PageListBaseRes<XxxRes>>` 用 [PsiSubstitutor] 逐层代入实参，不是按名字猜
 * - 父类字段：分页请求的 pageNum/pageSize 常在基类，必须沿继承链向上收集
 * - 自引用：`ShippingInfoVO` 里再引用自己会无限递归，用路径集合 + 深度双重兜底
 * - Jackson 注解：`@JsonIgnore` 跳过、`@JsonProperty` 用注解里的名字，否则生成的 body 字段名是错的
 *
 * @author Red
 * @since 2026-08-14
 */
class JsonSampleGenerator(private val config: JsonSampleOptions = JsonSampleOptions()) {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 请求体示例 */
    fun generate(type: PsiType?): JsonSample = build(type, 0, emptySet())

    private fun build(type: PsiType?, depth: Int, visiting: Set<String>): JsonSample {
        if (type == null) return JsonSample.Unknown
        if (depth > config.maxDepth) return JsonSample.Unknown

        if (type is PsiPrimitiveType) return primitive(type.name)
        if (type is PsiArrayType) return array(build(type.componentType, depth + 1, visiting))

        val classType = type as? PsiClassType ?: return JsonSample.Unknown
        val resolveResult = classType.resolveGenerics()
        val psiClass = resolveResult.element ?: return JsonSample.Unknown
        val substitutor = resolveResult.substitutor
        val fqn = psiClass.qualifiedName ?: return JsonSample.Unknown

        scalar(fqn, psiClass)?.let { return it }

        // Optional<T> 直接当 T
        if (fqn == "java.util.Optional") {
            return build(classType.parameters.firstOrNull(), depth, visiting)
        }

        // Collection / Iterable
        PsiUtil.extractIterableTypeParameter(classType, false)?.let { itemType ->
            return array(build(itemType, depth + 1, visiting))
        }

        // Map<K, V> → 用占位 key 展示 value 结构
        mapValueType(classType, psiClass)?.let { valueType ->
            return JsonSample.Obj(listOf("key" to build(valueType, depth + 1, visiting)))
        }

        if (psiClass.isEnum) return enumSample(psiClass)
        if (psiClass.isInterface && psiClass.fields.isEmpty()) return JsonSample.Unknown

        // 自引用：同一条路径上再次出现同一个类就停手
        if (fqn in visiting) return JsonSample.Unknown

        val fields = collectFields(psiClass).mapNotNull { field ->
            if (isSkipped(field)) return@mapNotNull null
            val fieldType = substitutor.substitute(field.type)
            jsonName(field) to build(fieldType, depth + 1, visiting + fqn)
        }
        return JsonSample.Obj(fields)
    }

    /** 沿继承链收集字段：本类在前，父类在后，同名字段以子类为准 */
    private fun collectFields(psiClass: PsiClass): List<PsiField> {
        val result = LinkedHashMap<String, PsiField>()
        var current: PsiClass? = psiClass
        var guard = 0
        while (current != null && guard++ < 20) {
            val fqn = current.qualifiedName
            if (fqn == CharSequence::class.java.name || fqn == "java.lang.Object") break
            current.fields.forEach { field -> result.putIfAbsent(field.name, field) }
            current = current.superClass
        }
        return result.values.toList()
    }

    private fun isSkipped(field: PsiField): Boolean {
        if (field.hasModifierProperty(PsiModifier.STATIC)) return true
        if (field.hasModifierProperty(PsiModifier.TRANSIENT)) return true
        if (field.name == "serialVersionUID") return true
        return field.annotations.any { it.qualifiedName?.endsWith("JsonIgnore") == true }
    }

    /** `@JsonProperty("order_no")` 时 body 里必须用注解值，否则服务端收不到 */
    private fun jsonName(field: PsiField): String {
        val annotation = field.annotations.firstOrNull { it.qualifiedName?.endsWith("JsonProperty") == true }
        val declared = annotation
            ?.findAttributeValue("value")
            ?.text
            ?.trim()
            ?.removeSurrounding("\"")
        return declared?.takeIf { it.isNotEmpty() } ?: field.name
    }

    private fun mapValueType(classType: PsiClassType, psiClass: PsiClass): PsiType? {
        if (!InheritanceHelper.isMap(psiClass)) return null
        val mapClass = InheritanceHelper.findMapClass(psiClass) ?: return null
        val typeParameters = mapClass.typeParameters
        if (typeParameters.size < 2) return null
        val substitutor = TypeConversionUtil.getSuperClassSubstitutor(mapClass, classType)
        return substitutor.substitute(typeParameters[1])
    }

    private fun enumSample(psiClass: PsiClass): JsonSample {
        val firstConstant = psiClass.fields.firstOrNull { it is com.intellij.psi.PsiEnumConstant }?.name
        return JsonSample.Str(firstConstant ?: "")
    }

    private fun array(item: JsonSample): JsonSample =
        JsonSample.Arr(List(config.sampleListSize.coerceAtLeast(0)) { item })

    private fun primitive(name: String): JsonSample = when (name) {
        "boolean" -> JsonSample.Bool(false)
        "char" -> JsonSample.Str("")
        "void" -> JsonSample.Null
        "float", "double" -> JsonSample.Num("0.0")
        else -> JsonSample.Num("0")
    }

    /** 已知标量类型的样例值 —— 命中则不再递归展开字段 */
    private fun scalar(fqn: String, psiClass: PsiClass): JsonSample? = when (fqn) {
        "java.lang.String", "java.lang.CharSequence", "java.lang.Character" -> JsonSample.Str("")
        "java.lang.Boolean" -> JsonSample.Bool(false)
        "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
        "java.math.BigInteger", "java.util.concurrent.atomic.AtomicLong" -> JsonSample.Num("0")

        "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> JsonSample.Num("0.00")
        "java.util.UUID" -> JsonSample.Str("00000000-0000-0000-0000-000000000000")
        "java.time.LocalDate" -> JsonSample.Str(LocalDate.now().toString())
        "java.time.LocalTime" -> JsonSample.Str("00:00:00")
        "java.time.LocalDateTime", "java.util.Date", "java.time.Instant",
        "java.sql.Timestamp", "java.time.OffsetDateTime", "java.time.ZonedDateTime" ->
            JsonSample.Str(LocalDateTime.now().withNano(0).format(dateFormatter))

        "java.lang.Object" -> JsonSample.Unknown
        else -> if (psiClass.qualifiedName?.endsWith("MultipartFile") == true) {
            JsonSample.Str("<file>")
        } else {
            null
        }
    }
}

/** Map 判定抽出来，避免在生成器里塞一堆继承树遍历 */
private object InheritanceHelper {

    fun isMap(psiClass: PsiClass): Boolean = findMapClass(psiClass) != null

    fun findMapClass(psiClass: PsiClass): PsiClass? {
        if (psiClass.qualifiedName == "java.util.Map") return psiClass
        val seen = HashSet<String>()
        val queue = ArrayDeque<PsiClass>()
        queue += psiClass
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val fqn = current.qualifiedName
            if (fqn != null && !seen.add(fqn)) continue
            if (fqn == "java.util.Map") return current
            current.supers.forEach { queue += it }
            if (seen.size > 60) return null
        }
        return null
    }
}
