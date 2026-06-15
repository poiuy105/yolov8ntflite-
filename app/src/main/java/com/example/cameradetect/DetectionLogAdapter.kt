package com.example.cameradetect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DetectionLogAdapter : RecyclerView.Adapter<DetectionLogAdapter.ViewHolder>() {

    private val logs = ArrayDeque<DetectionLogEntry>()
    private val maxSize = 100

    fun addLog(entry: DetectionLogEntry) {
        if (logs.isNotEmpty() && logs.first().personCount == entry.personCount) {
            return
        }
        logs.addFirst(entry)
        while (logs.size > maxSize) {
            logs.removeLast()
        }
        notifyItemInserted(0)
    }

    fun getLatest(): DetectionLogEntry? = logs.firstOrNull()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs.elementAt(position)
        holder.tvTime.text = log.timestamp
        holder.tvCount.text = "检测到 ${log.personCount} 人"
    }

    override fun getItemCount(): Int = logs.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime: TextView = itemView.findViewById(android.R.id.text1)
        val tvCount: TextView = itemView.findViewById(android.R.id.text2)
    }
}
