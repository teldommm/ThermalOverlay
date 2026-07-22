@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

/**
 * ListView adapter backing the process monitor: sorts by CPU, filters
 * Apps-only/All, resolves friendly app names (cached in SharedPreferences)
 * and icons (cached in memory via AppInfoLoader).
 *
 * Dropped the keyword-highlight and
 * MEM/PID sort-mode helpers — FloatTaskManager only ever exposes the
 * Apps/All filter and CPU sort, so those paths were unreachable here.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.model.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class AdapterProcessMini(
    private val context: Context,
    private var processes: ArrayList<ProcessInfo> = ArrayList(),
    private var filterMode: Int = FILTER_ANDROID
) : BaseAdapter() {
    private val appInfoLoader = AppInfoLoader(context, 100)
    private val androidIcon = context.getDrawable(R.drawable.process_android)
    private val linuxIcon = context.getDrawable(R.drawable.process_linux)

    companion object {
        const val FILTER_ALL = 1
        const val FILTER_ANDROID = 32
    }

    private val pm = context.packageManager
    private var list: ArrayList<ProcessInfo> = ArrayList()
    private val nameCache = context.getSharedPreferences("ProcessNameCache", Context.MODE_PRIVATE)

    private val androidProcessRegex = Regex(".*\\..*")
    private fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        return processInfo.command.contains("app_process") && processInfo.name.matches(androidProcessRegex)
    }

    init {
        applyFilterAndSort()
        if (processes.size > 0) loadLabels()
    }

    override fun getCount(): Int = list.size
    override fun getItem(position: Int): ProcessInfo = list[position]
    override fun getItemId(position: Int): Long = position.toLong()

    private fun applyFilterAndSort() {
        // Multiple threads of the same app (":process" suffix) get folded
        // into one row with their CPU summed — otherwise a heavy app can
        // occupy half the list as near-duplicate entries.
        val filtered = processes.filter {
            when (filterMode) {
                FILTER_ALL -> true
                FILTER_ANDROID -> isAndroidProcess(it)
                else -> true
            }
        }
        val groups = filtered.groupBy {
            if (it.name.contains(":") && isAndroidProcess(it)) {
                it.name.substring(0, it.name.indexOf(":"))
            } else {
                it.name
            }
        }
        val merged = groups.map { (_, group) ->
            val info = group.first()
            info.cpu = group.sumOf { it.cpu.toDouble() }.toFloat()
            info
        }.sortedByDescending { it.cpu }

        list = ArrayList(if (merged.size > 100) merged.subList(0, 100) else merged)
        notifyDataSetChanged()
    }

    fun updateFilterMode(filterMode: Int) {
        this.filterMode = filterMode
        applyFilterAndSort()
    }

    fun setList(processes: ArrayList<ProcessInfo>) {
        this.processes = processes
        applyFilterAndSort()
        loadLabels()
    }

    fun removeItem(position: Int) {
        list.removeAt(position)
        notifyDataSetChanged()
    }

    private fun loadIcon(imageView: ImageView, item: ProcessInfo) {
        if (imageView.tag == item.name) return

        if (isAndroidProcess(item)) {
            GlobalScope.launch(Dispatchers.IO) {
                val pkg = if (item.name.contains(":")) item.name.substring(0, item.name.indexOf(":")) else item.name
                val icon: Drawable? = try {
                    appInfoLoader.loadIcon(pkg).await()
                } catch (ex: Exception) {
                    null
                }
                imageView.post {
                    imageView.setImageDrawable(icon ?: androidIcon)
                    imageView.tag = item.name
                }
            }
        } else {
            imageView.setImageDrawable(linuxIcon)
            imageView.tag = item.name
        }
    }

    private fun loadLabels() {
        val editor = nameCache.edit()
        var changed = false
        for (item in processes) {
            if (isAndroidProcess(item)) {
                val pkg = if (item.name.contains(":")) item.name.substring(0, item.name.indexOf(":")) else item.name
                val cached = nameCache.getString(item.name, null)
                if (cached != null) {
                    item.friendlyName = cached
                } else {
                    item.friendlyName = try {
                        val app = pm.getApplicationInfo(pkg, 0)
                        "" + app.loadLabel(pm)
                    } catch (ex: Exception) {
                        pkg
                    }
                    editor.putString(item.name, item.friendlyName)
                    changed = true
                }
            } else {
                item.friendlyName = item.name
            }
        }
        if (changed) {
            editor.apply()
            notifyDataSetChanged()
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: View.inflate(context, R.layout.list_item_process_small, null)
        val processInfo = getItem(position)
        view.findViewById<TextView>(R.id.ProcessFriendlyName).text = processInfo.friendlyName
        view.findViewById<TextView>(R.id.ProcessCPU).text = String.format("%.1f%%", processInfo.cpu)
        loadIcon(view.findViewById(R.id.ProcessIcon), processInfo)
        return view
    }
}
