package dev.red.apiscope.plugin.psi

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiType
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * DTO → JSON 生成的真实 PSI 验证
 *
 * 用真 JDK（非 mock）跑，否则 java.time / BigDecimal 解析不出来，测不到标量分支。
 *
 * @author Red
 * @since 2026-08-14
 */
class JsonSampleGeneratorTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_LATEST_WITH_LATEST_JDK

    private val generator by lazy {
        JsonSampleGenerator(JsonSampleOptions(maxDepth = 6, sampleListSize = 1))
    }

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonIgnore {}")
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }")
        myFixture.addClass("package p; public class BaseReq { private Integer pageNum; private Integer pageSize; }")
        myFixture.addClass("package p; public enum StatusEnum { ACTIVE, INACTIVE }")
        myFixture.addClass("package p; public class ItemVO { private String name; private java.math.BigDecimal amount; }")
    }

    fun testInheritedFieldsAreIncluded() {
        val json = generate(
            """
            package p;
            public class OrderReq extends BaseReq {
                private Long orderId;
            }
            """
        )
        assertTrue("子类字段缺失: $json", json.contains("\"orderId\": 0"))
        assertTrue("父类字段缺失（分页参数常在基类）: $json", json.contains("\"pageNum\": 0"))
        assertTrue(json.contains("\"pageSize\": 0"))
    }

    fun testEnumUsesFirstConstantAndDecimalIsNumeric() {
        val json = generate(
            """
            package p;
            public class OrderReq {
                private StatusEnum status;
                private java.math.BigDecimal amount;
            }
            """
        )
        assertTrue("枚举应取第一个常量: $json", json.contains("\"status\": \"ACTIVE\""))
        assertTrue("BigDecimal 应是数字而非字符串: $json", json.contains("\"amount\": 0.00"))
    }

    fun testCollectionAndMapExpandNestedStructure() {
        val json = generate(
            """
            package p;
            import java.util.List;
            import java.util.Map;
            public class OrderReq {
                private List<ItemVO> items;
                private Map<String, ItemVO> mapping;
            }
            """
        )
        assertTrue("List 元素应展开为对象: $json", json.contains("\"items\": [") && json.contains("\"name\": \"\""))
        assertTrue("Map 应以占位 key 展示 value 结构: $json", json.contains("\"mapping\": {") && json.contains("\"key\": {"))
    }

    fun testSkippedAndRenamedFields() {
        val json = generate(
            """
            package p;
            public class OrderReq {
                private static final long serialVersionUID = 1L;
                private transient String tempToken;
                @com.fasterxml.jackson.annotation.JsonIgnore private String secret;
                @com.fasterxml.jackson.annotation.JsonProperty("order_no") private String orderNo;
            }
            """
        )
        assertFalse("static 字段不该出现: $json", json.contains("serialVersionUID"))
        assertFalse("transient 字段不该出现: $json", json.contains("tempToken"))
        assertFalse("@JsonIgnore 字段不该出现: $json", json.contains("secret"))
        assertTrue("@JsonProperty 应改用注解里的名字: $json", json.contains("\"order_no\""))
        assertFalse("不该同时输出原字段名: $json", json.contains("\"orderNo\""))
    }

    fun testSelfReferenceDoesNotRecurseForever() {
        val json = generate(
            """
            package p;
            import java.util.List;
            public class TreeNode {
                private String name;
                private TreeNode parent;
                private List<TreeNode> children;
            }
            """
        )
        // 只要没栈溢出/超时，且自引用位置收敛为空对象即可
        assertTrue("自引用应收敛: $json", json.contains("\"parent\": {}"))
    }

    fun testDateTimeFieldsRenderAsFormattedStrings() {
        val json = generate(
            """
            package p;
            public class OrderReq {
                private java.time.LocalDate day;
                private java.time.LocalDateTime createTime;
            }
            """
        )
        assertTrue("LocalDate 应是 yyyy-MM-dd: $json", Regex("\"day\": \"\\d{4}-\\d{2}-\\d{2}\"").containsMatchIn(json))
        assertTrue(
            "LocalDateTime 应带时间: $json",
            Regex("\"createTime\": \"\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\"").containsMatchIn(json)
        )
    }

    private fun generate(classText: String): String {
        val psiClass = myFixture.addClass(classText.trimIndent())
        return generator.generate(typeOf(psiClass)).render()
    }

    private fun typeOf(psiClass: PsiClass): PsiType =
        PsiElementFactory.getInstance(project).createType(psiClass)
}
