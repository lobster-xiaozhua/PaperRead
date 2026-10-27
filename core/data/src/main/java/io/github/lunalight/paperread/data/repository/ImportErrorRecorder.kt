package io.github.lunalight.paperread.data.repository

/**
 * 导入失败的外报回调（T-021 · AC-19.4「最近导入失败」）。
 *
 * 为什么是接口而不是直接写日志：`:core:data` **不得**反向依赖 `:app`
 * （模块依赖是 app → data，反过来会让编译图成环）。数据层只声明「我产出了
 * 一条失败」，具体怎么存（环形 5 条、落 JSON、进诊断包）由 app 的
 * `diag.ImportErrorLog` 实现，装配点（`AppContainer`）把两者接起来。
 *
 * 语义要求：**绝不能抛**。导入失败已经在给用户看错误了，记录失败不该再
 * 制造第二个错误——实现方须自行吞掉 IO 异常（见 ImportErrorLog）。
 *
 * 用 `fun interface` 是为了让「不记录」的默认值能写成一行 [NONE]，
 * 同时保持单方法语义（一个回调，不做多事件总线）。
 */
fun interface ImportErrorRecorder {

    /** 记录一次导入 / 解析失败（在产出 [ImportError] 的位置调用）。 */
    fun record(error: ImportError)

    companion object {

        /** 空实现：未装配诊断模块时（测试 / 预览）的默认值。 */
        val NONE: ImportErrorRecorder = ImportErrorRecorder { }
    }
}
