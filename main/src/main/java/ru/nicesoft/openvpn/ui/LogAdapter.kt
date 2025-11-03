package ru.nicesoft.openvpn.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.LogItem
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(context: Context) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val appContext = context.applicationContext
    private val entries = mutableListOf<LogItem>()
    private val timeFormatter: DateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())

    fun setItems(items: List<LogItem>) {
        entries.clear()
        val toAdd = if (items.size > MAX_ENTRIES) items.takeLast(MAX_ENTRIES) else items
        entries.addAll(toAdd)
        notifyDataSetChanged()
    }

    fun addLogItem(item: LogItem) {
        if (entries.size >= MAX_ENTRIES) {
            entries.removeAt(0)
        }
        entries.add(item)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size

    override fun getItem(position: Int): LogItem = entries[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val viewHolder: ViewHolder
        val view = if (convertView == null) {
            val inflated = inflater.inflate(R.layout.item_log_entry, parent, false)
            viewHolder = ViewHolder(
                timeView = inflated.findViewById(R.id.log_time),
                messageView = inflated.findViewById(R.id.log_message),
            )
            inflated.tag = viewHolder
            inflated
        } else {
            viewHolder = convertView.tag as ViewHolder
            convertView
        }

        val logItem = getItem(position)
        viewHolder.timeView.text = timeFormatter.format(Date(logItem.logtime))
        viewHolder.messageView.text = logItem.getString(appContext)

        return view
    }

    private data class ViewHolder(
        val timeView: TextView,
        val messageView: TextView,
    )

    companion object {
        private const val MAX_ENTRIES = 1000
    }
}
