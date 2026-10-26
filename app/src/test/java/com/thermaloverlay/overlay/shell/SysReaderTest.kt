package com.thermaloverlay.overlay.shell

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SysReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val shell = KeepShell(rootMode = false)
    private lateinit var original: () -> KeepShell

    @Before
    fun setUp() {
        original = SysReader.shellProvider
        SysReader.shellProvider = { shell }
    }

    @After
    fun tearDown() {
        SysReader.shellProvider = original
        shell.tryExit()
    }

    @Test
    fun readsFileDirectly() {
        val f = tmp.newFile("a").apply { writeText("42\n") }
        assertEquals("42", SysReader.read(f.path))
        assertEquals(0, shell.spawnCount) // no process needed
    }

    @Test
    fun missingNodeReadsEmptyButShellFailureIsNull() {
        assertEquals("", SysReader.read(File(tmp.root, "nope").path))
        assertEquals("", SysReader.readOrNull(File(tmp.root, "nope").path))
    }

    @Test
    fun readManyBatchesIntoOneShellCall() {
        val a = tmp.newFile("a").apply { writeText("1") }
        val missing1 = File(tmp.root, "m1").path
        val missing2 = File(tmp.root, "m2").path
        val values = SysReader.readMany(listOf(a.path, missing1, missing2))
        assertEquals(listOf("1", "", ""), values)
        assertEquals(1, shell.spawnCount)
    }

    @Test
    fun parseSectionsHandlesMultilineAndMissingTrailingNewline() {
        val out = "@@SEC:0\nline1\nline2\n\n@@SEC:2\nx\n@@SEC:5\n"
        val m = SysReader.parseSections(out)
        assertEquals("line1\nline2", m[0])
        assertEquals("x", m[2])
        assertEquals("", m[5])
        assertEquals(null, m[1])
    }

    @Test
    fun shQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", SysReader.shQuote("it's"))
    }

    @Test
    fun writeFileSurvivesQuotesAndReportsFailure() {
        val f = tmp.newFile("w")
        assertTrue(RootFile.writeFile(f.path, "it's \"ok\" \$HOME"))
        assertEquals("it's \"ok\" \$HOME", f.readText().trim())
        assertEquals(false, RootFile.writeFile(File(tmp.root, "no/such/dir/x").path, "1"))
    }

    @Test
    fun probesAreTriState() {
        val f = tmp.newFile("p")
        assertEquals(true, RootFile.fileExistsOrNull(f.path))
        assertEquals(false, RootFile.fileExistsOrNull(File(tmp.root, "zzz").path))
        assertEquals(true, RootFile.dirExistsOrNull(tmp.root.path))
        // A dead/unavailable shell must read as "unknown", never "absent".
        val broken = KeepShell(rootMode = false, command = "/definitely/not/a/shell")
        SysReader.shellProvider = { broken }
        assertEquals(null, RootFile.fileExistsOrNull(File(tmp.root, "zzz").path))
        assertEquals(null, SysReader.readOrNull(File(tmp.root, "zzz").path))
        assertEquals("", SysReader.read(File(tmp.root, "zzz").path))
    }
}
