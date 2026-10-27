package io.github.lunalight.paperread.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
import kotlinx.coroutines.launch

/**
 * 三屏新手引导（T-020 / US-18，AC-18.2 / AC-18.3）。
 *
 * 三屏只讲「用户上手前必须知道的三件事」，不讲功能清单：
 * 1. **数据在哪**：全部只存本机、不联网——这是本应用的立身之本，也是用户
 *    第一个会问的问题（「会不会传我的书出去」）；
 * 2. **书怎么进来**：导入 TXT / EPUB + 局域网传书 / 换机迁移；
 * 3. **听书的前提**：系统 TTS 需要中文语音包，缺了就按 AC-12.8 的话术提示
 *    去系统设置装——否则用户点了朗读没声音，只会以为应用坏了。
 *
 * 交互（AC-18.3）：可左右滑动、右上角「跳过」、末屏「开始使用」。
 * 完成态（[onFinish]）由宿主决定：首启时写标记并回书架，设置页重看时只返回。
 *
 * 文案全部走 strings.xml（AC-18.6）；本文件不含任何业务数据读写。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size })
    val scope = rememberCoroutineScope()
    val lastPage = ONBOARDING_PAGES.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // 跳过：三屏都可见（AC-18.3）
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onFinish) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            OnboardingPageContent(page = ONBOARDING_PAGES[page])
        }

        // 指示器：只做位置反馈，不承担导航（滑动 / 按钮才是主路径）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ONBOARDING_PAGES.indices.forEach { index ->
                val active = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }

        Button(
            onClick = {
                if (pagerState.currentPage == lastPage) {
                    onFinish()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                stringResource(
                    if (pagerState.currentPage == lastPage) {
                        R.string.onboarding_start
                    } else {
                        R.string.onboarding_next
                    },
                ),
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 单屏的文案资源（文案本体在 strings.xml，见 AC-18.6）。 */
internal data class OnboardingPage(val titleRes: Int, val bodyRes: Int)

/**
 * 三屏定义。
 *
 * `internal` 而非 private：单测只需断言「三屏、顺序、文案非空」，
 * 不必把文案再抄一遍到测试里（抄一遍就等于两份真相）。
 */
internal val ONBOARDING_PAGES: List<OnboardingPage> = listOf(
    OnboardingPage(R.string.onboarding_page1_title, R.string.onboarding_page1_body),
    OnboardingPage(R.string.onboarding_page2_title, R.string.onboarding_page2_body),
    OnboardingPage(R.string.onboarding_page3_title, R.string.onboarding_page3_body),
)
