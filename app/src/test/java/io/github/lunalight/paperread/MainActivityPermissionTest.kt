package io.github.lunalight.paperread

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 冷启动权限行为测试（T-009 · R-02 / AC-20.2）。
 *
 * AC-20.2：应用不得在冷启动时批量申请运行时权限，运行时权限申请必须
 * 绑定功能场景。POST_NOTIFICATIONS 已改为阅读器首次朗读前场景化申请
 * （ReaderViewModel），冷启动路径不应发起任何 Activity / 权限申请。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityPermissionTest {

    @Test
    fun `冷启动不发起任何权限申请或页面跳转`() {
        val app = ApplicationProvider.getApplicationContext<PaperReadApp>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val shadow = shadowOf(activity)
        assertNull(
            "冷启动不得 startActivity（含权限申请页）",
            shadow.nextStartedActivity,
        )
        assertNull(
            "冷启动不得 startActivityForResult（含权限申请）",
            shadow.nextStartedActivityForResult,
        )
        // 通知权限保持未授权状态：证明没有「静默申请」路径
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        org.junit.Assert.assertFalse("冷启动后通知权限应仍未授权（未申请）", granted)
        assertNull("Application 侧同样不应有跳转", shadowOf(app).nextStartedActivity)
    }
}
