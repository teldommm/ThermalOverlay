package com.thermaloverlay.overlay.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsersTest {
    @Test
    fun swapFromProcSwaps() {
        val out = """
            Filename   Type       Size     Used    Priority
            /dev/block/zram0 partition 4194300 1048575 -2
        """.trimIndent()
        assertEquals(25, SwapUtils.parseProcSwaps(out))
        assertNull(SwapUtils.parseProcSwaps(""))
        assertNull(SwapUtils.parseProcSwaps("Filename Type Size Used Priority"))
    }

    @Test
    fun gpuLoadFormats() {
        assertEquals(37, GpuUtils.parseGpuLoad("37 %"))
        assertEquals(37, GpuUtils.parseGpuLoad("37%\n"))
        assertEquals(-1, GpuUtils.parseGpuLoad(""))
        assertEquals(-1, GpuUtils.parseGpuLoad("error"))
    }

    @Test
    fun maliTotalBytes() {
        assertEquals("123456", GpuUtils.parseMaliTotalBytes("Total memory usage : 7 pages (123456 bytes)\nother"))
        assertEquals("", GpuUtils.parseMaliTotalBytes("nothing here"))
    }

    @Test
    fun batteryVoltageUnits() {
        assertEquals(4.123456, BatteryStatusReader.parseVoltage("4123456")!!, 1e-9)
        assertEquals(4.123, BatteryStatusReader.parseVoltage("4123")!!, 1e-9)
        assertNull(BatteryStatusReader.parseVoltage("12"))
        assertNull(BatteryStatusReader.parseVoltage("error"))
        assertNull(BatteryStatusReader.parseVoltage(""))
    }

    @Test
    fun foregroundPackage() {
        assertEquals(
            "com.foo.bar",
            ForegroundApp.parse("mCurrentFocus=Window{4b3a u0 com.foo.bar/com.foo.bar.MainActivity}")
        )
        assertEquals(
            "com.game-x",
            ForegroundApp.parse("mCurrentFocus=null\n mFocusedApp=ActivityRecord{1 u0 com.game-x/.Main t5}")
        )
        assertNull(ForegroundApp.parse("error"))
        assertNull(ForegroundApp.parse(""))
        assertNull(ForegroundApp.parse("mCurrentFocus=Window{1 u0 NotificationShade}"))
    }
}
