/**
 * Reads kernel nodes (sysfs/procfs). Tries a plain in-process file read first
 * (no process, no root) and falls back to the root shell for nodes the app is
 * not allowed to read. Nodes that needed the shell are remembered, so the
 * failing direct attempt is only paid once per path.
 */
package com.thermaloverlay.overlay.shell

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

object SysReader {
    // Paths the app itself cannot read but the root shell can.
    private val shellOnly: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Overridable in tests.
    @Volatile
    internal var shellProvider: () -> KeepShell = { KeepShellPublic.getDefaultInstance() }

    /** Quotes [s] for use inside a POSIX shell command. */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun readDirect(path: String): String? {
        if (path in shellOnly) return null
        return try {
            FileInputStream(File(path)).use { String(it.readBytes()).trim() }
        } catch (ex: Exception) {
            null
        }
    }

    /**
     * Trimmed content of [path]; "" if the node is missing/empty; null only if
     * the shell itself failed (so callers can tell "no such node" apart from
     * "could not ask", and avoid caching a wrong negative answer).
     */
    fun readOrNull(path: String): String? {
        readDirect(path)?.let { return it }
        val out = shellProvider().doCmdSync("cat ${shQuote(path)} 2>/dev/null")
        if (out == KeepShell.ERROR) return null
        if (out.isNotEmpty()) shellOnly.add(path)
        return out
    }

    /** Like [readOrNull] but a shell failure reads as "". */
    fun read(path: String): String = readOrNull(path) ?: ""

    /**
     * Reads many nodes with at most one shell round trip. Element i is null if
     * the shell failed for that path, "" if the node is missing.
     */
    fun readMany(paths: List<String>): List<String?> {
        val result = arrayOfNulls<String>(paths.size)
        val needShell = ArrayList<Int>()
        for ((i, p) in paths.withIndex()) {
            val d = readDirect(p)
            if (d != null) result[i] = d else needShell.add(i)
        }
        if (needShell.isNotEmpty()) {
            val script = StringBuilder()
            for (i in needShell) {
                script.append("echo '").append(SECTION).append(i).append("'; cat ")
                    .append(shQuote(paths[i])).append(" 2>/dev/null; echo\n")
            }
            val out = shellProvider().doCmdSync(script.toString())
            if (out != KeepShell.ERROR) {
                val sections = parseSections(out)
                for (i in needShell) {
                    val value = sections[i] ?: ""
                    result[i] = value
                    if (value.isNotEmpty()) shellOnly.add(paths[i])
                }
            }
        }
        return result.toList()
    }

    /** Convenience: [readMany] as a map, shell failures read as "". */
    fun readManyMap(paths: List<String>): Map<String, String> {
        val values = readMany(paths)
        val map = HashMap<String, String>(paths.size * 2)
        for ((i, p) in paths.withIndex()) map[p] = values[i] ?: ""
        return map
    }

    private const val SECTION = "@@SEC:"

    /** Splits the output of the [readMany] script into index -> trimmed content. */
    internal fun parseSections(output: String): Map<Int, String> {
        val map = HashMap<Int, String>()
        var current = -1
        val buf = StringBuilder()
        fun flush() {
            if (current >= 0) map[current] = buf.toString().trim()
            buf.setLength(0)
        }
        for (line in output.split('\n')) {
            if (line.startsWith(SECTION)) {
                flush()
                current = line.substring(SECTION.length).trim().toIntOrNull() ?: -1
            } else if (current >= 0) {
                buf.append(line).append('\n')
            }
        }
        flush()
        return map
    }
}
