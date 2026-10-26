package com.thermaloverlay.overlay.metrics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CpuFrequencyUtilsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Builds a fake /sys/devices/system/cpu for an 8-core 4+3+1 SoC. */
    private fun fakeCpuTree(): File {
        val root = tmp.newFolder("cpu")
        File(root, "present").writeText("0-7\n")
        for (i in 0..7) File(root, "cpu$i").mkdirs()
        return root
    }

    private fun policy(root: File, cpu: Int, related: String, curKHz: Long?) {
        val dir = File(root, "cpu$cpu/cpufreq").apply { mkdirs() }
        File(dir, "related_cpus").writeText("$related\n")
        if (curKHz != null) File(dir, "scaling_cur_freq").writeText("$curKHz\n")
    }

    @Test
    fun parsesCpuLists() {
        assertEquals(listOf(0, 1, 2, 3), CpuFrequencyUtils.parseCpuList("0 1 2 3\n"))
        assertEquals(listOf(0, 1, 2, 3, 6), CpuFrequencyUtils.parseCpuList("0-3,6"))
        assertEquals(listOf(7), CpuFrequencyUtils.parseCpuList("7"))
        assertEquals(emptyList<Int>(), CpuFrequencyUtils.parseCpuList(""))
        assertEquals(emptyList<Int>(), CpuFrequencyUtils.parseCpuList("garbage"))
    }

    @Test
    fun coreCountComesFromPresent() {
        val root = fakeCpuTree()
        assertEquals(8, CpuFrequencyUtils(root.path).getCoreCount())
    }

    @Test
    fun allOnlineGivesCompleteCachedScan() {
        val root = fakeCpuTree()
        policy(root, 0, "0 1 2 3", 1_000_000)
        policy(root, 4, "4 5 6", 2_000_000)
        policy(root, 7, "7", 3_000_000)
        val u = CpuFrequencyUtils(root.path)
        val scan = u.getClusters()
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6), listOf(7)), scan.clusters)
        assertTrue(scan.isComplete)
        assertTrue("complete scans are cached", u.getClusters() === scan)
    }

    /** The reported bug: cores offline when the overlay starts must appear once online. */
    @Test
    fun clusterThatWasOfflineAtStartAppearsAfterItComesBack() {
        val root = fakeCpuTree()
        policy(root, 0, "0 1 2 3", 1_000_000)
        policy(root, 4, "4 5 6", 2_000_000)
        // cpu7 is its own policy and is offline: the kernel removed its cpufreq dir.
        val u = CpuFrequencyUtils(root.path)

        val first = u.getClusters()
        assertEquals(2, first.clusters.size)
        assertEquals(listOf(7), first.missing)
        assertFalse(first.isComplete)

        // Still offline: must keep reporting it as missing (not cache the partial result).
        assertEquals(listOf(7), u.getClusters().missing)

        // cpu7 comes back.
        policy(root, 7, "7", 3_000_000)
        val after = u.getClusters()
        assertTrue(after.isComplete)
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6), listOf(7)), after.clusters)
    }

    /** Old code stopped at the first gap, losing every cluster after it. */
    @Test
    fun gapInTheMiddleDoesNotHideLaterClusters() {
        val root = fakeCpuTree()
        policy(root, 0, "0 1 2 3", 1_000_000)
        // cpu4..6 cluster offline entirely, cpu7 online.
        policy(root, 7, "7", 3_000_000)
        val scan = CpuFrequencyUtils(root.path).getClusters()
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(7)), scan.clusters)
        assertEquals(listOf(4, 5, 6), scan.missing)
    }

    @Test
    fun offlineMemberOfAClusterDoesNotSplitOrHideIt() {
        val root = fakeCpuTree()
        policy(root, 0, "0 1 2 3", 1_000_000)
        // 4 and 5 offline, 6 online: kernel keeps the policy via cpu6.
        policy(root, 6, "4 5 6", 2_000_000)
        policy(root, 7, "7", 3_000_000)
        val scan = CpuFrequencyUtils(root.path).getClusters()
        assertTrue(scan.isComplete)
        assertEquals(listOf(4, 5, 6), scan.clusters[1])
    }

    @Test
    fun clusterFrequencyFallsBackToAnOnlineMember() {
        val root = fakeCpuTree()
        policy(root, 0, "0 1 2 3", 1_800_000)
        policy(root, 6, "4 5 6", 2_400_000) // cpu4 (first member) has no cpufreq
        val u = CpuFrequencyUtils(root.path)
        assertEquals(1_800_000L, u.getClusterFrequencyKHz(listOf(0, 1, 2, 3)))
        assertEquals(2_400_000L, u.getClusterFrequencyKHz(listOf(4, 5, 6)))
        assertEquals(-1L, u.getClusterFrequencyKHz(listOf(7)))
    }
}
