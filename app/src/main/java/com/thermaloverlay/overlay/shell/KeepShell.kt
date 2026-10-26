/**
 * Keeps a single persistent su (or sh) process alive and synchronizes command
 * output via start/end markers, to avoid spawning su on every command.
 *
 * Design:
 *  - Everything that touches the process (spawn, write, wait for output)
 *    happens under one lock, so two threads can never race to create two
 *    processes.
 *  - Output is read by a dedicated thread per process and handed over through
 *    a queue. The caller waits on the queue WITH A TIMEOUT, so a hung command
 *    (blocked sysfs node, stuck dumpsys, su prompt nobody answers) can never
 *    block the caller forever — a blocking readLine() cannot be interrupted or
 *    closed from another thread. On timeout the shell is killed and the next
 *    command transparently starts a new one.
 *  - A command either returns its real output or [ERROR]; it never returns the
 *    output of a previous command, because every process has its own queue.
 */
package com.thermaloverlay.overlay.shell

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class KeepShell(
    private val rootMode: Boolean = true,
    // The executable to run; only tests use something other than su/sh.
    private val command: String = if (rootMode) "su" else "sh",
) {
    /** One running shell process plus the queue its reader thread fills. */
    private class Session(val process: Process) {
        val out: OutputStream = process.outputStream
        val lines = LinkedBlockingQueue<Any>()

        fun kill() {
            try {
                out.close()
            } catch (ignored: Exception) {
            }
            try {
                process.destroy()
            } catch (ignored: Exception) {
            }
        }
    }

    private val lock = ReentrantLock()

    // Replaced/cleared from other threads too (tryExit), hence the stateLock.
    private val stateLock = Any()
    private var session: Session? = null

    @Volatile
    private var firstCommandDone = false

    @Volatile
    private var nextSpawnAllowedAt = 0L

    /** Number of processes this instance has spawned (used by tests). */
    @Volatile
    internal var spawnCount = 0
        private set

    /** True when no command is running and none is waiting. */
    val isIdle: Boolean
        get() = !lock.isLocked && !lock.hasQueuedThreads()

    /** Kills the shell process. Safe to call from any thread, any time. */
    fun tryExit() {
        val s = synchronized(stateLock) {
            val current = session
            session = null
            current
        }
        s?.kill()
    }

    /** Kills the shell only if nobody is using it right now. */
    fun tryExitIfIdle() {
        if (lock.tryLock()) {
            try {
                tryExit()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Lets the next command try to start su immediately (user pressed "check again"). */
    fun resetBackoff() {
        nextSpawnAllowedAt = 0L
    }

    /**
     * Returns true if commands run as uid 0 (always false for a plain sh).
     * The first call can block while the user answers the su prompt.
     */
    fun checkRoot(): Boolean {
        if (!rootMode) return false
        val r = doCmdSync(CHECK_ROOT_CMD, ROOT_GRANT_TIMEOUT_MS)
        if (r.contains("success")) return true
        tryExit()
        return false
    }

    /** Must be called with [lock] held. Returns null if no shell is available. */
    private fun ensureSession(): Session? {
        synchronized(stateLock) {
            val s = session
            if (s != null && s.process.isAlive) return s
        }
        // Dead or missing: clean up and (re)spawn.
        tryExit()

        if (System.currentTimeMillis() < nextSpawnAllowedAt) return null

        return try {
            val p = ProcessBuilder(command).start()
            spawnCount++
            val s = Session(p)
            startReader(s)
            startStderrDrain(p)
            synchronized(stateLock) { session = s }
            firstCommandDone = false
            s
        } catch (ex: Exception) {
            Log.e(TAG, "spawn failed: " + ex.message)
            nextSpawnAllowedAt = System.currentTimeMillis() + SPAWN_BACKOFF_MS
            null
        }
    }

    private fun startReader(s: Session) {
        Thread({
            try {
                s.process.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        s.lines.put(line)
                    }
                }
            } catch (ignored: Exception) {
            }
            s.lines.put(EOF)
        }, "KeepShell-reader").apply { isDaemon = true }.start()
    }

    private fun startStderrDrain(p: Process) {
        Thread({
            try {
                p.errorStream.bufferedReader().use { er ->
                    while (true) {
                        val line = er.readLine() ?: break
                        Log.w(TAG, line)
                    }
                }
            } catch (ignored: Exception) {
            }
        }, "KeepShell-stderr").apply { isDaemon = true }.start()
    }

    /**
     * Runs [cmd] and returns its trimmed stdout, or [ERROR] if the shell is
     * unavailable, died, or the command exceeded [timeoutMs].
     */
    fun doCmdSync(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        try {
            if (!lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "lock wait timed out")
                return ERROR
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return ERROR
        }
        try {
            return runLocked(cmd, timeoutMs)
        } finally {
            lock.unlock()
        }
    }

    private fun runLocked(cmd: String, timeoutMs: Long): String {
        val s = ensureSession() ?: return ERROR

        // The very first command of a root shell can sit behind the su
        // permission prompt, so give the user time to answer it.
        val effectiveTimeout =
            if (!firstCommandDone && rootMode) maxOf(timeoutMs, ROOT_GRANT_TIMEOUT_MS) else timeoutMs
        val wasFirstCommand = !firstCommandDone

        try {
            s.out.write(START_TAG_BYTES)
            s.out.write(cmd.toByteArray())
            s.out.write(END_TAG_BYTES)
            s.out.flush()
        } catch (ex: IOException) {
            return fail(s, timedOut = false, wasFirstCommand = wasFirstCommand)
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeout)
        val result = StringBuilder()
        var started = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    Log.e(TAG, "command timed out, killing shell")
                    return fail(s, timedOut = true, wasFirstCommand = wasFirstCommand)
                }
                val item = s.lines.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: continue // timed out: re-check deadline above
                if (item === EOF) return fail(s, timedOut = false, wasFirstCommand = wasFirstCommand)

                val line = item as String
                val endIdx = line.indexOf(END_TAG)
                if (endIdx >= 0) {
                    if (started) result.append(line, 0, endIdx)
                    firstCommandDone = true
                    return result.toString().trim()
                }
                val startIdx = line.indexOf(START_TAG)
                if (startIdx >= 0) {
                    result.setLength(0)
                    result.append(line, startIdx + START_TAG.length, line.length)
                    started = true
                } else if (started) {
                    result.append(line).append('\n')
                }
                // Lines before the start marker (e.g. su banners) are ignored.
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return fail(s, timedOut = false, wasFirstCommand = false)
        }
    }

    private fun fail(s: Session, timedOut: Boolean, wasFirstCommand: Boolean): String {
        // Only drop the session this command ran in (it may already have been
        // replaced by someone calling tryExit()).
        synchronized(stateLock) {
            if (session === s) session = null
        }
        s.kill()
        if (wasFirstCommand && !timedOut) {
            // su exited right away (root denied / not present): do not hammer
            // it with a new spawn on every metrics tick.
            nextSpawnAllowedAt = System.currentTimeMillis() + SPAWN_BACKOFF_MS
        }
        return ERROR
    }

    companion object {
        const val ERROR = "error"
        private const val TAG = "ThermalOverlayShell"

        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val LONG_TIMEOUT_MS = 60_000L
        private const val ROOT_GRANT_TIMEOUT_MS = 45_000L
        private const val LOCK_WAIT_MS = 90_000L
        private const val SPAWN_BACKOFF_MS = 5_000L

        private val EOF = Any()

        private const val START_TAG = "|SH>>|"
        private const val END_TAG = "|<<SH|"
        private val START_TAG_BYTES = "\necho '$START_TAG'\n".toByteArray()
        private val END_TAG_BYTES = "\necho '$END_TAG'\n".toByteArray()

        private const val CHECK_ROOT_CMD =
            "if [ \"$(id -u 2>/dev/null)\" = \"0\" ]; then echo success; else echo notroot; fi"
    }
}
