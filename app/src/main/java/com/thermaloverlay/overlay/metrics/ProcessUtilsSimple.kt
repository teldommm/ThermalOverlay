/**
 * Lists running processes and kills them, via a root shell `top`/`ps`.
 *
 * One deliberate simplification versus a fuller implementation: some apps
 * bundle a prebuilt toybox binary as an asset and install it to get
 * consistent `-o %CPU,NAME,COMMAND,PID` output across vendor ROMs whose
 * stock top/ps don't support that column selection. We don't have a
 * verified build of that binary to carry over, so this only tries the
 * device's own top/ps. On most modern (toybox-based) Android builds that's
 * fine; on older/customized ones the process list may come back empty —
 * `supported()` reports that case so the caller can show it instead of a
 * silently blank list.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.model.ProcessInfo
import com.thermaloverlay.overlay.model.ThreadInfo
import com.thermaloverlay.overlay.shell.KeepShellPublic

class ProcessUtilsSimple {
    // Resolved once per process: which of the two candidate commands this
    // device actually understands. Empty string means neither worked.
    private var resolvedCommand: String? = null

    private val candidateCommands = arrayOf(
        "top -o %CPU,NAME,COMMAND,PID -q -b -n 1 -m 65535",
        "ps -e -o %CPU,NAME,COMMAND,PID"
    )

    private fun resolveCommand(): String {
        resolvedCommand?.let { return it }

        for (cmd in candidateCommands) {
            val rows = KeepShellPublic.doCmdSync("$cmd 2>&1").split("\n".toRegex())
            val firstLine = rows.getOrElse(0) { "" }
            if (rows.size > 10 &&
                !(firstLine.contains("bad -o") || firstLine.contains("Unknown option") || firstLine.contains("bad"))
            ) {
                resolvedCommand = cmd
                return cmd
            }
        }
        resolvedCommand = ""
        return ""
    }

    fun supported(): Boolean = resolveCommand().isNotEmpty()

    // Apps whose own accounting/scanning processes would otherwise clutter
    // the list.
    private val excludeProcess = setOf("ps", "top", "com.thermaloverlay.overlay")

    private fun readRow(row: String): ProcessInfo? {
        val columns = row.split(" +".toRegex())
        if (columns.size < 4) return null
        return try {
            val info = ProcessInfo()
            info.cpu = columns[0].toFloat()
            info.name = columns[1]
            if (info.name in excludeProcess) return null
            info.command = columns[2]
            info.pid = columns[3].toInt()
            info
        } catch (ex: Exception) {
            null
        }
    }

    val allProcess: ArrayList<ProcessInfo>
        get() {
            val result = ArrayList<ProcessInfo>()
            val cmd = resolveCommand()
            if (cmd.isEmpty()) return result

            val skipRows = if (cmd.startsWith("ps")) 1 else 0
            val rows = KeepShellPublic.doCmdSync(cmd).split("\n".toRegex())
            for ((index, row) in rows.withIndex()) {
                if (index < skipRows) continue
                readRow(row.trim())?.let { result.add(it) }
            }
            return result
        }

    private val androidProcessRegex = Regex(".*\\..*")
    private fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        return processInfo.command.contains("app_process") && processInfo.name.matches(androidProcessRegex)
    }

    fun killProcess(processInfo: ProcessInfo) {
        if (isAndroidProcess(processInfo)) {
            val packageName = if (processInfo.name.contains(":")) {
                processInfo.name.substring(0, processInfo.name.indexOf(":"))
            } else {
                processInfo.name
            }
            KeepShellPublic.doCmdSync("killall -9 $packageName; am force-stop $packageName; am kill $packageName")
        } else {
            KeepShellPublic.doCmdSync("kill -9 ${processInfo.pid}")
        }
    }

    // Resolves an app's main process PID from its package name, for the
    // thread monitor to attach to.
    fun getAppMainProcess(packageName: String?): Int {
        if (packageName.isNullOrEmpty()) return -1
        val pid = KeepShellPublic.doCmdSync(
            "ps -ef -o PID,NAME | grep -e ${packageName}\$ | egrep -o '[0-9]{1,}' | head -n 1"
        ).trim()
        return pid.toIntOrNull() ?: -1
    }

    // Top 15 threads of a process by CPU load, for the thread monitor.
    fun getThreadLoads(pid: Int): List<ThreadInfo> {
        val output = KeepShellPublic.doCmdSync("top -H -b -q -n 1 -p $pid -o TID,%CPU,CMD")
        val threads = ArrayList<ThreadInfo>()
        for (rawRow in output.split("\n".toRegex())) {
            val row = rawRow.trim()
            val cols = row.split(" +".toRegex())
            if (cols.size <= 2) continue
            try {
                val info = ThreadInfo()
                info.tid = cols[0].toInt()
                info.cpuLoad = cols[1].toDouble()
                info.name = row.substring(row.indexOf(cols[1]) + cols[1].length).trim()
                threads.add(info)
            } catch (ex: Exception) {
            }
        }
        threads.sortByDescending { it.cpuLoad }
        return threads.subList(0, threads.size.coerceAtMost(15))
    }
}
