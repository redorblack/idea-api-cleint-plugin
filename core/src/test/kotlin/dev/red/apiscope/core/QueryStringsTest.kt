package dev.red.apiscope.core

import dev.red.apiscope.core.endpoint.QueryParam
import dev.red.apiscope.core.endpoint.QueryStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryStringsTest {

    @Test
    fun `不含问号时整串都是 base`() {
        val split = QueryStrings.split("/bck/vip/shipping/list")
        assertEquals("/bck/vip/shipping/list", split.base)
        assertTrue(split.params.isEmpty())
    }

    @Test
    fun `单个参数`() {
        val split = QueryStrings.split("/list?page=1")
        assertEquals("/list", split.base)
        assertEquals(listOf("page" to "1"), split.params)
    }

    @Test
    fun `多个参数保持原始顺序`() {
        val split = QueryStrings.split("http://x/y?page=1&size=20&name=red")
        assertEquals("http://x/y", split.base)
        assertEquals(listOf("page" to "1", "size" to "20", "name" to "red"), split.params)
    }

    @Test
    fun `以问号结尾得到空参数列表`() {
        val split = QueryStrings.split("http://x/y?")
        assertEquals("http://x/y", split.base)
        assertTrue(split.params.isEmpty())
    }

    @Test
    fun `value 内部的等号不被二次切分`() {
        assertEquals(listOf("a" to "1=2"), QueryStrings.split("/x?a=1=2").params)
    }

    @Test
    fun `段里没有等号时 value 为空串`() {
        assertEquals(listOf("flag" to "", "a" to "1"), QueryStrings.split("/x?flag&a=1").params)
    }

    @Test
    fun `空段被跳过`() {
        assertEquals(listOf("a" to "1", "b" to "2"), QueryStrings.split("/x?a=1&&b=2").params)
    }

    @Test
    fun `名字为空的段被跳过`() {
        assertEquals(listOf("b" to "2"), QueryStrings.split("/x?=1&b=2").params)
    }

    @Test
    fun `只有第一个问号是切分点`() {
        val split = QueryStrings.split("/x?a=1?b=2")
        assertEquals("/x", split.base)
        assertEquals(listOf("a" to "1?b=2"), split.params)
    }

    @Test
    fun `value 不被 URL 解码`() {
        assertEquals(listOf("a" to "%E4%B8%AD"), QueryStrings.split("/x?a=%E4%B8%AD").params)
    }

    @Test
    fun `name 与 value 两侧空白不被 trim`() {
        assertEquals(listOf(" a " to " 1 "), QueryStrings.split("/x? a = 1 ").params)
    }

    @Test
    fun `fragment 留在最后一个 value 里`() {
        assertEquals(listOf("a" to "1#top"), QueryStrings.split("/x?a=1#top").params)
    }

    @Test
    fun `append 后再 split 能拿回等价的参数列表`() {
        val params = listOf(
            QueryParam("page", "1"),
            QueryParam("size", "20", required = true),
            QueryParam("name", "red", type = "String", comment = "用户名")
        )
        val url = QueryStrings.append("/bck/vip/shipping/list", params)

        val split = QueryStrings.split(url)
        assertEquals("/bck/vip/shipping/list", split.base)
        assertEquals(params.map { it.name to it.sampleValue }, split.params)
    }

    @Test
    fun `append 到已带 query 的 URL 后 split 能拿回全部参数`() {
        val url = QueryStrings.append("/x?token=abc", listOf(QueryParam("page", "1")))
        assertEquals("/x?token=abc&page=1", url)
        assertEquals(listOf("token" to "abc", "page" to "1"), QueryStrings.split(url).params)
    }
}
