package io.github.lunalight.paperread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M1 冒烟测试：验证 JVM 单元测试工具链可用。
 * M2 起替换为解析引擎的真实单测（见 docs/TASKS.md）。
 */
class SmokeTest {

    @Test
    fun toolchainSanity() {
        assertEquals(4, 2 + 2)
    }
}
