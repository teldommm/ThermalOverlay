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
        private const val DB_VERSION = 1
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
        } catch (ex: Exception) {
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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

    fun addHistory(session: Long, fps: Float, cpuLoad: Double, gpuLoad: Double, capacity: Int, temperature: Double): Boolean {
        return try {
            val values = ContentValues().apply {
                put("time", System.currentTimeMillis())
                put("session", session)
                put("fps", fps)
                put("cpu_load", cpuLoad)
                put("gpu_load", gpuLoad)
                put("capacity", capacity)
                put("temperature", temperature)
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
