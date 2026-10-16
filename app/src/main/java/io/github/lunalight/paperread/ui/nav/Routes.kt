package io.github.lunalight.paperread.ui.nav

import kotlinx.serialization.Serializable

/** 类型安全路由（Navigation Compose 2.8 + kotlinx-serialization）。 */

@Serializable
data object BookshelfRoute

@Serializable
data class ReaderRoute(val bookId: Long)

@Serializable
data object SettingsRoute
