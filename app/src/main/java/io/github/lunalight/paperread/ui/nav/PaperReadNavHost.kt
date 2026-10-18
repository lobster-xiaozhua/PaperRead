package io.github.lunalight.paperread.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.lunalight.paperread.ui.about.AboutScreen
import io.github.lunalight.paperread.ui.about.LicenseScreen
import io.github.lunalight.paperread.ui.bookshelf.BookshelfScreen
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.settings.SettingsScreen

/** 全应用导航图。 */
@Composable
fun PaperReadNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = BookshelfRoute) {

        composable<BookshelfRoute> {
            BookshelfScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenBook = { bookId -> navController.navigate(ReaderRoute(bookId)) },
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
    }
}
