/**
 * Persists recorded framerate sessions to SQLite, and reads them back for
 * the history/stats screen (ActivityFpsChart).
 * FpsWatchStore, now with the query methods that screen needs restored.
 */
package com.thermaloverlay.overlay.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.thermaloverlay.overlay.model.FpsWatchSession

class FpsWatchStore(context: Context) : SQLiteOpenHelper(context, "fps_watch_log", null, DB_VERSION) {
    companion object {
        private const val DB_VERSION = 4

        // Columns added in v2, applied both to fresh installs (onCreate)
        // and existing DBs (onUpgrade) from the same list, so the two paths
        // can never drift apart.
        private val V2_COLUMNS = listOf(
            "cpu_temp REAL",
            "ddr_freq INTEGER",
            "current_ma REAL",
            "voltage REAL",
            "core_loads TEXT",
            "cluster_freqs TEXT",
            "core_cycles TEXT"
        )

        // v3: jank/big-jank counts and worst frame time for the tick, from
        // FrameStatsUtils (dumpsys gfxinfo framestats).
        private val V3_COLUMNS = listOf(
            "jank_count INTEGER",
            "big_jank_count INTEGER",
            "frame_time REAL"
        )

        // v4: GPU frequency (GpuLoadView is actually a dual-series chart
        // in the source — load% and frequency together).
        private val V4_COLUMNS = listOf(
            "gpu_freq INTEGER"
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "create table session(" +
                    "id INTEGER primary key, " +
                    "package_name text, " +
                    "time_begin INTEGER default(-1), " +
                    "time_end INTEGER default(-1)" +
                    ")"
            )
            db.execSQL(
                "create table fps_history(" +
                    "id INTEGER primary key AUTOINCREMENT, " +
                    "time INTEGER, " +
                    "session INTEGER, " +
                    "fps REAL, " +
                    "cpu_load REAL, " +
                    "gpu_load REAL, " +
                    "capacity INTEGER, " +
                    "temperature REAL" +
                    ")"
            )
            V2_COLUMNS.forEach { db.execSQL("alter table fps_history add column $it") }
            V3_COLUMNS.forEach { db.execSQL("alter table fps_history add column $it") }
            V4_COLUMNS.forEach { db.execSQL("alter table fps_history add column $it") }
        } catch (ex: Exception) {
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            V2_COLUMNS.forEach {
                try {
                    db.execSQL("alter table fps_history add column $it")
                } catch (ex: Exception) {
                    // Column already present (e.g. a partially-applied
                    // earlier upgrade) — ignore and keep going.
                }
            }
        }
        if (oldVersion < 3) {
            V3_COLUMNS.forEach {
                try {
                    db.execSQL("alter table fps_history add column $it")
                } catch (ex: Exception) {
                }
            }
        }
        if (oldVersion < 4) {
            V4_COLUMNS.forEach {
                try {
                    db.execSQL("alter table fps_history add column $it")
                } catch (ex: Exception) {
                }
            }
        }
    }

    fun createSession(packageName: String): Long {
        val time = System.currentTimeMillis()
        return try {
            val values = ContentValues().apply {
                put("id", time)
                put("package_name", packageName)
                put("time_begin", time)
            }
            writableDatabase.insert("session", null, values)
            time
        } catch (ex: Exception) {
            -1L
        }
    }

    fun addHistory(
        session: Long,
        fps: Float,
        cpuLoad: Double,
        gpuLoad: Double,
        capacity: Int,
        temperature: Double,
        cpuTemp: Double? = null,
        ddrFreq: Int? = null,
        currentMa: Double? = null,
        voltage: Double? = null,
        coreLoads: List<Int>? = null,
        clusterFreqs: List<Int>? = null,
        coreCycles: List<Int>? = null,
        jankCount: Int? = null,
        bigJankCount: Int? = null,
        frameTimeMs: Double? = null,
        gpuFreq: Int? = null
    ): Boolean {
        return try {
            val values = ContentValues().apply {
                put("time", System.currentTimeMillis())
                put("session", session)
                put("fps", fps)
                put("cpu_load", cpuLoad)
                put("gpu_load", gpuLoad)
                put("capacity", capacity)
                put("temperature", temperature)
                cpuTemp?.let { put("cpu_temp", it) }
                ddrFreq?.let { put("ddr_freq", it) }
                currentMa?.let { put("current_ma", it) }
                voltage?.let { put("voltage", it) }
                coreLoads?.let { put("core_loads", it.joinToString(",")) }
                clusterFreqs?.let { put("cluster_freqs", it.joinToString(",")) }
                coreCycles?.let { put("core_cycles", it.joinToString(",")) }
                jankCount?.let { put("jank_count", it) }
                bigJankCount?.let { put("big_jank_count", it) }
                frameTimeMs?.let { put("frame_time", it) }
                gpuFreq?.let { put("gpu_freq", it) }
            }
            writableDatabase.insert("fps_history", null, values) >= 0
        } catch (ex: Exception) {
            false
        }
    }

    // Most recent session first — the history screen shows the latest
    // recording by default.
    fun sessions(): ArrayList<FpsWatchSession> {
        val result = ArrayList<FpsWatchSession>()
        try {
            readableDatabase.rawQuery("select id, package_name, time_begin from session order by time_begin desc", null).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        FpsWatchSession(
                            sessionId = cursor.getLong(0),
                            packageName = cursor.getString(1),
                            beginTime = cursor.getLong(2)
                        )
                    )
                }
            }
        } catch (ex: Exception) {
        }
        return result
    }

    private fun floatColumn(column: String, sessionId: Long): ArrayList<Float> {
        val result = ArrayList<Float>()
        try {
            readableDatabase.rawQuery(
                "select $column from fps_history where session = ? order by id",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) result.add(cursor.getFloat(0))
            }
        } catch (ex: Exception) {
        }
        return result
    }

    fun sessionFpsData(sessionId: Long) = floatColumn("fps", sessionId)
    fun sessionTemperatureData(sessionId: Long) = floatColumn("temperature", sessionId)
    fun sessionCpuLoadData(sessionId: Long) = floatColumn("cpu_load", sessionId)
    fun sessionGpuLoadData(sessionId: Long) = floatColumn("gpu_load", sessionId)
    fun sessionCapacityData(sessionId: Long) = floatColumn("capacity", sessionId)
    fun sessionCpuTempData(sessionId: Long) = floatColumn("cpu_temp", sessionId)
    fun sessionDdrFreqData(sessionId: Long) = floatColumn("ddr_freq", sessionId)
    fun sessionCurrentData(sessionId: Long) = floatColumn("current_ma", sessionId)
    fun sessionVoltageData(sessionId: Long) = floatColumn("voltage", sessionId)
    fun sessionJankData(sessionId: Long) = floatColumn("jank_count", sessionId)
    fun sessionBigJankData(sessionId: Long) = floatColumn("big_jank_count", sessionId)
    fun sessionFrameTimeData(sessionId: Long) = floatColumn("frame_time", sessionId)
    fun sessionGpuFreqData(sessionId: Long) = floatColumn("gpu_freq", sessionId)

    // Raw rows for a CSV-per-tick column: one entry per tick, each the
    // comma-split values for that tick (core loads, cluster freqs, ...).
    // Ticks where the column is null/empty come back as an empty list
    // rather than being skipped, so tick alignment with the other columns
    // (e.g. for a shared time axis) is preserved.
    private fun csvColumn(column: String, sessionId: Long): List<List<Float>> {
        val result = ArrayList<List<Float>>()
        try {
            readableDatabase.rawQuery(
                "select $column from fps_history where session = ? order by id",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(0)
                    result.add(
                        if (raw.isNullOrEmpty()) emptyList()
                        else raw.split(",").mapNotNull { it.toFloatOrNull() }
                    )
                }
            }
        } catch (ex: Exception) {
        }
        return result
    }

    // Transposes a CSV-per-tick column into one series per index (core,
    // cluster, ...) — series[i] is the value at index i across every tick.
    // Ticks missing that index (core count changed mid-session, hotplug,
    // etc.) contribute nothing to that series rather than a fabricated 0,
    // so a chart drawing series[i] just sees fewer points for that index.
    private fun transposeSeries(rows: List<List<Float>>): List<List<Float>> {
        val seriesCount = rows.maxOfOrNull { it.size } ?: return emptyList()
        val series = List(seriesCount) { ArrayList<Float>() }
        for (row in rows) {
            for (i in row.indices) series[i].add(row[i])
        }
        return series
    }

    fun sessionCoreLoadSeries(sessionId: Long) = transposeSeries(csvColumn("core_loads", sessionId))
    fun sessionClusterFreqSeries(sessionId: Long) = transposeSeries(csvColumn("cluster_freqs", sessionId))
    fun sessionCoreCyclesSeries(sessionId: Long) = transposeSeries(csvColumn("core_cycles", sessionId))

    private fun aggregate(fn: String, sessionId: Long): Float {
        return try {
            readableDatabase.rawQuery(
                "select $fn(fps) from fps_history where session = ?",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                if (cursor.moveToNext()) cursor.getFloat(0) else 0f
            }
        } catch (ex: Exception) {
            0f
        }
    }

    fun sessionAvgFps(sessionId: Long) = aggregate("avg", sessionId)
    fun sessionMinFps(sessionId: Long) = aggregate("min", sessionId)
    fun sessionMaxFps(sessionId: Long) = aggregate("max", sessionId)

    fun deleteSession(sessionId: Long): Boolean {
        return try {
            writableDatabase.delete("session", "id = ?", arrayOf(sessionId.toString()))
            writableDatabase.delete("fps_history", "session = ?", arrayOf(sessionId.toString()))
            true
        } catch (ex: Exception) {
            false
        }
    }
}
