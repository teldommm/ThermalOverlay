/**
 * RecyclerView adapter for the session-history list in ActivityFpsChart.
 * Doesn't include a keyword-highlight search feature, since nothing here
 * exposes a search box for it (ActivityFpsChart never sets it either).
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.model.FpsWatchSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdapterSessions(
    private val context: Context,
    private val list: ArrayList<FpsWatchSession>
) : RecyclerView.Adapter<AdapterSessions.ViewHolder>() {

    fun getItem(position: Int): FpsWatchSession = list[position]

    override fun getItemCount(): Int = list.size

    fun removeItem(position: Int) {
        list.removeAt(position)
        notifyDataSetChanged()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val itemIcon: ImageView = view.findViewById(R.id.ItemIcon)
        val itemTitle: TextView = view.findViewById(R.id.ItemTitle)
        val itemDesc: TextView = view.findViewById(R.id.ItemDesc)
        val itemDelete: ImageButton = view.findViewById(R.id.ItemDelete)
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int)
    }

    private var onItemClickListener: OnItemClickListener? = null
    private var onItemDeleteClickListener: OnItemClickListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        onItemClickListener = listener
    }

    fun setOnItemDeleteClickListener(listener: OnItemClickListener?) {
        onItemDeleteClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.list_item_fps_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.itemView.setOnClickListener { onItemClickListener?.onItemClick(position) }
        holder.itemDelete.setOnClickListener { onItemDeleteClickListener?.onItemClick(position) }

        val item = getItem(position)
        holder.itemTitle.text = item.appName
        holder.itemIcon.setImageDrawable(item.appIcon)
        holder.itemDesc.text = dateFormat.format(Date(item.beginTime))
    }
}
