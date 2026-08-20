package dev.red.apiscope.plugin.psi

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * 从 Controller 所在 module 的 `application.yml` / `.properties` 里读出本地地址
 *
 * 为什么值得读：默认下拉只有 `http://localhost:8080`，而微服务里一人一段端口，非 8080 是常态。
 * 「填 Base URL」是"装上插件→发出第一个请求"这条路上**唯一需要外部知识**的一步：
 * 新人点了 gutter、点了发送，得到「连接被拒绝」，然后得自己去翻 `application.yml`。
 *
 * **和被删掉的 Consul / 网关推断划清界限**：那一套删得对 —— 它跨服务猜地址、四级解析链、
 * 猜错时的排错成本高于自己填一次。这里只做**同一个 module 内的确定性读取**，
 * 结果仅作为下拉框里的一个候选项出现，不参与任何解析优先级，用户随时能改能删。
 *
 * 读不到、或读出来有歧义就返回 null —— 宁可不给，也不给个错的。
 *
 * @author Red
 * @since 2026-08-14
 */
object ServerAddress {

    /** 形如 `http://localhost:9101/ctx`；读不到端口就返回 null */
    fun suggest(element: PsiElement): String? {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
        val scope = GlobalSearchScope.moduleScope(module)

        val texts = CONFIG_FILES
            .flatMap { name -> FilenameIndex.getVirtualFilesByName(name, scope) }
            .mapNotNull { file -> runCatching { VfsUtilCore.loadText(file) }.getOrNull() }
        if (texts.isEmpty()) return null

        val port = texts.firstNotNullOfOrNull { portOf(it) } ?: return null
        val contextPath = texts.firstNotNullOfOrNull { contextPathOf(it) }.orEmpty()
        return "http://localhost:$port" + contextPath.trimEnd('/')
    }

    /**
     * `server.port`。
     *
     * yml 只认**顶层 `server:` 块下缩进一级**的 `port:` —— 直接全文搜 `port:` 会把
     * `management.server.port`、`redis.port`、`datasource` 里的端口一起捞进来。
     */
    private fun portOf(text: String): String? =
        PROPERTIES_PORT.find(text)?.groupValues?.get(1)
            ?: YAML_SERVER_BLOCK.find(text)?.let { block ->
                YAML_PORT.find(block.groupValues[1])?.groupValues?.get(1)
            }

    private fun contextPathOf(text: String): String? =
        PROPERTIES_CONTEXT_PATH.find(text)?.groupValues?.get(1)
            ?: YAML_SERVER_BLOCK.find(text)?.let { block ->
                YAML_CONTEXT_PATH.find(block.groupValues[1])?.groupValues?.get(1)
            }

    private val CONFIG_FILES = listOf(
        "application.yml",
        "application.yaml",
        "application.properties",
        "bootstrap.yml",
        "bootstrap.yaml"
    )

    private val PROPERTIES_PORT = Regex("""(?m)^\s*server\.port\s*[=:]\s*(\d+)\s*$""")
    private val PROPERTIES_CONTEXT_PATH =
        Regex("""(?m)^\s*server\.servlet\.context-path\s*[=:]\s*(\S+)\s*$""")

    /** 顶层 `server:` 到下一个顶层 key（或文件尾）之间的内容 */
    private val YAML_SERVER_BLOCK = Regex("""(?m)^server:\s*$([\s\S]*?)(?=^\S|\z)""")
    private val YAML_PORT = Regex("""(?m)^\s+port:\s*(\d+)\s*$""")
    private val YAML_CONTEXT_PATH = Regex("""(?m)^\s+context-path:\s*(\S+)\s*$""")
}
