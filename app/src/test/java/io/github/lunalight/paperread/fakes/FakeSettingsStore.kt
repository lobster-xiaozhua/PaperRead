package io.github.lunalight.paperread.fakes

import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [SettingsStore] 的内存 fake：状态即时生效，update 调用可被观测。 */
class FakeSettingsStore(initial: ReaderSettings = ReaderSettings()) : SettingsStore {

    private val state = MutableStateFlow(initial)

    override val settings: Flow<ReaderSettings> = state

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        state.value = transform(state.value)
    }

    /** 当前设置快照（断言用）。 */
    fun current(): ReaderSettings = state.value
}
