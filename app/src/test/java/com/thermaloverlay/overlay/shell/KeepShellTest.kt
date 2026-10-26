package com.thermaloverlay.overlay.shell

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a plain `sh` (rootMode = false), so no root is needed. */
class KeepShellTest {
    private val shell = KeepShell(rootMode = false)

    @After
    fun tearDown() = shell.tryExit()

    @Test
    fun returnsCommandOutput() {
        assertEquals("hello", shell.doCmdSync("echo hello"))
    }

    @Test
    fun keepsMultilineOutputAndTrims() {
        assertEquals("a\nb\nc", shell.doCmdSync("echo a; echo b; echo c"))
    }

    @Test
    fun commandWithoutTrailingNewline() {
        assertEquals("x", shell.doCmdSync("printf x"))
    }

    @Test
    fun reusesOneProcessForManyCommands() {
        repeat(20) { assertEquals("$it", shell.doCmdSync("echo $it")) }
        assertEquals(1, shell.spawnCount)
    }

    @Test
    fun concurrentFirstUseSpawnsExactlyOneProcess() {
        // Regression: two threads hitting a not-yet-started shell used to
        // each spawn a process, leaking one.
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val results = (0 until threads).map { i ->
            pool.submit<String> {
                ready.countDown()
                go.await()
                shell.doCmdSync("echo $i")
            }
        }
        ready.await()
        go.countDown()
        results.forEachIndexed { i, f -> assertEquals("$i", f.get(20, TimeUnit.SECONDS)) }
        pool.shutdown()
        assertEquals(1, shell.spawnCount)
    }

    @Test
    fun hungCommandIsKilledAndShellRecovers() {
        val t0 = System.currentTimeMillis()
        val r = shell.doCmdSync("sleep 30; echo late", timeoutMs = 700)
        val took = System.currentTimeMillis() - t0
        assertEquals(KeepShell.ERROR, r)
        assertTrue("watchdog should fire quickly, took $took ms", took < 5_000)

        // The next command transparently gets a fresh shell.
        assertEquals("ok", shell.doCmdSync("echo ok"))
        assertEquals(2, shell.spawnCount)
    }

    @Test
    fun deadShellNeverReturnsPreviousOutput() {
        // Regression: when the shell died, the old implementation returned the
        // cached output of the PREVIOUS command as if it were the result.
        assertEquals("first", shell.doCmdSync("echo first"))
        assertEquals(KeepShell.ERROR, shell.doCmdSync("exit 0"))
        assertEquals("second", shell.doCmdSync("echo second"))
    }

    @Test
    fun interruptedCallerGetsErrorNotException() {
        // Regression: unlock() in `finally` after an interrupted
        // lockInterruptibly() threw IllegalMonitorStateException.
        var result: String? = null
        val t = Thread {
            Thread.currentThread().interrupt()
            result = shell.doCmdSync("echo hi")
        }
        t.start()
        t.join(5_000)
        assertEquals(KeepShell.ERROR, result)
        // And the shell is still fine afterwards.
        assertEquals("fine", shell.doCmdSync("echo fine"))
    }

    @Test
    fun idleState() {
        assertTrue(shell.isIdle)
        val started = CountDownLatch(1)
        val t = Thread {
            started.countDown()
            shell.doCmdSync("sleep 1")
        }
        t.start()
        started.await()
        Thread.sleep(300)
        assertFalse(shell.isIdle)
        t.join()
        assertTrue(shell.isIdle)
    }

    @Test
    fun exitIfIdleLeavesBusyShellAlone() {
        val t = Thread { shell.doCmdSync("sleep 1; echo done") }
        t.start()
        Thread.sleep(300)
        shell.tryExitIfIdle() // busy: must not kill it
        t.join()
        assertEquals("alive", shell.doCmdSync("echo alive"))
        assertEquals(1, shell.spawnCount)
    }

    @Test
    fun resetBackoffLetsAUserRetryStartSuImmediately() {
        // A "su" that exits at once, like a denied root request.
        val denied = KeepShell(rootMode = true, command = "false")
        assertEquals(KeepShell.ERROR, denied.doCmdSync("echo x"))
        assertEquals(1, denied.spawnCount)

        // Within the pause no new process is started...
        assertEquals(KeepShell.ERROR, denied.doCmdSync("echo x"))
        assertEquals(1, denied.spawnCount)

        // ...unless the user explicitly asked to check again.
        denied.resetBackoff()
        assertEquals(KeepShell.ERROR, denied.doCmdSync("echo x"))
        assertEquals(2, denied.spawnCount)
    }
}
