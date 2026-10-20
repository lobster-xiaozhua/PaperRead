package io.github.lunalight.paperread.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.lunalight.paperread.MainActivity
import io.github.lunalight.paperread.ui.about.AboutScreen
import io.github.lunalight.paperread.ui.about.LicenseScreen
import io.github.lunalight.paperread.ui.bookshelf.BookshelfScreen
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.settings.SettingsScreen
import io.github.lunalight.paperread.ui.transfer.TransferScreen

/** 全应用导航图。 */
@Composable
fun PaperReadNavHost() {
    val navController = rememberNavController()

    // 朗读通知点击回到阅读器：通知携带 bookId，冷启动时直接定位到该书，
    // 而不是落在书架再让用户自己找。
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(Unit) {
        val bookId = activity?.intent?.getLongExtra(MainActivity.EXTRA_BOOK_ID, -1L) ?: -1L
        if (bookId > 0L) {
            navController.navigate(ReaderRoute(bookId))
        }
    }

    NavHost(navController = navController, startDestination = BookshelfRoute) {

        composable<BookshelfRoute> {
            BookshelfScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenBook = { bookId -> navController.navigate(ReaderRoute(bookId)) },
                onStartTransfer = { bookIds -> navController.navigate(TransferRoute(bookIds)) },
            )
        }

        composable<ReaderRoute> { backStackEntry ->
            val route: ReaderRoute = backStackEntry.toRoute()
            ReaderScreen(bookId = route.bookId, onBack = { navController.popBackStack() })
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(AboutRoute) },
            )
        }

        composable<AboutRoute> {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenLicense = { navController.navigate(LicenseRoute) },
            )
        }

        composable<LicenseRoute> {
            LicenseScreen(onBack = { navController.popBackStack() })
        }

        composable<TransferRoute> { backStackEntry ->
            val route: TransferRoute = backStackEntry.toRoute()
            TransferScreen(
                bookIds = route.bookIds,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
