package io.github.lunalight.paperread

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.lunalight.paperread.ui.nav.PaperReadNavHost
import io.github.lunalight.paperread.ui.theme.PaperReadTheme

/** 单 Activity 入口，全应用唯一 Activity。 */
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
}
