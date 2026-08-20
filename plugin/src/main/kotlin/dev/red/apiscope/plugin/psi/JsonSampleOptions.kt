package dev.red.apiscope.plugin.psi

/**
 * DTO → JSON 示例的生成参数（原 `.apiscope.yml` 的 json 段，配置文件形态取消后就地给默认值）。
 *
 * 替代已删除的 `core.convention.JsonSampleDef`；原 `includeNulls` 字段是死配置
 * （生成器从没读过），确认后不带过来。
 *
 * @author Red
 * @since 2026-08-14
 */
data class JsonSampleOptions(
    /** 递归深度上限，防自引用 DTO 爆栈 */
    val maxDepth: Int = 6,
    /** 集合字段生成几个元素 */
    val sampleListSize: Int = 1
)
