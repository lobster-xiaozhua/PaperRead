package io.github.lunalight.paperread.di

import android.content.Context

/**
 * 手动依赖容器（服务定位器）。
 *
 * 本项目依赖点约 5 个，选择手动 DI 而非 Hilt：构建更快、贡献者上手成本更低。
 * 若未来依赖规模增长，可平滑替换为 Hilt 而不影响上层。
 *
 * M1 阶段为空壳；M2 起在此提供 AppDatabase / BookRepository / SettingsRepository。
 */
class AppContainer(context: Context)
