package com.neuralbridge.companion.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.neuralbridge.companion.R
import com.neuralbridge.companion.log.CommandLog
import java.text.SimpleDateFormat
import java.util.*

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var entries: List<CommandLog.Entry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timestamp: TextView = view.findViewById(R.id.logTimestamp)
        val command: TextView = view.findViewById(R.id.logCommand)
        val latency: TextView = view.findViewById(R.id.logLatency)
        val status: TextView = view.findViewById(R.id.logStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.timestamp.text = timeFormat.format(Date(entry.timestamp))
        holder.command.text = entry.command

        val ms = entry.latencyMs
        holder.latency.text = "${ms}ms"
        holder.latency.setTextColor(holder.itemView.context.getColor(
            when {
                ms < 20 -> R.color.success
                ms < 50 -> R.color.success
                ms < 100 -> R.color.warning
                else -> R.color.status_error
            }
        ))

        if (entry.success) {
            holder.status.text = "✓"
            holder.status.setTextColor(holder.itemView.context.getColor(R.color.success))
        } else {
            holder.status.text = "✗"
            holder.status.setTextColor(holder.itemView.context.getColor(R.color.status_error))
        }
    }

    override fun getItemCount() = entries.size

    fun updateEntries(newEntries: List<CommandLog.Entry>) {
        entries = newEntries
        notifyDataSetChanged()
    }
}
