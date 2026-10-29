package sh.zeron.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.DeviceView

/**
 * The pure half of the Settings Devices section: [deviceConnectionLabel]
 * and [devicesRows] over Zeron's [DeviceView] (the workspace feed's
 * `devices`, #20). Plain JVM — the generated record touches no JNA.
 */
class DevicesRowsTest {

    private fun device(id: String, name: String, online: Boolean = true) = DeviceView(
        id = id,
        name = name,
        platform = "macos",
        online = online,
        lastSeenMs = null,
        version = null,
        capabilities = emptyList(),
        isExecutionHost = true,
        isSelf = false,
        sessionCount = 0u,
    )

    @Test
    fun presenceMapsToItsVerbatimLabel() {
        assertEquals("Connected", deviceConnectionLabel(true))
        assertEquals("Offline", deviceConnectionLabel(false))
    }

    @Test
    fun nullAndEmptyListsGiveNoRows() {
        assertEquals(emptyList<DevicesRowSpec>(), devicesRows(null))
        assertEquals(emptyList<DevicesRowSpec>(), devicesRows(emptyList()))
    }

    @Test
    fun devicesKeepTheCoresOrderAndIds() {
        val rows = devicesRows(
            listOf(
                device(id = "B", name = "Studio", online = false),
                device(id = "A", name = "MacBook Pro"),
            ),
        )
        assertEquals(listOf("device:B", "device:A"), rows.map { it.id })
        val studio = rows[0] as DevicesRowSpec.Device
        assertEquals("B", studio.deviceId)
        assertEquals("Studio", studio.title)
        assertEquals("Offline", studio.label)
        assertFalse(studio.connected)
        val mac = rows[1] as DevicesRowSpec.Device
        assertEquals("Connected", mac.label)
        assertTrue(mac.connected)
    }

    @Test
    fun aBlankNameFallsBackToTheId() {
        val rows = devicesRows(listOf(device(id = "d1", name = "  ")))
        assertEquals("d1", (rows[0] as DevicesRowSpec.Device).title)
    }
}
