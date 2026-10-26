package io.github.lunalight.paperread

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.lunalight.paperread.ui.nav.PaperReadNavHost
import io.github.lunalight.paperread.ui.theme.PaperReadTheme

/**
 * 单 Activity 入口，全应用唯一 Activity。
 *
 * 权限策略（AC-20.2）：**不在冷启动批量申请任何运行时权限**。
 * POST_NOTIFICATIONS 的唯一用途是朗读控制通知，已改为场景化申请——
 * 用户首次在阅读器点朗读时，先弹场景说明对话框再发起系统申请，
 * 拒绝不影响朗读本身（见 ReaderViewModel 的 speechPermissionEvent）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PaperReadTheme {
                PaperReadNavHost()
            }
        }
    }

    companion object {
        /** 通知/服务点击回到阅读器时携带的书籍 id（NavHost 据此直接打开 ReaderRoute）。 */
        const val EXTRA_BOOK_ID = "io.github.lunalight.paperread.extra.BOOK_ID"
    }
}
