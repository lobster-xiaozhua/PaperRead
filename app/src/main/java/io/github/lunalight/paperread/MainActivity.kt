package io.github.lunalight.paperread

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.lunalight.paperread.ui.nav.PaperReadNavHost
import io.github.lunalight.paperread.ui.theme.PaperReadTheme

/** 单 Activity 入口，全应用唯一 Activity。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 朗读控制通知的运行时权限（Android 13+）：仅用于展示前台服务通知，
        // 拒绝不影响朗读本身（服务仍以前台方式运行，仅通知不可见）。
        requestNotificationPermissionIfNeeded()
        setContent {
            PaperReadTheme {
                PaperReadNavHost()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 结果不改变朗读能力：仅决定控制通知是否可见。
        }.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** 通知/服务点击回到阅读器时携带的书籍 id（NavHost 据此直接打开 ReaderRoute）。 */
        const val EXTRA_BOOK_ID = "io.github.lunalight.paperread.extra.BOOK_ID"
    }
}
