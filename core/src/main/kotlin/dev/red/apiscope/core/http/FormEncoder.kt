package dev.red.apiscope.core.http

import java.net.URLEncoder

/**
 * x-www-form-urlencoded 编码器
 *
 * @author Red
 * @since 2026-08-14
 */
object FormEncoder {

    const val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"

    fun encode(fields: List<Pair<String, String>>): String = fields.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
}
