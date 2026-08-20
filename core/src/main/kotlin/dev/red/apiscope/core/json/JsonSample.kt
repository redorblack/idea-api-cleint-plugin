package dev.red.apiscope.core.json

/**
 * 极简 JSON 树 + 格式化输出
 *
 * 放在 core 而非 plugin：PSI 只负责「类型 → 树」，缩进/转义这类纯逻辑留在可测试的地方。
 * 不引第三方 JSON 库，避免和 IntelliJ 平台自带的 Jackson 版本冲突。
 *
 * @author Red
 * @since 2026-08-14
 */
sealed interface JsonSample {

    data class Str(val value: String) : JsonSample
    data class Num(val value: String) : JsonSample
    data class Bool(val value: Boolean) : JsonSample
    data object Null : JsonSample
    data class Arr(val items: List<JsonSample>) : JsonSample
    data class Obj(val fields: List<Pair<String, JsonSample>>) : JsonSample

    /** 无法解析的类型，输出成注释友好的占位对象 */
    data object Unknown : JsonSample

    fun render(indent: Int = 2): String = StringBuilder().also { write(it, indent, 0) }.toString()

    private fun write(sb: StringBuilder, indent: Int, level: Int) {
        when (this) {
            is Str -> sb.append('"').append(escape(value)).append('"')
            is Num -> sb.append(value)
            is Bool -> sb.append(value)
            Null -> sb.append("null")
            Unknown -> sb.append("{}")

            is Arr -> if (items.isEmpty()) sb.append("[]") else {
                sb.append('[')
                items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    sb.append('\n').indent(indent, level + 1)
                    item.write(sb, indent, level + 1)
                }
                sb.append('\n').indent(indent, level).append(']')
            }

            is Obj -> if (fields.isEmpty()) sb.append("{}") else {
                sb.append('{')
                fields.forEachIndexed { index, (name, value) ->
                    if (index > 0) sb.append(',')
                    sb.append('\n').indent(indent, level + 1)
                    sb.append('"').append(escape(name)).append("\": ")
                    value.write(sb, indent, level + 1)
                }
                sb.append('\n').indent(indent, level).append('}')
            }
        }
    }

    private fun StringBuilder.indent(indent: Int, level: Int): StringBuilder =
        append(" ".repeat(indent * level))

    private fun escape(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}
