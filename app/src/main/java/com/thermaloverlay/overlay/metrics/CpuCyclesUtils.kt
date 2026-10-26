/**
 * Reads per-core CPU cycle counts via simpleperf, to show real per-core load
 * on clusters that share a single frequency.
 */
package com.thermaloverlay.overlay.metrics

import android.os.Process
import com.thermaloverlay.overlay.shell.KeepShell
import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.SysReader

object CpuCyclesUtils {
    private const val CYC_FILE = "/data/local/tmp/thermaloverlay_cyc.txt"
    private const val CYC_SIG = "thermaloverlay:cycstream"
    private const val INTERVAL_MS = 500

    // Upper bound for the number of cores the awk script reports.
    private const val MAX_CORES = 16

    private var streamStarted = false
    private var simpleperfAvailable: Boolean? = null

    /** null = could not find out (shell failed); the caller must retry later. */
    private fun hasSimpleperf(): Boolean? {
        simpleperfAvailable?.let { return it }
        val result = KeepShellPublic.doCmdSync("command -v simpleperf >/dev/null 2>&1 && echo 1 || echo 0").trim()
        val available = when (result) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        simpleperfAvailable = available
        return available
    }

    private fun killStreamCmd(): String =
        "pkill -f \"$CYC_SIG\" 2>/dev/null; pkill -f \"simpleperf stat -a -e cpu-cycles\" 2>/dev/null"

    @Synchronized
    private fun ensureStreamStarted() {
        if (streamStarted) return
        if (hasSimpleperf() != true) return

        val cores = SysReader.read("/sys/devices/system/cpu/present")
            .substringAfterLast('-').toIntOrNull()?.plus(1)?.coerceIn(1, MAX_CORES)
            ?: Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_CORES)

        KeepShellPublic.doCmdSync(killStreamCmd())
        // Remove a stale results file so a fresh stream never serves values
        // recorded before a restart (e.g. with a different set of online cores).
        KeepShellPublic.doCmdSync("rm -f $CYC_FILE 2>/dev/null")

        // Watchdog: the pipeline runs as root and outlives the app. If the app
        // process dies without a clean stop (crash, OOM kill) nobody would
        // ever stop it, so a tiny watcher kills it once our PID is gone. Its
        // command line contains CYC_SIG, hence a normal stop kills it too.
        val pid = Process.myPid()
        KeepShellPublic.doCmdSync(
            "nohup sh -c 'while kill -0 $pid 2>/dev/null; do sleep 5; done; " +
                "pkill -f $CYC_SIG; pkill -f \"simpleperf stat -a -e cpu-cycles\"' >/dev/null 2>&1 &"
        )

        val cmd = "nohup sh -c \"simpleperf stat -a -e cpu-cycles --per-core --interval $INTERVAL_MS " +
            "--interval-only-values --duration 999999 2>&1 | awk -v out=$CYC_FILE -v sig=$CYC_SIG -v n=$cores '" +
            "/cpu-cycles/ { c=\\\$1+0; for(i=1;i<=NF;i++) if(\\\$i==\\\"#\\\"){ g=\\\$(i+1)+0; break }; m[c]=int(g*1000+0.5) } " +
            // The array is cleared after every interval: without this, a core
            // that went offline keeps printing its last value forever (frozen
            // MHz in the HUD). A zero makes the app fall back to the
            // freq*load estimate instead.
            "/Total test time/ { s=\\\"\\\"; for(k=0;k<n;k++) s=s (k?\\\",\\\":\\\"\\\") (m[k]+0); print s > out; close(out); for(j in m) delete m[j] }" +
            "'\" >/dev/null 2>&1 &"

        if (KeepShellPublic.doCmdSync(cmd) != KeepShell.ERROR) {
            streamStarted = true
        }
    }

    @Synchronized
    fun stopStream() {
        if (!streamStarted) return
        KeepShellPublic.doCmdSync(killStreamCmd())
        streamStarted = false
    }

    /**
     * Kills a stream orphaned by a previous process death (the nohup'ed
     * simpleperf pipeline outlives the app). Call on service start, from a
     * background thread.
     */
    @Synchronized
    fun cleanupOrphans() {
        if (streamStarted) return
        KeepShellPublic.doCmdSync(killStreamCmd())
    }

    /**
     * Restarts the stream after a CPU core is hotplugged on/off. simpleperf
     * opens its perf events once at launch, so a re-onlined core never gets a
     * counter again in the running instance — the stream must be relaunched.
     * It is stopped here and lazily restarted by the next
     * getPerCoreCyclesMhz() call.
     */
    @Synchronized
    fun restartAfterCoreHotplug() {
        if (!streamStarted) return
        KeepShellPublic.doCmdSync(killStreamCmd())
        KeepShellPublic.doCmdSync("rm -f $CYC_FILE 2>/dev/null")
        streamStarted = false
    }

    fun getPerCoreCyclesMhz(): List<Int>? {
        ensureStreamStarted()
        if (!streamStarted) return null

        val content = SysReader.read(CYC_FILE).trim()
        if (content.isEmpty() || content == KeepShell.ERROR) return null

        val values = content.split(",").mapNotNull { it.toIntOrNull() }
        return if (values.isEmpty()) null else values
    }
}
