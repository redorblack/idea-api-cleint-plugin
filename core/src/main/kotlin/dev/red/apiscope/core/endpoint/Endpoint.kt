package dev.red.apiscope.core.endpoint

/**
 * 一个可调用端点的描述 —— 由 PSI 扫描产出，填进请求面板
 *
 * 定义在 core 而非 plugin，是为了让 query string 拼接这类纯逻辑能脱离 IntelliJ 独立测试。
 *
 * @author Red
 * @since 2026-08-14
 */
data class EndpointDescriptor(
    val httpMethod: String,
    /** 类级 + 方法级 mapping 拼接后的路径，如 /bck/vip/shipping/list */
    val path: String,
    /** 展示名，如 ShippingBckController#list */
    val displayName: String = "",
    val queryParams: List<QueryParam> = emptyList(),
    val bodyJson: String? = null,
    /**
     * `@RequestHeader` 读到的必填头（如网关/多租户体系里的 `X-Tenant-Id`）。
     *
     * 不读它的代价很具体：面板"看起来填好了"，一发就是 400 或空数据，
     * 用户得回去翻方法签名手抄 —— 一键起手的承诺在那一刻就破了。
     */
    val headers: List<QueryParam> = emptyList(),
    /** `@CookieValue` 读到的 cookie，形状同 [headers] */
    val cookies: List<QueryParam> = emptyList(),
    /**
     * 从本 module 的 `application.yml` 读出来的本地地址（如 `http://localhost:9101/ctx`）。
     *
     * 只是**一个候选项**：Base URL 仍然由人给，插件不猜、不参与解析优先级。
     * 微服务里一人一段端口，默认的 8080 基本不对，而这是"发出第一个请求"路上唯一需要外部知识的一步。
     */
    val suggestedBaseUrl: String? = null
)

data class QueryParam(
    val name: String,
    val sampleValue: String = "",
    val required: Boolean = false,
    /** 参数类型，如 int / String / LocalDate，来自源码里的参数声明 */
    val type: String = "",
    /** 参数说明，来自方法 Javadoc 的 @param */
    val comment: String = ""
)
