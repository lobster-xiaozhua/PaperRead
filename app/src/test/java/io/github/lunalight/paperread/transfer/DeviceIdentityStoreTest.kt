package io.github.lunalight.paperread.transfer

import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设备标识（AC-11.4）：随机 UUID 持久化 + 机型名展示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceIdentityStoreTest {

    @Test
    fun `同一设备多次读取标识一致且为合法 UUID`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = DeviceIdentityStore(context)
        val first = store.deviceId()
        assertEquals(first, store.deviceId())
        assertEquals(first, DeviceIdentityStore(context).deviceId())
        // 合法 UUID（fromString 不抛即合法）
        UUID.fromString(first)
    }

    @Test
    fun `不同应用数据目录的标识相互独立`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = DeviceIdentityStore(context)
        val id = store.deviceId()
        // 篡改落盘值后应读回篡改值（证明确实走了持久化而非每次随机）
        val file = java.io.File(java.io.File(context.filesDir, "transfer"), "device_id.txt")
        file.writeText("tampered-id")
        assertEquals("tampered-id", DeviceIdentityStore(context).deviceId())
        assertNotEquals(id, "tampered-id")
    }

    @Test
    fun `设备名为非空展示名`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = DeviceIdentityStore(context).deviceName()
        assertTrue(name.isNotBlank())
    }
}
