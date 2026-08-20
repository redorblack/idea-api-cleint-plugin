package dev.red.apiscope.plugin.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 历史弹窗里那一行标签
 *
 * cURL 导入会把**整条 URL** 放进路径框（导入的地址往往和当前 Base URL 无关，硬拆会看不出发去哪），
 * 于是老写法 `"$method $baseUrl$path"` 会拼出 `POST http://localhost:8080http://baidu.com/x`，
 * 列表直接不可读。
 *
 * @author Red
 * @since 2026-08-14
 */
class HistoryEntryLabelTest {

    @Test
    fun `相对路径拼在 Base URL 后面`() {
        assertEquals("POST http://localhost:8080/order/list", entry("POST", "http://localhost:8080", "/order/list").label)
    }

    @Test
    fun `路径本身是整条地址时不再拼 Base URL`() {
        assertEquals("POST http://baidu.com/x", entry("POST", "http://localhost:8080", "http://baidu.com/x").label)
    }

    @Test
    fun `https 同样不拼`() {
        assertEquals("GET https://api.github.com/user", entry("GET", "http://localhost:8080", "https://api.github.com/user").label)
    }

    private fun entry(method: String, baseUrl: String, path: String) = ApiScopeState.HistoryEntry().apply {
        this.method = method
        this.baseUrl = baseUrl
        this.path = path
    }
}
