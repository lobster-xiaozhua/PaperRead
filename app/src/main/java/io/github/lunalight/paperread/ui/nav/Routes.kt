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
