package io.github.lunalight.paperread.ui.nav

import kotlinx.serialization.Serializable

/** 类型安全路由（Navigation Compose 2.8 + kotlinx-serialization）。 */

@Serializable
data object BookshelfRoute

@Serializable
data class ReaderRoute(val bookId: Long)

@Serializable
data object SettingsRoute

@Serializable
data object AboutRoute

@Serializable
data object LicenseRoute

/** 隐私说明一页（T-009 · AC-20.3）。 */
@Serializable
data object PrivacyRoute

/** 传书页：携带书架多选出的待发送书籍 id（空列表 = 仅待命发现）。 */
@Serializable
data class TransferRoute(val bookIds: List<Long>)

/**
 * 新手引导（T-020 · US-18）。
 *
 * 两个入口共用一条路由：首启自动进入（判据在 `OnboardingStore`），
 * 以及设置页「重新查看引导」（AC-18.4）。两者行为一致——看完 / 跳过都
 * 返回上一页，标记本来就只为首次展示服务。
 */
@Serializable
data object OnboardingRoute
