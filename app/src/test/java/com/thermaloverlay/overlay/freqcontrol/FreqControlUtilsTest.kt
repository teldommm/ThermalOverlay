package com.thermaloverlay.overlay.freqcontrol

import com.thermaloverlay.overlay.freqcontrol.FreqControlUtils.WriteResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FreqControlUtilsTest {
    private val paths = FreqControlUtils.ClusterPaths(
        minFreq = "min", maxFreq = "max", curFreq = "cur", availFreq = "af",
        availBoost = null, gov = "gov", availGov = "ag",
    )

    private lateinit var savedIo: FreqControlUtils.NodeIO
    private lateinit var savedSleeper: (Long) -> Unit

    @Before
    fun setUp() {
        savedIo = FreqControlUtils.nodes
        savedSleeper = FreqControlUtils.sleeper
        FreqControlUtils.sleeper = { }
    }

    @After
    fun tearDown() {
        FreqControlUtils.nodes = savedIo
        FreqControlUtils.sleeper = savedSleeper
    }

    /**
     * Behaves like a 4.x cpufreq driver: a write that would make min > max (or
     * max < min) is rejected with EINVAL, i.e. nothing changes.
     */
    private class StrictKernel(var min: Long, var max: Long) : FreqControlUtils.NodeIO {
        val log = ArrayList<String>()
        override fun read(path: String) = when (path) {
            "min" -> min.toString()
            "max" -> max.toString()
            else -> ""
        }

        override fun write(path: String, value: String): Boolean {
            log += "$path=$value"
            val v = value.toLong()
            return when (path) {
                "min" -> if (v > max) false else { min = v; true }
                "max" -> if (v < min) false else { max = v; true }
                else -> false
            }
        }
    }

    @Test
    fun maxBelowCurrentMinNeedsTheMinMovedFirst() {
        val k = StrictKernel(min = 1_800_000, max = 2_800_000)
        FreqControlUtils.nodes = k

        assertEquals(WriteResult.OK, FreqControlUtils.setCpuMaxKHz(paths, 1_200_000))

        assertEquals(1_200_000L, k.max)
        assertEquals(1_200_000L, k.min) // pushed down so the kernel accepted the max
        assertEquals("min=1200000", k.log.first())
    }

    @Test
    fun minAboveCurrentMaxNeedsTheMaxRaisedFirst() {
        val k = StrictKernel(min = 300_000, max = 1_000_000)
        FreqControlUtils.nodes = k

        assertEquals(WriteResult.OK, FreqControlUtils.setCpuMinKHz(paths, 1_800_000))

        assertEquals(1_800_000L, k.min)
        assertEquals(1_800_000L, k.max)
        assertEquals("max=1800000", k.log.first())
    }

    @Test
    fun plainChangeIsASingleWrite() {
        val k = StrictKernel(min = 300_000, max = 2_800_000)
        FreqControlUtils.nodes = k
        assertEquals(WriteResult.OK, FreqControlUtils.setCpuMaxKHz(paths, 2_000_000))
        assertEquals(listOf("max=2000000"), k.log)
    }

    /** A write that doesn't stick the first time (daemon overwrite / transient error). */
    private class Flaky(private val failFirst: Int) : FreqControlUtils.NodeIO {
        var value = "old"
        var writes = 0
        override fun read(path: String) = value
        override fun write(path: String, value: String): Boolean {
            writes++
            if (writes > failFirst) this.value = value
            return true
        }
    }

    @Test
    fun retriesUntilTheValueSticks() {
        val n = Flaky(failFirst = 2)
        FreqControlUtils.nodes = n
        assertEquals(WriteResult.OK, FreqControlUtils.writeVerified("x", "performance"))
        assertEquals(3, n.writes)
    }

    @Test
    fun givesUpAfterThreeAttemptsAndReportsFailure() {
        val n = Flaky(failFirst = 99)
        FreqControlUtils.nodes = n
        assertEquals(WriteResult.FAILED, FreqControlUtils.writeVerified("x", "performance"))
        assertEquals(3, n.writes)
    }

    @Test
    fun numericComparisonIgnoresFormatting() {
        val n = object : FreqControlUtils.NodeIO {
            override fun read(path: String) = " 0001800000\n"
            override fun write(path: String, value: String) = true
        }
        FreqControlUtils.nodes = n
        assertEquals(WriteResult.OK, FreqControlUtils.writeVerified("x", "1800000"))
    }

    @Test
    fun parsers() {
        assertEquals(listOf(300000, 576000), FreqControlUtils.parseIntList(" 300000  576000\n"))
        assertEquals(emptyList<Int>(), FreqControlUtils.parseIntList(null))
        assertEquals(listOf("schedutil", "performance"), FreqControlUtils.parseWordList("schedutil performance\n"))
        assertEquals(1804, FreqControlUtils.parseMHzFromKHz("1804800"))
        assertEquals(818, FreqControlUtils.parseGpuCurMHz("818000000"))
    }
}
