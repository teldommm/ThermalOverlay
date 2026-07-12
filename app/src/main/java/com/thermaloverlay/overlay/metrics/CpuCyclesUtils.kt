/**
 * Reads per-core CPU cycle counts via simpleperf, to show real per-core load
 * on clusters that share a single frequency.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.KernelProrp

object CpuCyclesUtils {
    private const val CYC_FILE = "/data/local/tmp/thermaloverlay_cyc.txt"
    private const val CYC_SIG = "thermaloverlay:cycstream"
    private const val INTERVAL_MS = 500

    private var streamStarted = false
    private var simpleperfAvailable: Boolean? = null

    private fun hasSimpleperf(): Boolean {
        simpleperfAvailable?.let { return it }
        val result = KeepShellPublic.doCmdSync("command -v simpleperf >/dev/null 2>&1 && echo 1 || echo 0").trim()
        val available = result == "1"
        simpleperfAvailable = available
        return available
    }

    @Synchronized
    private fun ensureStreamStarted() {
        if (streamStarted) return
        streamStarted = true

        if (!hasSimpleperf()) return

        KeepShellPublic.doCmdSync("pkill -f \"$CYC_SIG\" 2>/dev/null")

        val cmd = "nohup sh -c \"simpleperf stat -a -e cpu-cycles --per-core --interval $INTERVAL_MS " +
            "--interval-only-values --duration 999999 2>&1 | awk -v out=$CYC_FILE -v sig=$CYC_SIG '" +
            "/cpu-cycles/ { c=\\\$1+0; for(i=1;i<=NF;i++) if(\\\$i==\\\"#\\\"){ g=\\\$(i+1)+0; break }; m[c]=int(g*1000+0.5) } " +
            "/Total test time/ { s=\\\"\\\"; for(k=0;k<8;k++) s=s (k?\\\",\\\":\\\"\\\") (m[k]+0); print s > out; close(out) }" +
            "'\" >/dev/null 2>&1 &"

        KeepShellPublic.doCmdSync(cmd)
    }

    @Synchronized
    fun stopStream() {
        if (!streamStarted) return
        KeepShellPublic.doCmdSync("pkill -f \"$CYC_SIG\" 2>/dev/null")
        streamStarted = false
    }

    fun getPerCoreCyclesMhz(): List<Int>? {
        ensureStreamStarted()
        if (simpleperfAvailable != true) return null

        val content = KernelProrp.getProp(CYC_FILE).trim()
        if (content.isEmpty() || content == "error") return null

        val values = content.split(",").mapNotNull { it.toIntOrNull() }
        return if (values.isEmpty()) null else values
    }
}
