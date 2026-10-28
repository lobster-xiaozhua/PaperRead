package io.github.lunalight.paperread.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/**
 * 测试专用：把 [UiText] 解析成最终文案（Robolectric application resources）。
 *
 * 断言「解析结果 == 资源化前的原字面量」，等价于在每个调用点核对
 * AC-21.5 的「纯搬运、内容零变更」；不需要 Compose 组合上下文。
 * 仅可在 Robolectric 环境（有 application）调用。
 */
fun UiText?.resolveForTest(): String? =
    this?.resolve(ApplicationProvider.getApplicationContext<Context>())
