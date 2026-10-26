package com.thermaloverlay.overlay.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuLoadUtilsTest {
    private val stat = """
        cpu  100 0 50 800 50 0 0 0 999 999
        cpu0 40 0 20 300 40 0 0 0 0 0
        cpu2 60 0 30 500 10 0 0 0 0 0
        intr 12345
        ctxt 678
    """.trimIndent()

    @Test
    fun parsesOnlyCpuLines() {
        val m = CpuLoadUtils.parseProcStat(stat)
        assertEquals(setOf(-1, 0, 2), m.keys)
    }

    @Test
    fun guestCountersAreNotCountedTwice() {
        val total = CpuLoadUtils.parseProcStat(stat)[-1]!![0]
        assertEquals(100 + 0 + 50 + 800 + 50, total.toInt()) // 1000, not 1000 + 1998
    }

    @Test
    fun idleIncludesIowait() {
        assertEquals(850L, CpuLoadUtils.parseProcStat(stat)[-1]!![1])
    }

    @Test
    fun loadBetweenSamples() {
        val prev = longArrayOf(1000, 850)
        val cur = longArrayOf(1200, 900) // +200 ticks, +50 idle
        assertEquals(75.0, CpuLoadUtils.loadBetween(prev, cur), 1e-9)
    }

    @Test
    fun loadEdgeCases() {
        assertEquals(0.0, CpuLoadUtils.loadBetween(longArrayOf(10, 5), longArrayOf(10, 5)), 0.0)
        assertEquals(100.0, CpuLoadUtils.loadBetween(longArrayOf(0, 0), longArrayOf(100, 0)), 0.0)
        assertEquals(0.0, CpuLoadUtils.loadBetween(longArrayOf(0, 0), longArrayOf(100, 100)), 0.0)
        // Counter anomalies must stay inside 0..100.
        val weird = CpuLoadUtils.loadBetween(longArrayOf(0, 0), longArrayOf(100, 500))
        assertTrue(weird in 0.0..100.0)
    }

    @Test
    fun garbageLinesAreIgnored() {
        val m = CpuLoadUtils.parseProcStat("cpuX 1 2 3 4\ncpu1 1 2\ncpu3 1 2 3 4 5 6 7 8")
        assertEquals(setOf(3), m.keys)
    }

    @Test
    fun liveSampleOnThisMachine() {
        // /proc/stat is readable directly on a Linux build machine.
        if (!java.io.File("/proc/stat").canRead()) return
        val u = CpuLoadUtils()
        val first = u.sample()
        assertNotNull(first)
        assertEquals(0.0, first!!.total, 0.0) // no baseline yet
        assertFalse(first.perCore.isEmpty())
        Thread.sleep(150)
        val second = u.sample()!!
        assertTrue(second.total in 0.0..100.0)
        assertTrue(second.perCore.values.all { it in 0.0..100.0 })
    }
}
