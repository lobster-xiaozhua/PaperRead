package io.github.lunalight.paperread.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.lunalight.paperread.MainActivity
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.ui.about.AboutScreen
import io.github.lunalight.paperread.ui.about.LicenseScreen
import io.github.lunalight.paperread.ui.about.PrivacyScreen
import io.github.lunalight.paperread.ui.adaptive.LocalWindowWidthClass
import io.github.lunalight.paperread.ui.adaptive.rememberWindowWidthClass
import io.github.lunalight.paperread.ui.bookshelf.BookshelfScreen
import io.github.lunalight.paperread.ui.onboarding.OnboardingScreen
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.settings.SettingsScreen
import io.github.lunalight.paperread.ui.transfer.TransferScreen
import kotlinx.coroutines.launch

/** 全应用导航图。 */
@Composable
fun PaperReadNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = (context.applicationContext as? PaperReadApp)?.container

    // 首启判定（T-020 / AC-18.1 / AC-18.2）：启动时读 OnboardingStore，
    // 未看过 → 引导路由。放在 NavHost 起来之后再跳转，而不是改
    // startDestination：后者要在组合期同步拿到 DataStore 的值（只能阻塞读），
    // 前者是纯导航行为，且书架页在引导下面先渲染一帧反而是更好的过渡。
    //
    // 朗读通知冷启动（携带 bookId）优先于引导：用户点通知是要「继续听」，
    // 不是要看引导；标记不写，下次从桌面启动仍会展示。
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        val bookId = activity?.intent?.getLongExtra(MainActivity.EXTRA_BOOK_ID, -1L) ?: -1L
        if (bookId > 0L) {
            navController.navigate(ReaderRoute(bookId))
            return@LaunchedEffect
        }
        if (container?.onboardingStore?.shouldShowGuide() == true) {
            navController.navigate(OnboardingRoute)
        }
    }

    CompositionLocalProvider(LocalWindowWidthClass provides rememberWindowWidthClass()) {
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
                    onReplayOnboarding = { navController.navigate(OnboardingRoute) },
                )
            }

            composable<AboutRoute> {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicense = { navController.navigate(LicenseRoute) },
                    onOpenPrivacy = { navController.navigate(PrivacyRoute) },
                )
            }

            composable<LicenseRoute> {
                LicenseScreen(onBack = { navController.popBackStack() })
            }

            composable<PrivacyRoute> {
                PrivacyScreen(onBack = { navController.popBackStack() })
            }

            composable<TransferRoute> { backStackEntry ->
                val route: TransferRoute = backStackEntry.toRoute()
                TransferScreen(
                    bookIds = route.bookIds,
                    onBack = { navController.popBackStack() },
                )
            }

            composable<OnboardingRoute> {
                OnboardingScreen(
                    onFinish = {
                        // 完成 / 跳过都算「看过」：重看入口进来时标记本来就已为 true，
                        // 重复写是幂等的
                        container?.let { it1 ->
                            scope.launch { it1.onboardingStore.markSeen() }
                        }
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
