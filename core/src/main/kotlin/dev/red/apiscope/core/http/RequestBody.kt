package dev.red.apiscope.core.http

/**
 * 请求 body 的四种形态（对齐 Postman：none / raw / form-urlencoded / multipart）
 *
 * 用 sealed interface 而不是「body: String? + type: enum」两个字段拼凑，
 * 是因为每种形态需要的数据结构完全不同（form 是键值对、multipart 还带文件），
 * 塞进一个 String 字段迟早要么塞不下、要么靠约定字符串区分类型，两者都不安全。
 *
 * @author Red
 * @since 2026-08-14
 */
sealed interface RequestBody {

    /** 无 body（GET/DELETE 等常见形态） */
    data object None : RequestBody

    /** raw 文本 body，contentType 决定 Content-Type 头，可以是 JSON/XML/纯文本等任意格式 */
    data class Text(val content: String, val contentType: String = "application/json; charset=utf-8") : RequestBody

    /** x-www-form-urlencoded，字段用 List<Pair> 而不是 Map，保留用户填写的顺序和重复 key */
    data class Form(val fields: List<Pair<String, String>>) : RequestBody

    /** multipart/form-data，可混合普通字段和文件字段 */
    data class Multipart(val parts: List<MultipartPart>) : RequestBody
}

/** multipart 里的一个 part：普通文本字段，或引用本地文件的字段 */
sealed interface MultipartPart {
    data class Field(val name: String, val value: String) : MultipartPart
    data class FileRef(val name: String, val path: String) : MultipartPart
}
