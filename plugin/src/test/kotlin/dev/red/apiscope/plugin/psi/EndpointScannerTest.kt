package dev.red.apiscope.plugin.psi

import com.intellij.psi.PsiClass
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.red.apiscope.core.endpoint.EndpointDescriptor

/**
 * Controller 扫描的真实 PSI 验证
 *
 * @author Red
 * @since 2026-08-14
 */
class EndpointScannerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_LATEST_WITH_LATEST_JDK

    override fun setUp() {
        super.setUp()
        val web = "package org.springframework.web.bind.annotation;"
        myFixture.addClass("$web public enum RequestMethod { GET, POST, PUT, DELETE, PATCH }")
        myFixture.addClass("$web public @interface RequestMapping { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }")
        myFixture.addClass("$web public @interface RestController {}")
        myFixture.addClass("$web public @interface PostMapping { String[] value() default {}; String[] path() default {}; }")
        myFixture.addClass("$web public @interface GetMapping { String[] value() default {}; String[] path() default {}; }")
        myFixture.addClass("$web public @interface RequestBody {}")
        myFixture.addClass("$web public @interface RequestParam { String value() default \"\"; String name() default \"\"; boolean required() default true; }")
        myFixture.addClass("$web public @interface PathVariable { String value() default \"\"; String name() default \"\"; }")
        myFixture.addClass("package org.springframework.cloud.openfeign; public @interface FeignClient { String value() default \"\"; String name() default \"\"; String path() default \"\"; String url() default \"\"; }")
        myFixture.addClass("package p; public class OrderListReq { private String orderNo; }")
        myFixture.addClass("package p; public class OrderRes { private Long id; }")
        myFixture.addClass("$web public @interface RequestHeader { String value() default \"\"; String name() default \"\"; boolean required() default true; }")
        myFixture.addClass("$web public @interface CookieValue { String value() default \"\"; String name() default \"\"; boolean required() default true; }")
        myFixture.addClass("package p; public class Paths { public static final String LIST = \"/list\"; }")
        myFixture.addClass("package p; public class OrderQueryDTO { private String orderNo; private Integer pageNo; }")
        myFixture.addClass("package javax.servlet.http; public interface HttpServletRequest {}")
    }

    fun testRequestHeaderGoesToTheHeadersTab() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class TenantController {
                /**
                 * @param tenant 租户号
                 */
                @GetMapping("/list")
                public OrderRes list(@RequestHeader("X-Tenant-Id") String tenant,
                                     @RequestHeader(value = "X-Trace-Id", required = false) String trace) {
                    return null;
                }
            }
            """
        )
        assertEquals(listOf("X-Tenant-Id", "X-Trace-Id"), descriptor.headers.map { it.name })
        assertTrue("没写 required 就是必填", descriptor.headers[0].required)
        assertFalse(descriptor.headers[1].required)
        // javadoc 的 @param 用形参名，不是注解里的对外名
        assertEquals("租户号", descriptor.headers[0].comment)
        assertTrue("header 不该混进 query 参数", descriptor.queryParams.isEmpty())
    }

    fun testCookieValueGoesToTheCookieTab() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class SessionController {
                @GetMapping("/me")
                public OrderRes me(@CookieValue("JSESSIONID") String sid) { return null; }
            }
            """
        )
        assertEquals(listOf("JSESSIONID"), descriptor.cookies.map { it.name })
        assertTrue(descriptor.queryParams.isEmpty())
    }

    fun testUnannotatedPojoIsFlattenedIntoQueryParams() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class QueryController {
                @GetMapping("/list")
                public OrderRes list(OrderQueryDTO query) { return null; }
            }
            """
        )
        // 没有这条，GET + DTO 的接口点了 gutter 面板全空：既没 body 也没 params
        assertEquals(listOf("orderNo", "pageNo"), descriptor.queryParams.map { it.name })
        assertEquals(listOf("String", "Integer"), descriptor.queryParams.map { it.type })
        assertTrue("源码里没有「必填」这个信息，一律不勾", descriptor.queryParams.none { it.required })
        assertNull("GET + DTO 不该被当成请求体", descriptor.bodyJson)
    }

    fun testUnannotatedSimpleParamBecomesOneQueryParam() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class SimpleController {
                @GetMapping("/search")
                public OrderRes search(String keyword, int pageNo) { return null; }
            }
            """
        )
        assertEquals(listOf("keyword", "pageNo"), descriptor.queryParams.map { it.name })
        assertEquals("1", descriptor.queryParams[1].sampleValue)
    }

    fun testFrameworkParametersAreSkipped() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            import javax.servlet.http.HttpServletRequest;
            @RestController
            public class ServletController {
                @GetMapping("/ping")
                public OrderRes ping(HttpServletRequest request) { return null; }
            }
            """
        )
        assertTrue("HttpServletRequest 是 Spring 注入的，不是用户要填的参数", descriptor.queryParams.isEmpty())
    }

    fun testControllerClassAndMethodPathsAreJoined() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/bck/vip/shipping")
            public class ShippingBckController {
                @PostMapping("/list")
                public OrderRes list(@RequestBody OrderListReq req) { return null; }
            }
            """
        )
        assertEquals("POST", descriptor.httpMethod)
        assertEquals("/bck/vip/shipping/list", descriptor.path)
        assertEquals("ShippingBckController#list", descriptor.displayName)
        assertNotNull("有 @RequestBody 就该生成 body", descriptor.bodyJson)
        assertTrue(descriptor.bodyJson!!.contains("\"orderNo\""))
    }

    fun testRequestParamAndPathVariableHandling() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/bck/order")
            public class OrderBckController {
                @GetMapping("/detail/{orderId}")
                public OrderRes detail(@PathVariable("orderId") Long orderId,
                                       @RequestParam("keyword") String keyword,
                                       @RequestParam(value = "size", required = false) Integer size) {
                    return null;
                }
            }
            """
        )
        assertEquals("GET", descriptor.httpMethod)
        assertEquals("路径变量应替换为样例值，否则生成的 URL 直接 404", "/bck/order/detail/1", descriptor.path)
        assertEquals(2, descriptor.queryParams.size)
        assertEquals("keyword", descriptor.queryParams[0].name)
        assertTrue(descriptor.queryParams[0].required)
        assertFalse("required = false 应被读出", descriptor.queryParams[1].required)
        assertNull("无 @RequestBody 不该生成 body", descriptor.bodyJson)
    }

    /**
     * 类型和说明是插件相对通用 API 客户端的独有优势（别家只能手填），所以这两列必须真读到。
     *
     * 三个点分别锁住：`presentableText` 给的是短名不是全限定名、javadoc 的 `@param` 按**形参名**
     * 匹配（不是 `@RequestParam` 改过的对外名）、多行说明被压成单行（表格单元格放不下换行）。
     */
    fun testQueryParamCarriesTypeAndJavadocComment() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/bck/order")
            public class OrderBckController {
                /**
                 * 分页查订单。
                 *
                 * @param pageNum 页码，
                 *                从 1 开始
                 * @param keyword 模糊搜索关键词
                 * @param notAParam 这个参数不存在
                 */
                @GetMapping("/list")
                public OrderRes list(@RequestParam("page") Integer pageNum,
                                     @RequestParam("keyword") String keyword) {
                    return null;
                }
            }
            """
        )
        assertEquals(2, descriptor.queryParams.size)

        val page = descriptor.queryParams[0]
        assertEquals("对外名来自 @RequestParam", "page", page.name)
        assertEquals("Integer", page.type)
        assertEquals("多行 javadoc 要压成单行", "页码， 从 1 开始", page.comment)

        val keyword = descriptor.queryParams[1]
        assertEquals("String", keyword.type)
        assertEquals("模糊搜索关键词", keyword.comment)
    }

    fun testQueryParamWithoutJavadocHasEmptyComment() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class NoDocController {
                @GetMapping("/plain")
                public OrderRes plain(@RequestParam("size") int size) { return null; }
            }
            """
        )
        assertEquals("int", descriptor.queryParams[0].type)
        assertEquals("没有 javadoc 时说明为空串，不是 null", "", descriptor.queryParams[0].comment)
    }

    fun testRequestMappingWithExplicitMethodAndConstantPath() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/bck/order")
            public class OrderBckController {
                @RequestMapping(value = Paths.LIST, method = RequestMethod.PUT)
                public OrderRes list() { return null; }
            }
            """
        )
        assertEquals("PUT", descriptor.httpMethod)
        assertEquals("常量引用的路径也要能求值", "/bck/order/list", descriptor.path)
    }

    /** Feign 客户端不再是端点：面板只发「本工程提供的接口」，Feign 声明交给源码本身 */
    fun testFeignClientIsNotAnEndpoint() {
        val psiClass = myFixture.addClass(
            """
            package p;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "my-gateway", path = "/order-admin-api")
            public interface ShippingPlatformApi {
                @PostMapping("/bck/vip/shipping/list")
                OrderRes list(@RequestBody OrderListReq req);
            }
            """.trimIndent()
        )
        val method = psiClass.methods.first()
        assertFalse("@FeignClient 方法不该挂 gutter 图标", EndpointScanner.isEndpoint(method))
        assertNull(EndpointScanner.scan(method))
    }

    fun testNonEndpointMethodsAreIgnored() {
        val psiClass = myFixture.addClass(
            """
            package p;
            public class PlainService {
                public void doWork() {}
            }
            """.trimIndent()
        )
        val method = psiClass.findMethodsByName("doWork", false).first()
        assertFalse(EndpointScanner.isEndpoint(method))
        assertNull(EndpointScanner.scan(method))
    }

    fun testStringPathVariableKeepsItsPlaceholder() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/bck/user")
            public class UserBckController {
                @GetMapping("/{name}/detail")
                public OrderRes detail(@PathVariable("name") String name) { return null; }
            }
            """
        )
        assertEquals(
            "String 没有猜得出来的样例值，占位符必须留着等人补 —— 替成空串会得到 /bck/user//detail 这种「看起来完整」的错地址",
            "/bck/user/{name}/detail",
            descriptor.path
        )
    }

    fun testUnmatchedPathVariableNameIsLeftForTheUser() {
        val descriptor = scanFirst(
            """
            package p;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class MismatchController {
                @GetMapping("/order/{orderId}")
                public OrderRes detail(@PathVariable("id") Long id) { return null; }
            }
            """
        )
        // 占位符名字和注解对不上时也不能瞎替，留着由发送前的校验拦下来
        assertEquals("/order/{orderId}", descriptor.path)
    }

    private fun scanFirst(classText: String): EndpointDescriptor {
        val psiClass: PsiClass = myFixture.addClass(classText.trimIndent())
        val method = psiClass.methods.first()
        return requireNotNull(EndpointScanner.scan(method)) { "扫描失败：${psiClass.name}#${method.name}" }
    }
}
