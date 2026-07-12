/**
 * Keeps a single persistent su shell process alive and synchronizes command
 * output via start/end markers, to avoid spawning su on every command.
 */
package com.thermaloverlay.overlay.shell

import android.util.Log
import java.io.BufferedReader
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.locks.ReentrantLock

class KeepShell(private var rootMode: Boolean = true) {
    private var p: Process? = null
    private var out: OutputStream? = null
    private var reader: BufferedReader? = null
    private var currentIsIdle = true
    val isIdle: Boolean
        get() = currentIsIdle

    fun tryExit() {
        try {
            out?.close()
            reader?.close()
        } catch (ex: Exception) {
        }
        try {
            p?.destroy()
        } catch (ex: Exception) {
        }
        enterLockTime = 0L
        out = null
        reader = null
        p = null
        currentIsIdle = true
    }

    private val GET_ROOT_TIMEOUT = 20000L
    private val mLock = ReentrantLock()
    private val LOCK_TIMEOUT = 10000L
    private var enterLockTime = 0L

    private var checkRootState =
        "if [[ \$(id -u 2>&1) == '0' ]] || [[ \$(\$UID) == '0' ]] || [[ \$(whoami 2>&1) == 'root' ]] || [[ \$(set | grep 'USER_ID=0') == 'USER_ID=0' ]]; then\n" +
            "  echo 'success'\n" +
            "else\n" +
            "if [[ -d /cache ]]; then\n" +
            "  echo 1 > /cache/thermaloverlay_root\n" +
            "  if [[ -f /cache/thermaloverlay_root ]] && [[ \$(cat /cache/thermaloverlay_root) == '1' ]]; then\n" +
            "    echo 'success'\n" +
            "    rm -rf /cache/thermaloverlay_root\n" +
            "    return\n" +
            "  fi\n" +
            "fi\n" +
            "exit 1\n" +
            "exit 1\n" +
            "fi\n"

    fun checkRoot(): Boolean {
        val r = doCmdSync(checkRootState).lowercase(Locale.getDefault())
        return if (r == "error" || r.contains("permission denied") || r.contains("not allowed") || r == "not found") {
            if (rootMode) tryExit()
            false
        } else if (r.contains("success")) {
            true
        } else {
            if (rootMode) tryExit()
            false
        }
    }

    private fun getRuntimeShell() {
        if (p != null) return
        val getSu = Thread {
            try {
                mLock.lockInterruptibly()
                enterLockTime = System.currentTimeMillis()
                p = if (rootMode) ShellExecutor.getSuperUserRuntime() else ShellExecutor.getRuntime()
                out = p!!.outputStream
                reader = p!!.inputStream.bufferedReader()
                if (rootMode) {
                    out?.run {
                        write(checkRootState.toByteArray(Charset.defaultCharset()))
                        flush()
                    }
                }
                Thread {
                    try {
                        val errorReader = p!!.errorStream.bufferedReader()
                        while (true) {
                            Log.e("ThermalOverlayShell", errorReader.readLine() ?: break)
                        }
                    } catch (ex: Exception) {
                        Log.e("ThermalOverlayShell", "" + ex.message)
                    }
                }.start()
            } catch (ex: Exception) {
                Log.e("ThermalOverlayShell", "getRuntime: " + ex.message)
            } finally {
                enterLockTime = 0L
                mLock.unlock()
            }
        }
        getSu.start()
        getSu.join(10000)
        if (p == null && getSu.state != Thread.State.TERMINATED) {
            enterLockTime = 0L
            getSu.interrupt()
        }
    }

    private val shellOutputCache = StringBuilder()
    private val startTag = "|SH>>|"
    private val endTag = "|<<SH|"
    private val startTagBytes = "\necho '$startTag'\n".toByteArray(Charset.defaultCharset())
    private val endTagBytes = "\necho '$endTag'\n".toByteArray(Charset.defaultCharset())

    fun doCmdSync(cmd: String): String {
        if (mLock.isLocked && enterLockTime > 0 && System.currentTimeMillis() - enterLockTime > LOCK_TIMEOUT) {
            tryExit()
            Log.e("ThermalOverlayShell", "Lock wait timed out")
        }
        getRuntimeShell()

        try {
            mLock.lockInterruptibly()
            currentIsIdle = false

            out?.apply {
                write(startTagBytes)
                write(cmd.toByteArray(Charset.defaultCharset()))
                write(endTagBytes)
                flush()
            }

            var unstart = true
            while (reader != null) {
                val line = reader!!.readLine() ?: break
                if (line.contains(endTag)) {
                    shellOutputCache.append(line.substring(0, line.indexOf(endTag)))
                    break
                } else if (line.contains(startTag)) {
                    shellOutputCache.clear()
                    shellOutputCache.append(line.substring(line.indexOf(startTag) + startTag.length))
                    unstart = false
                } else if (!unstart) {
                    shellOutputCache.append(line)
                    shellOutputCache.append("\n")
                }
            }
            return shellOutputCache.toString().trim()
        } catch (e: Exception) {
            tryExit()
            Log.e("ThermalOverlayShell", "doCmdSync: " + e.message)
            return "error"
        } finally {
            enterLockTime = 0L
            mLock.unlock()
            currentIsIdle = true
        }
    }
}
