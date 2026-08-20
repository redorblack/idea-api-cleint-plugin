package dev.red.apiscope.plugin.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.red.apiscope.core.endpoint.QueryStrings

/**
 * 地址栏 ⇄ Params 表的往返必须是无损的
 *
 * 这里钉住的是一次真实的数据丢失：回写地址栏原先走 `toMap()`，`LinkedHashMap` 会把重名参数
 * 折叠成最后一个 —— `?ids=1&ids=2&ids=3`（Spring `List<Long>` 的标准写法）粘进来，
 * 表格里看得见三行，随手去 Headers 页签敲一个字，地址栏就静默变成 `?ids=3`。
 *
 * @author Red
 * @since 2026-08-14
 */
class ParamsWritebackTest : BasePlatformTestCase() {

    private fun tabs() = RequestTabs(project, onChange = {}, onParamsCommit = {})

    fun testDuplicateNamesSurviveTheRoundTrip() {
        val tabs = tabs()
        tabs.setParamsFromUrl(QueryStrings.split("/order/list?ids=1&ids=2&ids=3").params)

        assertEquals(
            listOf("ids" to "1", "ids" to "2", "ids" to "3"),
            tabs.paramPairs()
        )
    }

    fun testToMapStillCollapsesDuplicates() {
        val tabs = tabs()
        tabs.setParamsFromUrl(QueryStrings.split("/x?ids=1&ids=2").params)

        // 发请求用的 header/变量映射本来就该是 Map，这里只是说明「回写地址栏不能用它」
        assertEquals(mapOf("ids" to "2"), tabs.params.toMap())
    }

    fun testUncheckedAndUnnamedRowsAreNotWrittenBack() {
        val tabs = tabs()
        // 行首 # 等于「没勾」，空名字的行不算数据
        tabs.params.text = "a = 1\n# b = 2\n = 3"

        assertEquals(listOf("a" to "1"), tabs.paramPairs())
    }

    fun testEmptyValuesKeepTheirPlace() {
        val tabs = tabs()
        tabs.setParamsFromUrl(QueryStrings.split("/x?a=&b=2").params)

        assertEquals(listOf("a" to "", "b" to "2"), tabs.paramPairs())
    }
}
